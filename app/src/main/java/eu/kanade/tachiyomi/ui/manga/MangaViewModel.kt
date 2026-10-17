package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadErrorLog
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import mihon.app.di.appGraph
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.translation.translator.TextTranslatorLanguage
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class MangaViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val isFromSource: Boolean,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val libraryPreferences: LibraryPreferences,
    trackPreferences: TrackPreferences,
    readerPreferences: ReaderPreferences,
    private val trackerManager: TrackerManager,
    private val trackChapter: TrackChapter,
    private val downloadManager: DownloadManager,
    private val translationManager: TranslationManager,
    private val downloadCache: DownloadCache,
    private val getMangaAndChapters: GetMangaWithChapters,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getAvailableScanlators: GetAvailableScanlators,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val addTracks: AddTracks,
    private val setMangaCategories: SetMangaCategories,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val sourceManager: SourceManager,
    private val refreshTracks: RefreshTracks,
    private val coverCache: CoverCache,
) : ViewModel() {

    private val subscriberGate = SubscriberGate()

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, isFromSource: Boolean): MangaViewModel
    }

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(viewModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds = MutableStateFlow(emptySet<Long>())

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val isRefreshingData = MutableStateFlow(false)

    private val downloadStates = MutableStateFlow(emptyMap<Long, Download.State>())

    // TachiyomiAT: live translation states reported by the translator, overriding the queried ones
    private val translationStates = MutableStateFlow(emptyMap<Long, Translation.State>())

    private val chapterFusion = context.appGraph.chapterFusion

    /** MANHWA fusion: the entries whose chapters continue this work (kept ones included), when it is fused. */
    private val fusionOther: StateFlow<Set<Long>> = chapterFusion.fusions
        .map { it[mangaId]?.continuationIds().orEmpty().toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, chapterFusion.fusionOf(mangaId)?.continuationIds().orEmpty().toSet())

    /** This work's chapters, or those of its fused continuation. */
    private fun isOurs(id: Long): Boolean = id == mangaId || id in fusionOther.value

    /** MANHWA fusion: the continuation's chapters the translate sheet offered last, by id. */
    private var continuationChapters: Map<Long, Chapter> = emptyMap()

    /** MANHWA: download stage of chapters that are fetched to be translated (before they reach the queue). */
    private val downloadStage = MutableStateFlow<Map<Long, Translation.Progress>>(emptyMap())

    /** MANHWA: every chapter of this manga in the translation queue, with its stage and real percentage. */
    private val queueProgress = combine(translationManager.queueState, fusionOther) { queue, _ -> queue }
        .flatMapLatest { queue ->
            val mine = queue.filter { isOurs(it.manga.id) }
            if (mine.isEmpty()) {
                flowOf(emptyMap<Long, Translation.Progress>())
            } else {
                combine(mine.map { t -> t.progress.map { t.chapter.id to it } }) { it.toMap() }
            }
        }

    /** MANHWA: stage and percentage per chapter, read from the queues themselves (right after leaving and coming back). */
    val translationProgress: StateFlow<Map<Long, Translation.Progress>> = combine(downloadStage, queueProgress) { d, q -> d + q }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Shown next to a chapter while it is fetched or translated; null when there is nothing to show. */
    fun getTranslationProgress(chapterId: Long): Translation.Progress? = translationProgressMap[chapterId]

    private val translationProgressMap = mutableStateMapOf<Long, Translation.Progress>()


    // Progress ticks often, so only the download indicators that read it recompose instead of every tick rebuilding
    // the chapter list
    private val downloadProgress = mutableStateMapOf<Long, Int>()

    // A finished chapter leaves the queue, and its last status change can be lost with it, so its state is left to
    // the queried item once it's no longer queued
    private val queuedDownloadStates = combine(downloadStates, downloadManager.queueState) { states, queue ->
        val queuedChapterIds = queue.mapTo(HashSet()) { it.chapter.id }
        states.filterKeys { it in queuedChapterIds }
    }

    private val hideMissingChapters = libraryPreferences.hideMissingChapters.get()

    private var hasPromptedToAddBefore = false

    private val defaultChapterFlagsJob = viewModelScope.launch {
        val manga = getMangaAndChapters.awaitManga(mangaId)
        if (!manga.favorite) {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    private val mangaAndChapters = combine(
        flow {
            // So an entry outside the library isn't shown with its old chapter settings first
            defaultChapterFlagsJob.join()
            emitAll(getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true))
        }
            .distinctUntilChanged(),
        downloadCache.changes.pausedWhileUnsubscribed(),
        downloadManager.queueState.pausedWhileUnsubscribed(),
        translationManager.queueState.pausedWhileUnsubscribed(),
    ) { (manga, chapters), _, _, _ ->
        val source = sourceManager.getOrStub(manga.source)
        MangaAndChapters(
            manga = manga,
            source = source,
            chapters = chapters.toChapterListItems(manga, source),
        )
    }

    private val scanlators = combine(
        getAvailableScanlators.subscribe(mangaId).distinctUntilChanged(),
        getExcludedScanlators.subscribe(mangaId).distinctUntilChanged(),
        ::Pair,
    )

    private val trackers = flow { emit(sourceManager.getOrStub(getMangaAndChapters.awaitManga(mangaId).source)) }
        .flatMapLatest { source ->
            combine(
                getTracks.subscribe(mangaId).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
        }
        .distinctUntilChanged()
        .onStart { emit(0 to false) }

    val state: StateFlow<State> = combine(
        mangaAndChapters,
        scanlators,
        trackers,
        combine(selectedChapterIds, queuedDownloadStates.pausedWhileUnsubscribed(), translationStates, ::Triple),
        combine(dialog, isRefreshingData, ::Pair),
    ) {
            mangaAndChapters,
            (availableScanlators, excludedScanlators),
            (trackingCount, hasLoggedInTrackers),
            (selectedIds, downloads, translations),
            (dialog, isRefreshingData),
        ->
        State.Success(
            manga = mangaAndChapters.manga,
            source = mangaAndChapters.source,
            isFromSource = isFromSource,
            chapters = mangaAndChapters.chapters.map { item ->
                val downloadState = downloads[item.id]
                val translationState = translations[item.id]
                val selected = item.id in selectedIds
                if (downloadState == null && translationState == null && !selected) return@map item
                item.copy(
                    downloadState = downloadState ?: item.downloadState,
                    translationState = translationState ?: item.translationState,
                    selected = selected,
                )
            },
            availableScanlators = availableScanlators,
            excludedScanlators = excludedScanlators,
            trackingCount = trackingCount,
            hasLoggedInTrackers = hasLoggedInTrackers,
            isRefreshingData = isRefreshingData,
            dialog = dialog,
            hideMissingChapters = hideMissingChapters,
        )
    }
        // Composition would otherwise build these on first read, on the main thread, for every new state
        .onEach {
            it.processedChapters
            it.chapterListItems
            it.isAnySelected
            it.selectedChapters
            it.hasUnreadChapters
            it.hasReadChapters
            it.missingChapterCount
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope + subscriberGate, subscriberGate.whileSubscribed(5.seconds), State.Loading)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            downloadManager.statusFlow()
                .filter { isOurs(it.manga.id) }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(::updateDownloadState)
        }
        // TachiyomiAT
        viewModelScope.launch(Dispatchers.Default) {
            translationManager.statusFlow()
                .filter { isOurs(it.manga.id) }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect { translation -> translationStates.update { it + (translation.chapter.id to translation.status) } }
        }
        // MANHWA: the end of every chapter, also of those that left the queue or were turned away before it
        viewModelScope.launch(Dispatchers.Default) {
            translationManager.finished
                .filter { isOurs(it.mangaId) }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect { end -> translationStates.update { it + (end.chapterId to end.state) } }
        }
        viewModelScope.launch(Dispatchers.Default) {
            downloadManager.progressFlow()
                .filter { isOurs(it.manga.id) }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect {
                    if (it.manga.id == mangaId) downloadProgress[it.chapter.id] = it.progress
                    // MANHWA: a chapter fetched to be translated shows its download as the first quarter
                    if (it.chapter.id in translationManager.translateAfterDownload) {
                        val pages = it.pages?.size ?: 0
                        val stage = Translation.Progress(
                            stage = Translation.Stage.DOWNLOADING,
                            done = it.downloadedImages,
                            total = pages,
                            percent = Translation.Stage.DOWNLOADING.percentAt(it.downloadedImages, pages)
                                .coerceAtLeast(it.progress * 25 / 100),
                        )
                        downloadStage.update { map ->
                            val old = map[it.chapter.id]
                            map + (it.chapter.id to stage.copy(percent = maxOf(stage.percent, old?.percent ?: 0), startedAt = old?.startedAt ?: stage.startedAt))
                        }
                    }
                }
        }
        // MANHWA: the per-chapter percentages, kept as state so only the rows that change are redrawn
        viewModelScope.launch {
            translationProgress.collect { map ->
                translationProgressMap.keys.retainAll(map.keys)
                translationProgressMap.putAll(map)
            }
        }

        viewModelScope.launch {
            // The state loads the entry and its chapters anyway, so they aren't queried separately
            val loadedState = state.filterIsInstance<State.Success>().first()
            val manga = loadedState.manga
            val needRefreshInfo = !manga.initialized
            val needRefreshChapter = loadedState.chapters.isEmpty()

            // Fetch info-chapters when needed
            if (needRefreshInfo || needRefreshChapter) {
                isRefreshingData.value = true
                fetchAllFromSource(
                    manga = manga,
                    manualFetch = false,
                    fetchDetails = needRefreshInfo,
                    fetchChapters = needRefreshChapter,
                )
                isRefreshingData.value = false
            }
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            isRefreshingData.value = true
            fetchAllFromSource(
                manga = getMangaAndChapters.awaitManga(mangaId),
                manualFetch = manualFetch,
                fetchDetails = true,
                fetchChapters = true,
            )
            isRefreshingData.value = false
        }
    }

    private suspend fun fetchAllFromSource(
        manga: Manga,
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        try {
            val update = updateMangaFromRemote(
                source = sourceManager.getOrStub(manga.source),
                manga = manga,
                fetchDetails = fetchDetails,
                fetchChapters = fetchChapters,
                manualFetch = manualFetch,
            )
                .getOrThrow()

            if (manualFetch) {
                downloadNewChapters(update.newChapters)
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                viewModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers(coverCache) != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    onRemoved()
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        dialog.value = Dialog.DuplicateManga(manga, duplicates)
                        return@launch
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launch
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default', no categories, or the MANHWA organizer places it by itself
                    defaultCategoryId == 0L || categories.isEmpty() || libraryPreferences.autoOrganize.get() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launch
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                appScope.launch { addTracks.bindEnhancedTrackers(manga, state.source) }
            }
        }
    }

    fun showRemoveFromLibraryDialog() {
        if (successState?.manga?.favorite == true) dialog.value = Dialog.RemoveFromLibrary
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            dialog.value = Dialog.ChangeCategory(
                manga = manga,
                initialSelection = categories.mapAsCheckboxState { it.id in selection },
            )
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.SetFetchInterval(manga)
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launch {
            updateManga.awaitUpdateFetchInterval(
                // Custom intervals are negative
                manga.copy(fetchInterval = -interval),
            )
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        appScope.launch { downloadManager.deleteManga(state.manga, state.source) }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(categories)
        if (manga.favorite) return

        viewModelScope.launch {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        viewModelScope.launch {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun updateDownloadState(download: Download) {
        val chapterId = download.chapter.id
        downloadStates.update {
            // Terminal states are derived by the queried item itself, so drop the override instead
            // of letting it outlive reality, e.g. showing a since deleted chapter as downloaded.
            if (download.status == Download.State.NOT_DOWNLOADED || download.status == Download.State.DOWNLOADED) {
                it - chapterId
            } else {
                it + (chapterId to download.status)
            }
        }
        if (download.status == Download.State.DOWNLOADING) {
            downloadProgress[chapterId] = download.progress
        } else {
            downloadProgress.remove(chapterId)
        }
        // MANHWA: a chapter waiting to be translated whose download failed or was cancelled won't be translated
        val failed = download.status == Download.State.ERROR || download.status == Download.State.NOT_DOWNLOADED
        if (failed && translationManager.translateAfterDownload.remove(chapterId)) {
            downloadStage.update { map ->
                val old = map[chapterId] ?: Translation.Progress(stage = Translation.Stage.DOWNLOADING)
                map + (chapterId to old.copy(note = Translation.Note.DOWNLOAD_FAILED, detail = DownloadErrorLog.get(download.manga.id, download.chapter.name)))
            }
            translationStates.update { it + (chapterId to Translation.State.ERROR) }
        } else if (download.status == Download.State.DOWNLOADED) {
            // Its translation takes over from here
            downloadStage.update { it - chapterId }
        } else if (download.status == Download.State.QUEUE && chapterId in translationManager.translateAfterDownload) {
            downloadStage.update { map -> if (chapterId in map) map else map + (chapterId to Translation.Progress()) }
        }
    }

    fun getDownloadProgress(chapterId: Long): Int {
        return downloadProgress[chapterId] ?: 0
    }

    private fun List<Chapter>.toChapterListItems(manga: Manga, source: Source): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        val translatedFiles = if (isLocal) emptySet() else translationManager.getTranslatedFileNames(manga, source)
        val queuedDownloads = if (isLocal) emptyMap() else downloadManager.getQueuedDownloadsByChapterId()
        val downloadedChapterIds = if (isLocal) emptySet() else downloadManager.getDownloadedChapterIds(this, manga)
        return map { chapter ->
            val activeDownload = queuedDownloads[chapter.id]
            val downloaded = isLocal || chapter.id in downloadedChapterIds
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                translationState = if (
                    downloaded && translationManager.getTranslationFileName(chapter) in translatedFiles
                ) {
                    Translation.State.TRANSLATED
                } else {
                    Translation.State.NOT_TRANSLATED
                },
            )
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        return successState.chapters.getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        if (successState == null) return
        val startNowId = if (startNow) chapters.singleOrNull()?.id ?: return else null

        viewModelScope.launch {
            // The queueing outlives the screen, the add to library prompt doesn't
            appScope.launch {
                if (startNowId != null) {
                    downloadManager.startDownloadNow(startNowId)
                } else {
                    downloadChapters(chapters)
                }
            }.join()
            if (startNowId == null) toggleAllSelection(false)

            if (!isFavorited && !hasPromptedToAddBefore) {
                hasPromptedToAddBefore = true
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    // TachiyomiAT
    fun runChapterTranslationAction(item: ChapterList.Item, action: ChapterTranslationAction) {
        val success = state.value as? State.Success ?: return
        val chapterId = item.id
        when (action) {
            ChapterTranslationAction.START -> {
                translationStates.update { it + (chapterId to Translation.State.QUEUE) }
                if (!item.isDownloaded) {
                    // MANHWA: "try again" on a chapter whose download failed: fetched again, then translated
                    downloadStage.update { it + (chapterId to Translation.Progress()) }
                    appScope.launch(Dispatchers.IO) {
                        translationManager.translateAfterDownload += chapterId
                        downloadManager.downloadChapters(success.manga, listOf(item.chapter))
                    }
                    return
                }
                downloadStage.update { it - chapterId }
                viewModelScope.launch(Dispatchers.IO) {
                    translationManager.translateChapter(success.manga, item.chapter)
                }
            }
            ChapterTranslationAction.CANCEL -> {
                translationManager.getQueuedTranslationOrNull(chapterId)?.let(translationManager::cancelQueuedTranslation)
                // A chapter still being fetched to be translated is simply not translated when it arrives
                translationManager.translateAfterDownload.remove(chapterId)
                downloadStage.update { it - chapterId }
                translationStates.update { it + (chapterId to Translation.State.NOT_TRANSLATED) }
            }
            ChapterTranslationAction.DELETE -> {
                translationManager.deleteTranslation(item.chapter, success.manga, success.source)
                translationStates.update { it + (chapterId to Translation.State.NOT_TRANSLATED) }
            }
        }
    }

    // MANHWA: translate chapters from the manga screen, without downloading them by hand first

    /**
     * Progress of the chapters asked to translate from this screen: how many ended ([done], failed ones
     * included), the chapter in progress with its stage, the real percentage of the chapter ([percent]) and of
     * all of them ([totalPercent]), and at the end ([finished]) the result.
     */
    data class TranslateBatch(
        val done: Int,
        val total: Int,
        val current: String?,
        val fetching: Boolean,
        val failed: Int = 0,
        val progress: Translation.Progress? = null,
        val percent: Int = 0,
        val totalPercent: Int = 0,
        val finished: Boolean = false,
        /** When the chapters were asked for, for the time spent and the time left. */
        val startedAt: Long = System.currentTimeMillis(),
    )

    private val translateBatchIds = MutableStateFlow<List<Long>>(emptyList())

    private var translateBatchStartedAt = System.currentTimeMillis()

    val translateBatch: StateFlow<TranslateBatch?> = combine(translateBatchIds, translationStates, translationProgress) { ids, states, progress ->
        if (ids.isEmpty()) return@combine null
        val items = allChapters.orEmpty().associateBy { it.id }
        fun ended(id: Long) = states[id] == Translation.State.TRANSLATED || states[id] == Translation.State.ERROR
        val failed = ids.count { states[it] == Translation.State.ERROR }
        val done = ids.count(::ended)
        // The one working now, else the next one waiting (it may still be downloading)
        val current = ids.firstOrNull { !ended(it) && progress[it]?.stage?.let { s -> s != Translation.Stage.QUEUED } == true }
            ?: ids.firstOrNull { !ended(it) }
        val chapterPercent = { id: Long -> if (ended(id)) 100 else progress[id]?.percent ?: 0 }
        TranslateBatch(
            done = done,
            total = ids.size,
            current = current?.let { items[it]?.chapter?.name ?: continuationChapters[it]?.name },
            fetching = current != null && (items[current]?.isDownloaded == false || current in translationManager.translateAfterDownload),
            failed = failed,
            progress = current?.let { progress[it] },
            percent = current?.let(chapterPercent) ?: 100,
            totalPercent = ids.sumOf(chapterPercent) / ids.size,
            finished = done >= ids.size,
            startedAt = translateBatchStartedAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Unread chapters in reading order: the ones the translate sheet offers. */
    fun translateCandidates(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val unread = allChapters.orEmpty().filter { !it.chapter.read }.map { it.chapter }.sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) unread.reversed() else unread
    }

    fun totalChapterCount(): Int = allChapters.orEmpty().size

    /**
     * MANHWA fusion: what the translate sheet offers. A fused work read in the language chapters are translated
     * to (its own chapters are ready) offers only its continuation's unread chapters, oldest first; any other
     * work offers its own ([translateCandidates]).
     */
    suspend fun loadTranslateCandidates(): TranslateCandidates = withContext(Dispatchers.IO) {
        val manga = successState?.manga ?: return@withContext TranslateCandidates(emptyList(), 0, continuation = false)
        val baseLang = (sourceManager.get(manga.source) as? CatalogueSource)?.lang.orEmpty()
        val toLang = TextTranslatorLanguage.fromPref(context.appGraph.translationPreferences.translateToLanguage()).code
        if (fusionOther.value.isEmpty() || !eu.kanade.translation.unified.FusionRules.sameLanguage(baseLang, toLang)) {
            continuationChapters = emptyMap()
            return@withContext TranslateCandidates(translateCandidates(), totalChapterCount(), continuation = false)
        }
        val all = chapterFusion.continuation(mangaId, refresh = false).map { it.chapter }
        continuationChapters = all.associateBy { it.id }
        TranslateCandidates(all.filter { !it.read }.sortedBy { it.chapterNumber }, all.size, continuation = true)
    }

    data class TranslateCandidates(val chapters: List<Chapter>, val total: Int, val continuation: Boolean)

    /**
     * MANHWA fusion: translates a chapter of the continuation. One that is not downloaded is fetched first and
     * translated as soon as it arrives, as on this work's own chapters.
     */
    fun translateContinuation(chapter: Chapter) {
        if (chapter.mangaId !in fusionOther.value) return
        val chapterId = chapter.id
        translationStates.update { it + (chapterId to Translation.State.QUEUE) }
        appScope.launch(Dispatchers.IO) {
            // The chapter's own entry: the current continuation, or one kept from an earlier source
            val other = context.appGraph.getMangaById.await(chapter.mangaId) ?: return@launch
            // Shown as fetching from the start; dropped at once when it was downloaded already
            downloadStage.update { it + (chapterId to Translation.Progress()) }
            if (!translationManager.translateOrFetch(other, chapter)) downloadStage.update { it - chapterId }
        }
    }

    /**
     * Translates [count] unread chapters (all of them when null) starting at [startChapterId]. Chapters that
     * are not downloaded are fetched from the source first, by themselves, and translated as soon as they arrive.
     */
    /**
     * MANHWA: stops every chapter of the batch that has not ended: queued and running translations are
     * cancelled, and chapters still downloading only to be translated stop downloading. Translated ones stay.
     */
    fun stopTranslateBatch() {
        val ids = translateBatchIds.value
        if (ids.isEmpty()) return
        val states = translationStates.value
        val open = ids.filter { states[it] != Translation.State.TRANSLATED && states[it] != Translation.State.ERROR }
        val fetching = open.filter { translationManager.translateAfterDownload.remove(it) }
        open.forEach { id ->
            translationManager.getQueuedTranslationOrNull(id)?.let(translationManager::cancelQueuedTranslation)
        }
        downloadStage.update { it - open.toSet() }
        translationStates.update { map -> map + open.associateWith { Translation.State.NOT_TRANSLATED } }
        translateBatchIds.value = emptyList()
        if (fetching.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                downloadManager.cancelQueuedDownloads(fetching.mapNotNull(downloadManager::getQueuedDownloadOrNull))
            }
        }
    }

    /** MANHWA: tries the chapters of the batch that failed again; each carries on from where it stopped. */
    fun retryFailedInBatch() {
        val states = translationStates.value
        val items = allChapters.orEmpty().associateBy { it.id }
        val failedIds = translateBatchIds.value.filter { states[it] == Translation.State.ERROR }
        val failed = failedIds.mapNotNull { items[it] }
        // MANHWA fusion: failed chapters of the continuation
        val failedContinuation = failedIds.filter { it !in items }.mapNotNull { continuationChapters[it] }
        if (failed.isEmpty() && failedContinuation.isEmpty()) return
        translateBatchStartedAt = System.currentTimeMillis()
        failed.forEach { runChapterTranslationAction(it, ChapterTranslationAction.START) }
        failedContinuation.forEach(::translateContinuation)
    }

    fun translateChapters(count: Int?, startChapterId: Long?) {
        // MANHWA fusion: the sheet offered the continuation's chapters
        if (continuationChapters.isNotEmpty()) {
            val candidates = continuationChapters.values.filter { !it.read }.sortedBy { it.chapterNumber }
            val start = candidates.indexOfFirst { it.id == startChapterId }.coerceAtLeast(0)
            val picked = candidates.drop(start).let { if (count != null) it.take(count) else it }
            if (picked.isEmpty()) return
            translateBatchStartedAt = System.currentTimeMillis()
            translateBatchIds.value = picked.map { it.id }
            picked.forEach(::translateContinuation)
            return
        }
        val manga = successState?.manga ?: return
        val candidates = translateCandidates()
        val start = candidates.indexOfFirst { it.id == startChapterId }.coerceAtLeast(0)
        val picked = candidates.drop(start).let { if (count != null) it.take(count) else it }
        if (picked.isEmpty()) return
        val items = allChapters.orEmpty().associateBy { it.id }
        val (downloaded, missing) = picked.partition { items[it.id]?.isDownloaded == true }
        translateBatchStartedAt = System.currentTimeMillis()
        translateBatchIds.value = picked.map { it.id }
        translationStates.update { states -> states + picked.associate { it.id to Translation.State.QUEUE } }
        downloadStage.update { map -> map - picked.map { it.id }.toSet() + missing.associate { it.id to Translation.Progress() } }
        // The queue outlives the screen
        appScope.launch(Dispatchers.IO) {
            missing.forEach { translationManager.translateAfterDownload += it.id }
            if (missing.isNotEmpty()) downloadManager.downloadChapters(manga, missing)
            downloaded.forEach { translationManager.translateChapter(manga, it) }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
            DownloadAction.CANCEL_QUEUED -> {
                cancelWorkQueue()
                return
            }
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    /** MANHWA: every chapter of this work waiting to download (and to be translated after) is stopped. */
    private fun cancelWorkQueue() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            val queued = downloadManager.queueState.value.filter { it.manga.id == manga.id }
            queued.forEach { translationManager.translateAfterDownload.remove(it.chapter.id) }
            if (queued.isNotEmpty()) downloadManager.cancelQueuedDownloads(queued)
            queued.forEach { updateDownloadState(it.apply { status = Download.State.NOT_DOWNLOADED }) }
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        viewModelScope.launch {
            downloadManager.cancelQueuedDownloads(listOf(activeDownload))
            updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
        }
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launch {
            // The read state is written even if the screen closes, the tracking prompt isn't
            appScope.launch {
                setReadStatus.await(
                    read = read,
                    chapters = chapters.toTypedArray(),
                )
            }.join()

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launch
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launch
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                return@launch
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers() {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withContext(Dispatchers.Main) {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private suspend fun downloadChapters(chapters: List<Chapter>) {
        val manga = successState?.manga ?: return
        downloadManager.downloadChapters(manga, chapters)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        viewModelScope.launch {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(it.id) { bookmark = bookmarked } }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        val state = successState ?: return
        // Outlives the screen, which the delete dialog usually closes along with
        appScope.launch {
            try {
                downloadManager.deleteChapters(chapters, state.manga, state.source)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        appScope.launch {
            val manga = successState?.manga ?: return@launch
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        appScope.launch {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        appScope.launch {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        appScope.launch {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        appScope.launch {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        appScope.launch {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            appScope.launch {
                libraryPreferences.setChapterSettingsDefault(manga)
                if (applyToExisting) {
                    setMangaDefaultChapterFlags.awaitAll()
                }
            }.join()
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        val chapters = successState?.processedChapters ?: return
        val selectedIndex = chapters.indexOfFirst { it.id == item.chapter.id }
        if (selectedIndex < 0) return

        // Read from the ids, as the state catches up with a selection only after it's been made
        val selectedIds = HashSet(selectedChapterIds.value)
        if ((item.id in selectedIds) == selected) return

        val firstSelection = chapters.none { it.id in selectedIds }
        selectedIds.addOrRemove(item.id, selected)

        if (selected && fromLongPress) {
            if (firstSelection) {
                selectedPositions[0] = selectedIndex
                selectedPositions[1] = selectedIndex
            } else {
                // Try to select the items in-between when possible
                val range: IntRange
                if (selectedIndex < selectedPositions[0]) {
                    range = selectedIndex + 1..<selectedPositions[0]
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    range = (selectedPositions[1] + 1)..<selectedIndex
                    selectedPositions[1] = selectedIndex
                } else {
                    // Just select itself
                    range = IntRange.EMPTY
                }

                range.forEach { selectedIds.add(chapters[it].id) }
            }
        } else if (!fromLongPress) {
            if (!selected) {
                if (selectedIndex == selectedPositions[0]) {
                    selectedPositions[0] = chapters.indexOfFirst { it.id in selectedIds }
                } else if (selectedIndex == selectedPositions[1]) {
                    selectedPositions[1] = chapters.indexOfLast { it.id in selectedIds }
                }
            } else {
                if (selectedIndex < selectedPositions[0]) {
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    selectedPositions[1] = selectedIndex
                }
            }
        }
        selectedChapterIds.value = selectedIds
    }

    fun toggleAllSelection(selected: Boolean) {
        selectedChapterIds.value = if (selected) allChapters.orEmpty().mapTo(HashSet()) { it.id } else emptySet()
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        val selectedIds = selectedChapterIds.value
        selectedChapterIds.value = allChapters.orEmpty().filterNot { it.id in selectedIds }.mapTo(HashSet()) { it.id }
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    // Chapters list - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object RemoveFromLibrary : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun dismissDialog() {
        dialog.value = null
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        dialog.value = Dialog.DeleteChapters(chapters)
    }

    fun showSettingsDialog() {
        dialog.value = Dialog.SettingsSheet
    }

    fun showTrackDialog() {
        dialog.value = Dialog.TrackSheet
    }

    fun showCoverDialog() {
        dialog.value = Dialog.FullCover
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.Migrate(target = manga, current = duplicate)
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        viewModelScope.launch {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    private data class MangaAndChapters(val manga: Manga, val source: Source, val chapters: List<ChapterList.Item>)

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hideMissingChapters: Boolean = false,
        ) : State {
            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val selectedChapters by lazy {
                processedChapters.fastFilter { it.selected }
            }

            val hasUnreadChapters by lazy {
                processedChapters.fastAny { !it.chapter.read }
            }

            val hasReadChapters by lazy {
                chapters.fastAny { it.chapter.read }
            }

            val missingChapterCount by lazy {
                if (hideMissingChapters) return@lazy 0
                processedChapters.fastMap { it.chapter.chapterNumber }.missingChaptersCount()
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    if (lowerChapter == null) {
                        floor(higherChapter.chapter.chapterNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                val chapterSort = getChapterSort(manga)
                return asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> chapterSort(chapter1, chapter2) }
            }
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val selected: Boolean = false,
        val translationState: Translation.State = Translation.State.NOT_TRANSLATED, // TachiyomiAT
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}
