package dev.mibox.neon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.GazeteEdition
import dev.mibox.neon.data.HomeTheme
import kotlinx.coroutines.delay
import dev.mibox.neon.i18n.t

private val PreviewW = 144.dp
private val PreviewH = 81.dp

private fun nameOf(t: HomeTheme) = when (t) {
    HomeTheme.NEON -> "Neon"
    HomeTheme.GAZETE -> t("Gazete")
    HomeTheme.TELETEXT -> "Teletext"
    HomeTheme.GLASS -> t("Cam")
    HomeTheme.LUMEN -> "Lumen"
}

private fun blurbOf(t: HomeTheme) = when (t) {
    HomeTheme.NEON -> t("Renkli, parlak, cam gibi")
    HomeTheme.GAZETE -> t("Manşet ve sütunlarla gazete sayfası")
    HomeTheme.TELETEXT -> t("90'lar teletekst sayfası")
    HomeTheme.GLASS -> t("Buzlu cam ve sinema gibi büyük görseller")
    HomeTheme.LUMEN -> t("Sakin ve sinema gibi; önce kaldığın yer")
}

/**
 * "Tema": Neon, Gazete, Teletext, Cam, each with a small drawn preview. Choosing applies at once (the
 * dialog itself re-skins); Gazete also offers its edition. Back closes; Home is handled by
 * LauncherScreen. The picker is drawn in the active theme like every other overlay.
 */
@Composable
fun ThemePicker(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val st = LocalHomeStyle.current
    val current by vm.theme.collectAsStateWithLifecycle()
    val edition by vm.gazeteEdition.collectAsStateWithLifecycle()
    val night by vm.gazeteNight.collectAsStateWithLifecycle()
    val requesters = remember { HomeTheme.values().associateWith { FocusRequester() } }
    val editionFirst = remember { FocusRequester() }
    val closeButton = remember { FocusRequester() }
    val gazeteCard = requesters.getValue(HomeTheme.GAZETE)
    val editionShown = current == HomeTheme.GAZETE

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .ignoreOpeningPress()
                .width(900.dp)
                .panel(st)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(t("Tema"), style = st.headline.fillWith(st.accentBrush, st.titleColor))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeTheme.values().forEach { t ->
                    ThemeOption(
                        theme = t,
                        selected = t == current,
                        gazeteNight = night,
                        onPick = { vm.setTheme(t) },
                        modifier = Modifier
                            .weight(1f)
                            // Down from any card: the edition row when shown, else Kapat
                            .focusProperties { down = if (editionShown) editionFirst else closeButton }
                            .focusRequester(requesters.getValue(t)),
                    )
                }
            }
            if (editionShown) {
                Text(st.caps(t("Gazete baskısı")), style = st.label, color = st.textDim)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val toCard = Modifier.focusProperties { up = gazeteCard }
                    EditionChip(t("Otomatik (gün batımına göre)"), edition == GazeteEdition.AUTO, toCard.focusRequester(editionFirst)) {
                        vm.setGazeteEdition(GazeteEdition.AUTO)
                    }
                    EditionChip(t("Gündüz"), edition == GazeteEdition.DAY, toCard) { vm.setGazeteEdition(GazeteEdition.DAY) }
                    EditionChip(t("Gece"), edition == GazeteEdition.NIGHT, toCard) { vm.setGazeteEdition(GazeteEdition.NIGHT) }
                }
            }
            Chip(t("Kapat"), onClick = onDismiss, modifier = Modifier.focusRequester(closeButton))
        }
    }

    // Focus starts on the active theme. After a pick the buttons are rebuilt in the new skin:
    // the same request puts focus back on the chosen card.
    LaunchedEffect(current) {
        repeat(5) {
            if (runCatching { requesters.getValue(current).requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

@Composable
private fun EditionChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Chip(label, onClick = onClick, modifier = modifier, primary = selected)
}

@Composable
private fun ThemeOption(
    theme: HomeTheme,
    selected: Boolean,
    gazeteNight: Boolean,
    onPick: () -> Unit,
    modifier: Modifier,
) {
    val st = LocalHomeStyle.current
    NeonSurface(
        onClick = onPick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.03f,
    ) {
        Column(
            Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (theme) {
                HomeTheme.NEON -> NeonPreview()
                HomeTheme.GAZETE -> GazetePreview(gazeteNight)
                HomeTheme.TELETEXT -> TeletextPreview()
                HomeTheme.GLASS -> GlassPreview()
                HomeTheme.LUMEN -> LumenPreview()
            }
            Text(
                st.mark(selected) + nameOf(theme),
                style = st.item,
                maxLines = 1,
            )
            Text(
                blurbOf(theme),
                style = st.type(11.sp, color = Color.Unspecified, role = TextRole.BODY).copy(textAlign = TextAlign.Center),
                maxLines = 2,
                modifier = Modifier.height(30.dp),
            )
        }
    }
}

// --- Previews: a few rects, lines and texts in each theme's own colors and fonts ----------------

@Composable
private fun NeonPreview() {
    Box(
        Modifier
            .size(PreviewW, PreviewH)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(Neon.Bg1, Neon.Bg2, Neon.Bg3))),
    ) {
        // Glow, top bar chips, hero title, a row of cards
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(80.dp)
                .background(Brush.radialGradient(listOf(Neon.Magenta.copy(alpha = 0.45f), Color.Transparent))),
        )
        Row(Modifier.padding(start = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { Box(Modifier.size(width = 26.dp, height = 7.dp).background(Neon.ChipFill, Pill)) }
        }
        Text(
            "Netflix",
            modifier = Modifier.padding(start = 8.dp, top = 22.dp),
            style = TextStyle(fontFamily = Poppins, fontSize = 16.sp, fontWeight = FontWeight.Black, brush = Neon.TitleBrush),
        )
        Text(
            t("— İyi akşamlar"),
            modifier = Modifier.padding(start = 8.dp, top = 16.dp),
            style = TextStyle(fontFamily = Poppins, fontSize = 5.sp, color = Neon.Greeting),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(Neon.Pink, Neon.Violet, Neon.PinkInk, Neon.Magenta, Neon.Violet).forEach {
                Box(Modifier.size(width = 24.dp, height = 14.dp).clip(RoundedCornerShape(3.dp)).background(it))
            }
        }
    }
}

@Composable
private fun GazetePreview(night: Boolean) {
    val g = GazettePalette
    val paper = if (night) g.NightPaper else g.DayPaper
    val ink = if (night) g.NightInk else g.DayInk
    val red = if (night) g.NightRed else g.DayRed
    val muted = if (night) g.NightMuted else g.DayMuted
    Box(Modifier.size(PreviewW, PreviewH).background(paper)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(ink))
            Text(
                "NEON",
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    fontFamily = Playfair,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = ink,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(ink))
            Box(Modifier.fillMaxWidth().height(1.dp).background(ink))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                // Left column: rules like short stories
                Column(Modifier.width(36.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(width = 14.dp, height = 2.dp).background(red))
                    repeat(4) { Box(Modifier.fillMaxWidth().height(1.dp).background(muted)) }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(ink))
                // The lead story
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.size(width = 20.dp, height = 2.dp).background(red))
                    Text(
                        t("Pazartesi derbi"),
                        style = TextStyle(fontFamily = Playfair, fontSize = 10.sp, fontWeight = FontWeight.Black, color = ink),
                        maxLines = 1,
                    )
                    Text(
                        t("GS – FB, 20.30"),
                        style = TextStyle(fontFamily = SourceSerif, fontSize = 6.sp, color = muted),
                        maxLines = 1,
                    )
                    repeat(2) { Box(Modifier.fillMaxWidth().height(1.dp).background(muted)) }
                }
            }
        }
    }
}

