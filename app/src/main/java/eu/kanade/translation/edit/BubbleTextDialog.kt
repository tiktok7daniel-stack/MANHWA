package eu.kanade.translation.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.translation.model.TranslationBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA: rewrite one bubble in the reader: type the wording, or ask the team for three better ones and pick.
 * [onSave] gets the new wording (the caller saves it and the team learns it).
 */
@Composable
fun BubbleTextDialog(block: TranslationBlock, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(block) { mutableStateOf(block.translation) }
    var options by remember(block) { mutableStateOf<List<String>>(emptyList()) }
    var asking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(ATMR.strings.edit_text_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = !asking,
                    onClick = {
                        asking = true
                        failed = null
                        scope.launch {
                            try {
                                options = BubbleTextEditor.rephrase(context, block.copy(translation = text))
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                failed = e.message ?: e.javaClass.simpleName
                            } finally {
                                asking = false
                            }
                        }
                    },
                ) {
                    if (asking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(ATMR.strings.edit_rephrase))
                }
                failed?.let {
                    Text(
                        stringResource(ATMR.strings.edit_rephrase_failed, it.take(140)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (option == text) 0.22f else 0.08f))
                            .clickable { text = option }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    stringResource(ATMR.strings.edit_text_learns),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(ATMR.strings.th_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(ATMR.strings.th_cancel)) } },
    )
}
