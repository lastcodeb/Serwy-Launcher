package dev.mibox.neon.ui.glass

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.Headline
import dev.mibox.neon.data.HeadlineTarget
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.i18n.t
import dev.mibox.neon.ui.AccentFollows
import dev.mibox.neon.ui.Focus
import dev.mibox.neon.ui.HomeActions
import dev.mibox.neon.ui.HomeSignal
import dev.mibox.neon.ui.ROW_APPS
import dev.mibox.neon.ui.ROW_FAVORITES
import dev.mibox.neon.ui.ROW_FOR_YOU
import dev.mibox.neon.ui.ROW_WATCH
import dev.mibox.neon.ui.RowsController
import dev.mibox.neon.ui.TeamLogo
import dev.mibox.neon.ui.TopBar
import dev.mibox.neon.ui.moveKeys
import dev.mibox.neon.ui.rowKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

private val Sans = FontFamily.SansSerif
private val Side = 56.dp

// The shelf: a frosted slab across the lower part of the screen holding one row at a time
private val ShelfTop = 352.dp
private val ShelfHeight = 166.dp
private val RowPitch = ShelfHeight
private val CardPad = 14.dp // room for the focus scale and shadow inside the clipped shelf

/**
 * Apple TV-like home: the focused content fills the top of the screen (sharp, fading into a
 * frosted blur of itself), with its story on the left; below, a glass shelf holds the rows.
 */
@Composable
fun GlassHome(vm: LauncherViewModel, actions: HomeActions, signals: SharedFlow<HomeSignal>) {
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

    // Same start-up and restore rules as Neon: first card of the top row once Watch Next is known
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
            // Focus is kept: if the first card already had it, no focus event would refill it
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

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12))) {
        TopShelf(vm, focus)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .offset(y = 26.dp)
                .focusProperties { down = rows.requester(rows.activeKey ?: firstRow) },
        ) {
            TopBar(
                vm,
                onOpenMenu = actions.onOpenMenu,
                onOpenLight = actions.onOpenLight,
                onOpenControl = actions.onOpenControl,
                onOpenNet = actions.onOpenNet,
                onOpenMatch = actions.onOpenMatch,
                onOpenSearch = actions.onOpenSearch,
                onOpenMedia = actions.onOpenMedia,
            )
        }
        Shelf(vm, apps, favorites, watch, forYou, moving, movingFavorite, rows, focus, actions)
    }
}

// --- Top shelf -----------------------------------------------------------------------------------

/** What the top of the screen shows: the focused card's story, or the lead story when idle. */
private sealed interface Feature {
    val headline: Headline

    data class Watch(override val headline: Headline, val item: WatchItem, val app: AppEntry?) : Feature
    data class App(override val headline: Headline, val app: AppEntry) : Feature
    data class Lead(override val headline: Headline, val app: AppEntry?, val item: WatchItem?, val match: Boolean) : Feature
}

