package eu.kanade.tachiyomi.ui.home.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extension.interactor.AddExtensionStore
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.combine
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetFavorites
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
    private val adultFilter: eu.kanade.domain.source.service.AdultFilter,
    private val networkToLocalManga: NetworkToLocalManga,
    private val extensionManager: ExtensionManager,
    private val addExtensionStore: AddExtensionStore,
    private val intelligence: HomeIntelligence,
    private val getFavorites: GetFavorites,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val fresh: eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersEngine,
    private val readingProgress: eu.kanade.tachiyomi.ui.home.stats.ReadingProgressEngine,
    context: android.content.Context,
    getUpdates: GetUpdates,
    getHistory: GetHistory,
    getCategories: GetCategories,
) : ViewModel() {

    enum class RecsProblem { NOT_CONFIGURED, FAILED }

    /** Reading numbers for the home dashboard. */
    data class Stats(
        val libraryCount: Int = 0,
        /** Series read on each of the last 7 days, oldest first. */
        val week: List<Int> = List(7) { 0 },
        val weekReadMs: Long = 0,
    )

    /** How far the reader is in the series at the top of "continue reading". */
    data class Progress(val mangaId: Long, val read: Int, val total: Int)

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
        /** Newest chapters of every series on the home sources, in the library or not. */
        val fresh: List<eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersEngine.Item> = emptyList(),
        val freshRunning: Boolean = false,
        val continueReading: List<HistoryWithRelations> = emptyList(),
        val categories: List<Category> = emptyList(),
        val sourceFilter: Long? = null,
        val refreshing: Boolean = false,
        val stats: Stats = Stats(),
        /** MANHWA: streak, hunter rank and the level of every manhwa read. */
        val game: eu.kanade.tachiyomi.ui.home.stats.ReadingProgressEngine.Snapshot? = null,
        val recommendations: List<HomeIntelligence.Recommendation> = emptyList(),
        val recsLoading: Boolean = false,
        /** Null when fine, otherwise why recommendations could not be made. */
        val recsProblem: RecsProblem? = null,
        val alerts: List<HomeIntelligence.SourceAlert> = emptyList(),
        val progress: Progress? = null,
    ) {
        val needsSetup: Boolean get() = selectedSourceIds.isEmpty()
        val visibleFeeds: List<SourceFeed> get() = feeds.filter { sourceFilter == null || it.source.id == sourceFilter }

        /** Latest titles of every chosen source, interleaved so no single source floods the list. */
        val latest: List<Pair<Manga, Source>> get() = interleave { it.latest }
        val popular: List<Pair<Manga, Source>> get() = interleave { it.popular }

        /**
         * Popular titles ranked across all chosen sources: a title high on many sources' popular lists
         * beats one that is only popular on a single source.
         */
        val trending: List<Pair<Manga, Source>> get() {
            val scores = LinkedHashMap<String, Pair<Pair<Manga, Source>, Double>>()
            visibleFeeds.forEach { feed ->
                feed.popular.forEachIndexed { index, manga -> add(scores, manga, feed.source, 1.0 / (index + 2)) }
                feed.latest.forEachIndexed { index, manga -> add(scores, manga, feed.source, 0.3 / (index + 2)) }
            }
            return scores.values.sortedByDescending { it.second }.map { it.first }
        }

        private fun add(
            scores: LinkedHashMap<String, Pair<Pair<Manga, Source>, Double>>,
            manga: Manga,
            source: Source,
            score: Double,
        ) {
            val key = manga.title.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "${manga.id}" }
            val current = scores[key]
            if (current == null) {
                scores[key] = (manga to source) to score
            } else {
                // Keep the entry that has more details to show
                val best = if (current.first.first.description.isNullOrBlank() && !manga.description.isNullOrBlank()) {
                    manga to source
                } else {
                    current.first
                }
                scores[key] = best to (current.second + score + 0.5)
            }
        }

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
        setUpBuiltinStore()
        // Global chapter sync: hourly in the background, and now when the data is old
        eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersEngine.schedule(context)
        fresh.state
            .onEach { snap -> _state.update { it.copy(fresh = snap.items, freshRunning = snap.running) } }
            .launchIn(viewModelScope)
        if (fresh.isStale()) viewModelScope.launch(Dispatchers.IO) { fresh.refresh() }
        // Sources and the reader's choices are watched, so a newly installed extension shows up at once.
        combine(
            sourceManager.sources,
            sourcePreferences.enabledLanguages.changes(),
            sourcePreferences.disabledSources.changes(),
            adultFilter.rule(),
        ) { sources, languages, disabled, adult ->
            sources
                .filter {
                    !it.isLocal() && it !is tachiyomi.domain.source.model.StubSource && "${it.id}" !in disabled &&
                        !adult(it.id, it.name)
                }
                .sortedWith(compareBy<Source>({ it.lang !in languages }, { it.name.lowercase() }))
        }
            .catch { logcat(LogPriority.ERROR, it) }
            .combine(sourcePreferences.homeSources.changes()) { available, ids -> available to ids }
            .distinctUntilChanged()
            .onEach { (available, ids) ->
                val before = _state.value
                _state.update { it.copy(availableSources = available, selectedSourceIds = ids) }
                val loadedIds = before.feeds.map { "${it.source.id}" }.toSet()
                val nowIds = ids.filter { id -> available.any { "${it.id}" == id } }.toSet()
                when {
                    loadedIds == nowIds -> Unit
                    loadedIds.isEmpty() -> load()
                    // Only the sources that were added or removed change; the other feeds stay as they are
                    else -> changeFeeds(
                        added = available.filter { "${it.id}" in nowIds && "${it.id}" !in loadedIds },
                        removed = loadedIds - nowIds,
                    )
                }
            }
            .launchIn(viewModelScope)
        sourcePreferences.homeSourcesOrder.changes()
            .onEach { _state.update { s -> s.copy(feeds = s.feeds.inHomeOrder { it.source.id }) } }
            .launchIn(viewModelScope)
        getUpdates.subscribe(read = false, after = Clock.System.now() - 7.days)
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list -> _state.update { it.copy(newInLibrary = list.distinctBy { u -> u.mangaId }.take(20)) } }
            .launchIn(viewModelScope)
        getHistory.subscribe("")
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list ->
                val distinct = list.distinctBy { h -> h.mangaId }
                _state.update { it.copy(continueReading = distinct.take(15), stats = it.stats.withHistory(list)) }
                loadGame()
                distinct.firstOrNull()?.let { loadProgress(it.mangaId) }
            }
            .launchIn(viewModelScope)
        viewModelScope.launch(Dispatchers.IO) { loadLibraryCount() }
        getCategories.subscribe()
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list -> _state.update { it.copy(categories = list.filterNot { c -> c.isSystemCategory }) } }
            .launchIn(viewModelScope)
    }

    fun setSources(ids: Set<String>) {
        sourcePreferences.homeSources.set(ids)
    }

    /**
     * First run: adds the built-in extension store and turns on English and Arabic, so every source
     * of the store is listed in My sources without the reader looking for a store link.
     */
    private fun setUpBuiltinStore() {
        if (sourcePreferences.manhwaBuiltinStore.get()) return
        viewModelScope.launch(Dispatchers.IO) {
            sourcePreferences.enabledLanguages.set(sourcePreferences.enabledLanguages.get() + BUILTIN_LANGUAGES)
            addExtensionStore(BUILTIN_STORE)
            // Already added counts as done too; any other failure is retried next time.
            sourcePreferences.manhwaBuiltinStore.set(true)
            runCatching { extensionManager.findAvailableExtensions() }
        }
    }

    fun setSourceFilter(sourceId: Long?) {
        _state.update { it.copy(sourceFilter = sourceId) }
    }

    fun refresh() {
        load(refreshing = true)
        viewModelScope.launch(Dispatchers.IO) { fresh.refresh() }
        viewModelScope.launch(Dispatchers.IO) { loadLibraryCount() }
    }

    private var gameJob: kotlinx.coroutines.Job? = null

    /** The reading game again after reading changed; a newer reload replaces one still running. */
    private fun loadGame() {
        gameJob?.cancel()
        gameJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { readingProgress.load() }
                .onSuccess { game -> _state.update { it.copy(game = game) } }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else logcat(LogPriority.ERROR, it) }
        }
    }

    /** The next chapter to read in [mangaId], for the level cards' continue button. */
    suspend fun nextChapter(mangaId: Long) = readingProgress.nextChapter(mangaId)

    private suspend fun loadLibraryCount() {
        val count = runCatching { getFavorites.await().size }.getOrNull() ?: return
        _state.update { it.copy(stats = it.stats.copy(libraryCount = count)) }
    }

    private fun loadProgress(mangaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val chapters = runCatching { getChaptersByMangaId.await(mangaId) }.getOrNull() ?: return@launch
            if (chapters.isEmpty()) return@launch
            _state.update { it.copy(progress = Progress(mangaId, chapters.count { c -> c.read }, chapters.size)) }
        }
    }

    private fun Stats.withHistory(history: List<HistoryWithRelations>): Stats {
        val dayMs = 86_400_000L
        val now = System.currentTimeMillis()
        val startOfToday = now - Math.floorMod(now + java.util.TimeZone.getDefault().getOffset(now), dayMs)
        val week = IntArray(7)
        var readMs = 0L
        history.forEach { h ->
            val at = h.readAt?.toEpochMilliseconds() ?: return@forEach
            val daysAgo = if (at >= startOfToday) 0 else ((startOfToday - at) / dayMs + 1).toInt()
            if (daysAgo in 0..6) {
                week[6 - daysAgo]++
                readMs += h.readDuration
            }
        }
        return copy(week = week.toList(), weekReadMs = readMs)
    }

    /** Feeds follow the order the reader set in the source hub. */
    private fun <T> List<T>.inHomeOrder(id: (T) -> Long): List<T> {
        val position = sourcePreferences.homeSourcesOrder.get().split(',').withIndex().associate { (i, v) -> v to i }
        return sortedBy { position["${id(it)}"] ?: Int.MAX_VALUE }
    }

    private fun load(refreshing: Boolean = false) {
        val ids = _state.value.selectedSourceIds
        val sources = ids.mapNotNull { id -> _state.value.availableSources.firstOrNull { "${it.id}" == id } }
            .inHomeOrder { it.id }
        loadJob?.cancel()
        _state.update { s -> s.copy(feeds = sources.map { SourceFeed(it) }, refreshing = refreshing) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(PARALLEL_SOURCES)
            coroutineScope {
                sources.map { source ->
                    async {
                        gate.withPermit { loadFeed(source) }
                    }
                }.awaitAll()
            }
            _state.update { it.copy(refreshing = false) }
            // Pull to refresh only reloads the feeds; picks and alerts keep their own cache times
            loadIntelligence(force = false)
        }
    }

    private suspend fun loadFeed(source: Source) {
        val latest = fetch(source) { source.getLatestUpdates(1) }
        val popular = fetch(source) { source.getPopularManga(1) }
        currentCoroutineContext().ensureActive()
        val feed = SourceFeed(
            source = source,
            latest = latest.orEmpty(),
            popular = popular.orEmpty(),
            loading = false,
            failed = latest == null && popular == null,
        )
        _state.update { s -> s.copy(feeds = s.feeds.map { if (it.source.id == source.id) feed else it }) }
    }

    private fun changeFeeds(added: List<Source>, removed: Set<String>) {
        _state.update { s ->
            s.copy(
                feeds = (s.feeds.filterNot { "${it.source.id}" in removed } + added.map { SourceFeed(it) })
                    .inHomeOrder { it.source.id },
                sourceFilter = s.sourceFilter?.takeIf { id -> "$id" !in removed },
            )
        }
        if (added.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { fresh.refresh() }
        viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(PARALLEL_SOURCES)
            coroutineScope { added.map { source -> async { gate.withPermit { loadFeed(source) } } }.awaitAll() }
        }
    }

    private suspend fun loadIntelligence(force: Boolean) {
        val sources = _state.value.feeds.filterNot { it.failed }.map { it.source }
        if (_state.value.recommendations.isEmpty()) {
            _state.update { it.copy(recommendations = intelligence.cachedRecommendations()) }
        }
        if (force || !intelligence.recommendationsAreFresh()) {
            loadRecommendations(sources)
        }
        val alerts = runCatching { intelligence.alerts(sources, force) }
            .onFailure { logcat(LogPriority.ERROR, it) }
            .getOrNull()
        if (alerts != null) _state.update { it.copy(alerts = alerts) }
    }

    private suspend fun loadRecommendations(sources: List<Source>) {
        if (!intelligence.aiConfigured()) {
            _state.update { it.copy(recsProblem = RecsProblem.NOT_CONFIGURED) }
            return
        }
        if (sources.isEmpty()) return
        _state.update { it.copy(recsLoading = true, recsProblem = null) }
        runCatching { intelligence.recommend(sources) }
            .onSuccess { list -> _state.update { it.copy(recommendations = list, recsLoading = false) } }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) {
                    _state.update { it.copy(recsLoading = false) }
                    throw e
                }
                logcat(LogPriority.ERROR, e)
                _state.update { it.copy(recsLoading = false, recsProblem = if (it.recommendations.isEmpty()) RecsProblem.FAILED else null) }
            }
    }

    private var recsJob: Job? = null

    fun refreshRecommendations() {
        if (recsJob?.isActive == true) return
        recsJob = viewModelScope.launch(Dispatchers.IO) {
            loadRecommendations(_state.value.feeds.filterNot { it.failed }.map { it.source })
        }
    }

    /** Opens the newer chapter from the other source: saves its chapters, then hands back what to read. */
    /** Opens a chapter of the global feed in the reader, or the series when the chapter can't be found. */
    fun openFresh(item: eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersEngine.Item, open: (Long, Long) -> Unit, fallback: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = fresh.open(item)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (ids != null) open(ids.first, ids.second) else fallback(item.mangaId)
            }
        }
    }

    fun openAlert(alert: HomeIntelligence.SourceAlert, open: (Long, Long) -> Unit, fallback: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = runCatching { intelligence.openAlert(alert) }.getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (ids != null) open(ids.first, ids.second) else fallback(alert.otherManga.id)
            }
        }
    }

    private suspend fun fetch(source: Source, call: suspend () -> MangasPage): List<Manga>? {
        val page = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            try {
                call()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: return null
        val mangas = page.mangas.take(MAX_PER_LIST).map { it.toDomainManga(source.id) }.distinctBy { it.url }
        return runCatching { networkToLocalManga(mangas) }.getOrNull()
    }

    private companion object {
        const val PARALLEL_SOURCES = 4
        const val SOURCE_TIMEOUT_MS = 30_000L
        const val MAX_PER_LIST = 20
        const val AUTO_PICK = 6
        const val BUILTIN_STORE = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        val BUILTIN_LANGUAGES = setOf("en", "ar", "all")
    }
}
