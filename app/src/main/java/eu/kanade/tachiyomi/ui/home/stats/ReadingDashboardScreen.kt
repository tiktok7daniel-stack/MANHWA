package eu.kanade.tachiyomi.ui.home.stats

import androidx.compose.foundation.layout.Arrangement
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

        LaunchedEffect(Unit) {
            snapshot = runCatching { engine.load() }.onFailure { logcat(LogPriority.ERROR, it) }.getOrNull()
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues()

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
                    if (data.series.isEmpty()) {
                        item {
                            Text(
                                stringResource(ATMR.strings.game_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                    items(data.series, key = { it.manga.id }) { series ->
                        SeriesLevelCard(
                            series = series,
                            onOpen = { navigator.push(MangaScreen(series.manga.id)) },
                            onContinue = {
                                scope.launch {
                                    val chapter = engine.nextChapter(series.manga.id)
                                    if (chapter != null) {
                                        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
                                    } else {
                                        navigator.push(MangaScreen(series.manga.id))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
