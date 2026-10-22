package dev.mibox.neon.ui.teletext

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mibox.neon.data.WeatherNow
import kotlin.math.ceil
import kotlin.math.hypot

// --- Mosaic bitmaps ------------------------------------------------------------------------------
// One letter per cell: R G Y B M C W = the teletext colors, K = black, anything else = empty.
// Each bitmap is drawn by ONE draw call (see TtMosaic), never by hundreds of composables.

/** "NEON" in 5x6 block letters, each letter its own color (cyan, yellow, green, magenta). */
internal val TtLogo: List<String> = run {
    val glyphs = mapOf(
        'N' to listOf("X...X", "XX..X", "X.X.X", "X..XX", "X...X", "X...X"),
        'E' to listOf("XXXXX", "X....", "XXXX.", "X....", "X....", "XXXXX"),
        'O' to listOf(".XXX.", "X...X", "X...X", "X...X", "X...X", ".XXX."),
    )
    val colors = "CYGM"
    (0 until 6).map { r ->
        "NEON".mapIndexed { i, letter ->
            glyphs.getValue(letter)[r].map { if (it == 'X') colors[i] else '.' }.joinToString("")
        }.joinToString(".")
    }
}

/** A television set (14x10): the KALDIĞIN YER box. */
internal val TtTv = listOf(
    "....W....W....",
    ".....W..W.....",
    "WWWWWWWWWWWWWW",
    "WBBBBBBBBBBBBW",
    "WBBBYYBBBBBBBW",
    "WBBBYYYYBBBBBW",
    "WBBBYYBBBBBBBW",
    "WBBBBBBBBBBBBW",
    "WWWWWWWWWWWWWW",
    "...WW....WW...",
)

/** A football (11x11): the live match page. */
internal val TtBall = listOf(
    "...WWWWW...",
    ".WWWWBWWWW.",
    "WWWBBBBWWWW",
    "WWBWBBBWBWW",
    "WBBWWBWWBBW",
    "WBBBWWWBBBW",
    "WWBBBWBBBWW",
    "WWWBBBBBWWW",
    ".WWWBBBWWW.",
    "..WWWWWWW..",
    "...WWWWW...",
)

internal val TtSun = listOf(
    "......YYY......",
    ".Y...YYYYY...Y.",
    "..Y.YYYYYYY.Y..",
    "...YYYYYYYYY...",
    "..YYYYYYYYYYY..",
    "Y.YYYYYYYYYYY.Y",
    "Y.YYYYYYYYYYY.Y",
    "..YYYYYYYYYYY..",
    "...YYYYYYYYY...",
    "..Y.YYYYYYY.Y..",
    ".Y...YYYYY...Y.",
    "......YYY......",
)

internal val TtMoon = listOf(
    "....YYYYY......",
    "..YYYYYY.......",
    ".YYYYYY........",
    ".YYYYY.........",
    "YYYYYY.........",
    "YYYYYY.........",
    "YYYYYY.........",
    "YYYYYY.........",
    ".YYYYYYY.......",
    ".YYYYYYYYYY....",
    "..YYYYYYYYY....",
    "....YYYYYY.....",
)

private val CloudBody = listOf(
    "......WWW......",
    "....WWWWWWW....",
    "...WWWWWWWWW...",
    ".WWWWWWWWWWWWW.",
    "WWWWWWWWWWWWWWW",
    "WWWWWWWWWWWWWWW",
    ".WWWWWWWWWWWWW.",
)

private const val Blank15 = "..............."

/** Two empty rows, the cloud, then three rows of weather under it (12 rows in all). */
private fun cloudWith(bottom: List<String>): List<String> = listOf(Blank15, Blank15) + CloudBody + bottom

internal val TtCloud = cloudWith(listOf(Blank15, Blank15, Blank15))

internal val TtRain = cloudWith(listOf(".C..C..C..C..C.", "..C..C..C..C..C", ".C..C..C..C..C."))

