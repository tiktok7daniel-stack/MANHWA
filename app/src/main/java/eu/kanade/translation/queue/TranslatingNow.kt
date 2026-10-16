package eu.kanade.translation.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.manga.components.translationStageLabel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.DeleteSweep
import mihon.icons.materialsymbols.rounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.roundedfilled.Cancel
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private val Ember = Color(0xFFFF6A2B)

/**
 * MANHWA "translating now": a button for the top bar that shows, from any work, how many chapters are being
 * translated anywhere (a glowing badge), and opens [TranslatingNowScreen].
 */
@Composable
fun TranslatingNowButton() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val manager = remember { context.appGraph.translationManager }
    val queue by manager.queueState.collectAsState()
    val active = queue.isNotEmpty()
    IconButton(onClick = { navigator?.push(TranslatingNowScreen()) }) {
        Box {
            Icon(
                MaterialSymbols.Rounded.Translate,
                contentDescription = stringResource(ATMR.strings.tn_title),
                tint = if (active) Ember else LocalContentColor.current,
            )
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Ember),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (queue.size > 9) "9+" else queue.size.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

/** The same button as a glass orb, for the home screen's header (next to the bell). */
@Composable
fun TranslatingNowOrb() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val manager = remember { context.appGraph.translationManager }
    val queue by manager.queueState.collectAsState()
    val active = queue.isNotEmpty()
    Box {
        Box(
            Modifier
                .size(42.dp)
                .glass(RoundedCornerShape(14.dp), accent = active, alpha = 0.45f)
                .clickable { navigator?.push(TranslatingNowScreen()) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MaterialSymbols.Rounded.Translate,
                contentDescription = stringResource(ATMR.strings.tn_title),
                tint = if (active) Ember else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        if (active) {
            Text(
                if (queue.size > 99) "99+" else "${queue.size}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .clip(CircleShape)
                    .background(Ember)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Every chapter being translated or waiting, from every work: the work, the chapter, where it is and how far,
 * with "open the work" and "stop this one", and pause / carry on / clear for the whole queue.
 */
class TranslatingNowScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { context.appGraph.translationManager }
        val queue by manager.queueState.collectAsState()
        // isRunning is not a flow: read again every second
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                tick++
            }
        }
        val running = remember(tick, queue) { manager.isRunning }
        val bottom = WindowInsets.navigationBars.asPaddingValues()
        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.tn_title),
                    subtitle = if (queue.isEmpty()) stringResource(ATMR.strings.tn_empty_short) else stringResource(ATMR.strings.tn_count, queue.size),
                    onBack = navigator::pop,
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (queue.isEmpty()) {
                        item {
                            Text(
                                stringResource(ATMR.strings.tn_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                    } else {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (running) {
                                    ManhwaKit.NeonButton(stringResource(ATMR.strings.tn_pause), icon = MaterialSymbols.RoundedFilled.Pause, modifier = Modifier.weight(1f)) {
                                        manager.pauseTranslation()
                                        tick++
                                    }
                                } else {
                                    ManhwaKit.NeonButton(stringResource(ATMR.strings.tn_resume), icon = MaterialSymbols.RoundedFilled.PlayArrow, modifier = Modifier.weight(1f)) {
                                        manager.startTranslation()
                                        tick++
                                    }
                                }
                                ManhwaKit.GhostButton(stringResource(ATMR.strings.tn_clear), icon = MaterialSymbols.Rounded.DeleteSweep, modifier = Modifier.weight(1f)) {
                                    manager.clearQueue()
                                }
                            }
                        }
                        items(queue, key = { System.identityHashCode(it) }) { t ->
                            QueueCard(
                                t,
                                onOpen = { navigator.push(MangaScreen(t.manga.id)) },
                                onStop = { manager.cancelQueuedTranslation(t) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun QueueCard(t: Translation, onOpen: () -> Unit, onStop: () -> Unit) {
        val state by t.statusFlow.collectAsState()
        val progress by t.progress.collectAsState()
        val working = state == Translation.State.TRANSLATING
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), accent = working, alpha = 0.5f)
                .clickable(onClick = onOpen)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.manga.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.chapter.name, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${progress.percent}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = if (working) Ember else Color.White.copy(alpha = 0.5f),
                )
            }
            translationStageLabel(state, progress)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = if (working) Color(0xFF3DDC97) else Color.White.copy(alpha = 0.6f))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.percent.coerceIn(0, 100) / 100f)
                        .height(6.dp)
                        .background(Ember),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManhwaKit.GhostButton(stringResource(ATMR.strings.tn_open), icon = MaterialSymbols.Rounded.OpenInNew, modifier = Modifier.weight(1f), onClick = onOpen)
                ManhwaKit.GhostButton(stringResource(ATMR.strings.tn_stop), icon = MaterialSymbols.RoundedFilled.Cancel, modifier = Modifier.weight(1f), onClick = onStop)
            }
        }
    }
}
