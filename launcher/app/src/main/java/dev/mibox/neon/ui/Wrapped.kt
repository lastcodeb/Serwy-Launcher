package dev.mibox.neon.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppUsage
import dev.mibox.neon.data.PersonaKind
import dev.mibox.neon.data.WrappedSummary
import kotlinx.coroutines.delay
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private sealed interface WrappedLoad {
    data object Loading : WrappedLoad
    data object Empty : WrappedLoad
    data class Ready(val summary: WrappedSummary) : WrappedLoad
}

private enum class Slide { Intro, Apps, Peak, Records, Persona, Change }

private data class SlideKey(val month: YearMonth, val index: Int)

/** Tiles that have no data (system-total months) are left out, so a story never shows empty pages. */
private fun slidesFor(s: WrappedSummary): List<Slide> = buildList {
    add(Slide.Intro)
    if (s.topApps.isNotEmpty()) add(Slide.Apps)
    if (s.busiestWeekday != null || s.busiestHour != null) add(Slide.Peak)
    if (s.longestSession != null || s.streakDays != null) add(Slide.Records)
    add(Slide.Persona)
    add(Slide.Change)
}

/** Near-white or near-black banner colors make a dull glow; fall back to the design's magenta. */
private fun tintOf(c: Color?): Color =
    if (c == null || c.luminance() > 0.8f || c.luminance() < 0.02f) Neon.Magenta else c

private fun monthName(m: YearMonth): String = m.month.getDisplayName(JTextStyle.FULL_STANDALONE, TR)

private fun hoursText(h: Double): String = String.format(TR, if (h >= 100) "%.0f" else "%.1f", h)

private fun minutesText(min: Int): String = if (min >= 60) "${min / 60} sa ${min % 60} dk" else "$min dk"

private fun emojiOf(k: PersonaKind) = when (k) {
    PersonaKind.NIGHT_OWL -> "🦉"
    PersonaKind.EARLY_BIRD -> "🌅"
    PersonaKind.WEEKEND_MARATHONER -> "🛋️"
    PersonaKind.LOYALIST -> "💙"
    PersonaKind.EXPLORER -> "🧭"
    PersonaKind.BALANCED -> "⚖️"
    PersonaKind.LIGHT -> "🌙"
}

/**
 * Neon Wrapped: a full-screen story for one month. ◀ ▶ (or OK) move between pages, ▲ ▼ switch
 * month when several exist, Back closes. Opening last month marks it seen (the VM clears the badge).
 */
