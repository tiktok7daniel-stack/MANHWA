package eu.kanade.tachiyomi.ui.library.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.neonGlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Undo
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The small glass card that tells the reader where the organizer just put the series they added, with an undo.
 * It sits at the bottom, above the navigation, so it never covers the header or the drop banner.
 */
@Composable
fun BoxScope.AutoOrganizeBanner() {
    val context = LocalContext.current
    val organizer = remember { context.appGraph.libraryOrganizer }
    var current by remember { mutableStateOf<List<LibraryOrganizer.Placed>>(emptyList()) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(organizer) {
        organizer.placed.collectLatest { placed ->
            current = placed
            visible = true
            delay(SHOW_MS)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val placed = current
        if (placed.isEmpty()) return@AnimatedVisibility
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(20.dp)
        val first = placed.first()
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 88.dp)
                .fillMaxWidth()
                .neonGlow(shape, colors.primary, 0.35f, 14.dp)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xF00B0A0E), Color(0xF01A070C))))
                .border(1.dp, colors.primary.copy(alpha = 0.6f), shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (placed.size == 1) {
                        stringResource(ATMR.strings.org_placed, "⁨${first.title}⁩", "⁨${first.categories.joinToString(" · ")}⁩")
                    } else {
                        stringResource(ATMR.strings.org_placed_many, placed.size)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (placed.size == 1) {
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            organizer.undo(first)
                            visible = false
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MaterialSymbols.Rounded.Undo, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(ATMR.strings.org_undo), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.primary)
                }
            }
        }
    }
}

private const val SHOW_MS = 6_000L
