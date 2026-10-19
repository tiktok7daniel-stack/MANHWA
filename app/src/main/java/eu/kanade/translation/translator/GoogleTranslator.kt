package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import java.io.IOException
import java.io.UnsupportedEncodingException
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
    private val client1 = "gtx"
    private val client2 = "webapp"
    val okHttpClient = sharedClient

    /** Thrown when Google did not answer for some bubbles even after waiting and asking again. */
    class GoogleFailed(val failed: Int, val total: Int, cause: Throwable?) :
        IOException("Google Translate did not answer for $failed of $total bubbles", cause)

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // A few bubbles at a time, so a page comes back in about a second
        val gate = Semaphore(4)
        val blocks = pages.values.flatMap { it.blocks }
        var failed = 0
        var lastError: Throwable? = null
        coroutineScope {
            blocks.map { block ->
                async {
                    gate.withPermit {
                        try {
                            block.translation = translateText(block.text.replace('\n', ' ')).ifBlank { block.text }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The bubble keeps its text for now; the chapter is reported as failed below
                            block.translation = block.text
                            synchronized(this@GoogleTranslator) {
                                failed++
                                lastError = e
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        // Never a chapter saved half in English without saying so: it can be tried again (the pages read are kept)
        if (failed > 0) throw GoogleFailed(failed, blocks.size, lastError)
    }

    /**
     * Translates one text; used as the fast draft of the hybrid engine and as the last fallback. A request that
     * fails (no network for a moment, a busy answer) is asked again a few times, waiting longer each time and as
     * long as Google says to wait; still nothing throws, rather than give the text back untranslated.
     */
    suspend fun translateText(text: String): String {
        if (text.isBlank()) return text
        val sent = GoogleRules.prepare(text)
        var last: Throwable? = null
        repeat(GoogleRules.ATTEMPTS) { attempt ->
            if (attempt > 0) delay(GoogleRules.waitMillis(attempt, last))
            try {
                return request(toLang.code, sent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                logcat(LogPriority.WARN) { "Google Translate try ${attempt + 1} failed: ${e.message}" }
            }
        }
        throw last ?: IOException("Google Translate did not answer")
    }

    private suspend fun request(lang: String, text: String): String {
        val response = okHttpClient.newCall(Request.Builder().url(getTranslateUrl(lang, text)).build()).await()
        response.use {
            if (!it.isSuccessful) {
                throw GoogleRules.Busy(it.code, it.header("Retry-After")?.trim()?.toLongOrNull())
            }
            return GoogleRules.parse(it.body.string()) ?: throw IOException("Google Translate sent an answer that is not a translation")
        }
    }

    private fun getTranslateUrl(lang: String, text: String): String {
        try {
            val client = client1
            val calculateToken = calculateToken(text)
            val encode: String = URLEncoder.encode(text, "utf-8")
            return "https://translate.google.com/translate_a/single?client=$client&sl=auto&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$calculateToken&q=$encode"
        } catch (unused: UnsupportedEncodingException) {
            val client2 = client1
            val calculateToken2 = calculateToken(text)
            return "https://translate.google.com/translate_a/single?client=$client2&sl=auto&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$calculateToken2&q=$text"
        }
    }

    private fun calculateToken(str: String): String {
        val list = mutableListOf<Int>()
        var i = 0

        while (i < str.length) {
            val charCodeAt = str.codePointAt(i)
            when {
                charCodeAt < 128 -> list.add(charCodeAt)
                charCodeAt < 2048 -> {
                    list.add((charCodeAt shr 6) or 192)
                    list.add((charCodeAt and 63) or 128)
                }
                charCodeAt in 55296..57343 && i + 1 < str.length -> {
                    val nextChar = str.codePointAt(i + 1)
                    if (nextChar in 56320..57343) {
                        val codePoint = ((charCodeAt and 1023) shl 10) + (nextChar and 1023) + 65536
                        list.add((codePoint shr 18) or 240)
                        list.add(((codePoint shr 12) and 63) or 128)
                        list.add(((codePoint shr 6) and 63) or 128)
                        list.add((codePoint and 63) or 128)
                        i++
                    }
                }
                else -> {
                    list.add((charCodeAt shr 12) or 224)
                    list.add(((charCodeAt shr 6) and 63) or 128)
                    list.add((charCodeAt and 63) or 128)
                }
            }
            i++
        }

        var j: Long = 406644
        for (num in list) {
            j = RL(j + num.toLong(), "+-a^+6")
        }
        var rL = RL(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) {
            rL = (rL and 2147483647L) + 2147483648L
        }
        val j2 = rL % 1000000L
        return "$j2.${406644L xor j2}"
    }

    private fun RL(j: Long, str: String): Long {
        var result = j
        var i = 0
        while (i < str.length - 2) {
            val shift = if (str[i + 2] in 'a'..'z') str[i + 2].code - 'W'.code else str[i + 2].digitToInt()
            val shiftValue = if (str[i + 1] == '+') result ushr shift else result shl shift
            result = if (str[i] == '+') (result + shiftValue) and 4294967295L else result xor shiftValue
            i += 3
        }
        return result
    }


    override fun close() {
    }

    companion object {
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

