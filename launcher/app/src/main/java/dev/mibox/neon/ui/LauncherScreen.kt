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
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.HomeTheme
import dev.mibox.neon.data.PinResult
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.ui.gazete.GazeteHome
import dev.mibox.neon.ui.aura.AuraHome
import dev.mibox.neon.ui.glass.GlassHome
import dev.mibox.neon.ui.lumen.LumenApps
import dev.mibox.neon.ui.lumen.LumenHome
import dev.mibox.neon.ui.lumen.LumenRoom
import dev.mibox.neon.ui.teletext.TeletextHome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import dev.mibox.neon.i18n.t

/**
 * The shell shared by every home theme: toasts, the lifecycle and polling block, the overlays
 * (menus, panels, Wrapped, the theme picker) and the style. The themed home itself is drawn
 * underneath: [NeonHome], [GazeteHome], [TeletextHome], [GlassHome], [LumenHome] or [AuraHome], all fed by the same [HomeActions].
 */
@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val night by vm.gazeteNight.collectAsStateWithLifecycle()
    // Day/night only exists in Gazete: Neon and Teletext never rebuild their style at sunset
    val style = rememberHomeStyle(theme, night && theme == HomeTheme.GAZETE)

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    // Set when the open app/content menu came from a Favoriler card: "Taşı" then moves that card
    var favoriteKey by remember { mutableStateOf<String?>(null) }
    var contentMenuFor by remember { mutableStateOf<WatchItem?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showLight by remember { mutableStateOf(false) }
    var showControl by remember { mutableStateOf(false) }
    var showNet by remember { mutableStateOf(false) }
    var showWrapped by remember { mutableStateOf(false) }
    var showMatch by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showAllApps by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }

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
            onAppMenu = {
                favoriteKey = null
                menuFor = it
            },
            onContentMenu = {
                favoriteKey = null
                contentMenuFor = it
            },
            onFavoriteMenu = { card ->
                favoriteKey = card.key
                when (card) {
                    is FavoriteCard.App -> menuFor = card.app
                    is FavoriteCard.Content -> contentMenuFor = card.item
                }
            },
            onOpenSearch = { showSearch = true },
            onOpenMedia = { showMedia = true },
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
        vm.notice.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
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
            showAllApps = false
            showSearch = false
            showMedia = false
        }
    }
    // The remote's Apps key: after the Home reset above, so it is not closed again at once
    val allAppsRequest by vm.allAppsRequest.collectAsStateWithLifecycle()
    LaunchedEffect(allAppsRequest) {
        if (!allAppsRequest) return@LaunchedEffect
        delay(80)
        showAllApps = true
        vm.requestAllApps(false)
    }

    // When the last overlay closes, tell the home to take focus back if it lost it
    val overlayOpen = menuFor != null || contentMenuFor != null || showMenu || showLight || showControl ||
        showNet || showWrapped || showMatch || showTheme || showAllApps || showSearch || showMedia
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
                HomeTheme.GLASS -> GlassHome(vm, actions, signals)
                HomeTheme.LUMEN -> LumenHome(vm, actions, signals)
                HomeTheme.AURA -> AuraHome(vm, actions, signals)
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
                    onMove = favoriteKey?.let { key ->
                        {
                            contentMenuFor = null
                            vm.startFavoriteMove(key)
                        }
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
                        val key = favoriteKey
                        if (key != null) vm.startFavoriteMove(key) else vm.startMove(app.pkg)
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
            // Lumen and Aura have the room sheet; Aura's has its violet where Lumen's is amber
            if (showControl && (theme == HomeTheme.LUMEN || theme == HomeTheme.AURA)) {
                LumenRoom(
                    vm,
                    accent = if (theme == HomeTheme.AURA) AuraPalette.Violet else LumenPalette.Amber,
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
                    onOpenSearch = {
                        showControl = false
                        showSearch = true
                    },
                    onOpenMedia = {
                        showControl = false
                        showMedia = true
                    },
                )
            } else if (showControl) {
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
                    onOpenSearch = {
                        showControl = false
                        showSearch = true
                    },
                    onOpenMedia = {
                        showControl = false
                        showMedia = true
                    },
                )
            }
            if (showNet) NetPanel(vm, onDismiss = { showNet = false })
            if (showWrapped) WrappedScreen(vm, onDismiss = { showWrapped = false })
            if (showMatch) MatchPanel(vm, onDismiss = { showMatch = false })
            if (showTheme) ThemePicker(vm, onDismiss = { showTheme = false })
            if (showAllApps) LumenApps(vm, actions.onAppMenu) { showAllApps = false }
            if (showSearch) SearchPanel(vm, onDismiss = { showSearch = false })
            if (showMedia) MediaPanel(vm, onDismiss = { showMedia = false })
        }
    }
}
