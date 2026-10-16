package eu.kanade.translation.brain

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Psychology
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** MANHWA work brain chip on the series screen: characters, relations, timeline and questions, no spoilers. */
@Composable
fun BrainButton(mangaId: Long, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val brainStore = remember { context.appGraph.seriesBrain }
    val brain by remember(mangaId, title) { brainStore.flow(mangaId, title) }.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    if (brain.entities.isEmpty() && brain.events.isEmpty()) return
    Row(
        modifier = modifier
            .glass(RoundedCornerShape(50), accent = true, alpha = 0.45f)
            .clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MaterialSymbols.Rounded.Psychology, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(ATMR.strings.brain_button, brain.entities.size, brain.events.size),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
    if (open) BrainSheet(mangaId, title, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrainSheet(mangaId: Long, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { context.appGraph.seriesBrain }
    val scope = rememberCoroutineScope()
    // Spoiler shield: the last chapter read is where the brain stops
    val readUpTo by produceState(-1.0, mangaId) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.appGraph.getChaptersByMangaId.await(mangaId).filter { it.read }.maxOfOrNull { it.chapterNumber } ?: -1.0 }.getOrDefault(-1.0)
        }
    }
    val full by remember(mangaId, title) { store.flow(mangaId, title) }.collectAsState()
    val brain = remember(full, readUpTo) { if (readUpTo < 0) SeriesBrain.Brain() else BrainRules.upTo(full, readUpTo) }
    var question by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF0B0709)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(ATMR.strings.brain_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
            Text(
                if (readUpTo >= 0) stringResource(ATMR.strings.brain_shield, BrainRules.number(readUpTo)) else stringResource(ATMR.strings.brain_shield_none),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFB020),
            )
            // Ask the brain
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text(stringResource(ATMR.strings.brain_ask_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ManhwaKit.NeonButton(stringResource(if (asking) ATMR.strings.hub_checking else ATMR.strings.brain_ask), icon = MaterialSymbols.Rounded.AutoAwesome, modifier = Modifier.fillMaxWidth()) {
                if (question.isBlank() || asking || readUpTo < 0) return@NeonButton
                asking = true
                scope.launch {
                    answer = runCatching { store.ask(mangaId, title, readUpTo, question.trim()) }.getOrElse { it.message.orEmpty() }
                    asking = false
                }
            }
            if (asking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            answer?.let { Card { Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium) } }
            // Characters and the rest
            val people = brain.entities.filter { it.type.equals("character", true) }
            if (people.isNotEmpty()) Section(stringResource(ATMR.strings.brain_people))
            people.forEach { e -> EntityCard(e) }
            val others = brain.entities.filterNot { it.type.equals("character", true) }
            if (others.isNotEmpty()) Section(stringResource(ATMR.strings.brain_world))
            others.forEach { e -> EntityCard(e) }
            if (brain.relations.isNotEmpty()) {
                Section(stringResource(ATMR.strings.brain_relations))
                Card {
                    brain.relations.forEach { r -> Text("${r.a}  ←  ${r.kind}  →  ${r.b}", color = Color.White, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (brain.events.isNotEmpty()) {
                Section(stringResource(ATMR.strings.brain_timeline))
                brain.events.reversed().forEach { ev ->
                    Card {
                        Text(stringResource(ATMR.strings.brain_chapter, BrainRules.number(ev.chapter)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(ev.text, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), alpha = 0.45f).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun EntityCard(e: SeriesBrain.Entity) {
    Card {
        Text(
            listOf(e.arabic, e.name).filter { it.isNotBlank() }.distinct().joinToString(" · "),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (e.details.isNotBlank()) Text(e.details, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        e.history.takeLast(3).forEach { f ->
            Text("${BrainRules.number(f.chapter)} · ${f.text}", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
