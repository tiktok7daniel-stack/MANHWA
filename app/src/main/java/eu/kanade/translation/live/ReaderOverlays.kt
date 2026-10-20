package eu.kanade.translation.live

import eu.kanade.presentation.components.ManhwaInk

import mihon.icons.materialsymbols.rounded.Check
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.translation.glossary.GlossaryStore
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.automirroredrounded.Label
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Floating glass pill bound to the live translator's real state: a pulsing red dot and "Translating page 3
 * of 15" while working, "page ready" with the background progress once the page on screen is done, and a
 * clear message with a retry button when a request fails. With the reader menu open it can pause, switch
 * live translation on or off and open the glossary.
 */
@Composable
fun LiveTranslationPill(
    status: LiveTranslator.Status,
    menuVisible: Boolean,
    onEnable: (Boolean) -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onGlossary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val phase = status.phase
    var justFinished by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == LiveTranslator.Phase.DONE) {
            justFinished = true
            delay(3_000)
            justFinished = false
        }
    }
    val working = phase == LiveTranslator.Phase.WORKING
    val visible = phase != LiveTranslator.Phase.OFF && (menuVisible || when (phase) {
        LiveTranslator.Phase.OFF -> false
        LiveTranslator.Phase.DONE -> justFinished
        LiveTranslator.Phase.IDLE -> status.total > 0 && !status.focusReady
        else -> true
    })
    // The pulse only runs while a page is being translated, so an idle pill costs nothing
    val pulseState = if (working) {
        rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "pulse",
        )
    } else {
        null
    }
    val pulse = pulseState?.value ?: 1f
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .neonGlow(RoundedCornerShape(22.dp), colors.primary, if (working) 0.25f * pulse else 0.08f, 10.dp)
                .glass(RoundedCornerShape(22.dp), accent = status.enabled, alpha = 0.82f)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .graphicsLayer { alpha = if (working) pulse else 1f }
                        .neonGlow(CircleShape, colors.primary, if (working) 0.5f * pulse else 0.15f, 6.dp)
                        .background(
                            when (phase) {
                                LiveTranslator.Phase.OFF, LiveTranslator.Phase.PAUSED -> Color.White.copy(alpha = 0.45f)
                                LiveTranslator.Phase.ERROR -> Color(0xFFFFB020)
                                LiveTranslator.Phase.DONE -> Color(0xFF3DDC97)
                                else -> colors.primary
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.widthIn(max = 250.dp)) {
                    Text(
                        when (phase) {
                            LiveTranslator.Phase.OFF -> stringResource(ATMR.strings.live_off)
                            LiveTranslator.Phase.PAUSED -> stringResource(ATMR.strings.live_paused, status.translated, status.total)
                            LiveTranslator.Phase.DONE -> stringResource(ATMR.strings.live_done)
                            LiveTranslator.Phase.ERROR -> stringResource(ATMR.strings.live_error)
                            LiveTranslator.Phase.WORKING ->
                                if (status.focusReady) {
                                    stringResource(ATMR.strings.live_page_ready, status.focusPage)
                                } else {
                                    stringResource(ATMR.strings.live_working, status.workingPage ?: status.focusPage, status.total)
                                }
                            LiveTranslator.Phase.IDLE ->
                                if (status.focusReady || status.total == 0) {
                                    stringResource(ATMR.strings.live_ready, status.translated, status.total)
                                } else {
                                    stringResource(ATMR.strings.live_waiting, status.focusPage)
                                }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail = when {
                        phase == LiveTranslator.Phase.ERROR -> status.error
                        working && status.focusReady ->
                            stringResource(ATMR.strings.live_background, status.workingPage ?: 0, status.translated, status.total)
                        else -> null
                    }
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (working) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.primary)
                }
                if (phase == LiveTranslator.Phase.ERROR) {
                    Spacer(Modifier.width(6.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ManhwaKit.accentBrush())
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(MaterialSymbols.Rounded.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(ATMR.strings.recs_retry),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                        )
                    }
                }
                if (menuVisible) {
                    Spacer(Modifier.width(6.dp))
                    if (status.enabled && phase != LiveTranslator.Phase.ERROR) {
                        SmallOrb(
                            if (phase == LiveTranslator.Phase.PAUSED) MaterialSymbols.RoundedFilled.PlayArrow else MaterialSymbols.RoundedFilled.Pause,
                            onPause,
                        )
                    }
                    SmallOrb(MaterialSymbols.AutoMirroredRounded.Label, onGlossary)
                    SmallOrb(MaterialSymbols.Rounded.Translate, active = status.enabled) { onEnable(!status.enabled) }
                }
            }
            if (status.total > 0 && status.enabled && phase != LiveTranslator.Phase.DONE) {
                Spacer(Modifier.height(6.dp))
                ManhwaKit.GlowBar(
                    status.translated.toFloat() / status.total,
                    Modifier.padding(end = 6.dp).width(220.dp),
                )
            }
        }
    }
}

