package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** The "translate chapters" bar of the manga screen: the button, and the progress while chapters translate. */
@Composable
fun TranslateChaptersBar(batch: MangaViewModel.TranslateBatch?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(18.dp), accent = batch != null, alpha = 0.5f)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(ManhwaKit.accentBrush()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MaterialSymbols.Rounded.Translate, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(ATMR.strings.tc_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (batch == null) {
                        stringResource(ATMR.strings.tc_subtitle)
                    } else if (batch.finished) {
                        val translated = batch.total - batch.failed
                        if (batch.failed == 0) {
                            stringResource(ATMR.strings.tc_done, translated, batch.total)
                        } else {
                            stringResource(ATMR.strings.tc_done_failed, translated, batch.total, batch.failed)
                        }
                    } else if (batch.pages > 0 && !batch.fetching) {
                        stringResource(
                            ATMR.strings.tc_progress_pages,
                            batch.current.orEmpty(),
                            batch.page,
                            batch.pages,
                            batch.done + 1,
                            batch.total,
                        )
                    } else {
                        stringResource(
                            if (batch.fetching) ATMR.strings.tc_progress_fetching else ATMR.strings.tc_progress,
                            batch.current.orEmpty(),
                            batch.done + 1,
                            batch.total,
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        batch == null -> colors.onSurfaceVariant
                        batch.finished && batch.failed > 0 -> colors.error
                        else -> colors.primary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (batch != null && !batch.finished) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = {
                    // Finished chapters, plus the share of pages read in the one in progress
                    val partial = if (batch.pages > 0) batch.page.toFloat() / batch.pages else 0f
                    ((batch.done + partial) / batch.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
            )
        }
    }
}

/**
 * Glass sheet to pick how many chapters to translate and where to start. Chapters that are not downloaded
 * are fetched by themselves; nothing has to be downloaded by hand first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateChaptersSheet(
    candidates: List<Chapter>,
    totalChapters: Int,
    onDismiss: () -> Unit,
    onStart: (count: Int?, startChapterId: Long?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val max = candidates.size.coerceAtLeast(1)
    var count by remember { mutableIntStateOf(minOf(3, max)) }
    var all by remember { mutableStateOf(false) }
    var start by remember { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xF2120609),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ManhwaKit.SectionTitle(
                stringResource(ATMR.strings.tc_title),
                stringResource(ATMR.strings.tc_counts, totalChapters, candidates.size),
            )
            if (candidates.isEmpty()) {
                Text(stringResource(ATMR.strings.tc_none), color = Color.White.copy(alpha = 0.7f))
                return@Column
            }
            // How many
            Row(
                modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.45f).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StepButton("−") {
                    all = false
                    count = (count - 1).coerceAtLeast(1)
                }
                Text(
                    if (all) stringResource(ATMR.strings.tc_all, candidates.size) else stringResource(ATMR.strings.tc_n_chapters, count),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = colors.primary,
                )
                StepButton("+") {
                    all = false
                    count = (count + 1).coerceAtMost(max)
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val quick = listOf(1, 3, 5, 10).filter { it <= max }
                quick.forEach { n ->
                    item {
                        ManhwaKit.Chip(stringResource(ATMR.strings.tc_n_chapters, n), !all && count == n) {
                            all = false
                            count = n
                        }
                    }
                }
                item { ManhwaKit.Chip(stringResource(ATMR.strings.tc_all_unread), all) { all = true } }
            }
            // Where to start
            Text(stringResource(ATMR.strings.tc_start_from), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).glass(RoundedCornerShape(16.dp), alpha = 0.35f),
            ) {
                itemsIndexed(candidates) { index, chapter ->
                    val picked = index == start
                    val inRange = index >= start && (all || index < start + count)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { start = index }
                            .background(if (picked) colors.primary.copy(alpha = 0.25f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(if (inRange) colors.primary else Color.White.copy(alpha = 0.2f)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            chapter.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(stringResource(ATMR.strings.tc_hint), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            ManhwaKit.NeonButton(
                text = stringResource(ATMR.strings.tc_start),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onStart(if (all) null else count, candidates.getOrNull(start)?.id)
                onDismiss()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(ManhwaKit.accentBrush())
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}
