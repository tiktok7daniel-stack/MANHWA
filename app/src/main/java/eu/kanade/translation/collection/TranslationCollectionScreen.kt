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
        val codexPacks by prefs.translationCodexPacks().collectAsState()
        val healOcr by prefs.translationOcrHealer().collectAsState()
        var optimizing by remember { mutableStateOf(false) }
        var optimized by remember { mutableStateOf<String?>(null) }
        val optimizedTemplate = stringResource(ATMR.strings.db_optimized)
        val progressTemplate = stringResource(ATMR.strings.db_optimizing_progress)
        val noAi = stringResource(ATMR.strings.recs_setup)

        var refresh by remember { mutableIntStateOf(0) }
        var stats by remember { mutableStateOf<TranslationCollection.Stats?>(null) }
        var query by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf<String?>(null) }
        var entries by remember { mutableStateOf(emptyList<TranslationCollection.Entry>()) }
        var editing by remember { mutableStateOf<TranslationCollection.Entry?>(null) }
        var confirmClear by remember { mutableStateOf(false) }

        // MANHWA review: lines nobody has checked yet (count, oldest date)
        var unreviewed by remember { mutableStateOf(0L to 0L) }
        // Names nobody checked yet: how many, in how many series
        var unreviewedNames by remember { mutableStateOf(0 to 0) }
        var reviewRounds by remember { mutableStateOf(emptyList<TranslationCollection.ReviewRound>()) }
        LaunchedEffect(refresh) {
            stats = withContext(Dispatchers.IO) { collection.stats() }
            unreviewed = withContext(Dispatchers.IO) { collection.unreviewed() }
            unreviewedNames = withContext(Dispatchers.IO) {
                runCatching { context.appGraph.glossaryStore.unreviewed().let { m -> m.values.sumOf { it.size } to m.size } }.getOrDefault(0 to 0)
            }
            reviewRounds = withContext(Dispatchers.IO) { collection.reviewRounds() }
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
        val reviewExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val count = runCatching {
                    // The names nobody checked yet, by series, go in the same file to be corrected in one place
                    val graph = context.appGraph
                    val glossaries = graph.glossaryStore.unreviewed().map { (id, entries) ->
                        val terms = entries.map { CollectionReview.Term(it.source, it.translation, it.kind, it.gender, it.note) }
                        CollectionReview.Glossary(id, graph.getMangaById.await(id)?.title.orEmpty(), terms)
                    }
                    context.contentResolver.openOutputStream(uri)!!.use { collection.exportReview(it, glossaries) }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (count != null) context.toast(context.stringResource(ATMR.strings.col_review_exported, count)) else context.toast(ATMR.strings.col_failed)
                }
            }
        }
        val reviewImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val result = runCatching { collection.importReview(context.contentResolver.openInputStream(uri)!!) }.getOrNull()
                // Each corrected name goes back to its series (lines with a changed name follow it)
                result?.glossaries?.forEach { g ->
                    val store = context.appGraph.glossaryStore
                    runCatching {
                        store.applyReview(
                            g.mangaId,
                            g.terms.map { eu.kanade.translation.glossary.GlossaryStore.Entry(it.source, it.translation, kind = it.kind, gender = it.gender, note = it.note) },
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        context.toast(context.stringResource(ATMR.strings.col_review_done, result.corrected, result.approved, result.deleted, result.glossaries.size))
                    } else {
                        context.toast(ATMR.strings.col_review_not_file)
                    }
                    refresh++
                }
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
                            Text(stringResource(ATMR.strings.db_templates, s.templates))
                            Text(
                                stringResource(
                                    ATMR.strings.db_codex_terms,
                                    ManhwaCodex.Pack.entries.filter { it.id in codexPacks }.sumOf { ManhwaCodex.size(it) },
                                ),
                            )
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
                // MANHWA master codex: each genre's standard terms, on or off by pack
                item {
                    Card {
                        Text(stringResource(ATMR.strings.db_codex), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(stringResource(ATMR.strings.db_codex_summary), style = MaterialTheme.typography.bodySmall)
                        ManhwaCodex.Pack.entries.forEach { pack ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        when (pack) {
                                            ManhwaCodex.Pack.SYSTEM -> ATMR.strings.db_pack_system
                                            ManhwaCodex.Pack.MURIM -> ATMR.strings.db_pack_murim
                                            ManhwaCodex.Pack.FANTASY -> ATMR.strings.db_pack_fantasy
                                            ManhwaCodex.Pack.MODERN -> ATMR.strings.db_pack_modern
                                            ManhwaCodex.Pack.GAMING -> ATMR.strings.db_pack_gaming
                                            ManhwaCodex.Pack.IDIOMS -> ATMR.strings.db_pack_idioms
                                        },
                                        ManhwaCodex.size(pack),
                                    ),
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = pack.id in codexPacks,
                                    onCheckedChange = { on ->
                                        prefs.translationCodexPacks().set(if (on) codexPacks + pack.id else codexPacks - pack.id)
                                    },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(ATMR.strings.db_healer), fontWeight = FontWeight.Bold)
                                Text(stringResource(ATMR.strings.db_healer_summary), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = healOcr, onCheckedChange = { prefs.translationOcrHealer().set(it) })
                        }
                    }
                }
                // MANHWA optimizer: the team's editor reviews the most reused lines
                item {
                    Card {
                        Text(stringResource(ATMR.strings.db_optimize), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(stringResource(ATMR.strings.db_optimize_summary), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            enabled = !optimizing,
                            onClick = {
                                if (!eu.kanade.translation.translator.appAiConfigured()) {
                                    optimized = noAi
                                } else {
                                    optimizing = true
                                    optimized = null
                                    scope.launch {
                                        val from = eu.kanade.translation.recognizer.TextRecognizerLanguage.fromPref(prefs.translateFromLanguage())
                                        val to = eu.kanade.translation.translator.TextTranslatorLanguage.fromPref(prefs.translateToLanguage())
                                        val result = runCatching {
                                            CollectionOptimizer.optimize(collection, from.name, to.name, to.label) { reviewed, improved ->
                                                optimized = progressTemplate.replace("%1\$d", "$reviewed").replace("%2\$d", "$improved")
                                            }
                                        }
                                        optimizing = false
                                        optimized = result.fold(
                                            { optimizedTemplate.replace("%1\$d", "${it.reviewed}").replace("%2\$d", "${it.improved}") },
                                            { it.message ?: noAi },
                                        )
                                        refresh++
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(if (optimizing) ATMR.strings.db_optimizing else ATMR.strings.db_optimize_go))
                        }
                        optimized?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                // MANHWA review: export what the AI saved and nobody checked, correct it, import it back
                item {
                    Card {
                        Text(stringResource(ATMR.strings.col_review_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val (count, since) = unreviewed
                        Text(
                            if (count > 0) {
                                stringResource(
                                    ATMR.strings.col_review_body,
                                    count.toInt(),
                                    java.text.DateFormat.getDateInstance().format(java.util.Date(since)),
                                )
                            } else {
                                stringResource(ATMR.strings.col_review_none)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val (names, series) = unreviewedNames
                        if (names > 0) Text(stringResource(ATMR.strings.col_review_names, names, series), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = count > 0 || names > 0,
                                onClick = { reviewExportLauncher.launch("manhwa_review_${java.time.LocalDate.now()}.json") },
                            ) { Text(stringResource(ATMR.strings.col_review_export)) }
                            OutlinedButton(onClick = { reviewImportLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
                                Text(stringResource(ATMR.strings.col_review_import))
                            }
                        }
                        // The rounds so far, newest first: each one holds what the AI added after the one before
                        reviewRounds.forEachIndexed { i, round ->
                            Text(
                                stringResource(
                                    ATMR.strings.col_review_round,
                                    reviewRounds.size - i,
                                    java.text.DateFormat.getDateInstance().format(java.util.Date(round.at)),
                                    round.corrected,
                                    round.approved,
                                    round.deleted,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
