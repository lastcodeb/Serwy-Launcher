package dev.mibox.neon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.LinkType
import dev.mibox.neon.data.NetHealth
import dev.mibox.neon.data.NetVerdict
import dev.mibox.neon.data.SpeedResult
import dev.mibox.neon.data.SpeedState

private val PanelShape = RoundedCornerShape(28.dp)

/** Whole Mbps from 10 up ("24"), one decimal below ("6,4"); never rounds up past a threshold. */
fun fmtMbps(mbps: Double): String = if (mbps >= 10) mbps.toInt().toString() else String.format(TR, "%.1f", mbps)

fun verdictColor(v: NetVerdict): Color = when (v) {
    NetVerdict.UHD_OK -> Neon.Ok
    NetVerdict.HD_OK -> Neon.Amber
    NetVerdict.WEAK -> Neon.Danger
    NetVerdict.UNKNOWN -> Neon.TextMuted
}

fun verdictText(v: NetVerdict): String = when (v) {
    NetVerdict.UHD_OK -> "4K için uygun"
    NetVerdict.HD_OK -> "HD için yeterli"
    NetVerdict.WEAK -> "Zayıf bağlantı"
    NetVerdict.UNKNOWN -> "Ölçüm yok"
}

private fun linkText(t: LinkType): String = when (t) {
    LinkType.ETHERNET -> "Kablolu"
    LinkType.WIFI -> "Wi-Fi"
    LinkType.OTHER -> "Diğer"
    LinkType.NONE -> "Bağlantı yok"
}

private fun agoText(at: Long): String {
    val m = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "az önce"
        m < 60 -> "$m dk önce"
        m < 24 * 60 -> "${m / 60} sa önce"
        else -> "${m / (24 * 60)} gün önce"
    }
}

private fun oneDecimal(mbps: Double) = String.format(TR, "%.1f", mbps)

/** "Ağ durumu": connection, Wi-Fi signal, latency, newest result, verdict and the last 30 results. */
@Composable
fun NetPanel(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val net by vm.netNow.collectAsStateWithLifecycle()
    val history by vm.netHistory.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    // A fresh ping whenever the card opens
    LaunchedEffect(Unit) { vm.refreshNet(latency = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .ignoreOpeningPress()
                .width(700.dp)
                .heightIn(max = 500.dp)
                .clip(PanelShape)
                .background(Neon.Surface)
                .border(1.dp, Neon.ChipStroke, PanelShape)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📡  Ağ durumu", style = MaterialTheme.typography.headlineSmall.copy(brush = Neon.Gradient))
                Spacer(Modifier.weight(1f))
                VerdictBadge(net.verdict)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatRow("Bağlantı") { StatText(linkText(net.link)) }
                    if (net.link == LinkType.WIFI) {
                        StatRow("Sinyal") {
                            SignalBars(net.signalBars)
                            Spacer(Modifier.width(8.dp))
                            StatText(net.rssiDbm?.let { "$it dBm" } ?: "–")
                        }
                        StatRow("Bağlantı hızı") { StatText(net.linkMbps?.let { "$it Mbps" } ?: "–") }
                    }
                    StatRow("Gecikme") { StatText(net.latencyMs?.let { "$it ms" } ?: "–") }
                    val last = net.last
                    StatRow("Son ölçüm") {
                        StatText(if (last == null) "Henüz yok" else "${oneDecimal(last.mbps)} Mbps")
                    }
                    if (last != null) {
                        Text(
                            agoText(last.at) + if (last.auto) " · otomatik" else "",
                            modifier = Modifier.align(Alignment.End),
                            style = TextStyle(fontFamily = Poppins, fontSize = 11.sp, color = Neon.TextMuted),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Son ${history.size} ölçüm",
                        style = TextStyle(fontFamily = Poppins, fontSize = 13.sp, color = Neon.TextDim),
                    )
                    HistoryChart(history)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(
                    if (speed is SpeedState.Running) "Ölçülüyor…" else "Hızı ölç",
                    onClick = vm::runSpeedTest,
                    leading = "⚡",
                    primary = true,
                    modifier = Modifier.focusRequester(first),
                )
                Chip("Kapat", onClick = onDismiss)
            }
        }
    }
    FocusSoon(first)
}

@Composable
private fun VerdictBadge(v: NetVerdict) {
    val c = verdictColor(v)
    Row(
        Modifier.height(30.dp).background(c.copy(alpha = 0.16f), Pill).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(verdictText(v), style = TextStyle(fontFamily = Poppins, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c))
    }
}

@Composable
private fun StatRow(label: String, value: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyle(fontFamily = Poppins, fontSize = 13.sp, color = Neon.TextDim))
        Spacer(Modifier.weight(1f))
        value()
    }
}

@Composable
private fun StatText(text: String) {
    Text(text, style = TextStyle(fontFamily = Poppins, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
}

/** Four bars, filled up to [bars] (0..4). */
@Composable
private fun SignalBars(bars: Int?) {
    Canvas(Modifier.size(width = 22.dp, height = 16.dp)) {
        val bw = size.width / 5.5f
        val gap = bw * 0.5f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                color = if (i < (bars ?: 0)) Neon.Ok else Color.White.copy(alpha = 0.18f),
                topLeft = Offset(i * (bw + gap), size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/** One bar per result, newest at the right edge, colored by what it means for streaming. */
@Composable
private fun HistoryChart(history: List<SpeedResult>) {
    if (history.isEmpty()) {
        Text(
            "Henüz ölçüm yok. Hız testi yapınca burada görünür.",
            style = TextStyle(fontFamily = Poppins, fontSize = 12.sp, color = Neon.TextMuted),
        )
        return
    }
    val top = maxOf(history.maxOf { it.mbps }, 30.0).toFloat()
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val slot = size.width / NetHealth.HISTORY_MAX
        val bw = slot * 0.62f
        // The 4K threshold
        val y25 = size.height * (1f - NetHealth.UHD_MBPS.toFloat() / top)
        drawLine(
            Color.White.copy(alpha = 0.2f),
            Offset(0f, y25),
            Offset(size.width, y25),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
        )
        history.forEachIndexed { i, r ->
            val h = (size.height * (r.mbps.toFloat() / top)).coerceAtLeast(2.dp.toPx())
            val color = when {
                r.mbps >= NetHealth.UHD_MBPS -> Neon.Ok
                r.mbps >= NetHealth.HD_MBPS -> Neon.Amber
                else -> Neon.Danger
            }
            drawRoundRect(
                color = color.copy(alpha = if (r.auto) 0.55f else 1f),
                topLeft = Offset(size.width - (history.size - i) * slot + (slot - bw) / 2f, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        val small = TextStyle(fontFamily = Poppins, fontSize = 11.sp, color = Neon.TextMuted)
        Text("min ${oneDecimal(history.minOf { it.mbps })}", style = small)
        Spacer(Modifier.weight(1f))
        Text("maks ${oneDecimal(history.maxOf { it.mbps })} Mbps", style = small)
    }
    Text(
        "Kesik çizgi: 4K sınırı (25 Mbps) · soluk: otomatik ölçüm",
        style = TextStyle(fontFamily = Poppins, fontSize = 10.sp, color = Neon.TextMuted),
    )
}
