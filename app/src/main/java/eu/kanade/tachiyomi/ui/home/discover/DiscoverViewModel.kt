package eu.kanade.tachiyomi.ui.home.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.source.local.isLocal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * MANHWA home: gathers what is inside the reader's chosen sources, so nobody has to open each source,
 * and puts new chapters of their library first.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DiscoverViewModel(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
    getUpdates: GetUpdates,
    getHistory: GetHistory,
    getCategories: GetCategories,
) : ViewModel() {

    data class SourceFeed(
        val source: Source,
        val latest: List<Manga> = emptyList(),
        val popular: List<Manga> = emptyList(),
        val loading: Boolean = true,
        val failed: Boolean = false,
    )

    data class State(
        val selectedSourceIds: Set<String> = emptySet(),
        val availableSources: List<Source> = emptyList(),
        val feeds: List<SourceFeed> = emptyList(),
        val newInLibrary: List<UpdatesWithRelations> = emptyList(),
        val continueReading: List<HistoryWithRelations> = emptyList(),
        val categories: List<Category> = emptyList(),
        val sourceFilter: Long? = null,
        val refreshing: Boolean = false,
    ) {
        val needsSetup: Boolean get() = selectedSourceIds.isEmpty()
        val visibleFeeds: List<SourceFeed> get() = feeds.filter { sourceFilter == null || it.source.id == sourceFilter }

        /** Latest titles of every chosen source, interleaved so no single source floods the list. */
        val latest: List<Pair<Manga, Source>> get() = interleave { it.latest }
        val popular: List<Pair<Manga, Source>> get() = interleave { it.popular }

        private fun interleave(pick: (SourceFeed) -> List<Manga>): List<Pair<Manga, Source>> {
            val lists = visibleFeeds.map { feed -> pick(feed).map { it to feed.source } }
            val longest = lists.maxOfOrNull { it.size } ?: 0
            return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
                .distinctBy { it.first.id }
        }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val enabledLanguages = sourcePreferences.enabledLanguages.get()
            val disabled = sourcePreferences.disabledSources.get()
            val available = sourceManager.getAll()
                .filter { !it.isLocal() && it.lang in enabledLanguages && "${it.id}" !in disabled }
                .sortedBy { it.name.lowercase() }
            _state.update { it.copy(availableSources = available) }
            sourcePreferences.homeSources.changes()
                .distinctUntilChanged()
                .onEach { ids ->
                    _state.update { it.copy(selectedSourceIds = ids, sourceFilter = null) }
                    load()
                }
                .launchIn(this)
        }
        getUpdates.subscribe(read = false, after = Clock.System.now() - 7.days)
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list -> _state.update { it.copy(newInLibrary = list.distinctBy { u -> u.mangaId }.take(20)) } }
            .launchIn(viewModelScope)
        getHistory.subscribe("")
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list -> _state.update { it.copy(continueReading = list.distinctBy { h -> h.mangaId }.take(15)) } }
            .launchIn(viewModelScope)
        getCategories.subscribe()
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list -> _state.update { it.copy(categories = list.filterNot { c -> c.isSystemCategory }) } }
            .launchIn(viewModelScope)
    }

    fun setSources(ids: Set<String>) {
        sourcePreferences.homeSources.set(ids)
    }

    fun setSourceFilter(sourceId: Long?) {
        _state.update { it.copy(sourceFilter = sourceId) }
    }

    fun refresh() = load(refreshing = true)

    private fun load(refreshing: Boolean = false) {
        val ids = _state.value.selectedSourceIds
        val sources = ids.mapNotNull { id -> _state.value.availableSources.firstOrNull { "${it.id}" == id } }
        loadJob?.cancel()
        _state.update { s -> s.copy(feeds = sources.map { SourceFeed(it) }, refreshing = refreshing) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(PARALLEL_SOURCES)
            coroutineScope {
                sources.map { source ->
                    async {
                        gate.withPermit {
                            val latest = fetch(source) { source.getLatestUpdates(1) }
                            val popular = fetch(source) { source.getPopularManga(1) }
                            val feed = SourceFeed(
                                source = source,
                                latest = latest.orEmpty(),
                                popular = popular.orEmpty(),
                                loading = false,
                                failed = latest == null && popular == null,
                            )
                            _state.update { s -> s.copy(feeds = s.feeds.map { if (it.source.id == source.id) feed else it }) }
                        }
                    }
                }.awaitAll()
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetch(source: Source, call: suspend () -> MangasPage): List<Manga>? {
        val page = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { runCatching { call() }.getOrNull() } ?: return null
        val mangas = page.mangas.take(MAX_PER_LIST).map { it.toDomainManga(source.id) }.distinctBy { it.url }
        return runCatching { networkToLocalManga(mangas) }.getOrNull()
    }

    private companion object {
        const val PARALLEL_SOURCES = 4
        const val SOURCE_TIMEOUT_MS = 30_000L
        const val MAX_PER_LIST = 20
    }
}
