package dev.mibox.neon.ui.teletext

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.ui.HomeActions
import dev.mibox.neon.ui.HomeStyle
import dev.mibox.neon.ui.LocalHomeStyle
import dev.mibox.neon.ui.TeletextPalette
import kotlin.math.roundToInt

// --- Palette and metrics -------------------------------------------------------------------------
// The concept is 1920x1080 px; the app lays out in 960x540 dp, so 40px rows are 20dp rows.

internal val TtBlack = TeletextPalette.Black
internal val TtRed = TeletextPalette.Red
internal val TtGreen = TeletextPalette.Green
internal val TtYellow = TeletextPalette.Yellow
internal val TtBlue = TeletextPalette.Blue
internal val TtMagenta = TeletextPalette.Magenta
internal val TtCyan = TeletextPalette.Cyan
internal val TtWhite = TeletextPalette.White

/** One teletext line. */
internal val TtRowH = 20.dp

/** Body text: 13.8sp x the style's 1.45 = 20sp, the concept's 40px font. */
internal val TtSp = 13.8.sp

/** Double-height text (the concept's 80px); sizes above 24sp are not scaled by the style. */
internal val TtSp2 = 40.sp

/** Triple-height text (the concept's 120px): the temperature. */
internal val TtSp3 = 60.sp

/** The numbered pages of the service. 450 (Ağ durumu) is a block of 400, 5xx are the apps of 500. */
internal object TtPage {
    const val HOME = 100
    const val RESUME = 200
    const val SPORT = 300
    const val LIVE = 301
    const val WEATHER = 400
    const val APPS = 500
}

/** Black text on a focused (white) row, the row's own color otherwise. */
internal fun tc(focused: Boolean, color: Color): Color = if (focused) TtBlack else color

// --- Navigation state ----------------------------------------------------------------------------

/** Plain holder shared by the shell and the pages; only [accent] and [tick] are observable. */
@Stable
internal class TtNav {
    /** The first focus target of the open page (a page puts it on its first row). */
    val first = FocusRequester()

    /** Always-present target of sub pages (the "100 ANA SAYFA" row): used when a page has nothing to focus. */
    val fallback = FocusRequester()

    /** Row that had focus last; written by every [TtFocusable], read for Restore. Not observable. */
    var last: FocusRequester? = null

    /** App of the focused row for the room light; null = follow the headline. Read only by TtAccent. */
    val accent = mutableStateOf<AppEntry?>(null)

    /** Bumped to ask the shell (and P500's list) to reset focus to the page's first element. */
    val tick = mutableIntStateOf(0)

    /** Index row ("200", "300", ...) that takes focus when P100 is entered. */
    var entryKey: String = "200"

    private val index = HashMap<String, FocusRequester>()
    fun index(key: String): FocusRequester = index.getOrPut(key) { FocusRequester() }
}

internal val LocalTtNav = staticCompositionLocalOf<TtNav> { error("TtNav is provided by TeletextHome") }

/** What the pages need from the shell. */
@Stable
internal class TtCtx(
    val vm: LauncherViewModel,
    val actions: HomeActions,
    val nav: TtNav,
    /** Opens a page by number (100, 200, 300, 301, 400, 500). */
    val go: (Int) -> Unit,
) {
    fun openLight() {
        vm.wizRefresh()
        actions.onOpenLight()
    }
}

// --- Text ----------------------------------------------------------------------------------------

private fun HomeStyle.tt(size: TextUnit, color: Color): TextStyle {
    val lineHeight = if (size.value > 24f) size else (size.value * fontScale).sp
    return type(size, color = color).copy(lineHeight = lineHeight)
}

