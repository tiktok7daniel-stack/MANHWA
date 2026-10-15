package eu.kanade.translation.model

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

data class Translation(
    val source: HttpSource,
    val manga: Manga,
    val chapter: Chapter,
    val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.CHINESE,
    val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH,
) {
    // Every queued chapter is its own entry: two tries of the same chapter are never "equal", so the queue
    // always notices a chapter queued again after it failed
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    @Transient
    private val _statusFlow = MutableStateFlow(State.NOT_TRANSLATED)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()
    var status: State
        get() = _statusFlow.value
        set(status) {
            _statusFlow.value = status
        }

    @Transient
    private val _progress = MutableStateFlow(Progress())

    /** Where this chapter's translation is, with a real percentage (see [Progress]). */
    @Transient
    val progress = _progress.asStateFlow()

    /** Moves to [stage] with [done] of [total] items finished there; the percentage never goes back. */
    fun advance(stage: Stage, done: Int = 0, total: Int = 0) {
        _progress.update { old ->
            val percent = stage.percentAt(done, total).coerceAtLeast(old.percent)
            Progress(
                stage,
                done,
                total,
                percent,
                note = null,
                library = old.library,
                startedAt = old.startedAt,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Why the chapter is waiting right now (kept until it moves on); does not count as progress. */
    fun note(note: Note?) {
        _progress.update { it.copy(note = note) }
    }

    /** Share (0–100) of the chapter's lines that came straight from the translation collection. */
    fun fromLibrary(percent: Int) {
        _progress.update { it.copy(library = percent) }
    }

    fun fail(reason: Note, detail: String?) {
        _progress.update { it.copy(note = reason, detail = detail) }
    }

    /** Back in the queue for another try: keeps how far it got, so it shows where it will carry on. */
    fun requeue() {
        _progress.update { it.copy(stage = Stage.QUEUED, note = null, detail = null, startedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()) }
    }

    /**
     * Steps of a chapter translated from the manga screen, each with its share of the whole percentage:
     * downloading 0–25, reading the text 25–55, planning 55–60, translating 60–92, reviewing 92–98, saving 98–100.
     */
    enum class Stage(val from: Int, val to: Int) {
        QUEUED(0, 0),
        DOWNLOADING(0, 25),
        READING(25, 55),
        PLANNING(55, 60),
        TRANSLATING(60, 92),
        REVIEWING(92, 98),
        SAVING(98, 100),
        ;

        fun percentAt(done: Int, total: Int): Int {
            if (total <= 0) return from
            return from + ((to - from) * done.coerceIn(0, total) / total)
        }
    }

    /** What the chapter is waiting on, or why it failed. */
    enum class Note { AI, RETRY, KEY_LIMIT, KEY_INVALID, READER, NO_INTERNET, STALLED, DOWNLOAD_FAILED, NOT_DOWNLOADED, SAVE_FAILED, OTHER }

    data class Progress(
        val stage: Stage = Stage.QUEUED,
        val done: Int = 0,
        val total: Int = 0,
        /** 0–100, only goes up. */
        val percent: Int = 0,
        val note: Note? = null,
        /** The error text behind a failure, shown with it. */
        val detail: String? = null,
        /** Share (0–100) of the lines written straight from the translation collection. */
        val library: Int = 0,
        val startedAt: Long = System.currentTimeMillis(),
        /** Last time real work moved forward. */
        val updatedAt: Long = System.currentTimeMillis(),
    )

    enum class State(val value: Int) {
        NOT_TRANSLATED(0),
        QUEUE(1),
        TRANSLATING(2),
        TRANSLATED(3),
        ERROR(4),
    }

    companion object {
        suspend fun fromChapterId(
            chapterId: Long,
            getChapter: GetChapter,
            getManga: GetManga,
            sourceManager: SourceManager,
        ): Translation? {
            val chapter = getChapter.await(chapterId) ?: return null
            val manga = getManga.await(chapter.mangaId) ?: return null
            val source = sourceManager.get(manga.source) as? HttpSource ?: return null

            return Translation(source, manga, chapter)
        }
    }
}
