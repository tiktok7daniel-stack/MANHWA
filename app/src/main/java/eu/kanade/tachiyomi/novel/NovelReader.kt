package eu.kanade.tachiyomi.novel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.fleeksoft.ksoup.Ksoup
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.util.Screen
import ireader.core.source.HttpSource
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.Page
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Text as TextPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.i18n.at.ATMR
import tachiyomi.core.common.i18n.stringResource as ctxString
import tachiyomi.presentation.core.i18n.stringResource

/** One paragraph of a chapter, or one picture in it. */
private sealed interface Block {
    data class Para(val text: String) : Block
    data class Picture(val url: String) : Block
}

/**
 * MANHWA novels: the reader. The chapter's text alone on a calm page (paper, sepia, dark or black), set as the
 * reader likes it (size, spacing, margins, font, justified), right to left when the text is Arabic. A tap shows the
 * bars: back, the chapter, settings, translation, and the chapters before and after. Where the reader is gets saved
 * as they go, and the chapter counts as read near its end.
 */
class NovelReaderScreen(
    private val sourceId: Long,
    private val novelUrl: String,
    private val chapterUrl: String,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = remember { context.appGraph }
        val store = graph.novelStore
        val source = remember { graph.novelExtensionManager.source(sourceId) }
        val scope = rememberCoroutineScope()
        val novelKey = NovelStore.key(sourceId, novelUrl)
        val novel = remember { store.entry(sourceId, novelUrl) }
        val settings by store.settings.collectAsState()

        var current by rememberSaveable { mutableStateOf(chapterUrl) }
        var chapters by remember { mutableStateOf(NovelSession.chapters[novelKey].orEmpty()) }
        val chapter = chapters.firstOrNull { it.key == current } ?: ChapterInfo(key = current, name = novel?.title.orEmpty())
        val index = chapters.indexOfFirst { it.key == current }

        var attempt by remember { mutableStateOf(0) }
        var blocks by remember(current) { mutableStateOf<List<Block>?>(null) }
        var failed by remember(current) { mutableStateOf<String?>(null) }
        var translated by remember(current) { mutableStateOf(store.translation(novelKey, current)?.let(::splitParagraphs)) }
        var showTranslation by rememberSaveable(current) { mutableStateOf(translated != null) }
        var translating by remember(current) { mutableStateOf<Pair<Int, Int>?>(null) }
        var bars by remember { mutableStateOf(true) }
        var sheet by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()

        // The chapter list, when the reader was opened without the novel's page (from the library or a shell)
        LaunchedEffect(Unit) {
            if (chapters.isEmpty() && source != null) {
                runCatching {
                    withContext(Dispatchers.IO) { source.getChapterList(novel?.toInfo() ?: MangaInfo(key = novelUrl, title = ""), emptyList()) }
                }.getOrNull()?.filter { it.key.isNotBlank() }?.let {
                    chapters = it
                    NovelSession.chapters[novelKey] = it
                }
            }
        }

        // The chapter's text: kept from an earlier read, or asked of the source
        LaunchedEffect(current, attempt) {
            val cached = store.cachedText(novelKey, current)
            if (cached != null) {
                blocks = parseBlocks(cached)
            } else {
                val s = source
                if (s == null) {
                    failed = context.ctxString(ATMR.strings.novel_source_missing)
                    return@LaunchedEffect
                }
                try {
                    val text = withContext(Dispatchers.IO) { fetchChapter(s, chapter) }
                    if (text.isBlank()) error("Empty chapter")
                    store.cacheText(novelKey, current, text)
                    blocks = parseBlocks(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed = e.message ?: e.javaClass.simpleName
                }
            }
            // Back to where this chapter was left
            val saved = store.progressOf(novelKey, current)
            val count = blocks?.size ?: 0
            if (saved != null && !saved.read && count > 0) {
                listState.scrollToItem((saved.fraction * count).toInt().coerceIn(0, count - 1))
            } else {
                listState.scrollToItem(0)
            }
        }

        // Where the reader is, saved a moment after they stop scrolling
        val name by rememberUpdatedState(chapter.name)
        LaunchedEffect(current, blocks) {
            val count = blocks?.size ?: return@LaunchedEffect
            if (count == 0) return@LaunchedEffect
            snapshotFlow {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= info.totalItemsCount - 1) 1f else listState.firstVisibleItemIndex.toFloat() / count
            }.debounce(800).collect { fraction -> store.saveProgress(novelKey, current, name, fraction) }
        }

        val paragraphs = blocks.orEmpty()
        val shown: List<Block> = if (showTranslation && translated != null) {
            var i = 0
            paragraphs.map { b -> if (b is Block.Para) Block.Para(translated!!.getOrElse(i++) { b.text }) else b }
        } else {
            paragraphs
        }
        val original = remember(paragraphs) { paragraphs.filterIsInstance<Block.Para>().map { it.text } }
        val rtl = remember(shown) { looksArabic(shown) }
        val colors = readerColors(settings.theme)

        fun go(to: ChapterInfo?) {
            to ?: return
            current = to.key
        }

        fun translate() {
            if (translating != null || original.isEmpty()) return
            scope.launch {
                translating = 0 to original.size
                try {
                    val result = NovelTranslator.translate(context, novelKey, novel?.title.orEmpty(), original) { done, total ->
                        translating = done to total
                    }
                    store.saveTranslation(novelKey, current, result.joinToString("\n\n"))
                    translated = result
                    showTranslation = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed = e.message ?: e.javaClass.simpleName
                } finally {
                    translating = null
                }
            }
        }

        Box(Modifier.fillMaxSize().background(colors.background)) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                when {
                    blocks == null && failed == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                    blocks == null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(ATMR.strings.novel_chapter_failed), color = colors.text, textAlign = TextAlign.Center)
                            Text(failed.orEmpty(), color = colors.muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            ManhwaKit.GhostButton(stringResource(ATMR.strings.novel_retry)) {
                                failed = null
                                attempt++
                            }
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { bars = !bars } },
                        contentPadding = PaddingValues(horizontal = settings.margin.dp, vertical = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item(key = "title") {
                            Text(
                                chapter.name,
                                color = colors.text,
                                fontSize = (settings.fontSize + 6).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = fontOf(settings.font),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(bottom = 28.dp),
                            )
                        }
                        itemsIndexed(shown, key = { i, _ -> "$current#$i" }) { i, block ->
                            when (block) {
                                is Block.Para -> Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(bottom = (settings.fontSize * 0.9f).dp)) {
                                    if (settings.sideBySide && showTranslation && translated != null) {
                                        val src = (paragraphs.getOrNull(i) as? Block.Para)?.text
                                        if (src != null) {
                                            CompositionLocalProvider(LocalLayoutDirection provides if (looksArabic(listOf(Block.Para(src)))) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                                Text(src, color = colors.muted, fontSize = (settings.fontSize - 3).sp, lineHeight = ((settings.fontSize - 3) * settings.lineHeight).sp, fontFamily = fontOf(settings.font), modifier = Modifier.padding(bottom = 6.dp))
                                            }
                                        }
                                    }
                                    Text(
                                        block.text,
                                        color = colors.text,
                                        fontSize = settings.fontSize.sp,
                                        lineHeight = (settings.fontSize * settings.lineHeight).sp,
                                        fontFamily = fontOf(settings.font),
                                        textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
                                    )
                                }
                                is Block.Picture -> AsyncImage(
                                    model = block.url,
                                    contentDescription = null,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(12.dp)),
                                )
                            }
                        }
                        item(key = "end") {
                            val next = chapters.getOrNull(index + 1)
                            Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(ATMR.strings.novel_end_of, chapter.name), color = colors.muted, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                                if (next != null) {
                                    Text(next.name, color = colors.text, fontWeight = FontWeight.Bold, fontSize = (settings.fontSize + 2).sp, textAlign = TextAlign.Center)
                                    ManhwaKit.NeonButton(stringResource(ATMR.strings.novel_next)) { go(next) }
                                }
                            }
                        }
                    }
                }
            }

            // Top bar: back and the chapter
            AnimatedVisibility(
                visible = bars,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    Modifier.fillMaxWidth().background(colors.bar).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ManhwaKit.IconOrb(MaterialSymbols.AutoMirroredRounded.ArrowBack) { navigator.pop() }
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(novel?.title.orEmpty(), color = colors.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(chapter.name, color = colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Bottom bar: the chapters around this one, settings and translation
            AnimatedVisibility(
                visible = bars,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(Modifier.fillMaxWidth().background(colors.bar).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    translating?.let { (done, total) ->
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            color = colors.accent,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(50)),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        ManhwaKit.IconOrb(MaterialSymbols.AutoMirroredRounded.ArrowBack) { go(chapters.getOrNull(index - 1)) }
                        ManhwaKit.IconOrb(MaterialSymbols.Rounded.Settings) { sheet = true }
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (showTranslation) colors.accent else colors.accent.copy(alpha = 0.18f))
                                .clickable {
                                    when {
                                        translating != null -> Unit
                                        translated == null -> translate()
                                        else -> showTranslation = !showTranslation
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (translating != null) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.text)
                            } else {
                                Icon(MaterialSymbols.Rounded.Translate, null, tint = if (showTranslation) Color.White else colors.accent, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(
                                    when {
                                        translating != null -> ATMR.strings.novel_translating
                                        translated == null -> ATMR.strings.novel_translate
                                        showTranslation -> ATMR.strings.novel_show_original
                                        else -> ATMR.strings.novel_show_translation
                                    },
                                ),
                                color = if (showTranslation) Color.White else colors.text,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        ManhwaKit.IconOrb(MaterialSymbols.AutoMirroredRounded.ArrowForward) { go(chapters.getOrNull(index + 1)) }
                    }
                }
            }
        }

        if (sheet) {
            ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                ReaderSettings(settings) { change -> store.updateSettings(change) }
            }
        }

    }
}

