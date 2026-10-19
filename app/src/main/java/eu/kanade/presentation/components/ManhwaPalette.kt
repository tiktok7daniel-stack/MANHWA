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
 * MANHWA: the look of the whole app, as preview cards: MANHWA Glass and Obsidian Royal side by side, then the three
 * newer looks each with a live preview drawn by the look itself (its own backdrop and panels, moving as it does).
 * The looks with a light version show it as a choice. The app changes at once, no restart.
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
    /** Name and one line about each of the newer looks. */
    newer: List<Triple<ManhwaStyle, String, String>> = emptyList(),
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
        newer.forEach { (look, name, about) ->
            Spacer(Modifier.height(10.dp))
            StyleCard(name = name, selected = style == look, onClick = { onStyle(look) }) {
                LivePreview(look, light = style == look && light)
            }
            Text(
                about,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
        if (style.hasLight) {
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

/** A small screen in [style]: its backdrop with two of its panels on it (the second highlighted), moving live. */
@Composable
private fun LivePreview(style: ManhwaStyle, light: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val look = androidx.compose.runtime.remember(style, accent, light) { ManhwaLook.of(style, accent, light) }
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.runtime.CompositionLocalProvider(LocalManhwaLook provides look) {
        // Its own clock, so the preview moves even while another look is chosen
        ProvideLookClock(moving = style == ManhwaStyle.AURORA || style == ManhwaStyle.HOLO) {
        Box(Modifier.fillMaxWidth().height(110.dp).clip(shape)) {
            LookBackdrop(look, Modifier) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val panel = RoundedCornerShape(look.cardRadius.coerceAtMost(14).dp)
                    Box(Modifier.weight(1f).height(68.dp).glass(panel)) {
                        Column(Modifier.padding(10.dp)) {
                            Box(Modifier.fillMaxWidth(0.7f).height(7.dp).clip(RoundedCornerShape(50)).background(look.text.copy(alpha = 0.85f)))
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth(0.45f).height(5.dp).clip(RoundedCornerShape(50)).background(look.muted))
                        }
                    }
                    Box(Modifier.weight(1f).height(68.dp).glass(panel, accent = true)) {
                        Column(Modifier.padding(10.dp)) {
                            Box(Modifier.fillMaxWidth(0.55f).height(7.dp).clip(RoundedCornerShape(50)).background(accent))
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth(0.8f).height(5.dp).clip(RoundedCornerShape(50)).background(look.muted))
                        }
                    }
                }
            }
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
