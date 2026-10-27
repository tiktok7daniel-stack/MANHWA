package eu.kanade.presentation.manga

import eu.kanade.translation.model.Translation
import eu.kanade.translation.unified.fusionContinuationItems
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.tachiyomi.data.download.DownloadErrorLog
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Group
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.shouldExpandFAB
import tachiyomi.source.local.isLocal
import kotlin.time.Instant

@Composable
fun MangaScreen(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    isTabletUi: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onTranslateChapter: ((ChapterList.Item, ChapterTranslationAction) -> Unit)? = null, // TachiyomiAT
    translateBar: (@Composable () -> Unit)? = null, // MANHWA: translate chapters from here
    downloadProgressProvider: (chapterId: Long) -> Int,
    translationProgressProvider: (chapterId: Long) -> Translation.Progress? = { null }, // MANHWA
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditFetchIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    // MANHWA: the covers other sources have for this series
    onCoversClicked: (() -> Unit)? = null,
    // MANHWA: this series' source read in the site's own reader (lit when on)
    onSiteReaderClicked: (() -> Unit)? = null,
    siteReader: Boolean = false,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val context = LocalContext.current
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    // MANHWA: the whole page takes its colours from the cover, so every work has its own look
    eu.kanade.presentation.manga.components.CoverTheme(state.manga) {
    if (!isTabletUi) {
        MangaScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onTranslateChapter = onTranslateChapter,
            translateBar = translateBar,
            downloadProgressProvider = downloadProgressProvider,
            translationProgressProvider = translationProgressProvider,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onEditNotesClicked = onEditNotesClicked,
            onCoversClicked = onCoversClicked,
            onSiteReaderClicked = onSiteReaderClicked,
            siteReader = siteReader,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onChapterSwipe = onChapterSwipe,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
        )
    } else {
        MangaScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            nextUpdate = nextUpdate,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onTranslateChapter = onTranslateChapter,
            translateBar = translateBar,
            downloadProgressProvider = downloadProgressProvider,
            translationProgressProvider = translationProgressProvider,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onEditNotesClicked = onEditNotesClicked,
            onCoversClicked = onCoversClicked,
            onSiteReaderClicked = onSiteReaderClicked,
            siteReader = siteReader,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onChapterSwipe = onChapterSwipe,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
        )
    }
    }
}

