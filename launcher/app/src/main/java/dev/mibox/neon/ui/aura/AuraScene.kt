package dev.mibox.neon.ui.aura

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.Headline
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.data.Weather
import dev.mibox.neon.data.WeatherNow
import dev.mibox.neon.ui.AuraPalette
import dev.mibox.neon.ui.LumenDisplay
import dev.mibox.neon.ui.LumenSans
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.rememberNow
import dev.mibox.neon.ui.tame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val A = AuraPalette
private val Side = 48.dp

/** A minute without a key on the home starts Sahne. */
private const val SCENE_IDLE_MS = 60_000L
private const val SLIDE_MS = 9_000L

/** Whether Sahne plays, and what OK opens meanwhile (the slide on screen). */
internal class SceneState {
    var on by mutableStateOf(false)
    var open: (() -> Unit)? = null
}

/**
 * Starts Sahne after [SCENE_IDLE_MS] without input, only while the home is resumed and has the
 * window (no dialog open) and nothing is being moved. Lives in its own composable: the last
 * input changes with every key, and only this should recompose for it.
 */
@Composable
internal fun SceneTimer(vm: LauncherViewModel, scene: SceneState, busy: Boolean) {
    val lastInput by vm.lastInput.collectAsStateWithLifecycle()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lastInput, windowFocused, busy) {
        scene.on = false
        if (!windowFocused || busy) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                delay(SCENE_IDLE_MS)
                scene.on = true
                awaitCancellation()
            } finally {
                scene.on = false
            }
        }
    }
}

/** Draws Sahne while it plays or fades; [alpha] is read in the draw phase only. */
@Composable
internal fun SceneHost(vm: LauncherViewModel, scene: SceneState, alpha: State<Float>, aura: AuraLight) {
    val visible by remember { derivedStateOf { scene.on || alpha.value > 0f } }
    if (visible) AuraScene(vm, scene, alpha, aura)
}

private sealed interface Slide {
    data class Watch(val item: WatchItem) : Slide
    data class App(val app: AppEntry) : Slide
}

private val SceneClock = DateTimeFormatter.ofPattern("HH:mm", TR)
private val SceneDay = DateTimeFormatter.ofPattern("d MMMM", TR)

/**
 * Sahne: what you watch, full screen, one slow zoom each (Ken Burns), the room's lamp taking each
 * one's color; a big clock with the day and the sky outside, and the sky itself drawn over it
 * when it rains, snows, or the night is clear. Neon's idle showcase grown up.
 */
