package eu.kanade.translation.style

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import mihon.app.di.appGraph
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/**
 * Glass sheet in the reader to pick how translations look: typeface (or Auto by bubble kind), size, minimum
 * size, line spacing and outline, with a live preview. Changes apply to the pages on screen at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationStyleSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.appGraph.translationPreferences }
    val readerPrefs = remember { context.appGraph.readerPreferences }
    val typefaceIndex by prefs.translationTypeface().collectAsState()
    val sizeScale by prefs.translationSizeScale().collectAsState()
    val minSize by prefs.translationMinSize().collectAsState()
    val spacing by prefs.translationLineSpacing().collectAsState()
    val outline by prefs.translationOutline().collectAsState()
    val skipSfx by prefs.translationSkipSfx().collectAsState()
    val live by prefs.liveTranslation().collectAsState()
    val show by readerPrefs.showTranslations.collectAsState()
    val typeface = TranslationTypeface.entries.getOrNull(typefaceIndex) ?: TranslationTypeface.SMART
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xF20A080C),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ManhwaKit.SectionTitle(stringResource(ATMR.strings.style_title), stringResource(ATMR.strings.style_subtitle))

            // Live preview of the kinds of bubbles
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    BubbleKind.SPEECH to "سأحميك مهما حدث.",
                    BubbleKind.SHOUT to "لن أخسر!!",
                    BubbleKind.SYSTEM to "[ ارتفع المستوى ]",
                ).forEach { (kind, sample) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(86.dp)
                            .clip(RoundedCornerShape(40))
                            .background(Color.White)
                            .border(2.dp, Color.Black.copy(alpha = 0.8f), RoundedCornerShape(40))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val style = TextStyle(
                            fontFamily = typeface.family(kind),
                            fontSize = (15f * sizeScale).sp,
                            textAlign = TextAlign.Center,
                            textDirection = TextDirection.Content,
                            lineHeight = (15f * sizeScale * spacing).sp,
                        )
                        if (outline) Text(sample, style = style.copy(color = Color.White, drawStyle = Stroke(3f)))
                        Text(sample, style = style.copy(color = Color.Black))
                    }
                }
            }

            Text(stringResource(ATMR.strings.style_font), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TranslationTypeface.entries, key = { it.name }) { option ->
                    val selected = option == typeface
                    Column(
                        modifier = Modifier
                            .width(96.dp)
                            .then(
                                if (selected) {
                                    Modifier
                                        .neonGlow(RoundedCornerShape(18.dp), colors.primary, 0.3f, 8.dp)
                                        .glass(RoundedCornerShape(18.dp), accent = true, alpha = 0.6f)
                                } else {
                                    Modifier.glass(RoundedCornerShape(18.dp), alpha = 0.35f)
                                },
                            )
                            .clickable { prefs.translationTypeface().set(option.ordinal) }
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (option.arabic) "أبجد" else "Abc",
                            style = TextStyle(fontFamily = option.family(BubbleKind.SPEECH), fontSize = 22.sp),
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            option.label.substringAfterLast("/ ").ifBlank { option.label },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                        )
                    }
                }
            }

            SliderRow(stringResource(ATMR.strings.style_size), "${(sizeScale * 100).roundToInt()}%", sizeScale, 0.7f..1.6f, 17) {
                prefs.translationSizeScale().set(it)
            }
            SliderRow(stringResource(ATMR.strings.style_min_size), "${minSize}sp", minSize.toFloat(), 6f..16f, 9) {
                prefs.translationMinSize().set(it.roundToInt())
            }
            SliderRow(stringResource(ATMR.strings.style_spacing), String.format("%.2f×", spacing), spacing, 0.9f..1.6f, 6) {
                prefs.translationLineSpacing().set(it)
            }
            SwitchRow(stringResource(ATMR.strings.style_outline), outline) { prefs.translationOutline().set(it) }
            SwitchRow(stringResource(ATMR.strings.style_skip_sfx), skipSfx) { prefs.translationSkipSfx().set(it) }
            SwitchRow(stringResource(ATMR.strings.pref_live_translation), live) { prefs.liveTranslation().set(it) }
            SwitchRow(stringResource(ATMR.strings.pref_show_translations), show) { readerPrefs.showTranslations.set(it) }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var local by remember(current) { mutableFloatStateOf(current) }
    Column(modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.35f).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), accent = checked, alpha = 0.35f)
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = Color.White),
        )
    }
}
