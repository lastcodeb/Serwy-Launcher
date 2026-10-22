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
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.MatchResult
import dev.mibox.neon.data.MatchSamples
import dev.mibox.neon.data.MatchState
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
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
        h == 0L -> "$mm dk"
        mm == 0L -> "$h sa"
        else -> "$h sa $mm dk"
    }
}

/** Countdown in the last 6 hours, then "Bugün 21:30", "Yarın 21:30", "Pzt 21:30" (6 days) or "26 Eki 21:30". */
private fun upcomingWhen(m: MatchState.Upcoming, nowMs: Long): String {
    val left = m.kickoffMs - nowMs
    if (left <= 0) return "Başlıyor"
    if (left <= COUNTDOWN_MS) return countdown(left)
    val today = Instant.ofEpochMilli(nowMs).atZone(m.kickoff.zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, m.kickoff.toLocalDate())
    return when {
        days <= 0L -> "Bugün " + m.kickoff.format(HourFmt)
        days == 1L -> "Yarın " + m.kickoff.format(HourFmt)
        days <= 6L -> m.kickoff.format(KickFmt)
        else -> m.kickoff.format(DayMonFmt)
    }
}

private fun resultText(r: MatchResult) = when (r) {
    MatchResult.WIN -> "Galibiyet"
    MatchResult.DRAW -> "Beraberlik"
    MatchResult.LOSS -> "Mağlubiyet"
}

