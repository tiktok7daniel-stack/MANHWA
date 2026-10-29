package eu.kanade.tachiyomi.novel.bridge

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.tachiyomi.novel.NovelDetailsScreen
import eu.kanade.tachiyomi.novel.NovelReaderScreen
import eu.kanade.tachiyomi.novel.NovelSession
import eu.kanade.tachiyomi.novel.novelKeyChapter
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.MangaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoStories
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.custommaterialsymbols.CustomMaterialSymbols
import mihon.icons.custommaterialsymbols.rounded.DownloadCircle
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.MenuBook
import mihon.icons.materialsymbols.rounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.SwapHoriz
import mihon.icons.materialsymbols.rounded.TravelExplore
import mihon.icons.materialsymbols.rounded.Verified
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA novel bridge on the manhwa's page: what the panel knows and is doing. One per page; everything slow
 * (searching sources, reading chapters) runs in the page's scope and only on the reader's way into the novel tab.
 */
@Stable
class NovelBridgeUi(
    private val context: Context,
    private val scope: CoroutineScope,
    val mangaId: Long,
    val mangaTitle: String,
) {
    var loaded by mutableStateOf(false)
        private set
    var link by mutableStateOf<NovelBridge.Link?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchProgress by mutableStateOf(0 to 0)
        private set
    var candidates by mutableStateOf<List<NovelBridge.Candidate>?>(null)
        private set
    var installing by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var chapters by mutableStateOf<List<ChapterInfo>>(emptyList())
        private set
    var chaptersLoading by mutableStateOf(false)
        private set
    var chaptersFailed by mutableStateOf(false)
        private set
    var places by mutableStateOf<Map<Long, NovelBridge.Place>>(emptyMap())
        private set
    var step by mutableStateOf<NovelAligner.Step?>(null)
        private set

    /** The last answer for a manhwa chapter (by id) this visit; a found place is kept in [places] for good. */
    var answers by mutableStateOf<Map<Long, NovelAligner.Result>>(emptyMap())
        private set

    private var locating: Job? = null

    val sourceCount: Int get() = context.appGraph.novelExtensionManager.sources().size

    fun sourceName(): String? = link?.let { context.appGraph.novelExtensionManager.source(it.sourceId)?.name }

    fun load() {
        if (loaded) return
        scope.launch {
            link = NovelBridge.link(context, mangaId)
            places = NovelBridge.places(context, mangaId)
            loaded = true
            link?.let { loadChapters(it) }
        }
    }

    /** Looks for the novel in every source; a same-work result found from the facts is linked at once. */
    fun search() {
        if (searching) return
        searching = true
        scope.launch {
            try {
                val found = NovelBridge.find(context, mangaId, mangaTitle) { done, total -> searchProgress = done to total }
                candidates = found
                if (link == null) found.firstOrNull { it.exact }?.let { pick(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Novel search for $mangaTitle" }
                candidates = emptyList()
            } finally {
                searching = false
            }
        }
    }

    fun pick(candidate: NovelBridge.Candidate) {
        scope.launch {
            NovelBridge.setLink(context, mangaId, candidate)
            link = NovelBridge.link(context, mangaId)
            places = emptyMap()
            answers = emptyMap()
            chapters = emptyList()
            link?.let { loadChapters(it) }
        }
    }

    fun unlink() {
        locating?.cancel()
        scope.launch {
            NovelBridge.unlink(context, mangaId)
            link = null
            places = emptyMap()
            answers = emptyMap()
            chapters = emptyList()
        }
    }

    /** Installs every English novel source not on the phone yet, then looks for the novel. */
    fun installEnglishAndSearch() {
        if (installing != null) return
        scope.launch {
            val manager = context.appGraph.novelExtensionManager
            if (manager.available.value.isEmpty()) manager.refreshAvailable()
            manager.installAll(setOf("en"), nsfw = false) { done, total -> installing = done to total }
            installing = null
            candidates = null
            search()
        }
    }

    private suspend fun loadChapters(l: NovelBridge.Link) {
        val key = l.novelKey
        NovelSession.chapters[key]?.takeIf { it.isNotEmpty() }?.let {
            chapters = inReadingOrder(it.distinctBy { c -> c.key })
            return
        }
        val source = context.appGraph.novelExtensionManager.source(l.sourceId)
        if (source == null) {
            chaptersFailed = true
            return
        }
        chaptersLoading = true
        chaptersFailed = false
        try {
            val list = withContext(Dispatchers.IO) {
                source.getChapterList(MangaInfo(key = l.url, title = l.title, cover = l.cover), emptyList())
            }.filter { it.key.isNotBlank() }.distinctBy { it.key }
            NovelSession.chapters[key] = list
            chapters = inReadingOrder(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Novel chapters of ${l.title}" }
            chaptersFailed = true
        } finally {
            chaptersLoading = false
        }
    }

    fun retryChapters() {
        val l = link ?: return
        scope.launch { loadChapters(l) }
    }

    /** Finds where [chapter] of the manhwa is told in the novel (kept once found). */
    fun locate(manga: Manga, chapter: Chapter) {
        val l = link ?: return
        if (chapters.isEmpty() || locating?.isActive == true) return
        locating = scope.launch {
            try {
                val result = NovelAligner.locate(context, manga, chapter, l, chapters) { step = it }
                answers = answers + (chapter.id to result)
                if (result is NovelAligner.Result.Found) places = places + (chapter.id to result.place)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Novel place of ${chapter.name}" }
                answers = answers + (chapter.id to NovelAligner.Result.Failed(e.message.orEmpty()))
            } finally {
                step = null
            }
        }
    }

    val locatingNow: Boolean get() = step != null

    companion object {
        /** Oldest first, whichever way the source lists them. */
        fun inReadingOrder(list: List<ChapterInfo>): List<ChapterInfo> {
            val first = list.firstOrNull()?.number ?: return list
            val last = list.last().number
            return if (first >= 0 && last >= 0 && first > last) list.reversed() else list
        }
    }
}

@Composable
fun rememberNovelBridge(manga: Manga): NovelBridgeUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(manga.id) { NovelBridgeUi(context.applicationContext, scope, manga.id, manga.title) }
}

/** The last manhwa chapter read (by number), the one the novel should be continued after. */
fun lastReadChapter(chapters: List<Chapter>): Chapter? =
    chapters.filter { it.read && it.chapterNumber >= 0 }.maxByOrNull { it.chapterNumber }

// ---------------------------------------------------------------- The switch

/**
 * "Manhwa | Novel": a glass switch over the chapter panel. A tap or a sideways swipe on it changes the list below,
 * the lit half sliding across with a spring.
 */
@Composable
fun ShelfSwitch(tab: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(48.dp)
            .glass(RoundedCornerShape(50), alpha = 0.32f)
            .pointerInput(tab, rtl) {
                var pulled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { pulled = 0f },
                    onDragEnd = {
                        // Toward where the other half is: the novel half is at the end of the row
                        val toward = if (rtl) -pulled else pulled
                        if (toward > SWIPE_PX && tab == 0) onTab(1)
                        if (toward < -SWIPE_PX && tab == 1) onTab(0)
                    },
                ) { change, amount ->
                    change.consume()
                    pulled += amount
                }
            },
    ) {
        val half = maxWidth / 2
        val shift by animateDpAsState(
            if (tab == 0) 0.dp else half,
            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
            label = "shelf",
        )
        Box(
            Modifier
                .offset(x = shift)
                .width(half)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(primary.copy(alpha = 0.95f), primary.copy(alpha = 0.6f)))),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            ShelfHalf(stringResource(ATMR.strings.bridge_tab_manhwa), MaterialSymbols.Rounded.AutoStories, tab == 0) { onTab(0) }
            ShelfHalf(stringResource(ATMR.strings.bridge_tab_novel), MaterialSymbols.Rounded.MenuBook, tab == 1) { onTab(1) }
        }
    }
}

