package dev.mibox.neon.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.HeroReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import dev.mibox.neon.i18n.t

private const val IDLE_MS = 30_000L
private const val SHOWCASE_SLIDE_MS = 7_000L

/**
 * The original launcher home, unchanged: backdrop, hero, top bar, the card rows and the footer.
 * LauncherScreen owns the overlays and the lifecycle; this owns the rows' focus and scroll.
 */
@Composable
fun NeonHome(vm: LauncherViewModel, actions: HomeActions, signals: SharedFlow<HomeSignal>) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val favorites by vm.favoriteCards.collectAsStateWithLifecycle()
    val watch by vm.watchNext.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val watchLoaded by vm.watchLoaded.collectAsStateWithLifecycle()
    val moving by vm.moving.collectAsStateWithLifecycle()

    // Plain state holder: written by cards, read only by the hero host and the footer
    val focus = remember { mutableStateOf<Focus?>(null) }
    val rows = remember { RowsController() }
    val firstRow = rowKeys(favorites, watch, forYou).first()
    val scope = rememberCoroutineScope()

    // Start on the first card of the top row. Wait for Watch Next (it decides which row is on
    // top), then keep asking briefly: the row's node appears a frame or two after its data and
    // requestFocus throws until then, which would let the system park focus on a top-bar chip.
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
    // Home key: back to the first card of the top row (LauncherScreen closes the overlays)
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            focus.value = null
            rows.scrollAllToStart()
            rows.focus(rowKeys(vm.favoriteCards.value, vm.watchNext.value, vm.forYou.value).first())
        }
    }
    LaunchedEffect(Unit) {
        signals.collect { s ->
            when (s) {
                // The rows' own focus restorer brings focus back by itself; only step in when it is gone
                HomeSignal.Restore -> if (focus.value == null) {
                    rows.focus(rows.activeKey ?: rowKeys(vm.favoriteCards.value, vm.watchNext.value, vm.forYou.value).first())
                }
                HomeSignal.AppHidden -> {
                    focus.value = null
                    rows.focus(rows.activeKey ?: rowKeys(vm.favoriteCards.value, vm.watchNext.value, vm.forYou.value).first())
                }
                // Unpinning in Favoriler removes the focused card: take focus back once it is gone
                HomeSignal.PinToggled -> if (rows.activeKey == ROW_FAVORITES) {
                    focus.value = null
                    scope.launch {
                        delay(300)
                        val keys = rowKeys(vm.favoriteCards.value, vm.watchNext.value, vm.forYou.value)
                        rows.focus(if (ROW_FAVORITES in keys) ROW_FAVORITES else keys.first())
                    }
                }
            }
        }
    }

    // Absolute layout in the SVG's coordinate space (px / 2), 48dp side margins
    Box(Modifier.fillMaxSize()) {
        HeroHost(vm, focus)

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .offset(y = 35.dp)
                // Down from the top bar goes back to the row on screen
                .focusProperties { down = rows.requester(rows.activeKey ?: firstRow) },
        ) {
            TopBar(
                vm,
                onOpenMenu = actions.onOpenMenu,
                onOpenLight = actions.onOpenLight,
                onOpenControl = actions.onOpenControl,
                onOpenNet = actions.onOpenNet,
                onOpenMatch = actions.onOpenMatch,
            )
        }
        RowsArea(
            vm, favorites, apps, watch, forYou, moving, rows, focus,
            onMenu = actions.onAppMenu,
            onContentMenu = actions.onContentMenu,
        )
        FooterLabel(focus, moving != null)
    }
}

/**
 * Decides what the hero shows and tints everything behind it to match:
 * idle for 30s → showcase slides; a focused card → that app or episode; else the last used app.
 * Reading focus here keeps focus changes from recomposing the rest of the screen.
 */
