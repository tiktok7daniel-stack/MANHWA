package eu.kanade.translation.live

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.data.PageImage
import eu.kanade.translation.data.PageImageProcessor
import eu.kanade.translation.glossary.GlossaryStore
import eu.kanade.translation.live.PageOcr.rescaled
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import java.io.ByteArrayInputStream

/**
 * On-the-fly translation in the reader: the page on screen is translated first, then the next pages one
 * after another in the background, so nobody waits for a whole chapter. Results appear on the pages as
 * soon as each one is done.
 */
@Inject
@SingleIn(AppScope::class)
class LiveTranslator(
    private val preferences: TranslationPreferences,
    private val readerPreferences: ReaderPreferences,
    private val glossaryStore: GlossaryStore,
) {

    data class Status(
        val enabled: Boolean = false,
        val paused: Boolean = false,
        /** 1-based number of the page being translated now, null when idle. */
        val working: Int? = null,
        val done: Int = 0,
        val total: Int = 0,
        val error: String? = null,
    ) {
        val complete: Boolean get() = total > 0 && done >= total
    }

    fun session(scope: CoroutineScope, mangaId: () -> Long?): Session = Session(scope, mangaId)

    inner class Session(private val scope: CoroutineScope, private val mangaId: () -> Long?) {

        private val _status = MutableStateFlow(Status(enabled = preferences.liveTranslation().get()))
        val status: StateFlow<Status> = _status.asStateFlow()

        @Volatile private var pages: List<ReaderPage> = emptyList()

        @Volatile private var focus = 0
        private val attempted = HashSet<ReaderPage>()
        private var recognizer: TextRecognizer? = null
        private var translator: TextTranslator? = null

        init {
            scope.launch(Dispatchers.IO) {
                try {
                    loop()
                } finally {
                    recognizer?.close()
                    translator?.close()
                }
            }
        }

        fun focus(page: ReaderPage) {
            if (page is InsertPage) return
            val chapterPages = page.chapter.pages ?: return
            if (chapterPages !== pages) {
                synchronized(attempted) { attempted.clear() }
                pages = chapterPages
            }
            focus = page.index
            refreshCounts()
        }

        fun setEnabled(enabled: Boolean) {
            preferences.liveTranslation().set(enabled)
            _status.update { it.copy(enabled = enabled, paused = false, error = null) }
        }

        fun togglePause() = _status.update { it.copy(paused = !it.paused) }

        /** Clears this chapter's live results and translates again, e.g. after the glossary changed. */
        fun retranslate() {
            synchronized(attempted) { attempted.clear() }
            pages.forEach { it.translation = null }
            translator?.close()
            translator = null
            _status.update { it.copy(enabled = true, paused = false, error = null) }
            preferences.liveTranslation().set(true)
            refreshCounts()
        }

        private suspend fun loop() {
            while (scope.isActive) {
                val state = _status.value
                if (!state.enabled || state.paused || !readerPreferences.showTranslations.get()) {
                    delay(IDLE_MS)
                    continue
                }
                val next = pick()
                if (next == null) {
                    _status.update { it.copy(working = null) }
                    delay(IDLE_MS)
                    continue
                }
                _status.update { it.copy(working = next.number, error = null) }
                try {
                    translate(next)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.ERROR, e)
                    _status.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                } finally {
                    synchronized(attempted) { attempted += next }
                    refreshCounts()
                }
            }
        }

        /** The page on screen first, then the following ones that are loaded and not translated yet. */
        private fun pick(): ReaderPage? {
            val list = pages
            if (list.isEmpty()) return null
            val start = focus.coerceIn(0, list.lastIndex)
            val end = (start + LOOKAHEAD).coerceAtMost(list.lastIndex)
            return (start..end).asSequence().map { list[it] }.firstOrNull { page ->
                page.translation == null &&
                    synchronized(attempted) { page !in attempted } &&
                    page.status == Page.State.Ready &&
                    page.stream != null
            }
        }

        private suspend fun translate(page: ReaderPage) {
            val from = TextRecognizerLanguage.fromPref(preferences.translateFromLanguage())
            val to = TextTranslatorLanguage.fromPref(preferences.translateToLanguage())
            val ocr = recognizer?.takeIf { it.language == from } ?: TextRecognizer(from).also {
                recognizer?.close()
                recognizer = it
            }
            val engine = translator?.takeIf { it.fromLang == from && it.toLang == to }
                ?: TextTranslators.fromPref(preferences.translationEngine()).build(preferences, from, to).also {
                    translator?.close()
                    translator = it
                }
            val bytes = page.stream!!().use { it.readBytes() }
            val decoded = PageImageProcessor.decode { ByteArrayInputStream(bytes) } ?: return
            val result: PageTranslation
            val images = mutableMapOf<String, PageImage>()
            try {
                val text = ocr.recognize(InputImage.fromBitmap(decoded.bitmap, 0))
                val blocks = text.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }
                // OCR ran on the downscaled bitmap; scale boxes back to the real page size
                val scaled = PageOcr.toPageTranslation(blocks, decoded.bitmap.width, decoded.bitmap.height)
                result = scaled.rescaled(1f / decoded.scale)
                PageImageProcessor.sampleColors(result, decoded)
                val vision = engine is AiTranslator && preferences.translationVision().get()
                if (vision) images[KEY] = PageImageProcessor.tiles(decoded)
            } finally {
                decoded.bitmap.recycle()
            }
            if (result.blocks.isEmpty() && images.isEmpty()) return
            val map = mutableMapOf(KEY to result)
            val id = mangaId()
            (engine as? AiTranslator)?.apply {
                glossary = id?.let(glossaryStore::asMap).orEmpty()
                learnTerms = id != null
                discoveredTerms.clear()
            }
            engine.translate(map, images)
            images[KEY]?.let { PageImageProcessor.sampleColorsFromTiles(result, it) }
            if (id != null) (engine as? AiTranslator)?.let { glossaryStore.merge(id, it.discoveredTerms) }
            if (result.blocks.isNotEmpty()) {
                withContext(Dispatchers.Main) { page.translation = result }
            }
        }

        private fun refreshCounts() {
            val list = pages
            val done = list.count { it.translation != null || synchronized(attempted) { it in attempted } }
            _status.update { it.copy(done = done, total = list.size) }
        }
    }

    private companion object {
        const val KEY = "page"
        const val LOOKAHEAD = 6
        const val IDLE_MS = 400L
    }
}
