package dev.mibox.neon.ui.aura

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.Headline
import dev.mibox.neon.data.HeadlineTarget
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.NetVerdict
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.i18n.t
import dev.mibox.neon.ui.AuraPalette
import dev.mibox.neon.ui.Focus
import dev.mibox.neon.ui.HomeActions
import dev.mibox.neon.ui.HomeSignal
import dev.mibox.neon.ui.LumenDisplay
import dev.mibox.neon.ui.LumenSans
import dev.mibox.neon.ui.MatchChip
import dev.mibox.neon.ui.NeonSurface
import dev.mibox.neon.ui.nowPlayingLabel
import dev.mibox.neon.ui.Pill
import dev.mibox.neon.ui.ROW_APPS
import dev.mibox.neon.ui.ROW_FAVORITES
import dev.mibox.neon.ui.ROW_FOR_YOU
import dev.mibox.neon.ui.ROW_WATCH
import dev.mibox.neon.ui.RowsController
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.TeamLogo
import dev.mibox.neon.ui.glass.FrostFilter
import dev.mibox.neon.ui.glass.blurred
import dev.mibox.neon.ui.glass.maskedPoster
import dev.mibox.neon.ui.glass.rememberFading
import dev.mibox.neon.ui.lumen.AllAppsGlyph
import dev.mibox.neon.ui.lumen.LumenApps
import dev.mibox.neon.ui.rememberNow
import dev.mibox.neon.ui.rowKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import kotlin.math.roundToInt

private val A = AuraPalette
private val Side = 48.dp

// The stage: the story on the left, the art top right; then the row tabs and the rows
private val StoryTop = 84.dp
private val StoryH = 196.dp
private val TabsTop = 296.dp
// Card tops land on y 342; a row's box holds its glow above and below them
private val RowsTop = 315.dp
private val RowBox = CardH + CardPad * 2
private val RowPitch = RowBox
// The rows are clipped well above the tabs, so a focused card's glow is never cut flat; rows
// above the one in use are faded out instead
private val ClipTop = 230.dp
private val PosterW = 680.dp
private val PosterH = 383.dp

/**
 * Aura: Lumen's calm story and room sheet, Glass's frosted wash and lifting cards, Neon's color.
 * Whatever has focus lends its color to everything: the card glows in it, a light pools on the
 * floor under it and follows it along the row, the sky behind the story takes it, and so does
 * the lamp in the room. Rows sit behind tabs, the next one peeking from below.
 */
@Composable
fun AuraHome(vm: LauncherViewModel, actions: HomeActions, signals: SharedFlow<HomeSignal>) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val favorites by vm.favoriteCards.collectAsStateWithLifecycle()
    val watch by vm.watchNext.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val watchLoaded by vm.watchLoaded.collectAsStateWithLifecycle()
    val moving by vm.moving.collectAsStateWithLifecycle()
    val movingFavorite by vm.movingFavorite.collectAsStateWithLifecycle()

    val focus = remember { mutableStateOf<Focus?>(null) }
    val rows = remember { RowsController() }
    val aura = remember { AuraLight() }
    // Where focus is in its row, for the "3 / 12" next to the tabs
    val position = remember { mutableStateOf(0 to 0) }
    val firstRow = rowKeys(favorites, watch, forYou).first()
    val scope = rememberCoroutineScope()
    fun keysNow() = rowKeys(vm.favoriteCards.value, vm.watchNext.value, vm.forYou.value)

    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(watchLoaded, apps.isNotEmpty()) {
        if (initialFocusDone || !watchLoaded || apps.isEmpty()) return@LaunchedEffect
        repeat(40) {
            if (rows.focus(firstRow)) {
                initialFocusDone = true
                return@LaunchedEffect
            }
            delay(50)
        }
    }
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            rows.scrollAllToStart()
            rows.focus(keysNow().first())
        }
    }
    LaunchedEffect(Unit) {
        signals.collect { s ->
            when (s) {
                HomeSignal.Restore -> if (focus.value == null) rows.focus(rows.activeKey ?: keysNow().first())
                HomeSignal.AppHidden -> {
                    focus.value = null
                    rows.focus(rows.activeKey ?: keysNow().first())
                }
                HomeSignal.PinToggled -> if (rows.activeKey == ROW_FAVORITES) {
                    focus.value = null
                    scope.launch {
                        delay(300)
                        val keys = keysNow()
                        rows.focus(if (ROW_FAVORITES in keys) ROW_FAVORITES else keys.first())
                    }
                }
            }
        }
    }

    // The lamp in the room shows the screen's aura
    val auraColor = aura.color
    LaunchedEffect(auraColor) { vm.onAccent(auraColor) }

    var showApps by remember { mutableStateOf(false) }
    if (showApps) LumenApps(vm, actions.onAppMenu) { showApps = false }

    // One slow rise of the whole home when it appears
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    Box(Modifier.fillMaxSize().background(A.Ground)) {
        Stage(vm, focus, aura)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Side)
                .offset(y = 22.dp)
                .graphicsLayer {
                    alpha = intro.value
                    translationY = (1f - intro.value) * -14.dp.toPx()
                }
                .focusProperties { down = rows.requester(rows.activeKey ?: firstRow) },
        ) {
            AuraTopBar(vm, actions, aura)
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = intro.value
                translationY = (1f - intro.value) * 40.dp.toPx()
            },
        ) {
            Rows(vm, apps, favorites, watch, forYou, moving, movingFavorite, rows, focus, aura, position, actions, onOpenApps = { showApps = true })
        }
    }
}