internal val TtSnow = cloudWith(listOf("..W...W...W...W", ".W...W...W...W.", "..W...W...W...W"))

internal val TtStorm = cloudWith(listOf("......YYYY.....", ".....YYYY......", "......YY......."))

/** The mosaic that goes with a weather code (Open-Meteo WMO codes); a cloud while the weather is unknown. */
internal fun ttWeatherMosaic(w: WeatherNow?): List<String> {
    if (w == null) return TtCloud
    return when (w.code) {
        0, 1 -> if (w.isDay) TtSun else TtMoon
        in 51..67, in 80..82 -> TtRain
        in 71..77, 85, 86 -> TtSnow
        in 95..99 -> TtStorm
        else -> TtCloud
    }
}

private fun mosaicColor(ch: Char): Color? = when (ch) {
    'R' -> TtRed
    'G' -> TtGreen
    'Y' -> TtYellow
    'B' -> TtBlue
    'M' -> TtMagenta
    'C' -> TtCyan
    'W' -> TtWhite
    'K' -> TtBlack
    else -> null
}

/**
 * A mosaic bitmap: [rows] of cells [cell] square, optionally on a [bg] panel with [pad] around it.
 * Drawn in one pass; the size is fixed by the bitmap, so it never recomposes after the first frame.
 */
@Composable
internal fun TtMosaic(rows: List<String>, cell: Dp, modifier: Modifier = Modifier, bg: Color? = null, pad: Dp = 0.dp) {
    val cols = rows.maxOf { it.length }
    Spacer(
        modifier
            .size(cell * cols + pad * 2, cell * rows.size + pad * 2)
            .drawWithCache {
                val c = cell.toPx()
                val p = pad.toPx()
                onDrawBehind {
                    if (bg != null) drawRect(bg)
                    rows.forEachIndexed { y, row ->
                        row.forEachIndexed { x, ch ->
                            val color = mosaicColor(ch)
                            // +0.5px overlap keeps hairline seams out between neighbouring cells
                            if (color != null) drawRect(color, Offset(p + x * c, p + y * c), Size(c + 0.5f, c + 0.5f))
                        }
                    }
                }
            },
    )
}

/** The Galatasaray red/yellow stripe of the sport page: two staggered rows of 12x10dp cells, one row high. */
@Composable
internal fun TtStripe(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(TtRowH)
            .drawBehind {
                val cw = 12.dp.toPx()
                val ch = 10.dp.toPx()
                val cols = ceil(size.width / cw).toInt()
                for (r in 0..1) {
                    for (c in 0 until cols) {
                        val red = (c + r * 2) % 12 < 6
                        drawRect(if (red) TtRed else TtYellow, Offset(c * cw, r * ch), Size(cw + 0.5f, ch))
                    }
                }
            },
    )
}

// --- CRT overlay ---------------------------------------------------------------------------------

/**
 * Scanlines plus a soft vignette in ONE static overlay: its own graphics layer (so the clock tick
 * never re-records it) and a cached draw (built once per size). Draws only; never takes input.
 */
internal fun Modifier.ttScanlines(): Modifier = this
    .graphicsLayer { clip = false }
    .drawWithCache {
        val period = 2.dp.toPx() // 1px line every 4px on a 1080p panel, as in the concept
        val thick = 0.5.dp.toPx().coerceAtLeast(1f)
        val lines = Path().apply {
            var y = 0f
            while (y < size.height) {
                addRect(Rect(0f, y, size.width, y + thick))
                y += period
            }
        }
        val lineColor = Color.Black.copy(alpha = 0.16f)
        val vignette = Brush.radialGradient(
            0f to Color.Transparent,
            0.62f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.42f),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = hypot(size.width / 2f, size.height / 2f),
        )
        onDrawBehind {
            drawPath(lines, lineColor)
            drawRect(vignette)
        }
    }
