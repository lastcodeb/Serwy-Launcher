package dev.mibox.neon.ui.gazete

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.HeadlineKind
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.HeadlineTarget
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.MatchResult
import dev.mibox.neon.data.MatchState
import dev.mibox.neon.data.NetVerdict
import dev.mibox.neon.data.SpeedState
import dev.mibox.neon.data.Weather
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.ui.HomeActions
import dev.mibox.neon.ui.LocalHomeStyle
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.fmtMbps
import dev.mibox.neon.ui.rememberNow
import dev.mibox.neon.ui.verdictText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import dev.mibox.neon.i18n.t

private val HourFmt = DateTimeFormatter.ofPattern("HH:mm", TR)
private val DateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", TR)
private val KickFmt = DateTimeFormatter.ofPattern("EEE d MMMM '·' HH:mm", TR)
private val DayFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", TR)

private val MansetSizes = listOf(64f, 54f, 46f, 39f, 33f, 28f, 24f, 20f)
private val LiveSizes = listOf(74f, 62f, 52f, 44f, 38f)
private val ScoreSizes = listOf(30f, 26f, 22f, 19f)
private val FavSizes = listOf(15f, 13f, 12f)

/**
 * The front page: top strip, masthead, issue line, three columns (SPOR + KÜNYE, MANŞET +
 * KALDIĞIN YERDEN, EDİTÖRÜN SEÇİMİ) and the FAVORİLER strip. While a match is live the lead
 * becomes the evening "SON DAKİKA" edition (a different arrangement, picked by the VM's own
 * headline, not by focus, so moving around never reshuffles the page).
 */
@Composable
internal fun GazeteFront(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, onOpenDizin: () -> Unit) {
    val liveFlow = remember(vm) { vm.headline.map { it.kind == HeadlineKind.MATCH_LIVE }.distinctUntilChanged() }
    val live by liveFlow.collectAsStateWithLifecycle(false)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TopStrip(nav, actions, onOpenDizin)
        Masthead(vm, actions, nav, compact = live)
        IssueLine(vm, pageNo = 1)
        if (live) {
            LiveBody(vm, actions, nav, Modifier.weight(1f))
        } else {
            NormalBody(vm, actions, nav, onOpenDizin, Modifier.weight(1f))
        }
        FavoritesStrip(vm, actions, nav, onOpenDizin)
    }
}

// --- Top strip, masthead, issue line ---------------------------------------------------------------

/** "Günaydın · 09:40" on the left, the links Tema and Dizin on the right. */
@Composable
internal fun TopStrip(nav: GazeteNav, actions: HomeActions, onOpenDizin: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        GreetingText()
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            GazeteLink(nav, K_TOP_TEMA, t("Tema"), actions.onOpenThemePicker)
            GazeteLink(nav, K_TOP_DIZIN, t("Dizin"), onOpenDizin, arrow = ArrowDir.RIGHT)
        }
    }
}

@Composable
internal fun GreetingText() {
    val st = LocalHomeStyle.current
    val now by rememberNow()
    Text(
        st.caps("${greetingFor(now.hour)}  ·  ${now.format(HourFmt)}"),
        style = st.lab(13f, FontWeight.SemiBold),
        color = st.text,
        maxLines = 1,
    )
}

@Composable
private fun Masthead(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, compact: Boolean) {
    val st = LocalHomeStyle.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(2.5.dp).background(st.divider))
        Row(Modifier.fillMaxWidth().height(if (compact) 54.dp else 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { ClockEar(vm, actions, nav, compact) }
            Text(
                "NEON",
                style = st.display.copy(
                    fontSize = if (compact) 56.sp else 68.sp,
                    lineHeight = if (compact) 52.sp else 62.sp,
                    letterSpacing = 4.sp,
                ),
                // The tall line box puts the baseline below the row: lift the caps clear of the rule
                modifier = Modifier.offset(y = if (compact) (-9).dp else (-11).dp),
                color = st.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { WeatherBox(vm, actions, nav, compact) }
        }
    }
}

