package dev.mibox.neon.data

import androidx.compose.runtime.Immutable
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import kotlin.math.roundToInt
import dev.mibox.neon.i18n.I18n
import dev.mibox.neon.i18n.t

/** What kind of story the lead ("manşet") tells; the UI may style kinds differently (a live dot, a progress bar…). */
enum class HeadlineKind {
    MATCH_LIVE, MATCH_RESULT, MATCH_TODAY, MATCH_UPCOMING, CONTINUE, PREDICTED, LAST_USED,

    /** A focused app that is neither predicted nor last used (only from `headlineFor(app)`). */
    APP,
    GREETING,
}

/** What pressing OK on a headline does; hand it to `LauncherViewModel.act`. */
sealed interface HeadlineTarget {
    data class OpenApp(val pkg: String) : HeadlineTarget
    data class OpenWatch(val item: WatchItem) : HeadlineTarget
    data object OpenMatch : HeadlineTarget
    data object None : HeadlineTarget
}

/** One lead story. [deck] and [action] may be empty; they are never filled with made-up text. */
@Immutable
data class Headline(
    val kind: HeadlineKind,
    val kicker: String,
    val title: String,
    val deck: String,
    val action: String,
    val target: HeadlineTarget,
) {
    /** Watched fraction 0..1 for a progress bar; null when the headline is not a Watch Next item or the app did not say. */
    val progress: Float? get() = (target as? HeadlineTarget.OpenWatch)?.item?.progress
}

/** What the engine needs to know about installed apps; the ViewModel supplies it. */
interface HeadlineApps {
    /** Display name, or null when the app is not installed. */
    fun label(pkg: String): String?

    /** Epoch millis the app was last on screen, if the usage history knows. */
    fun lastSeen(pkg: String): Long?

    /** Turkish category name ("Oyun", "Video"…), if the app declares one. */
    fun category(pkg: String): String?
}

/** Pure headline logic (no Android state), so both themes and the tests share it. */
object HeadlineEngine {
    /** A match is assumed to end about 2 h after kickoff (see [MatchConfig.FINISHED_KEEP_MS]). */
    private const val MATCH_LENGTH_MS = 2L * 60 * 60 * 1000
    private const val RESULT_FRESH_MS = 3L * 60 * 60 * 1000
    private const val NEAR_END = 0.7f
    private const val UPCOMING_DAYS = 3L

    // Day and month names in the box's language ("Pazartesi" / "Monday")
    private fun weekday(at: ZonedDateTime) = at.dayOfWeek.getDisplayName(TextStyle.FULL, I18n.locale)
    private fun month(at: ZonedDateTime) = at.month.getDisplayName(TextStyle.FULL, I18n.locale)
    private fun monthShort(at: ZonedDateTime) = at.month.getDisplayName(TextStyle.SHORT, I18n.locale)

    /**
     * @param match the match card state (live / recent result / next fixture), null when disabled
     * @param upcoming the next fixture regardless of the card, so a stale result doesn't hide it
     * @param lights true when match-day light effects will really run
     */
    fun compute(
        now: Long,
        match: MatchState?,
        upcoming: MatchState.Upcoming?,
        watch: List<WatchItem>,
        hero: HeroState?,
        lights: Boolean,
        apps: HeadlineApps,
    ): Headline {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        if (match is MatchState.Live) return live(match)
        if (match is MatchState.Finished && now - match.kickoffMs <= MATCH_LENGTH_MS + RESULT_FRESH_MS) return result(match)

        val next: MatchState? = (match as? MatchState.Upcoming) ?: upcoming
        val nextDays = next?.let { ChronoUnit.DAYS.between(today, it.kickoff.toLocalDate()) }
        if (next != null && nextDays != null && nextDays <= 0) return scheduled(next, HeadlineKind.MATCH_TODAY, now, lights)

        val installed = watch.filter { apps.label(it.pkg) != null }
        val pick = installed.firstOrNull { (it.progress ?: 0f) >= NEAR_END } ?: installed.firstOrNull()
        if (pick != null) return continueWatching(pick, apps, now)

        if (hero?.reason == HeroReason.Predicted) {
            return Headline(
                HeadlineKind.PREDICTED, t("Genelde bu saatte"), hero.app.label,
                appDeck(hero.app.pkg, apps, now), t("Aç"), HeadlineTarget.OpenApp(hero.app.pkg),
            )
        }

        if (next != null && nextDays != null && nextDays in 1..UPCOMING_DAYS) {
            return scheduled(next, HeadlineKind.MATCH_UPCOMING, now, lights = false)
        }

        if (hero?.reason == HeroReason.Resume) return lastUsed(hero.app, apps, now)

        return greeting(now)
    }

    /** The floor: always available. */
    fun greeting(now: Long): Headline {
        val at = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val kicker = when (at.hour) {
            in 5..10 -> t("Günaydın")
            in 11..17 -> t("İyi günler")
            in 18..21 -> t("İyi akşamlar")
            else -> t("İyi geceler")
        }
        val deck = t("{0} {1} {2}", at.dayOfMonth, month(at), weekday(at))
        return Headline(HeadlineKind.GREETING, kicker, "Neon", deck, "", HeadlineTarget.None)
    }

    /** A Watch Next item as the lead; [resume] forces the "Kaldığın yerden" wording. */
    fun continueWatching(item: WatchItem, apps: HeadlineApps, now: Long, resume: Boolean = true): Headline {
        val app = apps.label(item.pkg) ?: item.pkg.substringAfterLast('.')
        val pct = item.progress?.let { (it * 100).roundToInt().coerceIn(0, 100) }?.takeIf { it > 0 }
        val episodeNo = episodeLabel(item)
        val deck = listOfNotNull(
            episodeNo ?: item.episodeTitle?.takeIf { it.isNotBlank() },
            pct?.let { t("%{0} izlendi", it) },
        ).joinToString(" · ")
        return Headline(
            HeadlineKind.CONTINUE,
            if (resume) t("Kaldığın yerden · {0}", app) else app,
            item.title, deck, t("Devam et"), HeadlineTarget.OpenWatch(item),
        )
    }

