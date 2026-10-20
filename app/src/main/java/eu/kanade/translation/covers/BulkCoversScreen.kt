package eu.kanade.translation.covers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.presentation.util.Screen
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Speed
import tachiyomi.domain.manga.model.MangaCover as CoverData
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA: every cover of one source's series changed at once ([CoverFinder.startBulk]). "Look" finds the sharper
 * covers and lists them with both sizes to pick from; "Fast" puts each one found at once.
 */
class BulkCoversScreen(private val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val finder = remember { context.appGraph.coverFinder }
        val bulk by finder.bulk.collectAsState()
        val run = bulk?.takeIf { it.sourceId == sourceId }
        val bottom = WindowInsets.navigationBars.asPaddingValues()

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.covers_bulk_title),
                    subtitle = run?.let { stringResource(ATMR.strings.covers_bulk_progress, it.sourceName, it.done, it.items.size) },
                    onBack = navigator::pop,
                )
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(ATMR.strings.covers_bulk_info), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
                    if (run?.running == true) {
                        ManhwaKit.GlowBar(if (run.items.isEmpty()) 0f else run.done / run.items.size.toFloat())
                        ManhwaKit.GhostButton(stringResource(ATMR.strings.covers_bulk_stop), Modifier.fillMaxWidth(), MaterialSymbols.Rounded.Close) {
                            finder.stopBulk()
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ManhwaKit.NeonButton(stringResource(ATMR.strings.covers_bulk_look), Modifier.weight(1f), MaterialSymbols.Rounded.Search) {
                                finder.startBulk(sourceId, auto = false)
                            }
                            ManhwaKit.GhostButton(stringResource(ATMR.strings.covers_bulk_fast), Modifier.weight(1f), MaterialSymbols.Rounded.Speed) {
                                finder.startBulk(sourceId, auto = true)
                            }
                        }
                        if (run != null && !run.auto && run.selectedCount > 0) {
                            ManhwaKit.NeonButton(stringResource(ATMR.strings.covers_bulk_apply, run.selectedCount), Modifier.fillMaxWidth()) {
                                finder.applySelected()
                            }
                        }
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(run?.items.orEmpty(), key = { it.manga.id }) { item ->
                        Row_(item, onToggle = { finder.toggle(item.manga.id) })
                    }
                }
            }
        }
    }

    @Composable
    private fun Row_(item: CoverFinder.Item, onToggle: () -> Unit) {
        val selectable = item.status == CoverFinder.Status.FOUND || item.status == CoverFinder.Status.SAME
        Row(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(18.dp), accent = item.status == CoverFinder.Status.APPLIED, alpha = 0.5f)
                .then(if (selectable) Modifier.pressClickable(onClick = onToggle) else Modifier)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(item.manga.asMangaCover())
            Spacer(Modifier.width(6.dp))
            Icon(MaterialSymbols.AutoMirroredRounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            val option = item.option
            if (option != null) {
                Cover(CoverData(-1L, option.sourceId, false, option.url, null))
            } else {
                Spacer(Modifier.width(54.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.manga.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ManhwaInk.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                option?.let { Text(it.sourceName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                val sizes = listOfNotNull(item.current?.let { "${it.first}×${it.second}" }, item.size?.let { "${it.first}×${it.second}" })
                if (sizes.isNotEmpty()) {
                    Text(sizes.joinToString(" → "), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
                }
                Text(
                    stringResource(
                        when (item.status) {
                            CoverFinder.Status.WAITING -> ATMR.strings.covers_st_waiting
                            CoverFinder.Status.LOOKING -> ATMR.strings.covers_st_looking
                            CoverFinder.Status.FOUND -> ATMR.strings.covers_st_found
                            CoverFinder.Status.NONE -> ATMR.strings.covers_st_none
                            CoverFinder.Status.SAME -> ATMR.strings.covers_st_same
                            CoverFinder.Status.APPLIED -> ATMR.strings.covers_st_applied
                            CoverFinder.Status.FAILED -> ATMR.strings.covers_st_failed
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.status == CoverFinder.Status.APPLIED) MaterialTheme.colorScheme.primary else ManhwaInk.muted,
                )
            }
            if (selectable) {
                Checkbox(checked = item.selected && item.status == CoverFinder.Status.FOUND, onCheckedChange = { onToggle() })
            }
        }
    }

    @Composable
    private fun Cover(data: Any) {
        AsyncImage(
            model = data,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
        )
    }
}
