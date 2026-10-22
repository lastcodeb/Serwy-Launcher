package dev.mibox.neon.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.LocalContentColor
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.HomeTheme
import dev.mibox.neon.data.PinResult
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.ui.gazete.GazeteHome
import dev.mibox.neon.ui.teletext.TeletextHome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import dev.mibox.neon.i18n.t

/**
 * The shell shared by every home theme: toasts, the lifecycle and polling block, the overlays
 * (menus, panels, Wrapped, the theme picker) and the style. The themed home itself is drawn
 * underneath: [NeonHome], [GazeteHome] or [TeletextHome], all fed by the same [HomeActions].
 */
@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val night by vm.gazeteNight.collectAsStateWithLifecycle()
    // Day/night only exists in Gazete: Neon and Teletext never rebuild their style at sunset
    val style = rememberHomeStyle(theme, night && theme == HomeTheme.GAZETE)

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var contentMenuFor by remember { mutableStateOf<WatchItem?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showLight by remember { mutableStateOf(false) }
    var showControl by remember { mutableStateOf(false) }
    var showNet by remember { mutableStateOf(false) }
    var showWrapped by remember { mutableStateOf(false) }
    var showMatch by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }

    // Messages to whichever home is on screen (focus restore, items that vanished)
    val signals = remember { MutableSharedFlow<HomeSignal>(extraBufferCapacity = 8) }
    val actions = remember {
        HomeActions(
            onOpenControl = { showControl = true },
            onOpenLight = { showLight = true },
            onOpenMenu = { showMenu = true },
            onOpenNet = { showNet = true },
            onOpenMatch = { showMatch = true },
            onOpenWrapped = { showWrapped = true },
            onOpenThemePicker = { showTheme = true },
            onAppMenu = { menuFor = it },
            onContentMenu = { contentMenuFor = it },
        )
    }

    // Toasts outlive the launcher going to the background, unlike anything drawn here
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        vm.pinResult.collect { r ->
            val text = when (r) {
                PinResult.Added -> t("Favorilere eklendi")
                PinResult.Removed -> t("Favorilerden çıkarıldı")
                PinResult.Full -> t("Favoriler dolu (en fazla 12 içerik)")
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        vm.netWarning.collect { w ->
            Toast.makeText(context, t("Bağlantı 4K için yetersiz ({0} Mbps)", fmtMbps(w.mbps)), Toast.LENGTH_LONG).show()
        }
    }

    // Polling only while the home screen is actually visible
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.onResumed()
            try {
                while (true) {
                    delay(4000)
                    vm.refreshRam()
                }
            } finally {
                // Leaving home: hand the backlight back to what it was showing
                vm.onPaused()
            }
        }
    }

    // Home closes every overlay; each home resets its own focus and scroll on the same event
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            menuFor = null
            contentMenuFor = null
            showMenu = false
            showLight = false
            showControl = false
            showNet = false
            showWrapped = false
            showMatch = false
            showTheme = false
        }
    }

    // When the last overlay closes, tell the home to take focus back if it lost it
    val overlayOpen = menuFor != null || contentMenuFor != null || showMenu || showLight || showControl ||
        showNet || showWrapped || showMatch || showTheme
    var hadOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(overlayOpen) {
        if (overlayOpen) {
            hadOverlay = true
        } else if (hadOverlay) {
            hadOverlay = false
            // The dialog window is still leaving; the activity window takes focus back a frame later
            delay(100)
            signals.emit(HomeSignal.Restore)
        }
    }

    CompositionLocalProvider(
        LocalHomeStyle provides style,
        LocalContentColor provides style.text,
    ) {
        Box(Modifier.fillMaxSize()) {
            when (theme) {
                HomeTheme.NEON -> NeonHome(vm, actions, signals)
                HomeTheme.GAZETE -> GazeteHome(vm, actions, signals)
                HomeTheme.TELETEXT -> TeletextHome(vm, actions, signals)
            }

            contentMenuFor?.let { item ->
                ContentMenu(
                    item,
                    vm,
                    onDismiss = { contentMenuFor = null },
                    onToggled = {
                        contentMenuFor = null
                        signals.tryEmit(HomeSignal.PinToggled)
                    },
                )
            }

            menuFor?.let { app ->
                AppMenu(
                    app,
                    vm,
                    onDismiss = { menuFor = null },
                    onMove = {
                        menuFor = null
                        vm.startMove(app.pkg)
                    },
                    onHide = {
                        menuFor = null
                        vm.setHidden(app.pkg, true)
                        signals.tryEmit(HomeSignal.AppHidden)
                    },
                    onInfo = {
                        menuFor = null
                        vm.openAppInfo(app.pkg)
                    },
                )
            }
            if (showMenu) {
                LauncherMenu(
                    vm,
                    onDismiss = { showMenu = false },
                    onOpenTheme = {
                        showMenu = false
                        showTheme = true
                    },
                )
            }
            if (showLight) LightPanel(vm, onDismiss = { showLight = false })
            if (showControl) {
                ControlCenter(
                    vm,
                    onDismiss = { showControl = false },
                    onOpenLight = {
                        showControl = false
                        showLight = true
                    },
                    onOpenMenu = {
                        showControl = false
                        showMenu = true
                    },
                    onOpenNet = {
                        showControl = false
                        showNet = true
                    },
                    onOpenWrapped = {
                        showControl = false
                        showWrapped = true
                    },
                    onOpenMatch = {
                        showControl = false
                        showMatch = true
                    },
                    onOpenTheme = {
                        showControl = false
                        showTheme = true
                    },
                )
            }
            if (showNet) NetPanel(vm, onDismiss = { showNet = false })
            if (showWrapped) WrappedScreen(vm, onDismiss = { showWrapped = false })
            if (showMatch) MatchPanel(vm, onDismiss = { showMatch = false })
            if (showTheme) ThemePicker(vm, onDismiss = { showTheme = false })
        }
    }
}
