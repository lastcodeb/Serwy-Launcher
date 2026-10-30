package dev.mibox.neon.ui.lumen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.Headline
import dev.mibox.neon.data.HeadlineTarget
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.NetVerdict
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.i18n.t
import dev.mibox.neon.ui.AccentFollows
import dev.mibox.neon.ui.Focus
import dev.mibox.neon.ui.HomeActions
import dev.mibox.neon.ui.HomeSignal
import dev.mibox.neon.ui.LumenDisplay
import dev.mibox.neon.ui.LumenPalette
import dev.mibox.neon.ui.LumenSans
import dev.mibox.neon.ui.MatchChip
import dev.mibox.neon.ui.NeonSurface
import dev.mibox.neon.ui.ROW_APPS
import dev.mibox.neon.ui.ROW_FAVORITES
import dev.mibox.neon.ui.ROW_FOR_YOU
import dev.mibox.neon.ui.ROW_WATCH
import dev.mibox.neon.ui.RowsController
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.TeamLogo
import dev.mibox.neon.ui.glass.GlassCard
import dev.mibox.neon.ui.moveKeys
import dev.mibox.neon.ui.rememberNow
import dev.mibox.neon.ui.rowKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import kotlin.math.roundToInt

private val L = LumenPalette
private val Side = 48.dp

// Rows: titles and card sizes (design px / 2)
private val SmallW = 80.dp
private val SmallH = 45.dp
private val BigW = 144.dp
private val BigH = 81.dp
private val TitleH = 22.dp
private val RowGap = 15.dp
private val RowsTop = 280.dp
private val CardShape = RoundedCornerShape(9.dp)
private val SmallShape = RoundedCornerShape(8.dp)

private fun rowHeight(key: String): Dp = TitleH + 8.dp + (if (key == ROW_WATCH || key == ROW_FOR_YOU) BigH else SmallH) + 8.dp

/**
 * Lumen: calm and content-first. The focused card's story fills the showcase (title left, the
 * artwork right); below, the rows in the Glass order, the one in use bright and the one above it
 * still in view. The room's light is the only accent.
 */
@Composable
fun LumenHome(vm: LauncherViewModel, actions: HomeActions, signals: SharedFlow<HomeSignal>) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val favorites by vm.favoriteCards.collectAsStateWithLifecycle()
    val watch by vm.watchNext.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val watchLoaded by vm.watchLoaded.collectAsStateWithLifecycle()
    val moving by vm.moving.collectAsStateWithLifecycle()
    val movingFavorite by vm.movingFavorite.collectAsStateWithLifecycle()

    val focus = remember { mutableStateOf<Focus?>(null) }
    val rows = remember { RowsController() }
    val firstRow = rowKeys(favorites, watch, forYou).first()
    val scope = rememberCoroutineScope()
    fun keysNow() = rowKeys(vm.favoriteCards.value, vm.watchNext.value, vm.forYou.value)

    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(watchLoaded, apps.isNotEmpty()) {
        if (initialFocusDone || !watchLoaded || apps.isEmpty()) return@LaunchedEffect
        repeat(40) {
            if (rows.focus(firstRow)) {
                initialFocusDone = true
                return@LaunchedEffect
            }
            delay(50)
        }
    }
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            rows.scrollAllToStart()
            rows.focus(keysNow().first())
        }
    }
    LaunchedEffect(Unit) {
        signals.collect { s ->
            when (s) {
                HomeSignal.Restore -> if (focus.value == null) rows.focus(rows.activeKey ?: keysNow().first())
                HomeSignal.AppHidden -> {
                    focus.value = null
                    rows.focus(rows.activeKey ?: keysNow().first())
                }
                HomeSignal.PinToggled -> if (rows.activeKey == ROW_FAVORITES) {
                    focus.value = null
                    scope.launch {
                        delay(300)
                        val keys = keysNow()
                        rows.focus(if (ROW_FAVORITES in keys) ROW_FAVORITES else keys.first())
                    }
                }
            }
        }
    }

    AccentFollows(vm, focus.value?.app)
    var showApps by remember { mutableStateOf(false) }
    if (showApps) LumenApps(vm, actions.onAppMenu) { showApps = false }

    Box(Modifier.fillMaxSize().background(L.Ground)) {
        Showcase(vm, focus)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Side)
                .offset(y = 22.dp)
                .focusProperties { down = rows.requester(rows.activeKey ?: firstRow) },
        ) {
            LumenTopBar(vm, actions)
        }
        Rows(vm, apps, favorites, watch, forYou, moving, movingFavorite, rows, focus, actions, onOpenApps = { showApps = true })
    }
}

