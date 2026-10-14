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
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** "Names glossary" chip on the series screen; opens the glass glossary of that series. */
@Composable
fun GlossaryButton(mangaId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { context.appGraph.glossaryStore }
    val entries by remember(mangaId) { store.flow(mangaId) }.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier
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
    if (open) {
        GlossarySheet(mangaId = mangaId, store = store, onDismiss = { open = false }, onRetranslate = null)
    }
}