@Composable
private fun TeletextPreview() {
    val t = TeletextPalette
    val line = TextStyle(fontFamily = Vt323, fontSize = 9.sp, color = t.White)
    Column(Modifier.size(PreviewW, PreviewH).background(t.Black)) {
        Box(Modifier.fillMaxWidth().height(11.dp).background(t.Blue).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text("P100 NEON 100", style = line.copy(fontSize = 9.sp), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(9.dp).background(t.Yellow).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text(t("MANSET"), style = line.copy(color = t.Black, fontSize = 8.sp), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(14.dp).background(t.Red).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text(t("PAZARTESİ DERBİ 20.30"), style = line.copy(fontSize = 11.sp), maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(t("Genelde bu saatte: YouTube"), style = line.copy(color = t.Cyan, fontSize = 8.sp), maxLines = 1)
            Row(Modifier.background(t.White).padding(horizontal = 2.dp)) {
                Text(t("300  Spor"), style = line.copy(color = t.Black, fontSize = 8.sp), maxLines = 1)
            }
            Text(t("200  Devam et"), style = line.copy(fontSize = 8.sp), maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(8.dp)) {
            listOf(t.Red, t.Green, t.Yellow, t.Blue).forEach { Box(Modifier.weight(1f).fillMaxSize().background(it)) }
        }
    }
}

@Composable
private fun GlassPreview() {
    val sans = androidx.compose.ui.text.font.FontFamily.SansSerif
    Box(
        Modifier
            .size(PreviewW, PreviewH)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B2A7A), Color(0xFF0E5E7E), Color(0xFF0B0D12)))),
    ) {
        // Artwork top right, title, a frosted shelf of tiles
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 8.dp)
                .size(width = 58.dp, height = 33.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFF9F6B), Color(0xFFE0457B)))),
        )
        Text(
            "Netflix",
            modifier = Modifier.padding(start = 8.dp, top = 22.dp),
            style = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 5.dp, vertical = 5.dp)
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .padding(horizontal = 5.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(Color.White, Color(0xFFE50914), Color(0xFF1DB954), Color(0xFF0A84FF), Color(0xFFFFD60A)).forEachIndexed { i, c ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (i == 0) c else c.copy(alpha = 0.8f)))
            }
        }
    }
}

@Composable
private fun LumenPreview() {
    val l = LumenPalette
    Box(Modifier.size(PreviewW, PreviewH).clip(RoundedCornerShape(6.dp)).background(l.Ground)) {
        // Title left, artwork right, rows below; one amber ring
        Text(
            "Cobra Kai",
            modifier = Modifier.padding(start = 7.dp, top = 16.dp),
            style = TextStyle(fontFamily = LumenDisplay, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = l.Text),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 9.dp, end = 7.dp)
                .size(width = 62.dp, height = 35.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF7A2A12), Color(0xFFE0893A)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 7.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(6) { Box(Modifier.size(width = 14.dp, height = 8.dp).clip(RoundedCornerShape(2.dp)).background(l.Raised)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(width = 27.dp, height = 15.dp).border(1.dp, l.Amber, RoundedCornerShape(3.dp)).padding(2.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF5B3A2A)))
                repeat(3) { Box(Modifier.size(width = 27.dp, height = 15.dp).clip(RoundedCornerShape(3.dp)).background(l.Raised)) }
            }
        }
    }
}
