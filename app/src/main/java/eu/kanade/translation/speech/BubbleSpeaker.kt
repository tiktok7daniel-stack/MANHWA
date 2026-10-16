package eu.kanade.translation.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * MANHWA read aloud: the translated bubbles of a page spoken by the phone's own voice (Android text to speech,
 * Arabic when the phone has it), in reading order: top to bottom, and right to left on the same row for
 * Arabic. When a page is done, [onPageDone] turns to the next one.
 */
class BubbleSpeaker(context: Context, private val language: Locale = Locale("ar")) {

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Called on the main thread's caller side when the last bubble of a page was spoken. */
    var onPageDone: (() -> Unit)? = null

    @Volatile private var ready = false
    private var pending: List<String>? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            runCatching { tts.language = language }
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == LAST && _speaking.value) onPageDone?.invoke()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == LAST && _speaking.value) onPageDone?.invoke()
                    }
                },
            )
            pending?.let { speakLines(it) }
            pending = null
        }
    }

    /** Whether the phone can speak [language] at all. */
    fun available(): Boolean = ready && runCatching { tts.isLanguageAvailable(language) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)

    fun speak(page: PageTranslation?) {
        _speaking.value = true
        val lines = readingOrder(page)
        if (!ready) {
            pending = lines
            return
        }
        speakLines(lines)
    }

    private fun speakLines(lines: List<String>) {
        tts.stop()
        if (lines.isEmpty()) {
            onPageDone?.invoke()
            return
        }
        lines.forEachIndexed { i, line ->
            tts.speak(line, TextToSpeech.QUEUE_ADD, null, if (i == lines.lastIndex) LAST else "b$i")
        }
    }

    fun stop() {
        _speaking.value = false
        pending = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }

    companion object {
        private const val LAST = "manhwa_last"

        /** The page's translated bubbles in reading order (rows top to bottom, right to left in a row). */
        fun readingOrder(page: PageTranslation?): List<String> {
            val blocks = page?.blocks.orEmpty().filter { it.translation.isNotBlank() && !it.kind.equals("sfx", true) }
            if (blocks.isEmpty()) return emptyList()
            val rowHeight = blocks.map { it.height }.average().toFloat().coerceAtLeast(1f)
            return blocks.sortedWith(compareBy({ (it.y / rowHeight).toInt() }, { -it.x })).map { it.translation.replace('\n', ' ').trim() }
        }
    }
}
