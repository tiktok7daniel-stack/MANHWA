package eu.kanade.translation.live

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Label
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.FormatBold
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.i18n.at.ATMR

/**
 * The one MANHWA button of the reader, on the side of the page: translation progress as a ring around it,
 * and a menu with "translate this chapter now", automatic translation, showing translations, auto-scroll
 * with its speed, bubble editing, typeface and size, and the glossary.
 */
@Composable
fun ReaderActionOrb(
    status: LiveTranslator.Status,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    showTranslations: Boolean,
    onShowTranslations: (Boolean) -> Unit,
    onTranslateNow: () -> Unit,
    onAuto: (Boolean) -> Unit,
    onRetry: () -> Unit,
    scrolling: Boolean,
    onScrolling: (Boolean) -> Unit,
    speed: Int,
    onSpeed: (Int) -> Unit,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onStyle: () -> Unit,
    onGlossary: () -> Unit,
    autoEnabled: Boolean,
    modifier: Modifier = Modifier,
    onHold: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        AnimatedVisibility(visible = expanded, enter = fadeIn() + scaleIn(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)), exit = fadeOut() + scaleOut(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f))) {
            Column(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .width(280.dp)
                    .neonGlow(RoundedCornerShape(24.dp), colors.primary, 0.12f, 12.dp)
                    .glass(RoundedCornerShape(24.dp), accent = true, alpha = 0.9f)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Translate now: the big action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ManhwaKit.accentBrush())
                        .clickable {
                            onTranslateNow()
                            onExpanded(false)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MaterialSymbols.Rounded.Translate, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(ATMR.strings.orb_translate_now), color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
                        Text(statusLine(status), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (status.phase == LiveTranslator.Phase.ERROR) {
                    ActionRow(MaterialSymbols.Rounded.Refresh, stringResource(ATMR.strings.recs_retry), onClick = onRetry)
                }
                SwitchAction(MaterialSymbols.Rounded.Translate, stringResource(ATMR.strings.orb_auto), autoEnabled, onAuto)
                SwitchAction(MaterialSymbols.AutoMirroredRounded.Label, stringResource(ATMR.strings.pref_show_translations), showTranslations, onShowTranslations)
                // Auto-scroll with its speed
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (scrolling) colors.primary else Color.White.copy(alpha = 0.1f))
                            .clickable { onScrolling(!scrolling) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (scrolling) MaterialSymbols.RoundedFilled.Pause else MaterialSymbols.RoundedFilled.PlayArrow,
                            contentDescription = stringResource(ATMR.strings.autoscroll),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(ATMR.strings.autoscroll), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        var value by remember(speed) { mutableFloatStateOf(speed.toFloat()) }
                        Slider(
                            value = value,
                            onValueChange = { value = it },
                            onValueChangeFinished = { onSpeed(value.toInt().coerceIn(1, 10)) },
                            valueRange = 1f..10f,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = colors.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                            ),
                            modifier = Modifier.height(28.dp),
                        )
                    }
                    Text("${speed}x", color = colors.primary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
                }
                SwitchAction(MaterialSymbols.Rounded.Edit, stringResource(ATMR.strings.orb_edit), editing) {
                    onEditing(it)
                    if (it) onExpanded(false)
                }
                ActionRow(MaterialSymbols.Rounded.FormatBold, stringResource(ATMR.strings.orb_style)) {
                    onStyle()
                    onExpanded(false)
                }
                ActionRow(MaterialSymbols.AutoMirroredRounded.Label, stringResource(ATMR.strings.orb_glossary)) {
                    onGlossary()
                    onExpanded(false)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Quick pause while auto-scroll runs and the menu is closed
            if (scrolling && !expanded) {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(40.dp)
                        .glass(CircleShape, accent = true, alpha = 0.7f)
                        .clickable { onScrolling(false) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MaterialSymbols.RoundedFilled.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(62.dp)) {
                val working = status.phase == LiveTranslator.Phase.WORKING
                if (status.enabled && status.total > 0 && status.phase != LiveTranslator.Phase.DONE) {
                    CircularProgressIndicator(
                        progress = { status.translated.toFloat() / status.total },
                        modifier = Modifier.size(62.dp),
                        strokeWidth = 3.dp,
                        color = colors.primary,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .neonGlow(CircleShape, colors.primary, if (working || expanded) 0.45f else 0.2f, 10.dp)
                        .clip(CircleShape)
                        .background(if (expanded) ManhwaKit.accentBrush() else androidx.compose.ui.graphics.SolidColor(Color(0xD9140508)))
                        // MANHWA: tap opens the menu; hold opens the quick HUD
                        .pointerInput(expanded) {
                            detectTapGestures(
                                onTap = { onExpanded(!expanded) },
                                onLongPress = { if (!expanded) onHold() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            expanded -> MaterialSymbols.Rounded.Close
                            else -> MaterialSymbols.Rounded.Translate
                        },
                        contentDescription = stringResource(ATMR.strings.orb_title),
                        tint = when (status.phase) {
                            LiveTranslator.Phase.ERROR -> Color(0xFFFFB020)
                            LiveTranslator.Phase.DONE -> Color(0xFF3DDC97)
                            else -> Color.White
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLine(status: LiveTranslator.Status): String = when (status.phase) {
    LiveTranslator.Phase.OFF -> stringResource(ATMR.strings.orb_now_sub)
    LiveTranslator.Phase.ERROR -> status.error ?: stringResource(ATMR.strings.live_error)
    LiveTranslator.Phase.DONE -> stringResource(ATMR.strings.live_done)
    LiveTranslator.Phase.PAUSED -> stringResource(ATMR.strings.live_paused, status.translated, status.total)
    LiveTranslator.Phase.WORKING -> stringResource(ATMR.strings.live_working, status.workingPage ?: status.focusPage, status.total)
    LiveTranslator.Phase.IDLE -> stringResource(ATMR.strings.live_ready, status.translated, status.total)
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwitchAction(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = Color.White),
        )
    }
}

/** Runs auto-scroll while [playing]: smooth scrolling in webtoon mode, timed page turns otherwise. */
@Composable
fun AutoScrollRunner(
    playing: Boolean,
    paused: Boolean,
    speed: Int,
    webtoon: Boolean,
    onScrollBy: (Int) -> Unit,
    onNextPage: () -> Unit,
    /** MANHWA smart pace: characters of text being read now (0 when unknown or off). */
    textNow: () -> Int = { 0 },
) {
    val density = LocalDensity.current.density
    LaunchedEffect(playing, paused, speed, webtoon) {
        if (!playing || paused) return@LaunchedEffect
        if (webtoon) {
            var last = 0L
            var carry = 0f
            // Slower where there is a lot to read, back to full speed over art; eased so it never jerks
            var pace = 1f
            var sampledAt = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (now - sampledAt > PACE_SAMPLE_NS) {
                        sampledAt = now
                        val target = (1f / (1f + textNow() / PACE_CHARS)).coerceIn(PACE_MIN, 1f)
                        pace += (target - pace) * PACE_EASE
                    }
                    if (last != 0L) {
                        val pixels = speed * 26f * density * pace * (now - last) / 1_000_000_000f + carry
                        val whole = pixels.toInt()
                        carry = pixels - whole
                        if (whole > 0) onScrollBy(whole)
                    }
                    last = now
                }
            }
        } else {
            while (isActive) {
                // A page full of text stays longer before it turns
                val base = (15 - speed).coerceAtLeast(2) * 1_000L
                delay((base * (1f + textNow() / PAGE_CHARS)).toLong().coerceAtMost(base * 3))
                onNextPage()
            }
        }
    }
}

/** Shown on top while bubbles are being edited: how it works, and the button to finish. */
@Composable
fun BubbleEditBanner(onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .neonGlow(RoundedCornerShape(22.dp), MaterialTheme.colorScheme.primary, 0.2f, 10.dp)
            .glass(RoundedCornerShape(22.dp), accent = true, alpha = 0.9f)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(ATMR.strings.orb_edit_hint),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(ATMR.strings.orb_done),
            color = Color.White,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ManhwaKit.accentBrush())
                .clickable(onClick = onDone)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private const val PACE_SAMPLE_NS = 250_000_000L
private const val PACE_CHARS = 140f
private const val PACE_MIN = 0.35f
private const val PACE_EASE = 0.35f
private const val PAGE_CHARS = 300f