@Composable
private fun SmallOrb(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) =
    SmallOrb(icon, active = false, onClick = onClick)

@Composable
private fun SmallOrb(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(if (active) colors.primary else ManhwaInk.text.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ManhwaInk.text, modifier = Modifier.size(17.dp))
    }
}

/**
 * Auto-scroll capsule: one tap to play or pause, a slider for the speed, and it shrinks to a small orb
 * a few seconds after the last touch so it never covers the art.
 */
@Composable
fun AutoScrollControl(
    webtoon: Boolean,
    speed: Int,
    onSpeed: (Int) -> Unit,
    onScrollBy: (Int) -> Unit,
    onNextPage: () -> Unit,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var playing by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current.density

    LaunchedEffect(expanded, lastTouch) {
        if (expanded) {
            delay(3_500)
            expanded = false
        }
    }
    LaunchedEffect(playing, paused, speed, webtoon) {
        if (!playing || paused) return@LaunchedEffect
        if (webtoon) {
            var last = 0L
            var carry = 0f
            while (isActive) {
                withFrameNanos { now ->
                    if (last != 0L) {
                        val pixels = speed * 26f * density * (now - last) / 1_000_000_000f + carry
                        val whole = pixels.toInt()
                        carry = pixels - whole
                        if (whole > 0) onScrollBy(whole)
                    }
                    last = now
                }
            }
        } else {
            while (isActive) {
                delay((MAX_SCROLL_SPEED + 1 - speed).coerceAtLeast(1) * 1_000L)
                onNextPage()
            }
        }
    }

    Row(
        modifier = modifier
            .glass(RoundedCornerShape(50), accent = playing, alpha = if (expanded) 0.82f else 0.5f)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .then(
                    if (playing) {
                        Modifier
                            .neonGlow(CircleShape, colors.primary, 0.4f, 10.dp)
                            .clip(CircleShape)
                            .background(ManhwaKit.accentBrush())
                    } else {
                        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f))
                    },
                )
                .clickable {
                    playing = !playing
                    expanded = true
                    lastTouch++
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) MaterialSymbols.RoundedFilled.Pause else MaterialSymbols.RoundedFilled.PlayArrow,
                contentDescription = stringResource(ATMR.strings.autoscroll),
                tint = Color.White,
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var value by remember(speed) { mutableFloatStateOf(speed.toFloat()) }
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        lastTouch++
                    },
                    onValueChangeFinished = { onSpeed(value.roundToInt().coerceIn(1, MAX_SCROLL_SPEED)) },
                    valueRange = 1f..MAX_SCROLL_SPEED.toFloat(),
                    steps = MAX_SCROLL_SPEED - 2,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier.width(150.dp).padding(horizontal = 8.dp),
                )
                Text(
                    "${value.toInt()}x",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = colors.primary,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
        }
    }
}

