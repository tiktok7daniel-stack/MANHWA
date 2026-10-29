package eu.kanade.tachiyomi.novel.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.novel.fetchChapter
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.live.PageOcr
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import ireader.core.source.CatalogSource
import ireader.core.source.model.ChapterInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import kotlin.math.max

/**
 * MANHWA novel bridge: finds where in the novel a manhwa chapter is told, from what both say ([ChapterMatcher]),
 * so "continue the novel from here" is true even when the chapter was never translated. The manhwa's words come
 * from its translation file when there is one, or are read from a few of its pages on the phone (no AI, nothing
 * sent anywhere); the novel's from its chapters, kept once read.
 */
object NovelAligner {

    sealed interface Result {
        data class Found(val place: NovelBridge.Place) : Result

        /** Read, but no chapter is a clear match: nothing is claimed. */
        data object Unsure : Result

        /** The manhwa chapter has too few words to tell (pictures only, or its pages could not be read). */
        data object NoText : Result

        data class Failed(val reason: String) : Result
    }

    enum class Step { READING_MANHWA, READING_NOVEL, COMPARING }

    /** Pages read from a chapter without a translation, spread over it. */
    private const val OCR_PAGES = 6

    /** Words enough to compare; reading stops early once there are this many telling words. */
    private const val ENOUGH_WORDS = 40

    private const val PARALLEL_FETCH = 3

