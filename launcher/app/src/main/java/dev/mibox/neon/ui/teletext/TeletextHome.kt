package dev.mibox.neon.ui.teletext

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.HeadlineTarget
import dev.mibox.neon.ui.AccentFollows
import dev.mibox.neon.ui.HomeActions
import dev.mibox.neon.ui.HomeSignal
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.onRemoteKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HeaderDate = DateTimeFormatter.ofPattern("EEE d MMM", TR)
private val HeaderTime = DateTimeFormatter.ofPattern("HH:mm:ss", TR)

/**
 * The Teletext home: a numbered page service inside the home screen. Pages 100 (Ana sayfa), 200,
 * 300, 301, 400 (with the 450 network block) and 500 are kept in local state; only the content
 * area is rebuilt when the page changes. The shell around it (header with the seconds clock, hint
 * row, fastext keys, scanline overlay) stays.
 *
 * Keys: D-pad moves between rows, OK opens, long-press OK opens the row's menu where it has one.
 * BACK on any page but 100 returns to 100; on 100 it falls through. Home returns to 100 with focus
 * on the first index row. Remotes with color keys jump to 300 / 200 / 400 / 500; with number keys,
 * a page number can be typed (three digits, shown as "P3__" in the header).
 */
@Composable
fun TeletextHome(vm: LauncherViewModel, actions: HomeActions, signals: SharedFlow<HomeSignal>) {
    val nav = remember { TtNav() }
    var page by remember { mutableIntStateOf(TtPage.HOME) }
    val typed = remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Opening a page: clear the typing buffer, remember which index row leads back, ask for focus
    val go: (Int) -> Unit = remember(nav) {
        { target: Int ->
            typed.value = ""
            if (target == TtPage.HOME && page != TtPage.HOME) nav.entryKey = indexKeyOf(page)
            page = target
            nav.tick.intValue++
        }
    }
    val ctx = remember(vm, actions, nav, go) { TtCtx(vm, actions, nav, go) }

    // Focus the page's first element on every page change and whenever a reset is asked for.
    // The request throws until the node exists, so ask again; nav.last tells when it landed.
    LaunchedEffect(page, nav.tick.intValue) {
        val target = if (page == TtPage.HOME) nav.index(nav.entryKey) else nav.first
        nav.last = null
        repeat(12) { attempt ->
            val ok = runCatching { target.requestFocus() }.isSuccess
            // A page with nothing to focus still has its ANA SAYFA button
            if (!ok && attempt >= 6 && page != TtPage.HOME) runCatching { nav.fallback.requestFocus() }
            delay(30)
            if (nav.last === target || (attempt >= 6 && nav.last === nav.fallback)) return@LaunchedEffect
        }
    }

    // Typed digits that never reach three are forgotten after a while
    LaunchedEffect(typed.value) {
        if (typed.value.isNotEmpty()) {
            delay(4000)
            typed.value = ""
        }
    }

    BackHandler(enabled = page != TtPage.HOME || typed.value.isNotEmpty()) {
        if (typed.value.isNotEmpty()) typed.value = "" else go(TtPage.HOME)
    }

    // Home key: back to P100, first index row (LauncherScreen already closed every overlay)
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            nav.entryKey = "200"
            typed.value = ""
            page = TtPage.HOME
            nav.tick.intValue++
        }
    }
    LaunchedEffect(Unit) {
        signals.collect { s ->
            when (s) {
                // An overlay closed: put focus back on the row it came from, or reset if that row is gone
                HomeSignal.Restore -> if (runCatching { nav.last!!.requestFocus() }.isFailure) nav.tick.intValue++
                // The focused app or pin vanishes: wait for the lists to update, then re-focus
                HomeSignal.AppHidden, HomeSignal.PinToggled -> scope.launch {
                    delay(300)
                    if (runCatching { nav.last!!.requestFocus() }.isFailure) nav.tick.intValue++
                }
            }
        }
    }

    // Number keys: three digits open a page (or a tool, for 6xx-9xx)
    val onDigit: (Int) -> Unit = { d ->
        val t = typed.value
        if (t.isNotEmpty() || d != 0) {
            val n = t + d
            if (n.length < 3) {
                typed.value = n
            } else {
                typed.value = ""
                when (n.toInt()) {
                    TtPage.HOME -> go(TtPage.HOME)
                    TtPage.LIVE -> go(TtPage.LIVE)
                    in 200..299 -> go(TtPage.RESUME)
                    in 300..399 -> go(TtPage.SPORT)
                    in 400..499 -> go(TtPage.WEATHER)
                    in 500..599 -> go(TtPage.APPS)
                    600 -> actions.onOpenWrapped()
                    700 -> ctx.openLight()
                    800 -> actions.onOpenControl()
                    900 -> actions.onOpenThemePicker()
                    else -> Unit // not a page of this service: the digits are dropped
                }
            }
        }
    }

    TtAccent(vm, nav)

    CompositionLocalProvider(LocalTtNav provides nav) {
        Box(
            Modifier
                .fillMaxSize()
                .background(TtBlack)
                .onRemoteKeys(
                    red = { go(TtPage.SPORT) },
                    green = { go(TtPage.RESUME) },
                    yellow = { go(TtPage.WEATHER) },
                    blue = { go(TtPage.APPS) },
                    digit = onDigit,
                ),
        ) {
            // 48dp side margins, 27dp top and bottom: the 960x540 design space of the concept
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
                TtHeader(page, typed)
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    when (page) {
                        TtPage.RESUME -> TtResumePage(ctx)
                        TtPage.SPORT -> TtSportPage(ctx)
                        TtPage.LIVE -> TtLivePage(ctx)
                        TtPage.WEATHER -> TtWeatherPage(ctx)
                        TtPage.APPS -> TtAppsPage(ctx)
                        else -> TtHomePage(ctx)
                    }
                }
                TtHintRow(ctx, page)
                TtFastext(ctx)
            }
            // Scanlines and vignette: one cached overlay on top, drawing only
            Box(Modifier.fillMaxSize().ttScanlines())
        }
    }
}

