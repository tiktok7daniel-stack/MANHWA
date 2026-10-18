package eu.kanade.tachiyomi.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource as ctxString
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import mihon.app.di.appGraph
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CloudOff
import mihon.icons.materialsymbols.rounded.SwapCalls
import mihon.icons.materialsymbols.rounded.TravelExplore
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA fallback for a dead source: on a series of the library whose source was hidden from home because it
 * does not work, a card says so and opens the move screen, which looks for the same series on the reader's
 * working sources and moves it with its progress and categories (only when the reader confirms there).
 */
@Composable
fun SourceDownCard(mangaId: Long, sourceId: Long, favorite: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val entries by remember { context.appGraph.sourceHealthManager.entries }.collectAsState()
    val entry = entries[sourceId]?.takeIf { it.hidden } ?: return
    if (!favorite) return
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), alpha = 0.5f).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MaterialSymbols.Rounded.CloudOff, contentDescription = null, tint = Color(0xFFFFB020), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(ATMR.strings.fallback_text, entry.fails),
                style = MaterialTheme.typography.bodyMedium,
                color = ManhwaInk.text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        // MANHWA one work, all sources: the next chapter straight from a working copy, nothing moved
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var finding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        ManhwaKit.NeonButton(
            stringResource(if (finding) ATMR.strings.hub_checking else ATMR.strings.unified_read_next),
            icon = MaterialSymbols.Rounded.TravelExplore,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (finding) return@NeonButton
            finding = true
            scope.launch {
                val graph = context.appGraph
                val target = withContext(Dispatchers.IO) {
                    runCatching {
                        val manga = graph.getMangaById.await(mangaId) ?: return@runCatching null
                        val next = graph.getChaptersByMangaId.await(mangaId).filter { !it.read && it.chapterNumber >= 0 }.minByOrNull { it.chapterNumber }?.chapterNumber
                        graph.unifiedSources.discover(manga)
                        val alts = graph.unifiedSources.alternatives(mangaId)
                        (if (next != null) alts.firstOrNull { it.number == next } else null) ?: alts.maxByOrNull { it.number }
                    }.getOrNull()
                }
                finding = false
                if (target != null) {
                    context.startActivity(eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(context, target.mangaId, target.chapterId))
                } else {
                    android.widget.Toast.makeText(context, context.ctxString(ATMR.strings.unified_not_found), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        ManhwaKit.GhostButton(stringResource(ATMR.strings.fallback_move), icon = MaterialSymbols.Rounded.SwapCalls, modifier = Modifier.fillMaxWidth()) {
            navigator?.push(MigrationConfigScreen(listOf(mangaId)))
        }
    }
}

/**
 * MANHWA missing chapters: gaps in the series' chapter numbers ("23, 24 missing"), with the move screen one tap
 * away to find a source that has them. Only whole-number gaps between chapters the source numbered are counted.
 */
@Composable
fun ChapterGapsCard(mangaId: Long, favorite: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val gaps by produceState<List<Int>>(emptyList(), mangaId) {
        value = withContext(Dispatchers.IO) {
            runCatching { ChapterGaps.missing(context.appGraph.getChaptersByMangaId.await(mangaId).map { it.chapterNumber }) }.getOrDefault(emptyList())
        }
    }
    if (!favorite || gaps.isEmpty()) return
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), alpha = 0.45f).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MaterialSymbols.Rounded.TravelExplore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(ATMR.strings.gaps_text, gaps.size, ChapterGaps.describe(gaps)),
                style = MaterialTheme.typography.bodyMedium,
                color = ManhwaInk.text,
                modifier = Modifier.weight(1f),
            )
        }
        ManhwaKit.GhostButton(stringResource(ATMR.strings.gaps_find), icon = MaterialSymbols.Rounded.SwapCalls, modifier = Modifier.fillMaxWidth()) {
            navigator?.push(MigrationConfigScreen(listOf(mangaId)))
        }
    }
}

/** The pure rule behind [ChapterGapsCard] (tested without a phone). */
object ChapterGaps {
    /** Whole chapter numbers missing between the first and last chapter; nothing when gaps are too many to be real. */
    fun missing(numbers: List<Double>): List<Int> {
        val known = numbers.filter { it >= 0 }.map { it.toInt() }.toSortedSet()
        if (known.size < 3) return emptyList()
        val gaps = (known.first()..known.last()).filter { it !in known }
        // A source that numbers by volume or skips on purpose: not missing chapters
        return if (gaps.size > known.size / 4 || gaps.size > MAX_GAPS) emptyList() else gaps
    }

    /** "23, 24, 40" or "23–30" for runs. */
    fun describe(gaps: List<Int>): String {
        if (gaps.isEmpty()) return ""
        val runs = mutableListOf<IntRange>()
        var start = gaps.first()
        var prev = start
        gaps.drop(1).forEach { n ->
            if (n != prev + 1) {
                runs += start..prev
                start = n
            }
            prev = n
        }
        runs += start..prev
        return runs.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
    }

    private const val MAX_GAPS = 40
}
