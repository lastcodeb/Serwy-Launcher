package dev.mibox.neon.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.WatchItem

/** What has focus in the rows; drives the hero, the backdrop glow and the footer. */
@Immutable
sealed interface Focus {
    val app: AppEntry?
    val label: String

    @Immutable
    data class App(override val app: AppEntry) : Focus {
        override val label get() = app.label
    }

    @Immutable
    data class Watch(val item: WatchItem, override val app: AppEntry?) : Focus {
        override val label get() = item.title
    }
}

const val ROW_FAVORITES = "fav"
const val ROW_WATCH = "watch"
const val ROW_FOR_YOU = "foryou"
const val ROW_APPS = "apps"

/** Rows top to bottom; empty ones are left out. */
fun rowKeys(favorites: List<FavoriteCard>, watch: List<WatchItem>, forYou: List<WatchItem>): List<String> = buildList {
    if (favorites.isNotEmpty()) add(ROW_FAVORITES)
    if (watch.isNotEmpty()) add(ROW_WATCH)
    if (forYou.isNotEmpty()) add(ROW_FOR_YOU)
    add(ROW_APPS)
}

/**
 * One FocusRequester and scroll state per row, plus which row is on screen. Up/down between rows
 * goes through here instead of Compose's geometric search, which got lost with rows slid away.
 */
@Stable
class RowsController {
    private val requesters = HashMap<String, FocusRequester>()
    private val states = HashMap<String, LazyListState>()
    var activeKey by mutableStateOf<String?>(null)

    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    fun listState(key: String) = states.getOrPut(key) { LazyListState() }
    fun focus(key: String): Boolean = runCatching { requester(key).requestFocus() }.isSuccess

    suspend fun scrollAllToStart() = states.values.forEach { it.scrollToItem(0) }
}

// 16:9 like Android TV banners, so the app's own logo fills the card exactly. The focused card
// scales by the SVG's 230/200 ratio in the draw phase only: neighbours never get pushed around.
private val CardW = 128.dp
private val CardH = 72.dp
private const val FOCUS_SCALE = 1.15f
private val CardShape = RoundedCornerShape(11.dp)
private val CardGap = 18.dp

/** Card size and spacing of one row; favorites use a 30% smaller, tighter variant. */
@Immutable
private data class CardSpec(val w: Dp, val h: Dp, val gap: Dp)

private val Regular = CardSpec(CardW, CardH, CardGap)
private val Small = CardSpec(CardW * 0.7f, CardH * 0.7f, 10.dp)
private const val GLOW_PAD = 12 // dp around a row so focus glows and the scale are not clipped

// The rows area: top of the first header to just above the footer
private val AreaTop = 375.dp
private val AreaHeight = 137.dp
private val RowPitch = 135.dp
private val HeaderBaseline = 28.5.dp
private val RowTop = 38.5.dp // idle card bottoms land on the design's y995px

