package eu.kanade.translation.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.translation.glossary.GlossaryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoStories
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The smart recap window: the reader picks a chapter (the current one by default) and gets the story told up
 * to it, where the hero stands, the open threads and who is who, with nothing from after that chapter.
 */
@Composable
fun ChapterRecapSheet(manga: Manga, currentChapter: Double, store: GlossaryStore, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var chapter by remember { mutableStateOf(if (currentChapter > 0) formatNumber(currentChapter) else "") }
    var working by remember { mutableStateOf(false) }
    var recap by remember { mutableStateOf<ChapterRecap.Recap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(ATMR.strings.crecap_failed)

    fun run() {
        val upTo = chapter.replace(',', '.').toDoubleOrNull() ?: return
        working = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ChapterRecap.recap(manga, upTo, store) } }
            working = false
            result.onSuccess { recap = it }.onFailure { error = it.message ?: failed }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(horizontal = 14.dp)
                .glass(shape, accent = true, alpha = 0.94f)
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Rounded.AutoStories, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(ATMR.strings.crecap_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
                    Text(manga.title, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MaterialSymbols.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(ATMR.strings.recap_up_to), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, colors.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = chapter,
                        onValueChange = { chapter = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize),
                        cursorBrush = SolidColor(colors.primary),
                    )
                }
                Spacer(Modifier.width(10.dp))
                ManhwaKit.NeonButton(
                    stringResource(ATMR.strings.recap_go),
                    Modifier.weight(1f),
                    MaterialSymbols.Rounded.AutoStories,
                ) { if (!working) run() }
            }
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    working -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = colors.primary)
                    }
                    error != null -> Text(error.orEmpty(), color = Color(0xFFFFB020), style = MaterialTheme.typography.bodyMedium)
                    recap == null -> Text(stringResource(ATMR.strings.recap_hint), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
                    else -> RecapBody(recap!!)
                }
            }
        }
    }
}

@Composable
private fun RecapBody(recap: ChapterRecap.Recap) {
    val colors = MaterialTheme.colorScheme
    recap.story.split("\n\n").map(String::trim).filter(String::isNotEmpty).forEach { paragraph ->
        Text(paragraph, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
    if (recap.hero.isNotBlank()) {
        Section(stringResource(ATMR.strings.recap_hero)) {
            Text(recap.hero, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (recap.threads.isNotEmpty()) {
        Section(stringResource(ATMR.strings.recap_threads)) {
            recap.threads.forEach { thread ->
                Row {
                    Box(Modifier.padding(top = 8.dp).size(6.dp).clip(RoundedCornerShape(50)).background(colors.primary))
                    Spacer(Modifier.width(8.dp))
                    Text(thread, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (recap.characters.isNotEmpty()) {
        Section(stringResource(ATMR.strings.crecap_characters)) {
            recap.characters.forEach { (name, role) ->
                Text(name, color = colors.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                if (role.isNotBlank()) Text(role, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (recap.fromSummaries == 0) {
        Text(stringResource(ATMR.strings.recap_from_knowledge), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
