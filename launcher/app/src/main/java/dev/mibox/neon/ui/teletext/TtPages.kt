package dev.mibox.neon.ui.teletext

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.LinkType
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.MatchResult
import dev.mibox.neon.data.MatchState
import dev.mibox.neon.data.NetHealth
import dev.mibox.neon.data.SpeedResult
import dev.mibox.neon.data.SpeedState
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.data.Weather
import dev.mibox.neon.ui.LocalHomeStyle
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.fmtMbps
import dev.mibox.neon.ui.rememberNow
import dev.mibox.neon.ui.verdictText
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt
import dev.mibox.neon.i18n.t

private val KickDay = DateTimeFormatter.ofPattern("EEE dd.MM", TR)
private val KickHour = DateTimeFormatter.ofPattern("HH.mm", TR)
private val NowDay = DateTimeFormatter.ofPattern("EEEE d MMMM", TR)

// --- P200 Kaldığın yerden ------------------------------------------------------------------------

/** P200: Watch Next with episode and progress, "Senin için", and the Favoriler. */
@Composable
internal fun TtResumePage(ctx: TtCtx) {
    val vm = ctx.vm
    val watch by vm.watchNext.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val cards by vm.favoriteCards.collectAsStateWithLifecycle()
    val nav = ctx.nav
    val shownWatch = watch.take(3)
    val shownYou = forYou.take(3)
    val shownCards = cards.take(4)
    // The first row that exists takes the page's first focus
    val firstSection = when {
        shownWatch.isNotEmpty() -> 0
        shownYou.isNotEmpty() -> 1
        shownCards.isNotEmpty() -> 2
        else -> -1
    }

    Column(Modifier.fillMaxSize()) {
        TtBand(t("Kaldığın yerden"), "200", TtMagenta, TtBlack, size = TtSp2, height = 40.dp)

        TtBand(t("İzlemeye devam et"), t("İlerleme"), TtCyan, TtBlack)
        if (shownWatch.isEmpty()) TtEmpty()
        shownWatch.forEachIndexed { i, item ->
            key(item.id) {
                TtWatchRow(ctx, item, 201 + i, if (firstSection == 0 && i == 0) nav.first else null)
            }
        }

        TtBand(t("Senin için"), t("Öneriler"), TtRed, TtBlack)
        if (shownYou.isEmpty()) TtEmpty()
        shownYou.forEachIndexed { i, item ->
            key(item.id) {
                val app = vm.appFor(item.pkg)
                TtRow(
                    onClick = { vm.launchWatch(item) },
                    onLongClick = { ctx.actions.onContentMenu(item) },
                    requester = if (firstSection == 1 && i == 0) nav.first else null,
                    accent = app,
                ) { f ->
                    TtText((211 + i).toString(), tc(f, TtYellow))
                    Spacer(Modifier.width(12.dp))
                    TtLead(item.title, tc(f, TtWhite), Modifier.weight(1f))
                    TtText(app?.label ?: item.pkg.substringAfterLast('.'), tc(f, TtGreen), Modifier.padding(horizontal = 8.dp))
                }
            }
        }

        TtBand(t("Favoriler"), t("Sabitlenenler"), TtGreen, TtBlack)
        if (shownCards.isEmpty()) TtEmpty()
        shownCards.forEachIndexed { i, card ->
            key(card.key) {
                TtRow(
                    onClick = { vm.launchPinned(card) },
                    onLongClick = {
                        when (card) {
                            is FavoriteCard.App -> ctx.actions.onAppMenu(card.app)
                            is FavoriteCard.Content -> ctx.actions.onContentMenu(card.item)
                        }
                    },
                    requester = if (firstSection == 2 && i == 0) nav.first else null,
                    accent = card.app,
                ) { f ->
                    TtText((221 + i).toString(), tc(f, TtYellow))
                    Spacer(Modifier.width(12.dp))
                    TtLead(
                        if (card is FavoriteCard.Content) card.item.title else card.app.label,
                        tc(f, TtWhite),
                        Modifier.weight(1f),
                    )
                    TtText(
                        if (card is FavoriteCard.Content) t("İçerik") else t("Uygulama"),
                        tc(f, TtCyan),
                        Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/** A two-line row: number, app, "DEVAM ET" over title, episode and a block progress bar. */
@Composable
private fun TtWatchRow(ctx: TtCtx, item: WatchItem, number: Int, requester: FocusRequester?) {
    val vm = ctx.vm
    val app = vm.appFor(item.pkg)
    val episode = ttEpisode(item)
    TtRow(
        onClick = { vm.launchWatch(item) },
        onLongClick = { ctx.actions.onContentMenu(item) },
        requester = requester,
        accent = app,
        height = 40.dp,
    ) { f ->
        Column(Modifier.weight(1f)) {
            Row(Modifier.height(TtRowH), verticalAlignment = Alignment.CenterVertically) {
                TtText(number.toString(), tc(f, TtYellow))
                Spacer(Modifier.width(12.dp))
                TtLead(app?.label ?: item.pkg.substringAfterLast('.'), tc(f, TtCyan), Modifier.weight(1f))
                TtText(t("Devam et"), tc(f, TtWhite), Modifier.padding(horizontal = 8.dp))
            }
            Row(Modifier.height(TtRowH).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TtText(
                    item.title + (episode?.let { " · $it" } ?: ""),
                    tc(f, TtWhite),
                    Modifier.weight(1f, fill = false),
                )
                val p = item.progress
                if (p != null) {
                    Spacer(Modifier.width(12.dp))
                    TtBar(p)
                    Spacer(Modifier.width(8.dp))
                    TtText(t("%{0}", (p * 100).roundToInt()), tc(f, TtCyan))
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

// --- P300 Spor / P301 Canlı ----------------------------------------------------------------------

private fun oppShort(m: MatchState): String = m.opponentShort.ifBlank { m.opponent.take(3) }

/** "Süper Lig · iç saha galibiyeti"; a draw has no venue form: "Süper Lig · beraberlik". */
private fun resultLine(competition: String, home: Boolean, r: MatchResult): String {
    val venue = if (home) t("iç saha") else "deplasman"
    val word = when (r) {
        MatchResult.WIN -> t("{0} galibiyeti", venue)
        MatchResult.DRAW -> "beraberlik"
        MatchResult.LOSS -> t("{0} mağlubiyeti", venue)
    }
    return "$competition · $word"
}

/** On/off value of a setting: green AÇIK, red KAPALI. */
private fun onOff(on: Boolean): String = if (on) t("Açık") else t("Kapalı")

@Composable
private fun TtSettingRow(
    label: String,
    value: String,
    valueColor: Color,
    onClick: () -> Unit,
    requester: FocusRequester? = null,
) {
    TtRow(onClick = onClick, requester = requester) { f ->
        TtLead(label, tc(f, TtWhite), Modifier.weight(1f))
        TtText(value, tc(f, valueColor), Modifier.padding(horizontal = 8.dp))
    }
}

/** A setting that is switched off with match day: same place, dimmed, not focusable. */
@Composable
private fun TtDimRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(TtRowH).padding(start = 26.dp, end = 8.dp).graphicsLayer { alpha = 0.4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TtLead(label, TtWhite, Modifier.weight(1f))
        TtText(value, TtCyan)
    }
}

/** P300 Spor · Galatasaray: the match card, the match-day settings and the shortcuts. */
@Composable
internal fun TtSportPage(ctx: TtCtx) {
    val vm = ctx.vm
    val match by vm.match.collectAsStateWithLifecycle()
    val enabled by vm.matchEnabled.collectAsStateWithLifecycle()
    val goal by vm.matchGoalEffect.collectAsStateWithLifecycle()
    val delaySec by vm.matchDelay.collectAsStateWithLifecycle()
    val appPkg by vm.matchApp.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    val team = MatchConfig.Team
    val m = match
    val nav = ctx.nav

    Column(Modifier.fillMaxSize()) {
        TtBand(t("Spor · Galatasaray"), "300", TtRed, TtWhite, rightColor = TtYellow, size = TtSp2, height = 40.dp)
        TtStripe()

        if (!enabled) {
            TtBand(t("Maç günü"), t("Kapalı"), TtRed, TtWhite)
            TtLine(t("Maç günü kapalı: skor ve ışık efektleri durdu"), TtCyan)
        } else if (m == null) {
            TtBand(t("Maç günü"), t("Açık"), TtBlue, TtWhite)
            TtLine(t("Yaklaşan maç yok: fikstür yüklenemedi ya da sezon arası."), TtCyan)
        } else {
            val homeName = if (m.home) team.name else m.opponent
            val awayName = if (m.home) m.opponent else team.name
            val homeShort = if (m.home) team.shortName else oppShort(m)
            val awayShort = if (m.home) oppShort(m) else team.shortName
            when (m) {
                is MatchState.Upcoming -> {
                    TtBand(t("Sıradaki maç"), m.competition, TtCyan, TtBlack)
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TtText("$homeName - $awayName", TtYellow, Modifier.weight(1f), TtSp2)
                    }
                    Row(Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        TtText(m.kickoff.format(KickDay), TtWhite)
                        TtText(m.kickoff.format(KickHour), TtYellow)
                        TtText(m.competition, TtCyan)
                        TtText(if (m.home) t("İç saha") else t("Deplasman"), TtGreen)
                    }
                }
                is MatchState.Live -> {
                    val a = if (m.home) m.gsScore else m.oppScore
                    val b = if (m.home) m.oppScore else m.gsScore
                    TtBand(t("Canlı"), m.competition, TtRed, TtWhite)
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TtText("$homeShort $a - $b $awayShort", TtYellow, Modifier.weight(1f), TtSp2)
                    }
                    Row(Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        TtText("${m.clock} ${m.status}".trim(), TtGreen)
                        TtText(t("301 canlı sayfa"), TtCyan)
                    }
                }
                is MatchState.Finished -> {
                    val a = if (m.home) m.gsScore else m.oppScore
                    val b = if (m.home) m.oppScore else m.gsScore
                    TtBand(t("Son sonuç"), m.kickoff.format(KickDay), TtGreen, TtBlack)
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TtText("$homeName $a-$b $awayName", TtYellow, Modifier.weight(1f), TtSp2)
                    }
                    TtLine(resultLine(m.competition, m.home, m.result), TtCyan)
                }
            }
        }

        // Son sonuç next to the card, unless the card already is that result
        val lastRes = lastResult
        if (enabled && lastRes != null && (m !is MatchState.Finished || m.kickoffMs != lastRes.kickoffMs)) {
            val a = if (lastRes.home) lastRes.gsScore else lastRes.oppScore
            val b = if (lastRes.home) lastRes.oppScore else lastRes.gsScore
            val homeName = if (lastRes.home) team.name else lastRes.opponent
            val awayName = if (lastRes.home) lastRes.opponent else team.name
            TtBand(t("Son sonuç"), lastRes.kickoff.format(KickDay), TtGreen, TtBlack)
            TtLine("$homeName $a-$b $awayName", TtYellow)
            TtLine(resultLine(lastRes.competition, lastRes.home, lastRes.result), TtCyan)
        }
        Spacer(Modifier.height(TtRowH))

        TtBand(t("Maç günü ayarları"), t("OK: değiştir"), TtBlue, TtWhite, rightColor = TtYellow)
        // The master switch stays first and keeps focus; the rest stays in place, dimmed and inert, while it is off
        TtSettingRow(t("Maç günü"), onOff(enabled), if (enabled) TtGreen else TtRed, { vm.setMatchEnabled(!enabled) }, nav.first)
        val pkg = appPkg
        val appLabel = if (pkg == null) t("Seçilmedi") else apps.firstOrNull { it.pkg == pkg }?.label ?: t("Yüklü değil")
        if (enabled) {
            TtSettingRow(t("Gol efekti"), onOff(goal), if (goal) TtGreen else TtRed, { vm.setMatchGoalEffect(!goal) })
            TtSettingRow(
                t("Yayın gecikmesi"),
                t("{0} sn", delaySec),
                TtYellow,
                {
                    // Cycles 0 / 15 / 30 / 45 / 60 / 90; an unknown stored value starts the cycle over
                    val options = MatchConfig.DelayOptions
                    vm.setMatchDelay(options[(options.indexOf(delaySec) + 1) % options.size])
                },
            )
            TtSettingRow(t("Maçı izlediğim uygulama"), appLabel, if (pkg == null) TtMagenta else TtCyan, ctx.actions.onOpenMatch)
        } else {
            TtDimRow(t("Gol efekti"), onOff(goal))
            TtDimRow(t("Yayın gecikmesi"), t("{0} sn", delaySec))
            TtDimRow(t("Maçı izlediğim uygulama"), appLabel)
        }

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (m is MatchState.Live) {
                TtButton(onClick = { ctx.go(TtPage.LIVE) }, color = TtRed) {
                    TtText("301", it)
                    TtText(t("Canlı"), it)
                }
            }
            if (enabled) {
                TtButton(
                    onClick = { if (!vm.launchMatchApp()) ctx.actions.onOpenMatch() },
                    color = TtYellow,
                ) { TtText(t("Maçı aç"), it) }
            }
        }
    }
}

/** A blink phase 0..1 over 1.1 s; the tag reads it in its graphics layer, so nothing recomposes. */
@Composable
private fun rememberTtBlink(): State<Float> {
    val transition = rememberInfiniteTransition(label = "tt-blink")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "tt-blink-phase",
    )
}

/** P301 Canlı: the score in double height, minute and phase, a blinking CANLI tag and the match-day status. */
@Composable
internal fun TtLivePage(ctx: TtCtx) {
    val vm = ctx.vm
    val match by vm.match.collectAsStateWithLifecycle()
    val enabled by vm.matchEnabled.collectAsStateWithLifecycle()
    val goal by vm.matchGoalEffect.collectAsStateWithLifecycle()
    val delaySec by vm.matchDelay.collectAsStateWithLifecycle()
    val team = MatchConfig.Team
    val live = match as? MatchState.Live

    Column(Modifier.fillMaxSize()) {
        TtBand(
            if (live != null) t("Canlı · {0}", live.competition) else t("Canlı maç yok"),
            "301",
            TtRed,
            TtBlack,
        )
        if (live == null) {
            TtLine(t("Şu an oynanan bir Galatasaray maçı yok."), TtCyan)
            TtLine(t("Sıradaki maç ve son sonuç için 300 Spor sayfasına bak."), TtWhite)
        } else {
            val phase = rememberTtBlink()
            val homeName = if (live.home) team.name else live.opponent
            val awayName = if (live.home) live.opponent else team.name
            val a = if (live.home) live.gsScore else live.oppScore
            val b = if (live.home) live.oppScore else live.gsScore
            Row(
                Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TtMosaic(TtBall, 7.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        val gsFirst = live.home
                        TtTeamBlock(if (gsFirst) team.shortName else oppShort(live), gsFirst)
                        TtText("$a - $b", TtYellow, size = TtSp2)
                        TtTeamBlock(if (gsFirst) oppShort(live) else team.shortName, !gsFirst)
                    }
                    Row(Modifier.height(TtRowH), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TtText("${live.clock} ${live.status}".trim(), TtGreen)
                        Box(
                            Modifier
                                .graphicsLayer { alpha = if (phase.value < 0.5f) 1f else 0f }
                                .background(TtRed)
                                .padding(horizontal = 6.dp),
                        ) { TtText(t("Canlı"), TtBlack) }
                    }
                    TtLine("$homeName - $awayName", TtCyan, inset = 0.dp)
                }
            }
        }

        Spacer(Modifier.height(TtRowH))
        TtBand(t("Maç günü durumu"), t("300 ayarlar"), TtBlue, TtWhite, rightColor = TtYellow)
        Row(
            Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (!enabled) {
                TtText(t("Maç günü kapalı"), TtRed)
            } else {
                TtStatusPair(t("Işıklar"), onOff(true), TtGreen)
                TtStatusPair(t("Gol efekti"), onOff(goal), if (goal) TtGreen else TtRed)
                TtStatusPair(t("Yayın gecikmesi"), t("{0} sn", delaySec), TtYellow)
            }
        }

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TtButton(
                onClick = { if (!vm.launchMatchApp()) ctx.actions.onOpenMatch() },
                color = TtYellow,
                requester = ctx.nav.first,
            ) { TtText(t("Maçı aç"), it) }
            TtButton(onClick = { ctx.go(TtPage.SPORT) }, color = TtRed) {
                TtText("300", it)
                TtText(t("Spor"), it)
            }
        }
    }
}

/** A team name on its color: Galatasaray red/white, the opponent blue/yellow. */
@Composable
private fun TtTeamBlock(name: String, ours: Boolean) {
    Box(
        Modifier.height(40.dp).background(if (ours) TtRed else TtBlue).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        TtText(name, if (ours) TtWhite else TtYellow, size = TtSp2)
    }
}

@Composable
private fun TtStatusPair(label: String, value: String, valueColor: Color) {
    Row {
        TtText(label, TtWhite)
        Spacer(Modifier.width(8.dp))
        TtText(value, valueColor)
    }
}

// --- P400 Hava + 450 Ağ durumu -------------------------------------------------------------------

private fun linkName(t: LinkType): String = when (t) {
    LinkType.ETHERNET -> t("Kablolu")
    LinkType.WIFI -> "Wi-Fi"
    LinkType.OTHER -> t("Diğer")
    LinkType.NONE -> t("Bağlantı yok")
}

/** P400: the weather now, then the 450 network block. No forecast: the app has no forecast data. */
@Composable
internal fun TtWeatherPage(ctx: TtCtx) {
    val vm = ctx.vm
    val st = LocalHomeStyle.current
    val city by vm.city.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val net by vm.netNow.collectAsStateWithLifecycle()
    val history by vm.netHistory.collectAsStateWithLifecycle()
    val now by rememberNow()
    val w = weather

    Column(Modifier.fillMaxSize()) {
        TtBand(t("Hava · {0}", city.name), "400", TtCyan, TtBlack, size = TtSp2, height = 40.dp)

        Row(
            Modifier.fillMaxWidth().height(100.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TtMosaic(ttWeatherMosaic(w), 8.dp, bg = TtBlue, pad = 2.dp)
            Spacer(Modifier.width(28.dp))
            Column {
                Box(Modifier.height(60.dp), contentAlignment = Alignment.CenterStart) {
                    TtText(if (w != null) "${w.tempC.roundToInt()}°C" else "--°C", TtYellow, size = TtSp3)
                }
                Box(Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
                    TtText(
                        if (w != null) Weather.describe(w.code, w.isDay).second else t("Hava bilgisi alınamadı"),
                        TtCyan,
                        size = TtSp2,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                TtLine(t("Şu an · {0}", NowDay.format(now)), TtWhite, inset = 0.dp)
                TtLine(city.name, TtCyan, inset = 0.dp)
            }
        }

        Spacer(Modifier.height(TtRowH))
        val last = net.last
        TtBand(
            t("450 Ağ durumu"),
            if (last != null) t("Son ölçüm {0} Mbps", fmtMbps(last.mbps)) else t("Ölçüm yok"),
            TtMagenta,
            TtBlack,
        )
        Row(Modifier.fillMaxWidth().height(100.dp)) {
            Column(Modifier.weight(1f)) {
                TtNetRow(t("Bağlantı"), linkName(net.link))
                if (net.link == LinkType.WIFI) {
                    TtNetRow(t("Sinyal"), net.rssiDbm?.let { "$it dBm" } ?: "–")
                    TtNetRow(t("Bağlantı hızı"), net.linkMbps?.let { "$it Mbps" } ?: "–")
                }
                TtNetRow(t("Gecikme"), net.latencyMs?.let { "$it ms" } ?: "–")
                TtNetRow(t("Son ölçüm"), if (last != null) "${fmtMbps(last.mbps)} Mbps" else t("Henüz yok"))
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.width(320.dp)) {
                Row(Modifier.height(TtRowH).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(st.verdict(net.verdict)).padding(horizontal = 6.dp)) {
                        TtText(verdictText(net.verdict), TtBlack)
                    }
                }
                TtHistory(history)
                TtLine(
                    if (history.isEmpty()) t("Henüz ölçüm yok") else t("Son {0} ölçüm · kırmızı çizgi: 25 Mbps", history.takeLast(HistoryCols).size),
                    TtCyan,
                )
            }
        }
        TtSpeedRow(ctx)
    }
}

@Composable
private fun TtNetRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(TtRowH).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TtLead(label, TtWhite, Modifier.weight(1f))
        TtText(value, TtYellow)
    }
}

private const val HistoryCols = 12

/**
 * The last results as columns of block cells (one cell = 10 Mbps, at most 10), colored by what they
 * mean for streaming, with a red line across at 25 Mbps (4K). Drawn in one pass.
 */
@Composable
private fun TtHistory(history: List<SpeedResult>) {
    val shown = history.takeLast(HistoryCols)
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .drawBehind {
                val unit = 6.dp.toPx()
                val cellH = 5.dp.toPx()
                val colW = 20.dp.toPx()
                val gap = 4.dp.toPx()
                var x = 8.dp.toPx()
                for (r in shown) {
                    val n = ceil(r.mbps / 10.0).toInt().coerceIn(1, 10)
                    val color = when {
                        r.mbps >= NetHealth.UHD_MBPS -> TtGreen
                        r.mbps >= NetHealth.HD_MBPS -> TtYellow
                        else -> TtMagenta
                    }
                    for (j in 0 until n) {
                        drawRect(color, Offset(x, size.height - (j + 1) * unit + (unit - cellH)), Size(colW, cellH))
                    }
                    x += colW + gap
                }
                drawRect(TtRed, Offset(0f, size.height - 2.5f * unit - 1.dp.toPx()), Size(size.width, 2.dp.toPx()))
            },
    )
}

/** "Hızı ölç": OK runs the test, long press opens the network panel. */
@Composable
private fun TtSpeedRow(ctx: TtCtx) {
    val vm = ctx.vm
    val speed by vm.speed.collectAsStateWithLifecycle()
    val s = speed
    TtRow(
        onClick = { vm.runSpeedTest() },
        onLongClick = ctx.actions.onOpenNet,
        requester = ctx.nav.first,
    ) { f ->
        TtLead(if (s is SpeedState.Running) t("Ölçülüyor...") else t("Hızı ölç"), tc(f, TtYellow), Modifier.weight(1f))
        val right = when (s) {
            is SpeedState.Running -> "${fmtMbps(s.mbps)} Mbps"
            is SpeedState.Done -> "${fmtMbps(s.mbps)} Mbps"
            SpeedState.Failed -> t("Ölçülemedi")
            SpeedState.Idle -> t("OK: başlat · uzun bas: ayrıntı")
        }
        TtText(right, tc(f, TtCyan), Modifier.padding(horizontal = 8.dp))
    }
}

// --- P500 Uygulamalar ----------------------------------------------------------------------------

/** P500: every app as a numbered index (501, 502, ...). OK launches, long press opens the shared app menu. */
@Composable
internal fun TtAppsPage(ctx: TtCtx) {
    val vm = ctx.vm
    val apps by vm.apps.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // Home, page entry and a lost row all ask for "the first row": make sure it is composed
    LaunchedEffect(ctx.nav.tick.intValue) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TtBand(t("Uygulamalar"), "500", TtGreen, TtBlack, size = TtSp2, height = 40.dp)
        TtBand(t("Tüm uygulamalar"), t("{0} uygulama", apps.size), TtYellow, TtBlack)
        if (apps.isEmpty()) TtEmpty()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            itemsIndexed(apps, key = { _, app -> app.pkg }) { i, app ->
                TtRow(
                    onClick = { vm.launch(app) },
                    onLongClick = { ctx.actions.onAppMenu(app) },
                    requester = if (i == 0) ctx.nav.first else null,
                    accent = app,
                ) { f ->
                    TtLead(app.label, tc(f, TtWhite), Modifier.weight(1f))
                    val category = remember(app.pkg) { vm.categoryOf(app.pkg) }
                    if (category != null) TtText(category, tc(f, TtCyan), Modifier.padding(start = 8.dp))
                    TtText((501 + i).toString(), tc(f, TtYellow), Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}