/** The color everything takes from the focused card, and where that card is on screen. */
internal class AuraLight {
    var color by mutableStateOf(A.Glow)

    /** Center x of the focused card in px, NaN before the first one is laid out. */
    var x by mutableStateOf(Float.NaN)
}

// --- Top bar -------------------------------------------------------------------------------------

private val DayFmt = DateTimeFormatter.ofPattern("d MMMM", TR)
private val ClockFmt = DateTimeFormatter.ofPattern("HH:mm", TR)

internal fun greeting(hour: Int) = when (hour) {
    in 5..10 -> t("Günaydın")
    in 11..17 -> t("İyi günler")
    in 18..21 -> t("İyi akşamlar")
    else -> t("İyi geceler")
}

@Composable
private fun AuraTopBar(vm: LauncherViewModel, actions: HomeActions, aura: AuraLight) {
    val now by rememberNow()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val wiz by vm.wiz.collectAsStateWithLifecycle()
    val wizIp by vm.wizIp.collectAsStateWithLifecycle()
    val net by vm.netNow.collectAsStateWithLifecycle()
    val match by vm.match.collectAsStateWithLifecycle()
    val matchOn by vm.matchEnabled.collectAsStateWithLifecycle()
    val ram by vm.ram.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    val playing by vm.nowPlaying.collectAsStateWithLifecycle()
    val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val small = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val dot = animateColorAsState(aura.color, tween(600), label = "auraDot")

    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        val day = now.dayOfWeek.getDisplayName(JTextStyle.FULL, TR)
        Text(
            "${greeting(now.hour)} · $day ${now.format(DayFmt)}",
            modifier = Modifier.weight(1f),
            style = small.copy(fontWeight = FontWeight.Medium, color = A.Dim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (matchOn || match != null) {
            MatchChip(match, nowMs, actions.onOpenMatch)
            Spacer(Modifier.width(8.dp))
        }

        // The status island: one frosted capsule, each part a button of its own
        Row(
            Modifier
                .height(34.dp)
                .clip(Pill)
                .background(A.Frost)
                .border(0.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f))), Pill)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            nowPlayingLabel(playing, max = 18)?.let { label ->
                Segment(onClick = actions.onOpenMedia, onLongClick = vm::mediaToggle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Glows in the aura while the music plays
                        Text("♪", style = small.copy(color = if (playing?.playing == true) dot.value else A.Dim))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = small, maxLines = 1)
                    }
                }
            }
            val w = weather
            Segment(onClick = actions.onOpenControl) {
                // While music shows, the city makes room for it
                val place = if (playing != null) "" else " ${city.name}"
                Text(if (w != null) "${w.tempC.roundToInt()}°$place" else city.name, style = small, maxLines = 1)
            }
            val p = wiz
            Segment(onClick = actions.onOpenLight, onLongClick = if (wizIp != null) ({ vm.wizQuick() }) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The lamp's dot shows what the lamp shows: the aura, or nothing when off
                    Box(
                        Modifier.size(6.dp).drawBehind {
                            drawCircle(if (p?.on == true) dot.value else Color.White.copy(alpha = 0.3f))
                        },
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        when {
                            wizIp == null -> t("Işık bul")
                            p == null -> t("bağlanıyor…")
                            p.on -> t("Işık · %{0}", p.dimming)
                            else -> t("Işık kapalı")
                        },
                        style = small,
                        maxLines = 1,
                    )
                }
            }
            // Memory in use; OK closes the apps running in the background
            Segment(onClick = vm::boost) {
                val f = freed
                Text(if (f != null) "+${f / (1024 * 1024)} MB" else t("RAM %{0}", ram.usedPercent), style = small, maxLines = 1)
            }
            Segment(onClick = actions.onOpenNet) {
                Text(
                    when (net.verdict) {
                        NetVerdict.UHD_OK -> t("4K hazır")
                        NetVerdict.HD_OK -> t("HD hazır")
                        NetVerdict.WEAK -> t("Zayıf bağlantı")
                        NetVerdict.UNKNOWN -> t("Ağ")
                    },
                    style = small,
                    maxLines = 1,
                )
            }
            Segment(onClick = actions.onOpenSearch) {
                Icon(Icons.Default.Search, contentDescription = t("Ara"), modifier = Modifier.size(15.dp))
            }
            Segment(onClick = actions.onOpenControl) {
                Text(now.format(ClockFmt), style = TextStyle(fontFamily = LumenDisplay, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        NeonSurface(
            onClick = vm::openSystemSettings,
            onLongClick = actions.onOpenMenu,
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Default.Settings, contentDescription = t("Ayarlar"), modifier = Modifier.size(16.dp).align(Alignment.Center))
        }
    }
}

