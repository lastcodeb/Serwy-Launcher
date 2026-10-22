package dev.mibox.neon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.HomeTheme
import dev.mibox.neon.data.SpeedResult
import dev.mibox.neon.data.SpeedState
import dev.mibox.neon.data.Weather
import dev.mibox.neon.data.Wiz
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import dev.mibox.neon.i18n.t

private val BigTime = DateTimeFormatter.ofPattern("HH:mm", TR)
private val LongDate = DateTimeFormatter.ofPattern("d MMMM EEEE", TR)

/** Opened from the clock chip: time and weather large on the left, light and system on the right. */
@Composable
fun ControlCenter(
    vm: LauncherViewModel,
    onDismiss: () -> Unit,
    onOpenLight: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenNet: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenMatch: () -> Unit,
    onOpenTheme: () -> Unit,
) {
    val st = LocalHomeStyle.current
    val hidden by vm.hiddenApps.collectAsStateWithLifecycle()
    val wrappedBadge by vm.wrappedBadge.collectAsStateWithLifecycle()
    val now by rememberNow()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val ram by vm.ram.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val net by vm.netNow.collectAsStateWithLifecycle()
    val wizIp by vm.wizIp.collectAsStateWithLifecycle()
    val wiz by vm.wiz.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .ignoreOpeningPress()
                .width(820.dp)
                .panel(st)
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            Column(Modifier.width(270.dp)) {
                Text(
                    now.format(BigTime),
                    style = st.type(68.sp, FontWeight.Black, role = TextRole.DISPLAY).fillWith(st.titleBrush, st.text),
                )
                Text(
                    now.format(LongDate),
                    style = st.type(15.sp, color = st.textSoft),
                )
                Spacer(Modifier.height(26.dp))
                val w = weather
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherIcon(w, Modifier.size(width = 52.dp, height = 32.dp), st.weatherIcon)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        w?.let { "${it.tempC.roundToInt()}°" } ?: "–°",
                        style = st.type(40.sp, FontWeight.Bold, role = TextRole.DISPLAY),
                    )
                }
                Text(
                    listOfNotNull(w?.let { Weather.describe(it.code, it.isDay).second }, city.name).joinToString(" · "),
                    style = st.type(14.sp, color = st.textDim),
                )
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section(t("Işık"), lightStatus(wizIp, wiz))
                if (wizIp != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        favorites.take(3).forEachIndexed { i, id ->
                            Chip(
                                Wiz.presetName(id),
                                onClick = { vm.wizPreset(id) },
                                leading = Wiz.presetEmoji(id),
                                primary = Wiz.isActive(id, wiz),
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip(if (wiz?.on == true) t("Kapat") else t("Aç"), onClick = vm::wizToggle, leading = "⏻")
                        Chip(t("Tüm ışık ayarları"), onClick = onOpenLight, leading = "💡")
                    }
                } else {
                    Chip(t("Işığı bul"), onClick = vm::wizRefresh, leading = "🔍", modifier = Modifier.focusRequester(first))
                }

                Spacer(Modifier.height(4.dp))
                Section(t("Sistem"), null)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val f = freed
                    Chip(
                        if (f != null) {
                            t("Kapatıldı · +{0} MB", f / (1024 * 1024))
                        } else if (st.theme == HomeTheme.NEON) {
                            t("Arka plandaki uygulamaları kapat · RAM %{0}", ram.usedPercent)
                        } else {
                            // Shorter: the wide fonts would squeeze the speed chip beside it
                            t("Belleği boşalt · RAM %{0}", ram.usedPercent)
                        },
                        onClick = vm::boost,
                        leading = "⚡",
                    )
                    Chip(speedLabel(speed, net.last), onClick = vm::runSpeedTest, leading = "📶", modifier = if (st.theme == HomeTheme.NEON) Modifier else Modifier.widthIn(min = 100.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (wizIp != null) Chip(sleepLabel(sleepAt), onClick = vm::wizCycleSleep, leading = "🌙")
                    Chip(t("Android TV ayarları"), onClick = { onDismiss(); vm.openSystemSettings() }, leading = "⚙️")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(t("Gizli uygulamalar ({0}) · Şehir", hidden.size), onClick = onOpenMenu, leading = "👁")
                    Chip(t("Tema"), onClick = onOpenTheme, leading = "🎨")
                }

                Spacer(Modifier.height(4.dp))
                Section(t("Keşfet"), null)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(t("Ağ durumu"), onClick = onOpenNet, leading = "📡")
                    Chip(
                        "Neon Wrapped",
                        onClick = onOpenWrapped,
                        leading = "✨",
                        // Unseen summary of last month
                        trailing = if (wrappedBadge) {
                            { Box(Modifier.size(7.dp).background(st.accent, CircleShape)) }
                        } else {
                            null
                        },
                    )
                    Chip(t("Maç günü"), onClick = onOpenMatch, leading = "⚽")
                }
            }
        }
    }
    LaunchedEffect(first) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

@Composable
private fun Section(title: String, status: String?) {
    val st = LocalHomeStyle.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(st.caps(title), style = st.type(15.sp, FontWeight.Bold, role = TextRole.LABEL))
        if (status != null) {
            Spacer(Modifier.width(10.dp))
            Text(st.caps(status), style = st.type(12.sp, color = st.textMuted, role = TextRole.LABEL))
        }
    }
}

private fun lightStatus(ip: String?, p: dev.mibox.neon.data.WizPilot?): String = when {
    ip == null -> t("bulunamadı")
    p == null -> t("bağlanıyor…")
    !p.on -> t("kapalı")
    p.sceneId > 0 -> t("{0} · %{1}", Wiz.sceneName(p.sceneId) ?: t("Sahne"), p.dimming)
    else -> t("açık · %{0}", p.dimming)
}

private fun speedLabel(s: SpeedState, last: SpeedResult?): String = when (s) {
    // After a restart nothing has run yet: show the newest saved result, as the top bar does
    SpeedState.Idle -> last?.let { "${String.format(TR, "%.1f", it.mbps)} Mbps" } ?: t("Hız testi")
    is SpeedState.Running -> if (s.mbps == 0.0) t("Ölçülüyor…") else "${String.format(TR, "%.1f", s.mbps)} Mbps"
    is SpeedState.Done -> "${String.format(TR, "%.1f", s.mbps)} Mbps"
    SpeedState.Failed -> t("Bağlantı yok")
}

private fun sleepLabel(at: Long?): String {
    if (at == null) return t("Uyku zamanlayıcısı")
    val min = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    return t("Uyku {0} dk", min)
}
