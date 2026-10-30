package dev.mibox.neon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import dev.mibox.neon.R
import dev.mibox.neon.data.HomeTheme
import dev.mibox.neon.data.NetVerdict
import dev.mibox.neon.i18n.t

// --- Fonts -------------------------------------------------------------------------------------
// Neon uses Poppins (Theme.kt). Weights not listed here resolve to the nearest listed one.

/** Weights: Bold (700), Black (900); each also as Italic. */
val Playfair = FontFamily(
    Font(R.font.playfair_bold, FontWeight.Bold),
    Font(R.font.playfair_black, FontWeight.Black),
    Font(R.font.playfair_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(R.font.playfair_black_italic, FontWeight.Black, FontStyle.Italic),
)

/** Weights: Normal (400), SemiBold (600); Normal also as Italic. */
val SourceSerif = FontFamily(
    Font(R.font.sourceserif_regular, FontWeight.Normal),
    Font(R.font.sourceserif_semibold, FontWeight.SemiBold),
    Font(R.font.sourceserif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** Weights: Medium (500), SemiBold (600), Bold (700). */
val BarlowCond = FontFamily(
    Font(R.font.barlowcond_medium, FontWeight.Medium),
    Font(R.font.barlowcond_semibold, FontWeight.SemiBold),
    Font(R.font.barlowcond_bold, FontWeight.Bold),
)

/** One weight only (Normal). The pixel grid of the teletext theme. */
val Vt323 = FontFamily(Font(R.font.vt323_regular, FontWeight.Normal))

// Lumen: Manrope, one variable font (subset to Latin + Turkish), weights picked by axis
@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.lumen_manrope, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope 400–800: body, labels and titles of the Lumen theme. */
val LumenSans = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Lumen's titles and clock: the same family, used at 700–800. */
val LumenDisplay = LumenSans

// --- Palettes ----------------------------------------------------------------------------------

/** Gazete colors from the approved concept. Day = paper, night = ink. */
object GazettePalette {
    val DayPaper = Color(0xFFEFE6D2)
    val DayInk = Color(0xFF16120E)
    val DayRed = Color(0xFFB3201A)
    val DayMuted = Color(0xFF5C5245)

    val NightPaper = Color(0xFF14110E) // the background: dark "paper"
    val NightInk = Color(0xFFEDE3CC) // the text
    val NightRed = Color(0xFFFF6A5C) // red text and rules
    val NightBand = Color(0xFFC8261D) // red filled bands (text on them: NightInk)
    val NightMuted = Color(0xFFB8AC92)
}

/** The eight pure teletext colors. */
object TeletextPalette {
    val Black = Color(0xFF000000)
    val Red = Color(0xFFFF0000)
    val Green = Color(0xFF00FF00)
    val Yellow = Color(0xFFFFFF00)
    val Blue = Color(0xFF0000FF)
    val Magenta = Color(0xFFFF00FF)
    val Cyan = Color(0xFF00FFFF)
    val White = Color(0xFFFFFFFF)
}

// --- Tokens ------------------------------------------------------------------------------------

/** How a focused element announces itself. */
enum class FocusStyle {
    /** Neon: brighter fill, white border, soft glow and a small scale-up. */
    GLOW,

    /** Gazete: a red hand-drawn loop around the element (see [penCircle]). No scale, no fill change. */
    PEN_CIRCLE,

    /** Teletext: inverse video, the element's fill becomes [HomeStyle.focus] and its text [HomeStyle.onFocus]. */
    INVERSE,
}

/** Which family a piece of text is set in. */
enum class TextRole { DISPLAY, HEADLINE, BODY, LABEL }

/**
 * Everything a home screen and the shared overlays need to look like one theme. One immutable
 * instance per theme (two for Gazete: day and night), provided as [LocalHomeStyle] at the root of
 * LauncherScreen. NEON's values are exactly the ones the launcher always had (Theme.kt).
 *
 * Text styles leave color unspecified on purpose: the color comes from LocalContentColor, which
 * the focusable surfaces switch on focus (that is how inverse video works). Set a color only on
 * purpose.
 */
@Immutable
class HomeStyle(
    val theme: HomeTheme,
    /** True for the Gazete night edition; always false for the other themes. */
    val night: Boolean,

    // Colors
    val background: Color,
    /** Panels and dialogs. */
    val surface: Color,
    /** Text fields and secondary fills. */
    val surfaceHi: Color,
    val panelBorder: Color,
    val text: Color,
    val textSoft: Color,
    val textBody: Color,
    val textDim: Color,
    val textMuted: Color,
    val accent: Color,
    /** Filled bands and emphasized buttons; text on them is [onAccent]. */
    val accent2: Color,
    val onAccent: Color,
    /** Small kicker/eyebrow lines above titles. */
    val eyebrow: Color,
    /** Neon: unused. Gazete: the pen color. Teletext: the inverse-video fill. */
    val focus: Color,
    /** Content color on a [focus] fill (INVERSE only). */
    val onFocus: Color,
    val divider: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val chipFill: Color,
    val chipFillFocused: Color,
    val chipStroke: Color,
    /** Empty part of a bar. */
    val track: Color,
    val icon: Color,
    val weatherIcon: Color,
    /** Stand-in behind an image that has not loaded. */
    val placeholder: Color,
    /** Flat color of panel titles where Neon has a gradient. */
    val titleColor: Color,
    /** Neon's gradient fills; null elsewhere (use [titleColor] / [accent]). */
    val titleBrush: Brush?,
    val accentBrush: Brush?,
    val ctaBrush: Brush?,
    /** Filled bars (brightness, shares): a gradient on Neon, [accent] elsewhere. Never null. */
    val barBrush: Brush,

    // Typography
    /** Big numbers and hero lines. */
    val display: TextStyle,
    /** Panel and card titles. */
    val headline: TextStyle,
    val body: TextStyle,
    /** Small section labels. */
    val label: TextStyle,
    /** Menu rows. */
    val item: TextStyle,
    val displayFont: FontFamily,
    val headlineFont: FontFamily,
    val bodyFont: FontFamily,
    val labelFont: FontFamily,
    /** Multiplier for sizes up to 24sp (VT323 reads small). */
    val fontScale: Float,
    val labelTracking: TextUnit,
    val displayTracking: TextUnit,

    // Shapes and knobs
    val panelRadius: Dp,
    /** Border width of panels and dialogs. */
    val borderWidth: Dp,
    /** Border width of chips at rest. */
    val chipBorderWidth: Dp,
    /** Pill on Neon, rectangle elsewhere. */
    val chipShape: Shape,
    val focusStyle: FocusStyle,
    val uppercaseLabels: Boolean,
    /** Emoji, arrows, triangles, stars, check marks usable? False where the fonts lack them: draw shapes instead. */
    val symbols: Boolean,
    /** Gazete panels get a second thin rule inside the border. */
    val doubleRule: Boolean,
) {
    val panelShape: Shape = if (panelRadius == 0.dp) RectangleShape else RoundedCornerShape(panelRadius)

    /** Neon, Glass, Lumen and Aura: rounded pills, scale on focus, symbols in the system font. */
    val modern: Boolean get() = theme == HomeTheme.NEON || theme == HomeTheme.GLASS || theme == HomeTheme.LUMEN || theme == HomeTheme.AURA

    /** [neonRadius] on Neon, square corners elsewhere. */
    fun shapeOf(neonRadius: Dp): Shape = if (panelRadius == 0.dp) RectangleShape else RoundedCornerShape(neonRadius)

    /** A style for [size] in the family of [role]. Neon: exactly `TextStyle(Poppins, size, weight, color)`. */
    fun type(
        size: TextUnit,
        weight: FontWeight? = null,
        color: Color = Color.Unspecified,
        role: TextRole = TextRole.BODY,
    ): TextStyle {
        val family = when (role) {
            TextRole.DISPLAY -> displayFont
            TextRole.HEADLINE -> headlineFont
            TextRole.BODY -> bodyFont
            TextRole.LABEL -> labelFont
        }
        return TextStyle(
            fontFamily = family,
            fontSize = scaled(size),
            fontWeight = weight,
            color = color,
            letterSpacing = if (role == TextRole.LABEL) labelTracking else TextUnit.Unspecified,
        )
    }

    /** Top-bar / chip text: Neon keeps its plain sans, the others use their label family. */
    fun bar(size: TextUnit, weight: FontWeight? = null, color: Color = Color.Unspecified): TextStyle =
        if (theme == HomeTheme.NEON) {
            BarText.copy(fontSize = size, fontWeight = weight, color = color)
        } else {
            type(size, weight, color, TextRole.LABEL)
        }

    private fun scaled(size: TextUnit): TextUnit =
        if (fontScale == 1f || size.value > 24f) size else (size.value * fontScale).sp

    /** Upper-cases labels (Turkish rules: i becomes İ) where the theme asks for it. */
    fun caps(s: String): String = if (uppercaseLabels) s.uppercase(TR) else s

    /**
     * Menu rows and titles are written "🙈   Gizle" / "💡  Arka ışık": where there are no symbols
     * the symbol and its gap are dropped.
     */
    fun plain(s: String): String = if (symbols) s else s.replaceFirst(SymbolGap, "")

    /**
     * Outside Neon: removes emoji and pictographs (the themed fonts cannot show them), collapses
     * runs of whitespace and trims. Turkish letters and ordinary punctuation are kept. Neon gets
     * [s] back untouched. One pass over the code points, no regex.
     */
    fun clean(s: String): String {
        if (symbols) return s
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (isPictograph(cp)) continue
            if (Character.isWhitespace(cp) || cp == 0xA0) {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) sb.append(' ')
                pendingSpace = false
                sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    /** "✓ " in front of the chosen entry, "" otherwise. [spaces] only matters on Neon. */
    fun mark(on: Boolean, spaces: Int = 1): String = when {
        !on -> ""
        theme == HomeTheme.NEON || theme == HomeTheme.GLASS || theme == HomeTheme.LUMEN || theme == HomeTheme.AURA -> "✓" + " ".repeat(spaces)
        theme == HomeTheme.GAZETE -> "• "
        else -> "* "
    }

    /** Star in front of a favorite. */
    val star: String get() = if (symbols) "★ " else "* "

    /** Color of a connection verdict. */
    fun verdict(v: NetVerdict): Color = when (v) {
        NetVerdict.UHD_OK -> ok
        NetVerdict.HD_OK -> warn
        NetVerdict.WEAK -> danger
        NetVerdict.UNKNOWN -> textMuted
    }

    private companion object {
        val SymbolGap = Regex("\\S+ {2,}")

        fun isPictograph(cp: Int): Boolean = when {
            cp in 0x1F000..0x1FAFF -> true // emoji, skin tones 1F3FB-FF, regional indicators 1F1E6-FF
            cp in 0x2600..0x27BF -> true // misc symbols, dingbats
            cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 -> true // selectors, ZWJ, keycap
            cp in 0x2B05..0x2B07 || cp == 0x2B1B || cp == 0x2B1C || cp == 0x2B50 || cp == 0x2B55 -> true
            cp == 0x231A || cp == 0x231B || cp in 0x23E9..0x23FA -> true // watch, hourglass, media keys
            cp == 0x2934 || cp == 0x2935 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 -> true
            else -> false
        }
    }
}

/** Neon's gradient fill when [brush] is set, otherwise the flat [flat] color. */
fun TextStyle.fillWith(brush: Brush?, flat: Color): TextStyle =
    if (brush != null) copy(brush = brush) else copy(color = flat)

/** Clip + fill + border of a dialog or panel; [neonRadius] is the corner radius on Neon. */
fun Modifier.panel(st: HomeStyle, neonRadius: Dp = 28.dp): Modifier {
    val shape = st.shapeOf(neonRadius)
    val base = this
        .clip(shape)
        .background(st.surface)
        .border(st.borderWidth, st.panelBorder, shape)
    return if (!st.doubleRule) {
        base
    } else {
        base.drawBehind {
            // Newspaper box: a hairline rule inside the heavy one
            val inset = (st.borderWidth + 3.dp).toPx()
            drawRect(
                st.panelBorder,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}

/**
 * Gazete's focus mark: a red loop drawn around the element a little outside its bounds, open at
 * the end like a pen stroke. [focused] is read in the draw phase only, so focus changes redraw but
 * never recompose. The path is built once per size.
 */
fun Modifier.penCircle(focused: State<Boolean>, color: Color, strokeWidth: Dp = 2.5.dp): Modifier =
    drawWithCache {
        val ox = 12.dp.toPx()
        val oy = 7.dp.toPx()
        val w = size.width + 2 * ox
        val h = size.height + 2 * oy
        // The concept's SVG loop (viewBox 100x100, stretched), mapped onto the element plus margin
        fun px(x: Float) = -ox + x / 100f * w
        fun py(y: Float) = -oy + y / 100f * h
        val path = Path().apply {
            moveTo(px(14f), py(30f))
            cubicTo(px(28f), py(8f), px(76f), py(4f), px(94f), py(26f))
            cubicTo(px(106f), py(44f), px(92f), py(84f), px(62f), py(92f))
            cubicTo(px(32f), py(100f), px(6f), py(84f), px(5f), py(56f))
            cubicTo(px(4f), py(40f), px(10f), py(30f), px(20f), py(20f))
            lineTo(px(27f), py(13f))
        }
        val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawWithContent {
            drawContent()
            if (focused.value) drawPath(path, color, style = stroke)
        }
    }

val LocalHomeStyle = staticCompositionLocalOf<HomeStyle> { error("LocalHomeStyle is provided by LauncherScreen") }

/** The style for [theme]; [gazeteNight] only matters for Gazete. Rebuilt only when one of them changes. */
@Composable
fun rememberHomeStyle(theme: HomeTheme, gazeteNight: Boolean): HomeStyle {
    val typography = MaterialTheme.typography
    return remember(theme, gazeteNight) {
        when (theme) {
            HomeTheme.NEON -> neonStyle(typography)
            HomeTheme.GAZETE -> gazeteStyle(gazeteNight)
            HomeTheme.TELETEXT -> teletextStyle()
            HomeTheme.GLASS -> glassStyle()
            HomeTheme.LUMEN -> lumenStyle()
            HomeTheme.AURA -> auraStyle()
        }
    }
}

// --- The five styles --------------------------------------------------------------------------

/** Today's launcher, token for token. */
internal fun neonStyle(t: Typography): HomeStyle = HomeStyle(
    theme = HomeTheme.NEON,
    night = false,
    background = Neon.Bg1,
    surface = Neon.Surface,
    surfaceHi = Neon.SurfaceHi,
    panelBorder = Neon.ChipStroke,
    text = Neon.Text,
    textSoft = Neon.TextSoft,
    textBody = Neon.TextBody,
    textDim = Neon.TextDim,
    textMuted = Neon.TextMuted,
    accent = Neon.Pink,
    accent2 = Neon.Violet,
    onAccent = Color.White,
    eyebrow = Neon.Greeting,
    focus = Color.White,
    onFocus = Color.White,
    divider = Color.White.copy(alpha = 0.15f),
    ok = Neon.Ok,
    warn = Neon.Amber,
    danger = Neon.Danger,
    chipFill = Neon.ChipFill,
    chipFillFocused = Neon.ChipFillFocused,
    chipStroke = Neon.ChipStroke,
    track = Color.White.copy(alpha = 0.1f),
    icon = Neon.Icon,
    weatherIcon = Neon.WeatherIcon,
    placeholder = Neon.Bg3,
    titleColor = Neon.Text,
    titleBrush = Neon.TitleBrush,
    accentBrush = Neon.Gradient,
    ctaBrush = Neon.Cta,
    barBrush = Neon.Gradient,
    display = TextStyle(fontFamily = Poppins, fontSize = 75.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headline = t.headlineSmall,
    body = t.bodyMedium,
    label = t.titleSmall,
    item = t.titleMedium,
    displayFont = Poppins,
    headlineFont = Poppins,
    bodyFont = Poppins,
    labelFont = Poppins,
    fontScale = 1f,
    labelTracking = TextUnit.Unspecified,
    displayTracking = (-1).sp,
    panelRadius = 28.dp,
    borderWidth = 1.dp,
    chipBorderWidth = 0.75.dp,
    chipShape = Pill,
    focusStyle = FocusStyle.GLOW,
    uppercaseLabels = false,
    symbols = true,
    doubleRule = false,
)

/** Newspaper: paper and ink, one red, ruled boxes, serif text, condensed caps labels. */
internal fun gazeteStyle(night: Boolean): HomeStyle {
    val g = GazettePalette
    val paper = if (night) g.NightPaper else g.DayPaper
    val ink = if (night) g.NightInk else g.DayInk
    val red = if (night) g.NightRed else g.DayRed
    val band = if (night) g.NightBand else g.DayRed
    val muted = if (night) g.NightMuted else g.DayMuted
    return HomeStyle(
        theme = HomeTheme.GAZETE,
        night = night,
        background = paper,
        surface = if (night) Color(0xFF1C1814) else Color(0xFFF4ECD9),
        surfaceHi = if (night) Color(0xFF26201A) else Color(0xFFE6DCC4),
        panelBorder = ink,
        text = ink,
        textSoft = ink,
        textBody = ink,
        textDim = muted,
        textMuted = muted,
        accent = red,
        accent2 = band,
        onAccent = if (night) g.NightInk else g.DayPaper,
        eyebrow = red,
        focus = red,
        onFocus = ink,
        divider = ink,
        ok = if (night) Color(0xFF7BC98C) else Color(0xFF2F6B3A),
        warn = if (night) Color(0xFFE0A94A) else Color(0xFFA86A00),
        danger = red,
        chipFill = Color.Transparent,
        chipFillFocused = Color.Transparent,
        chipStroke = ink,
        track = ink.copy(alpha = 0.15f),
        icon = ink,
        weatherIcon = ink,
        placeholder = if (night) Color(0xFF2A241E) else Color(0xFFD9CDB1),
        titleColor = ink,
        titleBrush = null,
        accentBrush = null,
        ctaBrush = null,
        barBrush = SolidColor(red),
        display = TextStyle(fontFamily = Playfair, fontSize = 74.sp, fontWeight = FontWeight.Black),
        headline = TextStyle(fontFamily = Playfair, fontSize = 26.sp, fontWeight = FontWeight.Bold),
        body = TextStyle(fontFamily = SourceSerif, fontSize = 14.sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = BarlowCond, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
        item = TextStyle(fontFamily = SourceSerif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        displayFont = Playfair,
        headlineFont = Playfair,
        bodyFont = SourceSerif,
        labelFont = BarlowCond,
        fontScale = 1f,
        labelTracking = 1.4.sp,
        displayTracking = TextUnit.Unspecified,
        panelRadius = 0.dp,
        borderWidth = 2.dp,
        chipBorderWidth = 1.dp,
        chipShape = RectangleShape,
        focusStyle = FocusStyle.PEN_CIRCLE,
        uppercaseLabels = true,
        symbols = false,
        doubleRule = true,
    )
}

/** 1990s teletext: pure colors on black, VT323 everywhere, hard edges, inverse-video focus. */
internal fun teletextStyle(): HomeStyle {
    val t = TeletextPalette
    return HomeStyle(
        theme = HomeTheme.TELETEXT,
        night = false,
        background = t.Black,
        surface = t.Black,
        surfaceHi = t.Blue,
        panelBorder = t.White,
        text = t.White,
        textSoft = t.White,
        textBody = t.White,
        textDim = t.Cyan,
        textMuted = t.Cyan,
        accent = t.Yellow,
        accent2 = t.Blue,
        onAccent = t.White,
        eyebrow = t.Yellow,
        focus = t.White,
        onFocus = t.Black,
        divider = t.White,
        ok = t.Green,
        warn = t.Yellow,
        danger = t.Red,
        chipFill = Color.Transparent,
        chipFillFocused = t.White,
        chipStroke = t.White,
        track = t.Blue,
        icon = t.White,
        weatherIcon = t.Cyan,
        placeholder = t.Blue,
        titleColor = t.Yellow,
        titleBrush = null,
        accentBrush = null,
        ctaBrush = null,
        barBrush = SolidColor(t.Yellow),
        display = TextStyle(fontFamily = Vt323, fontSize = 40.sp, fontWeight = FontWeight.Normal),
        headline = TextStyle(fontFamily = Vt323, fontSize = 28.sp, fontWeight = FontWeight.Normal),
        body = TextStyle(fontFamily = Vt323, fontSize = 20.sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = Vt323, fontSize = 20.sp, fontWeight = FontWeight.Normal),
        item = TextStyle(fontFamily = Vt323, fontSize = 22.sp, fontWeight = FontWeight.Normal),
        displayFont = Vt323,
        headlineFont = Vt323,
        bodyFont = Vt323,
        labelFont = Vt323,
        fontScale = 1.45f,
        labelTracking = TextUnit.Unspecified,
        displayTracking = TextUnit.Unspecified,
        panelRadius = 0.dp,
        borderWidth = 2.dp,
        chipBorderWidth = 2.dp,
        chipShape = RectangleShape,
        focusStyle = FocusStyle.INVERSE,
        uppercaseLabels = true,
        symbols = false,
        doubleRule = false,
    )
}

/** Apple TV-like glass: system sans, white on deep color, frosted panels, white focus lift. */
object GlassPalette {
    val Ink = Color(0xFF0B0D12)
    val Blue = Color(0xFF0A84FF)
    val OnWhite = Color(0xFF111114)

    /** Frosted fill of chips and the shelf; the backdrop behind it is already blurred. */
    val Frost = Color.White.copy(alpha = 0.12f)
    val Rim = Color.White.copy(alpha = 0.22f)
}

internal fun glassStyle(): HomeStyle {
    val g = GlassPalette
    val sans = FontFamily.SansSerif
    return HomeStyle(
        theme = HomeTheme.GLASS,
        night = false,
        background = g.Ink,
        // Dialogs float over the home without a blur behind them: nearly opaque smoked glass
        surface = Color(0xF21C1D24),
        surfaceHi = Color.White.copy(alpha = 0.14f),
        panelBorder = Color.White.copy(alpha = 0.16f),
        text = Color.White,
        textSoft = Color.White.copy(alpha = 0.92f),
        textBody = Color.White.copy(alpha = 0.86f),
        textDim = Color.White.copy(alpha = 0.62f),
        textMuted = Color.White.copy(alpha = 0.45f),
        accent = g.Blue,
        accent2 = g.Blue,
        onAccent = Color.White,
        eyebrow = Color.White.copy(alpha = 0.62f),
        focus = Color.White,
        onFocus = g.OnWhite,
        divider = Color.White.copy(alpha = 0.12f),
        ok = Color(0xFF30D158),
        warn = Color(0xFFFFD60A),
        danger = Color(0xFFFF453A),
        chipFill = g.Frost,
        chipFillFocused = Color.White,
        chipStroke = g.Rim,
        track = Color.White.copy(alpha = 0.16f),
        icon = Color.White.copy(alpha = 0.9f),
        weatherIcon = Color.White,
        placeholder = Color.White.copy(alpha = 0.08f),
        titleColor = Color.White,
        titleBrush = null,
        accentBrush = null,
        ctaBrush = null,
        barBrush = SolidColor(Color.White),
        display = TextStyle(fontFamily = sans, fontSize = 60.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp),
        headline = TextStyle(fontFamily = sans, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        body = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = sans, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
        item = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        displayFont = sans,
        headlineFont = sans,
        bodyFont = sans,
        labelFont = sans,
        fontScale = 1f,
        labelTracking = TextUnit.Unspecified,
        displayTracking = (-1.2).sp,
        panelRadius = 26.dp,
        borderWidth = 1.dp,
        chipBorderWidth = 0.75.dp,
        chipShape = Pill,
        focusStyle = FocusStyle.INVERSE,
        uppercaseLabels = false,
        symbols = true,
        doubleRule = false,
    )
}

/** Lumen: calm warm-dark ground, one amber accent (the room's light); focus lifts like Glass. */
object LumenPalette {
    val Ground = Color(0xFF0D0F14)
    val Surface = Color(0xFF14171E)
    val Raised = Color(0xFF1A1D25)
    val Text = Color(0xFFF3F0E8)
    val Dim = Color(0xFFB4B8C2)
    val Muted = Color(0xFF8A8F9B)
    val Faint = Color(0xFF6E7380)
    val Amber = Color(0xFFFFB547)
    val Ok = Color(0xFF9BE38A)
    val Hairline = Color.White.copy(alpha = 0.10f)
}

internal fun lumenStyle(): HomeStyle {
    val l = LumenPalette
    return HomeStyle(
        theme = HomeTheme.LUMEN,
        night = false,
        background = l.Ground,
        surface = l.Surface,
        surfaceHi = l.Raised,
        panelBorder = l.Hairline,
        text = l.Text,
        textSoft = l.Text,
        textBody = Color(0xFFD9D6CE),
        textDim = l.Dim,
        textMuted = l.Muted,
        accent = l.Amber,
        accent2 = l.Amber,
        onAccent = l.Ground,
        eyebrow = l.Dim,
        focus = l.Text,
        onFocus = l.Ground,
        divider = Color.White.copy(alpha = 0.08f),
        ok = l.Ok,
        warn = l.Amber,
        danger = Color(0xFFFF6B6B),
        chipFill = l.Raised,
        chipFillFocused = l.Text,
        chipStroke = Color.White.copy(alpha = 0.14f),
        track = Color.White.copy(alpha = 0.14f),
        icon = l.Text,
        weatherIcon = l.Dim,
        placeholder = Color(0xFF171A21),
        titleColor = l.Text,
        titleBrush = null,
        accentBrush = null,
        ctaBrush = null,
        barBrush = SolidColor(l.Amber),
        display = TextStyle(fontFamily = LumenDisplay, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
        headline = TextStyle(fontFamily = LumenDisplay, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        body = TextStyle(fontFamily = LumenSans, fontSize = 14.sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = LumenSans, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
        item = TextStyle(fontFamily = LumenSans, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        displayFont = LumenDisplay,
        headlineFont = LumenDisplay,
        bodyFont = LumenSans,
        labelFont = LumenSans,
        fontScale = 1f,
        labelTracking = TextUnit.Unspecified,
        displayTracking = (-1.5).sp,
        panelRadius = 24.dp,
        borderWidth = 1.dp,
        chipBorderWidth = 0.75.dp,
        chipShape = Pill,
        focusStyle = FocusStyle.INVERSE,
        uppercaseLabels = false,
        symbols = true,
        doubleRule = false,
    )
}

/**
 * Aura: near-black night sky, white type in Manrope, frosted chips; the color comes from the
 * content itself (the focused card's glow, the screen's light, the room's lamp). The violet
 * and magenta are only the fallback aura, a nod to Neon.
 */
object AuraPalette {
    val Ground = Color(0xFF07080D)
    val Surface = Color(0xFF12141C)
    val Raised = Color(0xFF1B1E29)
    val Text = Color(0xFFFFFFFF)
    val Soft = Color.White.copy(alpha = 0.86f)
    val Dim = Color.White.copy(alpha = 0.64f)
    val Muted = Color.White.copy(alpha = 0.44f)
    val Violet = Color(0xFF8B7BFF)
    val Magenta = Color(0xFFFF5BD6)

    /** The aura before any art has a color. */
    val Glow = Color(0xFF7C5CFF)

    /** Card fill at rest, before the art arrives. */
    val Card = Color.White.copy(alpha = 0.07f)
    val Frost = Color.White.copy(alpha = 0.08f)
    val Rim = Color.White.copy(alpha = 0.16f)
}

internal fun auraStyle(): HomeStyle {
    val a = AuraPalette
    return HomeStyle(
        theme = HomeTheme.AURA,
        night = false,
        background = a.Ground,
        surface = a.Surface,
        surfaceHi = a.Raised,
        panelBorder = a.Rim,
        text = a.Text,
        textSoft = a.Soft,
        textBody = Color.White.copy(alpha = 0.8f),
        textDim = a.Dim,
        textMuted = a.Muted,
        accent = a.Violet,
        accent2 = a.Violet,
        onAccent = Color.White,
        eyebrow = a.Dim,
        focus = Color.White,
        onFocus = Color(0xFF0B0C12),
        divider = Color.White.copy(alpha = 0.10f),
        ok = Color(0xFF5BE3A0),
        warn = Color(0xFFFFC44D),
        danger = Color(0xFFFF5C6C),
        chipFill = a.Frost,
        chipFillFocused = Color.White,
        chipStroke = a.Rim,
        track = Color.White.copy(alpha = 0.14f),
        icon = Color.White.copy(alpha = 0.9f),
        weatherIcon = Color.White,
        placeholder = a.Card,
        titleColor = a.Text,
        titleBrush = null,
        accentBrush = null,
        ctaBrush = null,
        barBrush = Brush.horizontalGradient(listOf(a.Violet, a.Magenta)),
        display = TextStyle(fontFamily = LumenDisplay, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
        headline = TextStyle(fontFamily = LumenDisplay, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
        body = TextStyle(fontFamily = LumenSans, fontSize = 14.sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = LumenSans, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
        item = TextStyle(fontFamily = LumenSans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        displayFont = LumenDisplay,
        headlineFont = LumenDisplay,
        bodyFont = LumenSans,
        labelFont = LumenSans,
        fontScale = 1f,
        labelTracking = TextUnit.Unspecified,
        displayTracking = (-1.5).sp,
        panelRadius = 26.dp,
        borderWidth = 1.dp,
        chipBorderWidth = 0.75.dp,
        chipShape = Pill,
        focusStyle = FocusStyle.INVERSE,
        uppercaseLabels = false,
        symbols = true,
        doubleRule = false,
    )
}
