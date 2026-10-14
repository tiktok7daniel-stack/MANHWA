package eu.kanade.translation.live

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
 * Floating glass pill showing live translation progress with a pulsing red dot.
 * Expanded while the reader menu is open, so it can be switched on or off and open the glossary.
 */
@Composable
fun LiveTranslationPill(
    status: LiveTranslator.Status,
    menuVisible: Boolean,
    onEnable: (Boolean) -> Unit,
    onPause: () -> Unit,
    onGlossary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var justFinished by remember { mutableStateOf(false) }
    LaunchedEffect(status.complete) {
        if (status.complete && status.enabled) {
            justFinished = true
            delay(2_500)
            justFinished = false
        }
    }
    val working = status.enabled && !status.paused && status.working != null
    val visible = menuVisible || (status.enabled && (working || justFinished || status.paused || status.error != null))
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .glass(RoundedCornerShape(50), accent = status.enabled, alpha = 0.78f)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer { alpha = if (working) pulse else 1f }
                    .neonGlow(CircleShape, colors.primary, if (working) 0.5f * pulse else 0.15f, 6.dp)
                    .background(
                        when {
                            !status.enabled -> Color.White.copy(alpha = 0.4f)
                            status.error != null -> Color(0xFFFFB020)
                            else -> colors.primary
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !status.enabled -> stringResource(ATMR.strings.live_off)
                    status.paused -> stringResource(ATMR.strings.live_paused, status.done, status.total)
                    status.complete -> stringResource(ATMR.strings.live_done)
                    working -> stringResource(ATMR.strings.live_working, status.working ?: 0, status.done, status.total)
                    status.error != null -> stringResource(ATMR.strings.live_error)
                    else -> stringResource(ATMR.strings.live_ready, status.done, status.total)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
            )
            if (working) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.primary)
            }
            if (menuVisible) {
                Spacer(Modifier.width(6.dp))
                if (status.enabled) {
                    SmallOrb(if (status.paused) MaterialSymbols.RoundedFilled.PlayArrow else MaterialSymbols.RoundedFilled.Pause, onPause)
                }
                SmallOrb(MaterialSymbols.AutoMirroredRounded.Label, onGlossary)
                SmallOrb(MaterialSymbols.Rounded.Translate, active = status.enabled) { onEnable(!status.enabled) }
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
            .background(if (active) colors.primary else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
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
                delay((15 - speed).coerceAtLeast(2) * 1_000L)
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
                    onValueChangeFinished = { onSpeed(value.toInt().coerceIn(1, 10)) },
                    valueRange = 1f..10f,
                    steps = 8,
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
                        ) { Text("✓", color = Color.White, fontWeight = FontWeight.Black) }
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
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.45f), maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
