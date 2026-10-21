package dev.mibox.neon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.SpeedState
import dev.mibox.neon.data.WeatherNow
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

val TR: Locale = Locale.forLanguageTag("tr-TR")
private val TimeFmt = DateTimeFormatter.ofPattern("HH:mm", TR)
private val DateFmt = DateTimeFormatter.ofPattern("EEE d MMM", TR)

/** Current time, ticking once a minute on the minute boundary. */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000 + 20)
        value = LocalDateTime.now()
    }
}

/** Design: y 70–140px → a 35dp row; chips 10dp apart, gear flush with the right margin. */
@Composable
fun TopBar(vm: LauncherViewModel, onOpenMenu: () -> Unit, onOpenLight: () -> Unit, onOpenControl: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(35.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClockWeatherChip(vm, onOpenControl)
        Spacer(Modifier.weight(1f))
        LightChip(vm, onOpenLight)
        SpeedChip(vm)
        RamChip(vm)
        NeonSurface(
            onClick = vm::openSystemSettings,
            onLongClick = onOpenMenu,
            modifier = Modifier.size(35.dp),
            shape = CircleShape,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Ayarlar",
                modifier = Modifier.size(20.dp).align(Alignment.Center),
                tint = Neon.Icon,
            )
        }
    }
}

@Composable
private fun ClockWeatherChip(vm: LauncherViewModel, onOpen: () -> Unit) {
    val now by rememberNow()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    NeonSurface(
        onClick = {
            vm.refreshWeather()
            onOpen()
        },
        modifier = Modifier.height(35.dp),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = 16.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(now.format(TimeFmt), style = BarText.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(8.dp))
            Text(
                now.format(DateFmt).replace(".", ""),
                style = BarText.copy(fontSize = 12.sp, color = Neon.TextSoft),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(1.dp).height(19.dp).background(Color.White.copy(alpha = 0.15f)))
            Spacer(Modifier.width(10.dp))
            WeatherIcon(weather, Modifier.size(width = 26.dp, height = 16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                weather?.let { "${it.tempC.roundToInt()}°" } ?: "–°",
                style = BarText.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.width(4.dp))
            Text("· ${city.name}", style = BarText.copy(fontSize = 11.sp, color = Neon.TextDim))
        }
    }
}

/** Where the design has its AirPlay chip: the WiZ backlight, its dot showing the light's color. */
@Composable
private fun LightChip(vm: LauncherViewModel, onOpen: () -> Unit) {
    val ip by vm.wizIp.collectAsStateWithLifecycle()
    val wiz by vm.wiz.collectAsStateWithLifecycle()
    val w = wiz
    val dot = when {
        ip == null || w == null || !w.on -> Neon.TextMuted
        w.sceneId == 0 && w.temp == 0 -> Color(w.r / 255f, w.g / 255f, w.b / 255f)
        else -> Neon.Ok
    }
    Chip(
        if (ip == null) "Işık bul" else "Işık",
        onClick = { vm.wizRefresh(); onOpen() },
        // Long press: straight into the first favorite (Parti) and back
        onLongClick = vm::wizQuick,
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
    }
}

@Composable
private fun SpeedChip(vm: LauncherViewModel) {
    val speed by vm.speed.collectAsStateWithLifecycle()
    val label = when (val s = speed) {
        SpeedState.Idle -> "Hız testi"
        is SpeedState.Running -> if (s.mbps == 0.0) "Ölçülüyor…" else "${fmt(s.mbps)} Mbps"
        is SpeedState.Done -> "${fmt(s.mbps)} Mbps"
        SpeedState.Failed -> "Bağlantı yok"
    }
    Chip(label, onClick = vm::runSpeedTest)
}