    suspend fun locate(
        context: Context,
        manga: Manga,
        chapter: Chapter,
        link: NovelBridge.Link,
        novelChapters: List<ChapterInfo>,
        onStep: (Step) -> Unit = {},
    ): Result {
        if (novelChapters.isEmpty()) return Result.Failed("no chapters")
        val graph = context.appGraph
        val novelSource = graph.novelExtensionManager.source(link.sourceId) ?: return Result.Failed("source")
        onStep(Step.READING_MANHWA)
        val text = try {
            manhwaText(context, manga, chapter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Manhwa text for ${chapter.name}" }
            ""
        }
        if (ChapterMatcher.words(text).size < ChapterMatcher.MIN_WORDS) return Result.NoText

        val texts = HashMap<Int, String>()
        suspend fun read(indices: List<Int>): List<String> {
            val missing = indices.filter { it !in texts }
            onStep(Step.READING_NOVEL)
            val got = coroutineScope {
                val gate = Semaphore(PARALLEL_FETCH)
                missing.map { i -> async { gate.withPermit { i to novelText(context, link, novelSource, novelChapters[i]) } } }.awaitAll()
            }
            texts.putAll(got)
            onStep(Step.COMPARING)
            return indices.map { texts[it].orEmpty() }
        }

        suspend fun tryWindow(indices: List<Int>): NovelBridge.Place? {
            val chapterTexts = read(indices)
            if (chapterTexts.count { it.isBlank() } * 2 > chapterTexts.size) return null
            val m = ChapterMatcher.best(text, chapterTexts)?.takeIf { it.confident } ?: return null
            val i = indices[m.index]
            return NovelBridge.Place(
                manhwaNumber = chapter.chapterNumber,
                novelIndex = i,
                novelUrl = novelChapters[i].key,
                novelName = novelChapters[i].name,
                share = m.share,
                at = System.currentTimeMillis(),
            )
        }

        val count = novelChapters.size
        val known = NovelBridge.places(context, manga.id).values.map { it.manhwaNumber to it.novelIndex }
        AlignPlan.predict(chapter.chapterNumber, known)?.let { guess ->
            tryWindow(AlignPlan.window(guess, count))?.let { return found(context, manga, chapter, it) }
        }
        val spread = AlignPlan.coarse(chapter.chapterNumber, count)
        val spreadTexts = read(spread)
        if (spreadTexts.count { it.isBlank() } * 2 > spreadTexts.size) return Result.Failed("novel")
        val rough = ChapterMatcher.best(text, spreadTexts) ?: return Result.Unsure
        val radius = max(AlignPlan.gap(spread), 3)
        tryWindow(AlignPlan.window(spread[rough.index], count, radius))?.let { return found(context, manga, chapter, it) }
        return Result.Unsure
    }

    private suspend fun found(context: Context, manga: Manga, chapter: Chapter, place: NovelBridge.Place): Result {
        NovelBridge.savePlace(context, manga.id, chapter.id, place)
        return Result.Found(place)
    }

    /** A novel chapter's text: kept from an earlier read, or fetched once and kept. "" when it cannot be read. */
    private suspend fun novelText(context: Context, link: NovelBridge.Link, source: CatalogSource, chapter: ChapterInfo): String {
        val store = context.appGraph.novelStore
        store.cachedText(link.novelKey, chapter.key)?.let { return it }
        return try {
            withContext(Dispatchers.IO) { fetchChapter(source, chapter) }.also {
                if (it.isNotBlank()) store.cacheText(link.novelKey, chapter.key, it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN) { "Novel chapter ${chapter.name} not read: ${e.message}" }
            ""
        }
    }

    // ---------------------------------------------------------------- The manhwa chapter's words

    /** The chapter's original words: from its translation file, or read from a few of its pages. */
    private suspend fun manhwaText(context: Context, manga: Manga, chapter: Chapter): String {
        val graph = context.appGraph
        val source = graph.sourceManager.get(manga.source) ?: return ""
        val saved = withContext(Dispatchers.IO) {
            graph.translationManager.getChapterTranslation(chapter.name, chapter.scanlator, manga.title, source)
        }
        val fromFile = saved.values.flatMap { it.blocks }.filter { it.kind != "sfx" }.joinToString(" ") { it.text }
        if (ChapterMatcher.words(fromFile).size >= ChapterMatcher.MIN_WORDS) return fromFile
        return ocr(context, manga, chapter, source)
    }

    private suspend fun ocr(context: Context, manga: Manga, chapter: Chapter, source: Source): String = withContext(Dispatchers.IO) {
        val graph = context.appGraph
        // Downloaded pages first (no network), else the source's pages
        val local = try {
            graph.downloadManager.buildPageList(source, manga, chapter)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val pages: List<Page> = local.ifEmpty { source.getPageList(chapter.toSChapter()) }
        if (pages.isEmpty()) return@withContext ""
        val picked = spreadPages(pages.size)
        val out = StringBuilder()
        TextRecognizer(TextRecognizerLanguage.ENGLISH).use { recognizer ->
            for (i in picked) {
                val bitmap = runCatching { pageBitmap(context, source, pages[i]) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull() ?: continue
                try {
                    val page = PageOcr.recognize(recognizer, bitmap)
                    page.blocks.filter { it.kind != "sfx" }.forEach { out.append(it.text).append(' ') }
                } finally {
                    bitmap.recycle()
                }
                if (ChapterMatcher.words(out.toString()).size >= ENOUGH_WORDS) break
            }
        }
        out.toString()
    }

    /** [OCR_PAGES] pages spread over a chapter of [count], the first (often a cover) and last (credits) left out. */
    internal fun spreadPages(count: Int): List<Int> {
        if (count <= OCR_PAGES) return (0 until count).toList()
        val first = 1
        val last = count - 2
        return (0 until OCR_PAGES).map { first + (last - first) * it / (OCR_PAGES - 1) }.distinct()
    }

    private suspend fun pageBitmap(context: Context, source: Source, page: Page): Bitmap? {
        val bytes = page.uri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: run {
            val http = source as? HttpSource ?: return null
            if (page.imageUrl.isNullOrBlank()) page.imageUrl = http.getImageUrl(page)
            http.getImage(page).use { it.body.bytes() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Wide pages are read at a width the OCR handles well; tall webtoon strips keep their width
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private const val MAX_WIDTH = 1100
}
