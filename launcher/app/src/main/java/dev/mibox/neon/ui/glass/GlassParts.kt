package dev.mibox.neon.ui.glass

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.ui.GlassPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- Blur ----------------------------------------------------------------------------------------
// Android 11 has no RenderEffect, so the frosted backdrop is made by hand: the art shrunk to a
// 64×36 thumbnail, box-blurred three times (close to a Gaussian) and drawn back up full screen
// with bilinear filtering. 9 KB per image, a few ms on the box's CPU.

private const val BLUR_W = 64
private const val BLUR_H = 36
private const val BLUR_CACHE = 12

private val blurCache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > BLUR_CACHE
}

/** [src] blurred to a tiny bitmap meant to be stretched across the screen; cached under [key]. */
suspend fun blurred(key: String, src: ImageBitmap): ImageBitmap = withContext(Dispatchers.Default) {
    synchronized(blurCache) { blurCache[key] } ?: run {
        val small = Bitmap.createScaledBitmap(src.asAndroidBitmap(), BLUR_W, BLUR_H, true)
        val px = IntArray(BLUR_W * BLUR_H)
        small.getPixels(px, 0, BLUR_W, 0, 0, BLUR_W, BLUR_H)
        if (small !== src.asAndroidBitmap()) small.recycle()
        repeat(3) { boxBlur(px, BLUR_W, BLUR_H, 3) }
        val out = Bitmap.createBitmap(px, BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888).asImageBitmap()
        synchronized(blurCache) { blurCache[key] = out }
        out
    }
}

/** One horizontal and one vertical running-sum pass of radius [r], edges clamped. */
private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, step: Int, lineStep: Int) {
        val div = 2 * r + 1
        for (l in 0 until lines) {
            val base = l * lineStep
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val c = src[base + i.coerceIn(0, len - 1) * step]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * step] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[base + (i + r + 1).coerceAtMost(len - 1) * step]
                val sub = src[base + (i - r).coerceAtLeast(0) * step]
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
    pass(px, tmp, w, h, 1, w)
    pass(tmp, px, h, w, w, 1)
}

/** Apple's materials boost saturation under the frost; also dims a little for white text. */
val FrostFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(1.5f)
        val dim = ColorMatrix(
            floatArrayOf(
                0.78f, 0f, 0f, 0f, 0f,
                0f, 0.78f, 0f, 0f, 0f,
                0f, 0f, 0.78f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        timesAssign(dim)
    },
)

// --- Glass surfaces ------------------------------------------------------------------------------

/**
 * Frosted glass over the (already blurred) backdrop: a faint white fill brighter at the top, a
 * rim that catches light along the upper edge, and a hairline highlight just inside it.
 */
fun Modifier.glass(shape: Shape, fill: Float = 0.10f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = fill + 0.06f),
            1f to Color.White.copy(alpha = fill * 0.5f),
        ),
    )
    .border(
        1.dp,
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.38f),
            0.5f to Color.White.copy(alpha = 0.10f),
            1f to Color.White.copy(alpha = 0.16f),
        ),
        shape,
    )

// --- Cards ---------------------------------------------------------------------------------------

val GlassCardW = 150.dp
val GlassCardH = 84.dp
val GlassCardShape = RoundedCornerShape(14.dp)
const val GLASS_FOCUS_SCALE = 1.14f

/**
 * tvOS-style card: on focus it lifts (spring scale), casts a soft shadow and a light sheen sweeps
 * once across it; a specular glare stays on its upper left while it has focus. No border.
 */
