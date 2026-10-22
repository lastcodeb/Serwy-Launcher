package dev.mibox.neon.ui.gazete

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.HeadlineTarget
import dev.mibox.neon.ui.AccentFollows
import dev.mibox.neon.ui.HomeActions
import dev.mibox.neon.ui.HomeSignal
import dev.mibox.neon.ui.LocalHomeStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class GazetePage { Front, Dizin }

/**
 * The Gazete home: a newspaper front page (day or night edition, chosen by the style the shell
 * provides) and, one press of "Dizin" away, the index of all apps. Overlays are the shared
 * ones, opened through [actions].
 *
 * Focus: every focusable registers a requester in [GazeteNav]; the focused card publishes its
 * story into one state that only the manşet and [GazeteAmbient] read, so moving focus never
 * recomposes the page.
 */
@Composable
fun GazeteHome(vm: LauncherViewModel, actions: HomeActions, signals: SharedFlow<HomeSignal>) {
    val st = LocalHomeStyle.current
    val nav = remember { GazeteNav() }
    var page by remember { mutableStateOf(GazetePage.Front) }
    val scope = rememberCoroutineScope()

    val openDizin: () -> Unit = remember {
        {
            nav.story.value = null
            page = GazetePage.Dizin
            scope.launch {
                val first = vm.apps.value.firstOrNull()
                nav.focusWhenReady(listOfNotNull(first?.let(::dizinKey), K_BACK))
            }
        }
    }
    val backToFront: () -> Unit = remember {
        {
            nav.story.value = null
            page = GazetePage.Front
            scope.launch { nav.focusWhenReady(listOf(K_TOP_DIZIN)) }
        }
    }
    // Back leaves the index first; on the front page it falls through to the activity (nowhere to go)
    BackHandler(enabled = page == GazetePage.Dizin) { backToFront() }

    // Start on the manşet button. Watch Next decides what the lead is, so wait for it (briefly)
    LaunchedEffect(Unit) {
        withTimeoutOrNull(2500) { vm.watchLoaded.first { it } }
        nav.focusFirst(vm)
    }
    // Home key: close the index, scroll back and focus the manşet
    // (plain collect + a launched job: the old collectLatest block hung on scrollToItem of a
    // LazyListState that was never laid out when Favoriler was empty, so focus was never requested)
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            nav.story.value = null
            page = GazetePage.Front
            scope.launch {
                nav.focusFirst(vm)
                // The window may take focus back a moment later and restore the old target: ask again
                delay(300)
                if (page == GazetePage.Front) nav.focus(K_MAN)
                withTimeoutOrNull(200) { nav.favList.scrollToItem(0) }
            }
        }
    }
    LaunchedEffect(Unit) {
        signals.collect { s ->
            when (s) {
                // An overlay closed: Compose usually restores focus itself; step in only when it is gone
                HomeSignal.Restore -> scope.launch {
                    delay(120)
                    if (!nav.anyFocus) nav.focusWhenReady(listOfNotNull(nav.last) + nav.initialKeys(vm))
                }
                // The focused app is about to vanish: take the one that moves into its place
                HomeSignal.AppHidden -> scope.launch {
                    val index = nav.lastIndex
                    val inDizin = page == GazetePage.Dizin
                    nav.story.value = null
                    delay(250)
                    if (inDizin) {
                        val list = vm.apps.value
                        val next = list.getOrNull(index.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
                        nav.focusWhenReady(listOfNotNull(next?.let(::dizinKey), K_BACK))
                    } else {
                        nav.focusWhenReady(nav.initialKeys(vm))
                    }
                }
                // Unpinning removes the focused favorite: refocus its neighbour once it is gone
                HomeSignal.PinToggled -> if (nav.last?.startsWith("f:") == true) {
                    scope.launch {
                        val index = nav.lastIndex
                        nav.story.value = null
                        delay(300)
                        val cards = vm.favoriteCards.value
                        val next = cards.getOrNull(index.coerceIn(0, (cards.size - 1).coerceAtLeast(0)))
                        nav.focusWhenReady(listOfNotNull(next?.let(::favKey)) + nav.initialKeys(vm))
                    }
                }
            }
        }
    }

    val grain = remember(st) { st.text.copy(alpha = if (st.night) 0.08f else 0.09f) }
    Box(
        Modifier
            .fillMaxSize()
            .background(st.background)
            .onFocusChanged { nav.anyFocus = it.hasFocus },
    ) {
        Spacer(Modifier.fillMaxSize().paperGrain(grain))
        when (page) {
            GazetePage.Front -> GazeteFront(vm, actions, nav, openDizin)
            GazetePage.Dizin -> GazeteDizin(vm, actions, nav, backToFront)
        }
        GazeteAmbient(vm, nav)
    }
}

/** Suspends until the first of the initial candidates takes focus. */
private suspend fun GazeteNav.focusFirst(vm: LauncherViewModel) {
    focusWhenReady(initialKeys(vm))
}

/**
 * Keeps the room light on the focused card's app, or on the headline's app when nothing
 * app-bound is focused. Its own composable: it is the only one (with the manşet) that reads the
 * focus state.
 */
@Composable
private fun GazeteAmbient(vm: LauncherViewModel, nav: GazeteNav) {
    val headline by vm.headline.collectAsStateWithLifecycle()
    val story = nav.story.value
    val app = remember(story, headline.target) {
        when (story) {
            is GazeteStory.Item -> vm.appFor(story.item.pkg)
            is GazeteStory.Application -> story.app
            null -> when (val t = headline.target) {
                is HeadlineTarget.OpenApp -> vm.appFor(t.pkg)
                is HeadlineTarget.OpenWatch -> vm.appFor(t.item.pkg)
                HeadlineTarget.OpenMatch, HeadlineTarget.None -> null
            }
        }
    }
    AccentFollows(vm, app)
}
