package eu.kanade.translation.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoStories
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.SwapHoriz
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import mihon.icons.materialsymbols.rounded.RecordVoiceOver
import mihon.icons.materialsymbols.rounded.PictureInPictureAlt
import mihon.icons.materialsymbols.rounded.School
import mihon.icons.materialsymbols.rounded.Verified
import tachiyomi.i18n.at.ATMR
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.rounded.Speed
import tachiyomi.presentation.core.util.collectAsState as collectPreference
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA quick HUD: a small glass capsule over the reader, opened by holding the reader button. One tap each
 * for live translation, showing the translation, bubble editing, auto-scroll and its speed, moving the manhwa
 * to another source and the smart recap, without going through any menu.
 */
@Composable
fun ReaderQuickHud(
    visible: Boolean,
    onDismiss: () -> Unit,
    liveOn: Boolean,
    onLive: (Boolean) -> Unit,
    showTranslations: Boolean,
    onShowTranslations: (Boolean) -> Unit,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    scrolling: Boolean,
    onScrolling: (Boolean) -> Unit,
    speed: Int,
    onSpeed: (Int) -> Unit,
    onSwapSource: () -> Unit,
    onRecap: () -> Unit,
    modifier: Modifier = Modifier,
    /** MANHWA: the chapter's quality score (null when it was not translated by the team). */
    quality: Int? = null,
    onQuality: () -> Unit = {},
    speaking: Boolean = false,
    onSpeak: () -> Unit = {},
    onStudy: () -> Unit = {},
    /** MANHWA: the reader in its small window over other apps (null when the phone can't). */
    onPip: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 14.dp)
                .neonGlow(shape, colors.primary, 0.18f, 14.dp)
                .glass(shape, accent = true, alpha = 0.88f)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                HudButton(MaterialSymbols.Rounded.Translate, stringResource(ATMR.strings.hud_live), liveOn) { onLive(!liveOn) }
                HudButton(
                    if (showTranslations) MaterialSymbols.Rounded.Visibility else MaterialSymbols.Rounded.VisibilityOff,
                    stringResource(ATMR.strings.hud_show),
                    showTranslations,
                ) { onShowTranslations(!showTranslations) }
                HudButton(MaterialSymbols.Rounded.Edit, stringResource(ATMR.strings.orb_edit), editing) {
                    onEditing(!editing)
                    if (!editing) onDismiss()
                }
                HudButton(MaterialSymbols.Rounded.SwapHoriz, stringResource(ATMR.strings.hud_source), false) {
                    onDismiss()
                    onSwapSource()
                }
                HudButton(MaterialSymbols.Rounded.AutoStories, stringResource(ATMR.strings.hud_recap), false) {
                    onDismiss()
                    onRecap()
                }
            }
            Spacer(Modifier.height(6.dp))
            // Read aloud and the chapter's quality badge
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                // MANHWA fast translation (like Google Lens) or the smart one (the AI), switched from here
                val context = androidx.compose.ui.platform.LocalContext.current
                val fastPref = remember { context.appGraph.translationPreferences.translationFastMode() }
                val fast by fastPref.collectPreference()
                HudButton(
                    MaterialSymbols.Rounded.Speed,
                    stringResource(if (fast) ATMR.strings.hud_fast else ATMR.strings.hud_smart),
                    fast,
                ) { fastPref.set(!fast) }
                HudButton(MaterialSymbols.Rounded.RecordVoiceOver, stringResource(ATMR.strings.hud_speak), speaking) { onSpeak() }
                HudButton(MaterialSymbols.Rounded.School, stringResource(ATMR.strings.hud_study), false) {
                    onDismiss()
                    onStudy()
                }
                if (onPip != null) {
                    HudButton(MaterialSymbols.Rounded.PictureInPictureAlt, stringResource(ATMR.strings.hud_pip), false) {
                        onDismiss()
                        onPip()
                    }
                }
                if (quality != null) {
                    HudButton(MaterialSymbols.Rounded.Verified, "${stringResource(ATMR.strings.hud_quality)} $quality%", quality >= 85) {
                        onDismiss()
                        onQuality()
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
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
                    modifier = Modifier.weight(1f).height(28.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("${value.toInt()}x", color = colors.primary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun HudButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .then(if (active) Modifier.neonGlow(CircleShape, colors.primary, 0.35f, 8.dp) else Modifier)
                .clip(CircleShape)
                .background(if (active) ManhwaKit.accentBrush() else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.06f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Color.White else Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** The small glass chip that says whether the long press showed or hid the translation. */
@Composable
fun BoxScope.TranslationToggleChip(event: Pair<Boolean, Long>?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        visible = true
        delay(CHIP_MS)
        visible = false
    }
    AnimatedVisibility(
        visible = visible && event != null,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = Modifier.align(Alignment.Center),
    ) {
        val on = event?.first ?: true
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .neonGlow(RoundedCornerShape(50), colors.primary, 0.3f, 12.dp)
                .glass(RoundedCornerShape(50), accent = true, alpha = 0.9f)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (on) MaterialSymbols.Rounded.Visibility else MaterialSymbols.Rounded.VisibilityOff,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (on) ATMR.strings.hud_translation_on else ATMR.strings.hud_translation_off),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/** Smart focus: a soft crimson line at the middle of the screen, where the reader stopped last time. */
@Composable
fun BoxScope.FocusFlash(at: Long) {
    if (at == 0L) return
    val alpha = remember(at) { Animatable(0f) }
    LaunchedEffect(at) {
        alpha.animateTo(1f, tween(250))
        delay(FLASH_HOLD_MS)
        alpha.animateTo(0f, tween(600))
    }
    val crimson = MaterialTheme.colorScheme.primary
    Canvas(Modifier.align(Alignment.Center).fillMaxWidth().height(24.dp)) {
        val a = alpha.value
        if (a <= 0f) return@Canvas
        val y = size.height / 2f
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, crimson.copy(alpha = 0.25f * a), Color.Transparent)),
        )
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, crimson.copy(alpha = a), Color.White.copy(alpha = 0.9f * a), crimson.copy(alpha = a), Color.Transparent)),
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

private const val CHIP_MS = 1_300L
private const val FLASH_HOLD_MS = 900L