private fun resultColor(r: MatchResult) = when (r) {
    MatchResult.WIN -> Neon.Ok
    MatchResult.DRAW -> Neon.Amber
    MatchResult.LOSS -> Neon.Danger
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

/**
 * Permanent top bar chip while match day is on: a red/yellow accent (which already says
 * Galatasaray), then "FEN · Bugün 21:30" / "GS 2–1 FEN · 67'" (with a pulsing dot) /
 * "GS 4–2 LIL · Galibiyet", or "Yaklaşan maç yok" while [m] is null. Kept narrow for the 864dp bar.
 */
@Composable
fun MatchChip(m: MatchState?, nowMs: Long, onOpen: () -> Unit) {
    val team = MatchConfig.Team
    val style = BarText.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    NeonSurface(
        onClick = onOpen,
        modifier = Modifier.height(35.dp).widthIn(max = 170.dp),
        glowColor = team.primary,
        container = team.primary.copy(alpha = if (m is MatchState.Live) 0.24f else 0.12f),
        focusedContainer = team.primary.copy(alpha = 0.36f),
        stroke = team.primary.copy(alpha = 0.6f),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = 9.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Red over yellow
            Column(Modifier.size(width = 4.dp, height = 17.dp).clip(Pill)) {
                Box(Modifier.weight(1f).fillMaxWidth().background(team.primary))
                Box(Modifier.weight(1f).fillMaxWidth().background(team.secondary))
            }
            Spacer(Modifier.width(6.dp))
            when (m) {
                null -> Text("Yaklaşan maç yok", style = style.copy(color = Neon.TextSoft), maxLines = 1)
                is MatchState.Upcoming -> Text(
                    "${oppShort(m)} · ${upcomingWhen(m, nowMs)}",
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                is MatchState.Live -> {
                    PulseDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${team.shortName} ${m.gsScore}–${m.oppScore} ${oppShort(m)}" +
                            (m.clock.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is MatchState.Finished -> {
                    Text("${team.shortName} ${m.gsScore}–${m.oppScore} ${oppShort(m)} · ", style = style, maxLines = 1)
                    Text(resultText(m.result), style = style.copy(color = resultColor(m.result)), maxLines = 1)
                }
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
                .clip(PanelShape)
                .background(Neon.Surface)
                .border(1.dp, Neon.ChipStroke, PanelShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (picking) {
                Text("📺  Maç uygulaması", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (launchAfterPick) "Seçtiğin uygulamada maçı açarım" else "Maçı izlediğin uygulamayı seç",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Neon.TextDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(
                        "Geri",
                        onClick = closePicker,
                        leading = "←",
                        modifier = Modifier.focusRequester(first),
                    )
                    Chip("Seçimi kaldır", onClick = { vm.setMatchApp(null); closePicker() })
                }
                LazyColumn(
                    Modifier.heightIn(max = 270.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(apps, key = { it.pkg }) { app ->
                        Chip(
                            (if (app.pkg == appPkg) "✓  " else "") + app.label,
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
                        Section("Ayarlar")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(
                                "Işıklar: " + if (enabled) "Açık" else "Kapalı",
                                onClick = { vm.setMatchEnabled(!enabled) },
                                leading = "💡",
                                primary = enabled,
                            )
                            Chip(
                                "Gol efekti: " + if (goalEffect) "Açık" else "Kapalı",
                                onClick = { vm.setMatchGoalEffect(!goalEffect) },
                                leading = "🎆",
                                primary = goalEffect,
                            )
                        }
                        Chip(
                            "Gol efektini dene",
                            onClick = {
                                Toast.makeText(
                                    context.applicationContext,
                                    if (wizIp != null) "Gol efekti oynatılıyor" else "Işık bulunamadı — önce Işık bul",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                vm.testGoalEffect()
                            },
                            leading = "⚡",
                        )

                        Section("Yayın gecikmesi (sn)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MatchConfig.DelayOptions.forEach { s ->
                                Chip("$s", onClick = { vm.setMatchDelay(s) }, primary = s == delaySec)
                            }
                        }
                        Text(
                            "Yayın geç kalıyorsa ışık golü ekrandan önce göstermesin diye bekler.",
                            style = TextStyle(fontFamily = Poppins, fontSize = 11.sp, color = Neon.TextMuted),
                        )

                        Section("Maçı izlediğim uygulama")
                        val appLabel = appPkg?.let { p -> apps.firstOrNull { it.pkg == p }?.label ?: "Yüklü değil" } ?: "Seçilmedi"
                        Chip(
                            appLabel,
                            onClick = { launchAfterPick = false; picking = true },
                            leading = "📺",
                            modifier = Modifier.focusRequester(appBtn),
                        )

                        Section("Önizleme")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Yaklaşan", onClick = { vm.simulateMatch(MatchSamples.upcoming()) })
                            Chip("Canlı", onClick = { vm.simulateMatch(MatchSamples.live()) })
                            Chip("Bitti", onClick = { vm.simulateMatch(MatchSamples.finished()) })
                            Chip("Gerçek", onClick = { vm.simulateMatch(null) }, leading = "↩")
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
    Text(
        "⚽  Maç günü",
        style = MaterialTheme.typography.headlineSmall.copy(brush = Brush.horizontalGradient(listOf(red, yellow))),
    )
    if (m != null) {
        Scoreboard(m)
    } else {
        // Same card, nothing to show
        val shape = RoundedCornerShape(20.dp)
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
            Text("Yaklaşan maç yok", style = TextStyle(fontFamily = Poppins, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            Text(
                if (enabled) "Fikstür yüklenemedi ya da sezon arasındasın. Biraz sonra tekrar bakılır." else "Maç günü kapalı. Sağdan açabilirsin.",
                style = TextStyle(fontFamily = Poppins, fontSize = 12.sp, color = Neon.TextDim),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip(
            "Maçı aç",
            onClick = { if (vm.launchMatchApp()) onDismiss() else onPickApp() },
            leading = "▶",
            primary = true,
            modifier = Modifier.focusRequester(first),
        )
        Chip("Kapat", onClick = onDismiss)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = Neon.TextDim)
}

/** Home side first, like a stadium board; scores arrive Galatasaray-first, so they are swapped for away games. */
@Composable
private fun Scoreboard(m: MatchState) {
    val team = MatchConfig.Team
    val shape = RoundedCornerShape(20.dp)
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
                TeamSide(team.shortName, team.name, true, Modifier.weight(1f))
                Middle(m)
                TeamSide(oppShort(m), m.opponent, false, Modifier.weight(1f))
            } else {
                TeamSide(oppShort(m), m.opponent, false, Modifier.weight(1f))
                Middle(m)
                TeamSide(team.shortName, team.name, true, Modifier.weight(1f))
            }
        }
        Text(
            "${m.competition} · ${if (m.home) "İç saha" else "Deplasman"}",
            style = TextStyle(fontFamily = Poppins, fontSize = 12.sp, color = Neon.TextDim),
        )
    }
}

@Composable
private fun TeamSide(short: String, full: String, ours: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            short,
            style = TextStyle(
                fontFamily = Poppins,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = if (ours) MatchConfig.Team.secondary else Color.White,
            ),
            maxLines = 1,
        )
        Text(
            full,
            style = TextStyle(fontFamily = Poppins, fontSize = 12.sp, color = Neon.TextDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun score(m: MatchState, gs: Int, opp: Int) = if (m.home) "$gs – $opp" else "$opp – $gs"

@Composable
private fun Middle(m: MatchState) {
    val big = TextStyle(fontFamily = Poppins, fontSize = 36.sp, fontWeight = FontWeight.Black, brush = Neon.TitleBrush)
    val small = TextStyle(fontFamily = Poppins, fontSize = 11.sp, color = Neon.TextDim)
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
                        "CANLI" + (m.clock.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = small.copy(color = LiveRed, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
                Text(m.status, style = small, maxLines = 1)
            }
            is MatchState.Finished -> {
                Text(score(m, m.gsScore, m.oppScore), style = big, maxLines = 1)
                Text("Maç bitti", style = small, maxLines = 1)
                Text(resultText(m.result), style = small.copy(color = resultColor(m.result), fontWeight = FontWeight.Bold), maxLines = 1)
            }
        }
    }
}
