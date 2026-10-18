package eu.kanade.tachiyomi.data.backup.vault

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.runtime.collectAsState
import androidx.work.WorkInfo
import mihon.icons.materialsymbols.rounded.Storage
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CloudSync
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.FolderOpen
import mihon.icons.materialsymbols.rounded.Lock
import mihon.icons.materialsymbols.rounded.Upload
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DateFormat
import java.util.Date

/** MANHWA Vault: export, import and sync everything of the app in one encrypted file. */
class ManhwaVaultScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val vault = remember { context.appGraph.manhwaVault }
        val scope = rememberCoroutineScope()
        val colors = MaterialTheme.colorScheme
        var password by remember { mutableStateOf(vault.password.orEmpty()) }
        var folder by remember { mutableStateOf(vault.folder) }
        var working by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var lastSync by remember { mutableStateOf(vault.lastSync) }

        val needPassword = stringResource(ATMR.strings.vault_need_password)
        val exported = stringResource(ATMR.strings.vault_exported)
        val importedTemplate = stringResource(ATMR.strings.vault_imported)
        val wrongPassword = stringResource(ATMR.strings.vault_wrong_password)
        val notVault = stringResource(ATMR.strings.vault_not_vault)
        val failed = stringResource(ATMR.strings.vault_failed)
        val syncWrote = stringResource(ATMR.strings.vault_sync_wrote)
        val syncMerged = stringResource(ATMR.strings.vault_sync_merged)
        val needFolder = stringResource(ATMR.strings.vault_need_folder)
        val fullState by remember { FullBackupWorker.state(context) }.collectAsState(initial = null)
        var allDownloads by remember { mutableStateOf(false) }
        var translatedPages by remember { mutableStateOf(true) }
        val fullBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) FullBackupWorker.backup(context, uri, allDownloads, translatedPages)
        }
        val fullRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) FullBackupWorker.restore(context, uri)
        }

        fun launchTask(block: suspend () -> String) {
            if (working) return
            working = true
            message = null
            scope.launch {
                message = try {
                    block()
                } catch (e: ManhwaVault.WrongPasswordException) {
                    wrongPassword
                } catch (e: ManhwaVault.NotAVaultException) {
                    notVault
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    e.message ?: failed
                }
                working = false
                lastSync = vault.lastSync
            }
        }

        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) launchTask {
                vault.export(uri, password)
                exported
            }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) launchTask {
                val result = vault.import(uri, password)
                importedTemplate.replace("%1\$d", "${result.files}").replace("%2\$d", "${result.lines}")
            }
        }
        val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                vault.folder = uri.toString()
                folder = uri.toString()
            }
        }

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.vault_title),
                    subtitle = stringResource(ATMR.strings.vault_subtitle),
                    onBack = navigator::pop,
                )
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // MANHWA: the full backup, everything including the translated chapters
                    item {
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(MaterialSymbols.Rounded.Storage, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(ATMR.strings.full_backup_title), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                            }
                            Text(stringResource(ATMR.strings.full_backup_info), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.75f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(ATMR.strings.full_backup_all_downloads), style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.text, fontWeight = FontWeight.Bold)
                                    Text(stringResource(ATMR.strings.full_backup_all_downloads_info), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.6f))
                                }
                                Switch(checked = allDownloads, onCheckedChange = { allDownloads = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(ATMR.strings.full_backup_pages), style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.text, fontWeight = FontWeight.Bold)
                                    Text(stringResource(ATMR.strings.full_backup_pages_info), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.6f))
                                }
                                Switch(checked = translatedPages, onCheckedChange = { translatedPages = it })
                            }
                            val state = fullState
                            if (state?.running == true) {
                                Text(stringResource(ATMR.strings.full_backup_progress, state.percent), style = MaterialTheme.typography.labelLarge, color = colors.primary, fontWeight = FontWeight.Bold)
                                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                                if (state.name.isNotBlank()) {
                                    Text(state.name.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.text.copy(alpha = 0.55f), maxLines = 1)
                                }
                            } else {
                                ManhwaKit.NeonButton(stringResource(ATMR.strings.full_backup_make), Modifier.fillMaxWidth(), MaterialSymbols.Rounded.Upload) {
                                    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
                                    fullBackupLauncher.launch("MANHWA-full-$day.${FullBackup.EXTENSION}")
                                }
                                ManhwaKit.GhostButton(stringResource(ATMR.strings.full_backup_restore), Modifier.fillMaxWidth(), MaterialSymbols.Rounded.Download) {
                                    fullRestoreLauncher.launch(arrayOf("*/*"))
                                }
                            }
                            state?.finished?.let { info ->
                                val text = when {
                                    info.state == WorkInfo.State.SUCCEEDED -> stringResource(
                                        ATMR.strings.full_backup_done,
                                        info.outputData.getInt(FullBackupWorker.KEY_CHAPTERS, 0),
                                        info.outputData.getInt(FullBackupWorker.KEY_FILES, 0),
                                    )
                                    info.outputData.getString(FullBackupWorker.KEY_ERROR) == FullBackupWorker.ERROR_NOT_BACKUP -> stringResource(ATMR.strings.full_backup_not_backup)
                                    else -> stringResource(ATMR.strings.full_backup_failed)
                                }
                                Text(text, style = MaterialTheme.typography.bodySmall, color = if (info.state == WorkInfo.State.SUCCEEDED) Color(0xFF3DDC97) else Color(0xFFFFB020), fontWeight = FontWeight.Bold)
                            }
                            Text(stringResource(ATMR.strings.full_backup_share_warning), style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB020))
                        }
                    }
                    item {
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(MaterialSymbols.Rounded.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(ATMR.strings.vault_password), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ManhwaInk.text.copy(alpha = 0.06f))
                                    .border(1.dp, ManhwaInk.text.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                            ) {
                                BasicTextField(
                                    value = password,
                                    onValueChange = {
                                        password = it
                                        vault.password = it
                                    },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    textStyle = TextStyle(color = ManhwaInk.text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                                    cursorBrush = SolidColor(colors.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(stringResource(ATMR.strings.vault_password_hint), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.6f))
                        }
                    }
                    item {
                        Card {
                            ManhwaKit.NeonButton(stringResource(ATMR.strings.vault_export), Modifier.fillMaxWidth(), MaterialSymbols.Rounded.Upload) {
                                if (password.length < MIN_PASSWORD) message = needPassword else exportLauncher.launch("MANHWA-${System.currentTimeMillis() / 1000}.${ManhwaVault.EXTENSION}")
                            }
                            ManhwaKit.GhostButton(stringResource(ATMR.strings.vault_import), Modifier.fillMaxWidth(), MaterialSymbols.Rounded.Download) {
                                if (password.length < MIN_PASSWORD) message = needPassword else importLauncher.launch(arrayOf("*/*"))
                            }
                            Text(stringResource(ATMR.strings.vault_contents), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.6f))
                        }
                    }
                    item {
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(MaterialSymbols.Rounded.CloudSync, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(ATMR.strings.vault_sync), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                            }
                            Text(stringResource(ATMR.strings.vault_sync_hint), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.7f))
                            ManhwaKit.GhostButton(
                                folder?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') } ?: stringResource(ATMR.strings.vault_pick_folder),
                                Modifier.fillMaxWidth(),
                                MaterialSymbols.Rounded.FolderOpen,
                            ) { folderLauncher.launch(null) }
                            ManhwaKit.NeonButton(stringResource(ATMR.strings.vault_sync_now), Modifier.fillMaxWidth(), MaterialSymbols.Rounded.CloudSync) {
                                when {
                                    folder == null -> message = needFolder
                                    password.length < MIN_PASSWORD -> message = needPassword
                                    else -> launchTask {
                                        when (vault.sync()) {
                                            ManhwaVault.SyncResult.WROTE -> syncWrote
                                            ManhwaVault.SyncResult.MERGED_AND_WROTE -> syncMerged
                                            ManhwaVault.SyncResult.WRONG_PASSWORD -> wrongPassword
                                            ManhwaVault.SyncResult.NO_FOLDER -> needFolder
                                            ManhwaVault.SyncResult.NO_PASSWORD -> needPassword
                                            ManhwaVault.SyncResult.FAILED -> failed
                                        }
                                    }
                                }
                            }
                            if (lastSync > 0) {
                                Text(
                                    stringResource(ATMR.strings.vault_last_sync, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync))),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ManhwaInk.text.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                    if (working) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = colors.primary)
                            }
                        }
                    }
                    message?.let { text ->
                        item {
                            Text(
                                text,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.4f).padding(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), alpha = 0.45f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

private const val MIN_PASSWORD = 6
