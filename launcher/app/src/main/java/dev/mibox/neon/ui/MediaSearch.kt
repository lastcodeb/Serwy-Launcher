package dev.mibox.neon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.NowPlaying
import kotlinx.coroutines.delay
import dev.mibox.neon.i18n.t

/** "♪ Song · Artist" for a top bar; null when nothing plays or is paused. */
fun nowPlayingLabel(np: NowPlaying?, max: Int = 34): String? = np?.let {
    val s = listOfNotNull(it.title, it.artist).joinToString(" · ")
    if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}

/**
 * What another app is playing: its cover, title and artist, previous / play-pause / next, and a
 * way into the app. Opened from the music item of every top bar and from the Control Center.
 */
@Composable
fun MediaPanel(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val st = LocalHomeStyle.current
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val access by vm.mediaAccess.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .ignoreOpeningPress()
                .width(620.dp)
                .panel(st)
                .padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val p = np
            Cover(vm, p, 168.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !access -> {
                        Text(t("Müzik kontrolü kapalı"), style = st.type(22.sp, FontWeight.Bold, role = TextRole.DISPLAY), maxLines = 1)
                        Text(
                            t("Spotify gibi arka planda çalan uygulamaları buradan durdurup geçebilmek için bir kez izin vermen gerekiyor."),
                            style = st.type(13.sp, color = st.textDim),
                        )
                        Spacer(Modifier.height(8.dp))
                        Chip(t("İzin ver"), onClick = vm::enableMedia, primary = true, modifier = Modifier.focusRequester(first))
                    }
                    p == null -> {
                        Text(t("Şu an çalan bir şey yok"), style = st.type(22.sp, FontWeight.Bold, role = TextRole.DISPLAY), maxLines = 1)
                        Text(t("Spotify ya da YouTube Music'te bir şey başlat, burada görünür."), style = st.type(13.sp, color = st.textDim))
                        Spacer(Modifier.height(8.dp))
                        Chip(t("Kapat"), onClick = onDismiss, modifier = Modifier.focusRequester(first))
                    }
                    else -> {
                        val app = remember(p.pkg) { vm.appFor(p.pkg) }
                        Text(
                            st.caps(app?.label ?: p.pkg) + if (p.playing) "" else " · " + t("duraklatıldı"),
                            style = st.type(12.sp, FontWeight.SemiBold, color = st.textMuted, role = TextRole.LABEL),
                            maxLines = 1,
                        )
                        Text(
                            p.title,
                            style = st.type(24.sp, FontWeight.Bold, role = TextRole.DISPLAY),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        p.artist?.let { Text(it, style = st.type(15.sp, color = st.textSoft), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            TransportButton(Glyph.PREVIOUS, 46.dp, enabled = p.canPrev, onClick = vm::mediaPrevious)
                            TransportButton(
                                if (p.playing) Glyph.PAUSE else Glyph.PLAY,
                                58.dp,
                                onClick = vm::mediaToggle,
                                modifier = Modifier.focusRequester(first),
                            )
                            TransportButton(Glyph.NEXT, 46.dp, enabled = p.canNext, onClick = vm::mediaNext)
                            Spacer(Modifier.width(6.dp))
                            Chip(
                                t("Uygulamayı aç"),
                                onClick = {
                                    onDismiss()
                                    vm.openNowPlaying()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    FocusFirst(first, access to (np == null))
}

@Composable
private fun Cover(vm: LauncherViewModel, np: NowPlaying?, size: Dp) {
    val st = LocalHomeStyle.current
    val app = remember(np?.pkg) { np?.pkg?.let(vm::appFor) }
    val appArt by produceState<ImageBitmap?>(null, app) { value = app?.let { vm.art(it)?.image } }
    Box(
        Modifier
            .size(size)
            .clip(st.shapeOf(18.dp))
            .background(st.surfaceHi),
        contentAlignment = Alignment.Center,
    ) {
        val art = np?.art ?: appArt
        if (art != null) {
            Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("♪", style = st.type(56.sp, FontWeight.Bold, color = st.textMuted))
        }
    }
}

private enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

@Composable
private fun TransportButton(glyph: Glyph, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    NeonSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.size(size),
        shape = CircleShape,
        focusedScale = 1.1f,
    ) {
        val tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.35f)
        Canvas(Modifier.size(size * 0.36f).align(Alignment.Center)) { draw(glyph, tint) }
    }
}

private fun DrawScope.draw(glyph: Glyph, c: Color) {
    val w = size.width
    val h = size.height
    fun triangle(x0: Float, x1: Float, pointRight: Boolean) = Path().apply {
        if (pointRight) {
            moveTo(x0, 0f); lineTo(x1, h / 2); lineTo(x0, h)
        } else {
            moveTo(x1, 0f); lineTo(x0, h / 2); lineTo(x1, h)
        }
        close()
    }
    val bar = w * 0.16f
    when (glyph) {
        Glyph.PLAY -> drawPath(triangle(w * 0.12f, w, pointRight = true), c)
        Glyph.PAUSE -> {
            drawRect(c, Offset(w * 0.12f, 0f), Size(w * 0.26f, h))
            drawRect(c, Offset(w * 0.62f, 0f), Size(w * 0.26f, h))
        }
        Glyph.NEXT -> {
            drawPath(triangle(0f, w - bar, pointRight = true), c)
            drawRect(c, Offset(w - bar, 0f), Size(bar, h))
        }
        Glyph.PREVIOUS -> {
            drawRect(c, Offset(0f, 0f), Size(bar, h))
            drawPath(triangle(bar, w, pointRight = false), c)
        }
    }
}

/**
 * Search: type (or speak through the keyboard's microphone) and pick an app, or send the words
 * to YouTube, YouTube Music, Spotify or Google TV. It sits at the top so the keyboard below
 * never covers it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchPanel(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val st = LocalHomeStyle.current
    var query by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val targets = remember { vm.searchTargets() }
    val apps = remember(query) { vm.searchApps(query).take(4) }
    var jump by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .ignoreOpeningPress()
                    .width(640.dp)
                    .panel(st, 24.dp)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(t("Ara"), style = st.headline.fillWith(st.accentBrush, st.titleColor))
                val fieldShape = st.shapeOf(14.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(fieldShape)
                        .background(st.surfaceHi)
                        .border(1.5.dp, st.barBrush, fieldShape)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) Text(t("Uygulama, dizi, film ya da şarkı…"), style = st.item, color = st.textDim)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(field),
                        singleLine = true,
                        textStyle = st.item.copy(color = st.text),
                        cursorBrush = SolidColor(st.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            jump++
                        }),
                    )
                }

                if (query.isBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (targets.any { it == dev.mibox.neon.data.SearchTarget.GOOGLE }) {
                            Chip(
                                t("Sesle ara"),
                                leading = "🎤",
                                onClick = {
                                    onDismiss()
                                    vm.voiceSearch()
                                },
                            )
                        }
                        Text(t("Yazınca uygulamalar ve arama yerleri burada çıkar"), style = st.type(13.sp, color = st.textDim))
                    }
                } else {
                    if (apps.isNotEmpty()) {
                        Text(st.caps(t("Uygulamalar")), style = st.type(12.sp, FontWeight.Bold, color = st.textMuted, role = TextRole.LABEL))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            apps.forEachIndexed { i, app ->
                                Chip(
                                    app.label,
                                    leading = "▶",
                                    onClick = {
                                        onDismiss()
                                        vm.launch(app)
                                    },
                                    modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
                                )
                            }
                        }
                    }
                    if (targets.isNotEmpty()) {
                        Text(st.caps(t("İçinde ara")), style = st.type(12.sp, FontWeight.Bold, color = st.textMuted, role = TextRole.LABEL))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            targets.forEachIndexed { i, target ->
                                Chip(
                                    target.label,
                                    leading = "🔍",
                                    onClick = {
                                        onDismiss()
                                        vm.searchIn(target, query)
                                    },
                                    modifier = if (i == 0 && apps.isEmpty()) Modifier.focusRequester(firstResult) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    FocusFirst(field, Unit)
    // The keyboard's search key: from the text to the first result
    LaunchedEffect(jump) {
        if (jump == 0) return@LaunchedEffect
        delay(120)
        runCatching { firstResult.requestFocus() }
    }
}

/** Dialog windows take focus a frame late; ask until it sticks (again whenever [key] changes). */
@Composable
private fun FocusFirst(requester: FocusRequester, key: Any) {
    LaunchedEffect(requester, key) {
        repeat(5) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}
