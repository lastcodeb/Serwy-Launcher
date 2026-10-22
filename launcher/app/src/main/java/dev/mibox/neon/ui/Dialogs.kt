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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.HomeTheme
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.data.Wiz
import kotlinx.coroutines.launch
import dev.mibox.neon.i18n.t

@Composable
private fun NeonDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val st = LocalHomeStyle.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .ignoreOpeningPress()
                .width(440.dp)
                .panel(st, 24.dp)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val st = LocalHomeStyle.current
    NeonSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.03f,
    ) {
        Text(
            st.caps(st.plain(text)),
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp),
            // Other themes set menu rows as labels: condensed caps / teletext lines
            style = if (st.theme == HomeTheme.NEON) st.item else st.type(16.sp, FontWeight.SemiBold, role = TextRole.LABEL),
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
    val st = LocalHomeStyle.current
    var pickProfile by remember { mutableStateOf(false) }
    val first = remember(pickProfile) { FocusRequester() }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val wizIp by vm.wizIp.collectAsStateWithLifecycle()
    val pinned by vm.pinnedShowcase.collectAsStateWithLifecycle()
    val profile = profiles[app.pkg]
    // Only the Neon rows can reorder apps; the other homes have no "moving" mode
    val canMove = st.theme == HomeTheme.NEON

    NeonDialog(onDismiss) {
        Text(app.label, style = st.headline)
        Spacer(Modifier.height(4.dp))
        if (!pickProfile) {
            if (canMove) MenuItem(t("↔   Taşı"), onMove, Modifier.focusRequester(first))
            MenuItem(t("🙈   Gizle"), onHide, if (canMove) Modifier else Modifier.focusRequester(first))
            MenuItem(
                if (app.pkg in pinned) t("⭐   Favorilerden çıkar") else t("⭐   Favorilere ekle"),
                { vm.toggleShowcase(app.pkg); onDismiss() },
            )
            if (wizIp != null) {
                val current = profile?.let { "${Wiz.presetEmoji(it)} ${Wiz.presetName(it)}" } ?: t("yok")
                MenuItem(t("💡   Işık profili: {0}", current), { pickProfile = true })
            }
            MenuItem(t("ⓘ   Uygulama bilgisi"), onInfo)
            MenuItem(t("✕   Kapat"), onDismiss)
        } else {
            Text(t("Bu uygulama açılınca ışık:"), style = st.body, color = st.textDim)
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MenuItem(
                    st.mark(profile == null) + t("Değişmesin"),
                    { vm.setLightProfile(app.pkg, null); pickProfile = false },
                    Modifier.focusRequester(first),
                )
                (Wiz.BuiltInPresets + favorites).distinct().forEach { id ->
                    MenuItem(
                        st.mark(profile == id) + "${Wiz.presetEmoji(id)}   ${Wiz.presetName(id)}",
                        { vm.setLightProfile(app.pkg, id); pickProfile = false },
                    )
                }
            }
            MenuItem(t("←   Geri"), { pickProfile = false })
        }
    }
    FocusOnOpen(first)
}

/**
 * Long-press on a Watch Next / "Senin için" card or on a pinned content card in Favoriler:
 * pins the item or takes it off the Favoriler row again.
 */
@Composable
fun ContentMenu(item: WatchItem, vm: LauncherViewModel, onDismiss: () -> Unit, onToggled: () -> Unit) {
    val st = LocalHomeStyle.current
    val keys by vm.pinnedContentKeys.collectAsStateWithLifecycle()
    val pinned = vm.pinKeyOf(item) in keys
    val first = remember { FocusRequester() }

    NeonDialog(onDismiss) {
        Text(item.title, style = st.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        MenuItem(
            if (pinned) t("⭐   Favorilerden çıkar") else t("⭐   Favorilere ekle"),
            { vm.togglePinned(item); onToggled() },
            Modifier.focusRequester(first),
        )
        MenuItem(t("✕   Kapat"), onDismiss)
    }
    FocusOnOpen(first)
}

private enum class MenuPage { Main, Hidden, City }

@Composable
fun LauncherMenu(vm: LauncherViewModel, onDismiss: () -> Unit, onOpenTheme: () -> Unit) {
    val st = LocalHomeStyle.current
    var page by remember { mutableStateOf(MenuPage.Main) }
    val hidden by vm.hiddenApps.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val first = remember(page) { FocusRequester() }

    NeonDialog(onDismiss) {
        when (page) {
            MenuPage.Main -> {
                Text(
                    if (st.theme == HomeTheme.NEON) "Neon" else t("Menü"),
                    style = st.headline.fillWith(st.accentBrush, st.titleColor),
                )
                Spacer(Modifier.height(4.dp))
                MenuItem(t("👁   Gizli uygulamalar ({0})", hidden.size), { page = MenuPage.Hidden }, Modifier.focusRequester(first))
                MenuItem(t("📍   Şehir: {0}", city.name), { page = MenuPage.City })
                MenuItem(t("🎨   Tema"), onOpenTheme)
                MenuItem(t("⚙️   Android TV ayarları"), { onDismiss(); vm.openSystemSettings() })
                MenuItem(t("✕   Kapat"), onDismiss)
            }
            MenuPage.Hidden -> {
                Text(t("Gizli uygulamalar"), style = st.headline)
                Text(t("Göstermek için seç"), style = st.body, color = st.textDim)
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
                MenuItem(t("←   Geri"), { page = MenuPage.Main }, if (hidden.isEmpty()) Modifier.focusRequester(first) else Modifier)
            }
            MenuPage.City -> CityEditor(vm, first, onDone = { page = MenuPage.Main })
        }
    }
    FocusOnOpen(first)
}

@Composable
private fun CityEditor(vm: LauncherViewModel, first: FocusRequester, onDone: () -> Unit) {
    val st = LocalHomeStyle.current
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

    Text(t("Şehir"), style = st.headline)
    val fieldShape = st.shapeOf(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(fieldShape)
            .background(st.surfaceHi)
            .border(1.5.dp, st.barBrush, fieldShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) Text(t("Örn. Ankara"), style = st.item, color = st.textDim)
        BasicTextField(
            value = text,
            onValueChange = { text = it; error = false },
            modifier = Modifier.fillMaxWidth().focusRequester(first),
            singleLine = true,
            textStyle = st.item.copy(color = st.text),
            cursorBrush = SolidColor(st.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
    }
    if (error) Text(t("Şehir bulunamadı"), style = st.body, color = st.danger)
    MenuItem(if (busy) t("Aranıyor…") else t("✓   Kaydet"), { save() })
    MenuItem(t("←   Geri"), onDone)
}
