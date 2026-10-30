package eu.kanade.tachiyomi.novel.bridge

import android.content.Context
import eu.kanade.tachiyomi.novel.NovelSession
import eu.kanade.tachiyomi.novel.fetchChapter
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.MangaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * MANHWA "the other face": the novel side of the manhwa chapter being read. The chapter of the novel that tells
 * the same scene is found from what both say ([NovelAligner], kept once found); while it cannot be told apart for
 * sure, the pace of the chapters already matched gives where it most likely is, marked as such. Its text comes
 * in Arabic when it was translated before, else as the source has it, and can be translated there and then.
 */
object OtherFace {

    sealed interface State {
        /** The manhwa has no novel linked yet (its page's "Novel" side finds one). */
        data object NoNovel : State

        data class Locating(val step: NovelAligner.Step?) : State

        data class Ready(
            val link: NovelBridge.Link,
            val chapters: List<ChapterInfo>,
            val index: Int,
            /** Found from the words both share (else estimated from the pace of the chapters matched before). */
            val matched: Boolean,
            /** How much of the manhwa's telling words the novel chapter shares, 0 to 100 (0 for an estimate). */
            val share: Int,
            val paragraphs: List<String>,
            val translated: Boolean,
        ) : State {
            val chapter: ChapterInfo get() = chapters[index]
        }

        data class Failed(val why: Why) : State
    }

    enum class Why { CHAPTERS, TEXT, UNSURE, NO_SOURCE }

    /** Where [chapter] of [manga] is told in its novel, and that novel chapter's text. */
    suspend fun open(context: Context, manga: Manga, chapter: Chapter, onState: (State) -> Unit) {
        val link = NovelBridge.link(context, manga.id) ?: return onState(State.NoNovel)
        onState(State.Locating(null))
        val chapters = chaptersOf(context, link) ?: return onState(State.Failed(Why.CHAPTERS))
        if (chapters.isEmpty()) return onState(State.Failed(Why.CHAPTERS))
        val known = NovelBridge.places(context, manga.id)
        var matched = true
        var share = 0
        val index = known[chapter.id]?.let { place ->
            share = (place.share * 100).toInt()
            place.novelIndex
        } ?: when (val r = NovelAligner.locate(context, manga, chapter, link, chapters) { onState(State.Locating(it)) }) {
            is NovelAligner.Result.Found -> {
                share = (r.place.share * 100).toInt()
                r.place.novelIndex
            }
            else -> {
                // Not told apart for sure: the pace of the chapters matched before says where it most likely is
                matched = false
                AlignPlan.predict(chapter.chapterNumber, known.values.map { it.manhwaNumber to it.novelIndex })
            }
        } ?: return onState(State.Failed(Why.UNSURE))
        val at = index.coerceIn(0, chapters.size - 1)
        onState(load(context, link, chapters, at, matched, share))
    }

    /** The novel chapter at [index] (to go on or back from where the scene was found). */
    suspend fun load(
        context: Context,
        link: NovelBridge.Link,
        chapters: List<ChapterInfo>,
        index: Int,
        matched: Boolean = false,
        share: Int = 0,
    ): State {
        val chapter = chapters[index]
        val store = context.appGraph.novelStore
        store.translation(link.novelKey, chapter.key)?.takeIf { it.isNotBlank() }?.let {
            return State.Ready(link, chapters, index, matched, share, paragraphs(it), translated = true)
        }
        val text = store.cachedText(link.novelKey, chapter.key) ?: run {
            val source = context.appGraph.novelExtensionManager.source(link.sourceId) ?: return State.Failed(Why.NO_SOURCE)
            try {
                withContext(Dispatchers.IO) { fetchChapter(source, chapter) }.also {
                    if (it.isNotBlank()) store.cacheText(link.novelKey, chapter.key, it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Other face: ${chapter.name}" }
                ""
            }
        }
        if (text.isBlank()) return State.Failed(Why.TEXT)
        return State.Ready(link, chapters, index, matched, share, paragraphs(text), translated = false)
    }

    /** [ready]'s chapter in Arabic (kept for the novel reader too). */
    suspend fun translate(context: Context, ready: State.Ready, onProgress: (Int, Int) -> Unit): State.Ready {
        val out = eu.kanade.tachiyomi.novel.NovelTranslator.translate(context, ready.link.novelKey, ready.link.title, ready.paragraphs, onProgress)
        val text = out.joinToString("\n\n")
        context.appGraph.novelStore.saveTranslation(ready.link.novelKey, ready.chapter.key, text)
        return ready.copy(paragraphs = out, translated = true)
    }

    private suspend fun chaptersOf(context: Context, link: NovelBridge.Link): List<ChapterInfo>? {
        NovelSession.chapters[link.novelKey]?.takeIf { it.isNotEmpty() }?.let { return NovelBridgeUi.inReadingOrder(it.distinctBy { c -> c.key }) }
        val source = context.appGraph.novelExtensionManager.source(link.sourceId) ?: return null
        return try {
            val list = withContext(Dispatchers.IO) {
                source.getChapterList(MangaInfo(key = link.url, title = link.title, cover = link.cover), emptyList())
            }.filter { it.key.isNotBlank() }.distinctBy { it.key }
            NovelSession.chapters[link.novelKey] = list
            NovelBridgeUi.inReadingOrder(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Other face: chapters of ${link.title}" }
            null
        }
    }

    /** The chapter's paragraphs (pictures left out: the face shows the words). */
    private fun paragraphs(text: String): List<String> =
        text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('\u0000') }
}
