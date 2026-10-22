package dev.mibox.neon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.WatchItem

/**
 * Everything a home screen (NeonHome, GazeteHome, TeletextHome) asks LauncherScreen to do. The
 * overlays themselves live in LauncherScreen and are shared by every theme.
 */
@Immutable
class HomeActions(
    /** Clock/weather chip: opens the Control Center. */
    val onOpenControl: () -> Unit,
    /** WiZ light panel. */
    val onOpenLight: () -> Unit,
    /** Launcher menu (hidden apps, city, theme). Neon: long-press on the gear. */
    val onOpenMenu: () -> Unit,
    /** Network health panel. */
    val onOpenNet: () -> Unit,
    /** Match-day panel. */
    val onOpenMatch: () -> Unit,
    /** Neon Wrapped story. */
    val onOpenWrapped: () -> Unit,
    /** The theme picker. */
    val onOpenThemePicker: () -> Unit,
    /** Long-press on an app (Tüm uygulamalar, pinned app): move/hide/pin/light profile/info. */
    val onAppMenu: (AppEntry) -> Unit,
    /** Long-press on a Watch Next / "Senin için" / pinned content item: pin or unpin. */
    val onContentMenu: (WatchItem) -> Unit,
)

/** One-shot messages from LauncherScreen to the active home (see [HomeActions]); collect them in a LaunchedEffect. */
enum class HomeSignal {
    /** An overlay or dialog closed. If focus got lost, put it back where it was (or on the first element). */
    Restore,

    /** The app that had focus was hidden from its menu: it is about to vanish; re-focus a neighbour. */
    AppHidden,

    /** A pinned item was added or removed from its menu; the Favoriler list changes. */
    PinToggled,
}

/** Near-white or near-black banner colors make a dull glow; fall back to the design's magenta. */
fun tame(c: Color?): Color =
    if (c == null || c.luminance() > 0.8f || c.luminance() < 0.02f) Neon.Magenta else c

/**
 * Keeps the room's WiZ backlight following [app] (the focused or headline app) the way Neon's
 * Backdrop does: its vibrant art color, tamed, goes to [LauncherViewModel.onAccent]. Call it once
 * from every home, with null when no app is in focus.
 */
@Composable
fun AccentFollows(vm: LauncherViewModel, app: AppEntry?) {
    var art by remember { mutableStateOf<AppArt?>(null) }
    LaunchedEffect(app) { art = app?.let { vm.art(it) } }
    val accent = tame(art?.vibrant)
    LaunchedEffect(accent) { vm.onAccent(accent) }
}

/**
 * Optional extras for remotes that have them: the colored program keys and the number keys. The
 * Mi TV Box S remote has none of these, so every function must also be reachable with the D-pad.
 * Only these keys are ever consumed (and only when the matching callback is given); D-pad, OK and
 * Back pass through untouched. Place it on a container: it sees keys bubbling up from its children.
 *
 * [digit] receives 0..9 (top row or keypad).
 */
fun Modifier.onRemoteKeys(
    red: (() -> Unit)? = null,
    green: (() -> Unit)? = null,
    yellow: (() -> Unit)? = null,
    blue: (() -> Unit)? = null,
    digit: ((Int) -> Unit)? = null,
): Modifier = onKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount != 0) return@onKeyEvent false
    val code = e.nativeKeyEvent.keyCode
    val action: (() -> Unit)? = when (code) {
        android.view.KeyEvent.KEYCODE_PROG_RED -> red
        android.view.KeyEvent.KEYCODE_PROG_GREEN -> green
        android.view.KeyEvent.KEYCODE_PROG_YELLOW -> yellow
        android.view.KeyEvent.KEYCODE_PROG_BLUE -> blue
        else -> {
            val d = when (code) {
                in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> code - android.view.KeyEvent.KEYCODE_0
                in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> code - android.view.KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (d >= 0 && digit != null) ({ digit(d) }) else null
        }
    }
    if (action == null) {
        false
    } else {
        action()
        true
    }
}