// --- Top bar -------------------------------------------------------------------------------------

private val DayFmt = DateTimeFormatter.ofPattern("d MMMM", TR)
private val ClockFmt = DateTimeFormatter.ofPattern("HH:mm", TR)

private fun greeting(hour: Int) = when (hour) {
    in 5..10 -> t("Günaydın")
    in 11..17 -> t("İyi günler")
    in 18..21 -> t("İyi akşamlar")
    else -> t("İyi geceler")
}

@Composable
private fun LumenTopBar(vm: LauncherViewModel, actions: HomeActions) {
    val now by rememberNow()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val wiz by vm.wiz.collectAsStateWithLifecycle()
    val wizIp by vm.wizIp.collectAsStateWithLifecycle()
    val net by vm.netNow.collectAsStateWithLifecycle()
    val match by vm.match.collectAsStateWithLifecycle()
    val matchOn by vm.matchEnabled.collectAsStateWithLifecycle()
    val ram by vm.ram.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val dim = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, fontWeight = FontWeight.Medium)

    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("serwan", style = TextStyle(fontFamily = LumenDisplay, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = L.Text))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha = 0.14f)))
        Spacer(Modifier.width(14.dp))
        val day = now.dayOfWeek.getDisplayName(JTextStyle.FULL, TR)
        Text("${greeting(now.hour)} · $day ${now.format(DayFmt)}", style = dim.copy(color = L.Dim), maxLines = 1)
        Spacer(Modifier.width(14.dp))
        if (matchOn || match != null) MatchChip(match, nowMs, actions.onOpenMatch)
        Spacer(Modifier.weight(1f))

        // Each status is a quiet text button: plain at rest, a white pill on focus
        val w = weather
        BarButton(onClick = actions.onOpenControl) {
            Text(
                if (w != null) "${w.tempC.roundToInt()}° ${city.name}" else city.name,
                style = dim, maxLines = 1,
            )
        }
        val p = wiz
        BarButton(onClick = actions.onOpenLight, onLongClick = if (wizIp != null) ({ vm.wizQuick() }) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (p?.on == true) L.Amber else L.Faint))
                Spacer(Modifier.width(5.dp))
                Text(
                    when {
                        wizIp == null -> t("Işık bul")
                        p == null -> t("bağlanıyor…")
                        p.on -> t("Işık · %{0}", p.dimming)
                        else -> t("Işık kapalı")
                    },
                    style = dim, maxLines = 1,
                )
            }
        }
        // Memory in use; OK closes the running apps and says how much came back
        BarButton(onClick = vm::boost) {
            val f = freed
            Text(if (f != null) "+${f / (1024 * 1024)} MB" else t("RAM %{0}", ram.usedPercent), style = dim, maxLines = 1)
        }
        BarButton(onClick = actions.onOpenNet) {
            Text(
                when (net.verdict) {
                    NetVerdict.UHD_OK -> t("4K hazır")
                    NetVerdict.HD_OK -> t("HD hazır")
                    NetVerdict.WEAK -> t("Zayıf bağlantı")
                    NetVerdict.UNKNOWN -> t("Ağ")
                },
                style = dim, maxLines = 1,
            )
        }
        BarButton(onClick = actions.onOpenControl) {
            Text(now.format(ClockFmt), style = TextStyle(fontFamily = LumenDisplay, fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
        Spacer(Modifier.width(6.dp))
        NeonSurface(
            onClick = vm::openSystemSettings,
            onLongClick = actions.onOpenMenu,
            modifier = Modifier.size(30.dp),
            shape = CircleShape,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = t("Ayarlar"),
                modifier = Modifier.size(16.dp).align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun BarButton(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    NeonSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.height(28.dp),
        container = Color.Transparent,
        stroke = Color.Transparent,
        focusedScale = 1f,
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { content() }
    }
}

// --- Showcase ------------------------------------------------------------------------------------

/** What the showcase tells: the focused card's story, or the lead story when nothing has focus. */
private data class Shown(val headline: Headline, val item: WatchItem?, val app: AppEntry?, val match: Boolean)

@Composable
private fun Showcase(vm: LauncherViewModel, focus: MutableState<Focus?>) {
    val lead by vm.headline.collectAsStateWithLifecycle()
    val f = focus.value
    val shown: Shown = when (f) {
        is Focus.Watch -> Shown(vm.headlineFor(f.item), f.item, f.app, false)
        is Focus.App -> Shown(vm.headlineFor(f.app), null, f.app, false)
        null -> when (val tg = lead.target) {
            is HeadlineTarget.OpenApp -> Shown(lead, null, vm.appFor(tg.pkg), false)
            is HeadlineTarget.OpenWatch -> Shown(lead, tg.item, vm.appFor(tg.item.pkg), false)
            HeadlineTarget.OpenMatch -> Shown(lead, null, null, true)
            HeadlineTarget.None -> Shown(lead, null, null, false)
        }
    }
    val item = shown.item
    val app = shown.app
    val large by produceState<ImageBitmap?>(null, item?.id, app?.pkg) {
        value = null
        delay(150)
        value = when {
            item != null -> vm.largeArt(item)
            app != null -> vm.largeArt(app)
            else -> null
        }
    }
    val small by produceState<AppArt?>(null, item?.id, app?.pkg) {
        value = when {
            item != null -> vm.watchArt(item) ?: app?.let { vm.art(it) }
            app != null -> vm.art(app)
            else -> null
        }
    }

    Row(
        Modifier.padding(start = Side, end = Side, top = 70.dp).fillMaxWidth().height(192.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            shown.headline,
            transitionSpec = { fadeIn(tween(300, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
            label = "lumenStory",
            modifier = Modifier.weight(1f),
        ) { h -> Story(h) }
        Spacer(Modifier.width(36.dp))
        Box(Modifier.size(341.dp, 192.dp).clip(RoundedCornerShape(13.dp)).background(L.Surface)) {
            if (shown.match) {
                MatchArt(vm)
            } else {
                Crossfade(large ?: small?.image, animationSpec = tween(400), label = "lumenArt") { img ->
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun Story(h: Headline) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (h.kicker.isNotBlank()) {
            Text(
                h.kicker.uppercase(TR),
                style = TextStyle(fontFamily = LumenSans, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = L.Dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val long = h.title.length > 16
        Text(
            h.title,
            style = TextStyle(
                fontFamily = LumenDisplay,
                fontSize = if (long) 42.sp else 60.sp,
                lineHeight = if (long) 42.sp else 56.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = if (long) (-1).sp else (-2).sp,
                color = L.Text,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (h.deck.isNotBlank()) {
            Text(h.deck, style = TextStyle(fontFamily = LumenSans, fontSize = 12.sp, color = L.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        h.progress?.let { p ->
            Box(Modifier.width(220.dp).height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.02f, 1f)).background(L.Text))
            }
        }
        if (h.action.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(17.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("OK", style = TextStyle(fontFamily = LumenSans, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = L.Dim))
                }
                Spacer(Modifier.width(7.dp))
                Text(h.action.lowercase(TR), style = TextStyle(fontFamily = LumenSans, fontSize = 10.sp, color = L.Muted))
            }
        }
    }
}

/** Match lead: both crests on the poster's place, home side first. */
@Composable
private fun BoxScope.MatchArt(vm: LauncherViewModel) {
    val m by vm.match.collectAsStateWithLifecycle()
    val s = m ?: return
    val us = MatchConfig.Team.espnId
    val (home, away) = if (s.home) us to s.opponentId else s.opponentId to us
    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        TeamLogo(home, 84.dp)
        Text("–", style = TextStyle(fontFamily = LumenDisplay, fontSize = 30.sp, color = L.Dim))
        TeamLogo(away, 84.dp)
    }
}

// --- Rows ----------------------------------------------------------------------------------------

@Composable
private fun Rows(
    vm: LauncherViewModel,
    apps: List<AppEntry>,
    favorites: List<FavoriteCard>,
    watch: List<WatchItem>,
    forYou: List<WatchItem>,
    moving: String?,
    movingFavorite: String?,
    rows: RowsController,
    focus: MutableState<Focus?>,
    actions: HomeActions,
    onOpenApps: () -> Unit,
) {
    val keys = rowKeys(favorites, watch, forYou)
    val active = keys.indexOf(rows.activeKey).coerceAtLeast(0)
    // Keep the row above the active one in view, like the design
    val firstShown = (active - 1).coerceAtLeast(0)
    var top = 0.dp
    for (i in 0 until firstShown) top += rowHeight(keys[i]) + RowGap
    val shift by animateDpAsState(-top, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow), label = "lumenRows")

    Box(
        Modifier
            .offset(y = RowsTop)
            .fillMaxWidth()
            .height(540.dp - RowsTop)
            .clipToBounds()
            .onPreviewKeyEvent { e ->
                if (moving != null || movingFavorite != null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> active > 0 && rows.focus(keys[active - 1])
                    Key.DirectionDown -> active >= keys.lastIndex || rows.focus(keys[active + 1])
                    else -> false
                }
            },
    ) {
        Column(
            Modifier
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = shift.toPx() },
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            keys.forEachIndexed { i, key ->
                val entry = Modifier
                    .focusRequester(rows.requester(key))
                    .onFocusChanged { if (it.hasFocus) rows.activeKey = key }
                val title = when (key) {
                    ROW_FAVORITES -> t("Favoriler")
                    ROW_WATCH -> t("İzlemeye devam et")
                    ROW_FOR_YOU -> t("Senin için")
                    else -> t("Tüm uygulamalar")
                }
                Column(Modifier.height(rowHeight(key))) {
                    Text(
                        title,
                        modifier = Modifier.padding(start = Side).height(TitleH),
                        style = TextStyle(
                            fontFamily = LumenDisplay,
                            fontSize = if (i == active) 15.sp else 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (i == active) L.Text else L.Dim,
                        ),
                    )
                    when (key) {
                        ROW_FAVORITES -> FavoritesRow(favorites, vm, movingFavorite, rows.listState(key), entry, focus, actions, onOpenApps)
                        ROW_WATCH -> WatchRow(watch, vm, rows.listState(key), entry, focus, actions)
                        ROW_FOR_YOU -> WatchRow(forYou, vm, rows.listState(key), entry, focus, actions)
                        ROW_APPS -> AppsRow(apps, vm, moving, rows.listState(key), entry, focus, actions, onOpenApps)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.row(entry: Modifier, h: Dp) = fillMaxWidth()
    .height(h + 16.dp)
    .then(entry)
    .focusRestorer()

/** On a row's last card: Right stays put instead of jumping up to the top bar. */
@OptIn(ExperimentalComposeUiApi::class)
private val RowEnd = Modifier.focusProperties { right = FocusRequester.Cancel }

private val RowPadding = PaddingValues(horizontal = Side, vertical = 8.dp)


@Composable
private fun FavoritesRow(
    cards: List<FavoriteCard>,
    vm: LauncherViewModel,
    moving: String?,
    state: LazyListState,
    entry: Modifier,
    focus: MutableState<Focus?>,
    actions: HomeActions,
    onOpenApps: () -> Unit,
) {
    LazyRow(state = state, modifier = Modifier.row(entry, SmallH), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(cards, key = { _, c -> c.key }) { index, card ->
            when (card) {
                is FavoriteCard.App -> AppTile(
                    card.app, vm, moving = moving == card.key, index = index,
                    modifier = Modifier.animateItem(),
                    onFocused = { focus.value = Focus.App(card.app) },
                    onClick = { vm.launchPinned(card) },
                    onLongClick = { actions.onFavoriteMenu(card) },
                )
                is FavoriteCard.Content -> ContentTile(
                    card.item, vm, small = true, pinned = card, moving = moving == card.key, index = index,
                    modifier = Modifier.animateItem(),
                    onFocused = { focus.value = Focus.Watch(card.item, card.app) },
                    onClick = { vm.launchPinned(card) },
                    onLongClick = { actions.onFavoriteMenu(card) },
                )
            }
        }
        item(key = "all") { AllAppsTile(onOpenApps) }
    }
}

/** Last tile of Favoriler and Tüm uygulamalar: opens the grid of every app. */
@Composable
private fun AllAppsTile(onClick: () -> Unit) {
    LumenCard(SmallW, SmallH, SmallShape, onClick, onClick, RowEnd, moving = false, onFocused = {}) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            AllAppsGlyph()
            Spacer(Modifier.width(7.dp))
            Text(t("Tümü"), style = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = L.Text))
        }
    }
}

@Composable
private fun WatchRow(
    items: List<WatchItem>,
    vm: LauncherViewModel,
    state: LazyListState,
    entry: Modifier,
    focus: MutableState<Focus?>,
    actions: HomeActions,
) {
    LazyRow(state = state, modifier = Modifier.row(entry, BigH), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
            ContentTile(
                item, vm,
                modifier = if (i == items.lastIndex) RowEnd else Modifier,
                onFocused = { focus.value = Focus.Watch(item, vm.appFor(item.pkg)) },
                onClick = { vm.launchWatch(item) },
                onLongClick = { actions.onContentMenu(item) },
            )
        }
    }
}

@Composable
private fun AppsRow(
    apps: List<AppEntry>,
    vm: LauncherViewModel,
    moving: String?,
    state: LazyListState,
    entry: Modifier,
    focus: MutableState<Focus?>,
    actions: HomeActions,
    onOpenApps: () -> Unit,
) {
    LazyRow(state = state, modifier = Modifier.row(entry, SmallH), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
            AppTile(
                app, vm, moving = moving == app.pkg, index = index,
                modifier = Modifier.animateItem(),
                onFocused = { focus.value = Focus.App(app) },
                onClick = { vm.launch(app) },
                onLongClick = { actions.onAppMenu(app) },
            )
        }
        item(key = "all") { AllAppsTile(onOpenApps) }
    }
}

/** A Lumen card: the Glass card's lift, shadow and sheen on focus, in Lumen's sizes. */
@Composable
private fun LumenCard(
    width: Dp,
    height: Dp,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
    moving: Boolean,
    onFocused: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        width = width,
        height = height,
        shape = shape,
        forceBig = moving,
        onFocused = onFocused,
    ) {
        content()
        if (moving) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text("◀   ▶", style = TextStyle(fontFamily = LumenSans, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppEntry,
    vm: LauncherViewModel,
    moving: Boolean,
    index: Int,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val art by produceState<AppArt?>(null, app.pkg) { value = vm.art(app) }
    val bring = remember { BringIntoViewRequester() }
    if (moving) LaunchedEffect(index) { bring.bringIntoView() }
    LumenCard(
        SmallW, SmallH, SmallShape, onClick, onLongClick,
        modifier = modifier
            .bringIntoViewRequester(bring)
            .then(if (moving) Modifier.onPreviewKeyEvent { moveKeys(it, vm) } else Modifier),
        moving = moving,
        onFocused = onFocused,
    ) {
        val a = art
        when {
            a == null -> {}
            a.isBanner -> Image(a.image, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> {
                Box(Modifier.fillMaxSize().background(a.cardTop.copy(alpha = 0.6f)))
                Image(a.image, app.label, Modifier.align(Alignment.Center).size(24.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContentTile(
    item: WatchItem,
    vm: LauncherViewModel,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    pinned: FavoriteCard.Content? = null,
    moving: Boolean = false,
    index: Int = 0,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb by produceState<AppArt?>(null, item.id, item.imageUri) {
        value = if (pinned != null) vm.favoriteArt(pinned) else vm.watchArt(item)
    }
    val app = remember(item.pkg) { vm.appFor(item.pkg) }
    val appArt by produceState<AppArt?>(null, item.pkg) { value = app?.let { vm.art(it) } }
    val bring = remember { BringIntoViewRequester() }
    if (moving) LaunchedEffect(index) { bring.bringIntoView() }
    LumenCard(
        if (small) SmallW else BigW, if (small) SmallH else BigH, if (small) SmallShape else CardShape,
        onClick, onLongClick,
        modifier = modifier
            .bringIntoViewRequester(bring)
            .then(if (moving) Modifier.onPreviewKeyEvent { moveKeys(it, vm) } else Modifier),
        moving = moving,
        onFocused = onFocused,
    ) {
        val th = thumb
        if (th != null) {
            Image(th.image, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            appArt?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.5f) }
            Text(
                item.title,
                modifier = Modifier.align(Alignment.BottomStart).padding(7.dp),
                style = TextStyle(fontFamily = LumenSans, fontSize = if (small) 8.sp else 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!small) {
            item.progress?.let { p ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 7.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.03f, 1f)).background(L.Text))
                }
            }
        }
    }
}