@Composable
private fun TopShelf(vm: LauncherViewModel, focus: MutableState<Focus?>) {
    val lead by vm.headline.collectAsStateWithLifecycle()
    val f = focus.value
    val feature: Feature = when (f) {
        is Focus.Watch -> Feature.Watch(vm.headlineFor(f.item), f.item, f.app)
        is Focus.App -> Feature.App(vm.headlineFor(f.app), f.app)
        null -> when (val tg = lead.target) {
            is HeadlineTarget.OpenApp -> Feature.Lead(lead, vm.appFor(tg.pkg), null, false)
            is HeadlineTarget.OpenWatch -> Feature.Lead(lead, vm.appFor(tg.item.pkg), tg.item, false)
            HeadlineTarget.OpenMatch -> Feature.Lead(lead, null, null, true)
            HeadlineTarget.None -> Feature.Lead(lead, null, null, false)
        }
    }
    val item = (feature as? Feature.Watch)?.item ?: (feature as? Feature.Lead)?.item
    val app = when (feature) {
        is Feature.Watch -> feature.app
        is Feature.App -> feature.app
        is Feature.Lead -> feature.app
    }
    val match = (feature as? Feature.Lead)?.match == true

    // Art: the large picture for the top, the small one (already loaded for the cards) for the blur
    val large by produceState<ImageBitmap?>(null, item?.id, app?.pkg) {
        value = null
        // A short pause: scrolling fast through a row should not decode every poster on the way
        delay(180)
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
    val blurKey = item?.let { "w${it.id}" } ?: app?.pkg
    val frost by produceState<ImageBitmap?>(null, blurKey, small) {
        val s = small
        if (s != null && blurKey != null) value = blurred(blurKey, s.image)
    }

    Box(Modifier.fillMaxSize()) {
        // 1 + 2. The frosted wash over the whole screen (an aurora when there is no art) and the
        // content art, sharp in the upper right and melting into the wash. One canvas, no layers:
        // full-screen crossfades and masks would each cost a screen-sized GPU buffer.
        val showFull = item != null
        val wash = rememberFading(frost, 600)
        val poster = rememberFading(if (showFull) large else null, 500)
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val posterW = 700.dp.toPx()
                    val posterH = 394.dp.toPx()
                    val left = size.width - posterW
                    val cur = poster.cur?.let { maskedPoster(it, left, posterW, posterH) }
                    val prev = poster.prev?.let { maskedPoster(it, left, posterW, posterH) }
                    onDrawBehind {
                        if (wash.progress < 1f) drawWash(wash.prev, 1f, match)
                        drawWash(wash.cur, wash.progress, match)
                        drawIntoCanvas { c ->
                            prev?.let { it.alpha = ((1f - poster.progress) * 255).toInt(); c.nativeCanvas.drawRect(left, 0f, size.width, posterH, it) }
                            cur?.let { it.alpha = (poster.progress * 255).toInt(); c.nativeCanvas.drawRect(left, 0f, size.width, posterH, it) }
                        }
                    }
                },
        )
        // App banners are small artwork: a lifted tile, not stretched. The fade's layer is tile-sized.
        Box(Modifier.fillMaxSize()) {
            Crossfade(
                if (showFull) null else large,
                animationSpec = tween(500),
                label = "tile",
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = Side).size(320.dp, 180.dp),
            ) { img ->
                if (img != null) {
                    Image(
                        img,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(28.dp, RoundedCornerShape(22.dp))
                            .clip(RoundedCornerShape(22.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        if (match) MatchCrests(vm)
        // 3. Legibility: darker towards the bottom (shelf) and the left (text)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        0.35f to Color.Transparent,
                        0.62f to Color.Black.copy(alpha = 0.25f),
                        1f to Color.Black.copy(alpha = 0.6f),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.55f to Color.Transparent)),
        )
        // 4. The story
        AnimatedContent(
            feature.headline,
            transitionSpec = { fadeIn(tween(350, delayMillis = 80)) togetherWith fadeOut(tween(200)) },
            label = "story",
            modifier = Modifier.padding(start = Side, top = 112.dp).widthIn(max = 520.dp),
        ) { h -> Story(h) }
    }
}

@Composable
private fun Story(h: Headline) {
    Column(Modifier.height(224.dp), verticalArrangement = Arrangement.Bottom) {
        if (h.kicker.isNotBlank()) {
            Text(
                h.kicker,
                style = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.72f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            h.title,
            style = TextStyle(fontFamily = Sans, fontSize = if (h.title.length > 22) 40.sp else 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = Color.White, lineHeight = if (h.title.length > 22) 44.sp else 56.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (h.deck.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                h.deck,
                style = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.78f)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        h.progress?.let {
            Spacer(Modifier.height(12.dp))
            GlassProgress(it, Modifier.width(180.dp))
        }
    }
}

/** Match lead: both crests large in the top right, home side first, on a glass plate. */
@Composable
private fun MatchCrests(vm: LauncherViewModel) {
    val m by vm.match.collectAsStateWithLifecycle()
    val s = m ?: return
    val us = MatchConfig.Team.espnId
    val (home, away) = if (s.home) us to s.opponentId else s.opponentId to us
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 112.dp, end = Side)
                .glass(RoundedCornerShape(28.dp), fill = 0.12f)
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamLogo(home, 96.dp)
            Text("–", style = TextStyle(fontFamily = Sans, fontSize = 34.sp, fontWeight = FontWeight.Light, color = Color.White.copy(alpha = 0.7f)))
            TeamLogo(away, 96.dp)
        }
    }
}

// --- The glass shelf -----------------------------------------------------------------------------

@Composable
private fun Shelf(
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
) {
    val keys = rowKeys(favorites, watch, forYou)
    val active = keys.indexOf(rows.activeKey).coerceAtLeast(0)
    val shift by animateDpAsState(
        -RowPitch * active,
        spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
        label = "shelfShift",
    )
    Box(
        Modifier
            .offset(y = ShelfTop)
            .padding(horizontal = 28.dp)
            .fillMaxWidth()
            .height(ShelfHeight)
            .glass(RoundedCornerShape(30.dp))
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
        ) {
            for (key in keys) {
                val entry = Modifier
                    .focusRequester(rows.requester(key))
                    .onFocusChanged { if (it.hasFocus) rows.activeKey = key }
                val title = when (key) {
                    ROW_FAVORITES -> t("Favoriler")
                    ROW_WATCH -> t("İzlemeye devam et")
                    ROW_FOR_YOU -> t("Senin için")
                    else -> t("Tüm uygulamalar")
                }
                ShelfRow(title, keys.indexOf(key), keys.size) {
                    when (key) {
                        ROW_FAVORITES -> FavoritesRow(favorites, vm, movingFavorite, rows.listState(key), entry, focus, actions)
                        ROW_WATCH -> WatchRow(watch, vm, rows.listState(key), entry, focus, actions)
                        ROW_FOR_YOU -> WatchRow(forYou, vm, rows.listState(key), entry, focus, actions)
                        ROW_APPS -> AppsRow(apps, vm, moving, rows.listState(key), entry, focus, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(title: String, index: Int, count: Int, row: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().height(RowPitch).padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TextStyle(fontFamily = Sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f)))
            Spacer(Modifier.weight(1f))
            // Page dots: which shelf row this is
            if (count > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(count) { i ->
                        Box(
                            Modifier
                                .size(if (i == index) 7.dp else 6.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (i == index) 0.9f else 0.3f)),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        row()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.shelfRow(entry: Modifier) = fillMaxWidth()
    .height(GlassCardH + CardPad * 2)
    .then(entry)
    .focusRestorer()

private val RowPadding = PaddingValues(horizontal = 28.dp, vertical = CardPad)

@Composable
private fun AppsRow(
    apps: List<AppEntry>,
    vm: LauncherViewModel,
    moving: String?,
    state: LazyListState,
    entry: Modifier,
    focus: MutableState<Focus?>,
    actions: HomeActions,
) {
    LazyRow(state = state, modifier = Modifier.shelfRow(entry), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
            AppTile(
                app, vm,
                moving = moving == app.pkg,
                index = index,
                modifier = Modifier.animateItem(),
                onFocused = { focus.value = Focus.App(app) },
                onClick = { vm.launch(app) },
                onLongClick = { actions.onAppMenu(app) },
            )
        }
    }
}

@Composable
private fun FavoritesRow(
    cards: List<FavoriteCard>,
    vm: LauncherViewModel,
    moving: String?,
    state: LazyListState,
    entry: Modifier,
    focus: MutableState<Focus?>,
    actions: HomeActions,
) {
    LazyRow(state = state, modifier = Modifier.shelfRow(entry), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        itemsIndexed(cards, key = { _, c -> c.key }) { index, card ->
            when (card) {
                is FavoriteCard.App -> AppTile(
                    card.app, vm,
                    moving = moving == card.key,
                    index = index,
                    modifier = Modifier.animateItem(),
                    onFocused = { focus.value = Focus.App(card.app) },
                    onClick = { vm.launchPinned(card) },
                    onLongClick = { actions.onFavoriteMenu(card) },
                )
                is FavoriteCard.Content -> ContentTile(
                    card.item, vm,
                    modifier = Modifier.animateItem(),
                    pinned = card,
                    moving = moving == card.key,
                    index = index,
                    onFocused = { focus.value = Focus.Watch(card.item, card.app) },
                    onClick = { vm.launchPinned(card) },
                    onLongClick = { actions.onFavoriteMenu(card) },
                )
            }
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
    LazyRow(state = state, modifier = Modifier.shelfRow(entry), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(items, key = { it.id }) { item ->
            ContentTile(
                item, vm,
                onFocused = { focus.value = Focus.Watch(item, vm.appFor(item.pkg)) },
                onClick = { vm.launchWatch(item) },
                onLongClick = { actions.onContentMenu(item) },
            )
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
    GlassCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .bringIntoViewRequester(bring)
            .then(if (moving) Modifier.onPreviewKeyEvent { moveKeys(it, vm) } else Modifier),
        forceBig = moving,
        onFocused = onFocused,
    ) {
        val a = art
        when {
            a == null -> {}
            a.isBanner -> Image(a.image, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> {
                // Phone apps: the icon on frosted glass with the name under it, like tvOS
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(a.cardTop.copy(alpha = 0.55f), a.cardBottom.copy(alpha = 0.35f)))))
                Image(a.image, null, Modifier.align(Alignment.Center).padding(bottom = 14.dp).size(38.dp))
                CardCaption {
                    Text(
                        app.label,
                        style = TextStyle(fontFamily = Sans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (moving) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Text("◀   ▶", style = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White))
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
    pinned: FavoriteCard.Content? = null,
    moving: Boolean = false,
    index: Int = 0,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bring = remember { BringIntoViewRequester() }
    if (moving) LaunchedEffect(index) { bring.bringIntoView() }
    val thumb by produceState<AppArt?>(null, item.id, item.imageUri) {
        value = if (pinned != null) vm.favoriteArt(pinned) else vm.watchArt(item)
    }
    val app = remember(item.pkg) { vm.appFor(item.pkg) }
    val appArt by produceState<AppArt?>(null, item.pkg) { value = app?.let { vm.art(it) } }
    GlassCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .bringIntoViewRequester(bring)
            .then(if (moving) Modifier.onPreviewKeyEvent { moveKeys(it, vm) } else Modifier),
        forceBig = moving,
        onFocused = onFocused,
    ) {
        val th = thumb
        if (th != null) {
            Image(th.image, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            appArt?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.5f) }
            CardCaption {
                Text(
                    item.title,
                    style = TextStyle(fontFamily = Sans, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item.progress?.let {
            GlassProgress(it, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(), height = 3.dp)
        }
        if (moving) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Text("◀   ▶", style = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White))
            }
        }
    }
}
