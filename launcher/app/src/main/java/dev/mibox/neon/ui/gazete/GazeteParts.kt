package dev.mibox.neon.ui.gazete

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.ui.HomeStyle
import dev.mibox.neon.ui.LocalHomeStyle
import dev.mibox.neon.ui.NeonSurface
import dev.mibox.neon.ui.TextRole
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// --- Focus keys --------------------------------------------------------------------------------
// Every focusable of the home owns one FocusRequester, looked up by these keys in GazeteNav.

internal const val K_CLOCK = "clock"
internal const val K_WEATHER = "weather"
internal const val K_TOP_TEMA = "top:tema"
internal const val K_TOP_DIZIN = "top:dizin"
internal const val K_MAN = "man"
internal const val K_SPOR = "spor"
internal const val K_LIGHT = "light"
internal const val K_SPEED = "speed"
internal const val K_RAM = "ram"
internal const val K_GEAR = "gear"
internal const val K_FAV_DIZIN = "fav:dizin"
internal const val K_BACK = "dizin:back"

/** A deck at least this long wraps to two lines or more and earns a drop cap. */
internal const val DROP_CAP_MIN = 70

internal fun watchKey(item: WatchItem) = "w:${item.id}"
internal fun editorKey(item: WatchItem) = "e:${item.id}"
internal fun favKey(card: FavoriteCard) = "f:${card.key}"
internal fun dizinKey(app: AppEntry) = "d:${app.component.flattenToShortString()}"

// --- Focus bookkeeping ---------------------------------------------------------------------------

/** What the manşet tells while a card or app has focus (idle: the VM's own headline). */
internal sealed interface GazeteStory {
    @Immutable
    data class Item(val item: WatchItem) : GazeteStory

    @Immutable
    data class Application(val app: AppEntry) : GazeteStory
}

/**
 * Plain state holder for the home: the requesters by key, where focus last was, and the one
 * state ([story]) that the manşet and the room light read. Cards write it on focus, so a focus
 * change recomposes only those two readers, never the page.
 */
@Stable
internal class GazeteNav {
    val story = mutableStateOf<GazeteStory?>(null)
    val favList = LazyListState()

    var last: String? = null
    var lastIndex: Int = 0
    var anyFocus: Boolean = false

    private val requesters = HashMap<String, FocusRequester>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** False while the node is not composed yet (requestFocus throws until then). */
    fun focus(key: String): Boolean = runCatching { requester(key).requestFocus() }.isSuccess

    fun note(key: String, index: Int, story: GazeteStory?, claims: Boolean) {
        last = key
        lastIndex = index
        if (claims) this.story.value = story
    }

    /** The manşet button first, then the first card of each section, then the masthead. */
    fun initialKeys(vm: LauncherViewModel): List<String> = listOfNotNull(
        K_MAN,
        vm.watchNext.value.firstOrNull()?.let(::watchKey),
        vm.forYou.value.firstOrNull()?.let(::editorKey),
        vm.favoriteCards.value.firstOrNull()?.let(::favKey),
        K_SPOR,
        K_CLOCK,
    )

    /** Nodes appear a frame or two after their data: keep asking until one of [keys] takes focus. */
    suspend fun focusWhenReady(keys: List<String>, attempts: Int = 40): Boolean {
        repeat(attempts) {
            for (k in keys) {
                if (focus(k)) {
                    delay(40)
                    if (anyFocus) return true
                }
            }
            delay(40)
        }
        return false
    }
}

// --- Text roles --------------------------------------------------------------------------------

/** Barlow Condensed: kickers, section labels, bylines. */
internal fun HomeStyle.lab(size: Float, weight: FontWeight = FontWeight.Bold): TextStyle =
    type(size.sp, weight, role = TextRole.LABEL)

/** Source Serif: running text. */
internal fun HomeStyle.serif(size: Float, weight: FontWeight? = null): TextStyle =
    type(size.sp, weight, role = TextRole.BODY)

/** Playfair Bold (headlines, names); [italic] for the editor's notes. */
internal fun HomeStyle.play(size: Float, italic: Boolean = false): TextStyle =
    type(size.sp, FontWeight.Bold, role = TextRole.HEADLINE).let { if (italic) it.copy(fontStyle = FontStyle.Italic) else it }