@Composable
private fun ReaderSettings(settings: NovelReaderSettings, onChange: ((NovelReaderSettings) -> NovelReaderSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(ATMR.strings.novel_settings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                NovelReaderSettings.THEME_PAPER to ATMR.strings.novel_theme_paper,
                NovelReaderSettings.THEME_SEPIA to ATMR.strings.novel_theme_sepia,
                NovelReaderSettings.THEME_DARK to ATMR.strings.novel_theme_dark,
                NovelReaderSettings.THEME_AMOLED to ATMR.strings.novel_theme_black,
            ).forEach { (theme, label) ->
                val c = readerColors(theme)
                Box(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.background)
                        .clickable { onChange { it.copy(theme = theme) } }
                        .then(if (settings.theme == theme) Modifier.background(c.accent.copy(alpha = 0.18f)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), color = c.text, fontWeight = if (settings.theme == theme) FontWeight.Black else FontWeight.Normal, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        // The sliders move freely and the setting is saved when they are let go
        var size by remember(settings.fontSize) { mutableStateOf(settings.fontSize.toFloat()) }
        var height by remember(settings.lineHeight) { mutableStateOf(settings.lineHeight) }
        var margin by remember(settings.margin) { mutableStateOf(settings.margin.toFloat()) }
        Labeled(stringResource(ATMR.strings.novel_font_size, size.toInt())) {
            Slider(value = size, onValueChange = { size = it }, onValueChangeFinished = { onChange { it.copy(fontSize = size.toInt()) } }, valueRange = 13f..32f)
        }
        Labeled(stringResource(ATMR.strings.novel_line_height, "%.2f".format(height))) {
            Slider(value = height, onValueChange = { height = (it * 20).toInt() / 20f }, onValueChangeFinished = { onChange { it.copy(lineHeight = height) } }, valueRange = 1.2f..2.4f)
        }
        Labeled(stringResource(ATMR.strings.novel_margins, margin.toInt())) {
            Slider(value = margin, onValueChange = { margin = it }, onValueChangeFinished = { onChange { it.copy(margin = margin.toInt()) } }, valueRange = 8f..48f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManhwaKit.Chip(stringResource(ATMR.strings.novel_font_sans), selected = settings.font == NovelReaderSettings.FONT_SANS) { onChange { it.copy(font = NovelReaderSettings.FONT_SANS) } }
            ManhwaKit.Chip(stringResource(ATMR.strings.novel_font_serif), selected = settings.font == NovelReaderSettings.FONT_SERIF) { onChange { it.copy(font = NovelReaderSettings.FONT_SERIF) } }
        }
        SwitchRow(stringResource(ATMR.strings.novel_justify), settings.justify) { v -> onChange { it.copy(justify = v) } }
        SwitchRow(stringResource(ATMR.strings.novel_side_by_side), settings.sideBySide) { v -> onChange { it.copy(sideBySide = v) } }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private data class ReaderColors(val background: Color, val text: Color, val muted: Color, val accent: Color, val bar: Color)

private fun readerColors(theme: Int): ReaderColors = when (theme) {
    NovelReaderSettings.THEME_PAPER -> ReaderColors(Color(0xFFF7F3EA), Color(0xFF1F1D1A), Color(0xFF6B655C), Color(0xFF8C5A2B), Color(0xF2F7F3EA))
    NovelReaderSettings.THEME_SEPIA -> ReaderColors(Color(0xFFF1E3C6), Color(0xFF3B2F22), Color(0xFF7A6650), Color(0xFF9A5B22), Color(0xF2F1E3C6))
    NovelReaderSettings.THEME_AMOLED -> ReaderColors(Color.Black, Color(0xFFD9D9D9), Color(0xFF8A8A8A), Color(0xFF7C5CFF), Color(0xF2000000))
    else -> ReaderColors(Color(0xFF121318), Color(0xFFE6E3DD), Color(0xFF9A98A6), Color(0xFF7C5CFF), Color(0xF2121318))
}

private fun fontOf(font: Int): FontFamily = if (font == NovelReaderSettings.FONT_SERIF) FontFamily.Serif else FontFamily.SansSerif

/** Whether most of the letters are Arabic, so the page reads right to left. */
private fun looksArabic(blocks: List<Block>): Boolean {
    val sample = blocks.asSequence().filterIsInstance<Block.Para>().take(6).joinToString(" ") { it.text }
    val letters = sample.count { it.isLetter() }
    if (letters == 0) return false
    val arabic = sample.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }
    return arabic * 2 > letters
}

private const val PICTURE = "\u0000img:"

/** The chapter as the source gives it, as text: paragraphs apart by an empty line, pictures marked on a line of their own. */
private suspend fun fetchChapter(source: ireader.core.source.CatalogSource, chapter: ChapterInfo): String {
    val pages: List<Page> = source.getPageList(chapter, emptyList())
    val parts = mutableListOf<String>()
    for (page in pages) {
        val complete = if (page is PageUrl && source is HttpSource) runCatching { source.getPage(page) }.getOrNull() else page
        when (complete) {
            is TextPage -> parts += htmlToParagraphs(complete.text)
            is ImageUrl -> parts += PICTURE + complete.url
            else -> Unit
        }
    }
    return parts.joinToString("\n\n")
}

/** Paragraphs of a chapter's text, which some sources send as HTML. */
private fun htmlToParagraphs(text: String): List<String> {
    if (!text.contains('<')) return splitParagraphs(text)
    return runCatching {
        val doc = Ksoup.parse(text)
        doc.select("p, h1, h2, h3, h4, li, blockquote").map { it.text().trim() }.filter { it.isNotEmpty() }
            .ifEmpty { splitParagraphs(doc.text()) }
    }.getOrElse { splitParagraphs(text) }
}

private fun splitParagraphs(text: String): List<String> =
    text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun parseBlocks(text: String): List<Block> =
    text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
        if (part.startsWith(PICTURE)) Block.Picture(part.removePrefix(PICTURE)) else Block.Para(part)
    }