@Composable
private fun AuraScene(vm: LauncherViewModel, scene: SceneState, alpha: State<Float>, aura: AuraLight) {
    val watch by vm.watchNext.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val favorites by vm.favoriteCards.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val now by rememberNow()

    val slides: List<Slide> = remember(watch, forYou, favorites, apps) {
        buildList {
            (watch + forYou).distinctBy { it.id }.take(8).forEach { add(Slide.Watch(it)) }
            if (size < 4) {
                val pinned = favorites.filterIsInstance<FavoriteCard.App>().map { it.app }
                pinned.ifEmpty { apps }.take(6 - size).forEach { add(Slide.App(it)) }
            }
        }
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slides.size) {
        while (true) {
            delay(SLIDE_MS)
            index++
        }
    }
    val slide = if (slides.isEmpty()) null else slides[index.mod(slides.size)]
    val image by produceState<ImageBitmap?>(null, slide) {
        value = when (slide) {
            is Slide.Watch -> vm.largeArt(slide.item) ?: vm.appFor(slide.item.pkg)?.let { vm.largeArt(it) }
            is Slide.App -> vm.largeArt(slide.app)
            null -> null
        }
    }
    val headline: Headline? = when (slide) {
        is Slide.Watch -> vm.headlineFor(slide.item)
        is Slide.App -> vm.headlineFor(slide.app)
        null -> null
    }
    // Each slide colors the lamp; the home gets its own color back afterwards
    DisposableEffect(Unit) {
        val before = aura.color
        onDispose {
            aura.color = before
            scene.open = null
        }
    }
    LaunchedEffect(slide) {
        scene.open = when (slide) {
            is Slide.Watch -> ({ vm.launchWatch(slide.item) })
            is Slide.App -> ({ vm.launch(slide.app) })
            null -> null
        }
        val art = when (slide) {
            is Slide.Watch -> vm.watchArt(slide.item) ?: vm.appFor(slide.item.pkg)?.let { vm.art(it) }
            is Slide.App -> vm.art(slide.app)
            null -> null
        }
        if (art != null) aura.color = tame(art.vibrant)
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(Color.Black),
    ) {
        Crossfade(image, animationSpec = tween(1800), label = "sceneArt") { img ->
            if (img != null) KenBurns(img) else Glow(aura.color)
        }
        skyOf(weather)?.let { Sky(it) }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val top = size.height * 0.4f
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black.copy(alpha = 0.85f),
                            startY = top,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, size.height - top),
                    )
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 1f to Color.Transparent, endY = 110.dp.toPx()), size = Size(size.width, 110.dp.toPx()))
                },
        )
        Text(
            "serwan",
            modifier = Modifier.align(Alignment.TopStart).padding(start = Side, top = 28.dp),
            style = TextStyle(fontFamily = LumenDisplay, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = A.Dim),
        )

        // The clock, the day, the sky outside
        Column(Modifier.align(Alignment.BottomStart).padding(start = Side, bottom = 40.dp)) {
            Text(
                now.format(SceneClock),
                style = TextStyle(fontFamily = LumenDisplay, fontSize = 104.sp, lineHeight = 104.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-5).sp, color = A.Text),
            )
            val w = weather
            val day = now.dayOfWeek.getDisplayName(JTextStyle.FULL, TR)
            val sky = w?.let { " · ${it.tempC.roundToInt()}° ${city.name}" + Weather.describe(it.code, it.isDay).second.let { d -> if (d.isBlank()) "" else ", ${d.lowercase(TR)}" } } ?: ""
            Text(
                "${greeting(now.hour)} · $day ${now.format(SceneDay)}$sky",
                style = TextStyle(fontFamily = LumenSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = A.Soft),
                maxLines = 1,
            )
        }

        // What is on screen, and that OK opens it
        if (headline != null) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = Side, bottom = 48.dp).widthIn(max = 360.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (headline.kicker.isNotBlank()) {
                    Text(
                        headline.kicker.uppercase(TR),
                        style = TextStyle(fontFamily = LumenSans, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp, color = lerp(aura.color, Color.White, 0.55f)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    headline.title,
                    style = TextStyle(fontFamily = LumenDisplay, fontSize = 26.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, color = A.Text, textAlign = TextAlign.End),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .height(18.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("OK", style = TextStyle(fontFamily = LumenSans, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = A.Soft))
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        headline.action.ifBlank { dev.mibox.neon.i18n.t("Aç") }.lowercase(TR),
                        style = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = A.Dim),
                    )
                }
                // Which slide of how many
                if (slides.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val cur = index.mod(slides.size)
                        repeat(slides.size) { i ->
                            Box(
                                Modifier
                                    .size(width = if (i == cur) 16.dp else 5.dp, height = 5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (i == cur) Color.White else Color.White.copy(alpha = 0.3f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The art full screen, zooming in slowly from a corner that differs from slide to slide. */
@Composable
private fun KenBurns(img: ImageBitmap) {
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(Unit) { zoom.animateTo(1.1f, tween(SLIDE_MS.toInt() + 2_500, easing = LinearEasing)) }
    val origin = remember(img) {
        val h = img.hashCode()
        TransformOrigin(if (h and 1 == 0) 0.2f else 0.8f, if (h and 2 == 0) 0.3f else 0.65f)
    }
    Image(
        img,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.value
            scaleY = zoom.value
            transformOrigin = origin
        },
        contentScale = ContentScale.Crop,
    )
}

/** No art: the aura alone, two soft pools of its color. */
@Composable
private fun Glow(color: Color) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width * 0.75f, size.height * 0.25f), radius = size.width * 0.55f),
                radius = size.width * 0.55f,
                center = Offset(size.width * 0.75f, size.height * 0.25f),
            )
            drawCircle(
                Brush.radialGradient(listOf(lerp(color, Color.Blue, 0.4f).copy(alpha = 0.4f), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.9f), radius = size.width * 0.5f),
                radius = size.width * 0.5f,
                center = Offset(size.width * 0.15f, size.height * 0.9f),
            )
        },
    )
}

