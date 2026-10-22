package dev.mibox.neon.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The team the match-day feature follows. Change this one value to follow another club. */
@Immutable
data class TeamSpec(
    /** ESPN team id. */
    val espnId: String,
    val name: String,
    val shortName: String,
    val primary: Color,
    val secondary: Color,
)

/** A competition ESPN's public API covers, by league slug. */
@Immutable
data class Competition(val slug: String, val label: String)

object MatchConfig {
    val Team = TeamSpec("432", "Galatasaray", "GS", Color(0xFFA90432), Color(0xFFFDB912))

    /** Checked against ESPN: the Turkish Cup (Ziraat Türkiye Kupası) has no ESPN league, so it is absent. */
    val Competitions = listOf(
        Competition("tur.1", "Süper Lig"),
        Competition("uefa.champions", "Şampiyonlar Ligi"),
        Competition("uefa.europa", "UEFA Avrupa Ligi"),
        Competition("uefa.europa.conf", "Konferans Ligi"),
    )

    /** Choices for the broadcast delay setting, in seconds. */
    val DelayOptions = listOf(0, 15, 30, 45, 60, 90)
    const val DEFAULT_DELAY_S = 30

    /** The newest finished fixture is cached this long, for the "last result" line. */
    const val LAST_RESULT_KEEP_MS = 14L * 24 * 60 * 60 * 1000

    /** The alarm chain starts this long before kickoff… */
    const val LEAD_MS = 5L * 60 * 1000

    /** …and gives up this long after it (extra time and penalties included). */
    const val WINDOW_MS = 180L * 60 * 1000

    /** A finished match stays on the card for about 12 h after the final whistle (kickoff + ~2 h). */
    const val FINISHED_KEEP_MS = 14L * 60 * 60 * 1000

    /** Fixtures are refetched at most this often while home is visible. */
    const val FIXTURES_TTL_MS = 6L * 60 * 60 * 1000

    /** The chain is armed for a match this close. */
    const val ARM_AHEAD_MS = 24L * 60 * 60 * 1000
}

enum class MatchPhase { PRE, LIVE, POST, OFF }

enum class MatchResult { WIN, DRAW, LOSS }

/** What the light effects can play. */
enum class MatchEffectKind { KICKOFF, GOAL, WIN }

/** Raw fixture as ESPN reports it. Scores are Galatasaray's ([gs]) and the opponent's ([opp]). */
@Immutable
data class Fixture(
    val eventId: String,
    val slug: String,
    val competition: String,
    val kickoffMs: Long,
    val home: Boolean,
    val opponent: String,
    val opponentShort: String,
    val phase: MatchPhase,
    val statusName: String,
    val clock: String,
    val gs: Int?,
    val opp: Int?,
)

/** What the match card shows. Scores are always from the followed team's point of view. */
sealed interface MatchState {
    val opponent: String
    val opponentShort: String
    val competition: String
    val kickoffMs: Long

    /** Kickoff in the box's own time zone. */
    val kickoff: ZonedDateTime get() = Instant.ofEpochMilli(kickoffMs).atZone(ZoneId.systemDefault())

    /** True when the followed team plays at home. */
    val home: Boolean

    @Immutable
    data class Upcoming(
        override val opponent: String,
        override val opponentShort: String,
        override val competition: String,
        override val kickoffMs: Long,
        override val home: Boolean,
    ) : MatchState

    /** [clock] is ESPN's display clock ("63'", "45'+2'"), [status] a Turkish phase name ("2. Yarı"). */
    @Immutable
    data class Live(
        override val opponent: String,
        override val opponentShort: String,
        override val competition: String,
        override val kickoffMs: Long,
        override val home: Boolean,
        val gsScore: Int,
        val oppScore: Int,
        val clock: String,
        val status: String,
    ) : MatchState

    @Immutable
    data class Finished(
        override val opponent: String,
        override val opponentShort: String,
        override val competition: String,
        override val kickoffMs: Long,
        override val home: Boolean,
        val gsScore: Int,
        val oppScore: Int,
        val result: MatchResult,
    ) : MatchState
}

/** Ready-made states to preview the card on the emulator (`vm.simulateMatch(MatchSamples.live())`). */
object MatchSamples {
    private val now get() = System.currentTimeMillis()

    fun upcoming() = MatchState.Upcoming("Fenerbahçe", "FEN", "Süper Lig", now + 3 * 3_600_000L, home = true)

    fun live() = MatchState.Live("Fenerbahçe", "FEN", "Süper Lig", now - 63 * 60_000L, true, 2, 1, "63'", "2. Yarı")

    fun finished() = MatchState.Finished("Fenerbahçe", "FEN", "Süper Lig", now - 3 * 3_600_000L, true, 3, 1, MatchResult.WIN)
}

