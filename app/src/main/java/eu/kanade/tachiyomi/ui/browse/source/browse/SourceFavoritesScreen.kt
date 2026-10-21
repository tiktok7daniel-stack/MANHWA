package eu.kanade.tachiyomi.ui.browse.source.browse

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA: the series of the library that come from one source and have chapters not read yet, with their newest
 * chapter (a series read to its end, or with nothing new, is not shown). Opened from a source's page; it can ask
 * the source for new chapters first.
 */
class SourceFavoritesScreen(private val sourceId: Long, private val sourceName: String) : Screen() {

    private class Entry(val manga: Manga, val unread: List<Chapter>, val newest: Chapter)

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = remember { context.appGraph }
        val scope = rememberCoroutineScope()
        var rows by remember { mutableStateOf<List<Entry>?>(null) }
        var refreshing by remember { mutableStateOf(false) }

        suspend fun load() {
            rows = withContext(Dispatchers.IO) {
                graph.getFavorites.subscribe(sourceId).first().mapNotNull { manga ->
                    val chapters = graph.getChaptersByMangaId.await(manga.id)
                    val unread = chapters.filter { !it.read }
                    if (unread.isEmpty()) return@mapNotNull null
                    Entry(manga, unread, unread.maxBy { it.chapterNumber })
                }.sortedByDescending { it.newest.dateUpload ?: it.newest.dateFetch }
            }
        }

        // Asks the source for each series' chapters, a few at a time, then lists again
        suspend fun refresh() {
            refreshing = true
            try {
                withContext(Dispatchers.IO) {
                    val source = graph.sourceManager.get(sourceId) ?: return@withContext
                    val mangas = graph.getFavorites.subscribe(sourceId).first()
                    val gate = Semaphore(REFRESH_PARALLEL)
                    coroutineScope {
                        mangas.map { manga ->
                            async {
                                gate.withPermit {
                                    try {
                                        val fresh = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                                            source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
                                        }
                                        if (!fresh.isNullOrEmpty()) graph.syncChaptersWithSource.await(fresh, manga, source)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        logcat(LogPriority.WARN, e) { "Source favorites: ${manga.title} not refreshed" }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                load()
            } finally {
                refreshing = false
            }
        }

        LaunchedEffect(Unit) { load() }
        val bottom = WindowInsets.navigationBars.asPaddingValues()

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.source_favorites_title),
                    subtitle = sourceName,
                    onBack = navigator::pop,
                    actions = {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.padding(8.dp))
                        } else {
                            ManhwaKit.IconOrb(MaterialSymbols.Rounded.Refresh) { scope.launch { refresh() } }
                        }
                    },
                )
                val list = rows
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(ATMR.strings.source_favorites_empty), color = ManhwaInk.muted)
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list, key = { it.manga.id }) { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .glass(RoundedCornerShape(18.dp), alpha = 0.5f)
                                    .pressClickable { navigator.push(MangaScreen(row.manga.id)) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MangaCover.Book(row.manga, Modifier.width(56.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(row.manga.title, fontWeight = FontWeight.Bold, color = ManhwaInk.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(row.newest.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(ATMR.strings.source_favorites_unread, row.unread.size), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
                                }
                                // Straight to the first chapter not read yet
                                ManhwaKit.NeonButton(stringResource(ATMR.strings.source_favorites_read)) {
                                    val first = row.unread.minBy { it.chapterNumber }
                                    context.startActivity(ReaderActivity.newIntent(context, row.manga.id, first.id))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val REFRESH_PARALLEL = 3
        private const val REFRESH_TIMEOUT_MS = 20_000L
    }
}
