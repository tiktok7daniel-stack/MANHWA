package eu.kanade.tachiyomi.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.tachiyomi.ui.home.discover.HomeIntelligence
import eu.kanade.tachiyomi.ui.home.stats.GlowIcon
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.SwapCalls
import mihon.icons.materialsymbols.rounded.TravelExplore
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA source radar: on a manhwa of the library, looks (in the background, a few hours apart) whether one of the
 * reader's home or pinned sources already has a newer chapter, and offers to read it there or move the manhwa.
 */
@Composable
fun SourceRadar(mangaId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var alert by remember(mangaId) { mutableStateOf<HomeIntelligence.SourceAlert?>(null) }

    LaunchedEffect(mangaId) {
        alert = withContext(Dispatchers.IO) {
            runCatching {
                val graph = context.appGraph
                val manga = graph.getMangaById.await(mangaId) ?: return@runCatching null
                if (!manga.favorite) return@runCatching null
                val prefs = graph.sourcePreferences
                val ids = (prefs.homeSources.get() + prefs.pinnedSources.get()).mapNotNull { it.toLongOrNull() }.distinct()
                val sources = ids.mapNotNull { graph.sourceManager.get(it) }
                    .filter { it.id != manga.source && it !is tachiyomi.domain.source.model.StubSource }
                if (sources.isEmpty()) return@runCatching null
                graph.homeIntelligence.alertFor(manga, sources)
            }.onFailure { if (it is CancellationException) throw it else logcat(LogPriority.WARN, it) }.getOrNull()
        }
    }

    val found = alert ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), accent = true, alpha = 0.5f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowIcon(MaterialSymbols.Rounded.TravelExplore, MaterialTheme.colorScheme.primary, size = 36, pulse = true)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(ATMR.strings.radar_text, number(found.chapterNumber), found.source.name, number(found.ourNumber)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ManhwaKit.NeonButton(
                text = stringResource(ATMR.strings.radar_open),
                icon = MaterialSymbols.RoundedFilled.PlayArrow,
                modifier = Modifier.weight(1f),
            ) {
                scope.launch {
                    val ids = withContext(Dispatchers.IO) { runCatching { context.appGraph.homeIntelligence.openAlert(found) }.getOrNull() }
                    if (ids != null) context.startActivity(ReaderActivity.newIntent(context, ids.first, ids.second))
                }
            }
            ManhwaKit.GhostButton(
                text = stringResource(ATMR.strings.radar_move),
                icon = MaterialSymbols.Rounded.SwapCalls,
                modifier = Modifier.weight(1f),
            ) {
                navigator?.push(MigrationConfigScreen(listOf(mangaId)))
            }
        }
    }
}

private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