/** A snapshot from the scoreboard: enough to tell what changed since the previous poll. */
data class LiveSnapshot(val phase: MatchPhase, val statusName: String, val clock: String, val gs: Int?, val opp: Int?)

/**
 * ESPN's public site API (no key). Kept behind this one object so the source can be swapped:
 * everything else only sees [Fixture] and [LiveSnapshot].
 *
 *  - fixtures: `site.api.espn.com/apis/site/v2/sports/soccer/{slug}/teams/{id}/schedule` (past) and
 *    `...schedule?fixture=true` (upcoming), one pair per competition in [MatchConfig.Competitions];
 *  - live score: `.../soccer/{slug}/scoreboard?dates=YYYYMMDD` (one ~10 KB gzip response).
 */
object MatchSource {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"

    /** Past and upcoming fixtures of every covered competition, deduplicated and sorted by kickoff. */
    fun fixtures(): List<Fixture> {
        val out = LinkedHashMap<String, Fixture>()
        var ok = 0
        var failure: Throwable? = null
        for (c in MatchConfig.Competitions) {
            for (q in listOf("", "?fixture=true")) {
                runCatching { events(httpGet("$BASE/${c.slug}/teams/${MatchConfig.Team.espnId}/schedule$q"), c) }
                    .onSuccess { list ->
                        ok++
                        for (f in list) {
                            val old = out[f.eventId]
                            // The same event can be in both lists; keep the one that knows more
                            if (old == null || f.phase != MatchPhase.PRE) out[f.eventId] = f
                        }
                    }
                    .onFailure { failure = it }
            }
        }
        if (ok == 0) throw failure ?: IllegalStateException("no fixtures")
        return out.values.sortedBy { it.kickoffMs }
    }

