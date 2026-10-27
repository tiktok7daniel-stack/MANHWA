package eu.kanade.tachiyomi.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA: clearing the history asks which sides go (manhwa, novels, anime); the side that is open is the one
 * chosen at first, so a clear on the novels never touches the manhwa history by itself.
 */
@Composable
fun ClearHistoryDialog(current: Int, onDismiss: () -> Unit, onClear: (Set<Int>) -> Unit) {
    var chosen by remember { mutableStateOf(setOf(current)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(ATMR.strings.history_clear_title)) },
        text = {
            Column {
                listOf(
                    KIND_MANGA to stringResource(ATMR.strings.library_manhwa),
                    KIND_NOVEL to stringResource(ATMR.strings.novel_kind),
                    KIND_ANIME to stringResource(ATMR.strings.library_anime),
                ).forEach { (kind, label) ->
                    val on = kind in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - kind else chosen + kind },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { chosen = if (it) chosen + kind else chosen - kind })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onClear(chosen) }, enabled = chosen.isNotEmpty()) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