/** All teletext text: VT323 through the style (size scale included), upper-cased with Turkish rules. */
@Composable
internal fun TtText(
    text: String,
    color: Color = TtWhite,
    modifier: Modifier = Modifier,
    size: TextUnit = TtSp,
) {
    val st = LocalHomeStyle.current
    val style = remember(st, size, color) { st.tt(size, color) }
    Text(
        st.caps(st.clean(text)),
        modifier = modifier,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One 20dp line holding a single text (optionally on a colored tag). */
@Composable
internal fun TtLine(
    text: String,
    color: Color = TtWhite,
    modifier: Modifier = Modifier,
    bg: Color? = null,
    inset: Dp = 8.dp,
) {
    Box(modifier.height(TtRowH).padding(horizontal = inset), contentAlignment = Alignment.CenterStart) {
        if (bg == null) {
            TtText(text, color)
        } else {
            TtText(text, color, Modifier.background(bg).padding(horizontal = 6.dp))
        }
    }
}

/** "KAYIT YOK" and friends: what an empty section shows. */
@Composable
internal fun TtEmpty(text: String = "Kayıt yok") {
    TtLine(text, TtCyan)
}

/** A colored band: section titles (20dp) and page titles (40dp, double-height text). */
@Composable
internal fun TtBand(
    left: String,
    right: String = "",
    bg: Color,
    fg: Color = TtBlack,
    modifier: Modifier = Modifier,
    rightColor: Color = fg,
    size: TextUnit = TtSp,
    height: Dp = TtRowH,
) {
    Row(
        modifier.fillMaxWidth().height(height).background(bg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TtText(left, fg, Modifier.weight(1f), size)
        if (right.isNotEmpty()) TtText(right, rightColor, size = size)
    }
}

/**
 * [text] followed by a dotted leader that fills the rest of the available width (the index look).
 * The dots start where the text ends: [onTextLayout] stores the line end and the draw phase reads it.
 */
@Composable
internal fun TtLead(text: String, color: Color, modifier: Modifier = Modifier, dots: Color = color) {
    val st = LocalHomeStyle.current
    val style = remember(st, color) { st.tt(TtSp, color) }
    var end by remember { mutableFloatStateOf(0f) }
    val dotColor = dots.copy(alpha = 0.6f)
    Box(
        modifier
            .height(TtRowH)
            .drawBehind {
                val gap = 8.dp.toPx()
                val step = 6.dp.toPx()
                val d = 1.5.dp.toPx()
                val y = size.height * 0.72f
                val limit = size.width - gap
                var x = end + gap
                while (x < limit) {
                    drawRect(dotColor, Offset(x, y), Size(d, d))
                    x += step
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            st.caps(st.clean(text)),
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { end = it.getLineRight(0) },
        )
    }
}

/** A progress bar of block cells (7x10dp, 1dp apart): [fraction] filled in [on], the rest in [off]. */
@Composable
internal fun TtBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    cells: Int = 16,
    on: Color = TtGreen,
    off: Color = TtBlue,
) {
    val filled = (fraction.coerceIn(0f, 1f) * cells).roundToInt()
    Spacer(
        modifier
            .size(8.dp * cells, 10.dp)
            .drawBehind {
                val w = 7.dp.toPx()
                val step = 8.dp.toPx()
                for (i in 0 until cells) drawRect(if (i < filled) on else off, Offset(i * step, 0f), Size(w, size.height))
            },
    )
}

// --- Focusables ----------------------------------------------------------------------------------

/**
 * The one focusable of the service. Inverse video on focus: [focusedContainer] (white) with
 * [focusedContent] (black) text, no scale, no glow, no animation. [restOutline] / [focusOutline]
 * draw an inset 3dp frame at rest / on focus, over the content. The focus state is read in the
 * draw phase for the frame; the content lambda gets it as a plain Boolean for its text colors.
 */
@Composable
internal fun TtFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    requester: FocusRequester? = null,
    accent: AppEntry? = null,
    container: Color = Color.Transparent,
    contentColor: Color = TtWhite,
    focusedContainer: Color = TtWhite,
    focusedContent: Color = TtBlack,
    restOutline: Color? = null,
    focusOutline: Color? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val nav = LocalTtNav.current
    val req = requester ?: remember { FocusRequester() }
    val src = remember { MutableInteractionSource() }
    val focusedState = src.collectIsFocusedAsState()
    val focused = focusedState.value
    LaunchedEffect(focused, accent, req) {
        if (focused) {
            nav.last = req
            nav.accent.value = accent
        }
    }
    val frame = if (restOutline == null && focusOutline == null) {
        Modifier
    } else {
        Modifier.drawWithCache {
            val w = 3.dp.toPx()
            val stroke = Stroke(w)
            val topLeft = Offset(w / 2f, w / 2f)
            val box = Size(size.width - w, size.height - w)
            onDrawWithContent {
                drawContent()
                val c = if (focusedState.value) focusOutline else restOutline
                if (c != null) drawRect(c, topLeft, box, style = stroke)
            }
        }
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.focusRequester(req).then(frame),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = contentColor,
            focusedContainerColor = focusedContainer,
            focusedContentColor = focusedContent,
            pressedContainerColor = focusedContainer,
            pressedContentColor = focusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = src,
    ) {
        content(this, focused)
    }
}

/** The ► marker of a focused row, drawn as a triangle (VT323 has no such glyph). 26dp wide with margins. */
@Composable
private fun TtMarker(focused: Boolean) {
    Spacer(
        Modifier
            .size(26.dp, TtRowH)
            .drawWithCache {
                val x0 = 8.dp.toPx()
                val w = 10.dp.toPx()
                val h = 12.dp.toPx()
                val y0 = (size.height - h) / 2f
                val tri = Path().apply {
                    moveTo(x0, y0)
                    lineTo(x0 + w, size.height / 2f)
                    lineTo(x0, y0 + h)
                    close()
                }
                onDrawBehind { if (focused) drawPath(tri, TtBlack) }
            },
    )
}

/**
 * An index row: ► marker, then [content]. Focus = the whole row white with black text. [fill]
 * false makes the row as wide as its content (the favorites line). [height] 40dp gives a two-line row.
 */
@Composable
internal fun TtRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    requester: FocusRequester? = null,
    accent: AppEntry? = null,
    marker: Boolean = true,
    fill: Boolean = true,
    height: Dp = TtRowH,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    TtFocusable(
        onClick = onClick,
        modifier = (if (fill) modifier.fillMaxWidth() else modifier).height(height),
        onLongClick = onLongClick,
        requester = requester,
        accent = accent,
    ) { focused ->
        Row(
            if (fill) Modifier.fillMaxSize() else Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.Top,
        ) {
            if (marker) TtMarker(focused)
            content(this, focused)
        }
    }
}

/** A colored label button (fastext keys, MAÇI AÇ…): its color at rest, white with a colored frame on focus. */
@Composable
internal fun TtButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color,
    fg: Color = TtBlack,
    requester: FocusRequester? = null,
    content: @Composable RowScope.(fg: Color) -> Unit,
) {
    TtFocusable(
        onClick = onClick,
        modifier = modifier.height(TtRowH),
        requester = requester,
        container = color,
        contentColor = fg,
        focusedContainer = TtWhite,
        focusedContent = TtBlack,
        focusOutline = color,
    ) { focused ->
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(this, if (focused) TtBlack else fg)
        }
    }
}