    /** Current state of one event, or null when ESPN doesn't list it for that day. */
    fun live(slug: String, eventId: String, kickoffMs: Long): LiveSnapshot? {
        val comp = MatchConfig.Competitions.firstOrNull { it.slug == slug } ?: return null
        for (shift in listOf(0L, -DAY_MS)) {
            // BASIC_ISO_DATE would append the offset ("20261021Z"), which ESPN rejects with HTTP 400
            val day = DAY_FORMAT.format(Instant.ofEpochMilli(kickoffMs + shift).atZone(ZoneOffset.UTC))
            val events = JSONObject(httpGet("$BASE/$slug/scoreboard?dates=$day")).optJSONArray("events") ?: continue
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                if (e.optString("id") != eventId) continue
                val f = parse(e, comp) ?: continue
                return LiveSnapshot(f.phase, f.statusName, f.clock, f.gs, f.opp)
            }
        }
        return null
    }

    private fun events(body: String, comp: Competition): List<Fixture> {
        val arr = JSONObject(body).optJSONArray("events") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it), comp) }
    }

    /** Works for both schedule events and scoreboard events. */
    private fun parse(e: JSONObject, comp: Competition): Fixture? {
        val c = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val teams = c.optJSONArray("competitors") ?: return null
        var us: JSONObject? = null
        var them: JSONObject? = null
        for (i in 0 until teams.length()) {
            val t = teams.getJSONObject(i)
            if (t.optJSONObject("team")?.optString("id") == MatchConfig.Team.espnId || t.optString("id") == MatchConfig.Team.espnId) us = t else them = t
        }
        if (us == null || them == null) return null
        val status = c.optJSONObject("status") ?: e.optJSONObject("status")
        val type = status?.optJSONObject("type")
        val name = type?.optString("name").orEmpty()
        val phase = when {
            "POSTPONED" in name || "CANCEL" in name || "ABANDON" in name -> MatchPhase.OFF
            type?.optString("state") == "in" -> MatchPhase.LIVE
            type?.optString("state") == "post" -> MatchPhase.POST
            else -> MatchPhase.PRE
        }
        val opp = them.optJSONObject("team")
        val kickoff = runCatching {
            // ESPN writes "2026-10-26T18:30Z" (no seconds)
            LocalDateTime.parse(e.getString("date").removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull() ?: return null
        return Fixture(
            eventId = e.getString("id"),
            slug = comp.slug,
            competition = comp.label,
            kickoffMs = kickoff,
            home = us.optString("homeAway") == "home",
            opponent = turkishName(opp?.optString("displayName").orEmpty().ifEmpty { "Rakip" }),
            opponentShort = opp?.optString("abbreviation").orEmpty(),
            phase = phase,
            statusName = name,
            clock = status?.optString("displayClock").orEmpty(),
            gs = score(us),
            opp = score(them),
        )
    }

    /** The scoreboard sends "2", the schedule sends {"displayValue":"2",...}. */
    private fun score(c: JSONObject): Int? {
        val s = c.opt("score")
        return when (s) {
            is JSONObject -> s.optString("displayValue").toIntOrNull()
            is String -> s.toIntOrNull()
            is Number -> s.toInt()
            else -> null
        }
    }

    private val TurkishNames = listOf(
        "Fenerbahçe", "Beşiktaş", "Başakşehir", "Göztepe", "Kasımpaşa", "Eyüpspor", "Çaykur Rizespor",
        "Gençlerbirliği", "Fatih Karagümrük", "Kocaelispor", "Gaziantep FK", "Kayserispor", "Konyaspor",
        "Samsunspor", "Alanyaspor", "Antalyaspor", "Trabzonspor", "Sivasspor", "Hatayspor", "Adana Demirspor",
        "Ankaragücü", "Bodrum FK", "Pendikspor", "İstanbulspor",
    ).associateBy { fold(it) }

    /** Lower-case ASCII form used to match ESPN's spelling ("Istanbul Basaksehir") against [TurkishNames]. */
    private fun fold(name: String): String =
        java.text.Normalizer.normalize(name.replace('ı', 'i').replace('İ', 'I'), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT).trim()

    /** Proper Turkish spelling of a known club name; unknown names are returned unchanged. */
    fun turkishName(name: String): String {
        val key = fold(name)
        TurkishNames[key]?.let { return it }
        // ESPN prefixes some clubs ("Istanbul Basaksehir", "Caykur Rizespor"): match on the distinctive part
        if (key == "istanbul basaksehir" || key == "medipol basaksehir" || key == "basaksehir fk") return "Başakşehir"
        return name
    }

    /** Turkish name of a live phase. */
    fun statusText(statusName: String): String = when {
        "HALFTIME" in statusName -> "Devre arası"
        "FIRST_HALF" in statusName -> "1. Yarı"
        "SECOND_HALF" in statusName -> "2. Yarı"
        "EXTRA" in statusName -> "Uzatma"
        "PENALT" in statusName || "SHOOTOUT" in statusName -> "Penaltılar"
        "DELAY" in statusName -> "Gecikmeli"
        else -> "Canlı"
    }

    /** The card for [now]: live beats a recent result, which beats the next fixture; null when nothing is known. */
    fun stateOf(fixtures: List<Fixture>, now: Long): MatchState? {
        fixtures.filter { it.phase == MatchPhase.LIVE && now - it.kickoffMs < MatchConfig.WINDOW_MS + 3_600_000L }
            .maxByOrNull { it.kickoffMs }?.let { return live(it) }
        fixtures.filter { it.phase == MatchPhase.POST && now - it.kickoffMs in 0..MatchConfig.FINISHED_KEEP_MS && it.gs != null && it.opp != null }
            .maxByOrNull { it.kickoffMs }?.let { return finished(it) }
        fixtures.filter { it.phase == MatchPhase.PRE && it.kickoffMs > now - MatchConfig.LEAD_MS }
            .minByOrNull { it.kickoffMs }?.let { return upcoming(it) }
        return null
    }

    fun upcoming(f: Fixture) = MatchState.Upcoming(f.opponent, f.opponentShort, f.competition, f.kickoffMs, f.home)

    fun live(f: Fixture) = MatchState.Live(
        f.opponent, f.opponentShort, f.competition, f.kickoffMs, f.home,
        f.gs ?: 0, f.opp ?: 0, f.clock, statusText(f.statusName),
    )

    fun finished(f: Fixture): MatchState.Finished {
        val gs = f.gs ?: 0
        val opp = f.opp ?: 0
        val result = when {
            gs > opp -> MatchResult.WIN
            gs < opp -> MatchResult.LOSS
            else -> MatchResult.DRAW
        }
        return MatchState.Finished(f.opponent, f.opponentShort, f.competition, f.kickoffMs, f.home, gs, opp, result)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
}

/** What the alarm chain remembers between polls: which event, and what was already reacted to. */
data class MatchTrack(
    val eventId: String,
    val slug: String,
    val kickoffMs: Long,
    /** Last seen scores; -1 until the first poll, so a late start never replays old goals. */
    val lastGs: Int = -1,
    val lastOpp: Int = -1,
    val kickoffFired: Boolean = false,
    val finalFired: Boolean = false,
)

/**
 * Small SharedPreferences file shared by the ViewModel and the alarm receiver (which may run in a
 * process without any ViewModel). Writes use commit() so an alarm thread's state is on disk at once.
 */
class MatchStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("match", Context.MODE_PRIVATE)

    fun fetchedAt(): Long = sp.getLong("fetched_at", 0L)

    fun fixtures(): List<Fixture> = runCatching {
        val a = JSONArray(sp.getString("fixtures", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Fixture(
                o.getString("id"), o.getString("slug"), o.getString("comp"), o.getLong("ko"), o.getBoolean("home"),
                MatchSource.turkishName(o.getString("opp")), o.getString("oppShort"), MatchPhase.valueOf(o.getString("phase")),
                o.optString("status"), o.optString("clock"),
                if (o.has("gs")) o.getInt("gs") else null, if (o.has("os")) o.getInt("os") else null,
            )
        }
    }.getOrDefault(emptyList())

    /** Keeps only what the card can still need: the last 36 h, and the next few fixtures. */
    fun saveFixtures(all: List<Fixture>, fetchedAt: Long = System.currentTimeMillis()) {
        val now = System.currentTimeMillis()
        val lastResult = all.filter { it.phase == MatchPhase.POST && it.gs != null && it.opp != null && now - it.kickoffMs in 0..MatchConfig.LAST_RESULT_KEEP_MS }
            .maxByOrNull { it.kickoffMs }
        val recent = all.filter { it.kickoffMs > now - 36L * 3_600_000L }.sortedBy { it.kickoffMs }.take(8)
        val keep = if (lastResult != null && recent.none { it.eventId == lastResult.eventId }) listOf(lastResult) + recent else recent
        val a = JSONArray()
        for (f in keep) {
            a.put(
                JSONObject().put("id", f.eventId).put("slug", f.slug).put("comp", f.competition).put("ko", f.kickoffMs)
                    .put("home", f.home).put("opp", f.opponent).put("oppShort", f.opponentShort)
                    .put("phase", f.phase.name).put("status", f.statusName).put("clock", f.clock)
                    .apply {
                        f.gs?.let { put("gs", it) }
                        f.opp?.let { put("os", it) }
                    },
            )
        }
        sp.edit().putString("fixtures", a.toString()).putLong("fetched_at", fetchedAt).commit()
    }

    /** Folds a fresh live snapshot into the cached fixture so the card shows it without a refetch. */
    fun applySnapshot(eventId: String, s: LiveSnapshot) {
        val list = fixtures()
        if (list.none { it.eventId == eventId }) return
        saveFixtures(
            list.map {
                if (it.eventId != eventId) it
                else it.copy(phase = s.phase, statusName = s.statusName, clock = s.clock, gs = s.gs ?: it.gs, opp = s.opp ?: it.opp)
            },
            fetchedAt(),
        )
    }

    fun track(): MatchTrack? = runCatching {
        val o = JSONObject(sp.getString("track", null) ?: return null)
        MatchTrack(
            o.getString("id"), o.getString("slug"), o.getLong("ko"),
            o.optInt("gs", -1), o.optInt("os", -1), o.optBoolean("kf"), o.optBoolean("ff"),
        )
    }.getOrNull()

    fun saveTrack(t: MatchTrack) {
        val o = JSONObject().put("id", t.eventId).put("slug", t.slug).put("ko", t.kickoffMs)
            .put("gs", t.lastGs).put("os", t.lastOpp).put("kf", t.kickoffFired).put("ff", t.finalFired)
        sp.edit().putString("track", o.toString()).commit()
    }

    fun clearTrack() {
        sp.edit().remove("track").putLong("next_poll", 0L).commit()
    }

    /** When the next poll alarm is due; used to tell a ticking chain from one that died (reboot, force stop). */
    fun nextPollAt(): Long = sp.getLong("next_poll", 0L)

    fun setNextPollAt(at: Long) {
        sp.edit().putLong("next_poll", at).commit()
    }
}

/** What the lights do. Shared by the ViewModel (through wizEffect) and the alarm receiver. */
object MatchEffects {
    private val Red = MatchConfig.Team.primary
    private val Yellow = MatchConfig.Team.secondary

    /** Plays [kind] through [set]; the caller snapshots the light before and restores it after. */
    suspend fun play(kind: MatchEffectKind, set: (JSONObject) -> Unit) {
        when (kind) {
            // Three red/yellow alternations
            MatchEffectKind.KICKOFF -> repeat(3) {
                set(Wiz.color(Red, 100))
                delay(450)
                set(Wiz.color(Yellow, 100))
                delay(450)
            }
            // About 8 s of fast red/yellow strobing
            MatchEffectKind.GOAL -> {
                val end = System.nanoTime() + 8_000_000_000L
                var red = true
                while (System.nanoTime() < end) {
                    set(Wiz.color(if (red) Red else Yellow, 100))
                    red = !red
                    delay(230)
                }
            }
            // Slower, warmer: about 6 s of red/yellow waves ending on yellow
            MatchEffectKind.WIN -> {
                repeat(5) {
                    set(Wiz.color(Red, 100))
                    delay(600)
                    set(Wiz.color(Yellow, 100))
                    delay(600)
                }
            }
        }
    }
}

/**
 * Set by the ViewModel while home is visible. The receiver hands effects to it so they go through
 * the ViewModel's light mutex and ambient bookkeeping; with no ViewModel the receiver plays them itself.
 */
object MatchBridge {
    @Volatile
    var sink: ((MatchEffectKind) -> Unit)? = null
}
