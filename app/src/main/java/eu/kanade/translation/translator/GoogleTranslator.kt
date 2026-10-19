package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat

class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {
    val okHttpClient = sharedClient

    /** Thrown when Google did not answer for some bubbles even after waiting and asking again. */
    class GoogleFailed(val failed: Int, val total: Int, cause: Throwable?) :
        IOException("Google Translate did not answer for $failed of $total bubbles", cause)

    /** The work's names and terms (source to translation), put in before the text is sent. */
    var glossary: Map<String, String> = emptyMap()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // Each page's bubbles go together, a few requests a page with the page around them; two requests at a time
        // (Google answers 429 "too many requests" to an app that asks faster)
        val gate = Semaphore(2)
        val parts = pages.values.flatMap { page ->
            GoogleRules.batches(page.blocks.map { it.text }).map { range -> page.blocks.subList(range.first, range.last + 1).toList() }
        }
        val total = parts.sumOf { it.size }
        val left = mutableListOf<TranslationBlock>()
        var lastError: Throwable? = null
        coroutineScope {
            parts.map { part ->
                async {
                    gate.withPermit {
                        val (missed, error) = translatePart(part)
                        if (missed.isNotEmpty()) {
                            synchronized(this@GoogleTranslator) {
                                left += missed
                                lastError = error
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (left.isEmpty()) return
        // Google refused or could not be reached: ML Kit on the phone translates what is left, so the chapter goes on
        val offline = runCatching { offlineFallback(left) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)
        if (offline) {
            GoogleStatus.usedOffline(left.size)
            return
        }
        left.forEach { it.translation = it.text }
        // Never a chapter saved half in English without saying so: it can be tried again (the pages read are kept)
        throw GoogleFailed(left.size, total, lastError)
    }

    private var mlkit: MLKitTranslator? = null

    /** ML Kit (on the phone) for [blocks]; true when every one came back translated. */
    private suspend fun offlineFallback(blocks: List<TranslationBlock>): Boolean {
        if (!TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)) return false
        val translator = mlkit ?: MLKitTranslator(fromLang, toLang).also { mlkit = it }
        val work = blocks.map { it.copy(translation = "") }.toMutableList()
        // Its model is downloaded once; a phone that cannot get it in time keeps the chapter as failed
        kotlinx.coroutines.withTimeoutOrNull(OFFLINE_TIMEOUT_MS) {
            translator.translate(mutableMapOf("google-fallback" to PageTranslation(work, 0f, 0f)))
        } ?: return false
        if (work.any { it.translation.isBlank() }) return false
        blocks.forEachIndexed { i, block -> block.translation = finish(work[i].translation, block.text) }
        return true
    }

    /** Translates [part] in one request (each bubble on its own line), else bubble by bubble; what was missed and why. */
    private suspend fun translatePart(part: List<TranslationBlock>): Pair<List<TranslationBlock>, Throwable?> {
        val sent = part.map { send(it.text) }
        if (part.size > 1) {
            val joined = try {
                translateText(sent.joinToString("\n"), prepared = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Google did not answer even after asking again: no use asking for every bubble
                return part to e
            }
            val lines = GoogleRules.split(joined, part.size)
            if (lines != null) {
                part.forEachIndexed { i, block -> block.translation = finish(lines[i], block.text) }
                return emptyList<TranslationBlock>() to null
            }
        }
        // The lines did not come back one for one: each bubble on its own
        var error: Throwable? = null
        val missed = mutableListOf<TranslationBlock>()
        part.forEachIndexed { i, block ->
            try {
                block.translation = finish(translateText(sent[i], prepared = true), block.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                missed += block
            }
        }
        return missed to error
    }

    /** The bubble as it is sent: the OCR's broken lines joined, the work's names put in, capitals in sentence case. */
    private fun send(text: String): String =
        GoogleRules.prepare(FastTranslatorRules.protectTerms(GoogleRules.cleanSource(text), glossary))

    private fun finish(translation: String, source: String): String {
        if (translation.isBlank()) return source
        val clean = TextSanitizer.clean(translation.trim(), source, toLang)
        return if (toLang == TextTranslatorLanguage.ARABIC) ArabicPolisher.polish(clean) else clean
    }

    /**
     * Translates one text; used as the fast draft of the hybrid engine and as the last fallback. A request that
     * fails (no network for a moment, a busy answer) is asked again a few times, waiting longer each time and as
     * long as Google says to wait; still nothing throws, rather than give the text back untranslated.
     */
    suspend fun translateText(text: String, prepared: Boolean = false, ignoreRest: Boolean = false): String {
        if (text.isBlank()) return text
        // Google said "too many requests" a moment ago: it is left alone until its pause ends
        if (!ignoreRest && GoogleStatus.resting()) throw GoogleRules.Busy(429, null)
        val sent = if (prepared) text else GoogleRules.prepare(GoogleRules.cleanSource(text))
        var last: Throwable? = null
        repeat(GoogleRules.ATTEMPTS) { attempt ->
            if (attempt > 0) delay(GoogleRules.waitMillis(attempt, last))
            try {
                val answer = request(toLang.code, sent)
                GoogleStatus.answered()
                return answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                logcat(LogPriority.WARN) { "Google Translate try ${attempt + 1} failed: ${e.message}" }
                // No network at all: asking again only makes the offline fallbacks wait
                if (!GoogleRules.worthRetry(e)) throw e
                // Too many requests twice: Google is given a pause, and the rest goes to the offline engine
                if (GoogleRules.isTooMany(e) && attempt >= 1) {
                    GoogleStatus.rest()
                    throw e
                }
            }
        }
        throw last ?: IOException("Google Translate did not answer")
    }

    private suspend fun request(lang: String, text: String): String {
        val response = okHttpClient.newCall(Request.Builder().url(GoogleRules.url(lang, text)).build()).await()
        response.use {
            if (!it.isSuccessful) {
                throw GoogleRules.Busy(it.code, it.header("Retry-After")?.trim()?.toLongOrNull())
            }
            return GoogleRules.parse(it.body.string()) ?: throw IOException("Google Translate sent an answer that is not a translation")
        }
    }

    override fun close() {
        mlkit?.close()
        mlkit = null
    }

    companion object {
        private const val OFFLINE_TIMEOUT_MS = 5 * 60_000L

        /** One client for every bubble and every chapter: its connections are reused, so each request is quicker. */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** The pure rules of [GoogleTranslator] (tested without a phone). */
object GoogleRules {

    const val ATTEMPTS = 4

    /** Google answered with an error code (429 when asked too often), and how long it said to wait. */
    class Busy(val code: Int, val retryAfterSeconds: Long?) : IOException("Google Translate answered HTTP $code")

    /** Google's public translation address (no key), asking only for the translation. */
    fun url(lang: String, text: String): String =
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" +
            URLEncoder.encode(lang, "utf-8") + "&dt=t&q=" + URLEncoder.encode(text, "utf-8")

    /** Google said "too many requests" (it pauses an app that asks too fast). */
    fun isTooMany(error: Throwable): Boolean = (error as? Busy)?.code == 429

    /** How long Google is left alone after it said "too many requests" twice. */
    const val REST_MS = 10 * 60_000L

    /** Most text sent in one request (the bubbles of a page joined), and most bubbles. */
    private const val BATCH_CHARS = 1200
    private const val BATCH_BUBBLES = 30

    /** The bubbles of a page in groups sent together, in reading order (index ranges into [texts]). */
    fun batches(texts: List<String>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        var chars = 0
        texts.forEachIndexed { i, t ->
            val len = t.length + 1
            if (i > start && (chars + len > BATCH_CHARS || i - start >= BATCH_BUBBLES)) {
                out += start until i
                start = i
                chars = 0
            }
            chars += len
        }
        if (start < texts.size) out += start until texts.size
        return out
    }

    /** The answer to a joined request back into [count] lines, or null when they did not come back one for one. */
    fun split(joined: String, count: Int): List<String>? {
        val lines = joined.split('\n').map { it.trim() }
        if (lines.size == count) return lines
        val filled = lines.filter { it.isNotEmpty() }
        return filled.takeIf { it.size == count }
    }

    /** The OCR text of a bubble on one line: a word broken with a hyphen at the end of a line joined again. */
    fun cleanSource(text: String): String =
        text.replace(Regex("(?<=\\p{L}\\p{L}\\p{L})-\\s*\\n\\s*(?=\\p{L})"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Whether asking again can help: not when there is no network at all (no address, no connection). */
    fun worthRetry(error: Throwable): Boolean =
        error !is java.net.UnknownHostException && error !is java.net.ConnectException && error !is java.net.NoRouteToHostException

    /** How long to wait before try [attempt] (1, 2, 3...): what Google asked for, else 1, 2, 4 seconds. */
    fun waitMillis(attempt: Int, error: Throwable?): Long {
        val asked = (error as? Busy)?.retryAfterSeconds
        if (asked != null && asked > 0) return asked.coerceAtMost(30) * 1000
        return 1000L shl (attempt - 1).coerceIn(0, 4)
    }

    /** The translation in Google's answer (every sentence joined), or null when the answer is not one. */
    fun parse(body: String): String? = try {
        val segments = Json.parseToJsonElement(body).jsonArray[0].jsonArray
        buildString {
            for (segment in segments) {
                val first = (segment as? JsonArray)?.firstOrNull() as? JsonPrimitive ?: continue
                if (first.isString) append(first.content)
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The text as it is sent. Manhwa bubbles are lettered in capitals, and Google reads a word in capitals as a
     * name or an acronym and leaves it as it is ("HUNTER", "RUN"): text mostly in capitals is sent in sentence
     * case, with the first letter of every sentence and the word "I" in capitals.
     */
    fun prepare(text: String): String {
        val letters = text.filter { it.isLetter() && it.code < 0x250 }
        if (letters.length < 4) return text
        val upper = letters.count { it.isUpperCase() }
        if (upper * 10 < letters.length * 7) return text
        val lower = text.lowercase()
        val out = StringBuilder(lower.length)
        var start = true
        for (i in lower.indices) {
            val c = lower[i]
            out.append(if (start && c.isLetter()) c.uppercaseChar() else c)
            if (c.isLetter() || c.isDigit()) start = false
            if (c == '.' || c == '!' || c == '?') start = true
        }
        return Regex("(?<![\\p{L}'])i(?=$|[^\\p{L}])").replace(out.toString(), "I")
    }
}


/**
 * MANHWA: how Google Translate is doing, for the settings ("who translates now"): paused after "too many
 * requests" (the offline engine translates meanwhile) and how many bubbles went to the offline engine.
 */
object GoogleStatus {
    private val _restingUntil = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val restingUntil: kotlinx.coroutines.flow.StateFlow<Long> = _restingUntil

    private val _offlineBubbles = kotlinx.coroutines.flow.MutableStateFlow(0)
    val offlineBubbles: kotlinx.coroutines.flow.StateFlow<Int> = _offlineBubbles

    fun resting(now: Long = System.currentTimeMillis()): Boolean = now < _restingUntil.value

    fun rest(now: Long = System.currentTimeMillis()) {
        _restingUntil.value = now + GoogleRules.REST_MS
    }

    fun answered() {
        if (_restingUntil.value != 0L) _restingUntil.value = 0L
    }

    fun usedOffline(bubbles: Int) {
        _offlineBubbles.value += bubbles
    }
}