@Composable
fun WrappedScreen(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val months by vm.wrappedAvailableMonths.collectAsStateWithLifecycle()
    val usageAccess by vm.usageAccess.collectAsStateWithLifecycle()
    // Last month first while its badge is up; read once, because opening it clears the badge
    val initial = remember {
        val list = vm.wrappedAvailableMonths.value
        val last = YearMonth.now().minusMonths(1)
        if (vm.wrappedBadge.value && last in list) last else list.firstOrNull()
    }
    var month by remember { mutableStateOf<YearMonth?>(initial) }
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(months) { if (month == null) month = months.firstOrNull() }
    LaunchedEffect(month) { page = 0 }

    val load by produceState<WrappedLoad>(WrappedLoad.Loading, month) {
        value = WrappedLoad.Loading
        val m = month
        value = if (m == null) WrappedLoad.Empty else vm.wrapped(m)?.let { WrappedLoad.Ready(it) } ?: WrappedLoad.Empty
    }
    val summary = (load as? WrappedLoad.Ready)?.summary
    val slides = remember(summary) { summary?.let(::slidesFor).orEmpty() }
    val cur = page.coerceIn(0, (slides.size - 1).coerceAtLeast(0))

    // The top app's color lights the page
    val tintArt by produceState<AppArt?>(null, summary) {
        value = summary?.topApps?.firstOrNull()?.let { vm.appFor(it.pkg) }?.let { vm.art(it) }
    }
    val tint = animateColorAsState(tintOf(tintArt?.vibrant), tween(700), label = "wrappedTint")

    val focus = remember { FocusRequester() }
    fun stepMonth(delta: Int) {
        val i = months.indexOf(month)
        val next = months.getOrNull(i + delta)
        if (i >= 0 && next != null) month = next
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .ignoreOpeningPress()
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> {
                            if (cur < slides.lastIndex) page = cur + 1
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (cur < slides.lastIndex) page = cur + 1 else onDismiss()
                            true
                        }
                        Key.DirectionLeft -> {
                            if (cur > 0) page = cur - 1
                            true
                        }
                        Key.DirectionUp -> {
                            stepMonth(-1)
                            true
                        }
                        Key.DirectionDown -> {
                            stepMonth(1)
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(focus)
                .focusable()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val base = Brush.linearGradient(
                        0f to Neon.Bg1, 0.55f to Neon.Bg2, 1f to Neon.Bg3,
                        start = Offset.Zero, end = Offset(w, h),
                    )
                    val violet = Brush.radialGradient(
                        listOf(Neon.Violet.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(0.15f * w, 0.95f * h),
                        radius = 0.5f * w,
                    )
                    onDrawBehind {
                        drawRect(base)
                        drawRect(violet)
                        val a = tint.value
                        drawRect(
                            Brush.radialGradient(
                                0f to a.copy(alpha = 0.6f),
                                0.5f to a.copy(alpha = 0.2f),
                                1f to Color.Transparent,
                                center = Offset(0.78f * w, 0.22f * h),
                                radius = 0.6f * w,
                            ),
                        )
                    }
                },
        ) {
            Sparkles()
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (slides.isNotEmpty()) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            slides.indices.forEach { i ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(3.dp)
                                        .clip(Pill)
                                        .background(
                                            when {
                                                i < cur -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.85f)))
                                                i == cur -> Neon.Cta
                                                else -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.2f)))
                                            },
                                        ),
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    val m = month
                    if (m != null && months.size > 1) {
                        Spacer(Modifier.width(18.dp))
                        Row(
                            Modifier
                                .height(28.dp)
                                .background(Neon.ChipFill, Pill)
                                .border(0.75.dp, Neon.ChipStroke, Pill)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${monthName(m)} ${m.year}  ▲▼",
                                style = TextStyle(fontFamily = Poppins, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val l = load) {
                        WrappedLoad.Loading -> Text(
                            "Yükleniyor…",
                            modifier = Modifier.align(Alignment.Center),
                            style = TextStyle(fontFamily = Poppins, fontSize = 16.sp, color = Neon.TextDim),
                        )
                        WrappedLoad.Empty -> EmptyState(usageAccess)
                        is WrappedLoad.Ready -> {
                            val s = l.summary
                            AnimatedContent(
                                targetState = SlideKey(s.month, cur),
                                transitionSpec = {
                                    val dir = if (targetState.index >= initialState.index) 1 else -1
                                    (fadeIn(tween(320, delayMillis = 100)) + slideInHorizontally(tween(380)) { it / 10 * dir }) togetherWith
                                        (fadeOut(tween(140)) + slideOutHorizontally(tween(260)) { -it / 10 * dir })
                                },
                                label = "wrappedSlide",
                            ) { k ->
                                when (slides[k.index.coerceIn(0, slides.lastIndex)]) {
                                    Slide.Intro -> IntroSlide(s)
                                    Slide.Apps -> AppsSlide(vm, s)
                                    Slide.Peak -> PeakSlide(s)
                                    Slide.Records -> RecordsSlide(vm, s)
                                    Slide.Persona -> PersonaSlide(s)
                                    Slide.Change -> ChangeSlide(s)
                                }
                            }
                        }
                    }
                }

                Text(
                    "◀ ▶ gezin" + (if (months.size > 1) "   ▲ ▼ ay değiştir" else "") + "   Geri: kapat",
                    style = TextStyle(fontFamily = Poppins, fontSize = 11.sp, color = Neon.TextMuted),
                )
            }
        }
    }
    FocusSoon(focus)
}

/** Soft twinkling dots in the side margins; one value, only read while drawing. */
@Composable
private fun Sparkles() {
    class Spark(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Int, val color: Color)

    // Only the 48dp side margins, between the progress dashes and the footer: never over text
    val sparks = remember {
        val rnd = Random(7)
        val palette = listOf(Neon.Pink, Neon.PinkLight, Neon.Violet, Color.White, Neon.Greeting)
        List(16) { i ->
            val x = if (i % 2 == 0) 0.008f + rnd.nextFloat() * 0.03f else 0.962f + rnd.nextFloat() * 0.03f
            Spark(x, rnd.nextFloat(), 1.5f + rnd.nextFloat() * 2.5f, rnd.nextFloat(), 1 + rnd.nextInt(2), palette[rnd.nextInt(palette.size)])
        }
    }
    // One short run after the page opens, then the canvas stops redrawing (2 GB box)
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(3500, easing = LinearEasing)) }
    Canvas(Modifier.fillMaxSize()) {
        val tv = t.value
        for (s in sparks) {
            val frac = (((s.y - tv * s.speed) % 1f) + 1f) % 1f
            val twinkle = 0.5f + 0.5f * sin((2.0 * PI * (tv * s.speed + s.phase)).toFloat())
            drawCircle(
                s.color.copy(alpha = 0.12f + 0.5f * twinkle),
                s.r.dp.toPx(),
                Offset(s.x * size.width, (0.08f + 0.8f * frac) * size.height),
            )
        }
    }
}

