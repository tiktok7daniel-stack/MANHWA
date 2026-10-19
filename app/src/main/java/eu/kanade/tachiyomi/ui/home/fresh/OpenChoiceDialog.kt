package eu.kanade.tachiyomi.ui.home.fresh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoStories
import mihon.icons.materialsymbols.rounded.Info
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** MANHWA: a new chapter tapped: read that chapter, or open the work's page. */
@Composable
fun OpenChoiceDialog(title: String, chapter: String, onRead: () -> Unit, onManga: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E0B0F),
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(chapter, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ManhwaKit.NeonButton(stringResource(ATMR.strings.fresh_open_chapter), Modifier.fillMaxWidth(), icon = MaterialSymbols.Rounded.AutoStories) {
                    onDismiss()
                    onRead()
                }
                ManhwaKit.GhostButton(stringResource(ATMR.strings.fresh_open_manga), Modifier.fillMaxWidth(), icon = MaterialSymbols.Rounded.Info) {
                    onDismiss()
                    onManga()
                }
            }
        },
        confirmButton = {},
    )
}
