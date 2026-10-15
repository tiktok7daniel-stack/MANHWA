package eu.kanade.translation.collection

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import tachiyomi.core.common.i18n.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus

/**
 * MANHWA: the translation collection: how big it is and how much it saved, its size limit, a backup, and every
 * saved line to search, correct or remove.
 */
class TranslationCollectionScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val collection = remember { context.appGraph.translationCollection }
        val lessons = remember { context.appGraph.lessonStore }
        val prefs = remember { context.appGraph.translationPreferences }
        val enabled by prefs.translationCollection().collectAsState()
        val limitMb by prefs.translationCollectionLimitMb().collectAsState()

        var refresh by remember { mutableIntStateOf(0) }
        var stats by remember { mutableStateOf<TranslationCollection.Stats?>(null) }
        var query by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf<String?>(null) }
        var entries by remember { mutableStateOf(emptyList<TranslationCollection.Entry>()) }
        var editing by remember { mutableStateOf<TranslationCollection.Entry?>(null) }
        var confirmClear by remember { mutableStateOf(false) }

        LaunchedEffect(refresh) {
            stats = withContext(Dispatchers.IO) { collection.stats() }
        }
        LaunchedEffect(query, kind, refresh) {
            entries = withContext(Dispatchers.IO) { collection.entries(query.trim(), kind) }
        }

        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val ok = runCatching { context.contentResolver.openOutputStream(uri)!!.use(collection::exportTo) }.isSuccess
                withContext(Dispatchers.Main) { context.toast(if (ok) ATMR.strings.col_exported else ATMR.strings.col_failed) }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val added = runCatching { collection.importFrom(context.contentResolver.openInputStream(uri)!!) }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (added != null) {
                        context.toast(context.stringResource(ATMR.strings.col_imported, added))
                    } else {
                        context.toast(ATMR.strings.col_failed)
                    }
                    refresh++
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(ATMR.strings.col_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // What it holds and what it saved
                item {
                    val s = stats
                    Card {
                        Text(stringResource(ATMR.strings.col_stats), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (s != null) {
                            Text(stringResource(ATMR.strings.col_lines, s.lines, Formatter.formatShortFileSize(context, s.bytes)))
                            val share = if (s.todayLookups > 0) (s.todayHits * 100 / s.todayLookups).toInt() else 0
                            Text(stringResource(ATMR.strings.col_today, share, s.todayHits))
                            Text(stringResource(ATMR.strings.col_saved, s.allHits), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                // On or off, and the size limit
                item {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(ATMR.strings.col_enabled), fontWeight = FontWeight.Bold)
                                Text(stringResource(ATMR.strings.col_enabled_summary), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = enabled, onCheckedChange = { prefs.translationCollection().set(it) })
                        }
                        Text(stringResource(ATMR.strings.col_limit), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(LIMITS) { mb ->
                                FilterChip(
                                    selected = limitMb == mb,
                                    onClick = { prefs.translationCollectionLimitMb().set(mb) },
                                    label = {
                                        Text(
                                            if (mb == 0) {
                                                stringResource(ATMR.strings.col_no_limit)
                                            } else {
                                                Formatter.formatShortFileSize(context, mb * 1024L * 1024L)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        Text(stringResource(ATMR.strings.col_limit_summary), style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Backup, move to another phone, start over
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportLauncher.launch("manhwa_collection.db") }) {
                            Text(stringResource(ATMR.strings.col_export))
                        }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Text(stringResource(ATMR.strings.col_import))
                        }
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(ATMR.strings.col_clear), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                // Every saved line: search, filter by kind, tap to correct
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(ATMR.strings.col_search)) },
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(KINDS) { (value, label) ->
                            FilterChip(
                                selected = kind == value,
                                onClick = { kind = value },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            stringResource(ATMR.strings.col_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable { editing = entry },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(entry.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(entry.translation, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                buildString {
                                    append(stringResourceText(entry))
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (entry.sensitive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        editing?.let { entry ->
            var text by remember(entry.id) { mutableStateOf(entry.translation) }
            var sensitive by remember(entry.id) { mutableStateOf(entry.sensitive) }
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(entry.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(ATMR.strings.col_sensitive), modifier = Modifier.weight(1f))
                            Switch(checked = sensitive, onCheckedChange = { sensitive = it })
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                if (text.trim() != entry.translation) {
                                    collection.edit(entry.id, text)
                                    // The reader's correction is a lesson for every manhwa
                                    lessons.add(
                                        eu.kanade.translation.learning.LessonStore.GLOBAL,
                                        eu.kanade.translation.learning.LessonStore.Lesson(
                                            entry.text,
                                            entry.translation,
                                            text.trim(),
                                            eu.kanade.translation.learning.LessonStore.Origin.USER.name,
                                        ),
                                    )
                                }
                                if (sensitive != entry.sensitive) collection.setSensitive(entry.id, sensitive)
                                refresh++
                            }
                            editing = null
                        },
                    ) { Text(stringResource(ATMR.strings.col_save)) }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    collection.delete(entry.id)
                                    refresh++
                                }
                                editing = null
                            },
                        ) { Text(stringResource(ATMR.strings.col_delete), color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { editing = null }) { Text(stringResource(ATMR.strings.col_cancel)) }
                    }
                },
            )
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(ATMR.strings.col_clear)) },
                text = { Text(stringResource(ATMR.strings.col_clear_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                collection.clear()
                                refresh++
                            }
                            confirmClear = false
                        },
                    ) { Text(stringResource(ATMR.strings.col_clear), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(ATMR.strings.col_cancel)) } },
            )
        }
    }

    @Composable
    private fun stringResourceText(entry: TranslationCollection.Entry): String {
        val parts = buildList {
            add(stringResource(ATMR.strings.col_uses, entry.uses, entry.mangas))
            if (entry.user) add(stringResource(ATMR.strings.col_by_you))
            if (entry.sensitive) add(stringResource(ATMR.strings.col_sensitive_short))
        }
        return parts.joinToString(" · ")
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }

    private companion object {
        val LIMITS = listOf(256, 512, 1024, 2048, 0)
        val KINDS = listOf(
            null to ATMR.strings.col_kind_all,
            "system" to ATMR.strings.col_kind_system,
            "sfx" to ATMR.strings.col_kind_sfx,
            "speech" to ATMR.strings.col_kind_speech,
            "shout" to ATMR.strings.col_kind_shout,
            "narration" to ATMR.strings.col_kind_narration,
        )
    }
}
