package eu.kanade.translation.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA chapter quality: where the chapter's lines came from (library or AI), what a second key reviewed and
 * changed, and the lines still doubtful, each with a field to correct it by hand. A correction is saved in the
 * translation library as the reader's own (never overwritten by the AI) and is used from the next opening on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterQualitySheet(
    report: ChapterQuality.Report,
    onCorrect: (source: String, translation: String) -> Unit,
    onDismiss: () -> Unit,
    onChoose: (source: String, chosen: String, rejected: String) -> Unit = { _, _, _ -> },
) {
    val fixed = remember { mutableStateMapOf<String, Boolean>() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF0B0709)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(ATMR.strings.quality_title, report.score), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
            Text(
                stringResource(ATMR.strings.quality_line, report.libraryPercent, report.fromLibrary, report.byAi, report.reviewed, report.changed),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            // MANHWA fingerprint: which reads better? Every answer teaches the reader's style
            val picked = remember { mutableStateMapOf<String, Boolean>() }
            val open = report.choices.filter { picked[it.source] != true }
            if (open.isNotEmpty()) {
                Text(stringResource(ATMR.strings.style_pick_title), color = Color.White, fontWeight = FontWeight.Bold)
                open.forEach { tie ->
                    Column(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), accent = true, alpha = 0.45f).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(tie.source, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        listOf(tie.a to tie.b, tie.b to tie.a).forEach { (mine, other) ->
                            ManhwaKit.GhostButton(mine, modifier = Modifier.fillMaxWidth()) {
                                onChoose(tie.source, mine, other)
                                picked[tie.source] = true
                            }
                        }
                    }
                }
            }
            if (report.doubtful.isEmpty()) {
                Text(stringResource(ATMR.strings.quality_no_doubts), color = Color(0xFF3DDC97), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(ATMR.strings.quality_doubts), color = Color.White, fontWeight = FontWeight.Bold)
            }
            report.doubtful.filter { fixed[it] != true }.forEach { source ->
                var text by remember(source) { mutableStateOf("") }
                Column(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.45f).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(source, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(ATMR.strings.quality_your_translation)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (text.isNotBlank()) {
                        ManhwaKit.NeonButton(stringResource(ATMR.strings.quality_save), icon = MaterialSymbols.Rounded.Check, modifier = Modifier.fillMaxWidth()) {
                            onCorrect(source, text.trim())
                            fixed[source] = true
                        }
                    }
                }
            }
        }
    }
}
