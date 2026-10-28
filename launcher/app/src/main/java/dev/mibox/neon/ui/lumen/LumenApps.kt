package dev.mibox.neon.ui.lumen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.i18n.t
import dev.mibox.neon.ui.LumenDisplay
import dev.mibox.neon.ui.LumenPalette
import dev.mibox.neon.ui.LumenSans
import dev.mibox.neon.ui.glass.GlassCard
import dev.mibox.neon.ui.ignoreOpeningPress
import kotlinx.coroutines.delay

private val L = LumenPalette

/** Every app as a six-column grid, name under each; long-press for the app menu. */
@Composable
fun LumenApps(vm: LauncherViewModel, onAppMenu: (AppEntry) -> Unit, onDismiss: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(L.Ground)
                .ignoreOpeningPress()
                .padding(start = 48.dp, end = 48.dp, top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("Geri: ana ekran"), style = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, color = L.Dim))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        t("Uygulamalar"),
                        style = TextStyle(fontFamily = LumenDisplay, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = L.Text),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${apps.size}", modifier = Modifier.padding(bottom = 8.dp), style = TextStyle(fontFamily = LumenSans, fontSize = 14.sp, color = L.Muted))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(apps, key = { _, a -> a.pkg }) { i, app ->
                    GridApp(
                        app, vm,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = {
                            onDismiss()
                            vm.launch(app)
                        },
                        onLongClick = { onAppMenu(app) },
                    )
                }
            }
        }
    }
    LaunchedEffect(apps.isNotEmpty()) {
        repeat(10) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
}

@Composable
private fun GridApp(app: AppEntry, vm: LauncherViewModel, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val art by produceState<AppArt?>(null, app.pkg) { value = vm.art(app) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassCard(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            width = 130.dp,
            height = 73.dp,
            shape = RoundedCornerShape(11.dp),
            onFocused = {},
        ) {
            val a = art
            when {
                a == null -> {}
                a.isBanner -> Image(a.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> {
                    Box(Modifier.fillMaxSize().background(a.cardTop.copy(alpha = 0.6f)))
                    Image(a.image, null, Modifier.align(Alignment.Center).size(32.dp))
                }
            }
        }
        Text(
            app.label,
            modifier = Modifier.width(130.dp),
            style = TextStyle(fontFamily = LumenSans, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = L.Dim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The small "every app" tile at the end of a row: four rounded squares. */
@Composable
fun AllAppsGlyph(modifier: Modifier = Modifier, color: Color = L.Text) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.5.dp)).background(color)) }
            }
        }
    }
}
