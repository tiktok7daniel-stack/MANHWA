package eu.kanade.translation.unified

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.SwapCalls
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA one work, all sources, on the series screen: where else the work is ("also on X, Y") and the
 * chapters those sources have that this one lacks, each opened right there. When this series' own source is
 * down, the next chapter is one tap away on a working one.
 */
@Composable
fun UnifiedWorkCard(manga: Manga, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember { context.appGraph }
    if (!manga.favorite || !graph.translationPreferences.translationUnifiedSources().get()) return
    val scope = rememberCoroutineScope()
    var links by remember(manga.id) { mutableStateOf(graph.unifiedSources.linksOf(manga.id)) }
    var missing by remember(manga.id) { mutableStateOf<List<UnifiedSources.AltChapter>?>(null) }
    var loading by remember(manga.id) { mutableStateOf(false) }
    LaunchedEffect(manga.id) {
        links = runCatching { graph.unifiedSources.discover(manga) }.getOrDefault(links)
    }
    if (links.isEmpty()) return
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), alpha = 0.45f).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MaterialSymbols.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(ATMR.strings.unified_also_on, links.joinToString("، ") { it.sourceName }),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
        when {
            loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            missing == null -> ManhwaKit.GhostButton(stringResource(ATMR.strings.unified_find_missing), icon = MaterialSymbols.Rounded.SwapCalls, modifier = Modifier.fillMaxWidth()) {
                loading = true
                scope.launch {
                    missing = runCatching { graph.unifiedSources.missingHere(manga.id) }.getOrDefault(emptyList())
                    loading = false
                }
            }
            missing!!.isEmpty() -> Text(stringResource(ATMR.strings.unified_nothing_missing), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            else -> {
                Text(stringResource(ATMR.strings.unified_missing, missing!!.size), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    missing!!.take(MAX_SHOWN).forEach { ch ->
                        Text(
                            "${number(ch.number)} · ${ch.sourceName}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier
                                .glass(RoundedCornerShape(12.dp), accent = true, alpha = 0.5f)
                                .clickable { context.startActivity(ReaderActivity.newIntent(context, ch.mangaId, ch.chapterId)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_SHOWN = 30

private fun number(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