@Composable
private fun Segment(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    NeonSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.height(28.dp),
        container = Color.Transparent,
        stroke = Color.Transparent,
        focusedScale = 1f,
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 11.dp), contentAlignment = Alignment.Center) { content() }
    }
}

// --- The stage -----------------------------------------------------------------------------------

private data class Shown(val headline: Headline, val item: WatchItem?, val app: AppEntry?, val match: Boolean)

/**
 * Everything behind the rows: the time of day low on the left, the focused art frosted over the
 * whole screen (Glass), its poster sharp in the upper right and melting into it, the aura's
 * light, then the story. Static between focus changes: nothing here animates on its own.
 */
@Composable
private fun Stage(vm: LauncherViewModel, focus: State<Focus?>, aura: AuraLight) {
    val lead by vm.headline.collectAsStateWithLifecycle()
    val now by rememberNow()
    val f = focus.value
    val shown: Shown = when (f) {
        is Focus.Watch -> Shown(vm.headlineFor(f.item), f.item, f.app, false)
        is Focus.App -> Shown(vm.headlineFor(f.app), null, f.app, false)
        null -> when (val tg = lead.target) {
            is HeadlineTarget.OpenApp -> Shown(lead, null, vm.appFor(tg.pkg), false)
            is HeadlineTarget.OpenWatch -> Shown(lead, tg.item, vm.appFor(tg.item.pkg), false)
            HeadlineTarget.OpenMatch -> Shown(lead, null, null, true)
            HeadlineTarget.None -> Shown(lead, null, null, false)
        }
    }
    val item = shown.item
    val app = shown.app
    val large by produceState<ImageBitmap?>(null, item?.id, app?.pkg) {
        value = null
        // Scrolling fast through a row should not decode every poster on the way
        delay(180)
        value = when {
            item != null -> vm.largeArt(item)
            app != null -> vm.largeArt(app)
            else -> null
        }
    }
    val small by produceState<AppArt?>(null, item?.id, app?.pkg) {
        value = when {
            item != null -> vm.watchArt(item) ?: app?.let { vm.art(it) }
            app != null -> vm.art(app)
            else -> null
        }
    }
    val blurKey = item?.let { "w${it.id}" } ?: app?.pkg
    val frost by produceState<ImageBitmap?>(null, blurKey, small) {
        val s = small
        if (s != null && blurKey != null) value = blurred(blurKey, s.image)
    }

    val tint = skyTint(now.hour)
    val wash = rememberFading(frost, 650)
    val poster = rememberFading(if (item != null) large else null, 500)
    Box(Modifier.fillMaxSize()) {
        // 1. Sky and the frosted wash, one canvas, no layers
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val sky = Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.75f), Color.Transparent),
                        center = Offset(size.width * 0.08f, size.height * 1.05f),
                        radius = size.width * 0.62f,
                    )
                    val dst = IntSize(size.width.toInt(), size.height.toInt())
                    onDrawBehind {
                        drawRect(sky)
                        fun washOf(img: ImageBitmap?, alpha: Float) {
                            if (img == null || alpha <= 0f) return
                            drawImage(
                                img,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(img.width, img.height),
                                dstSize = dst,
                                alpha = alpha * 0.62f,
                                colorFilter = FrostFilter,
                                filterQuality = FilterQuality.Low,
                            )
                        }
                        if (wash.progress < 1f) washOf(wash.prev, 1f)
                        washOf(wash.cur, wash.progress)
                    }
                },
        )
        // 2. The aura's light: high behind the art, and a pool on the floor under the card
        AuraGlow(aura)
        // 3. The poster, sharp top right, fading left and down (Glass's shader mask)
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = PosterW.toPx()
                    val h = PosterH.toPx()
                    val left = size.width - w
                    val cur = poster.cur?.let { maskedPoster(it, left, w, h) }
                    val prev = poster.prev?.let { maskedPoster(it, left, w, h) }
                    onDrawBehind {
                        drawIntoCanvas { c ->
                            prev?.let {
                                it.alpha = ((1f - poster.progress) * 255).toInt()
                                c.nativeCanvas.drawRect(left, 0f, size.width, h, it)
                            }
                            cur?.let {
                                it.alpha = (poster.progress * 255).toInt()
                                c.nativeCanvas.drawRect(left, 0f, size.width, h, it)
                            }
                        }
                    }
                },
        )
        // 4. Legibility, each gradient only over the part it darkens
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val leftW = size.width * 0.6f
                    val left = Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.62f), 1f to Color.Transparent, endX = leftW)
                    val bottomTop = size.height * 0.45f
                    val bottom = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Black.copy(alpha = 0.78f),
                        startY = bottomTop,
                        endY = size.height,
                    )
                    val top = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent, endY = 90.dp.toPx())
                    onDrawBehind {
                        drawRect(left, size = Size(leftW, size.height))
                        drawRect(bottom, topLeft = Offset(0f, bottomTop), size = Size(size.width, size.height - bottomTop))
                        drawRect(top, size = Size(size.width, 90.dp.toPx()))
                    }
                },
        )
        // App banners are small: a lifted tile instead of a stretched poster
        Crossfade(
            if (item == null && !shown.match) large else null,
            animationSpec = tween(450),
            label = "auraTile",
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = Side).size(300.dp, 169.dp),
        ) { img ->
            if (img != null) {
                Image(
                    img,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(30.dp, RoundedCornerShape(18.dp), ambientColor = aura.color, spotColor = aura.color)
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (shown.match) MatchCrests(vm)
        AnimatedContent(
            shown.headline,
            transitionSpec = { fadeIn(tween(320, delayMillis = 70)) togetherWith fadeOut(tween(160)) },
            label = "auraStory",
            modifier = Modifier.padding(start = Side, top = StoryTop).widthIn(max = 500.dp),
        ) { h -> Story(h, aura.color) }
    }
}

/** The sky low on the left follows the hour: dawn teal, day blue, dusk rose, night indigo. */
private fun skyTint(hour: Int): Color = when (hour) {
    in 5..8 -> Color(0xFF1F5A6B)
    in 9..16 -> Color(0xFF1C3F7A)
    in 17..20 -> Color(0xFF7A2E4F)
    else -> Color(0xFF2A1F6E)
}

/** Two pools of the aura's color; they glide to the focused card and change color with it. */
@Composable
private fun AuraGlow(aura: AuraLight) {
    val color = animateColorAsState(aura.color, tween(700), label = "auraColor")
    val target = aura.x
    val x = animateFloatAsState(if (target.isNaN()) 300f else target, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow), label = "auraX")
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val c = color.value
                // High right, behind the art (Neon's glow)
                val hi = Offset(size.width * 0.78f, size.height * 0.12f)
                val hiR = size.width * 0.42f
                drawCircle(
                    Brush.radialGradient(0f to c.copy(alpha = 0.42f), 0.5f to lerp(c, Color.Black, 0.2f).copy(alpha = 0.16f), 1f to Color.Transparent, center = hi, radius = hiR),
                    radius = hiR,
                    center = hi,
                )
                // Low, under the focused card: the light it throws on the floor
                val lo = Offset(x.value, size.height * 0.84f)
                val loR = size.width * 0.3f
                drawCircle(
                    Brush.radialGradient(0f to c.copy(alpha = 0.5f), 1f to Color.Transparent, center = lo, radius = loR),
                    radius = loR,
                    center = lo,
                )
            },
    )
}

