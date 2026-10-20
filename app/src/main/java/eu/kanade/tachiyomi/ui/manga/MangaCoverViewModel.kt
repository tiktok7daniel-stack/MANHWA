package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.home.discover.SourceHealthManager
import eu.kanade.translation.unified.UnifiedRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.image.LocalCoverManager
import kotlin.time.Duration.Companion.seconds

private const val SEARCH_PARALLEL = 4
private const val SEARCH_TIMEOUT_MS = 15_000L
private const val COVERS_PER_SOURCE = 3

@AssistedInject
class MangaCoverViewModel(
    @Assisted private val mangaId: Long,
    private val getManga: GetManga,
    private val imageSaver: ImageSaver,
    private val coverCache: CoverCache,
    private val updateManga: UpdateManga,
    private val coverManager: LocalCoverManager,
    private val sourceManager: SourceManager,
    private val sourceHealth: SourceHealthManager,
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long): MangaCoverViewModel
    }

    val state: StateFlow<Manga?> = getManga.subscribe(mangaId)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    fun saveCover(context: Context) {
        viewModelScope.launch {
            try {
                saveCoverInternal(context, temp = false)
                snackbarHostState.showSnackbar(
                    context.stringResource(MR.strings.cover_saved),
                    withDismissAction = true,
                )
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
                snackbarHostState.showSnackbar(
                    context.stringResource(MR.strings.error_saving_cover),
                    withDismissAction = true,
                )
            }
        }
    }

    fun shareCover(context: Context) {
        viewModelScope.launch {
            try {
                val uri = saveCoverInternal(context, temp = true) ?: return@launch
                withContext(Dispatchers.Main) {
                    context.startActivity(uri.toShareIntent(context))
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
                snackbarHostState.showSnackbar(
                    context.stringResource(MR.strings.error_sharing_cover),
                    withDismissAction = true,
                )
            }
        }
    }

    /**
     * Save manga cover Bitmap to picture or temporary share directory.
     *
     * @param context The context for building and executing the ImageRequest
     * @return the uri to saved file
     */
    private suspend fun saveCoverInternal(context: Context, temp: Boolean): Uri? {
        val manga = state.value ?: return null
        val req = ImageRequest.Builder(context)
            .data(manga)
            .size(Size.ORIGINAL)
            .build()

        return withContext(Dispatchers.IO) {
            val result = context.imageLoader.execute(req).image?.asDrawable(context.resources)

            // TODO: Handle animated cover
            val bitmap = result?.getBitmapOrNull() ?: return@withContext null
            imageSaver.save(
                Image.Cover(
                    bitmap = bitmap,
                    name = manga.title,
                    location = if (temp) Location.Cache else Location.Pictures.create(),
                ),
            )
        }
    }

    /**
     * Update cover with local file.
     *
     * @param context Context.
     * @param data uri of the cover resource.
     */
    fun editCover(context: Context, data: Uri) {
        val manga = state.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            context.contentResolver.openInputStream(data)?.use {
                try {
                    manga.editCover(coverManager, it, updateManga, coverCache)
                    notifyCoverUpdated(context)
                } catch (e: Exception) {
                    notifyFailedCoverUpdate(context, e)
                }
            }
        }
    }

    /** MANHWA: a cover another source has for this series (picked in the cover picker). */
    data class CoverOption(val sourceId: Long, val sourceName: String, val title: String, val url: String, val match: Boolean)

    private val _options = MutableStateFlow<List<CoverOption>>(emptyList())
    val options: StateFlow<List<CoverOption>> = _options.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private var search: Job? = null

    /**
     * Looks for [query] on the installed sources (not the local folder, not this series' own source, not one hidden
     * for not working), a few at a time, and lists the covers they give, the same work first. Each source shows
     * its first [COVERS_PER_SOURCE] results, so a series named otherwise there can still be picked by its cover.
     */
    fun findCovers(query: String) {
        val manga = state.value ?: return
        val q = query.trim().ifEmpty { manga.title }
        search?.cancel()
        _options.value = emptyList()
        search = viewModelScope.launch(Dispatchers.IO) {
            _searching.value = true
            try {
                val sources = sourceManager.getAll().filterIsInstance<CatalogueSource>()
                    .filter { it.id != LocalSource.ID && it.id != manga.source && !sourceHealth.isHidden(it.id) }
                val gate = Semaphore(SEARCH_PARALLEL)
                coroutineScope {
                    sources.forEach { source ->
                        launch {
                            gate.withPermit {
                                val found = runCatching {
                                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) { source.getSearchManga(1, q, FilterList()) }
                                        ?.mangas.orEmpty()
                                        .filter { !it.thumbnail_url.isNullOrBlank() }
                                        .take(COVERS_PER_SOURCE)
                                        .map {
                                            val match = UnifiedRules.sameWork(it.title, manga.title) || UnifiedRules.sameWork(it.title, q)
                                            CoverOption(source.id, source.name, it.title, it.thumbnail_url!!, match)
                                        }
                                }.getOrDefault(emptyList())
                                if (found.isNotEmpty()) _options.update { (it + found).sortedByDescending { o -> o.match } }
                            }
                        }
                    }
                }
            } finally {
                _searching.value = false
            }
        }
    }

    /** Puts [option]'s picture as this series' cover (fetched with its source's headers, like the source's covers). */
    fun useCover(context: Context, option: CoverOption, onDone: () -> Unit) {
        val manga = state.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = sourceManager.get(option.sourceId) as? HttpSource ?: error("Source ${option.sourceId} not installed")
                source.client.newCall(GET(option.url, source.headers)).await().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    checkNotNull(response.body).byteStream().use { manga.editCover(coverManager, it, updateManga, coverCache) }
                }
                notifyCoverUpdated(context)
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notifyFailedCoverUpdate(context, e)
                // The picker is on top of the snackbar
                withContext(Dispatchers.Main) { context.toast(MR.strings.notification_cover_update_failed) }
            }
        }
    }

    fun deleteCustomCover(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                coverCache.deleteCustomCover(mangaId)
                updateManga.awaitUpdateCoverLastModified(mangaId)
                notifyCoverUpdated(context)
            } catch (e: Exception) {
                notifyFailedCoverUpdate(context, e)
            }
        }
    }

    fun addToLibraryWarning(context: Context) {
        viewModelScope.launch {
            snackbarHostState.showSnackbar(
                context.stringResource(MR.strings.notification_first_add_to_library),
                withDismissAction = true,
            )
        }
    }

    private fun notifyCoverUpdated(context: Context) {
        viewModelScope.launch {
            snackbarHostState.showSnackbar(
                context.stringResource(MR.strings.cover_updated),
                withDismissAction = true,
            )
        }
    }

    private fun notifyFailedCoverUpdate(context: Context, e: Throwable) {
        viewModelScope.launch {
            snackbarHostState.showSnackbar(
                context.stringResource(MR.strings.notification_cover_update_failed),
                withDismissAction = true,
            )
            logcat(LogPriority.ERROR, e)
        }
    }
}