/** The clock ear (left of the nameplate); OK opens the Control Center. */
@Composable
private fun ClockEar(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, compact: Boolean) {
    val st = LocalHomeStyle.current
    val now by rememberNow()
    GazeteItem(nav, K_CLOCK, onClick = {
        vm.refreshWeather()
        actions.onOpenControl()
    }) {
        Column {
            Text(st.caps(t("Saat")), style = st.lab(12f), color = st.accent, maxLines = 1)
            Text(
                now.format(HourFmt),
                style = st.play(if (compact) 28f else 32f).copy(lineHeight = (if (compact) 30 else 34).sp),
                color = st.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

/** The HAVA box (right of the nameplate): temperature, condition and city; OK opens the Control Center. */
@Composable
private fun WeatherBox(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, compact: Boolean) {
    val st = LocalHomeStyle.current
    val weather by vm.weather.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val w = weather
    val condition = remember(w) { w?.let { Weather.describe(it.code, it.isDay).second }.orEmpty() }
    GazeteItem(nav, K_WEATHER, onClick = {
        vm.refreshWeather()
        actions.onOpenControl()
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(0.75.dp).height(38.dp).background(st.divider))
            Spacer(Modifier.width(12.dp))
            Text(
                if (w != null) "${w.tempC.roundToInt()}°" else "–°",
                style = st.playBlack(if (compact) 30f else 38f).copy(lineHeight = (if (compact) 32 else 40).sp),
                color = st.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 140.dp)) {
                Text(st.caps(t("Hava")), style = st.lab(12f), color = st.accent, maxLines = 1)
                if (condition.isNotBlank()) Text(condition, style = st.serif(13f), color = st.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(city.name, style = st.serif(13f), color = st.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Edition, date, issue number (the day of the year) and the weather, between a hairline and a double rule. */
@Composable
internal fun IssueLine(vm: LauncherViewModel, pageNo: Int) {
    val st = LocalHomeStyle.current
    val now by rememberNow()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val w = weather
    val middle = buildString {
        append(now.format(DateFmt))
        append(t("  ·  Sayı ")).append(now.dayOfYear)
        if (w != null) {
            append("  ·  ").append(city.name).append(' ').append(w.tempC.roundToInt()).append('°')
            val c = Weather.describe(w.code, w.isDay).second
            if (c.isNotBlank()) append(", ").append(c)
        }
    }
    val style = st.lab(13f, FontWeight.SemiBold)
    Column(Modifier.fillMaxWidth()) {
        HairRule()
        Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(st.caps(if (st.night) t("Akşam baskısı") else t("Sabah baskısı")), style = style, color = st.accent, maxLines = 1)
            Text(st.caps(middle), style = style, color = st.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(horizontal = 12.dp))
            Text(st.caps(t("Sayfa {0}", pageNo)), style = style, color = st.text, maxLines = 1)
        }
        DoubleRule()
    }
}

// --- Bodies ----------------------------------------------------------------------------------------

@Composable
private fun NormalBody(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, onOpenDizin: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.width(196.dp).fillMaxHeight().padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SporBlock(vm, actions, nav)
            KunyeBox(vm, actions, nav)
        }
        VRule()
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Manset(vm, actions, nav, onOpenDizin, Modifier.weight(1f))
            WatchSection(vm, actions, nav, count = 3, condensed = false)
        }
        VRule()
        EditorSection(vm, actions, nav, count = 3, Modifier.width(204.dp).fillMaxHeight().padding(start = 16.dp))
    }
}

/** The evening edition while a match is live: the score fills the lead, the rest condenses below. */
@Composable
private fun LiveBody(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LiveManset(vm, actions, nav)
        KunyeStrip(vm, actions, nav)
        Row(Modifier.fillMaxWidth().weight(1f)) {
            WatchSection(vm, actions, nav, count = 2, condensed = true, modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp))
            VRule()
            EditorSection(vm, actions, nav, count = 2, Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp), condensed = true)
        }
    }
}

// --- Manşet ----------------------------------------------------------------------------------------

private fun bylineFor(kind: HeadlineKind, night: Boolean): String = when (kind) {
    HeadlineKind.MATCH_LIVE, HeadlineKind.MATCH_RESULT, HeadlineKind.MATCH_TODAY, HeadlineKind.MATCH_UPCOMING -> t("Spor servisi")
    HeadlineKind.CONTINUE -> t("Yayın servisi")
    HeadlineKind.PREDICTED -> t("Alışkanlık")
    HeadlineKind.LAST_USED -> t("Son kullanılan")
    HeadlineKind.APP -> t("Uygulamalar")
    HeadlineKind.GREETING -> if (night) t("Akşam baskısı") else t("Sabah baskısı")
}

/** OK on a lead story: the VM launches it; a match with no match app set opens the match panel instead. */
private fun act(vm: LauncherViewModel, actions: HomeActions, target: HeadlineTarget, onOpenDizin: () -> Unit) {
    when (target) {
        HeadlineTarget.None -> onOpenDizin()
        HeadlineTarget.OpenMatch -> if (!vm.act(target)) actions.onOpenMatch()
        is HeadlineTarget.OpenApp, is HeadlineTarget.OpenWatch -> vm.act(target)
    }
}

/** The lead story's single button (inked, with an arrow). */
@Composable
private fun ActionButton(nav: GazeteNav, label: String, onClick: () -> Unit, props: FocusProperties.() -> Unit, big: Boolean) {
    val st = LocalHomeStyle.current
    GazeteItem(
        nav, K_MAN, onClick,
        modifier = Modifier.focusProperties(props),
        claimsStory = false,
        fill = st.text,
    ) {
        Row(
            Modifier.padding(horizontal = if (big) 18.dp else 14.dp, vertical = if (big) 6.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(st.caps(label), style = st.lab(if (big) 16f else 14f), color = st.background, maxLines = 1)
            Arrow(st.background)
        }
    }
}

/**
 * The lead: idle it is the VM's headline; with a card or app focused it is that item's own story.
 * Only this composable reads the focused story, so focus moves never recompose the page.
 */
@Composable
private fun Manset(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, onOpenDizin: () -> Unit, modifier: Modifier) {
    val st = LocalHomeStyle.current
    val idle by vm.headline.collectAsStateWithLifecycle()
    val story = nav.story.value
    val h = remember(story, idle) {
        when (story) {
            is GazeteStory.Item -> vm.headlineFor(story.item)
            is GazeteStory.Application -> vm.headlineFor(story.app)
            null -> idle
        }
    }
    val title = st.clean(h.title)
    val deck = st.clean(h.deck)
    // The lead's neighbours are named explicitly: the 2D search would land on the second card
    val props: FocusProperties.() -> Unit = {
        up = nav.requester(K_WEATHER)
        right = vm.forYou.value.firstOrNull()?.let { nav.requester(editorKey(it)) } ?: FocusRequester.Default
        down = vm.watchNext.value.firstOrNull()?.let { nav.requester(watchKey(it)) } ?: FocusRequester.Default
    }
    // A short story is set big and centered in the column instead of leaving a band of paper
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                st.caps(t("Manşet")),
                style = st.lab(12f),
                color = st.onAccent,
                modifier = Modifier.background(st.accent2).padding(horizontal = 7.dp, vertical = 1.dp),
            )
            Text(st.caps(st.clean(h.kicker)), style = st.lab(12f), color = st.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FitText(title, st.playBlack(64f), MansetSizes, maxLines = 3, modifier = Modifier.weight(1f, fill = false), color = st.text)
        if (deck.isNotBlank()) DropCapDeck(deck, size = if (deck.length < DROP_CAP_MIN) 15f else 13f, maxLines = 3)
        h.progress?.takeIf { it > 0f }?.let { ProgressBar(it) }
        HairRule(Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                st.caps(bylineFor(h.kind, st.night)),
                style = st.lab(13f, FontWeight.SemiBold),
                color = st.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            ActionButton(
                nav,
                label = h.action.ifEmpty { t("Dizini aç") },
                onClick = { act(vm, actions, h.target, onOpenDizin) },
                props = props,
                big = false,
            )
        }
    }
}

/** SON DAKİKA: band, the score in giant Playfair and the match facts; a fixed story, focus does not change it. */
@Composable
private fun LiveManset(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav) {
    val st = LocalHomeStyle.current
    val h by vm.headline.collectAsStateWithLifecycle()
    val match by vm.match.collectAsStateWithLifecycle()
    val m = match
    val facts = remember(m) {
        if (m == null) {
            ""
        } else {
            val team = MatchConfig.Team.name
            val names = if (m.home) "$team – ${m.opponent}" else "${m.opponent} – $team"
            listOf(names, m.competition, if (m.home) t("iç saha") else "deplasman").filter { it.isNotBlank() }.joinToString(" · ")
        }
    }
    val line = st.divider
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1.25f).padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    st.caps(h.kicker),
                    style = st.lab(15f).copy(letterSpacing = 2.5.sp),
                    color = st.onAccent,
                    maxLines = 1,
                    modifier = Modifier.background(st.accent2).padding(horizontal = 9.dp, vertical = 2.dp),
                )
                Text(st.caps(h.deck), style = st.lab(13f, FontWeight.SemiBold), color = st.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FitText(st.clean(h.title), st.playBlack(74f), LiveSizes, maxLines = 1, color = st.text)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 20.dp)
                .drawBehind { drawLine(line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 0.75.dp.toPx()) },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (facts.isNotBlank()) DropCapDeck(facts, size = 14f, maxLines = 3, modifier = Modifier.padding(start = 20.dp))
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(st.caps(t("Spor servisi")), style = st.lab(13f, FontWeight.SemiBold), color = st.textMuted, maxLines = 1)
                ActionButton(
                    nav,
                    label = h.action.ifEmpty { t("Maçı aç") },
                    onClick = { act(vm, actions, h.target, {}) },
                    props = { up = nav.requester(K_WEATHER) },
                    big = true,
                )
            }
        }
    }
}

