package eu.kanade.translation.learning

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/**
 * MANHWA: what the translation team learnt: the rules it follows (per manhwa and for the whole app) and the
 * corrections they came from. The reader can rewrite the rules and remove any lesson.
 */
class LessonsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val store = remember { context.appGraph.lessonStore }
        val revision by store.revision.collectAsState()
        var books by remember { mutableStateOf(emptyList<Pair<Long, LessonStore.Book>>()) }
        var editing by remember { mutableStateOf<Pair<Long, LessonStore.Book>?>(null) }

        LaunchedEffect(revision) {
            books = withContext(Dispatchers.IO) { store.books() }
        }
        val scope = rememberCoroutineScope()
        val backup = remember { KnowledgeBackup(context.appGraph.glossaryStore, store) }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val count = runCatching {
                    val library = context.appGraph.getFavorites.await()
                    context.contentResolver.openOutputStream(uri)!!.use { backup.export(library, it) }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    context.toast(if (count != null) context.stringResource(ATMR.strings.lessons_exported, count) else context.stringResource(ATMR.strings.col_failed))
                }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val count = runCatching {
                    val library = context.appGraph.getFavorites.await()
                    context.contentResolver.openInputStream(uri)!!.use { backup.import(library, it) }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    context.toast(if (count != null) context.stringResource(ATMR.strings.lessons_imported, count) else context.stringResource(ATMR.strings.col_failed))
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(ATMR.strings.lessons_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportLauncher.launch("manhwa_knowledge.json") }) {
                            Text(stringResource(ATMR.strings.lessons_export))
                        }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                            Text(stringResource(ATMR.strings.lessons_import))
                        }
                    }
                }
                item {
                    Text(
                        stringResource(ATMR.strings.lessons_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val shown = books.filter { (_, book) -> book.rules.isNotEmpty() || book.lessons.isNotEmpty() }
                if (shown.isEmpty()) {
                    item { Text(stringResource(ATMR.strings.lessons_empty), style = MaterialTheme.typography.bodyMedium) }
                }
                items(shown, key = { it.first }) { (id, book) ->
                    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (id == LessonStore.GLOBAL) stringResource(ATMR.strings.lessons_global) else book.title.ifBlank { "#$id" },
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { editing = id to book }) { Text(stringResource(ATMR.strings.lessons_edit_rules)) }
                            }
                            Text(stringResource(ATMR.strings.lessons_rules), style = MaterialTheme.typography.labelLarge)
                            if (book.rules.isEmpty()) {
                                Text(
                                    stringResource(ATMR.strings.lessons_no_rules),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            book.rules.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                            if (book.lessons.isNotEmpty()) {
                                Text(stringResource(ATMR.strings.lessons_corrections, book.lessons.size), style = MaterialTheme.typography.labelLarge)
                            }
                            book.lessons.asReversed().take(SHOWN_LESSONS).forEach { lesson ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(lesson.source, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        MarkedLine(MaterialSymbols.Rounded.Close, lesson.wrong, MaterialTheme.colorScheme.error, 1)
                                        MarkedLine(MaterialSymbols.Rounded.Check, lesson.right, MaterialTheme.colorScheme.primary, 2)
                                        Text(
                                            stringResource(
                                                if (lesson.origin == LessonStore.Origin.USER.name) ATMR.strings.lessons_by_you else ATMR.strings.lessons_by_editor,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { store.removeLesson(id, lesson) }) {
                                        Text(stringResource(ATMR.strings.col_delete), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        editing?.let { (id, book) ->
            var text by remember(id) { mutableStateOf(book.rules.joinToString("\n")) }
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(stringResource(ATMR.strings.lessons_edit_rules)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(ATMR.strings.lessons_rules_hint), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            store.setRules(id, text.lines())
                            editing = null
                        },
                    ) { Text(stringResource(ATMR.strings.col_save)) }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(ATMR.strings.col_cancel)) } },
            )
        }
    }

    /** A line of a lesson with its mark: what was wrong, or what is right. */
    @Composable
    private fun MarkedLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color, lines: Int) {
        Row(verticalAlignment = Alignment.Top) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(14.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 4.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }

    private companion object {
        const val SHOWN_LESSONS = 20
    }
}
