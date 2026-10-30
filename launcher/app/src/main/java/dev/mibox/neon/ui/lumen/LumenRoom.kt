package dev.mibox.neon.ui.lumen

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.NetVerdict
import dev.mibox.neon.data.SpeedState
import dev.mibox.neon.data.Wiz
import dev.mibox.neon.data.WizPilot
import dev.mibox.neon.i18n.t
import dev.mibox.neon.ui.Chip
import dev.mibox.neon.ui.LumenDisplay
import dev.mibox.neon.ui.LumenPalette
import dev.mibox.neon.ui.LumenSans
import dev.mibox.neon.ui.NeonSurface
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.ignoreOpeningPress
import kotlinx.coroutines.delay

private val L = LumenPalette

/**
 * Lumen's control center, "Oda" (the room): a sheet from the right with the backlight first
 * (on/off, brightness, the quick scenes, follow-the-screen, sleep), then the system and the
 * other panels. Opened from the weather, the clock or the light in the top bar.
 */
@Composable
fun LumenRoom(
    vm: LauncherViewModel,
    accent: Color = L.Amber,
    onDismiss: () -> Unit,
    onOpenLight: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenNet: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenMatch: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMedia: () -> Unit,
) {
    val wizIp by vm.wizIp.collectAsStateWithLifecycle()
    val wiz by vm.wiz.collectAsStateWithLifecycle()
    val presets by vm.favorites.collectAsStateWithLifecycle()
    val ambient by vm.ambient.collectAsStateWithLifecycle()
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    val ram by vm.ram.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    val net by vm.netNow.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      CompositionLocalProvider(LocalRoomAccent provides accent) {
        Box(Modifier.fillMaxSize().ignoreOpeningPress(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .padding(16.dp)
                    .width(380.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(L.Surface)
                    .border(1.dp, L.Hairline, RoundedCornerShape(18.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Oda"), style = TextStyle(fontFamily = LumenDisplay, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = L.Text))
                    Spacer(Modifier.weight(1f))
                    Text(t("Geri: kapat"), style = TextStyle(fontFamily = LumenSans, fontSize = 10.sp, color = L.Muted))
                }

                // The backlight
                val p = wiz
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Arka ışık"), style = TextStyle(fontFamily = LumenSans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = L.Text))
                        Text(lightStatus(wizIp, p), style = TextStyle(fontFamily = LumenSans, fontSize = 10.sp, color = L.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (wizIp == null) {
                        Chip(t("Işığı bul"), onClick = vm::wizRefresh, modifier = Modifier.focusRequester(first))
                    } else {
                        Toggle(p?.on == true, onClick = vm::wizToggle, modifier = Modifier.focusRequester(first))
                    }
                }
                if (wizIp != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        RoundButton("−", t("Kıs")) { vm.wizDim(-10) }
                        val dim = p?.dimming ?: 0
                        Box(
                            Modifier
                                .weight(1f)
                                .height(30.dp)
                                .clip(RoundedCornerShape(50))
                                .background(L.Raised),
                        ) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((if (p?.on == true) dim else 0) / 100f).background(accent))
                            Text(
                                if (p?.on == true) t("%{0}", dim) else t("kapalı"),
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                                style = TextStyle(fontFamily = LumenSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (p?.on == true && dim > 18) L.Ground else L.Dim),
                            )
                        }
                        RoundButton("+", t("Aç")) { vm.wizDim(10) }
                    }

                    // Quick scenes: the user's presets, two colors each
                    presets.take(6).chunked(3).forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            line.forEach { id -> SceneTile(id, Wiz.isActive(id, p), Modifier.weight(1f)) { vm.wizPreset(id) } }
                            repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("Ekranı takip et"), style = TextStyle(fontFamily = LumenSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = L.Text))
                            Text(t("Ana ekranda ışık, seçili kartın rengini alır"), style = TextStyle(fontFamily = LumenSans, fontSize = 10.sp, color = L.Muted), maxLines = 1)
                        }
                        Toggle(ambient, onClick = vm::wizToggleAmbient)
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))

                // The system, quietly
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val f = freed
                    InfoTile(
                        t("Bellek · %{0}", ram.usedPercent),
                        if (f != null) t("Kapatıldı · +{0} MB", f / (1024 * 1024)) else t("Arka planı temizle"),
                        L.Text,
                        Modifier.weight(1f),
                        onClick = vm::boost,
                    )
                    val mbps = when (val s = speed) {
                        is SpeedState.Running -> s.mbps
                        is SpeedState.Done -> s.mbps
                        else -> net.last?.mbps
                    }
                    InfoTile(
                        if (mbps != null) t("Ağ · {0} Mbps", String.format(TR, "%.1f", mbps)) else t("Ağ"),
                        when (net.verdict) {
                            NetVerdict.UHD_OK -> t("4K için uygun")
                            NetVerdict.HD_OK -> t("HD için yeterli")
                            NetVerdict.WEAK -> t("Zayıf bağlantı")
                            NetVerdict.UNKNOWN -> t("Hızı ölç")
                        },
                        if (net.verdict == NetVerdict.UHD_OK) L.Ok else L.Text,
                        Modifier.weight(1f),
                        onClick = onOpenNet,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (wizIp != null) Chip(sleepLabel(sleepAt), onClick = vm::wizCycleSleep)
                    Chip(t("Tüm ışık ayarları"), onClick = onOpenLight)
                    Chip(t("Tema"), onClick = onOpenTheme)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(t("Maç günü"), onClick = onOpenMatch)
                    Chip("Wrapped", onClick = onOpenWrapped)
                    Chip(t("Ayarlar"), onClick = { onDismiss(); vm.openSystemSettings() })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(t("Ara"), onClick = onOpenSearch)
                    Chip(t("Müzik"), onClick = onOpenMedia)
                }
                Chip(t("Gizli uygulamalar · Şehir · Yedek"), onClick = onOpenMenu)
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

/** The room's one color: amber in Lumen, violet in Aura. */
private val LocalRoomAccent = staticCompositionLocalOf { LumenPalette.Amber }

/** An on/off switch; the knob sits right and the track takes the accent when on. */
@Composable
private fun Toggle(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalRoomAccent.current
    NeonSurface(
        onClick = onClick,
        modifier = modifier.size(width = 48.dp, height = 26.dp),
        container = if (on) accent else L.Raised,
        focusedContainer = if (on) accent else Color(0xFF2A2E38),
        focusedScale = 1.1f,
    ) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on) L.Ground else L.Text),
        )
    }
}

