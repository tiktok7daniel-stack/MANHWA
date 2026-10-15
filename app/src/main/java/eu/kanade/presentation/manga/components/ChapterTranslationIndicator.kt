package eu.kanade.presentation.manga.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Error
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

enum class ChapterTranslationAction {
    START,
    CANCEL,
    DELETE,
}

/**
 * The translation mark next to a chapter. While the chapter is fetched or translated it is a ring that fills
 * with the real percentage (the number in the middle), coloured by stage; then a check when it is translated
 * and saved, or a warning with the reason and "try again" when it failed. It never spins without saying how far
 * it got.
 */
@Composable
fun ChapterTranslationIndicator(
    enabled: Boolean,
    translationStateProvider: () -> Translation.State,
    onClick: (ChapterTranslationAction) -> Unit,
    modifier: Modifier = Modifier,
    progressProvider: () -> Translation.Progress? = { null },
) {
    val state = translationStateProvider()
    val progress = progressProvider()
    val kind = when {
        state == Translation.State.TRANSLATED -> Kind.DONE
        state == Translation.State.ERROR -> Kind.FAILED
        state == Translation.State.QUEUE || state == Translation.State.TRANSLATING || progress != null -> Kind.WORKING
        else -> Kind.IDLE
    }
    Crossfade(targetState = kind, animationSpec = tween(350), label = "translation-mark") { shown ->
        when (shown) {
            Kind.IDLE -> NotTranslatedIndicator(enabled = enabled, modifier = modifier, onClick = onClick)
            Kind.WORKING -> ProgressRing(enabled = enabled, progress = progress, modifier = modifier, onClick = onClick)
            Kind.DONE -> TranslatedIndicator(enabled = enabled, modifier = modifier, onClick = onClick)
            Kind.FAILED -> ErrorIndicator(enabled = enabled, progress = progress, modifier = modifier, onClick = onClick)
        }
    }
}

private enum class Kind { IDLE, WORKING, DONE, FAILED }

/** The colour of a stage: downloading, reading, translating and finishing each look different. */
@Composable
fun translationStageColor(stage: Translation.Stage?): Color {
    val colors = MaterialTheme.colorScheme
    return when (stage) {
        null, Translation.Stage.QUEUED -> colors.onSurfaceVariant
        Translation.Stage.DOWNLOADING -> colors.tertiary
        Translation.Stage.READING -> colors.secondary
        Translation.Stage.PLANNING, Translation.Stage.TRANSLATING -> colors.primary
        Translation.Stage.REVIEWING, Translation.Stage.SAVING -> FinishingColor
    }
}

/** Current time, ticking every few seconds while something is in progress (for "waiting" and the timers). */
@Composable
private fun rememberNow(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    return now
}

/**
 * The line under a chapter's title while it is translated from the manga screen: the stage with its counter
 * ("Reading the text 11/18"), or why it waits when nothing moved for a while, or why it failed.
 */
@Composable
fun translationStageLabel(state: Translation.State, progress: Translation.Progress?): String? {
    val now = rememberNow(active = progress != null && state != Translation.State.TRANSLATED && state != Translation.State.ERROR)
    if (state == Translation.State.TRANSLATED) return null
    if (state == Translation.State.ERROR) {
        return stringResource(progress?.note?.let(::failureText) ?: ATMR.strings.tp_failed_other)
    }
    if (progress == null) {
        return if (state == Translation.State.QUEUE) stringResource(ATMR.strings.tp_queued) else null
    }
    if (progress.stage == Translation.Stage.QUEUED) return stringResource(ATMR.strings.tp_queued)
    // Nothing moved for a while: say what it is waiting on instead of looking busy
    val quiet = now - progress.updatedAt > WAITING_MS
    val note = progress.note
    if (note != null && (note != Translation.Note.AI || quiet)) return stringResource(waitingText(note))
    if (quiet) {
        return stringResource(if (progress.stage == Translation.Stage.TRANSLATING) ATMR.strings.tp_wait_ai else ATMR.strings.tp_waiting)
    }
    val counted = progress.total > 0
    val stage = when (progress.stage) {
        Translation.Stage.QUEUED -> stringResource(ATMR.strings.tp_queued)
        Translation.Stage.DOWNLOADING ->
            if (counted) stringResource(ATMR.strings.tp_downloading_n, progress.done, progress.total) else stringResource(ATMR.strings.tp_downloading)
        Translation.Stage.READING ->
            if (counted) stringResource(ATMR.strings.tp_reading_n, progress.done, progress.total) else stringResource(ATMR.strings.tp_reading)
        Translation.Stage.PLANNING -> stringResource(ATMR.strings.tp_planning)
        Translation.Stage.TRANSLATING ->
            if (counted) stringResource(ATMR.strings.tp_translating_n, progress.done, progress.total) else stringResource(ATMR.strings.tp_translating)
        Translation.Stage.REVIEWING -> stringResource(ATMR.strings.tp_reviewing)
        Translation.Stage.SAVING -> stringResource(ATMR.strings.tp_saving)
    }
    // How much of the chapter came straight from the translation collection
    return if (progress.library > 0 && progress.stage >= Translation.Stage.TRANSLATING) {
        stage + " · " + stringResource(ATMR.strings.tp_library, progress.library)
    } else {
        stage
    }
}

