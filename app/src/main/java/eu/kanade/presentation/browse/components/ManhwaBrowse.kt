package eu.kanade.presentation.browse.components

import eu.kanade.presentation.components.ManhwaInk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.neonGlow
import eu.kanade.tachiyomi.ui.home.hub.SourceFame
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Verified
import mihon.icons.materialsymbols.roundedfilled.Star
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** Fame tier of a source or extension ("S-Tier", "Official"...), nothing when the index doesn't know it. */
@Composable
fun SourceFameTag(name: String, modifier: Modifier = Modifier) {
    val tier = SourceFame.tier(name) ?: return
    val colors = MaterialTheme.colorScheme
    val label = when (tier) {
        SourceFame.Tier.S -> stringResource(ATMR.strings.tier_s)
        SourceFame.Tier.OFFICIAL -> stringResource(ATMR.strings.tier_official)
        SourceFame.Tier.TOP -> stringResource(ATMR.strings.tier_top)
        SourceFame.Tier.POPULAR -> stringResource(ATMR.strings.tier_popular)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.primary.copy(alpha = 0.18f))
            .border(1.dp, colors.primary.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (tier == SourceFame.Tier.OFFICIAL) MaterialSymbols.Rounded.Verified else MaterialSymbols.RoundedFilled.Star,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ManhwaInk.text, maxLines = 1)
    }
}

/** Section header of the browse lists: a glowing bar and a bold title. */
@Composable
fun GlowHeader(text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(20.dp)
                .neonGlow(RoundedCornerShape(2.dp), colors.primary, 0.3f, 6.dp)
                .background(Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.2f))), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = ManhwaInk.text,
            modifier = Modifier.weight(1f),
        )
        action()
    }
}