/** Playfair Black: the big numbers and the lead. */
internal fun HomeStyle.playBlack(size: Float, italic: Boolean = false): TextStyle =
    type(size.sp, FontWeight.Black, role = TextRole.DISPLAY).let { if (italic) it.copy(fontStyle = FontStyle.Italic) else it }

// --- Paper, rules, arrows ------------------------------------------------------------------------

/**
 * The concept's faint dot grain (a dot every 3dp). One tiny bitmap tile is built once per size
 * and repeated by the GPU; the layer keeps it from being re-recorded whenever focus redraws
 * something else.
 */
internal fun Modifier.paperGrain(dot: Color): Modifier = graphicsLayer { }.drawWithCache {
    val tile = 3.dp.toPx().roundToInt().coerceAtLeast(2)
    val bitmap = ImageBitmap(tile, tile)
    val paint = Paint().apply {
        color = dot
        isAntiAlias = true
    }
    Canvas(bitmap).drawCircle(Offset(tile / 2f, tile / 2f), tile * 0.17f, paint)
    val brush = ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind { drawRect(brush) }
}

/** Red rule under a focused item, drawn just outside its bounds from the focus state. */
internal fun Modifier.penUnderline(focused: State<Boolean>, color: Color): Modifier = drawBehind {
    if (focused.value) {
        drawRect(color, Offset(0f, size.height + 1.dp.toPx()), Size(size.width, 2.dp.toPx()))
    }
}

@Composable
internal fun HairRule(modifier: Modifier = Modifier) {
    val st = LocalHomeStyle.current
    Box(modifier.fillMaxWidth().height(0.75.dp).background(st.divider))
}

/** The concept's "4px double" rule: two hairlines with a gap. */
@Composable
internal fun DoubleRule(modifier: Modifier = Modifier) {
    val st = LocalHomeStyle.current
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.75.dp).background(st.divider))
        Spacer(Modifier.height(1.5.dp))
        Box(Modifier.fillMaxWidth().height(0.75.dp).background(st.divider))
    }
}

@Composable
internal fun VRule(modifier: Modifier = Modifier) {
    val st = LocalHomeStyle.current
    Box(modifier.width(0.75.dp).fillMaxHeight().background(st.divider))
}

/** A line with an arrowhead, drawn as a path (Barlow has no arrows). */
@Composable
internal fun Arrow(color: Color, modifier: Modifier = Modifier, left: Boolean = false, width: Dp = 13.dp, height: Dp = 8.dp) {
    Spacer(
        modifier.size(width, height).drawWithCache {
            val w = size.width
            val h = size.height
            val head = h * 0.62f
            val path = Path().apply {
                moveTo(0f, h / 2f)
                lineTo(w, h / 2f)
                if (left) {
                    moveTo(head, 0f)
                    lineTo(0f, h / 2f)
                    lineTo(head, h)
                } else {
                    moveTo(w - head, 0f)
                    lineTo(w, h / 2f)
                    lineTo(w - head, h)
                }
            }
            val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind { drawPath(path, color, style = stroke) }
        },
    )
}

// --- Building blocks ---------------------------------------------------------------------------

/** Red caps title over a hairline: the head of every column. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    val st = LocalHomeStyle.current
    Column(modifier.fillMaxWidth()) {
        Text(st.caps(title), style = st.lab(12f), color = st.accent, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        HairRule()
    }
}

/** The short italic line that stands in for an empty section. */
@Composable
internal fun EmptyLine(text: String, modifier: Modifier = Modifier) {
    val st = LocalHomeStyle.current
    Text(text, modifier = modifier.padding(vertical = 4.dp), style = st.play(14f, italic = true), color = st.textMuted)
}

/** [fraction] of a thin ink bar over its track. */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val st = LocalHomeStyle.current
    Box(modifier.fillMaxWidth().height(3.dp).background(st.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(st.text))
    }
}

/**
 * Text that shrinks through [sizes] (sp, descending) until it fits [maxLines] without being cut;
 * the last step falls back to an ellipsis. Restarts at the first size when [text] changes.
 */
@Composable
internal fun FitText(
    text: String,
    style: TextStyle,
    sizes: List<Float>,
    maxLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    var step by remember(text, sizes) { mutableIntStateOf(0) }
    val size = sizes[step.coerceIn(0, sizes.lastIndex)]
    Text(
        text,
        modifier = modifier,
        color = color,
        style = style.copy(fontSize = size.sp, lineHeight = (size * 1.05f).sp),
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if ((it.didOverflowWidth || it.didOverflowHeight) && step < sizes.lastIndex) step++ },
    )
}

