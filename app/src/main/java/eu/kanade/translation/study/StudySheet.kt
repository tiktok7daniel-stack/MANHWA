package eu.kanade.translation.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.glass
import eu.kanade.translation.model.PageTranslation
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Keep
import mihon.icons.materialsymbols.roundedfilled.Star
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA study mode: every bubble of the page with its original next to its translation, in reading order,
 * and a star to keep a line in the study list. The saved lines are listed under the page's lines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySheet(page: PageTranslation?, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val list = remember { StudyList(context) }
    val saved = remember { mutableStateListOf<String>().apply { addAll(list.all().map { it.original }) } }
    val blocks = page?.blocks.orEmpty().filter { it.text.isNotBlank() && it.translation.isNotBlank() }
        .sortedWith(compareBy({ it.y.toInt() / 40 }, { -it.x }))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF0B0709)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(ATMR.strings.study_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
            if (blocks.isEmpty()) {
                Text(stringResource(ATMR.strings.study_empty), color = Color.White.copy(alpha = 0.6f))
            }
            blocks.forEach { b ->
                val on = b.text in saved
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), accent = on, alpha = 0.45f).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(b.text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(b.translation, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        // Who wrote it: the phone, a key, the library
                        val by = when (b.by) {
                            "local" -> stringResource(ATMR.strings.by_local)
                            "team" -> stringResource(ATMR.strings.by_team)
                            "library" -> stringResource(ATMR.strings.by_library)
                            else -> ""
                        }
                        if (by.isNotEmpty()) Text(by, color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (on) MaterialSymbols.RoundedFilled.Star else MaterialSymbols.Rounded.Keep,
                        contentDescription = stringResource(ATMR.strings.study_save),
                        tint = if (on) Color(0xFFFFB020) else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (on) {
                                    list.remove(b.text)
                                    saved.remove(b.text)
                                } else {
                                    list.add(StudyList.Item(b.text, b.translation, title))
                                    saved.add(b.text)
                                }
                            }
                            .padding(5.dp),
                    )
                }
            }
            val items = remember(saved.size) { list.all().reversed() }
            if (items.isNotEmpty()) {
                Text(stringResource(ATMR.strings.study_saved, items.size), color = Color.White, fontWeight = FontWeight.Bold)
                items.take(50).forEach { item ->
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp), alpha = 0.3f).padding(10.dp)) {
                        Text(item.original, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelMedium)
                        Text(item.translation, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        if (item.title.isNotBlank()) Text(item.title, color = Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