/** Favoriler, İzlemeye devam et, Uygulamalar: one row on screen at a time, sliding on a spring. */
@Composable
fun RowsArea(
    vm: LauncherViewModel,
    favorites: List<FavoriteCard>,
    apps: List<AppEntry>,
    watch: List<WatchItem>,
    forYou: List<WatchItem>,
    moving: String?,
    rows: RowsController,
    focus: MutableState<Focus?>,
    onMenu: (AppEntry) -> Unit,
    onContentMenu: (WatchItem) -> Unit,
) {
    val keys = rowKeys(favorites, watch, forYou)
    val active = keys.indexOf(rows.activeKey).coerceAtLeast(0)
    val shift = animateDpAsState(
        -RowPitch * active,
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "rowsShift",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .offset(y = AreaTop)
            .height(AreaHeight)
            .clipToBounds()
            .onPreviewKeyEvent { e ->
                // A card being moved owns the D-pad
                if (moving != null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    // From the top row, Up falls through to the top bar
                    Key.DirectionUp -> active > 0 && rows.focus(keys[active - 1])
                    Key.DirectionDown -> active >= keys.lastIndex || rows.focus(keys[active + 1])
                    else -> false
                }
            },
    ) {
        // Every row keeps its full height; the area clips the ones slid away
        Column(
            Modifier
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = shift.value.toPx() },
        ) {
            for (key in keys) {
                val rowModifier = Modifier
                    .focusRequester(rows.requester(key))
                    .onFocusChanged { if (it.hasFocus) rows.activeKey = key }
                when (key) {
                    ROW_FAVORITES -> RowSection("Favoriler", favorites.size, Small) {
                        FavoritesRow(
                            favorites, vm, rows.listState(key), rowModifier,
                            onFocused = { focus.value = it },
                            onMenu = onMenu,
                            onContentMenu = onContentMenu,
                        )
                    }
                    ROW_WATCH -> RowSection("İzlemeye devam et", watch.size) {
                        WatchRow(
                            watch, vm, rows.listState(key), rowModifier,
                            onFocused = { item -> focus.value = Focus.Watch(item, vm.appFor(item.pkg)) },
                            onLongClick = onContentMenu,
                        )
                    }
                    ROW_FOR_YOU -> RowSection("Senin için", forYou.size) {
                        WatchRow(
                            forYou, vm, rows.listState(key), rowModifier,
                            onFocused = { item -> focus.value = Focus.Watch(item, vm.appFor(item.pkg)) },
                            onLongClick = onContentMenu,
                        )
                    }
                    else -> RowSection("Tüm uygulamalar", apps.size) {
                        AppsRow(
                            apps, vm, moving, rows.listState(key), rowModifier,
                            onFocused = { focus.value = Focus.App(it) },
                            onMenu = onMenu,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowSection(title: String, count: Int, spec: CardSpec = Regular, row: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(RowPitch)) {
        Row(Modifier.padding(start = 48.dp).baselineAt(HeaderBaseline), verticalAlignment = Alignment.Bottom) {
            Text(title, style = TextStyle(fontFamily = Poppins, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                style = TextStyle(fontFamily = Poppins, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Neon.TextMuted),
            )
        }
        // Smaller cards sit on the same bottom line as the regular rows
        Box(Modifier.offset(y = RowTop + (CardH - spec.h))) { row() }
    }
}

// [entry] carries the row's FocusRequester; the restorer comes after it so requesting the row
// lands on the card that was focused last
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.cardRow(entry: Modifier, spec: CardSpec = Regular) = fillMaxWidth()
    .height(spec.h + (2 * GLOW_PAD).dp)
    .then(entry)
    .focusRestorer()

@Composable
private fun AppsRow(
    apps: List<AppEntry>,
    vm: LauncherViewModel,
    moving: String?,
    state: LazyListState,
    modifier: Modifier,
    spec: CardSpec = Regular,
    onFocused: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier.cardRow(modifier, spec),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = GLOW_PAD.dp),
        horizontalArrangement = Arrangement.spacedBy(spec.gap),
    ) {
        itemsIndexed(apps, key = { _, app -> app.pkg }) { index, app ->
            AppCard(
                app = app,
                index = index,
                moving = moving == app.pkg,
                vm = vm,
                spec = spec,
                modifier = Modifier.animateItem(),
                onFocused = { onFocused(app) },
                onClick = { vm.launch(app) },
                onLongClick = { onMenu(app) },
            )
        }
    }
}

/** Pinned apps and pinned content (episodes, videos) in the order they were added. */
@Composable
private fun FavoritesRow(
    cards: List<FavoriteCard>,
    vm: LauncherViewModel,
    state: LazyListState,
    modifier: Modifier,
    onFocused: (Focus) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onContentMenu: (WatchItem) -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier.cardRow(modifier, Small),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = GLOW_PAD.dp),
        horizontalArrangement = Arrangement.spacedBy(Small.gap),
    ) {
        items(cards, key = { it.key }) { card ->
            when (card) {
                is FavoriteCard.App -> AppCard(
                    app = card.app,
                    index = 0,
                    moving = false,
                    vm = vm,
                    spec = Small,
                    modifier = Modifier.animateItem(),
                    onFocused = { onFocused(Focus.App(card.app)) },
                    onClick = { vm.launchPinned(card) },
                    onLongClick = { onMenu(card.app) },
                )
                is FavoriteCard.Content -> WatchCard(
                    item = card.item,
                    vm = vm,
                    onFocused = { onFocused(Focus.Watch(card.item, card.app)) },
                    onClick = { vm.launchPinned(card) },
                    onLongClick = { onContentMenu(card.item) },
                    modifier = Modifier.animateItem(),
                    spec = Small,
                    pinned = card,
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
    modifier: Modifier,
    onFocused: (WatchItem) -> Unit,
    onLongClick: (WatchItem) -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier.cardRow(modifier),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = GLOW_PAD.dp),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        items(items, key = { it.id }) { item ->
            WatchCard(
                item = item,
                vm = vm,
                onFocused = { onFocused(item) },
                onClick = { vm.launchWatch(item) },
                onLongClick = { onLongClick(item) },
            )
        }
    }
}

/**
 * Shared card chrome: white border and a glow in the card's own color on focus, and a springy
 * scale applied in the graphics layer so it costs no layout and never shifts the row.
 */
@Composable
private fun CardFrame(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    color: Color,
    glow: Color,
    modifier: Modifier,
    spec: CardSpec = Regular,
    forceBig: Boolean = false,
    onFocused: () -> Unit,
    content: @Composable BoxScope.(big: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val big = focused || forceBig
    val scale = animateFloatAsState(
        if (big) FOCUS_SCALE else 1f,
        spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "cardScale",
    )
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .zIndex(if (big) 1f else 0f)
            .size(spec.w, spec.h)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color,
            focusedContainerColor = color,
            pressedContainerColor = color,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.75.dp, Color.White.copy(alpha = 0.85f)), shape = CardShape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(glow.copy(alpha = 0.6f), 14.dp)),
        interactionSource = interaction,
    ) {
        content(big)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(
    app: AppEntry,
    index: Int,
    moving: Boolean,
    vm: LauncherViewModel,
    spec: CardSpec,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val art by produceState<AppArt?>(null, app.pkg) { value = vm.art(app) }
    val bringIntoView = remember { BringIntoViewRequester() }
    if (moving) {
        LaunchedEffect(index) { bringIntoView.bringIntoView() }
    }
    val a = art
    CardFrame(
        onClick = onClick,
        onLongClick = onLongClick,
        color = a?.cardTop ?: Neon.Bg3,
        glow = a?.vibrant ?: Neon.Pink,
        modifier = modifier
            .bringIntoViewRequester(bringIntoView)
            .then(if (moving) Modifier.onPreviewKeyEvent { moveKeys(it, vm) } else Modifier),
        spec = spec,
        forceBig = moving,
        onFocused = onFocused,
    ) { big ->
        when {
            a == null -> {}
            a.isBanner -> Image(
                bitmap = a.image,
                contentDescription = app.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            else -> {
                Image(
                    a.image,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(30.dp),
                )
                Text(
                    app.label,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 8.dp, bottom = 8.dp),
                    style = TextStyle(
                        fontFamily = Poppins,
                        fontSize = 12.5.sp,
                        fontWeight = if (big) FontWeight.ExtraBold else FontWeight.Bold,
                        color = Color.White,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (moving) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .border(2.dp, Neon.Pink, CardShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("◀   ▶", style = TextStyle(fontFamily = Poppins, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White))
            }
        }
    }
}

/**
 * The app's own thumbnail; without one, the app's banner with the title over it. A [pinned]
 * card (Favoriler) loads its saved poster and falls back to the app banner on its own.
 */
@Composable
private fun WatchCard(
    item: WatchItem,
    vm: LauncherViewModel,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    spec: CardSpec = Regular,
    pinned: FavoriteCard.Content? = null,
) {
    val thumb by produceState<AppArt?>(null, item.id, item.imageUri) {
        value = if (pinned != null) vm.favoriteArt(pinned) else vm.watchArt(item)
    }
    val app = remember(item.pkg) { vm.appFor(item.pkg) }
    val appArt by produceState<AppArt?>(null, item.pkg) { value = app?.let { vm.art(it) } }
    val t = thumb
    CardFrame(
        onClick = onClick,
        onLongClick = onLongClick,
        color = Neon.Bg3,
        glow = t?.vibrant ?: appArt?.vibrant ?: Neon.Pink,
        modifier = modifier,
        spec = spec,
        onFocused = onFocused,
    ) {
        if (t != null) {
            Image(t.image, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            appArt?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f) }
            Text(
                item.title,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 6.dp, bottom = 8.dp),
                style = TextStyle(
                    fontFamily = Poppins,
                    fontSize = if (spec.h < CardH) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Marks pinned content so it reads differently from the pinned apps beside it
        if (pinned != null) {
            Text(
                "★",
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 5.dp),
                style = TextStyle(fontSize = 9.sp, color = Neon.Amber),
            )
        }
        item.progress?.let { p ->
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceAtLeast(0.03f)).background(Neon.Pink))
            }
        }
    }
}

/** y1032px (516dp): just the name of what has focus; while moving, how to move it. */
@Composable
fun FooterLabel(focus: State<Focus?>, moving: Boolean) {
    val label = (if (moving) "◀ ▶ taşı  ·  OK bırak" else focus.value?.label) ?: return
    Text(
        label,
        modifier = Modifier.padding(horizontal = 48.dp).baselineAt(516.dp),
        style = TextStyle(fontFamily = Poppins, fontSize = 11.sp, color = if (moving) Neon.Pink else Neon.TextDim),
        maxLines = 1,
    )
}

/** While a card is being moved it swallows the D-pad: left/right swap, OK drops it. */
private fun moveKeys(e: KeyEvent, vm: LauncherViewModel): Boolean = when (e.key) {
    Key.DirectionLeft -> {
        if (e.type == KeyEventType.KeyDown) vm.move(-1)
        true
    }
    Key.DirectionRight -> {
        if (e.type == KeyEventType.KeyDown) vm.move(1)
        true
    }
    Key.DirectionUp, Key.DirectionDown -> true
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
        if (e.type == KeyEventType.KeyUp) vm.finishMove()
        true
    }
    else -> false
}