@Composable
private fun RoundButton(symbol: String, label: String, onClick: () -> Unit) {
    NeonSurface(onClick = onClick, modifier = Modifier.size(30.dp), shape = CircleShape) {
        Text(symbol, modifier = Modifier.align(Alignment.Center), style = TextStyle(fontFamily = LumenSans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** A quick scene: its two colors, its name; the one the light shows has an amber dot. */
@Composable
private fun SceneTile(id: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (a, b) = sceneColors(id)
    NeonSurface(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(11.dp),
        container = if (active) Color(0xFF2A2419) else L.Raised,
        focusedScale = 1.06f,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(a))
                Box(Modifier.size(11.dp).clip(CircleShape).background(b))
                if (active) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(LocalRoomAccent.current))
                }
            }
            Text(
                Wiz.presetName(id),
                style = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoTile(top: String, bottom: String, bottomColor: Color, modifier: Modifier, onClick: () -> Unit) {
    NeonSurface(onClick = onClick, modifier = modifier.height(50.dp), shape = RoundedCornerShape(11.dp), focusedScale = 1.04f) {
        Column(Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(top, style = TextStyle(fontFamily = LumenSans, fontSize = 9.5.sp), maxLines = 1)
            Text(bottom, style = TextStyle(fontFamily = LumenSans, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = bottomColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Two colors that read as the preset (a scene's own palette, roughly). */
private fun sceneColors(id: String): Pair<Color, Color> = when (id) {
    "film" -> Color(0xFFFFB547) to Color(0xFFFF8A3D)
    "mac" -> Color(0xFFE3263B) to Color(0xFFFDB912)
    "scene:1" -> Color(0xFF2F7DFF) to Color(0xFF3DE0C8)
    "scene:2" -> Color(0xFFE0457B) to Color(0xFF9B4BFF)
    "scene:3" -> Color(0xFFFF7A45) to Color(0xFFFFC46B)
    "scene:4" -> Color(0xFFC04BFF) to Color(0xFF3DD6FF)
    "scene:5", "scene:29" -> Color(0xFFFF6A2A) to Color(0xFFFFB04A)
    "scene:14" -> Color(0xFF7A5A2E) to Color(0xFF3A2A18)
    "scene:26" -> Color(0xFFFF3DCB) to Color(0xFF4B6BFF)
    else -> Color(0xFFFFB547) to Color(0xFFF3F0E8)
}

private fun lightStatus(ip: String?, p: WizPilot?): String = when {
    ip == null -> t("bulunamadı")
    p == null -> t("bağlanıyor…")
    !p.on -> t("kapalı")
    p.sceneId > 0 -> t("{0} · %{1}", Wiz.sceneName(p.sceneId) ?: t("Sahne"), p.dimming)
    else -> t("açık · %{0}", p.dimming)
}

private fun sleepLabel(at: Long?): String {
    if (at == null) return t("Uyku kapalı")
    val min = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    return t("Uyku {0} dk", min)
}
