package dev.mibox.neon.ui.teletext

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.HeadlineKind
import dev.mibox.neon.data.HeadlineTarget
import dev.mibox.neon.data.HeroReason
import dev.mibox.neon.data.SpeedState
import dev.mibox.neon.data.Weather
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.ui.LocalHomeStyle
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.rememberNow
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** "86,0": one decimal with a Turkish comma, like the Control Center. */
private fun oneDecimal(mbps: Double): String = String.format(TR, "%.1f", mbps)

private val LongDate =DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", TR)

/** Greeting by hour, the same bands as the headline engine (05-10, 11-17, 18-21, else night). */
private fun greeting(hour: Int): String = when (hour) {
    in 5..10 -> "Günaydın"
    in 11..17 -> "İyi günler"
    in 18..21 -> "İyi akşamlar"
    else -> "İyi geceler"
}

/** "2. Sezon 5. Bölüm", "5. Bölüm", the episode title, or null: only what the app published. */
internal fun ttEpisode(item: WatchItem): String? {
    val s = item.season?.trim().orEmpty()
    val e = item.episode?.trim().orEmpty()
    return when {
        s.isNotEmpty() && e.isNotEmpty() -> "$s. Sezon $e. Bölüm"
        e.isNotEmpty() -> "$e. Bölüm"
        else -> item.episodeTitle?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** P100 Ana sayfa: logo, greeting, manşet, index, resume box, favorites and the status row. */
@Composable
internal fun TtHomePage(ctx: TtCtx) {
    Column(Modifier.fillMaxSize()) {
        TtHero(ctx.vm)
        TtHeadline(ctx)
        TtBand("İndeks", "Sayfa", TtBlue, TtWhite, rightColor = TtYellow)
        Row(Modifier.fillMaxWidth()) {
            TtIndex(ctx, Modifier.weight(1f))
            Spacer(Modifier.width(28.dp))
            TtResumeBox(ctx)
        }
        TtFavorites(ctx)
        Spacer(Modifier.weight(1f))
        TtStatusRow(ctx)
    }
}

/** NEON mosaic, greeting tag, date, city with the weather, and the "type a page" hint. */
@Composable
private fun TtHero(vm: LauncherViewModel) {
    val now by rememberNow()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val city by vm.city.collectAsStateWithLifecycle()
    val w = weather
    val weatherLine = if (w == null) {
        "${city.name} · hava bekleniyor"
    } else {
        "${city.name} · ${w.tempC.roundToInt()}°C ${Weather.describe(w.code, w.isDay).second}"
    }
    Row(
        Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TtMosaic(TtLogo, 8.dp)
        Spacer(Modifier.width(28.dp))
        Column {
            TtLine(greeting(now.hour), TtBlack, bg = TtMagenta, inset = 0.dp)
            TtLine(LongDate.format(now), TtCyan, inset = 0.dp)
            TtLine(weatherLine, TtYellow, inset = 0.dp)
            TtLine("Sayfa no yaz ya da renkli tuşa bas", TtWhite, inset = 0.dp)
        }
    }
}

/** The manşet: kicker band, a double-height title line, and the deck / "genelde bu saatte" line. */
@Composable
private fun TtHeadline(ctx: TtCtx) {
    val vm = ctx.vm
    val h by vm.headline.collectAsStateWithLifecycle()
    val hero by vm.hero.collectAsStateWithLifecycle()
    val predicted = hero?.takeIf { it.reason == HeroReason.Predicted }?.app?.label
    val band: Color
    val bandFg: Color
    when (h.kind) {
        HeadlineKind.MATCH_LIVE, HeadlineKind.MATCH_RESULT, HeadlineKind.MATCH_TODAY, HeadlineKind.MATCH_UPCOMING -> {
            band = TtRed
            bandFg = TtWhite
        }
        HeadlineKind.CONTINUE -> {
            band = TtGreen
            bandFg = TtBlack
        }
        HeadlineKind.PREDICTED, HeadlineKind.LAST_USED, HeadlineKind.APP, HeadlineKind.GREETING -> {
            band = TtBlue
            bandFg = TtWhite
        }
    }
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(TtRowH).background(TtYellow).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TtText(h.kicker, TtBlack, Modifier.weight(1f))
                if (h.action.isNotEmpty()) TtText(h.action, TtBlack)
            }
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(band).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TtText(h.title, bandFg, Modifier.weight(1f), TtSp2)
            }
            Row(
                Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TtText(h.deck, TtCyan, Modifier.weight(1f))
                val progress = h.progress
                if (progress != null) {
                    TtBar(progress, Modifier.padding(start = 12.dp))
                    TtText("%${(progress * 100).roundToInt()}", TtCyan, Modifier.padding(start = 8.dp))
                }
                // The predicted app is its own headline when it leads; otherwise it is a quiet hint here
                if (predicted != null && h.kind != HeadlineKind.PREDICTED) {
                    TtText("Genelde bu saatte:", TtCyan, Modifier.padding(start = 16.dp))
                    Spacer(Modifier.width(8.dp))
                    TtText(predicted, TtYellow)
                }
            }
        }
    }
    if (h.target == HeadlineTarget.None) {
        // The greeting: nothing to open, so nothing to focus
        Box(Modifier.fillMaxWidth().height(80.dp)) { body() }
    } else {
        TtFocusable(
            onClick = {
                // A match headline with no match app chosen opens the match panel instead
                if (!vm.act(h.target) && h.target == HeadlineTarget.OpenMatch) ctx.actions.onOpenMatch()
            },
            modifier = Modifier.fillMaxWidth().height(80.dp),
            focusedContainer = Color.Transparent,
            focusedContent = TtWhite,
            focusOutline = TtWhite,
        ) { body() }
    }
}

