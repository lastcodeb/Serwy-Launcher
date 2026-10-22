package dev.mibox.neon.ui.gazete

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.mibox.neon.LauncherViewModel
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.ui.HomeActions
import dev.mibox.neon.ui.LocalHomeStyle

/**
 * The index ("Dizin"): every visible app as a newspaper index line, name, dotted leader and page
 * number, in two columns read down the left one first. It is a page of the home, not an overlay;
 * Back or "Ana sayfa" returns to the front page.
 */
@Composable
internal fun GazeteDizin(vm: LauncherViewModel, actions: HomeActions, nav: GazeteNav, onBack: () -> Unit) {
    val st = LocalHomeStyle.current
    val apps by vm.apps.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val half = (apps.size + 1) / 2

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            GreetingText()
            GazeteLink(nav, K_BACK, "Ana sayfa", onBack, arrow = ArrowDir.LEFT, color = st.accent)
        }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(2.5.dp).background(st.divider))
            Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    "NEON",
                    style = st.display.copy(fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = 3.sp),
                    color = st.text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    "Dizin",
                    modifier = Modifier.weight(1f),
                    style = st.playBlack(50f, italic = true).copy(lineHeight = 52.sp),
                    color = st.accent,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(st.caps("Tüm uygulamalar"), style = st.lab(12f), color = st.accent, maxLines = 1)
                    Text(
                        if (apps.isEmpty()) "Uygulama yok" else "${apps.size} uygulama · s. 1–${apps.size}",
                        style = st.serif(13f),
                        color = st.text,
                        maxLines = 1,
                    )
                }
            }
        }
        IssueLine(vm, pageNo = 2)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            // The pen loop reaches 7dp above and below, and a lazy column clips along its scroll axis
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(half, key = { apps[it].component.flattenToShortString() }) { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DizinCell(vm, actions, nav, apps[r], number = r + 1, first = r == 0, modifier = Modifier.weight(1f))
                    val right = apps.getOrNull(r + half)
                    if (right != null) {
                        DizinCell(vm, actions, nav, right, number = r + half + 1, first = r == 0, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Text("Menü için bir uygulamaya uzun bas.", style = st.play(14f, italic = true), color = st.textMuted, maxLines = 1)
    }
}

/**
 * One index line. The line under the name is real data only: the app's category as a red tag when
 * the system knows it, otherwise when it was last opened.
 */
@Composable
private fun DizinCell(
    vm: LauncherViewModel,
    actions: HomeActions,
    nav: GazeteNav,
    app: AppEntry,
    number: Int,
    first: Boolean,
    modifier: Modifier,
) {
    val st = LocalHomeStyle.current
    val category = remember(app) { vm.categoryOf(app.pkg)?.takeIf { it.isNotBlank() } }
    // headlineFor's deck is "Son açılış: …" when the usage history knows the app (else a category, shown as the tag)
    val info = remember(app) { vm.headlineFor(app).deck.takeIf { it.startsWith("Son açılış") }.orEmpty() }
    val label = remember(app, st) { st.clean(app.label) }
    val story = remember(app) { GazeteStory.Application(app) }
    val rule = remember(st) { st.divider.copy(alpha = 0.35f) }
    GazeteItem(
        nav, dizinKey(app),
        onClick = { vm.launch(app) },
        modifier = modifier
            .let { if (first) it.focusProperties { up = nav.requester(K_BACK) } else it }
            .drawBehind { drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 0.75.dp.toPx()) },
        onLongClick = { actions.onAppMenu(app) },
        story = story,
        index = number - 1,
    ) {
        // Side padding keeps the pen loop off the page number
        Column(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 16.dp, vertical = 3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    modifier = Modifier.widthIn(max = 280.dp),
                    style = st.play(19f),
                    color = st.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Leader(st.text, Modifier.weight(1f).offset(y = 4.dp))
                Spacer(Modifier.width(8.dp))
                Text("s. $number", style = st.lab(14f), color = st.text, maxLines = 1)
            }
            if (category != null || info.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (category != null) Text(st.caps(category), style = st.lab(12f), color = st.accent, maxLines = 1)
                    if (info.isNotBlank()) Text(info, style = st.serif(12f), color = st.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The dotted leader between a name and its page number. */
@Composable
private fun Leader(color: Color, modifier: Modifier) {
    Spacer(
        modifier.height(4.dp).drawWithCache {
            val stroke = 1.6.dp.toPx()
            val dots = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx()), 0f)
            onDrawBehind {
                drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = stroke, cap = StrokeCap.Round, pathEffect = dots)
            }
        },
    )
}