/** Glass glossary of a series: search, edit, add or remove names and terms used by every translation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossarySheet(
    mangaId: Long,
    store: GlossaryStore,
    onDismiss: () -> Unit,
    onRetranslate: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val entries by remember(mangaId) { store.flow(mangaId) }.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var newSource by rememberSaveable { mutableStateOf("") }
    var newTranslation by rememberSaveable { mutableStateOf("") }
    var changed by remember { mutableStateOf(false) }
    val q = query.trim().lowercase()
    val shown = entries.filter { q.isEmpty() || it.source.lowercase().contains(q) || it.translation.lowercase().contains(q) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xF20A080C),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            ManhwaKit.SectionTitle(
                title = stringResource(ATMR.strings.glossary_title),
                subtitle = stringResource(ATMR.strings.glossary_count, entries.size),
            )
            Spacer(Modifier.height(12.dp))
            ManhwaKit.SearchField(query, stringResource(ATMR.strings.glossary_search), { query = it })
            Spacer(Modifier.height(12.dp))
            // Add a term
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlineField(newSource, stringResource(ATMR.strings.glossary_source), Modifier.weight(1f)) { newSource = it }
                Text("→", color = colors.primary, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp))
                InlineField(newTranslation, stringResource(ATMR.strings.glossary_translation), Modifier.weight(1f)) { newTranslation = it }
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(38.dp)
                        .neonGlow(CircleShape, colors.primary, 0.3f, 8.dp)
                        .clip(CircleShape)
                        .background(ManhwaKit.accentBrush())
                        .clickable {
                            if (newSource.isNotBlank() && newTranslation.isNotBlank()) {
                                store.put(mangaId, newSource, newTranslation)
                                newSource = ""
                                newTranslation = ""
                                changed = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(MaterialSymbols.Rounded.Add, contentDescription = null, tint = Color.White) }
            }
            if (changed && onRetranslate != null) {
                Spacer(Modifier.height(10.dp))
                ManhwaKit.NeonButton(
                    stringResource(ATMR.strings.glossary_apply),
                    Modifier.fillMaxWidth(),
                    MaterialSymbols.Rounded.Refresh,
                ) {
                    onRetranslate()
                    onDismiss()
                }
            }
        }
        if (entries.isEmpty()) {
            Text(
                stringResource(ATMR.strings.glossary_empty),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(18.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = 460.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.source }) { entry ->
                var edit by remember(entry) { mutableStateOf(entry.translation) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(16.dp), accent = entry.edited, alpha = 0.38f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.source,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("→", color = colors.primary, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp))
                    InlineField(edit, "", Modifier.weight(1f)) { edit = it }
                    if (edit != entry.translation && edit.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(colors.primary)
                                .clickable {
                                    store.put(mangaId, entry.source, edit)
                                    changed = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                    androidx.compose.material3.Icon(
                        mihon.icons.materialsymbols.MaterialSymbols.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable {
                                store.remove(mangaId, entry.source)
                                changed = true
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(MaterialSymbols.Rounded.Delete, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(17.dp)) }
                }
            }
        }
    }
}

@Composable
private fun InlineField(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ManhwaInk.text.copy(alpha = 0.06f))
            .border(1.dp, ManhwaInk.text.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.45f), maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = ManhwaInk.text, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * MANHWA auto-scroll button on its own: shown and hidden by a double tap on the page. One tap plays or pauses,
 * the speed (1 to [MAX_SCROLL_SPEED]) is set on the slider next to it, which folds away a few seconds after the
 * last touch so the button never covers the art.
 */
@Composable
fun ScrollButton(
    playing: Boolean,
    onPlaying: (Boolean) -> Unit,
    speed: Int,
    onSpeed: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Each touch of the button (its owner hides it a moment after the last one)
    onTouch: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableIntStateOf(0) }
    LaunchedEffect(lastTouch) { if (lastTouch > 0) onTouch() }
    LaunchedEffect(expanded, lastTouch) {
        if (expanded) {
            delay(4_000)
            expanded = false
        }
    }
    Row(
        modifier = modifier
            .glass(RoundedCornerShape(50), accent = playing, alpha = if (expanded) 0.85f else 0.6f)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .then(
                    if (playing) {
                        Modifier
                            .neonGlow(CircleShape, colors.primary, 0.4f, 10.dp)
                            .clip(CircleShape)
                            .background(ManhwaKit.accentBrush())
                    } else {
                        Modifier.clip(CircleShape).background(ManhwaInk.text.copy(alpha = 0.1f))
                    },
                )
                .clickable {
                    onPlaying(!playing)
                    expanded = true
                    lastTouch++
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) MaterialSymbols.RoundedFilled.Pause else MaterialSymbols.RoundedFilled.PlayArrow,
                contentDescription = stringResource(ATMR.strings.autoscroll),
                tint = ManhwaInk.text,
            )
        }
        // The speed, always shown; a tap opens the slider
        Text(
            "${speed.coerceIn(1, MAX_SCROLL_SPEED)}x",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = colors.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable {
                    expanded = !expanded
                    lastTouch++
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        AnimatedVisibility(visible = expanded, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
            var value by remember(speed) { mutableFloatStateOf(speed.coerceIn(1, MAX_SCROLL_SPEED).toFloat()) }
            Slider(
                value = value,
                onValueChange = {
                    value = it
                    lastTouch++
                },
                onValueChangeFinished = { onSpeed(value.roundToInt().coerceIn(1, MAX_SCROLL_SPEED)) },
                valueRange = 1f..MAX_SCROLL_SPEED.toFloat(),
                steps = MAX_SCROLL_SPEED - 2,
                colors = SliderDefaults.colors(
                    thumbColor = ManhwaInk.text,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = ManhwaInk.text.copy(alpha = 0.15f),
                ),
                modifier = Modifier.width(170.dp).padding(end = 8.dp),
            )
        }
    }
}

/** The fastest auto-scroll speed the reader can pick. */
const val MAX_SCROLL_SPEED = 15
