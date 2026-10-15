package eu.kanade.tachiyomi.ui.library.ai

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
import eu.kanade.translation.translator.AiTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Verified
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/** Sorts the library into categories with AI, or collects the completed series, after a preview. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AiLibraryOrganizerViewModel(
    private val getLibraryManga: GetLibraryManga,
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val setMangaCategories: SetMangaCategories,
    private val translationPreferences: TranslationPreferences,
) : ViewModel() {

    data class Group(val name: String, val mangaIds: List<Long>)

    data class State(
        val working: Boolean = false,
        val preview: List<Group> = emptyList(),
        val message: String? = null,
        val keepExisting: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun setKeepExisting(keep: Boolean) = _state.update { it.copy(keepExisting = keep) }

    /** Asks the AI to split the whole library into categories; shows them as a preview. */
    fun organize(instruction: String, noAiMessage: String, failedMessage: String) {
        if (_state.value.working) return
        if (!eu.kanade.translation.translator.appAiConfigured()) {
            _state.update { it.copy(message = noAiMessage) }
            return
        }
        _state.update { it.copy(working = true, message = null, preview = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val library = getLibraryManga.await().take(MAX_SERIES)
                val list = library.joinToString("\n") { item ->
                    val m = item.manga
                    val status = when (m.status.toInt()) {
                        SManga.ONGOING -> "ongoing"
                        SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> "completed"
                        else -> "unknown"
                    }
                    "${m.id}|${m.title}|${m.genre.orEmpty().take(6).joinToString(",")}|$status|read ${item.readCount}/${item.totalChapters}"
                }
                val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
                val reply = eu.kanade.translation.translator.appAsk(eu.kanade.translation.team.AiRouter.Lane.CHAT) { ai ->
                    ai.askJson(
                        system = """
                            You organize a reader's comic library (manga, manhwa, manhua) into clear categories.
                            Make 4 to 10 categories with short names in $language (for example: Action & System,
                            Fantasy, Romance, Korean manhwa, Reading now, Completed). Every series must go in at least one
                            category and at most two. Follow the reader's instruction when given.
                            Reply with ONLY: {"categories":[{"name":"...","ids":[123,456]}]}
                        """.trimIndent(),
                        prompt = buildString {
                            if (instruction.isNotBlank()) appendLine("Reader's instruction: ${instruction.trim()}")
                            appendLine("Library (id|title|genres|status|progress):")
                            append(list)
                        },
                    )
                }
                val valid = library.map { it.manga.id }.toSet()
                val groups = (reply["categories"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    val ids = (obj["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
                        .filter { it in valid }.distinct()
                    Group(name, ids).takeIf { name.isNotEmpty() && ids.isNotEmpty() }
                }
                _state.update { it.copy(working = false, preview = groups, message = if (groups.isEmpty()) failedMessage else null) }
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
            val applied = apply(listOf(Group(categoryName, done)), keepExisting = true)
            _state.update { it.copy(working = false, message = doneMessage(applied)) }
        }
    }

    fun applyPreview(doneMessage: (Int) -> String) {
        val groups = _state.value.preview
        if (groups.isEmpty() || _state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val applied = apply(groups, _state.value.keepExisting)
            _state.update { it.copy(working = false, preview = emptyList(), message = doneMessage(applied)) }
        }
    }

    /** Creates missing categories, then sets each series' categories. Returns how many series changed. */
    private suspend fun apply(groups: List<Group>, keepExisting: Boolean): Int {
        if (groups.all { it.mangaIds.isEmpty() }) return 0
        val existing = getCategories.await().filterNot { it.isSystemCategory }
        groups.map { it.name }.distinct().forEach { name ->
            if (existing.none { it.name.equals(name, ignoreCase = true) }) createCategoryWithName.await(name)
        }
        val byName = getCategories.await().filterNot { it.isSystemCategory }.associateBy { it.name.lowercase() }
        val assigned = HashMap<Long, MutableSet<Long>>()
        groups.forEach { group ->
            val categoryId = byName[group.name.lowercase()]?.id ?: return@forEach
            group.mangaIds.forEach { assigned.getOrPut(it) { mutableSetOf() } += categoryId }
        }
        val library = getLibraryManga.await().associateBy { it.manga.id }
        assigned.forEach { (mangaId, ids) ->
            val current = library[mangaId]?.categories.orEmpty().filter { it != 0L }
            val result = if (keepExisting) (current + ids).distinct() else ids.toList()
            setMangaCategories.await(mangaId, result)
        }
        return assigned.size
    }

    private companion object {
        const val MAX_SERIES = 400
    }
}

/** Glass screen behind the ✨ button of the library. */
class AiLibraryOrganizerScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<AiLibraryOrganizerViewModel>()
        val state by viewModel.state.collectAsState()
        var instruction by rememberSaveable { mutableStateOf("") }
        val noAi = stringResource(ATMR.strings.recs_setup)
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
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                            ) {
                                if (instruction.isEmpty()) {
                                    Text(
                                        stringResource(ATMR.strings.org_instruction_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.45f),
                                    )
                                }
                                BasicTextField(
                                    value = instruction,
                                    onValueChange = { instruction = it },
                                    textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
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
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f),
                                )
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
                                    color = Color.White,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = state.keepExisting,
                                    onCheckedChange = viewModel::setKeepExisting,
                                    colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = Color.White),
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
