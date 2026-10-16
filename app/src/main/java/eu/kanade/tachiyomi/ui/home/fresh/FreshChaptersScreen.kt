package eu.kanade.tachiyomi.ui.home.fresh

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
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
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Every new chapter: from all home sources (each with its own tab) and from the library, newest first. */
class FreshChaptersScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<FreshChaptersViewModel>()
        val state by viewModel.state.collectAsState()
        var tab by rememberSaveable { mutableStateOf("all") }
        val openReader = { mangaId: Long, chapterId: Long -> context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId)) }

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.home_new_chapters),
                    subtitle = stringResource(ATMR.strings.fresh_subtitle),
                    onBack = navigator::pop,
                    actions = {
                        if (state.running) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            ManhwaKit.IconOrb(MaterialSymbols.Rounded.Refresh, onClick = viewModel::refresh)
                        }
                    },
                )
                val sourceIds = state.items.map { it.sourceId }.distinct()
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { ManhwaKit.Chip(stringResource(ATMR.strings.fresh_all), tab == "all") { tab = "all" } }
                    item { ManhwaKit.Chip(stringResource(ATMR.strings.drop_tab, state.drops.size), tab == "fav") { tab = "fav" } }
                    item { ManhwaKit.Chip(stringResource(ATMR.strings.fresh_library, state.library.size), tab == "lib") { tab = "lib" } }
                    items(sourceIds) { id ->
                        val count = state.items.count { it.sourceId == id }
                        ManhwaKit.Chip("${state.names[id] ?: id} · $count", tab == "$id") { tab = "$id" }
                    }
                }
                tachiyomi.presentation.core.components.material.PullRefresh(
                    refreshing = state.running,
                    enabled = true,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.weight(1f),
                ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (tab == "fav") {
                        if (state.drops.isEmpty()) item { Empty(stringResource(ATMR.strings.drop_empty)) }
                        items(state.drops, key = { "d${it.mangaId}|${it.chapterId}" }) { drop ->
                            ChapterItem(
                                cover = MangaCover(drop.mangaId, 0L, true, drop.thumbnail, null),
                                title = drop.title,
                                chapter = drop.chapterName,
                                detail = listOf(relative(drop.at), drop.sourceName).filter { it.isNotBlank() }.joinToString(" • "),
                                onClick = { openReader(drop.mangaId, drop.chapterId) },
                            )
                        }
                    } else if (tab == "lib") {
                        if (state.library.isEmpty()) item { Empty(stringResource(ATMR.strings.fresh_empty)) }
                        items(state.library, key = { "l${it.chapterId}" }) { update ->
                            ChapterItem(
                                cover = update.coverData,
                                title = update.mangaTitle,
                                chapter = update.chapterName,
                                detail = relative(update.dateFetch.toEpochMilliseconds()),
                                onClick = { openReader(update.mangaId, update.chapterId) },
                            )
                        }
                    } else {
                        val list = state.items.filter { tab == "all" || "${it.sourceId}" == tab }
                        if (list.isEmpty()) {
                            item { Empty(stringResource(if (state.running) ATMR.strings.fresh_checking else ATMR.strings.fresh_empty)) }
                        }
                        items(list, key = { "f${it.sourceId}|${it.chapterUrl}" }) { item ->
                            ChapterItem(
                                cover = MangaCover(item.mangaId, item.sourceId, false, item.thumbnail, null),
                                title = item.title,
                                chapter = item.chapterName,
                                detail = relative(item.sortTime) + " • " + (state.names[item.sourceId] ?: ""),
                                onClick = { viewModel.open(item, openReader) { navigator.push(MangaScreen(it, true)) } },
                            )
                        }
                    }
                }
                }
            }
        }
    }

    @Composable
    private fun Empty(text: String) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(8.dp))
    }

    @Composable
    private fun ChapterItem(cover: Any?, title: String, chapter: String, detail: String, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .glass(RoundedCornerShape(20.dp), alpha = 0.45f)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(70.dp).fillMaxHeight().clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))) {
                AsyncImage(
                    model = cover,
                    placeholder = ColorPainter(Color(0x1F888888)),
                    error = ColorPainter(Color(0xFF1A1418)),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(chapter, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                modifier = Modifier.padding(end = 14.dp).size(10.dp).background(colors.primary, androidx.compose.foundation.shape.CircleShape),
            )
        }
    }

    @Composable
    private fun relative(millis: Long): String = when {
        millis <= 0L -> ""
        System.currentTimeMillis() - millis < JUST_NOW_MS -> stringResource(ATMR.strings.fresh_just_now)
        else -> DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    private companion object {
        const val JUST_NOW_MS = 60_000L
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class FreshChaptersViewModel(
    private val engine: FreshChaptersEngine,
    favoriteDrops: FavoriteDrops,
    sourceManager: SourceManager,
    getUpdates: GetUpdates,
) : ViewModel() {

    data class State(
        val items: List<FreshChaptersEngine.Item> = emptyList(),
        val library: List<UpdatesWithRelations> = emptyList(),
        val names: Map<Long, String> = emptyMap(),
        val running: Boolean = false,
        val drops: List<FavoriteDrops.Drop> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        engine.state
            .onEach { snap ->
                val names = snap.items.map { it.sourceId }.distinct().associateWith { id -> sourceManager.get(id)?.name ?: "$id" }
                _state.update { it.copy(items = snap.items, running = snap.running, names = names) }
            }
            .launchIn(viewModelScope)
        getUpdates.subscribe(read = false, after = Clock.System.now() - 30.days)
            .catch { }
            .onEach { list -> _state.update { it.copy(library = list.take(200)) } }
            .launchIn(viewModelScope)
        favoriteDrops.archive
            .onEach { list -> _state.update { it.copy(drops = list) } }
            .launchIn(viewModelScope)
        viewModelScope.launch(Dispatchers.IO) { if (engine.isStaleLoaded()) engine.refresh() }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { engine.refresh() }
    }

    fun open(item: FreshChaptersEngine.Item, openReader: (Long, Long) -> Unit, fallback: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = engine.open(item)
            withContext(Dispatchers.Main) { if (ids != null) openReader(ids.first, ids.second) else fallback(item.mangaId) }
        }
    }
}
