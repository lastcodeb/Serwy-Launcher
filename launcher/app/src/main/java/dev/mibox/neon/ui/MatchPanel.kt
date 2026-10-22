package dev.mibox.neon.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.HomeTheme
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.MatchResult
import dev.mibox.neon.data.MatchSamples
import dev.mibox.neon.data.MatchState
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import dev.mibox.neon.data.TeamLogos
import dev.mibox.neon.i18n.t
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val PanelShape = RoundedCornerShape(28.dp)
private val KickFmt = DateTimeFormatter.ofPattern("EEE HH:mm", TR)
private val HourFmt = DateTimeFormatter.ofPattern("HH:mm", TR)
private val DayFmt = DateTimeFormatter.ofPattern("d MMM EEEE", TR)
private val LiveRed = Color(0xFFFF2D3A)

private val DayMonFmt = DateTimeFormatter.ofPattern("d MMM HH:mm", TR)

private const val COUNTDOWN_MS = 6L * 60 * 60 * 1000

private fun oppShort(m: MatchState): String = m.opponentShort.ifBlank { m.opponent.take(3).uppercase(TR) }

private fun countdown(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    val h = min / 60
    val mm = min % 60
    return when {
        h == 0L -> t("{0} dk", mm)
        mm == 0L -> t("{0} sa", h)
        else -> t("{0} sa {1} dk", h, mm)
    }
}

/** Countdown in the last 6 hours, then "Bugün 21:30", "Yarın 21:30", "Pzt 21:30" (6 days) or "26 Eki 21:30". */
private fun upcomingWhen(m: MatchState.Upcoming, nowMs: Long): String {
    val left = m.kickoffMs - nowMs
    if (left <= 0) return t("Başlıyor")
    if (left <= COUNTDOWN_MS) return countdown(left)
    val today = Instant.ofEpochMilli(nowMs).atZone(m.kickoff.zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, m.kickoff.toLocalDate())
    return when {
        days <= 0L -> t("Bugün ") + m.kickoff.format(HourFmt)
        days == 1L -> t("Yarın ") + m.kickoff.format(HourFmt)
        days <= 6L -> m.kickoff.format(KickFmt)
        else -> m.kickoff.format(DayMonFmt)
    }
}

private fun resultText(r: MatchResult) = when (r) {
    MatchResult.WIN -> t("Galibiyet")
    MatchResult.DRAW -> t("Beraberlik")
    MatchResult.LOSS -> t("Mağlubiyet")
}

private fun resultColor(r: MatchResult, st: HomeStyle) = when (r) {
    MatchResult.WIN -> st.ok
    MatchResult.DRAW -> st.warn
    MatchResult.LOSS -> st.danger
}

/** Red dot that breathes while a match is live; the alpha animates in the graphics layer only. */
@Composable
private fun PulseDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a = t.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "liveDot",
    )
    Box(
        Modifier
            .size(7.dp)
            .graphicsLayer { alpha = a.value }
            .background(LiveRed, CircleShape),
    )
}

