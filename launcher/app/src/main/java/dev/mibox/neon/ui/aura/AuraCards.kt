package dev.mibox.neon.ui.aura

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import dev.mibox.neon.ui.AuraPalette
import dev.mibox.neon.ui.LumenSans
import dev.mibox.neon.ui.moveKeys
import dev.mibox.neon.ui.tame

private val A = AuraPalette

// One size for every card: 16:9 like Android TV banners
internal val CardW = 156.dp
internal val CardH = 88.dp
/** Room above and below the cards for the lift and the glow: rows clip to their bounds. */
internal val CardPad = 27.dp
internal val CardGap = 18.dp
private val CardShape = RoundedCornerShape(12.dp)
private const val FOCUS_SCALE = 1.12f

/**
 * Glass's card (spring lift, one sheen across, a glare while focused) whose shadow is a glow in
 * its own color. On focus it hands that color and its position to [aura]; while it keeps focus
 * and the row scrolls, the position follows.
 */
@Composable
internal fun AuraCard(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    glow: Color,
    aura: AuraLight,
    moving: Boolean = false,
    onFocused: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focusedState = interaction.collectIsFocusedAsState()
    val focused = focusedState.value
    val big = focused || moving
    val scale by animateFloatAsState(
        if (big) FOCUS_SCALE else 1f,
        spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "auraScale",
    )
    val sheen = remember { Animatable(-1f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(-0.4f)
            sheen.animateTo(1.4f, tween(850))
        } else {
            sheen.snapTo(-1f)
        }
    }
    // The art's color usually arrives after the card: pass it on if the card already has focus
    val currentGlow by rememberUpdatedState(glow)
    LaunchedEffect(glow) { if (focusedState.value) aura.color = glow }
    val centerX = remember { floatArrayOf(Float.NaN) }

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .zIndex(if (big) 1f else 0f)
            .size(CardW, CardH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onGloballyPositioned { c ->
                centerX[0] = c.boundsInRoot().center.x
                if (focusedState.value) aura.x = centerX[0]
            }
            .onFocusChanged {
                if (it.isFocused) {
                    onFocused()
                    aura.color = currentGlow
                    if (!centerX[0].isNaN()) aura.x = centerX[0]
                }
            },
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = A.Card,
            focusedContainerColor = A.Card,
            pressedContainerColor = A.Card,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(glow.copy(alpha = 0.85f), 22.dp)),
        interactionSource = interaction,
    ) {
        content()
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val glare = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.75f,
                    )
                    val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)))
                    val r = CornerRadius(12.dp.toPx())
                    val hair = Stroke(1.dp.toPx())
                    onDrawBehind {
                        if (big) drawRect(glare) else drawRoundRect(rim, cornerRadius = r, style = hair)
                        val s = sheen.value
                        if (s > -0.5f && s < 1.5f) {
                            val x = s * size.width
                            drawRect(
                                Brush.linearGradient(
                                    0f to Color.Transparent,
                                    0.5f to Color.White.copy(alpha = 0.3f),
                                    1f to Color.Transparent,
                                    start = Offset(x - size.width * 0.35f, 0f),
                                    end = Offset(x + size.width * 0.15f, size.height),
                                ),
                            )
                        }
                    }
                },
        )
        if (moving) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text("◀   ▶", style = TextStyle(fontFamily = LumenSans, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AppTile(
    app: AppEntry,
    vm: LauncherViewModel,
    aura: AuraLight,
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
    AuraCard(
        onClick, onLongClick,
        modifier = modifier
            .bringIntoViewRequester(bring)
            .then(if (moving) Modifier.onPreviewKeyEvent { moveKeys(it, vm) } else Modifier),
        glow = art?.let { tame(it.vibrant) } ?: A.Glow,
        aura = aura,
        moving = moving,
        onFocused = onFocused,
    ) {
        val a = art
        when {
            a == null -> {}
            a.isBanner -> Image(a.image, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> {
                // Phone apps: the icon on its own colors with the name under it
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(a.cardTop.copy(alpha = 0.6f), a.cardBottom.copy(alpha = 0.4f)))))
                Image(a.image, null, Modifier.align(Alignment.Center).padding(bottom = 14.dp).size(36.dp))
                Text(
                    app.label,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 8.dp, bottom = 7.dp),
                    style = TextStyle(fontFamily = LumenSans, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ContentTile(
    item: WatchItem,
    vm: LauncherViewModel,
    aura: AuraLight,
    modifier: Modifier = Modifier,
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
    AuraCard(
        onClick, onLongClick,
        modifier = modifier
            .bringIntoViewRequester(bring)
            .then(if (moving) Modifier.onPreviewKeyEvent { moveKeys(it, vm) } else Modifier),
        glow = (thumb ?: appArt)?.let { tame(it.vibrant) } ?: A.Glow,
        aura = aura,
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
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 8.dp, bottom = 8.dp),
                style = TextStyle(fontFamily = LumenSans, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.progress?.let { p ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 9.dp, vertical = 7.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.03f, 1f)).background(Color.White))
            }
        }
    }
}
