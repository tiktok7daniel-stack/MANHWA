package eu.kanade.tachiyomi.ui.nova

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.BookmarkAdd
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.MenuBook
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.SwapCalls
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import mihon.icons.materialsymbols.roundedfilled.Circle
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA Nova: the series page of the second system. The cover in the middle over its own blurred light, the
 * title, chapters / type / status, a big "chapters" button with the library, categories and continue buttons
 * beside it, a card of numbers, the story, the four newest chapters, and the full list of chapters (search by
 * number, newest or oldest first) on its own page. The app's dialogs (categories, removing from the library)
 * are still drawn by the series screen around it.
 */
@Composable
fun NovaMangaDetails(
    state: MangaViewModel.State.Success,
    onBack: () -> Unit,
    onRead: (Chapter) -> Unit,
    onContinue: () -> Unit,
    onFavorite: () -> Unit,
    onCategories: (() -> Unit)?,
    onWebView: (() -> Unit)?,
    onRefresh: () -> Unit,
    onDownload: ((ChapterList.Item) -> Unit)?,
    onMarkRead: (Chapter, Boolean) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    NovaTheme {
        BackHandler(enabled = showAll) { showAll = false }
        // The chapters slide in over the series from the reading side, and back out the same way
        androidx.compose.animation.AnimatedContent(
            targetState = showAll,
            transitionSpec = {
                val dir = if (targetState) 1 else -1
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(320)) { it * dir / 3 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(320)) { -it * dir / 3 } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
            },
            label = "novaChapters",
        ) { all ->
            if (all) {
                NovaChapters(state, onBack = { showAll = false }, onRead = onRead, onDownload = onDownload, onMarkRead = onMarkRead)
            } else {
                NovaDetails(
                    state, onBack, onRead, onContinue, onFavorite, onCategories, onWebView, onRefresh, onDownload, onMarkRead,
                    onAll = { showAll = true },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NovaDetails(
    state: MangaViewModel.State.Success,
    onBack: () -> Unit,
    onRead: (Chapter) -> Unit,
    onContinue: () -> Unit,
    onFavorite: () -> Unit,
    onCategories: (() -> Unit)?,
    onWebView: (() -> Unit)?,
    onRefresh: () -> Unit,
    onDownload: ((ChapterList.Item) -> Unit)?,
    onMarkRead: (Chapter, Boolean) -> Unit,
    onAll: () -> Unit,
) {
    val manga = state.manga
    val chapters = state.processedChapters
    val newest = remember(chapters) { chapters.sortedByDescending { it.chapter.chapterNumber }.take(4) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val status = when (manga.status.toInt()) {
        SManga.ONGOING -> stringResource(MR.strings.ongoing)
        SManga.COMPLETED -> stringResource(MR.strings.completed)
        else -> stringResource(MR.strings.unknown)
    }

    LazyColumn(Modifier.fillMaxSize().background(NovaColors.Background), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") {
            Box(Modifier.fillMaxWidth()) {
                // The cover's own light behind the top of the page
                AsyncImage(
                    model = manga,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(380.dp).blur(28.dp).alpha(0.45f),
                )
                Box(
                    Modifier.fillMaxWidth().height(380.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, NovaColors.Background))),
                )
                Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(MaterialSymbols.AutoMirroredRounded.ArrowBack, onBack)
                        Spacer(Modifier.weight(1f))
                        Box {
                            RoundButton(MaterialSymbols.Rounded.MoreVert) { menu = true }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(MR.strings.action_webview_refresh)) }, onClick = { menu = false; onRefresh() })
                                if (onWebView != null) {
                                    DropdownMenuItem(text = { Text(stringResource(MR.strings.action_web_view)) }, onClick = { menu = false; onWebView() })
                                }
                            }
                        }
                    }
                    AsyncImage(
                        model = manga,
                        contentDescription = manga.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .novaAppear()
                            .width(190.dp)
                            .height(272.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
                    )
                    Text(
                        manga.title,
                        color = NovaColors.Ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
                    )
                    Text(
                        listOf(stringResource(ATMR.strings.nova_chapters_n, chapters.size), state.source.name, status).joinToString("  •  "),
                        color = NovaColors.Muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item(key = "actions") {
            Row(Modifier.novaAppear(1).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .novaClick(onAll)
                        .height(60.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(NovaColors.Blue),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MaterialSymbols.Rounded.MenuBook, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(ATMR.strings.nova_view_chapters), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                SquareButton(MaterialSymbols.RoundedFilled.PlayArrow, active = false, onClick = onContinue)
                if (onCategories != null) SquareButton(MaterialSymbols.Rounded.BookmarkAdd, active = false, onClick = onCategories)
                SquareButton(MaterialSymbols.Rounded.Favorite, active = manga.favorite, onClick = onFavorite)
            }
        }
        item(key = "stats") {
            Row(
                Modifier
                    .novaAppear(2)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(NovaColors.Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stat(chapters.size, stringResource(ATMR.strings.nova_stat_chapters))
                VerticalDivider(Modifier.height(56.dp), color = Color.White.copy(alpha = 0.08f))
                Stat(chapters.count { it.chapter.read }, stringResource(ATMR.strings.nova_stat_read))
                VerticalDivider(Modifier.height(56.dp), color = Color.White.copy(alpha = 0.08f))
                Stat(chapters.count { it.isDownloaded }, stringResource(ATMR.strings.nova_stat_downloaded))
            }
        }
        manga.description?.takeIf { it.isNotBlank() }?.let { story ->
            item(key = "story") {
                Column(Modifier.novaAppear(3).animateContentSize().padding(horizontal = 18.dp, vertical = 20.dp)) {
                    Text(stringResource(ATMR.strings.nova_story), color = NovaColors.Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        story,
                        color = NovaColors.Muted,
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        stringResource(if (expanded) ATMR.strings.nova_less else ATMR.strings.nova_more),
                        color = NovaColors.Blue,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp).clickable { expanded = !expanded },
                    )
                    manga.genre?.takeIf { it.isNotEmpty() }?.let { genres ->
                        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            genres.forEach { g ->
                                Text(
                                    g,
                                    color = NovaColors.Ink,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(NovaColors.Raised).padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "newestTitle") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(ATMR.strings.nova_newest), color = NovaColors.Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                Text(stringResource(ATMR.strings.nova_view_all), color = NovaColors.Blue, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onAll))
            }
        }
        items(newest, key = { "n${it.id}" }) { item ->
            ChapterRow(item, onRead, onDownload, onMarkRead)
        }
    }
}

/** All the chapters: search by number, newest or oldest first. */
@Composable
private fun NovaChapters(
    state: MangaViewModel.State.Success,
    onBack: () -> Unit,
    onRead: (Chapter) -> Unit,
    onDownload: ((ChapterList.Item) -> Unit)?,
    onMarkRead: (Chapter, Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    val all = state.processedChapters
    val shown = remember(all, query, newestFirst) {
        all.filter { query.isBlank() || it.chapter.chapterNumber.toString().removeSuffix(".0").contains(query.trim()) || it.chapter.name.contains(query.trim(), true) }
            .let { list -> if (newestFirst) list.sortedByDescending { it.chapter.chapterNumber } else list.sortedBy { it.chapter.chapterNumber } }
    }
    Column(Modifier.fillMaxSize().background(NovaColors.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(MaterialSymbols.AutoMirroredRounded.ArrowBack, onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(ATMR.strings.nova_chapters), color = NovaColors.Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(state.manga.title, color = NovaColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(18.dp)).background(NovaColors.Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MaterialSymbols.Rounded.Search, null, tint = NovaColors.Muted)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(ATMR.strings.nova_search_chapter), color = NovaColors.Muted)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = NovaColors.Ink, fontSize = 16.sp),
                        cursorBrush = SolidColor(NovaColors.Blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                Modifier.height(56.dp).clip(RoundedCornerShape(18.dp)).background(NovaColors.Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
                    .clickable { newestFirst = !newestFirst }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MaterialSymbols.Rounded.SwapCalls, null, tint = NovaColors.Blue)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (newestFirst) ATMR.strings.nova_newest_first else ATMR.strings.nova_oldest_first), color = NovaColors.Ink)
            }
        }
        Text(
            stringResource(ATMR.strings.nova_chapters_n, shown.size),
            color = NovaColors.Muted,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.id }) { item -> ChapterRow(item, onRead, onDownload, onMarkRead) }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

/** A chapter: its number in a tile, its name, download, and the read circle (tap to switch). */
@Composable
private fun ChapterRow(
    item: ChapterList.Item,
    onRead: (Chapter) -> Unit,
    onDownload: ((ChapterList.Item) -> Unit)?,
    onMarkRead: (Chapter, Boolean) -> Unit,
) {
    val chapter = item.chapter
    val number = chapter.chapterNumber.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onRead(chapter) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(NovaColors.Raised),
            contentAlignment = Alignment.Center,
        ) { Text(number, color = NovaColors.Muted, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        Spacer(Modifier.width(14.dp))
        Text(
            chapter.name,
            color = if (chapter.read) NovaColors.Muted else NovaColors.Ink,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onDownload != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onDownload(item) }, contentAlignment = Alignment.Center) {
                Icon(
                    if (item.downloadState == Download.State.DOWNLOADED) MaterialSymbols.Rounded.Check else MaterialSymbols.Rounded.Download,
                    null,
                    tint = NovaColors.Blue,
                )
            }
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { onMarkRead(chapter, !chapter.read) }, contentAlignment = Alignment.Center) {
            Icon(
                if (chapter.read) MaterialSymbols.RoundedFilled.CheckCircle else MaterialSymbols.RoundedFilled.Circle,
                null,
                tint = if (chapter.read) NovaColors.Blue else NovaColors.Blue.copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(value: Int, label: String) {
    // The number counts up to its value when the page opens, and runs to a new one when it changes
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(value) {
        shown.animateTo(value.toFloat(), androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${shown.value.toInt()}", color = NovaColors.Ink, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Text(label, color = NovaColors.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.novaClick(onClick).size(48.dp).clip(CircleShape).background(NovaColors.Surface.copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = NovaColors.Ink) }
}

@Composable
private fun SquareButton(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val fill by androidx.compose.animation.animateColorAsState(if (active) NovaColors.Blue.copy(alpha = 0.2f) else NovaColors.Surface, label = "novaFill")
    val edge by androidx.compose.animation.animateColorAsState(if (active) NovaColors.Blue else Color.White.copy(alpha = 0.08f), label = "novaEdge")
    // Turning on pops the icon past its size and settles it back
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    var was by remember { mutableStateOf(active) }
    androidx.compose.runtime.LaunchedEffect(active) {
        if (active && !was) {
            pop.animateTo(1.3f, androidx.compose.animation.core.tween(120))
            pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 400f))
        }
        was = active
    }
    Box(
        Modifier.novaClick(onClick).size(60.dp).clip(RoundedCornerShape(20.dp))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            null,
            tint = if (active) NovaColors.Blue else NovaColors.Ink,
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            },
        )
    }
}