/** The index row that leads to [page], to land on when coming back to P100. */
private fun indexKeyOf(page: Int): String = when (page) {
    TtPage.SPORT, TtPage.LIVE -> "300"
    TtPage.WEATHER -> "400"
    TtPage.APPS -> "500"
    else -> "200"
}

/**
 * Keeps the room light following the focused app, or the headline's app when no app row is
 * focused. Own composable so the focus state is read here and nowhere else.
 */
@Composable
private fun TtAccent(vm: LauncherViewModel, nav: TtNav) {
    val headline by vm.headline.collectAsStateWithLifecycle()
    val fallback = remember(headline.target) {
        when (val t = headline.target) {
            is HeadlineTarget.OpenApp -> vm.appFor(t.pkg)
            is HeadlineTarget.OpenWatch -> vm.appFor(t.item.pkg)
            else -> null
        }
    }
    AccentFollows(vm, nav.accent.value ?: fallback)
}

/** Blue header band: page number (or the digits being typed), service name, date and the seconds clock. */
@Composable
private fun TtHeader(page: Int, typed: State<String>) {
    val t = typed.value
    Row(
        Modifier.fillMaxWidth().height(TtRowH).background(TtBlue).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TtText(if (t.isEmpty()) "P$page" else "P" + t.padEnd(3, '_'), TtYellow)
        Spacer(Modifier.width(12.dp))
        TtText("NEON 100", TtWhite, Modifier.weight(1f))
        TtClock()
    }
}

/** "Per 22 Eki  21:12:05". Ticks once a second, and only while the home is RESUMED. */
@Composable
private fun TtClock() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(LocalDateTime.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = LocalDateTime.now()
                delay(1000L - System.currentTimeMillis() % 1000 + 5)
            }
        }
    }
    TtText(HeaderDate.format(now), TtWhite)
    Spacer(Modifier.width(12.dp))
    TtText(HeaderTime.format(now), TtYellow)
}

/** Row above the fastext keys: the way back to 100 (or, on 100, the gear), with the page's hint. */
@Composable
private fun TtHintRow(ctx: TtCtx, page: Int) {
    Row(
        Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page == TtPage.HOME) {
            TtText("Yukarı/aşağı: seç", TtCyan)
            Spacer(Modifier.width(24.dp))
            TtText("OK: aç", TtCyan, Modifier.weight(1f))
            // The gear: Android TV settings, long press = the launcher menu
            TtFocusable(
                onClick = { ctx.vm.openSystemSettings() },
                modifier = Modifier.height(TtRowH),
                onLongClick = ctx.actions.onOpenMenu,
            ) { f ->
                Row(Modifier.fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TtText("Ayarlar", tc(f, TtYellow))
                    Spacer(Modifier.width(12.dp))
                    TtText("(uzun bas: menü)", tc(f, TtCyan))
                }
            }
        } else {
            TtFocusable(
                onClick = { ctx.go(TtPage.HOME) },
                modifier = Modifier.height(TtRowH),
                requester = ctx.nav.fallback,
            ) { f ->
                Row(Modifier.fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TtText("100 Ana sayfa", tc(f, TtYellow))
                }
            }
            Spacer(Modifier.weight(1f))
            TtText(
                when (page) {
                    TtPage.RESUME, TtPage.APPS -> "Uzun bas: menü açılır"
                    TtPage.SPORT -> "OK: değiştir"
                    TtPage.WEATHER -> "Uzun bas: ağ ayrıntısı"
                    else -> "Sayfa no yaz ya da renkli tuşa bas"
                },
                TtCyan,
            )
        }
    }
}

/** The four fastext keys, the same on every page: red 300, green 200, yellow 400, blue 500. */
@Composable
private fun TtFastext(ctx: TtCtx) {
    Row(Modifier.fillMaxWidth().height(TtRowH), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FastKey(ctx, "Spor", TtPage.SPORT, TtRed, TtBlack, Modifier.weight(1f))
        FastKey(ctx, "Devam et", TtPage.RESUME, TtGreen, TtBlack, Modifier.weight(1f))
        FastKey(ctx, "Hava", TtPage.WEATHER, TtYellow, TtBlack, Modifier.weight(1f))
        FastKey(ctx, "Uygulamalar", TtPage.APPS, TtBlue, TtWhite, Modifier.weight(1f))
    }
}

@Composable
private fun FastKey(
    ctx: TtCtx,
    label: String,
    target: Int,
    color: Color,
    fg: Color,
    modifier: Modifier,
) {
    TtButton(onClick = { ctx.go(target) }, modifier = modifier, color = color, fg = fg) { c ->
        TtText(label, c, Modifier.weight(1f))
        TtText(target.toString(), c)
    }
}