/** Springy entrance in the graphics layer, optionally staggered. */
@Composable
private fun Modifier.popIn(delayMs: Int = 0): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f))
    }
    return graphicsLayer {
        val v = a.value
        scaleX = 0.7f + 0.3f * v
        scaleY = 0.7f + 0.3f * v
        alpha = v.coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(0f, 0.5f)
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text("— $text", style = TextStyle(fontFamily = Poppins, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Neon.Greeting))
}

private fun big(size: Int, brush: Brush = Neon.TitleBrush) =
    TextStyle(fontFamily = Poppins, fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, brush = brush)

@Composable
private fun EmptyState(usageAccess: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📊", style = TextStyle(fontSize = 56.sp), modifier = Modifier.popIn())
        Spacer(Modifier.height(8.dp))
        Text("Henüz veri yok", style = big(44), modifier = Modifier.popIn(80))
        Spacer(Modifier.height(10.dp))
        Text(
            "Neon Wrapped, kullanım geçmişin biriktikçe oluşur. Birkaç gün sonra tekrar bak.",
            modifier = Modifier.widthIn(max = 560.dp),
            style = TextStyle(fontFamily = Poppins, fontSize = 16.sp, color = Neon.TextBody),
        )
        if (!usageAccess) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Kullanım erişimi verilmemiş. install.ps1 ile izin ver.",
                style = TextStyle(fontFamily = Poppins, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Neon.Amber),
            )
        }
    }
}

@Composable
private fun IntroSlide(s: WrappedSummary) {
    val running = s.month == YearMonth.now()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Eyebrow("Neon Wrapped")
        Text(monthName(s.month), style = big(75), modifier = Modifier.popIn(60))
        Text(
            if (running) "Bu ay (şu ana kadar)" else "${s.month.year}",
            style = TextStyle(fontFamily = Poppins, fontSize = 16.sp, color = Neon.TextBody),
        )
        Spacer(Modifier.height(18.dp))
        Text("Toplam", style = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = Neon.TextDim))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(hoursText(s.totalHours), style = big(92, Neon.Gradient), modifier = Modifier.popIn(240))
            Spacer(Modifier.width(12.dp))
            Text(
                "saat",
                modifier = Modifier.padding(bottom = 18.dp),
                style = TextStyle(fontFamily = Poppins, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Neon.TextSoft),
            )
        }
        if (s.approximate) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Bu ay için yalnızca sistemin aylık toplamı var; ayrıntılar gösterilemiyor.",
                style = TextStyle(fontFamily = Poppins, fontSize = 12.sp, color = Neon.TextMuted),
            )
        }
    }
}

@Composable
private fun AppsSlide(vm: LauncherViewModel, s: WrappedSummary) {
    val maxShare = s.topApps.maxOf { it.share }.coerceAtLeast(0.01)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Eyebrow("En çok izlenen")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.topApps.forEachIndexed { i, a -> AppUsageRow(vm, a, i, (a.share / maxShare).toFloat()) }
        }
    }
}

@Composable
private fun AppUsageRow(vm: LauncherViewModel, a: AppUsage, index: Int, frac: Float) {
    Row(Modifier.fillMaxWidth().height(46.dp).popIn(index * 90), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${index + 1}",
            modifier = Modifier.width(30.dp),
            style = if (index == 0) big(26, Neon.Gradient) else TextStyle(fontFamily = Poppins, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Neon.TextMuted),
        )
        MiniBanner(vm, a.pkg)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    a.label,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = Poppins, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${hoursText(a.hours)} sa · %${(a.share * 100).roundToInt()}",
                    style = TextStyle(fontFamily = Poppins, fontSize = 13.sp, color = Neon.TextSoft),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(5.dp))
            ShareBar(frac, delayMs = 250 + index * 90)
        }
    }
}