private enum class SkyKind { RAIN, SNOW, STARS }

/** The weather outside, when it is worth drawing: rain, snow, or stars on a clear night. */
private fun skyOf(w: WeatherNow?): SkyKind? = when {
    w == null -> null
    w.code in 51..67 || w.code in 80..82 || w.code in 95..99 -> SkyKind.RAIN
    w.code in 71..77 || w.code == 85 || w.code == 86 -> SkyKind.SNOW
    w.code <= 1 && !w.isDay -> SkyKind.STARS
    else -> null
}

private const val DROPS = 90

/** Particles over the art; positions are pure functions of time, so nothing is allocated per frame. */
@Composable
private fun Sky(kind: SkyKind) {
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { ms.longValue = (it - start) / 1_000_000 }
    }
    val seed = remember { Random(42).let { r -> FloatArray(DROPS * 4) { r.nextFloat() } } }
    Canvas(Modifier.fillMaxSize()) {
        val t = ms.longValue / 1000f
        val w = size.width
        val h = size.height
        when (kind) {
            SkyKind.RAIN -> {
                val stroke = 1.dp.toPx()
                for (i in 0 until DROPS) {
                    val len = (12 + seed[i * 4 + 3] * 18).dp.toPx()
                    val speed = 0.7f + seed[i * 4 + 1] * 0.5f // screen heights per second
                    val y = ((seed[i * 4 + 2] + t * speed) % 1f) * (h + len) - len
                    val x = seed[i * 4] * w * 1.15f - w * 0.1f + y * 0.12f
                    drawLine(
                        Color.White.copy(alpha = 0.08f + seed[i * 4 + 3] * 0.12f),
                        Offset(x, y),
                        Offset(x + len * 0.12f, y + len),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            SkyKind.SNOW -> {
                val sway = 14.dp.toPx()
                for (i in 0 until 70) {
                    val speed = 0.04f + seed[i * 4 + 1] * 0.06f
                    val r = (1.2f + seed[i * 4 + 3] * 2f).dp.toPx()
                    val y = ((seed[i * 4 + 2] + t * speed) % 1f) * (h + 2 * r) - r
                    val x = seed[i * 4] * w + sin(t * 0.7f + seed[i * 4 + 1] * 2f * PI.toFloat()) * sway
                    drawCircle(Color.White.copy(alpha = 0.3f + seed[i * 4 + 3] * 0.4f), radius = r, center = Offset(x, y))
                }
            }
            SkyKind.STARS -> {
                for (i in 0 until 70) {
                    val x = seed[i * 4] * w
                    val y = seed[i * 4 + 2] * h * 0.55f
                    val twinkle = 0.5f + 0.5f * sin(t * (0.5f + seed[i * 4 + 1]) + seed[i * 4 + 3] * 2f * PI.toFloat())
                    drawCircle(
                        Color.White.copy(alpha = 0.12f + 0.5f * twinkle * seed[i * 4 + 3]),
                        radius = (0.6f + seed[i * 4 + 1]).dp.toPx(),
                        center = Offset(x, y),
                    )
                }
            }
        }
    }
}