@Composable
private fun Story(h: Headline, tint: Color) {
    val kicker = lerp(tint, Color.White, 0.55f)
    Column(Modifier.height(StoryH), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
        if (h.kicker.isNotBlank()) {
            Text(
                h.kicker.uppercase(TR),
                style = TextStyle(fontFamily = LumenSans, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp, color = kicker),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val long = h.title.length > 18
        Text(
            h.title,
            style = TextStyle(
                fontFamily = LumenDisplay,
                fontSize = if (long) 40.sp else 56.sp,
                lineHeight = if (long) 42.sp else 56.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = if (long) (-1).sp else (-2).sp,
                color = A.Text,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (h.deck.isNotBlank()) {
            Text(h.deck, style = TextStyle(fontFamily = LumenSans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = A.Soft), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        h.progress?.let { p ->
            Box(Modifier.width(220.dp).height(4.dp).clip(Pill).background(Color.White.copy(alpha = 0.16f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.03f, 1f)).background(Brush.horizontalGradient(listOf(tint, Color.White))))
            }
        }
        if (h.action.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("OK", style = TextStyle(fontFamily = LumenSans, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = A.Soft))
                }
                Spacer(Modifier.width(7.dp))
                Text(h.action.lowercase(TR), style = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = A.Dim))
            }
        }
    }
}

/** Match lead: both crests on a frosted plate where the art would be, home side first. */
@Composable
private fun MatchCrests(vm: LauncherViewModel) {
    val m by vm.match.collectAsStateWithLifecycle()
    val s = m ?: return
    val us = MatchConfig.Team.espnId
    val (home, away) = if (s.home) us to s.opponentId else s.opponentId to us
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 100.dp, end = Side)
                .clip(RoundedCornerShape(26.dp))
                .background(A.Frost)
                .border(0.75.dp, A.Rim, RoundedCornerShape(26.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamLogo(home, 92.dp)
            Text("–", style = TextStyle(fontFamily = LumenDisplay, fontSize = 32.sp, color = A.Dim))
            TeamLogo(away, 92.dp)
        }
    }
}

// --- Rows ----------------------------------------------------------------------------------------

private fun titleOf(key: String) = when (key) {
    ROW_FAVORITES -> t("Favoriler")
    ROW_WATCH -> t("İzlemeye devam et")
    ROW_FOR_YOU -> t("Senin için")
    else -> t("Tüm uygulamalar")
}

@Composable
private fun Rows(
    vm: LauncherViewModel,
    apps: List<AppEntry>,
    favorites: List<FavoriteCard>,
    watch: List<WatchItem>,
    forYou: List<WatchItem>,
    moving: String?,
    movingFavorite: String?,
    rows: RowsController,
    focus: MutableState<Focus?>,
    aura: AuraLight,
    position: MutableState<Pair<Int, Int>>,
    actions: HomeActions,
    onOpenApps: () -> Unit,
) {
    val keys = rowKeys(favorites, watch, forYou)
    val active = keys.indexOf(rows.activeKey).coerceAtLeast(0)
    val shift by animateDpAsState(-RowPitch * active, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow), label = "auraRows")

    Tabs(keys, active, aura, position)
    Box(
        Modifier
            .offset(y = ClipTop)
            .fillMaxWidth()
            .height(540.dp - ClipTop)
            .clipToBounds()
            .onPreviewKeyEvent { e ->
                if (moving != null || movingFavorite != null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> active > 0 && rows.focus(keys[active - 1])
                    Key.DirectionDown -> active >= keys.lastIndex || rows.focus(keys[active + 1])
                    else -> false
                }
            },
    ) {
        Column(
            Modifier
                .padding(top = RowsTop - ClipTop)
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = shift.toPx() },
        ) {
            keys.forEachIndexed { i, key ->
                // The row in use, the next one peeking dimmed, the rest not drawn at all
                val target = when (i) {
                    active -> 1f
                    active + 1 -> 0.3f
                    else -> 0f
                }
                // A row leaving upwards fades fast: it passes behind the tabs on its way out
                val alpha by animateFloatAsState(
                    target,
                    tween(if (target == 0f) 160 else 320),
                    label = "auraRowAlpha",
                )
                val entry = Modifier
                    .focusRequester(rows.requester(key))
                    .onFocusChanged { if (it.hasFocus) rows.activeKey = key }
                Box(
                    Modifier
                        .height(RowPitch)
                        .graphicsLayer {
                            this.alpha = alpha
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                ) {
                    when (key) {
                        ROW_FAVORITES -> FavoritesRow(favorites, vm, movingFavorite, rows.listState(key), entry, focus, aura, position, actions, onOpenApps)
                        ROW_WATCH -> WatchRow(watch, vm, rows.listState(key), entry, focus, aura, position, actions)
                        ROW_FOR_YOU -> WatchRow(forYou, vm, rows.listState(key), entry, focus, aura, position, actions)
                        ROW_APPS -> AppsRow(apps, vm, moving, rows.listState(key), entry, focus, aura, position, actions, onOpenApps)
                    }
                }
            }
        }
    }
}

