package dev.mibox.neon.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mibox.neon.data.HomeTheme
import kotlinx.coroutines.delay

val Pill = RoundedCornerShape(50)

/**
 * Every focusable thing: the SVG's glass chip (white 6% fill, 18% hairline) that on focus
 * brightens, takes a solid white border and a soft glow.
 */
@Composable
fun NeonSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape? = null,
    glowColor: Color = Color.White,
    idleGlow: Glow = Glow.None,
    focusedScale: Float = 1.05f,
    container: Color? = null,
    focusedContainer: Color? = null,
    stroke: Color? = null,
    strokeWidth: Dp? = null,
    brush: Brush? = null,
    emphasis: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // Skinned by the active home theme. Null color/shape/width parameters mean "the theme's
    // default"; the Neon branch of every expression below is exactly the original look.
    // [brush] and [shape] are Neon-only; [emphasis] (primary button) is for the other themes
    // (Neon expresses it with a brush). One Surface call for all themes, so a theme switch
    // keeps the node, and with it the focus.
    val st = LocalHomeStyle.current
    val neon = st.theme == HomeTheme.NEON
    val glass = st.theme == HomeTheme.GLASS
    val lumen = st.theme == HomeTheme.LUMEN
    // Glass, Lumen and Aura: a focused button turns white and lifts on a shadow
    val lifted = glass || lumen || st.theme == HomeTheme.AURA
    val src = interactionSource ?: remember { MutableInteractionSource() }
    val s: Shape = if (st.modern) shape ?: Pill else RectangleShape
    val inverse = st.focusStyle == FocusStyle.INVERSE

    val idleFill = container ?: if (emphasis && !neon) st.accent2 else st.chipFill
    val focusFill = focusedContainer ?: when {
        emphasis && !neon -> if (inverse) st.focus else st.accent2
        else -> st.chipFillFocused
    }
    val idleContent = if (neon) Neon.Text else if (emphasis) st.onAccent else st.text
    // Glass turns a focused button white with dark text, except where the caller keeps its own
    // fill (team color, light color): there the text stays light
    val focusContent = when {
        neon -> Color.White
        lifted && focusedContainer != null -> idleContent
        inverse -> st.onFocus
        else -> idleContent
    }

    val width = strokeWidth ?: st.chipBorderWidth
    val idleStroke = BorderStroke(width, stroke ?: st.chipStroke)
    val focusStroke = when {
        neon -> BorderStroke(1.5.dp, Color.White.copy(alpha = 0.85f))
        inverse -> BorderStroke(width, st.focus)
        else -> idleStroke
    }

    // Gazete: the red loop is drawn from the focus state in the draw phase
    val pen = st.focusStyle == FocusStyle.PEN_CIRCLE
    val penFocused = if (pen) src.collectIsFocusedAsState() else null
    val m = if (penFocused != null) {
        modifier.penCircle(penFocused, if (emphasis) st.text else st.focus)
    } else {
        modifier
    }

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = m,
        shape = ClickableSurfaceDefaults.shape(s),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = idleFill,
            contentColor = idleContent,
            focusedContainerColor = focusFill,
            focusedContentColor = focusContent,
            pressedContainerColor = focusFill,
            pressedContentColor = focusContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (st.modern) focusedScale else 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(idleStroke, shape = s),
            focusedBorder = Border(focusStroke, shape = s),
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = if (neon) idleGlow else Glow.None,
            focusedGlow = when {
                neon -> Glow(glowColor.copy(alpha = 0.45f), 12.dp)
                // Glass, Lumen and Aura: the lifted white button casts a soft shadow instead of a glow
                lifted -> Glow(Color.Black.copy(alpha = 0.4f), 12.dp)
                else -> Glow.None
            },
        ),
        interactionSource = src,
    ) {
        if (brush != null) Box(Modifier.matchParentSize().background(brush))
        content()
    }
}

/** 35dp glass pill with 13sp semibold text, as in the top bar of the design. */
@Composable
fun Chip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: String? = null,
    primary: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val st = LocalHomeStyle.current
    NeonSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(35.dp),
        brush = if (primary) st.ctaBrush else null,
        stroke = if (primary) Color.Transparent else null,
        glowColor = if (primary) st.accent else Color.White,
        emphasis = primary,
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (st.theme == HomeTheme.NEON) Neon.Icon else LocalContentColor.current,
                )
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            // Emoji only where the theme can show them; a lone "−" / "+" is the button itself
            if (leading != null && (st.symbols || text.isEmpty())) {
                Text(leading, style = st.bar(14.sp))
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) {
                Text(st.caps(text), style = st.bar(13.sp, FontWeight.SemiBold), maxLines = 1)
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/**
 * For dialogs opened by a long press: the OK that opened them is still held. Its auto-repeats
 * and its release would otherwise "click" whatever item has focus first (Taşı, Gizle…), so OK
 * events only count after a fresh press inside the dialog.
 */
@Composable
fun Modifier.ignoreOpeningPress(): Modifier {
    val sawPress = remember { mutableStateOf(false) }
    return onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        when {
            !ok || sawPress.value -> false
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                sawPress.value = true
                false
            }
            else -> true // repeat or release of the press that opened the dialog
        }
    }
}

/** Dialog windows take focus a frame late; ask until it sticks. */
@Composable
fun FocusSoon(requester: FocusRequester) {
    LaunchedEffect(requester) {
        repeat(5) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Places a text so its first baseline lands at [y] from the parent's top, like an SVG <text y>. */
fun Modifier.baselineAt(y: Dp) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val base = p[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: p.height
    layout(p.width, 0) { p.place(0, y.roundToPx() - base) }
}
