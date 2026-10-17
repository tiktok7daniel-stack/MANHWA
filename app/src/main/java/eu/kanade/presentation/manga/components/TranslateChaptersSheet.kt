package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.translation.model.Translation
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** The "translate chapters" bar of the manga screen: the button, and the progress while chapters translate. */
@Composable
fun TranslateChaptersBar(
    batch: MangaViewModel.TranslateBatch?,
    onClick: () -> Unit,
    onStopAll: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
) {
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
                if (batch == null) {
                    Text(
                        stringResource(ATMR.strings.tc_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (batch.finished) {
                    val translated = batch.total - batch.failed
                    Text(
                        if (batch.failed == 0) {
                            stringResource(ATMR.strings.tc_done, translated, batch.total)
                        } else {
                            stringResource(ATMR.strings.tc_done_failed, translated, batch.total, batch.failed)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (batch.failed > 0) colors.error else colors.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // The chapter in progress with its real percentage, then what it is doing right now
                    val stageColor = translationStageColor(batch.progress?.stage)
                    Text(
                        stringResource(ATMR.strings.tc_progress_percent, batch.current.orEmpty(), batch.percent),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TranslationStageLine(Translation.State.TRANSLATING, batch.progress, MaterialTheme.typography.labelMedium, stageColor)
                    translationPlanLines(batch.progress).firstOrNull()?.let { plan ->
                        Text(plan, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    if (batch.total > 1) {
                        Text(
                            stringResource(ATMR.strings.tc_total, (batch.done + 1).coerceAtMost(batch.total), batch.total, batch.totalPercent),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    // Time spent, and the time left from the real percentage so far
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(batch.startedAt) {
                        while (true) {
                            now = System.currentTimeMillis()
                            delay(1_000)
                        }
                    }
                    val elapsed = (now - batch.startedAt).coerceAtLeast(0)
                    val percent = batch.totalPercent
                    Text(
                        if (percent in 5..99) {
                            stringResource(ATMR.strings.tp_time_left, clock(elapsed), clock(elapsed * (100 - percent) / percent))
                        } else {
                            stringResource(ATMR.strings.tp_time, clock(elapsed))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (batch != null && !batch.finished) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${batch.totalPercent}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                )
            }
        }
        if (batch != null && !batch.finished) {
            Spacer(Modifier.height(10.dp))
            // The real percentage of all the chapters asked for, filling smoothly
            val shown by animateFloatAsState(
                targetValue = batch.totalPercent / 100f,
                animationSpec = tween(450),
                label = "batch-percent",
            )
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = colors.primary,
                trackColor = colors.primary.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
            )
            // Stops every chapter that has not ended; the translated ones stay
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onStopAll) {
                    Text(stringResource(ATMR.strings.tc_stop_all), color = colors.error)
                }
            }
        }
        if (batch != null && batch.finished && batch.failed > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetryFailed) {
                    Text(stringResource(ATMR.strings.tc_retry_failed, batch.failed))
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
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
    // MANHWA fusion: the chapters offered are the continuation's (this work's own are ready to read)
    continuation: Boolean = false,
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
                if (continuation) {
                    stringResource(ATMR.strings.tc_counts_continuation, totalChapters, candidates.size)
                } else {
                    stringResource(ATMR.strings.tc_counts, totalChapters, candidates.size)
                },
            )
            if (continuation) {
                Text(stringResource(ATMR.strings.tc_continuation_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            }
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