private fun waitingText(note: Translation.Note) = when (note) {
    Translation.Note.AI -> ATMR.strings.tp_wait_ai
    Translation.Note.RETRY -> ATMR.strings.tp_wait_retry
    Translation.Note.KEY_LIMIT -> ATMR.strings.tp_wait_key_limit
    Translation.Note.READER -> ATMR.strings.tp_wait_reader
    Translation.Note.NO_INTERNET -> ATMR.strings.tp_wait_internet
    else -> ATMR.strings.tp_waiting
}

private fun failureText(note: Translation.Note) = when (note) {
    Translation.Note.DOWNLOAD_FAILED -> ATMR.strings.tp_failed_download
    Translation.Note.NOT_DOWNLOADED -> ATMR.strings.tp_failed_not_downloaded
    Translation.Note.KEY_INVALID -> ATMR.strings.tp_failed_key
    Translation.Note.KEY_LIMIT -> ATMR.strings.tp_failed_key_limit
    Translation.Note.NO_INTERNET -> ATMR.strings.tp_failed_internet
    Translation.Note.STALLED -> ATMR.strings.tp_failed_stalled
    Translation.Note.SAVE_FAILED -> ATMR.strings.tp_failed_save
    else -> ATMR.strings.tp_failed_other
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun NotTranslatedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterTranslationAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterTranslationAction.START) },
                onClick = { onClick(ChapterTranslationAction.START) },
            )
            .secondaryItemAlpha(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_translate_circle),
            contentDescription = stringResource(ATMR.strings.manga_translate),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A ring that fills with the real percentage, the number in the middle, coloured by stage. */
@Composable
private fun ProgressRing(
    enabled: Boolean,
    progress: Translation.Progress?,
    onClick: (ChapterTranslationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    val percent = progress?.percent ?: 0
    val queued = progress == null || progress.stage == Translation.Stage.QUEUED
    val shown by animateFloatAsState(targetValue = percent / 100f, animationSpec = tween(450), label = "translation-percent")
    val color = translationStageColor(progress?.stage)
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { isMenuExpanded = true },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { shown },
            modifier = Modifier.size(RingSize),
            color = color.copy(alpha = if (queued) 0.35f else 1f),
            strokeWidth = RingStroke,
            trackColor = color.copy(alpha = 0.16f),
            strokeCap = StrokeCap.Round,
        )
        Text(
            text = if (queued) "…" else "$percent",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color.copy(alpha = if (queued) 0.6f else 1f),
            maxLines = 1,
        )

        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            val now = rememberNow(active = isMenuExpanded)
            val label = translationStageLabel(Translation.State.TRANSLATING, progress)
            DropdownMenuItem(
                text = { Text(text = "$percent% · ${label.orEmpty()}", color = color, fontWeight = FontWeight.Bold) },
                onClick = {},
                enabled = false,
            )
            if (progress != null && !queued) {
                val elapsed = now - progress.startedAt
                val left = if (percent in 5..99) elapsed * (100 - percent) / percent else null
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (left != null) {
                                stringResource(ATMR.strings.tp_time_left, formatDuration(elapsed), formatDuration(left))
                            } else {
                                stringResource(ATMR.strings.tp_time, formatDuration(elapsed))
                            },
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_cancel)) },
                onClick = {
                    onClick(ChapterTranslationAction.CANCEL)
                    isMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun TranslatedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterTranslationAction) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { isMenuExpanded = true },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_translate_circle_filled),
            contentDescription = null,
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_delete)) },
                onClick = {
                    onClick(ChapterTranslationAction.DELETE)
                    isMenuExpanded = false
                },
            )
        }
    }
}

/** A warning; tapping it shows why it failed and "try again". */
@Composable
private fun ErrorIndicator(
    enabled: Boolean,
    progress: Translation.Progress?,
    modifier: Modifier = Modifier,
    onClick: (ChapterTranslationAction) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterTranslationAction.START) },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Error,
            contentDescription = stringResource(MR.strings.chapter_error),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.error,
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(progress?.note?.let(::failureText) ?: ATMR.strings.tp_failed_other),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                },
                onClick = {},
                enabled = false,
            )
            progress?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                DropdownMenuItem(text = { Text(text = detail, maxLines = 3) }, onClick = {}, enabled = false)
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(ATMR.strings.tp_retry)) },
                onClick = {
                    onClick(ChapterTranslationAction.START)
                    isMenuExpanded = false
                },
            )
        }
    }
}

private fun Modifier.commonClickable(
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) = this.combinedClickable(
    enabled = enabled,
    onLongClick = {
        onLongClick()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    },
    onClick = onClick,
    role = Role.Button,
    interactionSource = null,
    indication = ripple(
        bounded = false,
        radius = IconButtonTokens.StateLayerSize / 2,
    ),
)

private val IndicatorSize = 23.dp
private val RingSize = 30.dp
private val RingStroke = 2.75.dp
private val FinishingColor = Color(0xFF2E9E5B)

/** Nothing moved for this long: the line says what it is waiting on. */
private const val WAITING_MS = 20_000L
private const val TICK_MS = 2_000L
