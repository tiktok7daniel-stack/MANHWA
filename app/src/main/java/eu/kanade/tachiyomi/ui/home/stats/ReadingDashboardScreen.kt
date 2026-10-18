package eu.kanade.tachiyomi.ui.home.stats

import eu.kanade.presentation.components.ManhwaInk

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** MANHWA: the whole reading game: streak, hunter rank and the level of every manhwa read. */
class ReadingDashboardScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val engine = remember { context.appGraph.readingProgressEngine }
        var snapshot by remember { mutableStateOf<ReadingProgressEngine.Snapshot?>(null) }
        var filter by remember { mutableStateOf(Filter.ALL) }
        var details by remember { mutableStateOf<ReadingProgressEngine.Series?>(null) }

        fun continueReading(series: ReadingProgressEngine.Series) {
            scope.launch {
                val chapter = engine.nextChapter(series.manga.id)
                if (chapter != null) {
                    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
                } else {
                    navigator.push(MangaScreen(series.manga.id))
                }
            }
        }

        LaunchedEffect(Unit) {
            snapshot = runCatching { engine.load() }.onFailure { logcat(LogPriority.ERROR, it) }.getOrNull()
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues()

        details?.let { series ->
            SeriesDetailsSheet(
                series = series,
                onDismiss = { details = null },
                onContinue = {
                    details = null
                    continueReading(series)
                },
                onOpen = {
                    details = null
                    navigator.push(MangaScreen(series.manga.id))
                },
            )
        }

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.game_title),
                    subtitle = stringResource(ATMR.strings.game_subtitle),
                    onBack = navigator::pop,
                )
                val data = snapshot
                if (data == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { HunterCard(data.hunter, data.totalChapters) }
                    item { StreakCard(data.streak) }
                    // Quick filters: the cards change with a smooth move
                    item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Filter.entries.forEach { option ->
                                ManhwaKit.Chip(filterName(option), selected = filter == option) { filter = option }
                            }
                        }
                    }
                    val shown = when (filter) {
                        Filter.ALL -> data.series
                        Filter.TOP -> data.series.sortedWith(compareByDescending<ReadingProgressEngine.Series> { it.level.level }.thenByDescending { it.read })
                        Filter.READING -> data.series.filter { it.readingNow }
                        Filter.COMPLETED -> data.series.filter { it.completed }
                    }
                    if (data.series.isNotEmpty() && shown.isEmpty()) {
                        item(key = "empty-filter") {
                            Text(
                                stringResource(ATMR.strings.filter_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ManhwaInk.text.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth().padding(16.dp).animateItem(),
                            )
                        }
                    }
                    if (data.series.isEmpty()) {
                        item {
                            Text(
                                stringResource(ATMR.strings.game_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ManhwaInk.text.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                    items(shown, key = { it.manga.id }) { series ->
                        SeriesLevelCard(
                            series = series,
                            onOpen = { details = series },
                            onContinue = { continueReading(series) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    private enum class Filter { ALL, TOP, READING, COMPLETED }

    @Composable
    private fun filterName(filter: Filter): String = when (filter) {
        Filter.ALL -> stringResource(ATMR.strings.filter_all)
        Filter.TOP -> stringResource(ATMR.strings.filter_top)
        Filter.READING -> stringResource(ATMR.strings.filter_reading)
        Filter.COMPLETED -> stringResource(ATMR.strings.filter_completed)
    }
}
