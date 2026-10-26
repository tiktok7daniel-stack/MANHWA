package eu.kanade.translation.speech

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.novel.Line
import eu.kanade.tachiyomi.novel.NovelSpeaker
import eu.kanade.tachiyomi.novel.Role
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.HeavyWork
import eu.kanade.translation.data.PageImageProcessor
import eu.kanade.translation.ocr.OcrModels
import eu.kanade.translation.ocr.PaddleOcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream

/** A line of text found on a page, where it is (page pixels). */
data class TextLine(val text: String, val x: Int, val y: Int, val w: Int, val h: Int)

/** The pure rules of reading a page aloud (tested without a phone). */
object PageSpeechRules {

    private val LATIN_RUN = Regex("[a-zA-Z0-9 :*./%+-]")

    /**
     * PaddleOCR's Arabic recognizer gives its letters from left to right, as they stand on the page; this turns them
     * into reading order, keeping runs of Latin letters and digits the way they were (PaddleOCR's own pred_reverse).
     */
    fun reverseArabic(text: String): String {
        val parts = ArrayList<String>()
        val run = StringBuilder()
        for (c in text) {
            if (!LATIN_RUN.matches(c.toString())) {
                if (run.isNotEmpty()) {
                    parts += run.toString()
                    run.clear()
                }
                parts += c.toString()
            } else {
                run.append(c)
            }
        }
        if (run.isNotEmpty()) parts += run.toString()
        return parts.asReversed().joinToString("").trim()
    }

    fun isArabic(text: String): Boolean = text.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }

    /**
     * The page's lines as bubbles in reading order. Lines one under the other that overlap sideways are one bubble;
     * bubbles go top to bottom, and right to left on the same row. Lines with fewer than two letters (page numbers,
     * marks, sound effects cut short) are left out.
     */
    fun bubbles(lines: List<TextLine>): List<String> {
        val kept = lines.filter { l -> l.text.count { it.isLetter() } >= 2 && l.h > 0 }
        if (kept.isEmpty()) return emptyList()
        val parent = IntArray(kept.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in kept.indices) for (j in i + 1 until kept.size) {
            val a = kept[i]
            val b = kept[j]
            val lineH = maxOf(a.h, b.h)
            val gap = maxOf(a.y, b.y) - minOf(a.y + a.h, b.y + b.h)
            val overlap = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
            if (gap < lineH && overlap > 0) parent[root(j)] = root(i)
        }
        val groups = kept.indices.groupBy { root(it) }.values.map { ids -> ids.map { kept[it] }.sortedBy { it.y } }
        val rowH = kept.map { it.h }.average().toFloat().coerceAtLeast(1f) * 2
        return groups
            .sortedWith(compareBy({ (it.first().y / rowH).toInt() }, { g -> -g.maxOf { it.x + it.w } }))
            .map { g -> g.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
    }
}

/**
 * MANHWA read aloud: says the bubbles of the page being read, then turns to the next page by itself. A translated
 * page is read in its translation; a page with no translation (one already in Arabic from its source) is read from
 * the picture itself with PaddleOCR's Arabic recognizer. The voice is the one picked for the novels (the phone's,
 * Piper's, or the reader's own voice made in VoxGate), so the reader sets it once in one place.
 */
