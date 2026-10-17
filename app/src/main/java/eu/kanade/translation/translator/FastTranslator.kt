package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import java.io.IOException
import java.time.LocalDate

/**
 * MANHWA fast translation, like Google Lens: the page's text, read by the OCR, goes straight to a machine
 * translation engine (no AI, no key of the team spent). The best engine the reader set up goes first: DeepL, then
 * Microsoft, then Google Cloud (each with the reader's own official key), then Google Translate and, with no
 * internet, ML Kit on the phone. An engine out of quota or refusing its key hands what is left to the next one.
 *
 * Lines already in the translation collection are written before this runs (only blocks still empty come here);
 * the work's glossary names are put in before sending, so names stay the same in every chapter.
 */
class FastTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val prefs: TranslationPreferences,
    private val client: OkHttpClient,
) : TextTranslator {

    /** The work's names and terms (source to translation), put in before the text is sent. */
    var glossary: Map<String, String> = emptyMap()

    private val google by lazy { GoogleTranslator(fromLang, toLang) }
    private var mlkit: MLKitTranslator? = null

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) = withContext(Dispatchers.IO) {
        val blocks = pages.values.flatMap { it.blocks }.filter { it.text.isNotBlank() && it.translation.isBlank() }
        if (blocks.isEmpty()) return@withContext
        var left: List<TranslationBlock> = blocks
        val keys = FastTranslatorRules.Keys(
            deepl = prefs.translationFastDeeplKey().get().trim(),
            microsoft = prefs.translationFastMicrosoftKey().get().trim(),
            googleCloud = prefs.translationFastGoogleCloudKey().get().trim(),
        )
        val chosen = FastTranslatorRules.Engine.entries.getOrNull(prefs.translationFastEngine().get())
        for (engine in FastTranslatorRules.order(keys, chosen)) {
            if (left.isEmpty()) break
            if (engine in resting()) continue
            left = try {
                run(engine, left, keys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Fast translation: ${engine.name} failed, the next engine takes over" }
                if (e is EngineOut) rest(engine)
                left
            }
        }
    }

    /** Translates [blocks] with [engine]; returns the ones still without a translation. */
    private suspend fun run(engine: FastTranslatorRules.Engine, blocks: List<TranslationBlock>, keys: FastTranslatorRules.Keys): List<TranslationBlock> {
        when (engine) {
            FastTranslatorRules.Engine.MLKIT -> {
                val translator = mlkit ?: MLKitTranslator(fromLang, toLang).also { mlkit = it }
                val page = mutableMapOf("fast" to PageTranslation(blocks.toMutableList(), 0f, 0f))
                translator.translate(page)
            }
            FastTranslatorRules.Engine.GOOGLE -> blocks.forEach { block ->
                block.translation = google.translateText(protect(block.text)).trim()
            }
            else -> {
                val batch = FastTranslatorRules.batchSize(engine)
                blocks.chunked(batch).forEach { part ->
                    val out = when (engine) {
                        FastTranslatorRules.Engine.DEEPL -> deepl(part.map { protect(it.text) }, keys.deepl)
                        FastTranslatorRules.Engine.MICROSOFT -> microsoft(part.map { protect(it.text) }, keys.microsoft)
                        else -> googleCloud(part.map { protect(it.text) }, keys.googleCloud)
                    }
                    part.forEachIndexed { i, block -> out.getOrNull(i)?.let { block.translation = it.trim() } }
                    FastUsage.add(prefs, engine, part.sumOf { it.text.length })
                }
            }
        }
        val done = blocks.filter { it.translation.isNotBlank() }
        done.forEach { it.translation = finish(it.translation, it.text) }
        if (done.isNotEmpty()) FastUsage.used(engine)
        return blocks.filter { it.translation.isBlank() }
    }

    private fun protect(text: String): String = FastTranslatorRules.protectTerms(text.replace('\n', ' '), glossary)

    private fun finish(translation: String, source: String): String {
        val clean = TextSanitizer.clean(translation, source, toLang)
        return if (toLang == TextTranslatorLanguage.ARABIC) ArabicPolisher.polish(clean) else clean
    }

    private suspend fun deepl(texts: List<String>, key: String): List<String> {
        val form = FormBody.Builder().apply {
            texts.forEach { add("text", it) }
            add("target_lang", FastTranslatorRules.deeplTarget(toLang.code))
        }.build()
        val request = Request.Builder()
            .url(FastTranslatorRules.deeplUrl(key))
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(form)
            .build()
        val body = call(request, FastTranslatorRules.Engine.DEEPL)
        return Json.parseToJsonElement(body).jsonObject["translations"]?.jsonArray.orEmpty()
            .map { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    private suspend fun microsoft(texts: List<String>, key: String): List<String> {
        val region = prefs.translationFastMicrosoftRegion().get().trim()
        val payload = buildJsonArray { texts.forEach { add(buildJsonObject { put("Text", it) }) } }.toString()
        val request = Request.Builder()
            .url("https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=${FastTranslatorRules.microsoftTarget(toLang.code)}")
            .header("Ocp-Apim-Subscription-Key", key)
            .apply { if (region.isNotEmpty()) header("Ocp-Apim-Subscription-Region", region) }
            .post(payload.toRequestBody(JSON))
            .build()
        val body = call(request, FastTranslatorRules.Engine.MICROSOFT)
        return Json.parseToJsonElement(body).jsonArray.map { item ->
            ((item as? JsonObject)?.get("translations") as? JsonArray)?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    private suspend fun googleCloud(texts: List<String>, key: String): List<String> {
        val payload = buildJsonObject {
            put("q", JsonArray(texts.map { JsonPrimitive(it) }))
            put("target", toLang.code)
            put("format", "text")
        }.toString()
        val request = Request.Builder()
            .url("https://translation.googleapis.com/language/translate/v2?key=$key")
            .post(payload.toRequestBody(JSON))
            .build()
        val body = call(request, FastTranslatorRules.Engine.GOOGLE_CLOUD)
        return Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("translations")?.jsonArray.orEmpty()
            .map { (it as? JsonObject)?.get("translatedText")?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    private suspend fun call(request: Request, engine: FastTranslatorRules.Engine): String {
        val response = client.newCall(request).await()
        response.use {
            val body = it.body.string()
            if (it.isSuccessful) return body
            // Out of quota or a refused key: this engine rests for a while, the next one goes on
            if (FastTranslatorRules.engineOut(it.code)) throw EngineOut("${engine.name} ${it.code}")
            throw IOException("${engine.name} ${it.code}: ${body.take(200)}")
        }
    }

    override fun close() {
        mlkit?.close()
        mlkit = null
    }

    private class EngineOut(message: String) : IOException(message)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** Engines out of quota or refused this app session, with when they may be tried again. */
        val restingUntil = java.util.concurrent.ConcurrentHashMap<FastTranslatorRules.Engine, Long>()

        fun resting(): Set<FastTranslatorRules.Engine> {
            val now = System.currentTimeMillis()
            return restingUntil.filterValues { it > now }.keys
        }

        fun rest(engine: FastTranslatorRules.Engine) {
            restingUntil[engine] = System.currentTimeMillis() + REST_MS
        }

        const val REST_MS = 30 * 60 * 1000L
    }
}

/** MANHWA: the characters each fast engine was sent this month (counted here), and the engine that worked last. */
object FastUsage {

    private val _last = MutableStateFlow<FastTranslatorRules.Engine?>(null)

    /** The engine that translated last, for the settings line. */
    val last: StateFlow<FastTranslatorRules.Engine?> = _last.asStateFlow()

    fun used(engine: FastTranslatorRules.Engine) {
        _last.value = engine
    }

    @Synchronized
    fun add(prefs: TranslationPreferences, engine: FastTranslatorRules.Engine, chars: Int) {
        val pref = prefs.translationFastUsage()
        val month = LocalDate.now().toString().take(7)
        val counts = FastTranslatorRules.parseUsage(pref.get(), month).toMutableMap()
        counts[engine.name] = (counts[engine.name] ?: 0L) + chars
        pref.set(FastTranslatorRules.formatUsage(month, counts))
    }

    /** Characters sent to [engine] this month. */
    fun thisMonth(prefs: TranslationPreferences, engine: FastTranslatorRules.Engine): Long =
        FastTranslatorRules.parseUsage(prefs.translationFastUsage().get(), LocalDate.now().toString().take(7))[engine.name] ?: 0L
}

/** MANHWA: the pure rules of fast translation (tested without a phone). */
object FastTranslatorRules {

    enum class Engine { AUTO, DEEPL, MICROSOFT, GOOGLE_CLOUD, GOOGLE, MLKIT }

    data class Keys(val deepl: String = "", val microsoft: String = "", val googleCloud: String = "")

    /**
     * The engines to try, in order: the one the reader chose first (when it can run), then every engine with a key
     * (DeepL, Microsoft, Google Cloud), then Google Translate, then ML Kit on the phone.
     */
    fun order(keys: Keys, chosen: Engine?): List<Engine> {
        val keyed = listOfNotNull(
            Engine.DEEPL.takeIf { keys.deepl.isNotBlank() },
            Engine.MICROSOFT.takeIf { keys.microsoft.isNotBlank() },
            Engine.GOOGLE_CLOUD.takeIf { keys.googleCloud.isNotBlank() },
        )
        val all = keyed + Engine.GOOGLE + Engine.MLKIT
        val first = chosen?.takeIf { it != Engine.AUTO && it in all }
        return (listOfNotNull(first) + all).distinct()
    }

    /** DeepL's free keys end with ":fx" and use their own server. */
    fun deeplUrl(key: String): String =
        if (key.trim().endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"

    /** DeepL's target code (Arabic "AR", English "EN-US", Portuguese "PT-BR", Chinese "ZH-HANS"). */
    fun deeplTarget(code: String): String = when (val c = code.lowercase().substringBefore('-')) {
        "en" -> "EN-US"
        "pt" -> "PT-BR"
        "zh" -> "ZH-HANS"
        else -> c.uppercase()
    }

    /** Microsoft's target code (Chinese "zh-Hans", the rest as they are). */
    fun microsoftTarget(code: String): String = when (code.lowercase()) {
        "zh", "zh-cn" -> "zh-Hans"
        "zh-tw" -> "zh-Hant"
        else -> code.lowercase()
    }

    /** How many lines go in one request. */
    fun batchSize(engine: Engine): Int = when (engine) {
        Engine.DEEPL -> 50
        Engine.MICROSOFT -> 100
        Engine.GOOGLE_CLOUD -> 100
        else -> 1
    }

    /** Out of quota (DeepL 456, 429) or a refused key (401, 403): the engine rests, the next one goes on. */
    fun engineOut(code: Int): Boolean = code == 456 || code == 429 || code == 401 || code == 403

    /** The work's names and terms put in before the line is sent, longest first, whole words only. */
    fun protectTerms(text: String, glossary: Map<String, String>): String {
        var out = text
        glossary.entries
            .filter { it.key.length >= 2 && it.value.isNotBlank() && it.key.any(Char::isLetter) }
            .sortedByDescending { it.key.length }
            .forEach { (term, translation) ->
                val regex = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                out = regex.replace(out) { translation }
            }
        return out
    }

    /** The month's character counts, read from "month|ENGINE=count;ENGINE=count" (another month: empty). */
    fun parseUsage(stored: String, month: String): Map<String, Long> {
        val parts = stored.split('|', limit = 2)
        if (parts.size < 2 || parts[0] != month) return emptyMap()
        return parts[1].split(';').mapNotNull { pair ->
            val (k, v) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            v.toLongOrNull()?.let { k to it }
        }.toMap()
    }

    fun formatUsage(month: String, counts: Map<String, Long>): String =
        month + "|" + counts.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** In fast mode, a saved line this close to the one read is written as it is (it is only a hint otherwise). */
    const val FAST_NEAR = 0.92f
}
