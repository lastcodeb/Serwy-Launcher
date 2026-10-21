package dev.mibox.neon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.Wiz
import kotlinx.coroutines.launch

private val DialogShape = RoundedCornerShape(24.dp)

@Composable
private fun NeonDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .ignoreOpeningPress()
                .width(440.dp)
                .clip(DialogShape)
                .background(Neon.Surface)
                .border(1.dp, Neon.ChipStroke, DialogShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NeonSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.03f,
    ) {
        Text(
            text,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Dialog windows take focus a frame late; ask until it sticks. */
@Composable
private fun FocusOnOpen(requester: FocusRequester) {
    LaunchedEffect(requester) {
        repeat(5) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }
}

@Composable
fun AppMenu(
    app: AppEntry,
    vm: LauncherViewModel,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onHide: () -> Unit,
    onInfo: () -> Unit,
) {
    var pickProfile by remember { mutableStateOf(false) }
    val first = remember(pickProfile) { FocusRequester() }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val wizIp by vm.wizIp.collectAsStateWithLifecycle()
    val pinned by vm.pinnedShowcase.collectAsStateWithLifecycle()
    val profile = profiles[app.pkg]

    NeonDialog(onDismiss) {
        Text(app.label, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        if (!pickProfile) {
            MenuItem("↔   Taşı", onMove, Modifier.focusRequester(first))
            MenuItem("🙈   Gizle", onHide)
            MenuItem(
                if (app.pkg in pinned) "⭐   Favorilerden çıkar" else "⭐   Favorilere ekle",
                { vm.toggleShowcase(app.pkg); onDismiss() },
            )
            if (wizIp != null) {
                val current = profile?.let { "${Wiz.presetEmoji(it)} ${Wiz.presetName(it)}" } ?: "yok"
                MenuItem("💡   Işık profili: $current", { pickProfile = true })
            }
            MenuItem("ⓘ   Uygulama bilgisi", onInfo)
            MenuItem("✕   Kapat", onDismiss)
        } else {
            Text("Bu uygulama açılınca ışık:", style = MaterialTheme.typography.bodyMedium, color = Neon.TextDim)
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MenuItem(
                    (if (profile == null) "✓ " else "") + "Değişmesin",
                    { vm.setLightProfile(app.pkg, null); pickProfile = false },
                    Modifier.focusRequester(first),
                )
                (Wiz.BuiltInPresets + favorites).distinct().forEach { id ->
                    MenuItem(
                        (if (profile == id) "✓ " else "") + "${Wiz.presetEmoji(id)}   ${Wiz.presetName(id)}",
                        { vm.setLightProfile(app.pkg, id); pickProfile = false },
                    )
                }
            }
            MenuItem("←   Geri", { pickProfile = false })
        }
    }
    FocusOnOpen(first)
}

private enum class MenuPage { Main, Hidden, City }

@Composable
fun LauncherMenu(vm: LauncherViewModel, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(MenuPage.Main) }
    val hidden by vm.hiddenApps.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val first = remember(page) { FocusRequester() }

    NeonDialog(onDismiss) {
        when (page) {
            MenuPage.Main -> {
                Text("Neon", style = MaterialTheme.typography.headlineSmall.copy(brush = Neon.Gradient))
                Spacer(Modifier.height(4.dp))
                MenuItem("👁   Gizli uygulamalar (${hidden.size})", { page = MenuPage.Hidden }, Modifier.focusRequester(first))
                MenuItem("📍   Şehir: ${city.name}", { page = MenuPage.City })
                MenuItem("⚙️   Android TV ayarları", { onDismiss(); vm.openSystemSettings() })
                MenuItem("✕   Kapat", onDismiss)
            }
            MenuPage.Hidden -> {
                Text("Gizli uygulamalar", style = MaterialTheme.typography.headlineSmall)
                Text("Göstermek için seç", style = MaterialTheme.typography.bodyMedium, color = Neon.TextDim)
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    hidden.forEachIndexed { i, app ->
                        MenuItem(
                            "👁   ${app.label}",
                            { vm.setHidden(app.pkg, false) },
                            if (i == 0) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
                MenuItem("←   Geri", { page = MenuPage.Main }, if (hidden.isEmpty()) Modifier.focusRequester(first) else Modifier)
            }
            MenuPage.City -> CityEditor(vm, first, onDone = { page = MenuPage.Main })
        }
    }
    FocusOnOpen(first)
}

@Composable
private fun CityEditor(vm: LauncherViewModel, first: FocusRequester, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val save = {
        if (!busy && text.isNotBlank()) {
            busy = true
            scope.launch {
                val ok = vm.setCity(text)
                busy = false
                if (ok) onDone() else error = true
            }
        }
    }

    Text("Şehir", style = MaterialTheme.typography.headlineSmall)
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Neon.SurfaceHi)
            .border(1.5.dp, Neon.Gradient, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) Text("Örn. Ankara", style = MaterialTheme.typography.titleMedium, color = Neon.TextDim)
        BasicTextField(
            value = text,
            onValueChange = { text = it; error = false },
            modifier = Modifier.fillMaxWidth().focusRequester(first),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = Neon.Text),
            cursorBrush = SolidColor(Neon.Pink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
    }
    if (error) Text("Şehir bulunamadı", style = MaterialTheme.typography.bodyMedium, color = Neon.Danger)
    MenuItem(if (busy) "Aranıyor…" else "✓   Kaydet", { save() })
    MenuItem("←   Geri", onDone)
}
