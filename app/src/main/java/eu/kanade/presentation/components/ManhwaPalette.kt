package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check

/** The MANHWA Glass variants: the same glass look, each with its own glow. */
object ManhwaPalette {
    data class Variant(val name: String, val color: Long)

    val variants = listOf(
        Variant("MANHWA Crimson", 0xFFFF3B4E),
        Variant("MANHWA Monarch", 0xFF9B5CFF),
        Variant("MANHWA Flame", 0xFFFF7A1A),
        Variant("MANHWA Abyss", 0xFF4F8BFF),
        Variant("MANHWA Jade", 0xFF3DDC97),
        Variant("MANHWA Sakura", 0xFFFF4FA3),
        Variant("MANHWA Aurum", 0xFFFFC233),
        Variant("MANHWA Frost", 0xFF00D1E0),
    )
}

/** Glass cards to pick a MANHWA Glass variant; the app changes colour at once, no restart. */
@Composable
fun ManhwaGlassPalette(selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ManhwaPalette.variants, key = { it.name }) { variant ->
            val color = Color(variant.color)
            val isSelected = selected == variant.color.toInt()
            val shape = RoundedCornerShape(20.dp)
            Column(
                modifier = Modifier
                    .width(112.dp)
                    .then(if (isSelected) Modifier.neonGlow(shape, color, 0.4f, 12.dp) else Modifier)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(lerp(color, Color.Black, 0.7f), Color(0xFF07070A))))
                    .border(if (isSelected) 2.dp else 1.dp, color.copy(alpha = if (isSelected) 1f else 0.4f), shape)
                    .clickable { onPick(variant.color.toInt()) }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .neonGlow(CircleShape, color, 0.5f, 12.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(color, lerp(color, Color.Black, 0.45f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) Icon(MaterialSymbols.Rounded.Check, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.height(10.dp))
                // Mini glass preview: a bar and a pill in the variant's glow
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(color, RoundedCornerShape(50)))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    variant.name.removePrefix("MANHWA "),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * MANHWA: the look of the whole app, as two preview cards (MANHWA Glass, Obsidian Royal); Obsidian Royal shows its
 * dark and light versions. The app changes at once, no restart.
 */
@Composable
fun ManhwaStylePicker(
    style: ManhwaStyle,
    light: Boolean,
    onStyle: (ManhwaStyle) -> Unit,
    onLight: (Boolean) -> Unit,
    glassName: String,
    obsidianName: String,
    darkName: String,
    lightName: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StyleCard(
                name = glassName,
                selected = style == ManhwaStyle.GLASS,
                modifier = Modifier.weight(1f),
                onClick = { onStyle(ManhwaStyle.GLASS) },
            ) {
                // Mini MANHWA Glass: smoked glass with a neon rim
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.verticalGradient(listOf(lerp(accent, Color.Black, 0.78f), Color(0xFF07070A))))
                        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.3f), accent)), RoundedCornerShape(12.dp)),
                )
            }
            StyleCard(
                name = obsidianName,
                selected = style == ManhwaStyle.OBSIDIAN,
                modifier = Modifier.weight(1f),
                onClick = { onStyle(ManhwaStyle.OBSIDIAN) },
            ) {
                // Mini Obsidian Royal: a solid card lit by a hairline from above, on obsidian or on paper
                val paper = style == ManhwaStyle.OBSIDIAN && light
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (paper) ObsidianTokens.PaperBackground else ObsidianTokens.Background)
                        .padding(8.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (paper) ObsidianTokens.PaperSurface else lerp(ObsidianTokens.Surface, accent, 0.04f))
                            .border(
                                0.5.dp,
                                Brush.verticalGradient(
                                    if (paper) {
                                        listOf(accent.copy(alpha = 0.3f), accent.copy(alpha = 0.03f))
                                    } else {
                                        listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.03f))
                                    },
                                ),
                                RoundedCornerShape(10.dp),
                            ),
                    )
                }
            }
        }
        if (style == ManhwaStyle.OBSIDIAN) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = !light,
                    onClick = { onLight(false) },
                    label = { Text(darkName, fontWeight = FontWeight.Bold) },
                )
                androidx.compose.material3.FilterChip(
                    selected = light,
                    onClick = { onLight(true) },
                    label = { Text(lightName, fontWeight = FontWeight.Bold) },
                )
            }
        }
    }
}

@Composable
private fun StyleCard(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        preview()
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(MaterialSymbols.Rounded.Check, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}
