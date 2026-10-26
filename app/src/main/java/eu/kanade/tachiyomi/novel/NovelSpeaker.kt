package eu.kanade.tachiyomi.novel

import android.content.Context
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Locale

/** Who a piece of a paragraph is said by. */
enum class Role { NARRATOR, MALE, FEMALE }

/** A piece of a paragraph and who says it. */
data class Line(val text: String, val role: Role)

/** What the speaker is doing: the engine ready or not, playing or not, and the paragraph being read. */
data class SpeakState(val ready: Boolean = false, val playing: Boolean = false, val paragraph: Int = -1, val failed: Boolean = false)

/** A voice the reader can pick: its id (a system voice's name, or a Piper voice's id), how it is shown, whether it sounds natural. */
data class VoiceOption(val id: String, val label: String, val natural: Boolean, val female: Boolean? = null)

/**
 * MANHWA novels: reads a chapter aloud. With [PIPER] it uses the open-source Piper voices inside the app (on the
 * phone, no network once a voice is downloaded); otherwise a speech engine of the phone (Google's, or another one
 * installed). The narration has its voice, and what the characters say has a man's or a woman's voice of its own,
 * so a scene sounds like people talking.
 */
class NovelSpeaker(context: Context, private val engine: String = "") {

    private val app = context.applicationContext
    private val piper = engine == PIPER

    private val _state = MutableStateFlow(SpeakState())
    val state: StateFlow<SpeakState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var onFinished: (() -> Unit)? = null

