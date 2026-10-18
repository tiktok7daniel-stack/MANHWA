package eu.kanade.presentation.history.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.lang.toTimestampString
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA: room for a title on two lines, the chapter line and the whole resume button (at 120 dp a long title
 * squeezed the button under the chapter line and cut its word).
 */
private val HistoryItemHeight = 140.dp

/** One read manhwa in the history, as a MANHWA glass card with a glowing resume button. */
@Composable
fun HistoryItem(
    history: HistoryWithRelations,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDelete: () -> Unit,
    onClickFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    // MANHWA fusion: read in the work's continuation (its language), shown next to the chapter
    continuation: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .height(HistoryItemHeight)
            .glass(RoundedCornerShape(22.dp), alpha = 0.42f)
            .clickable(onClick = onClickResume)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier
                .fillMaxHeight()
                .neonGlow(RoundedCornerShape(12.dp), colors.primary, 0.3f, 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            data = history.coverData,
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 12.dp, end = 6.dp),
        ) {
            Text(
                text = history.title,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            val readAt = remember { history.readAt?.toTimestampString() ?: "" }
            val chapter = if (history.chapterNumber > -1) {
                stringResource(ATMR.strings.history_chapter, formatChapterNumber(history.chapterNumber))
            } else {
                null
            }
            val continuationLabel = continuation?.let { stringResource(ATMR.strings.history_continuation, it) }
            Text(
                text = listOfNotNull(chapter, continuationLabel, readAt.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .neonGlow(RoundedCornerShape(8.dp), colors.primary, 0.2f, 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary.copy(alpha = 0.28f))
                    .border(1.dp, colors.primary.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManhwaKit.NeonButton(
                    text = stringResource(ATMR.strings.history_resume),
                    icon = MaterialSymbols.RoundedFilled.PlayArrow,
                    modifier = Modifier.height(38.dp),
                    onClick = onClickResume,
                )
                Spacer(Modifier.weight(1f))
                if (!history.coverData.isMangaFavorite && continuation == null) {
                    SmallOrb(MaterialSymbols.Rounded.Favorite, stringResource(MR.strings.add_to_library), onClickFavorite)
                    Spacer(Modifier.width(6.dp))
                }
                SmallOrb(MaterialSymbols.Rounded.Delete, stringResource(MR.strings.action_delete), onClickDelete)
            }
        }
    }
}

@Composable
private fun SmallOrb(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .glass(CircleShape, alpha = 0.4f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = ManhwaInk.text, modifier = Modifier.size(18.dp))
    }
}

@PreviewLightDark
@Composable
private fun HistoryItemPreviews(
    @PreviewParameter(HistoryWithRelationsProvider::class)
    historyWithRelations: HistoryWithRelations,
) {
    TachiyomiPreviewTheme {
        Surface {
            HistoryItem(
                history = historyWithRelations,
                onClickCover = {},
                onClickResume = {},
                onClickDelete = {},
                onClickFavorite = {},
            )
        }
    }
}
