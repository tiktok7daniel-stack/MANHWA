package eu.kanade.tachiyomi.data.backup.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.rounded.Upload
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA custom backup: the reader picks the works to carry (a few translated manhwa to give a friend's phone, or
 * to keep), and what goes with them; the file holds only those works, translated chapters and pages included, and
 * restores like any MANHWA backup.
 */
class CustomBackupScreen : Screen() {

    private class Entry(val manga: Manga, val translated: Int, val sourceName: String)

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        var rows by remember { mutableStateOf<List<Entry>?>(null) }
        var query by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf(emptySet<Long>()) }
        var extras by remember { mutableStateOf(true) }
        var fusions by remember { mutableStateOf(true) }
        var settings by remember { mutableStateOf(false) }
        var sources by remember { mutableStateOf(true) }
        var pages by remember { mutableStateOf(true) }
        var started by remember { mutableStateOf(false) }

        // Every work of the library with how many of its chapters are translated (read off the main thread)
        LaunchedEffect(Unit) {
            rows = withContext(Dispatchers.IO) {
                val graph = context.appGraph
                val vault = graph.manhwaVault
                runCatching { vault.mangaRepository.getFavorites() }.getOrDefault(emptyList()).map { manga ->
                    val source = graph.sourceManager.getOrStub(manga.source)
                    val translated = runCatching { graph.translationManager.getTranslatedFileNames(manga, source).size }.getOrDefault(0)
                    Entry(manga, translated, source.name)
                }.sortedWith(compareByDescending<Entry> { it.translated }.thenBy { it.manga.title.lowercase() })
            }
        }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                FullBackupWorker.backupCustom(
                    context,
                    uri,
                    only = chosen,
                    extras = extras,
                    fusions = fusions,
                    settings = settings,
                    sources = sources,
                    translatedPages = pages,
                )
                started = true
            }
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.custom_backup_title),
                    subtitle = stringResource(ATMR.strings.custom_backup_count, chosen.size),
                    onBack = navigator::pop,
                )
                val all = rows
                if (all == null) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Column
                }
                val shown = all.filter { query.isBlank() || it.manga.title.contains(query.trim(), ignoreCase = true) }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            stringResource(ATMR.strings.custom_backup_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = ManhwaInk.muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        ManhwaKit.SearchField(value = query, hint = stringResource(ATMR.strings.custom_backup_search), onValueChange = { query = it })
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ManhwaKit.Chip(stringResource(ATMR.strings.custom_backup_pick_translated), selected = false) {
                                chosen = all.filter { it.translated > 0 }.map { it.manga.id }.toSet()
                            }
                            ManhwaKit.Chip(stringResource(ATMR.strings.custom_backup_clear), selected = false) { chosen = emptySet() }
                        }
                    }
                    items(shown, key = { it.manga.id }) { row ->
                        val on = row.manga.id in chosen
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(16.dp), accent = on, alpha = 0.45f)
                                .pressClickable { chosen = if (on) chosen - row.manga.id else chosen + row.manga.id }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MangaCover.Book(
                                data = row.manga.asMangaCover(),
                                modifier = Modifier.width(42.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.manga.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ManhwaInk.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (row.translated > 0) {
                                        androidx.compose.material3.Icon(
                                            MaterialSymbols.Rounded.Translate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        if (row.translated > 0) {
                                            stringResource(ATMR.strings.custom_backup_translated, row.translated, row.sourceName)
                                        } else {
                                            stringResource(ATMR.strings.custom_backup_none, row.sourceName)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (row.translated > 0) MaterialTheme.colorScheme.primary else ManhwaInk.muted,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Checkbox(checked = on, onCheckedChange = { chosen = if (it) chosen + row.manga.id else chosen - row.manga.id })
                        }
                    }
                }
                // What goes with the chosen works, and the button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = bottom + 8.dp)
                        .glass(RoundedCornerShape(22.dp), alpha = 0.6f)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Toggle(stringResource(ATMR.strings.custom_backup_pages), pages) { pages = it }
                    Toggle(stringResource(ATMR.strings.custom_backup_extras), extras) { extras = it }
                    Toggle(stringResource(ATMR.strings.custom_backup_fusions), fusions) { fusions = it }
                    Toggle(stringResource(ATMR.strings.custom_backup_sources), sources) { sources = it }
                    Toggle(stringResource(ATMR.strings.custom_backup_settings), settings) { settings = it }
                    if (settings) {
                        Text(stringResource(ATMR.strings.full_backup_share_warning), style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB020))
                    }
                    Spacer(Modifier.height(6.dp))
                    if (started) {
                        Text(stringResource(ATMR.strings.custom_backup_started), style = MaterialTheme.typography.labelMedium, color = Color(0xFF3DDC97), fontWeight = FontWeight.Bold)
                    }
                    ManhwaKit.NeonButton(
                        stringResource(ATMR.strings.custom_backup_make, chosen.size),
                        Modifier.fillMaxWidth(),
                        MaterialSymbols.Rounded.Upload,
                    ) {
                        if (chosen.isNotEmpty()) {
                            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                            val single = all.firstOrNull { it.manga.id in chosen }?.manga?.title
                                ?.let { eu.kanade.tachiyomi.util.storage.DiskUtil.buildValidFilename(it).take(40) }
                            val name = if (chosen.size == 1 && single != null) single else "${chosen.size}-works"
                            launcher.launch("MANHWA-$name-$day.${FullBackup.EXTENSION}")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