    // Piper: one coroutine makes the next lines' audio while the one before plays
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            logcat(LogPriority.ERROR, e) { "Piper playback stopped" }
            _state.value = _state.value.copy(playing = false)
        },
    )
    private var job: Job? = null
    private val tracks = HashMap<Int, AudioTrack>()

    init {
        if (piper) {
            PiperVoices.refresh(app)
            _state.value = SpeakState(ready = PiperVoices.available, failed = !PiperVoices.available)
        } else {
            val listener = TextToSpeech.OnInitListener { status ->
                _state.value = _state.value.copy(ready = status == TextToSpeech.SUCCESS, failed = status != TextToSpeech.SUCCESS)
            }
            // A chosen engine (Google's, or an open-source one such as SherpaTTS), else the phone's default
            tts = if (engine.isBlank()) TextToSpeech(app, listener) else TextToSpeech(app, listener, engine)
            tts?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        paragraphOf(utteranceId)?.let { _state.value = _state.value.copy(playing = true, paragraph = it) }
                    }

                    override fun onDone(utteranceId: String) {
                        if (utteranceId == LAST) finished()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) = Unit

                    override fun onStop(utteranceId: String, interrupted: Boolean) = Unit
                },
            )
        }
    }

    /** The speech engines installed on the phone: package and name (Piper, inside the app, is offered apart). */
    fun engines(): List<Pair<String, String>> = runCatching {
        tts?.engines.orEmpty().map { it.name to it.label }
    }.getOrDefault(emptyList())

    /** The voices of [language] ("ar", "en"…) that can speak now, the ones that sound most human first. */
    fun voices(language: String): List<VoiceOption> {
        if (piper) {
            val installed = PiperVoices.installed.value
            return PiperVoices.all().filter { it.language == language && it.id in installed }
                .map { VoiceOption(it.id, it.label, natural = true, female = it.female) }
        }
        return systemVoices(language).map { v ->
            VoiceOption(v.name, v.name, natural = v.name.contains("network", ignoreCase = true) || v.isNetworkConnectionRequired)
        }
    }

    private fun systemVoices(language: String): List<Voice> = runCatching {
        tts?.voices.orEmpty()
            .filter { it.locale.language == language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
    }.getOrDefault(emptyList())

    /**
     * Reads [paragraphs] from paragraph [from] on, each split into its [Line]s; [voiceFor] gives the id of the voice
     * of a role (null for the default), [onFinished] is called when the last one has been said.
     */
    fun play(
        paragraphs: List<List<Line>>,
        from: Int,
        language: String,
        rate: Float,
        pitch: Float,
        voiceFor: (Role) -> String?,
        onFinished: () -> Unit,
    ) {
        if (!_state.value.ready || paragraphs.isEmpty()) return
        halt()
        this.onFinished = onFinished
        val start = from.coerceIn(0, paragraphs.size - 1)
        _state.value = _state.value.copy(playing = true, paragraph = start)
        if (piper) playPiper(paragraphs, start, language, rate, voiceFor) else playSystem(paragraphs, start, language, rate, pitch, voiceFor)
    }

    private fun playSystem(paragraphs: List<List<Line>>, start: Int, language: String, rate: Float, pitch: Float, voiceFor: (Role) -> String?) {
        val engine = tts ?: return
        val voices = systemVoices(language)
        engine.setLanguage(Locale.forLanguageTag(language))
        engine.setSpeechRate(rate)
        for (p in start until paragraphs.size) {
            paragraphs[p].forEachIndexed { i, line ->
                val voice = voiceFor(line.role)?.let { id -> voices.firstOrNull { it.name == id } }
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
    }

    private class Chunk(val paragraph: Int, val samples: FloatArray, val rate: Int)

    private fun playPiper(paragraphs: List<List<Line>>, start: Int, language: String, rate: Float, voiceFor: (Role) -> String?) {
        val fallback = voices(language).firstOrNull()?.id
        val chunks = Channel<Chunk?>(capacity = 2)
        job = scope.launch {
            // Makes the audio a line or two ahead
            val maker = launch {
                for (p in start until paragraphs.size) {
                    for (line in paragraphs[p]) {
                        if (!isActive) return@launch
                        val id = voiceFor(line.role) ?: fallback ?: continue
                        val audio = PiperVoices.generate(app, id, line.text, rate) ?: continue
                        chunks.send(Chunk(p, audio.samples, audio.sampleRate))
                    }
                    chunks.send(Chunk(p, FloatArray(0), 0))
                }
                chunks.send(null)
            }
            // Plays them in order
            for (chunk in chunks) {
                if (chunk == null) break
                _state.value = _state.value.copy(playing = true, paragraph = chunk.paragraph)
                if (chunk.rate == 0) {
                    delay(PAUSE_MS)
                    continue
                }
                val track = trackFor(chunk.rate)
                // Written a little at a time, so a pause or a stop takes effect at once
                var offset = 0
                while (offset < chunk.samples.size && isActive) {
                    val written = track.write(chunk.samples, offset, minOf(4096, chunk.samples.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                    if (written < 0) break
                    if (written == 0) delay(15) else offset += written
                }
                if (!isActive) break
            }
            maker.cancel()
            if (isActive) {
                // Lets the last words be heard before saying it is done
                delay(400)
                finished()
            }
        }
    }

    @Synchronized
    private fun trackFor(rate: Int): AudioTrack {
        val track = tracks.getOrPut(rate) { PiperVoices.newTrack(rate) }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        return track
    }

    private fun finished() {
        _state.value = _state.value.copy(playing = false, paragraph = -1)
        onFinished?.invoke()
    }

    /** Says [text] once in the voice [id], to hear what it sounds like. */
    fun sample(text: String, id: String?, language: String, rate: Float, pitch: Float) {
        if (!_state.value.ready) return
        halt()
        onFinished = null
        if (piper) {
            playPiper(listOf(listOf(Line(text, Role.NARRATOR))), 0, language, rate) { id }
            return
        }
        val engine = tts ?: return
        val voice = id?.let { name -> systemVoices(language).firstOrNull { it.name == name } }
        if (voice != null) engine.setVoice(voice) else engine.setLanguage(Locale.forLanguageTag(language))
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "sample")
    }

    /** Stops whatever is being said, at once. */
    private fun halt() {
        job?.cancel()
        job = null
        synchronized(this) {
            tracks.values.forEach {
                runCatching {
                    it.pause()
                    it.flush()
                }
            }
        }
        tts?.stop()
    }

    /** Stops where it is; [state]'s paragraph is where to go on from. */
    fun pause() {
        val at = _state.value.paragraph
        onFinished = null
        halt()
        _state.value = _state.value.copy(playing = false, paragraph = at)
    }

    fun stop() {
        onFinished = null
        halt()
        _state.value = _state.value.copy(playing = false, paragraph = -1)
    }

    fun release() {
        stop()
        scope.cancel()
        synchronized(this) {
            tracks.values.forEach { runCatching { it.release() } }
            tracks.clear()
        }
        if (piper) PiperVoices.releaseAll()
        tts?.shutdown()
        tts = null
    }

    private fun paragraphOf(utteranceId: String): Int? =
        utteranceId.takeIf { it.startsWith("p") }?.substringAfter('p')?.substringBefore('-')?.toIntOrNull()

    companion object {
        /** The engine id of the Piper voices inside the app. */
        const val PIPER = "manhwa.piper"

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
