package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.isLocal

class MangaScreen(
    private val mangaId: Long,
    val fromSource: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        // MANHWA: this screen follows the chosen style (and its light version)
        eu.kanade.presentation.components.ManhwaStyledScreen { MangaContent() }
    }

    @Composable
    private fun MangaContent() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val viewModel =
            assistedMetroViewModel<MangaViewModel, MangaViewModel.Factory> {
                create(mangaId = mangaId, isFromSource = fromSource)
            }

        val state by viewModel.state.collectAsStateWithLifecycle()

        if (state is MangaViewModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as MangaViewModel.State.Success
        // MANHWA: whether this series' source opens in the site's own reader (MangaTek by default, or the reader's choice)
        val sourcePrefs = remember { context.appGraph.sourcePreferences }
        val siteOn by sourcePrefs.siteReaderOn.collectAsState()
        val siteOff by sourcePrefs.siteReaderOff.collectAsState()
        val siteReader = eu.kanade.translation.site.SiteReader.opensInSite(successState.source, siteOn, siteOff)
        val isHttpSource = remember { successState.source is HttpSource }

        LaunchedEffect(successState.manga, viewModel.source) {
            if (isHttpSource) {
                try {
                    withContext(Dispatchers.IO) {
                        assistUrl = getMangaUrl(viewModel.manga, viewModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
                }
            }
        }

        // MANHWA: the second system has its own series page (the dialogs below still come from here)
        if (eu.kanade.tachiyomi.ui.nova.rememberAppSystem() != eu.kanade.tachiyomi.ui.nova.AppSystem.CLASSIC) {
            eu.kanade.tachiyomi.ui.nova.NovaMangaDetails(
                state = successState,
                onBack = navigator::pop,
                onRead = { openChapter(context, it) },
                onContinue = { continueReading(context, viewModel.getNextUnreadChapter()) },
                onFavorite = {
                    if (successState.manga.favorite) viewModel.showRemoveFromLibraryDialog() else viewModel.toggleFavorite()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onCategories = viewModel::showChangeCategoryDialog.takeIf { successState.manga.favorite },
                onWebView = { openMangaInWebView(navigator, viewModel.manga, viewModel.source) }.takeIf { isHttpSource },
                onRefresh = viewModel::fetchAllFromSource,
                onDownload = { item: ChapterList.Item ->
                    viewModel.runChapterDownloadActions(
                        listOf(item),
                        if (item.isDownloaded) {
                            eu.kanade.presentation.manga.components.ChapterDownloadAction.DELETE
                        } else {
                            eu.kanade.presentation.manga.components.ChapterDownloadAction.START
                        },
                    )
                }.takeIf { !successState.source.isLocalOrStub() },
                onMarkRead = { chapter, read -> viewModel.markChaptersRead(listOf(chapter), read) },
            )
        } else MangaScreen(
            state = successState,
            snackbarHostState = viewModel.snackbarHostState,
            nextUpdate = successState.manga.expectedNextUpdate,
            isTabletUi = isTabletUi(),
            chapterSwipeStartAction = viewModel.chapterSwipeStartAction,
            chapterSwipeEndAction = viewModel.chapterSwipeEndAction,
            navigateUp = navigator::pop,
            onChapterClicked = { openChapter(context, it) },
            onDownloadChapter = viewModel::runChapterDownloadActions.takeIf { !successState.source.isLocalOrStub() },
            onTranslateChapter = viewModel::runChapterTranslationAction.takeIf { !successState.source.isLocalOrStub() },
            translateBar = if (successState.source.isLocalOrStub()) {
                null
            } else {
                @Composable {
                    // MANHWA: translate chapters from here; missing ones are fetched by themselves
                    val batch by viewModel.translateBatch.collectAsStateWithLifecycle()
                    var showSheet by remember { mutableStateOf(false) }
                    eu.kanade.presentation.manga.components.TranslateChaptersBar(
                        batch = batch,
                        onClick = { showSheet = true },
                        onStopAll = viewModel::stopTranslateBatch,
                        onRetryFailed = viewModel::retryFailedInBatch,
                    )
                    if (showSheet) {
                        // MANHWA fusion: a fused work offers its continuation's chapters (looked up off the main thread)
                        val offered by androidx.compose.runtime.produceState<MangaViewModel.TranslateCandidates?>(null) {
                            value = viewModel.loadTranslateCandidates()
                        }
                        offered?.let { list ->
                            eu.kanade.presentation.manga.components.TranslateChaptersSheet(
                                candidates = list.chapters,
                                totalChapters = list.total,
                                onDismiss = { showSheet = false },
                                onStart = viewModel::translateChapters,
                                continuation = list.continuation,
                            )
                        }
                    }
                }
            },
            downloadProgressProvider = viewModel::getDownloadProgress,
            translationProgressProvider = viewModel::getTranslationProgress,
            onAddToLibraryClicked = {
                if (successState.manga.favorite) {
                    viewModel.showRemoveFromLibraryDialog()
                } else {
                    viewModel.toggleFavorite()
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onWebViewClicked = {
                openMangaInWebView(
                    navigator,
                    viewModel.manga,
                    viewModel.source,
                )
            }.takeIf { isHttpSource },
            onWebViewLongClicked = {
                copyMangaUrl(
                    context,
                    viewModel.manga,
                    viewModel.source,
                )
            }.takeIf { isHttpSource },
            onTrackingClicked = {
                if (!successState.hasLoggedInTrackers) {
                    navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                } else {
                    viewModel.showTrackDialog()
                }
            },
            onTagSearch = { scope.launch { performGenreSearch(navigator, it, viewModel.source!!) } },
            onFilterButtonClicked = viewModel::showSettingsDialog,
            onRefresh = viewModel::fetchAllFromSource,
            onContinueReading = { continueReading(context, viewModel.getNextUnreadChapter()) },
            onSearch = { query, global -> scope.launch { performSearch(navigator, query, global) } },
            onCoverClicked = viewModel::showCoverDialog,
            onShareClicked = { shareManga(context, viewModel.manga, viewModel.source) }.takeIf { isHttpSource },
            onDownloadActionClicked = viewModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
            onEditCategoryClicked = viewModel::showChangeCategoryDialog.takeIf { successState.manga.favorite },
            onEditFetchIntervalClicked = viewModel::showSetFetchIntervalDialog.takeIf {
                successState.manga.favorite
            },
            onMigrateClicked = {
                navigator.push(MigrationConfigScreen(successState.manga.id))
            }.takeIf { successState.manga.favorite },
            onEditNotesClicked = { navigator.push(MangaNotesScreen(manga = successState.manga)) },
            onCoversClicked = {
                // A new cover is kept for a series of the library only
                if (successState.manga.favorite || successState.manga.isLocal()) {
                    viewModel.showCoverPicker()
                } else {
                    context.toast(MR.strings.notification_first_add_to_library)
                }
            },
            onSiteReaderClicked = {
                val source = successState.source
                val prefs = context.appGraph.sourcePreferences
                val byDefault = eu.kanade.translation.site.SiteReader.handles(source)
                val (on, off) = eu.kanade.translation.site.SiteReader.switched(
                    source.id,
                    site = !siteReader,
                    byDefault = byDefault,
                    on = prefs.siteReaderOn.get(),
                    off = prefs.siteReaderOff.get(),
                )
                prefs.siteReaderOn.set(on)
                prefs.siteReaderOff.set(off)
                context.toast(if (!siteReader) ATMR.strings.site_reader_now_on else ATMR.strings.site_reader_now_off)
                Unit
            }.takeIf { isHttpSource },
            siteReader = siteReader,
            onMultiBookmarkClicked = viewModel::bookmarkChapters,
            onMultiMarkAsReadClicked = viewModel::markChaptersRead,
            onMarkPreviousAsReadClicked = viewModel::markPreviousChapterRead,
            onMultiDeleteClicked = viewModel::showDeleteChapterDialog,
            onChapterSwipe = viewModel::chapterSwipe,
            onChapterSelected = viewModel::toggleSelection,
            onAllChapterSelected = viewModel::toggleAllSelection,
            onInvertSelection = viewModel::invertSelection,
        )

        var showScanlatorsDialog by remember { mutableStateOf(false) }

        val onDismissRequest = { viewModel.dismissDialog() }
        when (val dialog = successState.dialog) {
            null -> {}
            MangaViewModel.Dialog.RemoveFromLibrary -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        if (successState.manga.favorite) viewModel.toggleFavorite()
                    },
                    mangaToRemove = successState.manga,
                )
            }
            is MangaViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is MangaViewModel.Dialog.DeleteChapters -> {
                DeleteChaptersDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.toggleAllSelection(false)
                        viewModel.deleteChapters(dialog.chapters)
                    },
                )
            }

            is MangaViewModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.showMigrateDialog(it) },
                )
            }

            is MangaViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaViewModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                onDismissRequest = onDismissRequest,
                manga = successState.manga,
                onDownloadFilterChanged = viewModel::setDownloadedFilter,
                onUnreadFilterChanged = viewModel::setUnreadFilter,
                onBookmarkedFilterChanged = viewModel::setBookmarkedFilter,
                onSortModeChanged = viewModel::setSorting,
                onDisplayModeChanged = viewModel::setDisplayMode,
                onSetAsDefault = viewModel::setCurrentSettingsAsDefault,
                onResetToDefault = viewModel::resetToDefaultSettings,
                scanlatorFilterActive = successState.scanlatorFilterActive,
                onScanlatorFilterClicked = { showScanlatorsDialog = true },
            )
            MangaViewModel.Dialog.TrackSheet -> {
                NavigatorAdaptiveSheet(
                    screen = TrackInfoDialogHomeScreen(
                        mangaId = successState.manga.id,
                        mangaTitle = successState.manga.title,
                        sourceId = successState.source.id,
                    ),
                    enableSwipeDismiss = { it.lastItem is TrackInfoDialogHomeScreen },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaViewModel.Dialog.PickCover -> {
                val vm =
                    assistedMetroViewModel<MangaCoverViewModel, MangaCoverViewModel.Factory> {
                        create(mangaId = mangaId)
                    }
                val manga by vm.state.collectAsStateWithLifecycle()
                val coverOptions by vm.options.collectAsStateWithLifecycle()
                val coverSearching by vm.searching.collectAsStateWithLifecycle()
                manga?.let { m ->
                    eu.kanade.presentation.manga.components.CoverPickerDialog(
                        title = m.title,
                        options = coverOptions,
                        searching = coverSearching,
                        onSearch = vm::findCovers,
                        onPick = { option -> vm.useCover(context, option, onDismissRequest) },
                        onDismissRequest = onDismissRequest,
                    )
                }
            }
            MangaViewModel.Dialog.FullCover -> {
                val vm =
                    assistedMetroViewModel<MangaCoverViewModel, MangaCoverViewModel.Factory> {
                        create(mangaId = mangaId)
                    }
                val manga by vm.state.collectAsStateWithLifecycle()
                if (manga != null) {
                    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                        if (it == null) return@rememberLauncherForActivityResult
                        vm.editCover(context, it)
                    }
                    // MANHWA: the cover picker (covers of the same series on the other sources)
                    var pickCover by remember { mutableStateOf(false) }
                    if (pickCover) {
                        val coverOptions by vm.options.collectAsStateWithLifecycle()
                        val coverSearching by vm.searching.collectAsStateWithLifecycle()
                        eu.kanade.presentation.manga.components.CoverPickerDialog(
                            title = manga!!.title,
                            options = coverOptions,
                            searching = coverSearching,
                            onSearch = vm::findCovers,
                            onPick = { option -> vm.useCover(context, option) { pickCover = false } },
                            onDismissRequest = { pickCover = false },
                        )
                    }
                    MangaCoverDialog(
                        manga = manga!!,
                        snackbarHostState = vm.snackbarHostState,
                        isCustomCover = remember(manga) { manga!!.hasCustomCover() },
                        onShareClick = { vm.shareCover(context) },
                        onSaveClick = { vm.saveCover(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> vm.deleteCustomCover(context)
                                EditCoverAction.ADD_TO_LIBRARY -> vm.addToLibraryWarning(context)
                                EditCoverAction.FROM_SOURCE -> pickCover = true
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            is MangaViewModel.Dialog.SetFetchInterval -> {
                SetIntervalDialog(
                    interval = dialog.manga.fetchInterval,
                    nextUpdate = dialog.manga.expectedNextUpdate,
                    onDismissRequest = onDismissRequest,
                    onValueChanged = { interval: Int -> viewModel.setFetchInterval(dialog.manga, interval) }
                        .takeIf { viewModel.isUpdateIntervalEnabled },
                )
            }
        }

        if (showScanlatorsDialog) {
            ScanlatorFilterDialog(
                availableScanlators = successState.availableScanlators,
                excludedScanlators = successState.excludedScanlators,
                onDismissRequest = { showScanlatorsDialog = false },
                onConfirm = viewModel::setExcludedScanlators,
            )
        }
    }

    private fun continueReading(context: Context, unreadChapter: Chapter?) {
        if (unreadChapter != null) openChapter(context, unreadChapter)
    }

    private fun openChapter(context: Context, chapter: Chapter) {
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
    }

    private fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
        val manga = manga_ ?: return null
        val source = source_ as? HttpSource ?: return null

        return try {
            source.getMangaUrl(manga.toSManga())
        } catch (e: Exception) {
            null
        }
    }

    private fun openMangaInWebView(navigator: Navigator, manga_: Manga?, source_: Source?) {
        getMangaUrl(manga_, source_)?.let { url ->
            navigator.push(
                WebViewScreen(
                    url = url,
                    initialTitle = manga_?.title,
                    sourceId = source_?.id,
                ),
            )
        }
    }

    private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
        try {
            getMangaUrl(manga_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(GlobalSearchScreen(query))
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                previousController.search(query)
            }
            is BrowseSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(navigator: Navigator, genreName: String, source: Source) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseSourceScreen && source is HttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
    }

    /**
     * Copy Manga URL to Clipboard
     */
    private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ as? HttpSource ?: return
        val url = source.getMangaUrl(manga.toSManga())
        context.copyToClipboard(url, url)
    }
}