/** A club crest from ESPN, loaded once per process; an empty box until (or unless) it arrives. */
@Composable
fun TeamLogo(espnId: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, espnId) { value = TeamLogos.get(ctx, espnId) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** Home side first, like a stadium board: ESPN ids (for crests) and the fans' abbreviations. */
private class Sides(val homeId: String, val homeCode: String, val awayId: String, val awayCode: String)

private fun sides(m: MatchState): Sides {
    val us = MatchConfig.Team
    return if (m.home) Sides(us.espnId, us.shortName, m.opponentId, oppShort(m)) else Sides(m.opponentId, oppShort(m), us.espnId, us.shortName)
}

/**
 * Permanent top bar chip while match day is on, home side first with both crests:
 * "[GS] GS – FB [FB] · Pzt 20:30", live "[GS] GS 2–1 FB [FB] ● 67'", finished "… · Galibiyet",
 * or "Yaklaşan maç yok" while [m] is null.
 */
@Composable
fun MatchChip(m: MatchState?, nowMs: Long, onOpen: () -> Unit) {
    val team = MatchConfig.Team
    val st = LocalHomeStyle.current
    val code = st.bar(12.sp, FontWeight.Bold)
    val info = st.bar(11.sp, FontWeight.SemiBold, st.textSoft)
    NeonSurface(
        onClick = onOpen,
        modifier = Modifier.height(35.dp).widthIn(max = 250.dp),
        glowColor = team.primary,
        container = team.primary.copy(alpha = if (m is MatchState.Live) 0.24f else 0.12f),
        focusedContainer = team.primary.copy(alpha = 0.36f),
        stroke = team.primary.copy(alpha = 0.6f),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (m == null) {
                TeamLogo(team.espnId, 20.dp)
                Spacer(Modifier.width(7.dp))
                Text(t("Yaklaşan maç yok"), style = info, maxLines = 1)
                return@Row
            }
            val s = sides(m)
            val middle = when (m) {
                is MatchState.Upcoming -> " – "
                is MatchState.Live -> score(m, m.gsScore, m.oppScore, compact = true)
                is MatchState.Finished -> score(m, m.gsScore, m.oppScore, compact = true)
            }
            TeamLogo(s.homeId, 20.dp)
            Spacer(Modifier.width(6.dp))
            Text(s.homeCode + middle + s.awayCode, style = code, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            TeamLogo(s.awayId, 20.dp)
            Spacer(Modifier.width(8.dp))
            when (m) {
                is MatchState.Upcoming -> Text(upcomingWhen(m, nowMs), style = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                is MatchState.Live -> {
                    PulseDot()
                    Spacer(Modifier.width(5.dp))
                    Text(m.clock.ifBlank { m.status }, style = info.copy(color = LiveRed), maxLines = 1)
                }
                is MatchState.Finished -> Text(resultText(m.result), style = info.copy(color = resultColor(m.result, st)), maxLines = 1)
            }
        }
    }
}

/** Opened from the match chip or the Control Center: scoreboard, "Maçı aç" and the match-day settings. */
@Composable
fun MatchPanel(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val match by vm.match.collectAsStateWithLifecycle()
    val enabled by vm.matchEnabled.collectAsStateWithLifecycle()
    val goalEffect by vm.matchGoalEffect.collectAsStateWithLifecycle()
    val delaySec by vm.matchDelay.collectAsStateWithLifecycle()
    val appPkg by vm.matchApp.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    // "Maçı aç" without a chosen app opens the picker first and launches the pick
    var launchAfterPick by remember { mutableStateOf(false) }
    val wizIp by vm.wizIp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val first = remember(picking) { FocusRequester() }
    val appBtn = remember { FocusRequester() }
    // Leaving the picker puts focus back on the button that opened it
    var focusApp by remember { mutableStateOf(false) }
    val team = MatchConfig.Team
    val st = LocalHomeStyle.current
    val closePicker = {
        focusApp = !launchAfterPick
        picking = false
        launchAfterPick = false
    }

    // Back closes only the picker while it is open
    Dialog(
        onDismissRequest = { if (picking) closePicker() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .ignoreOpeningPress()
                .width(820.dp)
                .heightIn(max = 500.dp)
                .panel(st)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (picking) {
                Text(st.plain(t("📺  Maç uygulaması")), style = st.headline)
                Text(
                    if (launchAfterPick) t("Seçtiğin uygulamada maçı açarım") else t("Maçı izlediğin uygulamayı seç"),
                    style = st.body,
                    color = st.textDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(
                        t("Geri"),
                        onClick = closePicker,
                        leading = "←",
                        modifier = Modifier.focusRequester(first),
                    )
                    Chip(t("Seçimi kaldır"), onClick = { vm.setMatchApp(null); closePicker() })
                }
                LazyColumn(
                    Modifier.heightIn(max = 270.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(apps, key = { it.pkg }) { app ->
                        Chip(
                            st.mark(app.pkg == appPkg, spaces = 2) + app.label,
                            onClick = {
                                vm.setMatchApp(app.pkg)
                                if (launchAfterPick) {
                                    vm.launch(app)
                                    onDismiss()
                                } else {
                                    closePicker()
                                }
                            },
                        )
                    }
                }
            } else {
                // Two columns so the whole panel fits the 540dp screen without scrolling
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MatchLeft(vm, match, enabled, first, team.primary, team.secondary, onDismiss) {
                            launchAfterPick = true
                            picking = true
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Section(t("Ayarlar"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(
                                t("Işıklar: ") + if (enabled) t("Açık") else t("Kapalı"),
                                onClick = { vm.setMatchEnabled(!enabled) },
                                leading = "💡",
                                primary = enabled,
                            )
                            Chip(
                                t("Gol efekti: ") + if (goalEffect) t("Açık") else t("Kapalı"),
                                onClick = { vm.setMatchGoalEffect(!goalEffect) },
                                leading = "🎆",
                                primary = goalEffect,
                            )
                        }
                        Chip(
                            t("Gol efektini dene"),
                            onClick = {
                                Toast.makeText(
                                    context.applicationContext,
                                    if (wizIp != null) t("Gol efekti oynatılıyor") else t("Işık bulunamadı — önce Işık bul"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                vm.testGoalEffect()
                            },
                            leading = "⚡",
                        )

                        Section(t("Yayın gecikmesi (sn)"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MatchConfig.DelayOptions.forEach { s ->
                                Chip("$s", onClick = { vm.setMatchDelay(s) }, primary = s == delaySec)
                            }
                        }
                        Text(
                            t("Yayın geç kalıyorsa ışık golü ekrandan önce göstermesin diye bekler."),
                            style = st.type(11.sp, color = st.textMuted),
                        )

                        Section(t("Maçı izlediğim uygulama"))
                        val appLabel = appPkg?.let { p -> apps.firstOrNull { it.pkg == p }?.label ?: t("Yüklü değil") } ?: t("Seçilmedi")
                        Chip(
                            appLabel,
                            onClick = { launchAfterPick = false; picking = true },
                            leading = "📺",
                            modifier = Modifier.focusRequester(appBtn),
                        )

                        Section(t("Önizleme"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(t("Yaklaşan"), onClick = { vm.simulateMatch(MatchSamples.upcoming()) })
                            Chip(t("Canlı"), onClick = { vm.simulateMatch(MatchSamples.live()) })
                            Chip(t("Bitti"), onClick = { vm.simulateMatch(MatchSamples.finished()) })
                            Chip(t("Gerçek"), onClick = { vm.simulateMatch(null) }, leading = "↩")
                        }
                    }
                }
            }
        }
    }
    FocusSoon(if (focusApp && !picking) appBtn else first)
}

/** Title, scoreboard (or the empty state), "Maçı aç" and "Kapat". */
@Composable
private fun MatchLeft(
    vm: LauncherViewModel,
    m: MatchState?,
    enabled: Boolean,
    first: FocusRequester,
    red: Color,
    yellow: Color,
    onDismiss: () -> Unit,
    onPickApp: () -> Unit,
) {
    val st = LocalHomeStyle.current
    // The team's red-to-yellow gradient is Neon's; the others set the title flat
    Text(
        st.plain(t("⚽  Maç günü")),
        style = st.headline.fillWith(
            if (st.theme == HomeTheme.NEON) Brush.horizontalGradient(listOf(red, yellow)) else null,
            st.titleColor,
        ),
    )
    if (m != null) {
        Scoreboard(m)
    } else {
        // Same card, nothing to show
        val shape = st.shapeOf(20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(red.copy(alpha = 0.14f))
                .border(1.dp, red.copy(alpha = 0.5f), shape)
                .padding(horizontal = 16.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(t("Yaklaşan maç yok"), style = st.type(18.sp, FontWeight.Bold, role = TextRole.HEADLINE))
            Text(
                if (enabled) t("Fikstür yüklenemedi ya da sezon arasındasın. Biraz sonra tekrar bakılır.") else t("Maç günü kapalı. Sağdan açabilirsin."),
                style = st.type(12.sp, color = st.textDim),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip(
            t("Maçı aç"),
            onClick = { if (vm.launchMatchApp()) onDismiss() else onPickApp() },
            leading = "▶",
            primary = true,
            modifier = Modifier.focusRequester(first),
        )
        Chip(t("Kapat"), onClick = onDismiss)
    }
}

@Composable
private fun Section(title: String) {
    val st = LocalHomeStyle.current
    Text(st.caps(title), style = st.label, color = st.textDim)
}

/** Home side first, like a stadium board; scores arrive Galatasaray-first, so they are swapped for away games. */
@Composable
private fun Scoreboard(m: MatchState) {
    val team = MatchConfig.Team
    val st = LocalHomeStyle.current
    val shape = st.shapeOf(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(team.primary.copy(alpha = 0.14f))
            .border(1.dp, team.primary.copy(alpha = 0.5f), shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (m.home) {
                TeamSide(team.espnId, team.shortName, team.name, true, Modifier.weight(1f))
                Middle(m)
                TeamSide(m.opponentId, oppShort(m), m.opponent, false, Modifier.weight(1f))
            } else {
                TeamSide(m.opponentId, oppShort(m), m.opponent, false, Modifier.weight(1f))
                Middle(m)
                TeamSide(team.espnId, team.shortName, team.name, true, Modifier.weight(1f))
            }
        }
        Text(
            "${t(m.competition)} · ${if (m.home) t("İç saha") else t("Deplasman")}",
            style = st.type(12.sp, color = st.textDim),
        )
    }
}

@Composable
private fun TeamSide(logoId: String, short: String, full: String, ours: Boolean, modifier: Modifier) {
    val st = LocalHomeStyle.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(logoId, 56.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            short,
            style = st.type(
                26.sp,
                FontWeight.Black,
                // The team's yellow only reads on Neon's dark glass
                if (ours && st.theme == HomeTheme.NEON) MatchConfig.Team.secondary else st.text,
                TextRole.DISPLAY,
            ),
            maxLines = 1,
        )
        Text(
            full,
            style = st.type(12.sp, color = st.textDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Home side's goals first; [compact] for the chip ("2–1"), spaced for the scoreboard ("2 – 1"). */
private fun score(m: MatchState, gs: Int, opp: Int, compact: Boolean = false): String {
    val dash = if (compact) "–" else " – "
    val pad = if (compact) " " else ""
    return if (m.home) "$pad$gs$dash$opp$pad" else "$pad$opp$dash$gs$pad"
}

@Composable
private fun Middle(m: MatchState) {
    val st = LocalHomeStyle.current
    val big = st.type(36.sp, FontWeight.Black, role = TextRole.DISPLAY).fillWith(st.titleBrush, st.text)
    val small = st.type(11.sp, color = st.textDim)
    Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (m) {
            is MatchState.Upcoming -> {
                Text(m.kickoff.format(HourFmt), style = big, maxLines = 1)
                Text(m.kickoff.format(DayFmt), style = small, maxLines = 1)
                Text(upcomingWhen(m, System.currentTimeMillis()), style = small.copy(color = MatchConfig.Team.secondary), maxLines = 1)
            }
            is MatchState.Live -> {
                Text(score(m, m.gsScore, m.oppScore), style = big, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        t("CANLI") + (m.clock.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = small.copy(color = LiveRed, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
                Text(m.status, style = small, maxLines = 1)
            }
            is MatchState.Finished -> {
                Text(score(m, m.gsScore, m.oppScore), style = big, maxLines = 1)
                Text(t("Maç bitti"), style = small, maxLines = 1)
                Text(resultText(m.result), style = small.copy(color = resultColor(m.result, st), fontWeight = FontWeight.Bold), maxLines = 1)
            }
        }
    }
}
