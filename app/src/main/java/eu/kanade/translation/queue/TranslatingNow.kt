package eu.kanade.translation.queue

import eu.kanade.presentation.components.ManhwaInk

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import mihon.icons.materialsymbols.rounded.Speed
import mihon.icons.materialsymbols.rounded.DeleteSweep
import mihon.icons.materialsymbols.rounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.roundedfilled.Cancel
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.i18n.stringResource

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
    // MANHWA: the chapter the reader translates live counts too
    val live by remember { context.appGraph.liveChapterProgress.current }.collectAsState()
    val count = queue.size + if (live != null) 1 else 0
    val active = count > 0
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
                    Text(if (count > 9) "9+" else count.toString(), color = ManhwaInk.text, fontSize = 9.sp, fontWeight = FontWeight.Black)
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
    // MANHWA: the chapter the reader translates live counts too
    val live by remember { context.appGraph.liveChapterProgress.current }.collectAsState()
    val count = queue.size + if (live != null) 1 else 0
    val active = count > 0
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
                tint = if (active) Ember else ManhwaInk.text,
                modifier = Modifier.size(22.dp),
            )
        }
        if (active) {
            Text(
                if (count > 99) "99+" else "$count",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ManhwaInk.text,
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
        // MANHWA fusion: the chapter whose "Open the work" asks where to go (the Arabic work or the English source)
        var choosing by remember { mutableStateOf<Pair<eu.kanade.translation.unified.ChapterFusion.Fusion, Long>?>(null) }
        // MANHWA: the chapter the reader translates live, shown above the queue
        val live by remember { context.appGraph.liveChapterProgress.current }.collectAsState()
        // A fused continuation's work asks where to go; any other work opens its own source, with a short note
        val openWork: (Long, String) -> Unit = { mangaId, sourceName ->
            val fusion = context.appGraph.chapterFusion.continuationOf(mangaId)
            if (fusion != null) {
                choosing = fusion to mangaId
            } else {
                android.widget.Toast.makeText(
                    context,
                    context.stringResource(ATMR.strings.tn_not_fused, sourceName),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                navigator.push(MangaScreen(mangaId))
            }
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues()
        choosing?.let { (fusion, mangaId) ->
            val title by androidx.compose.runtime.produceState("", mangaId) {
                value = context.appGraph.getMangaById.await(mangaId)?.title.orEmpty()
            }
            AlertDialog(
                onDismissRequest = { choosing = null },
                title = { Text(stringResource(ATMR.strings.tn_open_which)) },
                text = { Text(stringResource(ATMR.strings.tn_open_which_body, title)) },
                confirmButton = {
                    TextButton(onClick = {
                        choosing = null
                        navigator.push(MangaScreen(fusion.baseMangaId))
                    }) { Text(stringResource(ATMR.strings.tn_open_fused, fusion.baseSourceName)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        choosing = null
                        navigator.push(MangaScreen(mangaId))
                    }) { Text(stringResource(ATMR.strings.tn_open_source, fusion.sourceNameOf(mangaId))) }
                },
            )
        }
        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.tn_title),
                    subtitle = if (queue.isEmpty() && live == null) {
                        stringResource(ATMR.strings.tn_empty_short)
                    } else {
                        stringResource(ATMR.strings.tn_count, queue.size + if (live != null) 1 else 0)
                    },
                    onBack = navigator::pop,
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    live?.let { now ->
                        item(key = "live") { LiveCard(now) { sourceName -> openWork(now.mangaId, sourceName) } }
                    }
                    if (queue.isEmpty()) {
                        if (live == null) item {
                            Text(
                                stringResource(ATMR.strings.tn_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ManhwaInk.text.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                    } else {
                        // MANHWA: Android's battery limits still on for the app: one tap so the translation goes on
                        // with the app left or the screen off
                        item(key = "background") { BackgroundCard() }
                        item(key = "serving") { ServingOnLine() }
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
                                onOpen = { openWork(t.manga.id, t.source.name) },
                                onStop = { manager.cancelQueuedTranslation(t) },
                            )
                        }
                    }
                }
            }
        }
    }

    /** MANHWA: the chapter the reader translates live: the work, the chapter, the pages done and a bar. */
    @Composable
    private fun LiveCard(live: eu.kanade.translation.live.LiveChapterProgress.Live, onOpen: (sourceName: String) -> Unit) {
        val context = LocalContext.current
        // The work's title and its source's name, read off the main thread
        val work by androidx.compose.runtime.produceState("" to "", live.mangaId) {
            val manga = context.appGraph.getMangaById.await(live.mangaId)
            value = manga?.title.orEmpty() to manga?.let { context.appGraph.sourceManager.get(it.source)?.name }.orEmpty()
        }
        val title = work.first
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), accent = true, alpha = 0.5f)
                .clickable { onOpen(work.second) }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ManhwaInk.text, maxLines = 1)
            Text(live.chapterName, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.75f), maxLines = 1)
            Text(
                stringResource(ATMR.strings.fusion_live_translating, live.done, live.total),
                style = MaterialTheme.typography.labelMedium,
                color = Ember,
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (live.total > 0) live.done.toFloat() / live.total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = Ember,
                trackColor = ManhwaInk.text.copy(alpha = 0.15f),
            )
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
                    Text(t.manga.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = ManhwaInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.chapter.name, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${progress.percent}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = if (working) Ember else ManhwaInk.text.copy(alpha = 0.5f),
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
                    .background(ManhwaInk.text.copy(alpha = 0.12f)),
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

/** MANHWA home server: where translation runs now (the laptop, the cloud keys, or the phone's model). */
@Composable
private fun ServingOnLine() {
    val context = LocalContext.current
    val store = remember { context.appGraph.teamStore }
    // Asked again every few seconds: the laptop may come and go
    val serving by androidx.compose.runtime.produceState(store.servingOn()) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.servingOn() }
        }
    }
    val where = when (serving) {
        eu.kanade.translation.team.LocalServerRules.Serving.HOME -> stringResource(ATMR.strings.serving_home)
        eu.kanade.translation.team.LocalServerRules.Serving.CLOUD -> stringResource(ATMR.strings.serving_cloud)
        eu.kanade.translation.team.LocalServerRules.Serving.PHONE -> stringResource(ATMR.strings.serving_phone)
    }
    Text(
        stringResource(ATMR.strings.serving_on, where),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = ManhwaInk.text.copy(alpha = 0.8f),
        modifier = Modifier.glass(RoundedCornerShape(50), alpha = 0.35f).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Shown while Android's battery limits apply to the app; it opens Android's own question to lift them. */
@Composable
private fun BackgroundCard() {
    val context = LocalContext.current
    var limited by remember { mutableStateOf(false) }
    // Asked again when the reader comes back from Android's question
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            limited = runCatching {
                !(context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)
            kotlinx.coroutines.delay(2_000)
        }
    }
    if (!limited) return
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), accent = true, alpha = 0.45f).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(ATMR.strings.tn_bg_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = ManhwaInk.text)
        Text(stringResource(ATMR.strings.tn_bg_info), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.75f))
        ManhwaKit.NeonButton(stringResource(ATMR.strings.tn_bg_allow), icon = MaterialSymbols.Rounded.Speed, modifier = Modifier.fillMaxWidth()) {
            runCatching {
                @android.annotation.SuppressLint("BatteryLife")
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }
        }
    }
}
