package eu.kanade.tachiyomi.ui.library.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.translation.search.AiChatScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Autorenew
import mihon.icons.materialsymbols.rounded.Category
import mihon.icons.materialsymbols.rounded.Undo
import mihon.icons.materialsymbols.rounded.Verified
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** Sorts the library into categories with AI, or collects the completed series, after a preview. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AiLibraryOrganizerViewModel(
    private val getLibraryManga: GetLibraryManga,
    private val organizer: LibraryOrganizer,
    private val libraryPreferences: LibraryPreferences,
) : ViewModel() {

    data class State(
        val working: Boolean = false,
        val preview: List<LibraryOrganizer.Group> = emptyList(),
        val message: String? = null,
        val keepExisting: Boolean = true,
        val scheme: List<LibraryOrganizer.SchemeCategory> = emptyList(),
    )

    private val _state = MutableStateFlow(State(scheme = organizer.scheme()))
    val state = _state.asStateFlow()

    val log = organizer.log
    val autoOrganize = libraryPreferences.autoOrganize
    val autoCompletedMove = libraryPreferences.autoCompletedMove

    /** The last instruction the reader gave, so the field starts with it. */
    fun lastInstruction(): String = libraryPreferences.organizerInstruction.get()

    fun setKeepExisting(keep: Boolean) = _state.update { it.copy(keepExisting = keep) }

    fun setAutoOrganize(on: Boolean) {
        // Turned back on: only what is added from now on, not everything added while it was off
        if (on && !autoOrganize.get()) libraryPreferences.autoOrganizeSince.set(System.currentTimeMillis())
        autoOrganize.set(on)
    }

    fun setAutoCompletedMove(on: Boolean) = autoCompletedMove.set(on)

    fun clearScheme() {
        organizer.clearScheme()
        _state.update { it.copy(scheme = emptyList()) }
    }

    fun undo(entry: LibraryOrganizer.Placed) = organizer.undo(entry)

    /** Asks the AI to split the whole library into categories; shows them as a preview. */
    fun organize(instruction: String, noAiMessage: String, failedMessage: String) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, message = null, preview = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Without a key the built-in genre map still sorts the library
                val groups = if (eu.kanade.translation.translator.appAiConfigured()) {
                    organizer.organizeAll(instruction)
                } else {
                    organizer.organizeAllLocally()
                }
                val message = when {
                    groups.isEmpty() -> failedMessage
                    !eu.kanade.translation.translator.appAiConfigured() -> noAiMessage
                    else -> null
                }
                _state.update { it.copy(working = false, preview = groups, message = message) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                _state.update { it.copy(working = false, message = e.message ?: failedMessage) }
            }
        }
    }

    /** No AI needed: every completed series goes into a "Completed" category. */
    fun collectCompleted(categoryName: String, doneMessage: (Int) -> String) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val done = getLibraryManga.await()
                .filter { it.manga.status.toInt() == SManga.COMPLETED || it.manga.status.toInt() == SManga.PUBLISHING_FINISHED }
                .map { it.manga.id }
            val applied = organizer.apply(listOf(LibraryOrganizer.Group(categoryName, done)), keepExisting = true, remember = false)
            _state.update { it.copy(working = false, message = doneMessage(applied)) }
        }
    }

    fun applyPreview(doneMessage: (Int) -> String) {
        val groups = _state.value.preview
        if (groups.isEmpty() || _state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val applied = organizer.apply(groups, _state.value.keepExisting)
            _state.update {
                it.copy(working = false, preview = emptyList(), message = doneMessage(applied), scheme = organizer.scheme())
            }
        }
    }
}

