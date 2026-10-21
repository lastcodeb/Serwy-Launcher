package dev.mibox.neon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import dev.mibox.neon.R

/** Tokens taken 1:1 from tv-launcher-design/hero-glow.svg. */
object Neon {
    val Bg1 = Color(0xFF15122A)
    val Bg2 = Color(0xFF1C1430)
    val Bg3 = Color(0xFF2C1338)
    val Magenta = Color(0xFFFF2D78)
    val MagentaDeep = Color(0xFFD81E63)
    val Violet = Color(0xFF5B2CFF)
    val Pink = Color(0xFFFF3D6E)
    val PinkLight = Color(0xFFFF6FA0)
    val PinkInk = Color(0xFF7A0D2C)
    val Greeting = Color(0xFFFF9DC0)
    val TitleEnd = Color(0xFFBCD4FF)

    val Text = Color.White
    val TextSoft = Color(0xFFCFD3E6)
    val TextBody = Color(0xFFC7CBE0)
    val TextDim = Color(0xFFB9BFD6)
    val TextMuted = Color(0xFF9AA0C0)
    val Hint = Color(0xFFAAB0CC)
    val Icon = Color(0xFFE7EAF5)
    val WeatherIcon = Color(0xFFCFE0FF)

    val Ok = Color(0xFF4CE0B3)
    val Amber = Color(0xFFFFB454)
    val Danger = Color(0xFFFF3D4F)

    val ChipFill = Color.White.copy(alpha = 0.06f)
    val ChipFillFocused = Color.White.copy(alpha = 0.14f)
    val ChipStroke = Color.White.copy(alpha = 0.18f)
    val Surface = Bg2
    val SurfaceHi = Color(0xFF2A2145)

    val Cta = Brush.horizontalGradient(listOf(PinkLight, Pink))
    val TitleBrush = Brush.verticalGradient(listOf(Color.White, TitleEnd))
    val Gradient = Brush.horizontalGradient(listOf(PinkLight, Pink, Violet))
    val Purple = Violet
}

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
    Font(R.font.poppins_black, FontWeight.Black),
)

/** The design's top bar is set in a plain sans (Arial in the SVG); Roboto is the TV's equivalent. */
val BarText = TextStyle(fontFamily = FontFamily.SansSerif)

/** The SVG is 1920×1080; every dp here is exactly 2 of its px, on any panel resolution. */
const val DESIGN_WIDTH_DP = 960f

@Composable
fun NeonTheme(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val base = LocalDensity.current
    val screenPx = maxOf(config.screenWidthDp, config.screenHeightDp) * base.density
    val t = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Pink,
            secondary = Neon.Violet,
            background = Neon.Bg1,
            onBackground = Neon.Text,
            surface = Neon.Surface,
            onSurface = Neon.Text,
        ),
        typography = Typography(
            displayLarge = t.displayLarge.copy(fontFamily = Poppins),
            displayMedium = t.displayMedium.copy(fontFamily = Poppins),
            displaySmall = t.displaySmall.copy(fontFamily = Poppins),
            headlineLarge = t.headlineLarge.copy(fontFamily = Poppins),
            headlineMedium = t.headlineMedium.copy(fontFamily = Poppins),
            headlineSmall = t.headlineSmall.copy(fontFamily = Poppins),
            titleLarge = t.titleLarge.copy(fontFamily = Poppins),
            titleMedium = t.titleMedium.copy(fontFamily = Poppins),
            titleSmall = t.titleSmall.copy(fontFamily = Poppins),
            bodyLarge = t.bodyLarge.copy(fontFamily = Poppins),
            bodyMedium = t.bodyMedium.copy(fontFamily = Poppins),
            bodySmall = t.bodySmall.copy(fontFamily = Poppins),
            labelLarge = t.labelLarge.copy(fontFamily = Poppins),
            labelMedium = t.labelMedium.copy(fontFamily = Poppins),
            labelSmall = t.labelSmall.copy(fontFamily = Poppins),
        ),
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(screenPx / DESIGN_WIDTH_DP, fontScale = 1f),
            LocalContentColor provides Neon.Text,
            content = content,
        )
    }
}
