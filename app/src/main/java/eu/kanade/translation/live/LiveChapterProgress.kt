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

    fun report(chapterId: Long, done: Int, total: Int) {
        if (total <= 0) return
        _chapters.update { it + (chapterId to (done.coerceAtMost(total) to total)) }
    }
}