@Composable
private fun HeroHost(vm: LauncherViewModel, focus: MutableState<Focus?>) {
    val hero by vm.hero.collectAsStateWithLifecycle()
    val showcase by vm.showcase.collectAsStateWithLifecycle()
    val lastInput by vm.lastInput.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var slide by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(lastInput, showcase.size) {
        slide = null
        if (showcase.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(IDLE_MS)
            var i = 0
            while (true) {
                slide = i % showcase.size
                delay(SHOWCASE_SLIDE_MS)
                i++
            }
        }
    }

    val h = hero
    val f = focus.value
    val s = slide
    val content: HeroContent? = when {
        s != null && s < showcase.size -> showcase[s].let { HeroContent(t("Favorilerin"), it.label, null, it) }
        // Episodes keep their season/episode line, recommendations say where they come from;
        // apps are just their name
        f is Focus.Watch && f.app != null -> HeroContent(null, f.item.title, f.item.subtitle ?: f.app.label, f.app)
        f is Focus.App -> HeroContent(null, f.app.label, null, f.app)
        // Usage history says this is what is usually opened now: say so in the eyebrow line
        h != null -> HeroContent(if (h.reason == HeroReason.Predicted) t("Genelde bu saatte") else null, h.app.label, null, h.app)
        else -> null
    }

    Backdrop(vm, content?.app)
    HeroLogo(vm, content?.app)
    Box(Modifier.padding(start = 48.dp)) {
        content?.let { Hero(it) }
    }
}

/**
 * The SVG's background: diagonal #15122a→#1c1430→#2c1338, a violet glow low-left, a magenta
 * glow high-right (here tinted by the hero's app) and a 45° hairline hatch. Colors animate in
 * the draw phase only, so a change never recomposes the screen.
 */
@Composable
private fun Backdrop(vm: LauncherViewModel, target: AppEntry?) {
    var art by remember { mutableStateOf<AppArt?>(null) }
    LaunchedEffect(target) { art = target?.let { vm.art(it) } }
    val accent = tame(art?.vibrant)
    // The room's backlight follows the same color as the on-screen glow
    LaunchedEffect(accent) { vm.onAccent(accent) }
    val glow = animateColorAsState(accent, tween(700), label = "glow")

    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val base = Brush.linearGradient(
                    0f to Neon.Bg1, 0.55f to Neon.Bg2, 1f to Neon.Bg3,
                    start = Offset.Zero, end = Offset(w, h),
                )
                // SVG radial gradients are elliptical (objectBoundingBox): draw circles in a
                // w×w square and squash it to w×h
                val violet = Brush.radialGradient(
                    listOf(Neon.Violet.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(0.18f * w, 0.90f * w),
                    radius = 0.50f * w,
                )
                val step = 7.dp.toPx() * 1.4142f
                val hatch = Path().apply {
                    var x = -h
                    while (x < w) {
                        moveTo(x, h)
                        lineTo(x + h, 0f)
                        x += step
                    }
                }
                val hatchStroke = Stroke(0.5.dp.toPx())
                onDrawBehind {
                    drawRect(base)
                    scale(scaleX = 1f, scaleY = h / w, pivot = Offset.Zero) {
                        drawRect(violet, size = Size(w, w))
                        val a = glow.value
                        drawRect(
                            Brush.radialGradient(
                                0f to a.copy(alpha = 0.55f),
                                0.45f to lerp(a, Color.Black, 0.15f).copy(alpha = 0.22f),
                                1f to Color.Transparent,
                                center = Offset(0.76f * w, 0.24f * w),
                                radius = 0.55f * w,
                            ),
                            size = Size(w, w),
                        )
                    }
                    drawPath(hatch, Color.White.copy(alpha = 0.035f), style = hatchStroke)
                }
            },
    )
}

/** Where the SVG has its giant 7% "N": the hero app's real icon, just as faint. */
@Composable
private fun HeroLogo(vm: LauncherViewModel, app: AppEntry?) {
    var icon by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app?.pkg) { icon = app?.let { vm.heroIcon(it) } }
    icon?.let {
        Image(
            it,
            contentDescription = null,
            modifier = Modifier
                .offset(x = 670.dp, y = 150.dp)
                .size(230.dp)
                .alpha(0.09f)
                // Icons usually have an opaque square background: melt its edges away
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.radialGradient(0.45f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}