@Composable
private fun MangaScreenSmallImpl(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onTranslateChapter: ((ChapterList.Item, ChapterTranslationAction) -> Unit)? = null, // TachiyomiAT
    translateBar: (@Composable () -> Unit)? = null, // MANHWA: translate chapters from here
    downloadProgressProvider: (chapterId: Long) -> Int,
    translationProgressProvider: (chapterId: Long) -> Translation.Progress? = { null }, // MANHWA
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    // MANHWA: the covers other sources have for this series
    onCoversClicked: (() -> Unit)? = null,
    // MANHWA: this series' source read in the site's own reader (lit when on)
    onSiteReaderClicked: (() -> Unit)? = null,
    siteReader: Boolean = false,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val chapterListState = rememberLazyListState()
    // MANHWA: this work joined with its continuation on another source
    val fusionUi = eu.kanade.translation.unified.rememberFusionUi(state.manga)
    val fusedBadge = fusionUi.baseBadge()

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    // MANHWA: the page stands on a scene in the cover's colours (or the one picked for this work)
    val pagePalette = eu.kanade.presentation.manga.components.LocalCoverPalette.current
    val sceneContext = LocalContext.current
    val pickedScene by remember(state.manga.id) { eu.kanade.tachiyomi.music.MusicStore.get(sceneContext).sceneFlow(state.manga.id) }.collectFlowAsState(null)
    val pageEffects = eu.kanade.presentation.manga.components.rememberWorkEffects(state.manga.id)
    eu.kanade.presentation.manga.components.PageBackdrop(
        scene = eu.kanade.presentation.manga.components.BackdropScene.of(pickedScene)
            ?: eu.kanade.presentation.manga.components.BackdropScene.forColor(pagePalette?.primary, state.manga.id),
        custom = remember(pickedScene) { eu.kanade.tachiyomi.music.MusicStore.get(sceneContext).sceneFile(pickedScene) },
        effect = pageEffects.page,
        effectColor = pageEffects.color,
        beat = pageEffects.beat,
    ) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            val isFirstItemVisible by remember {
                derivedStateOf { chapterListState.firstVisibleItemIndex == 0 }
            }
            val isFirstItemScrolled by remember {
                derivedStateOf { chapterListState.firstVisibleItemScrollOffset > 0 }
            }
            val titleAlpha by animateFloatAsState(
                if (!isFirstItemVisible) 1f else 0f,
                label = "Top Bar Title",
            )
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "Top Bar Background",
            )
            MangaToolbar(
                title = state.manga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickFilter = onFilterClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                onClickCovers = onCoversClicked,
                onClickSiteReader = onSiteReaderClicked,
                siteReader = siteReader,
                canCancelDownloads = state.chapters.fastAny { it.downloadState == Download.State.QUEUE || it.downloadState == Download.State.DOWNLOADING },
                actionModeCounter = state.selectedChapters.size,
                onCancelActionMode = { onAllChapterSelected(false) },
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        bottomBar = {
            SharedMangaBottomActionMenu(
                selected = state.selectedChapters,
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                fillFraction = 1f,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = state.hasUnreadChapters && !isAnySelected
            SmallExtendedFloatingActionButton(
                text = {
                    Text(
                        text = stringResource(
                            if (state.hasReadChapters) MR.strings.action_resume else MR.strings.action_start,
                        ),
                    )
                },
                icon = { Icon(imageVector = MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = chapterListState.shouldExpandFAB(),
                modifier = Modifier.animateFloatingActionButton(
                    visible = isFABVisible,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()

        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(top = topPadding),
        ) {
            val layoutDirection = LocalLayoutDirection.current
            VerticalFastScroller(
                listState = chapterListState,
                topContentPadding = topPadding,
                endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(),
                    state = chapterListState,
                    contentPadding = PaddingValues(
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                ) {
                    item(
                        key = MangaScreenItem.INFO_BOX,
                        contentType = MangaScreenItem.INFO_BOX,
                    ) {
                        // MANHWA: the head of the page as an editorial poster, in the cover's colours
                        eu.kanade.presentation.manga.components.PosterHeader(
                            manga = state.manga,
                            sourceName = remember(state.source) { state.source.getNameForMangaInfo() },
                            chaptersRead = remember(state.chapters) { state.chapters.count { it.chapter.read } },
                            chapterCount = state.chapters.size,
                            topPadding = topPadding,
                            scrollProvider = {
                                if (chapterListState.firstVisibleItemIndex == 0) {
                                    chapterListState.firstVisibleItemScrollOffset.toFloat()
                                } else {
                                    2000f
                                }
                            },
                            onCoverClick = onCoverClicked,
                            doSearch = onSearch,
                            onTagSearch = onTagSearch,
                            onContinue = onContinueReading.takeIf { state.hasUnreadChapters },
                            continueLabel = stringResource(if (state.hasReadChapters) MR.strings.action_resume else MR.strings.action_start),
                            nextChapterHint = nextChapterHint(state),
                        )
                    }

                    item(
                        key = MangaScreenItem.ACTION_ROW,
                        contentType = MangaScreenItem.ACTION_ROW,
                    ) {
                        MangaActionRow(
                            favorite = state.manga.favorite,
                            trackingCount = state.trackingCount,
                            nextUpdate = nextUpdate,
                            isUserIntervalMode = state.manga.fetchInterval < 0,
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onWebViewClicked = onWebViewClicked,
                            onWebViewLongClicked = onWebViewLongClicked,
                            onTrackingClicked = onTrackingClicked,
                            onEditIntervalClicked = onEditIntervalClicked,
                            onEditCategory = onEditCategoryClicked,
                        )
                    }

                    item(
                        key = MangaScreenItem.DESCRIPTION_WITH_TAG,
                        contentType = MangaScreenItem.DESCRIPTION_WITH_TAG,
                    ) {
                        ExpandableMangaDescription(
                            defaultExpandState = state.isFromSource,
                            description = state.manga.description,
                            mangaId = state.manga.id,
                            title = state.manga.title,
                            tagsProvider = { null },
                            notes = state.manga.notes,
                            onTagSearch = onTagSearch,
                            onCopyTagToClipboard = onCopyTagToClipboard,
                            onEditNotes = onEditNotesClicked,
                        )
                    }

                    // MANHWA: back after two weeks, the story so far
                    item(key = "manhwa_return_recap") {
                        eu.kanade.translation.characters.ReturnRecapCard(state.manga.id)
                    }
                    // MANHWA: the cast of the work, in glass cards
                    item(key = "manhwa_characters") {
                        eu.kanade.translation.characters.CharacterGallery(state.manga.id, state.manga.title)
                    }
                    // MANHWA: the story map (key events and who is who), up to the last chapter read
                    item(key = "manhwa_storymap") {
                        val mapNavigator = cafe.adriel.voyager.navigator.LocalNavigator.current
                        eu.kanade.presentation.components.ManhwaKit.NeonButton(
                            text = stringResource(tachiyomi.i18n.at.ATMR.strings.storymap_open),
                            icon = mihon.icons.materialsymbols.MaterialSymbols.Rounded.Group,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
                        ) {
                            mapNavigator?.push(eu.kanade.translation.companion.StoryMapScreen(state.manga.id, state.manga.title))
                        }
                    }
                    // MANHWA: works like this one, from AniList's recommendations
                    item(key = "manhwa_similar") {
                        eu.kanade.translation.characters.SimilarWorksRow(state.manga.id, state.manga.title)
                    }
                    if (translateBar != null) {
                        item(key = "manhwa_translate") { translateBar() }
                    }
                    item(key = "manhwa_fusion") {
                        eu.kanade.translation.unified.FusionBanner(fusionUi, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                    item(
                        key = MangaScreenItem.CHAPTER_HEADER,
                        contentType = MangaScreenItem.CHAPTER_HEADER,
                    ) {
                        ChapterHeader(
                            enabled = !isAnySelected,
                            chapterCount = chapters.size,
                            missingChapterCount = state.missingChapterCount,
                            onClick = onFilterClicked,
                        )
                    }
                    fusionContinuationItems(fusionUi)

                    sharedChapterItems(
                        manga = state.manga,
                        chapters = listItem,
                        isAnyChapterSelected = state.selectedChapters.isNotEmpty(),
                        chapterSwipeStartAction = chapterSwipeStartAction,
                        chapterSwipeEndAction = chapterSwipeEndAction,
                        onChapterClicked = onChapterClicked,
                        onDownloadChapter = onDownloadChapter,
                        onTranslateChapter = onTranslateChapter,
                        downloadProgressProvider = downloadProgressProvider,
                        translationProgressProvider = translationProgressProvider,
                        onChapterSelected = onChapterSelected,
                        onChapterSwipe = onChapterSwipe,
                        fusedBadge = fusedBadge,
                    )
                }
            }
        }
    }
    // MANHWA music: the work's songs play while its page (or a chapter of it) is open, under a glass music bar
    eu.kanade.tachiyomi.music.WorkMusicEffect(state.manga.id)
    eu.kanade.tachiyomi.music.MusicMiniBar(
        mangaId = state.manga.id,
        title = state.manga.title,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .padding(start = 16.dp, bottom = 92.dp),
    )
    }
}

@Composable
fun MangaScreenLargeImpl(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onTranslateChapter: ((ChapterList.Item, ChapterTranslationAction) -> Unit)? = null, // TachiyomiAT
    translateBar: (@Composable () -> Unit)? = null, // MANHWA: translate chapters from here
    downloadProgressProvider: (chapterId: Long) -> Int,
    translationProgressProvider: (chapterId: Long) -> Translation.Progress? = { null }, // MANHWA
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    // MANHWA: the covers other sources have for this series
    onCoversClicked: (() -> Unit)? = null,
    // MANHWA: this series' source read in the site's own reader (lit when on)
    onSiteReaderClicked: (() -> Unit)? = null,
    siteReader: Boolean = false,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For swipe actions
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    var topBarHeight by remember { mutableIntStateOf(0) }

    val chapterListState = rememberLazyListState()
    // MANHWA: this work joined with its continuation on another source
    val fusionUi = eu.kanade.translation.unified.rememberFusionUi(state.manga)
    val fusedBadge = fusionUi.baseBadge()

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    Scaffold(
        topBar = {
            MangaToolbar(
                modifier = Modifier.onSizeChanged { topBarHeight = it.height },
                title = state.manga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickFilter = onFilterButtonClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                onClickCovers = onCoversClicked,
                onClickSiteReader = onSiteReaderClicked,
                siteReader = siteReader,
                canCancelDownloads = state.chapters.fastAny { it.downloadState == Download.State.QUEUE || it.downloadState == Download.State.DOWNLOADING },
                onCancelActionMode = { onAllChapterSelected(false) },
                actionModeCounter = state.selectedChapters.size,
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        bottomBar = {
            // Aligned by its own modifier: a wrapper would always be laid out, and the scaffold then drops the
            // navigation bar inset under the FAB
            SharedMangaBottomActionMenu(
                selected = state.selectedChapters,
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                fillFraction = 0.5f,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.End),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = state.hasUnreadChapters && !isAnySelected
            SmallExtendedFloatingActionButton(
                text = {
                    Text(
                        text = stringResource(
                            if (state.hasReadChapters) MR.strings.action_resume else MR.strings.action_start,
                        ),
                    )
                },
                icon = { Icon(imageVector = MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = chapterListState.shouldExpandFAB(),
                modifier = Modifier.animateFloatingActionButton(
                    visible = isFABVisible,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(
                start = insetPadding.calculateStartPadding(layoutDirection),
                top = with(density) { topBarHeight.toDp() },
                end = insetPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            TwoPanelBox(
                modifier = Modifier.padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
                startContent = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                    ) {
                        // MANHWA: the header glows in the colour of the cover
                        eu.kanade.presentation.components.AmbientGlow(eu.kanade.presentation.components.rememberCoverColor(state.manga)) {
                            MangaInfoBox(
                                isTabletUi = true,
                                appBarPadding = contentPadding.calculateTopPadding(),
                                manga = state.manga,
                                source = remember { state.source },
                                isStubSource = remember { state.source is StubSource },
                                onCoverClick = onCoverClicked,
                                doSearch = onSearch,
                                chaptersRead = remember(state.chapters) { state.chapters.count { it.chapter.read } },
                            )
                        }
                        MangaActionRow(
                            favorite = state.manga.favorite,
                            trackingCount = state.trackingCount,
                            nextUpdate = nextUpdate,
                            isUserIntervalMode = state.manga.fetchInterval < 0,
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onWebViewClicked = onWebViewClicked,
                            onWebViewLongClicked = onWebViewLongClicked,
                            onTrackingClicked = onTrackingClicked,
                            onEditIntervalClicked = onEditIntervalClicked,
                            onEditCategory = onEditCategoryClicked,
                        )
                        ExpandableMangaDescription(
                            defaultExpandState = true,
                            description = state.manga.description,
                            mangaId = state.manga.id,
                            title = state.manga.title,
                            tagsProvider = { state.manga.genre },
                            notes = state.manga.notes,
                            onTagSearch = onTagSearch,
                            onCopyTagToClipboard = onCopyTagToClipboard,
                            onEditNotes = onEditNotesClicked,
                        )
                        eu.kanade.translation.characters.CharacterGallery(state.manga.id, state.manga.title)
                        eu.kanade.translation.characters.ReturnRecapCard(state.manga.id)
                    }
                },
                endContent = {
                    VerticalFastScroller(
                        listState = chapterListState,
                        topContentPadding = contentPadding.calculateTopPadding(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            state = chapterListState,
                            contentPadding = PaddingValues(
                                top = contentPadding.calculateTopPadding(),
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        ) {
                            if (translateBar != null) {
                                item(key = "manhwa_translate") { translateBar() }
                            }
                            item(key = "manhwa_fusion") {
                                eu.kanade.translation.unified.FusionBanner(fusionUi, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            }
                            item(
                                key = MangaScreenItem.CHAPTER_HEADER,
                                contentType = MangaScreenItem.CHAPTER_HEADER,
                            ) {
                                ChapterHeader(
                                    enabled = !isAnySelected,
                                    chapterCount = chapters.size,
                                    missingChapterCount = state.missingChapterCount,
                                    onClick = onFilterButtonClicked,
                                )
                            }
                            fusionContinuationItems(fusionUi)

                            sharedChapterItems(
                                manga = state.manga,
                                chapters = listItem,
                                isAnyChapterSelected = state.selectedChapters.isNotEmpty(),
                                chapterSwipeStartAction = chapterSwipeStartAction,
                                chapterSwipeEndAction = chapterSwipeEndAction,
                                onChapterClicked = onChapterClicked,
                                onDownloadChapter = onDownloadChapter,
                                onTranslateChapter = onTranslateChapter,
                                downloadProgressProvider = downloadProgressProvider,
                                translationProgressProvider = translationProgressProvider,
                                onChapterSelected = onChapterSelected,
                                onChapterSwipe = onChapterSwipe,
                                fusedBadge = fusedBadge,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SharedMangaBottomActionMenu(
    selected: List<ChapterList.Item>,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAll { it.chapter.bookmark } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAny { it.chapter.read || it.chapter.lastPageRead > 0 } },
        onMarkPreviousAsReadClicked = {
            onMarkPreviousAsReadClicked(selected[0].chapter)
        }.takeIf { selected.size == 1 },
        onDownloadClicked = {
            onDownloadChapter!!(selected.toList(), ChapterDownloadAction.START)
        }.takeIf {
            onDownloadChapter != null && selected.fastAny { it.downloadState != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected.fastMap { it.chapter })
        }.takeIf {
            selected.fastAny { it.downloadState == Download.State.DOWNLOADED }
        },
    )
}

/** MANHWA: "next chapter likely on Thursday" (or how late it is), from when the last chapters came out. */
@Composable
private fun nextChapterHint(state: MangaViewModel.State.Success): String? {
    val rhythm = remember(state.chapters) {
        eu.kanade.tachiyomi.ui.home.schedule.ReleaseRhythm.of(
            state.chapters.mapNotNull { it.chapter.dateUpload?.toEpochMilliseconds() },
            System.currentTimeMillis(),
        )
    } ?: return null
    if (state.manga.status == eu.kanade.tachiyomi.source.model.SManga.COMPLETED.toLong()) return null
    return if (rhythm.lateDays > 0) {
        stringResource(tachiyomi.i18n.at.ATMR.strings.poster_next_late, rhythm.lateDays)
    } else {
        val day = java.time.Instant.ofEpochMilli(rhythm.nextAt).atZone(java.time.ZoneId.systemDefault()).dayOfWeek
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        stringResource(tachiyomi.i18n.at.ATMR.strings.poster_next, day)
    }
}

private fun LazyListScope.sharedChapterItems(
    manga: Manga,
    chapters: List<ChapterList>,
    isAnyChapterSelected: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onTranslateChapter: ((ChapterList.Item, ChapterTranslationAction) -> Unit)? = null, // TachiyomiAT
    downloadProgressProvider: (chapterId: Long) -> Int,
    translationProgressProvider: (chapterId: Long) -> Translation.Progress? = { null }, // MANHWA
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
    fusedBadge: String? = null,
) {
    items(
        items = chapters,
        key = { item ->
            when (item) {
                is ChapterList.MissingCount -> "missing-count-${item.id}"
                is ChapterList.Item -> "chapter-${item.id}"
            }
        },
        contentType = { MangaScreenItem.CHAPTER },
    ) { item ->
        val haptic = LocalHapticFeedback.current

        when (item) {
            is ChapterList.MissingCount -> {
                MissingChapterCountListItem(count = item.count)
            }
            is ChapterList.Item -> {
                // MANHWA no-spoiler mode: an unread chapter is only its number
                val spoilerContext = LocalContext.current
                val noSpoilers by remember { spoilerContext.appGraph.libraryPreferences.noSpoilers }.collectAsState()
                MangaChapterListItem(
                    title = if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER ||
                        (noSpoilers && !item.chapter.read && item.chapter.chapterNumber >= 0)
                    ) {
                        stringResource(
                            MR.strings.display_mode_chapter,
                            formatChapterNumber(item.chapter.chapterNumber),
                        )
                    } else {
                        item.chapter.name
                    },
                    date = relativeDateText(item.chapter.dateUpload),
                    readProgress = item.chapter.lastPageRead
                        .takeIf { !item.chapter.read && it > 0L }
                        ?.let {
                            stringResource(
                                MR.strings.chapter_progress,
                                it + 1,
                            )
                        },
                    scanlator = item.chapter.scanlator.takeIf { !it.isNullOrBlank() },
                    read = item.chapter.read,
                    bookmark = item.chapter.bookmark,
                    selected = item.selected,
                    downloadIndicatorEnabled = !isAnyChapterSelected && !manga.isLocal(),
                    downloadStateProvider = { item.downloadState },
                    downloadProgressProvider = { downloadProgressProvider(item.id) },
                    downloadErrorProvider = { DownloadErrorLog.get(manga.id, item.chapter.name) },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {
                        onChapterSelected(item, !item.selected, true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onClick = {
                        onChapterItemClick(
                            chapterItem = item,
                            isAnyChapterSelected = isAnyChapterSelected,
                            onToggleSelection = { onChapterSelected(item, !item.selected, false) },
                            onChapterClicked = onChapterClicked,
                        )
                    },
                    onDownloadClick = if (onDownloadChapter != null) {
                        { onDownloadChapter(listOf(item), it) }
                    } else {
                        null
                    },
                    translationStateProvider = { item.translationState },
                    translationProgressProvider = { translationProgressProvider(item.id) },
                    onTranslationClick = onTranslateChapter?.let { translate -> { translate(item, it) } },
                    onChapterSwipe = {
                        onChapterSwipe(item, it)
                    },
                    badge = fusedBadge,
                )
            }
        }
    }
}

private fun onChapterItemClick(
    chapterItem: ChapterList.Item,
    isAnyChapterSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onChapterClicked: (Chapter) -> Unit,
) {
    when {
        chapterItem.selected -> onToggleSelection(false)
        isAnyChapterSelected -> onToggleSelection(true)
        else -> onChapterClicked(chapterItem.chapter)
    }
}
