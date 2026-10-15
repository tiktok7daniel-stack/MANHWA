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