/** Every row's name in one line; the one in use is white and underlined in the aura's color. */
@Composable
private fun Tabs(keys: List<String>, active: Int, aura: AuraLight, position: State<Pair<Int, Int>>) {
    val line = animateColorAsState(aura.color, tween(600), label = "tabLine")
    Row(
        Modifier.offset(y = TabsTop).padding(horizontal = Side).fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEachIndexed { i, key ->
            val on = i == active
            Text(
                titleOf(key),
                modifier = if (on) {
                    Modifier.drawBehind {
                        val h = 3.dp.toPx()
                        drawRoundRect(
                            Brush.horizontalGradient(listOf(line.value, lerp(line.value, Color.White, 0.5f))),
                            topLeft = Offset(0f, size.height + 3.dp.toPx()),
                            size = Size(22.dp.toPx(), h),
                            cornerRadius = CornerRadius(h / 2),
                        )
                    }
                } else {
                    Modifier
                },
                style = TextStyle(
                    fontFamily = LumenDisplay,
                    fontSize = if (on) 15.sp else 13.sp,
                    fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (on) A.Text else A.Muted,
                ),
                maxLines = 1,
            )
            Spacer(Modifier.width(22.dp))
        }
        Spacer(Modifier.weight(1f))
        val (i, n) = position.value
        if (n > 1) {
            Text("${i + 1} / $n", style = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = A.Muted))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.row(entry: Modifier) = fillMaxWidth()
    .height(RowBox)
    .then(entry)
    .focusRestorer()

/** Ends of a row: Left/Right stay put instead of wandering to another row or the top bar. */
@OptIn(ExperimentalComposeUiApi::class)
private val RowEnd = Modifier.focusProperties { right = FocusRequester.Cancel }

@OptIn(ExperimentalComposeUiApi::class)
private val RowStart = Modifier.focusProperties { left = FocusRequester.Cancel }

private fun ends(i: Int, last: Int): Modifier = (if (i == 0) RowStart else Modifier).then(if (i == last) RowEnd else Modifier)

private val RowPadding = PaddingValues(horizontal = Side, vertical = CardPad)

@Composable
private fun FavoritesRow(
    cards: List<FavoriteCard>,
    vm: LauncherViewModel,
    moving: String?,
    state: LazyListState,
    entry: Modifier,
    focus: MutableState<Focus?>,
    aura: AuraLight,
    position: MutableState<Pair<Int, Int>>,
    actions: HomeActions,
    onOpenApps: () -> Unit,
) {
    val n = cards.size + 1
    LazyRow(state = state, modifier = Modifier.row(entry), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(CardGap)) {
        itemsIndexed(cards, key = { _, c -> c.key }) { index, card ->
            when (card) {
                is FavoriteCard.App -> AppTile(
                    card.app, vm, aura, moving = moving == card.key, index = index,
                    modifier = Modifier.animateItem().then(ends(index, n - 1)),
                    onFocused = {
                        focus.value = Focus.App(card.app)
                        position.value = index to n
                    },
                    onClick = { vm.launchPinned(card) },
                    onLongClick = { actions.onFavoriteMenu(card) },
                )
                is FavoriteCard.Content -> ContentTile(
                    card.item, vm, aura, pinned = card, moving = moving == card.key, index = index,
                    modifier = Modifier.animateItem().then(ends(index, n - 1)),
                    onFocused = {
                        focus.value = Focus.Watch(card.item, card.app)
                        position.value = index to n
                    },
                    onClick = { vm.launchPinned(card) },
                    onLongClick = { actions.onFavoriteMenu(card) },
                )
            }
        }
        item(key = "all") { AllAppsTile(aura, onOpenApps) { position.value = n - 1 to n } }
    }
}

@Composable
private fun WatchRow(
    items: List<WatchItem>,
    vm: LauncherViewModel,
    state: LazyListState,
    entry: Modifier,
    focus: MutableState<Focus?>,
    aura: AuraLight,
    position: MutableState<Pair<Int, Int>>,
    actions: HomeActions,
) {
    LazyRow(state = state, modifier = Modifier.row(entry), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(CardGap)) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
            ContentTile(
                item, vm, aura,
                modifier = ends(i, items.lastIndex),
                onFocused = {
                    focus.value = Focus.Watch(item, vm.appFor(item.pkg))
                    position.value = i to items.size
                },
                onClick = { vm.launchWatch(item) },
                onLongClick = { actions.onContentMenu(item) },
            )
        }
    }
}

