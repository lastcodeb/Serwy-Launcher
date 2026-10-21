package dev.mibox.neon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.Wiz
import dev.mibox.neon.data.WizPilot
import kotlinx.coroutines.delay

private val PanelShape = RoundedCornerShape(28.dp)

private val Swatches = listOf(
    Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF3B82F6), Color(0xFF22D3EE),
    Color(0xFF22C55E), Color(0xFFFACC15), Color(0xFFF97316), Color(0xFFEF4444),
)

private val Whites = listOf(2700 to "Sıcak", 4000 to "Nötr", 6500 to "Gün ışığı")

@Composable
fun LightPanel(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val ip by vm.wizIp.collectAsStateWithLifecycle()
    val wiz by vm.wiz.collectAsStateWithLifecycle()
    val ambient by vm.ambient.collectAsStateWithLifecycle()
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .ignoreOpeningPress()
                .width(780.dp)
                .heightIn(max = 500.dp)
                .clip(PanelShape)
                .background(Neon.Surface)
                .border(1.dp, Neon.ChipStroke, PanelShape)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💡  Arka ışık", style = MaterialTheme.typography.headlineSmall.copy(brush = Neon.Gradient))
                Spacer(Modifier.width(14.dp))
                Text(status(ip, wiz), style = MaterialTheme.typography.bodyMedium, color = Neon.TextDim)
            }

            if (ip == null) {
                Text(
                    "WiZ Sync Box ağda bulunamadı. Box ile aynı Wi-Fi'da olduğundan ve WiZ uygulamasında " +
                        "\"Yerel iletişime izin ver\" seçeneğinin açık olduğundan emin ol.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Chip("Tekrar ara", onClick = vm::wizRefresh, leading = "🔍", modifier = Modifier.focusRequester(first))
                return@Column
            }

            Section("Hızlı erişim")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                items(favorites, key = { it }) { id ->
                    Chip(
                        Wiz.presetName(id),
                        onClick = { vm.wizPreset(id) },
                        leading = Wiz.presetEmoji(id),
                        primary = Wiz.isActive(id, wiz),
                        modifier = if (id == favorites.first()) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(
                    if (wiz?.on == true) "Kapat" else "Aç",
                    onClick = vm::wizToggle,
                    leading = "⏻",
                    primary = wiz?.on != true,
                    modifier = if (favorites.isEmpty()) Modifier.focusRequester(first) else Modifier,
                )
                Chip("Önceki ışık", onClick = vm::wizBackToPrevious, leading = "↩")
                Chip(if (ambient) "Ambiyans açık" else "Ambiyans kapalı", onClick = vm::wizToggleAmbient, leading = "✨")
                Chip(sleepLabel(sleepAt), onClick = vm::wizCycleSleep, leading = "🌙")
                Chip("Hava ışığı", onClick = vm::wizWeather, leading = "⛅")
            }

            Section("Parlaklık")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip("", onClick = { vm.wizDim(-10) }, leading = "−")
                BrightnessBar((wiz?.dimming ?: 0) / 100f)
                Chip("", onClick = { vm.wizDim(10) }, leading = "+")
                Text("%${wiz?.dimming ?: 0}", style = MaterialTheme.typography.titleMedium)
            }

            Section("Renk")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Swatches.forEach { c -> Swatch(c) { vm.wizColor(c) } }
            }

            Section("Beyaz")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Whites.forEach { (k, name) -> Chip("$name · ${k}K", onClick = { vm.wizWhite(k) }) }
            }

            Section("Sahneler  ·  uzun bas: hızlı erişime ekle / çıkar")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                items(Wiz.Scenes, key = { it.first }) { (id, name) ->
                    val preset = "scene:$id"
                    Chip(
                        if (preset in favorites) "★ $name" else name,
                        onClick = { vm.wizScene(id) },
                        onLongClick = { vm.toggleFavorite(preset) },
                        primary = wiz?.sceneId == id,
                    )
                }
            }

            if ((wiz?.sceneId ?: 0) > 0) {
                Section("Efekt hızı")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("", onClick = { vm.wizSpeed(-20) }, leading = "−")
                    BrightnessBar((wiz?.speed ?: 100) / 200f)
                    Chip("", onClick = { vm.wizSpeed(20) }, leading = "+")
                }
            }
        }
    }
    FocusLater(first)
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = Neon.TextDim)
}

@Composable
private fun Swatch(color: Color, onClick: () -> Unit) {
    NeonSurface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        glowColor = color,
        focusedScale = 1.18f,
        container = color,
        focusedContainer = color,
    ) {}
}

@Composable
private fun BrightnessBar(fraction: Float) {
    Box(
        Modifier
            .width(360.dp)
            .height(10.dp)
            .background(Color.White.copy(alpha = 0.1f), Pill),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(10.dp)
                .background(Neon.Gradient, Pill),
        )
    }
}

@Composable
private fun FocusLater(requester: FocusRequester) {
    LaunchedEffect(requester) {
        repeat(5) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

private fun status(ip: String?, p: WizPilot?): String = when {
    ip == null -> "Bulunamadı"
    p == null -> "Bağlanıyor… $ip"
    !p.on -> "Kapalı"
    p.sceneId > 0 -> "Açık · %${p.dimming} · ${Wiz.sceneName(p.sceneId) ?: "Sahne ${p.sceneId}"}"
    p.temp > 0 -> "Açık · %${p.dimming} · ${p.temp}K"
    else -> "Açık · %${p.dimming} · Renk"
}

private fun sleepLabel(at: Long?): String {
    if (at == null) return "Uyku kapalı"
    val min = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    return "Uyku $min dk"
}