private const val SWIPE_PX = 60f

@Composable
private fun androidx.compose.foundation.layout.RowScope.ShelfHalf(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (on) MaterialTheme.colorScheme.onPrimary else ManhwaInk.muted
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- The novel side

/** The novel side of the chapter panel: the novel, where to continue it, and its chapters. */
fun LazyListScope.novelBridgeItems(ui: NovelBridgeUi, manga: Manga, mangaChapters: List<Chapter>) {
    item(key = "bridge-head") {
        LaunchedEffect(ui) { ui.load() }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            when {
                !ui.loaded -> Loading()
                ui.link == null -> FindNovelCard(ui)
                else -> LinkedNovelCard(ui)
            }
        }
    }
    val link = ui.link ?: return
    item(key = "bridge-continue") {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { ContinueNovelCard(ui, manga, mangaChapters, link) }
    }
    item(key = "bridge-chapters-title") {
        Text(
            stringResource(ATMR.strings.bridge_novel_chapters, ui.chapters.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
        )
    }
    when {
        ui.chaptersLoading -> item(key = "bridge-chapters-loading") { Loading() }
        ui.chaptersFailed && ui.chapters.isEmpty() -> item(key = "bridge-chapters-failed") {
            Box(Modifier.padding(horizontal = 16.dp)) {
                ManhwaKit.GhostButton(stringResource(ATMR.strings.bridge_retry), Modifier.fillMaxWidth(), icon = MaterialSymbols.Rounded.Refresh) {
                    ui.retryChapters()
                }
            }
        }
    }
    val here = ui.places.values.maxByOrNull { it.manhwaNumber }?.novelIndex
    items(ui.chapters.size, key = { "bridge-ch-${ui.chapters[it].key}" }) { i ->
        NovelChapterRow(link, ui.chapters[i], here = i == here)
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
    }
}

@Composable
private fun PanelCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), alpha = 0.36f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.muted)
}