private class IndexEntry(val key: String, val label: String)

private val IndexEntries = listOf(
    IndexEntry("200", "Kaldığın yerden"),
    IndexEntry("300", "Spor"),
    IndexEntry("400", "Hava"),
    IndexEntry("450", "Ağ durumu"),
    IndexEntry("500", "Uygulamalar"),
    IndexEntry("600", "Neon Wrapped"),
    IndexEntry("700", "Işık"),
    IndexEntry("800", "Kontrol merkezi"),
    IndexEntry("900", "Tema"),
)

/** The İNDEKS: one row per page or tool, number on the right. */
@Composable
private fun TtIndex(ctx: TtCtx, modifier: Modifier) {
    Column(modifier) {
        IndexEntries.forEach { e ->
            key(e.key) {
                TtRow(
                    onClick = { openIndex(ctx, e.key) },
                    requester = ctx.nav.index(e.key),
                ) { f ->
                    TtLead(e.label, tc(f, TtWhite), Modifier.weight(1f))
                    TtText(e.key, tc(f, TtYellow), Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

private fun openIndex(ctx: TtCtx, key: String) {
    val a = ctx.actions
    when (key) {
        "200" -> ctx.go(TtPage.RESUME)
        "300" -> ctx.go(TtPage.SPORT)
        "400", "450" -> ctx.go(TtPage.WEATHER)
        "500" -> ctx.go(TtPage.APPS)
        "600" -> a.onOpenWrapped()
        "700" -> ctx.openLight()
        "800" -> {
            ctx.vm.refreshWeather()
            a.onOpenControl()
        }
        "900" -> a.onOpenThemePicker()
    }
}

/** KALDIĞIN YER: the TV mosaic and the newest Watch Next item. Absent when there is none. */
@Composable
private fun TtResumeBox(ctx: TtCtx) {
    val vm = ctx.vm
    val watch by vm.watchNext.collectAsStateWithLifecycle()
    val item = watch.firstOrNull() ?: return
    val app = vm.appFor(item.pkg)
    val episode = ttEpisode(item)
    TtFocusable(
        onClick = { vm.launchWatch(item) },
        modifier = Modifier.width(320.dp),
        onLongClick = { ctx.actions.onContentMenu(item) },
        accent = app,
        focusedContainer = Color.Transparent,
        focusedContent = TtWhite,
        focusOutline = TtWhite,
    ) {
        Column {
            TtBand("Kaldığın yer", "200", TtGreen, TtBlack)
            Row(Modifier.padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TtMosaic(TtTv, 7.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    TtLine(app?.label ?: item.pkg.substringAfterLast('.'), TtYellow, inset = 0.dp)
                    TtLine(item.title, TtWhite, inset = 0.dp)
                    TtLine(episode ?: "", TtCyan, inset = 0.dp)
                    val p = item.progress
                    if (p != null) {
                        Box(Modifier.height(TtRowH), contentAlignment = Alignment.CenterStart) {
                            TtBar(p, cells = 12)
                        }
                    }
                }
            }
        }
    }
}

/** Number shown for a favorite: 5xx for apps (their row on P500), 22x for pinned content. */
private fun favNumber(card: FavoriteCard, index: Int, appIndex: Int): String = when (card) {
    is FavoriteCard.App -> if (appIndex >= 0) (501 + appIndex).toString() else "---"
    is FavoriteCard.Content -> (221 + index).toString()
}

/** FAVORİLER: the first four cards of vm.favoriteCards on one line. */
@Composable
private fun TtFavorites(ctx: TtCtx) {
    val vm = ctx.vm
    val cards by vm.favoriteCards.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val shown = cards.take(4)
    val numbers = shown.mapIndexed { i, c -> favNumber(c, i, apps.indexOfFirst { it.pkg == c.app.pkg }) }
    TtBand("Favoriler", numbers.joinToString(" · "), TtCyan, TtBlack)
    if (shown.isEmpty()) {
        TtLine("Favori yok · uygulamada uzun bas: Favorilere ekle", TtCyan)
    } else {
        Row(
            Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            shown.forEachIndexed { i, card ->
                key(card.key) {
                    TtRow(
                        onClick = { vm.launchPinned(card) },
                        onLongClick = {
                            when (card) {
                                is FavoriteCard.App -> ctx.actions.onAppMenu(card.app)
                                is FavoriteCard.Content -> ctx.actions.onContentMenu(card.item)
                            }
                        },
                        accent = card.app,
                        marker = false,
                        fill = false,
                    ) { f ->
                        Spacer(Modifier.width(8.dp))
                        TtText(numbers[i], tc(f, TtYellow))
                        Spacer(Modifier.width(12.dp))
                        TtText(
                            if (card is FavoriteCard.Content) card.item.title else card.app.label,
                            tc(f, TtWhite),
                            Modifier.widthIn(max = 200.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}

/** One status cell: filled with [color] at rest when [filled], else framed in it. White when focused. */
@Composable
private fun TtStatusCell(
    label: String,
    tag: String,
    color: Color,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    tagBox: Boolean = false,
) {
    TtFocusable(
        onClick = onClick,
        modifier = modifier.height(TtRowH),
        onLongClick = onLongClick,
        container = if (filled) color else TtBlack,
        contentColor = if (filled) TtBlack else color,
        restOutline = if (filled) null else color,
    ) { f ->
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (f || filled) TtBlack else color
            TtText(label, fg, Modifier.weight(1f))
            if (tagBox) {
                Box(Modifier.background(if (f) TtBlack else color).padding(horizontal = 6.dp)) {
                    TtText(tag, if (f) TtWhite else TtBlack)
                }
            } else {
                TtText(tag, fg)
            }
        }
    }
}

/** IŞIK / HIZ / RAM: three live cells, each a button. */
@Composable
private fun TtStatusRow(ctx: TtCtx) {
    val vm = ctx.vm
    val st = LocalHomeStyle.current
    val ip by vm.wizIp.collectAsStateWithLifecycle()
    val wiz by vm.wiz.collectAsStateWithLifecycle()
    val net by vm.netNow.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val ram by vm.ram.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxWidth().height(TtRowH), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Light: not found / connecting / on / off
        val pilot = wiz
        val lightLabel: String
        val lightColor: Color
        when {
            ip == null -> {
                lightLabel = "Işık: bulunamadı"
                lightColor = TtRed
            }
            pilot == null -> {
                lightLabel = "Işık: bağlanıyor"
                lightColor = TtYellow
            }
            pilot.on -> {
                lightLabel = "Işık: açık %${pilot.dimming}"
                lightColor = TtGreen
            }
            else -> {
                lightLabel = "Işık: kapalı"
                lightColor = TtWhite
            }
        }
        TtStatusCell(
            label = lightLabel,
            tag = "700",
            color = lightColor,
            filled = ip == null,
            modifier = Modifier.weight(1f),
            onClick = { ctx.openLight() },
            onLongClick = { vm.wizQuick() },
        )

        // Speed: OK runs the test, long press opens the network panel
        val last = net.last
        val speedLabel = when (val s = speed) {
            is SpeedState.Running -> "Ölçülüyor ${oneDecimal(s.mbps)} Mbps"
            is SpeedState.Done -> "Hız ${oneDecimal(s.mbps)} Mbps"
            SpeedState.Failed -> "Hız: ölçülemedi"
            SpeedState.Idle -> if (last != null) "Hız ${oneDecimal(last.mbps)} Mbps" else "Hız: ölçüm yok"
        }
        TtStatusCell(
            label = speedLabel,
            tag = "450",
            color = st.verdict(net.verdict),
            filled = false,
            modifier = Modifier.weight(1f),
            onClick = { vm.runSpeedTest() },
            onLongClick = ctx.actions.onOpenNet,
        )

        // RAM: OK frees it
        val ramColor = when {
            ram.usedPercent < 60 -> TtGreen
            ram.usedPercent < 85 -> TtYellow
            else -> TtRed
        }
        val freedBytes = freed
        TtStatusCell(
            label = "RAM %${ram.usedPercent}",
            tag = if (freedBytes != null) "+${freedBytes / (1024 * 1024)} MB" else "[Boşalt]",
            color = ramColor,
            filled = false,
            modifier = Modifier.weight(1f),
            onClick = { vm.boost() },
            tagBox = true,
        )
    }
}