/** Glass screen behind the ✨ button of the library. */
class AiLibraryOrganizerScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<AiLibraryOrganizerViewModel>()
        val state by viewModel.state.collectAsState()
        var instruction by rememberSaveable { mutableStateOf(viewModel.lastInstruction()) }
        val log by viewModel.log.collectAsState()
        val autoOrganize by viewModel.autoOrganize.collectAsState()
        val autoCompleted by viewModel.autoCompletedMove.collectAsState()
        val noAi = stringResource(ATMR.strings.org_local)
        val failed = stringResource(ATMR.strings.org_failed)
        val completedName = stringResource(ATMR.strings.org_completed_category)
        val doneTemplate = stringResource(ATMR.strings.org_done)
        val colors = MaterialTheme.colorScheme

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.org_title),
                    subtitle = stringResource(ATMR.strings.org_subtitle),
                    onBack = navigator::pop,
                ) {
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.AutoAwesome) { navigator.push(AiChatScreen()) }
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(24.dp), accent = true, alpha = 0.45f)
                                .padding(16.dp),
                        ) {
                            Text(
                                stringResource(ATMR.strings.org_instruction),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 70.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ManhwaInk.text.copy(alpha = 0.06f))
                                    .border(1.dp, ManhwaInk.text.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                            ) {
                                if (instruction.isEmpty()) {
                                    Text(
                                        stringResource(ATMR.strings.org_instruction_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ManhwaInk.text.copy(alpha = 0.45f),
                                    )
                                }
                                BasicTextField(
                                    value = instruction,
                                    onValueChange = { instruction = it },
                                    textStyle = TextStyle(color = ManhwaInk.text, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                                    cursorBrush = SolidColor(colors.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            ManhwaKit.NeonButton(
                                stringResource(ATMR.strings.org_organize),
                                Modifier.fillMaxWidth(),
                                MaterialSymbols.Rounded.AutoAwesome,
                            ) { viewModel.organize(instruction, noAi, failed) }
                            Spacer(Modifier.height(10.dp))
                            ManhwaKit.GhostButton(
                                stringResource(ATMR.strings.org_completed),
                                Modifier.fillMaxWidth(),
                                MaterialSymbols.Rounded.Verified,
                            ) { viewModel.collectCompleted(completedName) { doneTemplate.replace("%d", "$it") } }
                        }
                    }
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(24.dp), alpha = 0.45f)
                                .padding(16.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(MaterialSymbols.Rounded.Autorenew, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(ATMR.strings.org_auto_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.primary,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            SwitchRow(stringResource(ATMR.strings.org_auto), autoOrganize, viewModel::setAutoOrganize)
                            Text(
                                stringResource(ATMR.strings.org_auto_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = ManhwaInk.text.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.height(4.dp))
                            SwitchRow(stringResource(ATMR.strings.org_auto_completed), autoCompleted, viewModel::setAutoCompletedMove)
                        }
                    }
                    if (state.scheme.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glass(RoundedCornerShape(24.dp), alpha = 0.4f)
                                    .padding(16.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(MaterialSymbols.Rounded.Category, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(ATMR.strings.org_scheme), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = ManhwaInk.text)
                                        Text(stringResource(ATMR.strings.org_scheme_sub), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.6f))
                                    }
                                    Text(
                                        stringResource(ATMR.strings.org_scheme_clear),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable(onClick = viewModel::clearScheme)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                                state.scheme.forEach { category ->
                                    Spacer(Modifier.height(8.dp))
                                    Text(category.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ManhwaInk.text)
                                    if (category.about.isNotBlank()) {
                                        Text(category.about, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.6f))
                                    }
                                }
                            }
                        }
                    }
                    if (log.isNotEmpty()) {
                        item { ManhwaKit.SectionTitle(stringResource(ATMR.strings.org_log), null) }
                        items(log.take(MAX_LOG_SHOWN), key = { "${it.mangaId}-${it.at}" }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ManhwaInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(entry.categories.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable { viewModel.undo(entry) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(MaterialSymbols.Rounded.Undo, contentDescription = null, tint = ManhwaInk.text.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(ATMR.strings.org_undo), style = MaterialTheme.typography.labelMedium, color = ManhwaInk.text.copy(alpha = 0.8f))
                                }
                            }
                        }
                    }
                    if (state.working) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            }
                        }
                    }
                    state.message?.let { message ->
                        item {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
                                    .padding(14.dp),
                            )
                        }
                    }
                    if (state.preview.isNotEmpty()) {
                        item {
                            ManhwaKit.SectionTitle(
                                stringResource(ATMR.strings.org_preview),
                                stringResource(ATMR.strings.org_preview_sub, state.preview.size, state.preview.sumOf { it.mangaIds.size }),
                            )
                        }
                        items(state.preview, key = { it.name }) { group ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        group.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Black,
                                        color = ManhwaInk.text,
                                    )
                                    if (group.about.isNotBlank()) {
                                        Text(group.about, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.6f))
                                    }
                                }
                                Text(
                                    "${group.mangaIds.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Black,
                                    color = colors.primary,
                                )
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(ATMR.strings.org_keep),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ManhwaInk.text,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = state.keepExisting,
                                    onCheckedChange = viewModel::setKeepExisting,
                                    colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = ManhwaInk.text),
                                )
                            }
                        }
                        item {
                            ManhwaKit.NeonButton(
                                stringResource(ATMR.strings.org_apply),
                                Modifier.fillMaxWidth(),
                                MaterialSymbols.Rounded.Verified,
                            ) { viewModel.applyPreview { doneTemplate.replace("%d", "$it") } }
                        }
                    }
                    item { Spacer(Modifier.size(24.dp).width(1.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = ManhwaInk.text),
        )
    }
}

private const val MAX_LOG_SHOWN = 12
