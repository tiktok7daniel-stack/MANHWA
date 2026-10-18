package eu.kanade.translation.characters

import eu.kanade.presentation.components.ManhwaInk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoStories
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA "previously on": back to a series after [AWAY_DAYS] days or more, a glass card at the top of its page
 * tells what happened in the last chapters read (from the summaries the translation kept), so the reader picks
 * the story up again at once. It can be put away for this visit.
 */
@Composable
fun ReturnRecapCard(mangaId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var dismissed by remember(mangaId) { mutableStateOf(false) }
    val recap by produceState<Pair<Int, String>?>(null, mangaId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val graph = context.appGraph
                val last = graph.getHistory.await(mangaId).mapNotNull { it.readAt?.toEpochMilliseconds() }.maxOrNull() ?: return@runCatching null
                val days = ((System.currentTimeMillis() - last) / DAY_MS).toInt()
                if (days < AWAY_DAYS) return@runCatching null
                val story = graph.glossaryStore.storySoFar(mangaId)
                if (story.isBlank()) null else days to story
            }.getOrNull()
        }
    }
    val (days, story) = recap ?: return
    if (dismissed) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(22.dp), accent = true, alpha = 0.5f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MaterialSymbols.Rounded.AutoStories, contentDescription = null, tint = ManhwaInk.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(ATMR.strings.recap_back_title, days), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = ManhwaInk.text)
        }
        Text(story, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.85f))
        ManhwaKit.GhostButton(stringResource(ATMR.strings.recap_back_dismiss)) { dismissed = true }
    }
}

private const val AWAY_DAYS = 14
private const val DAY_MS = 24 * 60 * 60 * 1000L
