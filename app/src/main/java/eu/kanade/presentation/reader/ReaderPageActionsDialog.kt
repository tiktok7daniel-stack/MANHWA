package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.EditNote
import mihon.icons.materialsymbols.rounded.Group
import mihon.icons.materialsymbols.rounded.Photo
import mihon.icons.materialsymbols.rounded.Save
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.materialsymbols.rounded.SkipNext
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderPageActionsDialog(
    onDismissRequest: () -> Unit,
    onSetAsCover: () -> Unit,
    onShare: (Boolean) -> Unit,
    onSave: () -> Unit,
    onSkipAlways: (() -> Unit)? = null,
    onAddNote: (() -> Unit)? = null,
    onWhoIsThis: (() -> Unit)? = null,
) {
    var showSetCoverDialog by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            ActionButton(
                modifier = Modifier.weight(1f),
                title = stringResource(MR.strings.set_as_cover),
                icon = MaterialSymbols.Rounded.Photo,
                onClick = { showSetCoverDialog = true },
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                title = stringResource(MR.strings.action_copy_to_clipboard),
                icon = MaterialSymbols.Rounded.ContentCopy,
                onClick = {
                    onShare(true)
                    onDismissRequest()
                },
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                title = stringResource(MR.strings.action_share),
                icon = MaterialSymbols.Rounded.Share,
                onClick = {
                    onShare(false)
                    onDismissRequest()
                },
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                title = stringResource(MR.strings.action_save),
                icon = MaterialSymbols.Rounded.Save,
                onClick = {
                    onSave()
                    onDismissRequest()
                },
            )
        }
        // MANHWA: a note on this page, and a group's credits or ads page passed over from now on
        Row(
            modifier = Modifier.padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            if (onAddNote != null) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(ATMR.strings.notes_add),
                    icon = MaterialSymbols.Rounded.EditNote,
                    onClick = {
                        onAddNote()
                        onDismissRequest()
                    },
                )
            }
            if (onWhoIsThis != null) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(ATMR.strings.who_title),
                    icon = MaterialSymbols.Rounded.Group,
                    onClick = {
                        onWhoIsThis()
                        onDismissRequest()
                    },
                )
            }
            if (onSkipAlways != null) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(ATMR.strings.credit_skip_always),
                    icon = MaterialSymbols.Rounded.SkipNext,
                    onClick = {
                        onSkipAlways()
                        onDismissRequest()
                    },
                )
            }
        }
    }

    if (showSetCoverDialog) {
        SetCoverDialog(
            onConfirm = {
                onSetAsCover()
                showSetCoverDialog = false
            },
            onDismiss = { showSetCoverDialog = false },
        )
    }
}

@Composable
private fun SetCoverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        text = {
            Text(stringResource(MR.strings.confirm_set_image_as_cover))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismiss,
    )
}
