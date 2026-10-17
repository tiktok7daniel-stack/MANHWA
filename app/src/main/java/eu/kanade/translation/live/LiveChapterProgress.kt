package eu.kanade.translation.live

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * MANHWA: how far the reader's live translation got in each chapter of this app session (pages done and pages
 * in all), so a chapter's row outside the reader shows it is being translated, and that it ended.
 */
@Inject
@SingleIn(AppScope::class)
class LiveChapterProgress {

    private val _chapters = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())

    /** Chapter id to (pages translated, pages in all). */
    val chapters: StateFlow<Map<Long, Pair<Int, Int>>> = _chapters.asStateFlow()

    /** The chapter the reader translates live right now (null when none). */
    data class Live(val mangaId: Long, val chapterId: Long, val chapterName: String, val done: Int, val total: Int)

    private val _current = MutableStateFlow<Live?>(null)

    /** The chapter being translated live now, for "Translating now" and its badge; null once it is done. */
    val current: StateFlow<Live?> = _current.asStateFlow()

    fun report(chapterId: Long, done: Int, total: Int, mangaId: Long? = null, chapterName: String = "") {
        if (total <= 0) return
        val pages = done.coerceAtMost(total)
        _chapters.update { it + (chapterId to (pages to total)) }
        _current.value = if (pages < total && mangaId != null) Live(mangaId, chapterId, chapterName, pages, total) else null
    }

    /** The reader closed: nothing is translated live any more. */
    fun stop() {
        _current.value = null
    }
}
