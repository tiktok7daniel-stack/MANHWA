package eu.kanade.tachiyomi.novel

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Who a piece of a paragraph is said by. */
enum class Role { NARRATOR, MALE, FEMALE }

/** A piece of a paragraph and who says it. */
data class Line(val text: String, val role: Role)

/** What the speaker is doing: the engine ready or not, playing or not, and the paragraph being read. */
data class SpeakState(val ready: Boolean = false, val playing: Boolean = false, val paragraph: Int = -1, val failed: Boolean = false)

/**
 * MANHWA novels: reads a chapter aloud with the phone's own voices (the speech engine installed, Google's natural
 * voices on most phones, the network ones being the most lifelike). The narration has its voice, and what the
 * characters say has a man's or a woman's voice of its own, so a scene sounds like people talking. The reader
 * picks each voice, the speed and the pitch.
 */
class NovelSpeaker(context: Context, engine: String = "") {

    private val _state = MutableStateFlow(SpeakState())
    val state: StateFlow<SpeakState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var queued: List<List<Line>> = emptyList()
    private var onFinished: (() -> Unit)? = null

    init {
        val listener = TextToSpeech.OnInitListener { status ->
            _state.value = _state.value.copy(ready = status == TextToSpeech.SUCCESS, failed = status != TextToSpeech.SUCCESS)
        }
        // A chosen engine (Google's, or an open-source one such as SherpaTTS with Piper voices), else the phone's default
        tts = if (engine.isBlank()) TextToSpeech(context.applicationContext, listener) else TextToSpeech(context.applicationContext, listener, engine)
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    paragraphOf(utteranceId)?.let { _state.value = _state.value.copy(playing = true, paragraph = it) }
                }

                override fun onDone(utteranceId: String) {
                    if (utteranceId == LAST) {
                        _state.value = _state.value.copy(playing = false, paragraph = -1)
                        onFinished?.invoke()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = Unit

                override fun onStop(utteranceId: String, interrupted: Boolean) = Unit
            },
        )
    }

    /** The speech engines installed on the phone: package and name. */
    fun engines(): List<Pair<String, String>> = runCatching {
        tts?.engines.orEmpty().map { it.name to it.label }
    }.getOrDefault(emptyList())

    /** The voices of [language] ("ar", "en"…) the engine has, the ones that sound most human first. */
    fun voices(language: String): List<Voice> = runCatching {
        tts?.voices.orEmpty()
            .filter { it.locale.language == language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
    }.getOrDefault(emptyList())

    /**
     * Reads [paragraphs] from paragraph [from] on, each split into its [Line]s; [voiceFor] gives the voice of a role
     * (null for the engine's default), [onFinished] is called when the last one has been said.
     */
    fun play(
        paragraphs: List<List<Line>>,
        from: Int,
        language: String,
        rate: Float,
        pitch: Float,
        voiceFor: (Role) -> Voice?,
        onFinished: () -> Unit,
    ) {
        val engine = tts ?: return
        if (!_state.value.ready || paragraphs.isEmpty()) return
        engine.stop()
        queued = paragraphs
        this.onFinished = onFinished
        engine.setLanguage(Locale.forLanguageTag(language))
        engine.setSpeechRate(rate)
        val start = from.coerceIn(0, paragraphs.size - 1)
        for (p in start until paragraphs.size) {
            paragraphs[p].forEachIndexed { i, line ->
                val voice = voiceFor(line.role)
                if (voice != null) engine.setVoice(voice) else engine.setLanguage(Locale.forLanguageTag(language))
                // Without a voice of its own a character is told apart by the pitch alone
                engine.setPitch(
                    pitch * when {
                        voice != null -> 1f
                        line.role == Role.FEMALE -> 1.25f
                        line.role == Role.MALE -> 0.85f
                        else -> 1f
                    },
                )
                engine.speak(line.text, TextToSpeech.QUEUE_ADD, Bundle(), "p$p-$i")
            }
            // A short breath between paragraphs
            engine.playSilentUtterance(PAUSE_MS, TextToSpeech.QUEUE_ADD, "p$p-gap")
        }
        engine.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, LAST)
        _state.value = _state.value.copy(playing = true, paragraph = start)
    }

    /** Says [text] once in [voice], to hear what a voice sounds like. */
    fun sample(text: String, voice: Voice?, language: String, rate: Float, pitch: Float) {
        val engine = tts ?: return
        engine.stop()
        if (voice != null) engine.setVoice(voice) else engine.setLanguage(Locale.forLanguageTag(language))
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "sample")
    }

    /** Stops where it is; [state]'s paragraph is where to go on from. */
    fun pause() {
        val at = _state.value.paragraph
        onFinished = null
        tts?.stop()
        _state.value = _state.value.copy(playing = false, paragraph = at)
    }

    fun stop() {
        onFinished = null
        tts?.stop()
        _state.value = _state.value.copy(playing = false, paragraph = -1)
    }

    fun release() {
        onFinished = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun paragraphOf(utteranceId: String): Int? =
        utteranceId.takeIf { it.startsWith("p") }?.substringAfter('p')?.substringBefore('-')?.toIntOrNull()

    companion object {
        private const val LAST = "end"
        private const val PAUSE_MS = 350L

        private val QUOTES = Regex("[\"“”«»„]([^\"“”«»„]{1,600})[\"“”«»„]")

        /**
         * A paragraph in its [Line]s: what is between quotation marks is said by a character, the rest is told; a
         * paragraph that opens with a dash is a character speaking as a whole (as Arabic novels write dialogue).
         * [speaker] is the character's role for this paragraph, when it is known.
         */
        fun lines(paragraph: String, speaker: Role?): List<Line> {
            val voice = speaker ?: Role.MALE
            val trimmed = paragraph.trim()
            if (trimmed.startsWith("-") || trimmed.startsWith("–") || trimmed.startsWith("—")) {
                return listOf(Line(trimmed.trimStart('-', '–', '—', ' '), voice))
            }
            val out = mutableListOf<Line>()
            var last = 0
            for (match in QUOTES.findAll(trimmed)) {
                val before = trimmed.substring(last, match.range.first).trim()
                if (before.isNotEmpty()) out += Line(before, Role.NARRATOR)
                val said = match.groupValues[1].trim()
                if (said.isNotEmpty()) out += Line(said, voice)
                last = match.range.last + 1
            }
            val rest = trimmed.substring(last).trim()
            if (rest.isNotEmpty()) out += Line(rest, Role.NARRATOR)
            return out.ifEmpty { listOf(Line(trimmed, Role.NARRATOR)) }
        }

        /** Whether a paragraph has something a character says. */
        fun hasSpeech(paragraph: String): Boolean {
            val t = paragraph.trim()
            return t.startsWith("-") || t.startsWith("–") || t.startsWith("—") || QUOTES.containsMatchIn(t)
        }
    }
}