@Composable
private fun AppsRow(
    apps: List<AppEntry>,
    vm: LauncherViewModel,
    moving: String?,
    state: LazyListState,
    entry: Modifier,
    focus: MutableState<Focus?>,
    aura: AuraLight,
    position: MutableState<Pair<Int, Int>>,
    actions: HomeActions,
    onOpenApps: () -> Unit,
) {
    val n = apps.size + 1
    LazyRow(state = state, modifier = Modifier.row(entry), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(CardGap)) {
        itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
            AppTile(
                app, vm, aura, moving = moving == app.pkg, index = index,
                modifier = Modifier.animateItem().then(if (index == 0) RowStart else Modifier),
                onFocused = {
                    focus.value = Focus.App(app)
                    position.value = index to n
                },
                onClick = { vm.launch(app) },
                onLongClick = { actions.onAppMenu(app) },
            )
        }
        item(key = "all") { AllAppsTile(aura, onOpenApps) { position.value = n - 1 to n } }
    }
}

/** Last card of Favoriler and Tüm uygulamalar: the grid of every app. */
@Composable
private fun AllAppsTile(aura: AuraLight, onClick: () -> Unit, onFocused: () -> Unit) {
    AuraCard(onClick, onClick, RowEnd, glow = A.Glow, aura = aura, onFocused = onFocused) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f)))))
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            AllAppsGlyph()
            Spacer(Modifier.width(8.dp))
            Text(t("Tümü"), style = TextStyle(fontFamily = LumenSans, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = A.Text))
        }
    }
}