/** The fill grows in the draw phase, so the animation costs no layout. */
@Composable
private fun ShareBar(frac: Float, delayMs: Int) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(frac) {
        delay(delayMs.toLong())
        p.animateTo(frac.coerceIn(0f, 1f), tween(700))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(Pill)
            .background(Color.White.copy(alpha = 0.1f))
            .drawBehind { drawRect(Neon.Gradient, size = Size(size.width * p.value, size.height)) },
    )
}

@Composable
private fun MiniBanner(vm: LauncherViewModel, pkg: String) {
    val art by produceState<AppArt?>(null, pkg) { value = vm.appFor(pkg)?.let { vm.art(it) } }
    val a = art
    Box(
        Modifier
            .size(width = 76.dp, height = 43.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(a?.cardTop ?: Neon.Bg3),
        contentAlignment = Alignment.Center,
    ) {
        if (a != null) {
            if (a.isBanner) {
                Image(a.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(a.image, contentDescription = null, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier, delayMs: Int = 0, extra: @Composable () -> Unit = {}) {
    Column(modifier) {
        Text(label, style = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = Neon.TextDim))
        Text(value, style = big(46), maxLines = 1, modifier = Modifier.popIn(delayMs))
        extra()
    }
}

@Composable
private fun PeakSlide(s: WrappedSummary) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Eyebrow("Ne zaman izledin?")
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            s.busiestWeekday?.let {
                Fact("En yoğun gün", it.getDisplayName(JTextStyle.FULL, TR), Modifier.weight(1f), 80)
            }
            s.busiestHour?.let {
                Fact("En yoğun saat", "%02d:00 – %02d:00".format(it, (it + 1) % 24), Modifier.weight(1f), 200)
            }
        }
    }
}

@Composable
private fun RecordsSlide(vm: LauncherViewModel, s: WrappedSummary) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Eyebrow("Rekorların")
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            s.longestSession?.let { ls ->
                Fact("En uzun oturum", minutesText(ls.minutes), Modifier.weight(1f), 80) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MiniBanner(vm, ls.pkg)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ls.label,
                            style = TextStyle(fontFamily = Poppins, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Neon.TextSoft),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            s.streakDays?.let {
                Fact("Üst üste aktif gün", "$it gün", Modifier.weight(1f), 200)
            }
        }
    }
}

@Composable
private fun PersonaSlide(s: WrappedSummary) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Eyebrow("Bu ay sen")
        Text(emojiOf(s.persona.kind), style = TextStyle(fontSize = 72.sp), modifier = Modifier.popIn())
        Text(s.persona.title, style = big(54), maxLines = 1, modifier = Modifier.popIn(120))
        Spacer(Modifier.height(8.dp))
        Text(
            s.persona.blurb,
            modifier = Modifier.widthIn(max = 620.dp),
            style = TextStyle(fontFamily = Poppins, fontSize = 18.sp, color = Neon.TextBody),
        )
    }
}

@Composable
private fun ChangeSlide(s: WrappedSummary) {
    val p = s.changePercent
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Eyebrow("Geçen aya göre")
        if (p != null) {
            val sign = when {
                p > 0 -> "+"
                p < 0 -> "−"
                else -> ""
            }
            Text("$sign%${abs(p)}", style = big(92, if (p >= 0) Neon.Gradient else Neon.TitleBrush), modifier = Modifier.popIn(60))
            Text(
                when {
                    p > 0 -> "daha fazla izledin"
                    p < 0 -> "daha az izledin"
                    else -> "aynı kadar izledin"
                },
                style = TextStyle(fontFamily = Poppins, fontSize = 18.sp, color = Neon.TextBody),
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "Karşılaştırma için önceki ay verisi yok",
                style = TextStyle(fontFamily = Poppins, fontSize = 20.sp, color = Neon.TextBody),
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            SmallStat("Farklı uygulama", "${s.distinctApps}")
            SmallStat("Aktif gün", "${s.activeDays}")
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String) {
    Column {
        Text(value, style = TextStyle(fontFamily = Poppins, fontSize = 30.sp, fontWeight = FontWeight.Black))
        Text(label, style = TextStyle(fontFamily = Poppins, fontSize = 13.sp, color = Neon.TextDim))
    }
}
