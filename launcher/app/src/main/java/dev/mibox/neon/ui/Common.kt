package dev.mibox.neon.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
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
    shape: Shape = Pill,
    glowColor: Color = Color.White,
    idleGlow: Glow = Glow.None,
    focusedScale: Float = 1.05f,
    container: Color = Neon.ChipFill,
    focusedContainer: Color = Neon.ChipFillFocused,
    stroke: Color = Neon.ChipStroke,
    strokeWidth: Dp = 0.75.dp,
    brush: Brush? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = Neon.Text,
            focusedContainerColor = focusedContainer,
            focusedContentColor = Color.White,
            pressedContainerColor = focusedContainer,
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(strokeWidth, stroke), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.85f)), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = idleGlow,
            focusedGlow = Glow(glowColor.copy(alpha = 0.45f), 12.dp),
        ),
        interactionSource = interactionSource,
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
    NeonSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(35.dp),
        brush = if (primary) Neon.Cta else null,
        stroke = if (primary) Color.Transparent else Neon.ChipStroke,
        glowColor = if (primary) Neon.Pink else Color.White,
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = Neon.Icon)
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (leading != null) {
                Text(leading, style = BarText.copy(fontSize = 14.sp))
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) {
                Text(text, style = BarText.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
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