@Composable
private fun FindNovelCard(ui: NovelBridgeUi) {
    LaunchedEffect(ui.loaded) {
        // Opening the novel side is the ask: the search runs by itself the first time
        if (ui.loaded && ui.link == null && ui.candidates == null && ui.sourceCount > 0) ui.search()
    }
    PanelCard {
        CardTitle(MaterialSymbols.Rounded.TravelExplore, stringResource(ATMR.strings.bridge_find_title))
        val installing = ui.installing
        when {
            installing != null -> {
                Muted(stringResource(ATMR.strings.novel_installing_all, installing.first, installing.second))
                LinearProgressIndicator(
                    progress = { if (installing.second > 0) installing.first.toFloat() / installing.second else 0f },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                )
            }
            ui.sourceCount == 0 -> {
                Muted(stringResource(ATMR.strings.bridge_no_sources))
                ManhwaKit.NeonButton(stringResource(ATMR.strings.bridge_install_sources), Modifier.fillMaxWidth(), icon = CustomMaterialSymbols.Rounded.DownloadCircle) {
                    ui.installEnglishAndSearch()
                }
            }
            ui.searching -> {
                val (done, total) = ui.searchProgress
                Muted(stringResource(ATMR.strings.bridge_searching, done, total))
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                )
            }
            else -> {
                val found = ui.candidates.orEmpty()
                Muted(
                    stringResource(
                        when {
                            ui.candidates == null -> ATMR.strings.bridge_find_hint
                            found.isEmpty() -> ATMR.strings.bridge_not_found
                            else -> ATMR.strings.bridge_pick_hint
                        },
                    ),
                )
                found.take(12).forEach { CandidateRow(it) { ui.pick(it) } }
                ManhwaKit.GhostButton(stringResource(ATMR.strings.bridge_search_again), Modifier.fillMaxWidth(), icon = MaterialSymbols.Rounded.Refresh) {
                    ui.search()
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(c: NovelBridge.Candidate, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(c.info.cover, 44)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.info.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${c.sourceName} · ${c.lang}", style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
        }
        if (c.exact) Tag(stringResource(ATMR.strings.bridge_same_work), MaterialSymbols.Rounded.Verified, Color(0xFF3DDC84))
    }
}

@Composable
private fun Tag(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Cover(url: String, width: Int) {
    AsyncImage(
        model = url.ifBlank { null },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(width.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun LinkedNovelCard(ui: NovelBridgeUi) {
    val link = ui.link ?: return
    val navigator = LocalNavigator.current
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(link.cover, 64)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(ATMR.strings.bridge_source_novel), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(link.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ui.sourceName()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted) }
                if (link.manual) {
                    Tag(stringResource(ATMR.strings.bridge_picked_by_you), null, Color(0xFFFFB74D))
                } else {
                    Tag(stringResource(ATMR.strings.bridge_same_work), MaterialSymbols.Rounded.Verified, Color(0xFF3DDC84))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManhwaKit.GhostButton(stringResource(ATMR.strings.bridge_open_novel), Modifier.weight(1f), icon = MaterialSymbols.Rounded.OpenInNew) {
                navigator?.push(NovelDetailsScreen(link.sourceId, link.url, link.title, link.cover))
            }
            ManhwaKit.GhostButton(stringResource(ATMR.strings.bridge_change), Modifier.weight(1f), icon = MaterialSymbols.Rounded.SwapHoriz) {
                ui.unlink()
            }
        }
    }
}

@Composable
private fun ContinueNovelCard(ui: NovelBridgeUi, manga: Manga, mangaChapters: List<Chapter>, link: NovelBridge.Link) {
    val navigator = LocalNavigator.current
    val last = remember(mangaChapters) { lastReadChapter(mangaChapters) }
    val place = last?.let { ui.places[it.id] }
    val answer = last?.let { ui.answers[it.id] }
    LaunchedEffect(last?.id, ui.chapters.size) {
        // Found once, kept: only a chapter not looked at yet this visit is looked for
        if (last != null && place == null && answer == null && ui.chapters.isNotEmpty()) ui.locate(manga, last)
    }
    PanelCard {
        CardTitle(MaterialSymbols.Rounded.MenuBook, stringResource(ATMR.strings.bridge_continue_title))
        when {
            last == null -> Muted(stringResource(ATMR.strings.bridge_read_first))
            ui.chapters.isEmpty() -> Muted(stringResource(ATMR.strings.bridge_waiting_chapters))
            place != null -> {
                // The list may have changed since the place was found: its url says where it is now
                val at = ui.chapters.indexOfFirst { it.key == place.novelUrl }.takeIf { it >= 0 } ?: place.novelIndex
                val next = (at + 1).coerceIn(0, ui.chapters.lastIndex)
                Muted(stringResource(ATMR.strings.bridge_told_in, formatNumber(last.chapterNumber), place.novelName))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManhwaKit.NeonButton(
                        stringResource(ATMR.strings.bridge_continue_from, ui.chapters[next].name),
                        Modifier.weight(1f),
                        icon = MaterialSymbols.Rounded.MenuBook,
                    ) { navigator?.push(NovelReaderScreen(link.sourceId, link.url, ui.chapters[next].key)) }
                }
                ManhwaKit.GhostButton(stringResource(ATMR.strings.bridge_reread_matched), Modifier.fillMaxWidth()) {
                    navigator?.push(NovelReaderScreen(link.sourceId, link.url, place.novelUrl))
                }
                Text(
                    stringResource(ATMR.strings.bridge_how_known, (place.share * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = ManhwaInk.muted,
                )
            }
            ui.locatingNow -> {
                Muted(
                    stringResource(
                        when (ui.step) {
                            NovelAligner.Step.READING_MANHWA -> ATMR.strings.bridge_step_manhwa
                            NovelAligner.Step.READING_NOVEL -> ATMR.strings.bridge_step_novel
                            else -> ATMR.strings.bridge_step_compare
                        },
                        formatNumber(last.chapterNumber),
                    ),
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
            }
            answer == null -> {
                Muted(stringResource(ATMR.strings.bridge_waiting_chapters))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
            }
            else -> {
                val message = when (answer) {
                    is NovelAligner.Result.NoText -> ATMR.strings.bridge_no_text
                    is NovelAligner.Result.Failed -> ATMR.strings.bridge_failed
                    else -> ATMR.strings.bridge_unsure
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(MaterialSymbols.Rounded.Info, null, tint = ManhwaInk.muted, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Muted(stringResource(message, formatNumber(last.chapterNumber)))
                }
                ManhwaKit.GhostButton(stringResource(ATMR.strings.bridge_retry), Modifier.fillMaxWidth(), icon = MaterialSymbols.Rounded.Refresh) {
                    ui.locate(manga, last)
                }
            }
        }
    }
}

@Composable
private fun NovelChapterRow(link: NovelBridge.Link, chapter: ChapterInfo, here: Boolean) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val progress by remember { context.appGraph.novelStore.progress }.collectAsState()
    val p = progress[novelKeyChapter(link.novelKey, chapter.key)]
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { navigator?.push(NovelReaderScreen(link.sourceId, link.url, chapter.key)) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chapter.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (p?.read == true) FontWeight.Normal else FontWeight.SemiBold,
                color = if (p?.read == true) ManhwaInk.muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (p != null && !p.read && p.fraction > 0f) {
                ManhwaKit.GlowBar(p.fraction, Modifier.padding(top = 6.dp).width(120.dp))
            }
        }
        if (here) {
            Tag(stringResource(ATMR.strings.bridge_manhwa_here), MaterialSymbols.Rounded.AutoStories, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        if (p?.read == true) Icon(MaterialSymbols.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

private fun formatNumber(n: Double): String =
    if (n == Math.floor(n)) n.toLong().toString() else n.toString()
