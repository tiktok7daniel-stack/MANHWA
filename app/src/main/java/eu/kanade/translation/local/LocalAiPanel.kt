package eu.kanade.translation.local

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.OpenInNew
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreference
import java.io.File

/**
 * MANHWA on-device AI in the translation settings: the model in use and its size, a switch, "import a model"
 * (a MediaPipe .task file the reader downloaded from the model's official page), the official page itself,
 * and "remove" to free the space.
 */
@Composable
fun LocalAiPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.appGraph.translationPreferences }
    val scope = rememberCoroutineScope()
    val on by prefs.translationLocalAi().collectPreference()
    val path by prefs.translationLocalModelPath().collectPreference()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            status = runCatching { LocalAiEngine.import(context, uri) }
                .fold({ size -> Formatter.formatShortFileSize(context, size) }, { it.message ?: "Error" })
            busy = false
        }
    }
    val file = path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(ATMR.strings.local_ai_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    if (file != null) {
                        stringResource(ATMR.strings.local_ai_ready, Formatter.formatShortFileSize(context, file.length()))
                    } else {
                        stringResource(ATMR.strings.local_ai_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (file != null) Color(0xFF3DDC97) else Color.White.copy(alpha = 0.6f),
                )
            }
            Switch(checked = on, onCheckedChange = { prefs.translationLocalAi().set(it) })
        }
        Text(stringResource(ATMR.strings.local_ai_info), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManhwaKit.NeonButton(
                stringResource(if (busy) ATMR.strings.local_ai_importing else ATMR.strings.local_ai_import),
                icon = MaterialSymbols.Rounded.Download,
                modifier = Modifier.weight(1f),
            ) { if (!busy) picker.launch(arrayOf("*/*")) }
            ManhwaKit.GhostButton(stringResource(ATMR.strings.local_ai_get), icon = MaterialSymbols.Rounded.OpenInNew, modifier = Modifier.weight(1f)) {
                eu.kanade.translation.team.openInBrowser(context, MODELS_PAGE)
            }
        }
        if (file != null) {
            ManhwaKit.GhostButton(stringResource(ATMR.strings.local_ai_remove), icon = MaterialSymbols.Rounded.Delete, modifier = Modifier.fillMaxWidth()) {
                scope.launch {
                    LocalAiEngine.remove(context)
                    status = null
                }
            }
        }
    }
}

/** Google's official page of on-device models for MediaPipe LLM Inference. */
private const val MODELS_PAGE = "https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference#models"