/** "CPU %66 ● [⚡ Boşalt]" in the design; RAM is what the box actually runs short of. */
@Composable
private fun RamChip(vm: LauncherViewModel) {
    val ram by vm.ram.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    val pct = ram.usedPercent
    val dot = when {
        pct < 60 -> Neon.Ok
        pct < 85 -> Neon.Amber
        else -> Neon.Danger
    }
    NeonSurface(onClick = vm::boost, modifier = Modifier.height(35.dp), glowColor = Neon.Danger) {
        Row(
            Modifier.fillMaxHeight().padding(start = 15.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("RAM %$pct", style = BarText.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(11.dp))
            Row(
                Modifier.height(27.dp).background(Neon.Danger, Pill).padding(start = 9.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(width = 8.dp, height = 18.dp)) { drawBolt() }
                Spacer(Modifier.width(7.dp))
                val f = freed
                Text(
                    if (f != null) "Kapatıldı +${f / (1024 * 1024)} MB" else "Boşalt",
                    style = BarText.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

/** The SVG's bolt path, M1612 92 l-10 20 h9 l-4 16 l16 -22 h-9 z, at half scale. */
private fun DrawScope.drawBolt() {
    val u = size.width / 8f
    val p = Path().apply {
        moveTo(5f * u, 0f)
        lineTo(0f, 10f * u)
        lineTo(4.5f * u, 10f * u)
        lineTo(2.5f * u, 18f * u)
        lineTo(8f * u, 7f * u)
        lineTo(3.5f * u, 7f * u)
        close()
    }
    drawPath(p, Color.White)
}

/** Tiny line-art weather glyphs in the design's cloud color. */
@Composable
fun WeatherIcon(w: WeatherNow?, modifier: Modifier) {
    val c = Neon.WeatherIcon.copy(alpha = 0.9f)
    Canvas(modifier) {
        val code = w?.code ?: 3
        val day = w?.isDay ?: true
        when (code) {
            0, 1 -> if (day) sun(c) else moon(c)
            2 -> {
                if (day) sun(c, Offset(size.width * 0.3f, size.height * 0.35f), 0.7f) else moon(c)
                cloud(c, 0.75f)
            }
            in 51..67, in 80..82 -> {
                cloud(c, 0.7f)
                drops(c, dots = false)
            }
            in 71..77, 85, 86 -> {
                cloud(c, 0.7f)
                drops(c, dots = true)
            }
            in 95..99 -> {
                cloud(c, 0.7f)
                drawLine(c, Offset(size.width * 0.55f, size.height * 0.62f), Offset(size.width * 0.45f, size.height), 1.5.dp.toPx())
            }
            45, 48 -> repeat(3) { i ->
                val y = size.height * (0.25f + i * 0.28f)
                drawLine(c, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 1.5.dp.toPx(), StrokeCap.Round)
            }
            else -> cloud(c, 1f)
        }
    }
}

private fun DrawScope.cloud(c: Color, scale: Float) {
    val h = size.height * scale
    val w = size.width
    val base = h
    drawCircle(c, h * 0.32f, Offset(w * 0.32f, base - h * 0.34f))
    drawCircle(c, h * 0.45f, Offset(w * 0.55f, base - h * 0.48f))
    drawCircle(c, h * 0.28f, Offset(w * 0.78f, base - h * 0.3f))
    drawRoundRect(
        c,
        topLeft = Offset(w * 0.15f, base - h * 0.32f),
        size = Size(w * 0.75f, h * 0.32f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.16f),
    )
}

private fun DrawScope.sun(c: Color, center: Offset = Offset(size.width / 2, size.height / 2), scale: Float = 1f) {
    val r = size.height * 0.24f * scale
    drawCircle(c, r, center)
    repeat(8) { i ->
        val a = i * Math.PI / 4
        val from = Offset(center.x + (r * 1.45f * cos(a)).toFloat(), center.y + (r * 1.45f * sin(a)).toFloat())
        val to = Offset(center.x + (r * 1.95f * cos(a)).toFloat(), center.y + (r * 1.95f * sin(a)).toFloat())
        drawLine(c, from, to, 1.2.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.moon(c: Color) {
    val r = size.height * 0.42f
    val center = Offset(size.width / 2, size.height / 2)
    val p = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(center, r))
        val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(center.x + r * 0.55f, center.y - r * 0.35f), r)) }
        op(this, cut, androidx.compose.ui.graphics.PathOperation.Difference)
    }
    drawPath(p, c)
}

private fun DrawScope.drops(c: Color, dots: Boolean) {
    val y0 = size.height * 0.78f
    listOf(0.35f, 0.55f, 0.75f).forEach { fx ->
        val x = size.width * fx
        if (dots) {
            drawCircle(c, 1.2.dp.toPx(), Offset(x, y0 + size.height * 0.1f))
        } else {
            drawLine(c, Offset(x, y0), Offset(x - size.width * 0.06f, size.height), 1.2.dp.toPx(), StrokeCap.Round)
        }
    }
}

private fun fmt(mbps: Double) = String.format(TR, "%.1f", mbps)