// --- Left column: SPOR and KÜNYE -------------------------------------------------------------------

@Composable
private fun SporBlock(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav) {
    val st = LocalHomeStyle.current
    val enabled by vm.matchEnabled.collectAsStateWithLifecycle()
    val match by vm.match.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    val m = match
    val last = lastResult
    // The card already is that result: do not say it twice
    val showLast = enabled && last != null && !(m is MatchState.Finished && m.kickoffMs == last.kickoffMs)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SectionHeader(t("Spor"))
        GazeteItem(
            nav, K_SPOR, onClick = actions.onOpenMatch,
            modifier = Modifier.fillMaxWidth().focusProperties { right = nav.requester(K_MAN) },
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                when {
                    m != null -> MatchLines(m)
                    enabled -> Text(t("Yaklaşan maç yok."), style = st.play(14f, italic = true), color = st.textMuted)
                    else -> {
                        Text(t("Maç günü kapalı."), style = st.play(14f, italic = true), color = st.textMuted)
                        Text(t("Açmak için Tamam'a bas."), style = st.serif(12f), color = st.textMuted)
                    }
                }
                if (showLast) {
                    Spacer(Modifier.height(2.dp))
                    HairRule()
                    Spacer(Modifier.height(2.dp))
                    LastResultLines(last)
                }
            }
        }
    }
}

