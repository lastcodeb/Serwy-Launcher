package dev.mibox.neon.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.mibox.neon.data.AppEntry

/** What the hero shows: the focused app or episode, the last used app, or a favorite slide. */
@Immutable
data class HeroContent(
    val greeting: String?,
    val title: String,
    val subtitle: String?,
    val app: AppEntry,
)

/**
 * A pure display: greeting y358 → 179dp, title y500 → 250dp, optional subtitle y552 → 276dp
 * (px / 2 from the SVG). Nothing here takes focus; OK on a card opens it. The parent sits at the
 * top of the screen, so these are screen coordinates.
 */
@Composable
fun Hero(content: HeroContent) {
    val now by rememberNow()
    Box {
        Text(
            "— ${content.greeting ?: greeting(now.hour)}",
            modifier = Modifier.baselineAt(179.dp).offset(x = 1.dp),
            style = TextStyle(fontFamily = Poppins, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Neon.Greeting),
        )
        // Sequential fade: the old title is gone before the new one appears, so two huge
        // words never overlap
        AnimatedContent(
            targetState = content,
            transitionSpec = { (fadeIn(tween(200, delayMillis = 80)) togetherWith fadeOut(tween(80))).using(null) },
            label = "hero",
        ) { c ->
            Box {
                Text(
                    c.title,
                    modifier = Modifier.baselineAt(250.dp).offset(x = (-2).dp).widthIn(max = 620.dp),
                    style = TextStyle(
                        fontFamily = Poppins,
                        fontSize = if (c.title.length > 14) 56.sp else 75.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp,
                        brush = Neon.TitleBrush,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                c.subtitle?.let {
                    Text(
                        it,
                        modifier = Modifier.baselineAt(276.dp).widthIn(max = 620.dp),
                        style = TextStyle(fontFamily = Poppins, fontSize = 16.sp, color = Neon.TextBody),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Günaydın"
    in 12..17 -> "İyi günler"
    in 18..21 -> "İyi akşamlar"
    else -> "İyi geceler"
}