class PageVoice(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings get() = app.appGraph.novelStore.settings.value

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Called when the last bubble of a page was said. */
    var onPageDone: (() -> Unit)? = null

    /** Called with a message for the reader when something keeps the page from being read. */
    var onProblem: ((Problem) -> Unit)? = null

    /** The language of the work's source ("ar", "en"…); a page in another language than Arabic is read only translated. */
    var sourceLanguage: () -> String? = { null }

    enum class Problem { NO_ENGINE, NO_VOICE, OCR_MODELS, OCR_FAILED, NOT_TRANSLATED }

    private var speaker: NovelSpeaker? = null
    private var speakerEngine: String? = null
    private var job: Job? = null

    /** What was read on each page already, so turning back or speaking again does not read the picture twice. */
    private val cache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 40
    }

    private fun keyOf(page: ReaderPage): String =
        "${runCatching { page.chapter.chapter.id }.getOrNull()}:${page.index}:${page.translation?.blocks?.size ?: -1}"

    private fun speakerFor(engine: String): NovelSpeaker {
        speaker?.takeIf { speakerEngine == engine }?.let { return it }
        speaker?.release()
        return NovelSpeaker(app, engine).also {
            speaker = it
            speakerEngine = engine
        }
    }

    fun speak(page: ReaderPage?) {
        job?.cancel()
        _speaking.value = true
        if (page == null) return stop()
        job = scope.launch {
            val bubbles = textOf(page) ?: return@launch stop()
            if (!_speaking.value) return@launch
            if (bubbles.isEmpty()) {
                onPageDone?.invoke()
                return@launch
            }
            val s = settings
            val voice = speakerFor(s.ttsEngine)
            val state = withTimeoutOrNull(ENGINE_WAIT_MS) { voice.state.first { it.ready || it.failed } }
            if (state == null || !state.ready) {
                onProblem?.invoke(Problem.NO_ENGINE)
                return@launch stop()
            }
            val language = if (bubbles.any { PageSpeechRules.isArabic(it) }) "ar" else "en"
            val voices = voice.voices(language)
            // Piper says nothing without a voice of the language on the phone: better to say so than skip every page
            if (s.ttsEngine == NovelSpeaker.PIPER && voices.isEmpty()) {
                onProblem?.invoke(Problem.NO_VOICE)
                return@launch stop()
            }
            val narrator = s.voiceNarrator.takeIf { id -> voices.any { it.id == id } }
            voice.play(
                paragraphs = bubbles.map { listOf(Line(it, Role.NARRATOR)) },
                from = 0,
                language = language,
                rate = s.ttsRate,
                pitch = s.ttsPitch,
                voiceFor = { narrator },
                onFinished = { scope.launch { if (_speaking.value) onPageDone?.invoke() } },
            )
        }
    }

    /** Reads the next page's text ahead, while this one is being said. */
    fun prefetch(page: ReaderPage?) {
        if (page == null || cache.containsKey(keyOf(page))) return
        scope.launch { runCatching { textOf(page, quiet = true) } }
    }

    /** The page's bubbles in reading order; null when it could not be read at all. */
    private suspend fun textOf(page: ReaderPage, quiet: Boolean = false): List<String>? {
        val key = keyOf(page)
        cache[key]?.let { return it }
        val translated = BubbleSpeaker.readingOrder(page.translation)
        if (translated.isNotEmpty()) return translated.also { cache[key] = it }
        // No translation: the picture is read in Arabic, when the work is in Arabic (another script would read as noise)
        val lang = sourceLanguage()
        if (lang != null && lang != "ar" && lang !in ANY_LANGUAGE) {
            if (!quiet) onProblem?.invoke(Problem.NOT_TRANSLATED)
            return null
        }
        if (!OcrModels.ready(app, OcrModels.Pack.ARABIC)) {
            if (quiet) return null
            onProblem?.invoke(Problem.OCR_MODELS)
            OcrModels.download(app, OcrModels.Pack.ARABIC)
            withTimeoutOrNull(OCR_DOWNLOAD_WAIT_MS) {
                OcrModels.status.first { it[OcrModels.Pack.ARABIC] is OcrModels.Status.Ready || it[OcrModels.Pack.ARABIC] is OcrModels.Status.Failed }
            }
            if (!OcrModels.ready(app, OcrModels.Pack.ARABIC)) {
                onProblem?.invoke(Problem.OCR_FAILED)
                return null
            }
        }
        val lines = withContext(Dispatchers.Default) { HeavyWork.reader { readPicture(page) } }
        if (lines == null) {
            if (!quiet) onProblem?.invoke(Problem.OCR_FAILED)
            return null
        }
        return PageSpeechRules.bubbles(lines).also { cache[key] = it }
    }

    /** Every Arabic line on the page's picture, read a band at a time so tall webtoon strips keep their small text. */
    private fun readPicture(page: ReaderPage): List<TextLine>? {
        val open = page.stream ?: return null
        return try {
            val bytes = open().use { it.readBytes() }
            val decoded = PageImageProcessor.decode { ByteArrayInputStream(bytes) } ?: return null
            val bitmap = decoded.bitmap
            try {
                val out = ArrayList<TextLine>()
                val band = (bitmap.width * BAND_RATIO).toInt().coerceAtLeast(OVERLAP * 2)
                var top = 0
                while (top < bitmap.height) {
                    val h = minOf(band, bitmap.height - top)
                    val piece = if (top == 0 && h == bitmap.height) bitmap else Bitmap.createBitmap(bitmap, 0, top, bitmap.width, h)
                    val read = PaddleOcrEngine.read(app, piece, OcrModels.Pack.ARABIC, vertical = false)
                    if (piece !== bitmap) piece.recycle()
                    read?.forEach { l ->
                        val y = l.y + top
                        // A line cut by the band's edge is read again whole in the next band
                        val cut = top + h < bitmap.height && l.y + l.h > h - OVERLAP / 2
                        val seen = out.any { o -> kotlin.math.abs(o.y - y) < o.h && kotlin.math.abs(o.x - l.x) < o.h }
                        if (!cut && !seen) out += TextLine(PageSpeechRules.reverseArabic(l.text), l.x, y, l.w, l.h)
                    }
                    if (top + h >= bitmap.height) break
                    top += h - OVERLAP
                }
                out
            } finally {
                bitmap.recycle()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Page not read aloud" }
            null
        }
    }

    fun stop() {
        _speaking.value = false
        job?.cancel()
        job = null
        speaker?.stop()
    }

    fun shutdown() {
        stop()
        speaker?.release()
        speaker = null
        scope.cancel()
    }

    private companion object {
        const val ENGINE_WAIT_MS = 5_000L
        const val OCR_DOWNLOAD_WAIT_MS = 180_000L
        const val BAND_RATIO = 1.5f
        const val OVERLAP = 160
        val ANY_LANGUAGE = setOf("", "all", "other", "multi")
    }
}