@Composable
fun GlassCard(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    width: Dp = GlassCardW,
    height: Dp = GlassCardH,
    forceBig: Boolean = false,
    onFocused: () -> Unit,
    content: @Composable BoxScope.(big: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val big = focused || forceBig
    val scale by animateFloatAsState(
        if (big) GLASS_FOCUS_SCALE else 1f,
        spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "glassScale",
    )
    // The sheen runs -0.4 → 1.4 across the card each time focus lands
    val sheen = remember { Animatable(-1f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(-0.4f)
            sheen.animateTo(1.4f, tween(850))
        } else {
            sheen.snapTo(-1f)
        }
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .zIndex(if (big) 1f else 0f)
            .size(width, height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(GlassCardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = GlassPalette.Frost,
            focusedContainerColor = GlassPalette.Frost,
            pressedContainerColor = GlassPalette.Frost,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None,
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.Black.copy(alpha = 0.55f), 16.dp)),
        interactionSource = interaction,
    ) {
        content(big)
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val glare = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.75f,
                    )
                    onDrawBehind {
                        if (big) drawRect(glare)
                        val s = sheen.value
                        if (s > -0.5f && s < 1.5f) {
                            val x = s * size.width
                            drawRect(
                                Brush.linearGradient(
                                    0f to Color.Transparent,
                                    0.5f to Color.White.copy(alpha = 0.28f),
                                    1f to Color.Transparent,
                                    start = Offset(x - size.width * 0.35f, 0f),
                                    end = Offset(x + size.width * 0.15f, size.height),
                                ),
                            )
                        }
                    }
                },
        )
    }
}

/** A white capsule progress bar on a dark frosted track. */
@Composable
fun GlassProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0.03f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.95f)),
        )
    }
}

/** Bottom-aligned label inside a card when there is no image. */
@Composable
fun BoxScope.CardCaption(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 8.dp, bottom = 8.dp), content = content)
}

// --- Layer-free crossfades ------------------------------------------------------------------------

/** The value on screen and the one fading out; [progress] is read in the draw phase only. */
@Stable
class Fading<T>(initial: T) {
    var cur by mutableStateOf(initial)
    var prev by mutableStateOf<T?>(null)
    internal val anim = Animatable(1f)
    val progress: Float get() = anim.value
}

@Composable
fun <T> rememberFading(target: T, ms: Int): Fading<T> {
    val f = remember { Fading(target) }
    LaunchedEffect(target) {
        if (f.cur == target) return@LaunchedEffect
        f.prev = f.cur
        f.cur = target
        f.anim.snapTo(0f)
        f.anim.animateTo(1f, tween(ms))
        f.prev = null
    }
    return f
}

/** The frosted wash: a blurred bitmap stretched over the screen, or the aurora without one. */
fun DrawScope.drawWash(img: ImageBitmap?, alpha: Float, match: Boolean) {
    if (alpha <= 0f) return
    if (img == null) {
        val a = if (match) MatchConfig.Team.primary else Color(0xFF3A2BAE)
        val b = if (match) MatchConfig.Team.secondary else Color(0xFF0E7C9E)
        drawRect(Brush.linearGradient(listOf(Color(0xFF0B0D12), Color(0xFF121528))), alpha = alpha)
        drawRect(
            Brush.radialGradient(listOf(a.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width * 0.78f, size.height * 0.1f), radius = size.width * 0.68f),
            alpha = alpha,
        )
        drawRect(
            Brush.radialGradient(listOf(b.copy(alpha = 0.4f), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.95f), radius = size.width * 0.62f),
            alpha = alpha,
        )
        return
    }
    drawImage(
        img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        alpha = alpha,
        colorFilter = FrostFilter,
        filterQuality = FilterQuality.Low,
    )
}

/**
 * A paint that draws [img] cropped into the [w]×[h] box at [left], fading out towards the box's
 * left and bottom edges. The fade is a shader mask, so no offscreen layer is needed.
 */
fun maskedPoster(img: ImageBitmap, left: Float, w: Float, h: Float): android.graphics.Paint {
    val bmp = img.asAndroidBitmap()
    val scale = maxOf(w / bmp.width, h / bmp.height)
    val shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
        setLocalMatrix(
            android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(left + (w - bmp.width * scale) / 2f, (h - bmp.height * scale) / 2f)
            },
        )
    }
    val black = 0xFF000000.toInt()
    val fromLeft = android.graphics.LinearGradient(left, 0f, left + w * 0.38f, 0f, 0, black, android.graphics.Shader.TileMode.CLAMP)
    val toBottom = android.graphics.LinearGradient(0f, h * 0.68f, 0f, h, black, 0, android.graphics.Shader.TileMode.CLAMP)
    val mask = android.graphics.ComposeShader(fromLeft, toBottom, android.graphics.PorterDuff.Mode.DST_IN)
    return android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = android.graphics.ComposeShader(shader, mask, android.graphics.PorterDuff.Mode.DST_IN)
    }
}
