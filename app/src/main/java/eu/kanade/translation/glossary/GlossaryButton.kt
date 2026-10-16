package eu.kanade.translation.glossary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.glass
import eu.kanade.translation.live.GlossarySheet
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Label
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.IosShare
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** "Names glossary" chip on the series screen; opens the glass glossary of that series. */
@Composable
fun GlossaryButton(mangaId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { context.appGraph.glossaryStore }
    val entries by remember(mangaId) { store.flow(mangaId) }.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    // MANHWA glossary pack: the series' names and terms in one file, to keep or move to another phone
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { GlossaryPack.write(store, mangaId, it) } }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, if (ok) ATMR.strings.glossary_exported.let { context.stringResource(it) } else "Error", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val count = runCatching { context.contentResolver.openInputStream(uri)?.use { GlossaryPack.read(store, mangaId, it) } ?: 0 }.getOrDefault(-1)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    if (count >= 0) context.stringResource(ATMR.strings.glossary_imported, count) else context.stringResource(ATMR.strings.glossary_import_failed),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(50), alpha = 0.4f)
            .clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MaterialSymbols.AutoMirroredRounded.Label,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(ATMR.strings.glossary_button, entries.size),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
    Spacer(Modifier.width(6.dp))
    eu.kanade.presentation.components.ManhwaKit.IconOrb(MaterialSymbols.Rounded.IosShare, size = 34) { exporter.launch("glossary-$mangaId.manhwa-glossary.json") }
    Spacer(Modifier.width(6.dp))
    eu.kanade.presentation.components.ManhwaKit.IconOrb(MaterialSymbols.Rounded.Download, size = 34) { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
    }
    if (open) {
        GlossarySheet(mangaId = mangaId, store = store, onDismiss = { open = false }, onRetranslate = null)
    }
}
