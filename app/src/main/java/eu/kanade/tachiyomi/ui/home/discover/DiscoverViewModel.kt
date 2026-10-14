package eu.kanade.tachiyomi.ui.home.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.model.ContentWarning
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformWhile
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
    private val networkToLocalManga: NetworkToLocalManga,
    private val extensionManager: ExtensionManager,
    private val addExtensionStore: AddExtensionStore,
    private val basePreferences: BasePreferences,
    private val getFavorites: GetFavorites,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    getUpdates: GetUpdates,
    getHistory: GetHistory,
    getCategories: GetCategories,
) : ViewModel() {

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
        val continueReading: List<HistoryWithRelations> = emptyList(),
        val categories: List<Category> = emptyList(),
        val sourceFilter: Long? = null,
        val refreshing: Boolean = false,
        val stats: Stats = Stats(),
        /** Extensions from the reader's stores that are not installed yet, in enabled languages. */
        val addable: List<Extension.Available> = emptyList(),
        val installing: Map<String, InstallStep> = emptyMap(),
        /** Progress of "install all" as done / total, null when idle. */
        val bulk: Pair<Int, Int>? = null,
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
        // Sources and the reader's choices are watched, so a newly installed extension shows up at once.
        combine(
            sourceManager.sources,
            sourcePreferences.enabledLanguages.changes(),
            sourcePreferences.disabledSources.changes(),
        ) { sources, languages, disabled ->
            sources
                .filter { !it.isLocal() && it !is tachiyomi.domain.source.model.StubSource && "${it.id}" !in disabled }
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
                if (loadedIds != nowIds) {
                    _state.update { it.copy(sourceFilter = null) }
                    load()
                }
            }
            .launchIn(viewModelScope)
        combine(
            extensionManager.availableExtensionsFlow,
            extensionManager.loadedExtensionsFlow,
            sourcePreferences.enabledLanguages.changes(),
        ) { available, installed, languages ->
            val installedPkgs = installed.map { it.pkgName }.toSet()
            available
                .filter { it.pkgName !in installedPkgs && (it.lang in languages || it.lang == "all") }
                .distinctBy { it.pkgName }
                .sortedBy { it.name.lowercase() }
        }
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list -> _state.update { it.copy(addable = list) } }
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

    fun toggleSource(id: String) {
        val current = sourcePreferences.homeSources.get()
        sourcePreferences.homeSources.set(if (id in current) current - id else current + id)
    }

    /** Picks the installed sources in the reader's languages, up to [AUTO_PICK]. */
    fun autoPick() {
        val ids = _state.value.availableSources
            .filter { it.lang in sourcePreferences.enabledLanguages.get() }
            .ifEmpty { _state.value.availableSources }
            .take(AUTO_PICK)
            .map { "${it.id}" }
        if (ids.isNotEmpty()) sourcePreferences.homeSources.set(ids.toSet())
    }

    fun refreshExtensions() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { extensionManager.findAvailableExtensions() } }
    }

    /** Installs an extension and puts its sources on the home screen as soon as they load. */
    fun install(extension: Extension.Available) {
        val ids = extension.sources.map { "${it.id}" }
        sourcePreferences.homeSources.set(sourcePreferences.homeSources.get() + ids)
        viewModelScope.launch(Dispatchers.IO) {
            extensionManager.installExtension(extension)
                .untilDone()
                .catch { _state.update { s -> s.copy(installing = s.installing + (extension.pkgName to InstallStep.Error)) } }
                .collect { step -> _state.update { s -> s.copy(installing = s.installing + (extension.pkgName to step)) } }
        }
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

    /** Installs every safe extension in [languages], one after another, without asking for each one. */
    fun installAll(languages: Set<String>) {
        if (_state.value.bulk != null) return
        val queue = _state.value.addable.filter { it.lang in languages && it.contentWarning == ContentWarning.SAFE }
        if (queue.isEmpty()) return
        basePreferences.extensionInstaller.set(BasePreferences.ExtensionInstaller.PRIVATE)
        _state.update { it.copy(bulk = 0 to queue.size) }
        viewModelScope.launch(Dispatchers.IO) {
            queue.forEachIndexed { index, extension ->
                runCatching {
                    extensionManager.installExtension(extension)
                        .catch { emit(InstallStep.Error) }
                        .untilDone()
                        .collect { step ->
                            _state.update { s -> s.copy(installing = s.installing + (extension.pkgName to step)) }
                        }
                }
                _state.update { it.copy(bulk = (index + 1) to queue.size) }
            }
            _state.update { it.copy(bulk = null) }
        }
    }

    /** The installer's step flow never completes on its own; stop at the first final step. */
    private fun Flow<InstallStep>.untilDone() =
        transformWhile { step ->
            emit(step)
            !step.isCompleted()
        }

    fun setSourceFilter(sourceId: Long?) {
        _state.update { it.copy(sourceFilter = sourceId) }
    }

    fun refresh() {
        load(refreshing = true)
        viewModelScope.launch(Dispatchers.IO) { loadLibraryCount() }
    }

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
        const val AUTO_PICK = 6
        const val BUILTIN_STORE = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        val BUILTIN_LANGUAGES = setOf("en", "ar", "all")
    }
}