/** "SON SONUÇ · Şampiyonlar Ligi", "Lille 2–4 Galatasaray", "21 Ekim 2026 · deplasman galibiyeti". */
@Composable
private fun LastResultLines(r: MatchState.Finished) {
    val st = LocalHomeStyle.current
    val team = MatchConfig.Team.name
    val opponent = st.clean(r.opponent)
    val line = if (r.home) "$team ${r.gsScore}–${r.oppScore} $opponent" else "$opponent ${r.oppScore}–${r.gsScore} $team"
    val kind = when (r.result) {
        MatchResult.WIN -> t("galibiyeti")
        MatchResult.DRAW -> t("beraberliği")
        MatchResult.LOSS -> t("mağlubiyeti")
    }
    val date = r.kickoff.format(DayFmt)
    val note = t("{0} {1}", if (r.home) t("İç saha") else t("Deplasman"), kind)
    Text(
        st.caps(listOf(t("Son sonuç"), r.competition).filter { it.isNotBlank() }.joinToString(" · ")),
        style = st.lab(12f),
        color = st.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(line, style = st.serif(13f, FontWeight.SemiBold), color = st.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(date, style = st.serif(12f), color = st.textMuted, maxLines = 1)
    Text(note, style = st.serif(12f), color = st.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Upcoming, live or finished, from the followed team's side; scores are shown home side first. */
@Composable
private fun MatchLines(m: MatchState) {
    val st = LocalHomeStyle.current
    val gs = MatchConfig.Team.shortName
    val opp = m.opponentShort.ifBlank { m.opponent.take(3).uppercase(TR) }
    val title = when (m) {
        is MatchState.Upcoming -> if (m.home) "$gs – $opp" else "$opp – $gs"
        is MatchState.Live -> if (m.home) "$gs ${m.gsScore}–${m.oppScore} $opp" else "$opp ${m.oppScore}–${m.gsScore} $gs"
        is MatchState.Finished -> if (m.home) "$gs ${m.gsScore}–${m.oppScore} $opp" else "$opp ${m.oppScore}–${m.gsScore} $gs"
    }
    val head = when (m) {
        is MatchState.Upcoming -> listOf(m.competition, m.kickoff.format(KickFmt))
        is MatchState.Live -> listOf(t("Canlı"), m.clock, m.status)
        is MatchState.Finished -> listOf(t("Maç sonucu"), m.competition)
    }.filter { it.isNotBlank() }.joinToString(" · ")
    val side = if (m.home) t("İç saha") else t("Deplasman")
    val team = MatchConfig.Team.name
    val opponent = st.clean(m.opponent)
    val names = if (m.home) "$team – $opponent" else "$opponent – $team"
    Text(
        st.caps(head),
        style = st.lab(12f, FontWeight.SemiBold),
        color = if (m is MatchState.Live) st.accent else st.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    FitText(title, st.playBlack(30f), ScoreSizes, maxLines = 1, color = st.text)
    when (m) {
        is MatchState.Finished -> {
            val (word, color) = when (m.result) {
                MatchResult.WIN -> t("Galibiyet") to st.ok
                MatchResult.DRAW -> t("Beraberlik") to st.warn
                MatchResult.LOSS -> t("Mağlubiyet") to st.danger
            }
            Text("$word · $side", style = st.serif(13f, FontWeight.SemiBold), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(names, style = st.serif(12f), color = st.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        else -> {
            Text(names, style = st.serif(13f), color = st.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(side, style = st.serif(12f), color = st.textMuted, maxLines = 1)
        }
    }
}

/** One line of the KÜNYE box: a label and what OK does or the current value. */
@Composable
private fun KunyeCell(label: String, value: String, compact: Boolean, link: Boolean, hint: String? = null) {
    val st = LocalHomeStyle.current
    val deco = if (link) TextDecoration.Underline else null
    if (compact) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(st.caps(label), style = st.lab(12f), color = st.accent, maxLines = 1)
            Text(value, style = st.serif(14f, FontWeight.SemiBold), color = st.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = deco)
            if (hint != null) Text(hint, style = st.play(12f, italic = true), color = st.textMuted, maxLines = 1)
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = st.serif(13f), color = st.textMuted, maxLines = 1)
                // The boxed KÜNYE has room for four rows only: the hint shares the label's line
                if (hint != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(hint, style = st.play(11f, italic = true), color = st.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    value,
                    modifier = Modifier.weight(1f),
                    style = st.serif(13f, FontWeight.SemiBold),
                    color = st.text,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = deco,
                )
            }
        }
    }
}

@Composable
private fun LightCell(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, compact: Boolean, modifier: Modifier) {
    val ip by vm.wizIp.collectAsStateWithLifecycle()
    val wiz by vm.wiz.collectAsStateWithLifecycle()
    val w = wiz
    val value = when {
        ip == null -> t("Işık bul")
        w == null -> t("Bağlı")
        w.on -> t("Açık · %{0}", w.dimming)
        else -> t("Kapalı")
    }
    GazeteItem(
        nav, K_LIGHT,
        onClick = {
            vm.wizRefresh()
            actions.onOpenLight()
        },
        modifier = modifier,
        onLongClick = if (ip != null) ({ vm.wizQuick() }) else null,
    ) {
        KunyeCell(t("WiZ ışık"), value, compact, link = ip == null)
    }
}

@Composable
private fun SpeedCell(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, compact: Boolean, modifier: Modifier) {
    val speed by vm.speed.collectAsStateWithLifecycle()
    val net by vm.netNow.collectAsStateWithLifecycle()
    val s = speed
    val last = net.last
    // The value stays a short single line; the verdict goes on the muted line below it
    val verdict = if (net.verdict == NetVerdict.UNKNOWN) null else verdictText(net.verdict)
    val value = when (s) {
        SpeedState.Idle -> last?.let { "${fmtMbps(it.mbps)} Mbps" } ?: t("Hız testi")
        is SpeedState.Running -> if (s.mbps == 0.0) t("Ölçülüyor…") else "${fmtMbps(s.mbps)} Mbps"
        is SpeedState.Done -> "${fmtMbps(s.mbps)} Mbps"
        SpeedState.Failed -> t("Bağlantı yok")
    }
    val showVerdict = verdict != null && (s is SpeedState.Done || (s == SpeedState.Idle && last != null))
    GazeteItem(nav, K_SPEED, onClick = { vm.runSpeedTest() }, modifier = modifier, onLongClick = actions.onOpenNet) {
        KunyeCell(t("Hız"), value, compact, link = last == null && s == SpeedState.Idle, hint = if (showVerdict) verdict else null)
    }
}

@Composable
private fun RamCell(vm: LauncherViewModel, nav: GazeteNav, compact: Boolean, modifier: Modifier) {
    val ram by vm.ram.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    val f = freed
    GazeteItem(nav, K_RAM, onClick = { vm.boost() }, modifier = modifier) {
        KunyeCell(
            t("RAM %{0}", ram.usedPercent),
            if (f != null) "+${f / (1024 * 1024)} MB" else t("Boşalt"),
            compact,
            link = f == null,
        )
    }
}

@Composable
private fun GearCell(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, compact: Boolean, modifier: Modifier) {
    GazeteItem(nav, K_GEAR, onClick = { vm.openSystemSettings() }, modifier = modifier, onLongClick = actions.onOpenMenu) {
        KunyeCell(t("Ayarlar"), t("Sistem"), compact, link = true, hint = t("Uzun bas: menü"))
    }
}

/** The boxed KÜNYE: light, speed, memory and settings, each one OK away. */
@Composable
private fun KunyeBox(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav) {
    val st = LocalHomeStyle.current
    val cell = Modifier.fillMaxWidth()
    Column(Modifier.fillMaxWidth().border(1.dp, st.text).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(st.caps(t("Künye")), style = st.lab(12f), color = st.accent, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        HairRule()
        Spacer(Modifier.height(3.dp))
        LightCell(vm, actions, nav, compact = false, modifier = cell)
        SpeedCell(vm, actions, nav, compact = false, modifier = cell)
        RamCell(vm, nav, compact = false, modifier = cell)
        GearCell(vm, actions, nav, compact = false, modifier = cell)
    }
}

/** The same four, laid in a row between two rules (the evening edition has no left column). */
@Composable
private fun KunyeStrip(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav) {
    Column(Modifier.fillMaxWidth()) {
        HairRule()
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val cell = Modifier.weight(1f)
            LightCell(vm, actions, nav, compact = true, modifier = cell)
            SpeedCell(vm, actions, nav, compact = true, modifier = cell)
            RamCell(vm, nav, compact = true, modifier = cell)
            GearCell(vm, actions, nav, compact = true, modifier = cell)
        }
        HairRule()
    }
}

// --- Center and right: KALDIĞIN YERDEN, EDİTÖRÜN SEÇİMİ --------------------------------------------

@Composable
private fun WatchSection(
    vm: LauncherViewModel,
    actions: HomeActions,
    nav: GazeteNav,
    count: Int,
    condensed: Boolean,
    modifier: Modifier = Modifier,
) {
    val watch by vm.watchNext.collectAsStateWithLifecycle()
    val loaded by vm.watchLoaded.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The stacked list shows only as many cards as fit, never a clipped one
        val limit = if (condensed) ((maxHeight - 26.dp) / 60.dp).toInt().coerceIn(1, count) else count
        val items = remember(watch, limit) { watch.take(limit) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader(t("Kaldığın yerden"))
            if (items.isEmpty()) {
                if (loaded) EmptyLine(t("Bugün kaldığın bir yer yok."))
            } else if (condensed) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (item in items) {
                        key(item.id) { WatchCard(vm, actions, nav, item, condensed = true, upTo = null, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (item in items) {
                        key(item.id) {
                            WatchCard(vm, actions, nav, item, condensed = false, upTo = nav.requester(K_MAN), modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchCard(
    vm: LauncherViewModel,
    actions: HomeActions,
    nav: GazeteNav,
    item: WatchItem,
    condensed: Boolean,
    upTo: FocusRequester?,
    modifier: Modifier,
) {
    val st = LocalHomeStyle.current
    val appLabel = remember(item.pkg, st) { st.clean(vm.appFor(item.pkg)?.label ?: item.pkg.substringAfterLast('.')) }
    val story = remember(item) { GazeteStory.Item(item) }
    val pct = remember(item) { percentOf(item) }
    val episode = remember(item, st) { episodeLine(item)?.let { st.clean(it) } }
    val title = remember(item, st) { st.clean(item.title) }
    GazeteItem(
        nav, watchKey(item),
        onClick = { vm.launchWatch(item) },
        modifier = if (upTo != null) modifier.focusProperties { up = upTo } else modifier,
        onLongClick = { actions.onContentMenu(item) },
        story = story,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(st.caps(appLabel), style = st.lab(12f), color = st.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (condensed) {
                Text(
                    listOfNotNull(title, episode).joinToString(" · "),
                    style = st.play(15f),
                    color = st.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pct != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProgressBar(pct / 100f, Modifier.weight(1f))
                        Text(t("%{0} izlendi", pct), style = st.lab(12f, FontWeight.SemiBold), color = st.text, maxLines = 1)
                    }
                }
            } else {
                Text(title, style = st.play(15f).copy(lineHeight = 17.sp), color = st.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (episode != null) Text(episode, style = st.serif(12f), color = st.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pct != null) {
                    Spacer(Modifier.height(4.dp))
                    ProgressBar(pct / 100f)
                    Text(t("%{0} izlendi", pct), style = st.lab(12f, FontWeight.SemiBold), color = st.text, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EditorSection(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, count: Int, modifier: Modifier, condensed: Boolean = false) {
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val loaded by vm.watchLoaded.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier) {
        // Only as many picks as fit above the rule; titles take two lines in the evening edition
        val itemH = if (condensed) 60.dp else 80.dp
        val limit = ((maxHeight - 26.dp) / itemH).toInt().coerceIn(1, count)
        val items = remember(forYou, limit) { forYou.take(limit) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SectionHeader(t("Editörün seçimi"))
            if (items.isEmpty()) {
                if (loaded) EmptyLine(t("Şimdilik öneri yok."))
            } else {
                items.forEachIndexed { i, item -> key(item.id) { EditorRow(vm, actions, nav, item, i + 1, if (condensed) 2 else 3) } }
            }
        }
    }
}

@Composable
private fun EditorRow(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, item: WatchItem, number: Int, titleLines: Int) {
    val st = LocalHomeStyle.current
    val kicker = remember(item.pkg, st) { st.clean(vm.appFor(item.pkg)?.label.orEmpty()).let { if (it.isBlank()) t("Senin için") else t("{0} · Senin için", it) } }
    val title = remember(item, st) { st.clean(item.title) }
    val story = remember(item) { GazeteStory.Item(item) }
    GazeteItem(
        nav, editorKey(item),
        onClick = { vm.launchWatch(item) },
        modifier = Modifier.fillMaxWidth(),
        onLongClick = { actions.onContentMenu(item) },
        story = story,
        index = number - 1,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$number",
                modifier = Modifier.width(22.dp),
                style = st.playBlack(30f).copy(lineHeight = 30.sp),
                color = st.accent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
            Column(Modifier.weight(1f)) {
                Text(st.caps(kicker), style = st.lab(12f), color = st.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, style = st.play(14f).copy(lineHeight = 17.sp), color = st.text, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --- Bottom: FAVORİLER ---------------------------------------------------------------------------

@Composable
private fun FavoritesStrip(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, onOpenDizin: () -> Unit) {
    val st = LocalHomeStyle.current
    val cards by vm.favoriteCards.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        DoubleRule()
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(104.dp)) {
                Text(st.caps(t("Favoriler")), style = st.lab(12f), color = st.accent, maxLines = 1)
                Text(t("Sabitlediklerin"), style = st.play(14f, italic = true), color = st.text, maxLines = 1)
            }
            if (cards.isEmpty()) {
                EmptyLine(t("Henüz sabitlenmiş bir şey yok."), Modifier.weight(1f))
            } else {
                LazyRow(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    state = nav.favList,
                    // The pen loop reaches 12dp sideways and a lazy row clips along its scroll axis
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(cards, key = { _, c -> c.key }) { i, card ->
                        FavCard(vm, actions, nav, card, i, pageOf = apps.indexOfFirst { it.pkg == card.app.pkg } + 1)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            GazeteItem(nav, K_FAV_DIZIN, onClick = onOpenDizin, modifier = Modifier.width(82.dp).height(46.dp), fill = st.text) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(st.caps(t("Dizin")), style = st.lab(16f), color = st.background, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Arrow(st.background)
                }
            }
        }
    }
}

@Composable
private fun FavCard(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, card: FavoriteCard, index: Int, pageOf: Int) {
    val st = LocalHomeStyle.current
    val story: GazeteStory = remember(card) {
        when (card) {
            is FavoriteCard.App -> GazeteStory.Application(card.app)
            is FavoriteCard.Content -> GazeteStory.Item(card.item)
        }
    }
    val name = st.clean(
        when (card) {
            is FavoriteCard.App -> card.app.label
            is FavoriteCard.Content -> card.item.title
        },
    )
    val sub = when (card) {
        is FavoriteCard.App -> if (pageOf > 0) t("s. {0}", pageOf) else ""
        is FavoriteCard.Content -> st.clean(card.app.label)
    }
    GazeteItem(
        nav, favKey(card),
        onClick = { vm.launchPinned(card) },
        modifier = Modifier.width(118.dp).height(46.dp),
        onLongClick = {
            when (card) {
                is FavoriteCard.App -> actions.onAppMenu(card.app)
                is FavoriteCard.Content -> actions.onContentMenu(card.item)
            }
        },
        story = story,
        index = index,
        outline = true,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
            FitText(name, st.playBlack(15f), FavSizes, maxLines = 1, color = st.text)
            if (sub.isNotEmpty()) Text(st.caps(sub), style = st.lab(12f, FontWeight.SemiBold), color = st.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