/**
 * A deck set as running text. Only a deck long enough to wrap onto two or more lines gets the
 * red Playfair capital (about two lines tall, text beside it); a short one is plain text.
 */
@Composable
internal fun DropCapDeck(text: String, size: Float, maxLines: Int, modifier: Modifier = Modifier) {
    val st = LocalHomeStyle.current
    if (text.length < DROP_CAP_MIN) {
        Text(
            text,
            modifier = modifier.fillMaxWidth(),
            style = st.serif(size).copy(lineHeight = (size * 1.35f).sp),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Row(modifier.fillMaxWidth()) {
        Text(
            text.take(1),
            style = st.playBlack(size * 2.9f).copy(lineHeight = (size * 2.7f).sp),
            color = st.accent,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text.drop(1),
            modifier = Modifier.weight(1f),
            style = st.serif(size).copy(lineHeight = (size * 1.35f).sp),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The one focusable the whole home is built from: a transparent themed surface with no outline
 * of its own. Focus shows as the red pen loop (from NeonSurface) plus a red underline, so it does
 * not rely on color alone. [story] is what the manşet and the room light follow while this has
 * focus; [claimsStory] = false (the manşet button itself) keeps the current story. [fill] makes
 * a solid block (the inked buttons), [outline] a 1dp ink frame (favorite cards).
 */
@Composable
internal fun GazeteItem(
    nav: GazeteNav,
    key: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    story: GazeteStory? = null,
    claimsStory: Boolean = true,
    index: Int = 0,
    fill: Color? = null,
    outline: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val st = LocalHomeStyle.current
    val src = remember { MutableInteractionSource() }
    val focused = src.collectIsFocusedAsState()
    NeonSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .penUnderline(focused, st.focus)
            .focusRequester(nav.requester(key))
            .onFocusChanged { if (it.hasFocus) nav.note(key, index, story, claimsStory) },
        container = fill,
        focusedContainer = fill,
        stroke = if (outline) st.text else Color.Transparent,
        strokeWidth = 1.dp,
        interactionSource = src,
        content = content,
    )
}

internal enum class ArrowDir { NONE, LEFT, RIGHT }

/** A focusable underlined caps link of the top strip ("Dizin →", "← Ana sayfa", "Tema"). */
@Composable
internal fun GazeteLink(
    nav: GazeteNav,
    key: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    arrow: ArrowDir = ArrowDir.NONE,
    color: Color? = null,
) {
    val st = LocalHomeStyle.current
    val c = color ?: st.text
    GazeteItem(nav, key, onClick, modifier, claimsStory = true) {
        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (arrow == ArrowDir.LEFT) Arrow(c, left = true)
            Text(
                st.caps(label),
                style = st.lab(13f, FontWeight.SemiBold),
                color = c,
                textDecoration = TextDecoration.Underline,
                maxLines = 1,
            )
            if (arrow == ArrowDir.RIGHT) Arrow(c)
        }
    }
}

// --- Small formatting helpers --------------------------------------------------------------------

internal fun greetingFor(hour: Int): String = when (hour) {
    in 5..10 -> "Günaydın"
    in 11..17 -> "İyi günler"
    in 18..21 -> "İyi akşamlar"
    else -> "İyi geceler"
}

/** "2. Sezon 5. Bölüm", "5. Bölüm", else the episode title or the app's subtitle; null for movies. */
internal fun episodeLine(item: WatchItem): String? {
    fun num(s: String?) = s?.trim()?.toIntOrNull()
    val s = num(item.season)
    val e = num(item.episode)
    return when {
        s != null && e != null -> "$s. Sezon $e. Bölüm"
        e != null -> "$e. Bölüm"
        else -> item.episodeTitle?.takeIf { it.isNotBlank() } ?: item.subtitle?.takeIf { it.isNotBlank() }
    }
}

/** Whole percent watched, or null when unknown or zero. */
internal fun percentOf(item: WatchItem): Int? =
    item.progress?.let { (it * 100).roundToInt().coerceIn(0, 100) }?.takeIf { it > 0 }