    /** A focused app in the same format. */
    fun forApp(app: AppEntry, kind: HeadlineKind, apps: HeadlineApps, now: Long): Headline {
        val kicker = when (kind) {
            HeadlineKind.PREDICTED -> t("Genelde bu saatte")
            HeadlineKind.LAST_USED -> t("Son açılan")
            else -> t("Uygulama")
        }
        return Headline(kind, kicker, app.label, appDeck(app.pkg, apps, now), t("Aç"), HeadlineTarget.OpenApp(app.pkg))
    }

    private fun lastUsed(app: AppEntry, apps: HeadlineApps, now: Long) = forApp(app, HeadlineKind.LAST_USED, apps, now)

    private fun live(m: MatchState.Live): Headline {
        val deck = listOf(m.clock, m.status, m.competition).filter { it.isNotBlank() }.joinToString(" · ")
        return Headline(
            HeadlineKind.MATCH_LIVE, t("SON DAKİKA"), scoreLine(m, m.gsScore, m.oppScore),
            deck, t("Maçı aç"), HeadlineTarget.OpenMatch,
        )
    }

    private fun result(m: MatchState.Finished): Headline {
        val verdict = when (m.result) {
            MatchResult.WIN -> t("Galibiyet")
            MatchResult.DRAW -> t("Beraberlik")
            MatchResult.LOSS -> t("Mağlubiyet")
        }
        return Headline(
            HeadlineKind.MATCH_RESULT, t("Maç sonucu"), scoreLine(m, m.gsScore, m.oppScore),
            listOf(verdict, m.competition).filter { it.isNotBlank() }.joinToString(" · "),
            t("Maçı aç"), HeadlineTarget.OpenMatch,
        )
    }

    private fun scheduled(m: MatchState, kind: HeadlineKind, now: Long, lights: Boolean): Headline {
        val ko = m.kickoff
        val today = Instant.ofEpochMilli(now).atZone(ko.zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, ko.toLocalDate())
        val whenText = when {
            days <= 0L -> if (ko.hour >= 17) t("Bu akşam") else t("Bugün")
            days == 1L -> t("Yarın")
            else -> weekday(ko)
        }
        val word = if (isDerby(m)) t("derbi") else t("maç")
        val deck = listOfNotNull(
            m.competition.takeIf { it.isNotBlank() },
            if (m.home) t("İç saha") else t("Deplasman"),
            t("Maç günü ışıkları açık").takeIf { lights && kind == HeadlineKind.MATCH_TODAY },
        ).joinToString(" · ")
        return Headline(
            kind, t("{0} {1}", whenText, word), "${vs(m)}, ${"%02d:%02d".format(ko.hour, ko.minute)}",
            deck, t("Maçı aç"), HeadlineTarget.OpenMatch,
        )
    }

    private fun oppShort(m: MatchState) = m.opponentShort.ifBlank { m.opponent }

    /** "GS – FEN" with the home side first. */
    private fun vs(m: MatchState): String =
        if (m.home) "${MatchConfig.Team.shortName} – ${oppShort(m)}" else "${oppShort(m)} – ${MatchConfig.Team.shortName}"

    /** "GS 2–1 FEN" with the home side first, from the followed team's scores. */
    private fun scoreLine(m: MatchState, gs: Int, opp: Int): String =
        if (m.home) "${MatchConfig.Team.shortName} $gs–$opp ${oppShort(m)}" else "${oppShort(m)} $opp–$gs ${MatchConfig.Team.shortName}"

    /** Only Fenerbahçe and Beşiktaş count as a derby; ESPN's spelling may lack the Turkish letters. */
    private fun isDerby(m: MatchState): Boolean {
        val n = Normalizer.normalize(m.opponent, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace('ı', 'i')
        val short = m.opponentShort.uppercase(java.util.Locale("tr"))
        return "fenerbah" in n || "besikta" in n || short == "FB" || short == "FEN" || short == "BJK"
    }

    private fun episodeLabel(item: WatchItem): String? {
        fun num(s: String?) = s?.trim()?.toIntOrNull()
        val s = num(item.season)
        val e = num(item.episode)
        return when {
            s != null && e != null -> t("{0}. Sezon {1}. Bölüm", s, e)
            e != null -> t("{0}. Bölüm", e)
            else -> null
        }
    }

    /** "Son açılış: dün 21:40" when known, else the category, else empty. */
    fun appDeck(pkg: String, apps: HeadlineApps, now: Long): String {
        val seen = apps.lastSeen(pkg)
        if (seen != null && seen > 0) return lastOpenedPrefix() + whenText(seen, now)
        return apps.category(pkg).orEmpty()
    }

    private fun whenText(ms: Long, now: Long): String {
        val at: ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(at.zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
        val time = "%02d:%02d".format(at.hour, at.minute)
        return when {
            days <= 0L -> t("bugün {0}", time)
            days == 1L -> t("dün {0}", time)
            days <= 6L -> "${weekday(at)} $time"
            else -> "${at.dayOfMonth} ${monthShort(at)}"
        }
    }

    /** "Son açılış: " in the box's language; the Gazete index looks for it to tell app decks apart. */
    fun lastOpenedPrefix(): String = t("Son açılış: ")
}
