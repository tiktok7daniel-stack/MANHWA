package eu.kanade.translation.live

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.tachiyomi.ui.reader.PageTune
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/**
 * MANHWA: what a double tap on the page brings up, each on its own: the colours button on top, the auto-scroll
 * button under it, and the translation button at the bottom. The owner hides them a moment after the last touch.
 */
@Composable
fun ReaderQuickDock(
    translating: Boolean,
    onTranslate: () -> Unit,
    autoColors: Boolean,
    onColors: () -> Unit,
    onColorsHold: () -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
    scrollButton: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
        // A tap: the colours tune themselves page by page (or stop); a long press: set them by hand
        DockButton(MaterialSymbols.Rounded.Palette, ATMR.strings.dock_colors, autoColors, onHold = {
            onTouch()
            onColorsHold()
        }) {
            onTouch()
            onColors()
        }
        scrollButton()
        // The translation button opens the translation's own menu, all of it
        DockButton(MaterialSymbols.Rounded.Translate, ATMR.strings.hud_live, translating) {
            onTouch()
            onTranslate()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DockButton(icon: ImageVector, label: StringResource, on: Boolean, onHold: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(58.dp)
            .glass(CircleShape, accent = on, alpha = 0.85f)
            .padding(5.dp)
            .then(
                if (on) {
                    Modifier.neonGlow(CircleShape, colors.primary, 0.4f, 10.dp).clip(CircleShape).background(ManhwaKit.accentBrush())
                } else {
                    Modifier.clip(CircleShape).background(ManhwaInk.text.copy(alpha = 0.1f))
                },
            )
            .combinedClickable(onLongClick = onHold, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = stringResource(label), tint = ManhwaInk.text)
    }
}

/**
 * MANHWA: the chapter's colours, at hand while reading: the screen's own brightness, and the pages' brightness,
 * contrast, saturation, sharpness and warmth, live as the sliders move. A one-tap lift for washed-out scans and a
 * reset. The pages' values are the same ones as in the reader settings, so the two always agree.
 */
@Composable
fun ColorTunePanel(prefs: ReaderPreferences, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val screenOn by prefs.customBrightness.collectAsState()
    val screen by prefs.customBrightnessValue.collectAsState()
    Column(
        modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .glass(RoundedCornerShape(26.dp), alpha = 0.92f)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(ATMR.strings.tune_title), fontWeight = FontWeight.Black, color = ManhwaInk.text, modifier = Modifier.weight(1f))
            Icon(
                MaterialSymbols.Rounded.Close,
                contentDescription = null,
                tint = ManhwaInk.text,
                modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onDismiss).padding(6.dp),
            )
        }
        // The screen itself (the window's brightness, as the reader settings set it); 0 follows the phone
        TuneSlider(stringResource(ATMR.strings.tune_screen), if (screenOn) screen.coerceIn(0, 100) else 0, 0..100) { v ->
            prefs.customBrightness.set(v > 0)
            prefs.customBrightnessValue.set(v)
        }
        listOf(
            ATMR.strings.page_tune_brightness to prefs.pageBrightness,
            ATMR.strings.page_tune_contrast to prefs.pageContrast,
            ATMR.strings.page_tune_saturation to prefs.pageSaturation,
            ATMR.strings.tune_sharpness to prefs.pageSharpness,
            ATMR.strings.page_tune_warmth to prefs.pageWarmth,
        ).forEach { (label, pref) ->
            // Sharpness needs Android 13's shaders; on older phones it is left out
            if (pref === prefs.pageSharpness && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@forEach
            val value by pref.collectAsState()
            TuneSlider(stringResource(label), value, if (pref === prefs.pageSharpness) 0..100 else -100..100) {
                prefs.pageTune.set(true)
                pref.set(it)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ManhwaKit.GhostButton(stringResource(ATMR.strings.page_tune_auto)) {
                val auto = PageTune.AUTO
                prefs.pageTune.set(true)
                prefs.pageBrightness.set(auto.brightness)
                prefs.pageContrast.set(auto.contrast)
                prefs.pageSaturation.set(auto.saturation)
                prefs.pageWarmth.set(auto.warmth)
                prefs.pageSharpness.set(AUTO_SHARPNESS)
            }
            ManhwaKit.GhostButton(stringResource(ATMR.strings.page_tune_reset)) {
                listOf(prefs.pageBrightness, prefs.pageContrast, prefs.pageSaturation, prefs.pageWarmth, prefs.pageSharpness).forEach { it.set(0) }
                prefs.customBrightness.set(false)
            }
        }
    }
}

/** The sharpness the one-tap lift gives: enough to firm up soft, upscaled scans. */
private const val AUTO_SHARPNESS = 25

@Composable
private fun TuneSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(label, color = ManhwaInk.text, modifier = Modifier.weight(1f))
        Text(value.toString(), color = colors.primary, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
        modifier = Modifier.height(32.dp),
    )
}

