package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Context-aware LLM translator shared by every AI engine.
 *
 * Pages are sent in batches as `{"page": ["bubble", ...]}`; the model must answer with the same shape.
 * Each batch also carries the tail of what was already translated so names and tone stay consistent,
 * failed calls are retried with backoff, and a malformed answer never loses the original text.
 */
class AiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val provider: Provider,
    private val apiKey: String,
    model: String,
    baseUrl: String,
    private val maxOutputTokens: Int,
    private val temperature: Float,
    private val instructions: String,
) : TextTranslator {

    enum class Provider(val defaultModel: String, val defaultBaseUrl: String) {
        GEMINI("gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta"),
        OPENROUTER("google/gemini-2.5-flash", "https://openrouter.ai/api/v1"),
        OPENAI_COMPATIBLE("gpt-4o-mini", "https://api.openai.com/v1"),
        CLAUDE("claude-opus-5-5", "https://api.anthropic.com/v1"),
    }

    private val model = model.ifBlank { provider.defaultModel }
    private val baseUrl = baseUrl.ifBlank { provider.defaultBaseUrl }.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val context = ArrayDeque<String>()
        for (batch in batches(pages)) {
            val input = buildJsonObject {
                batch.forEach { name -> putJsonArray(name) { pages[name]!!.blocks.forEach { add(it.text) } } }
            }
            val output = withRetry { parse(request(systemPrompt(), userPrompt(input, context))) }
            for (name in batch) {
                val page = pages[name]!!
                val translated = (output[name] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
                page.blocks.forEachIndexed { i, block ->
                    block.translation = translated?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: block.text
                    context.addLast(block.translation)
                    if (context.size > CONTEXT_LINES) context.removeFirst()
                }
                page.blocks = page.blocks.filterNot { it.translation.contains(WATERMARK) }.toMutableList()
            }
        }
    }

    /** Splits pages into ordered batches small enough to come back in a single response. */
    private fun batches(pages: Map<String, PageTranslation>): List<List<String>> {
        val result = mutableListOf<MutableList<String>>()
        var size = 0
        for ((name, page) in pages) {
            if (page.blocks.isEmpty()) continue
            if (result.isEmpty() || size + page.blocks.size > BATCH_LINES) {
                result += mutableListOf<String>()
                size = 0
            }
            result.last() += name
            size += page.blocks.size
        }
        return result
    }

    private fun systemPrompt(): String = buildString {
        val target = toLang.label
        appendLine("You are an expert comic localizer (manga, manhwa, manhua) translating scanlations into $target.")
        appendLine("Source language: ${fromLang.label}. Input is a JSON object: keys are page image names,")
        appendLine("values are the OCR'd speech bubbles of that page in reading order.")
        appendLine()
        appendLine("Rules:")
        appendLine("1. Reply with ONLY a JSON object with exactly the same keys, and the same number of strings per key in the same order.")
        appendLine("2. Write natural, fluent $target the way a skilled human localizer would: keep each character's voice, tone and emotion, adapt idioms, and keep lines short enough to fit a speech bubble.")
        appendLine("3. OCR text can be noisy or split mid-sentence: use the neighbouring bubbles to recover the intended meaning and silently fix obvious OCR mistakes.")
        appendLine("4. Keep names of people, places, sects and techniques consistent across the chapter and with the glossary.")
        appendLine("5. Translate sound effects briefly; keep strings made only of punctuation or symbols unchanged.")
        appendLine("6. Replace watermarks, scanlation credits, site names and URLs with exactly \"$WATERMARK\".")
        if (toLang == TextTranslatorLanguage.ARABIC) {
            appendLine("7. Use clear Modern Standard Arabic suited to comics, with Arabic punctuation (، ؟ !).")
        }
        if (instructions.isNotBlank()) {
            appendLine()
            appendLine("Glossary and extra instructions from the reader (follow them):")
            appendLine(instructions.trim())
        }
    }

    private fun userPrompt(input: JsonObject, context: Collection<String>): String = buildString {
        if (context.isNotEmpty()) {
            appendLine("Previous lines, already translated (context only, do not output them):")
            context.forEach { appendLine("- $it") }
            appendLine()
        }
        append("Translate this JSON:\n")
        append(input.toString())
    }

    private suspend fun request(system: String, user: String): String = withContext(Dispatchers.IO) {
        val (url, body, headers) = when (provider) {
            Provider.GEMINI -> Triple(
                "$baseUrl/models/$model:generateContent",
                buildJsonObject {
                    putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
                    putJsonArray("contents") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { addJsonObject { put("text", user) } }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("temperature", temperature)
                        put("maxOutputTokens", maxOutputTokens)
                        put("responseMimeType", "application/json")
                    }
                },
                mapOf("x-goog-api-key" to apiKey),
            )
            Provider.OPENROUTER, Provider.OPENAI_COMPATIBLE -> Triple(
                "$baseUrl/chat/completions",
                buildJsonObject {
                    put("model", model)
                    put("temperature", temperature)
                    put("max_tokens", maxOutputTokens)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "system")
                            put("content", system)
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", user)
                        }
                    }
                },
                buildMap {
                    if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                    if (provider == Provider.OPENROUTER) put("X-Title", "Danghwa")
                },
            )
            // Current Claude models reject sampling parameters, so temperature is not sent.
            Provider.CLAUDE -> Triple(
                "$baseUrl/messages",
                buildJsonObject {
                    put("model", model)
                    put("max_tokens", maxOutputTokens)
                    put("system", system)
                    put("fallbacks", "default")
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            put("content", user)
                        }
                    }
                },
                mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                    "anthropic-beta" to "server-side-fallback-2026-07-01",
                ),
            )
        }
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw ApiException(response.code, text.take(500))
            extractText(json.parseToJsonElement(text).jsonObject)
        }
    }

    private fun extractText(root: JsonObject): String = when (provider) {
        Provider.GEMINI -> root["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!
            .jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        Provider.OPENROUTER, Provider.OPENAI_COMPATIBLE -> root["choices"]!!.jsonArray[0].jsonObject["message"]!!
            .jsonObject["content"]!!.jsonPrimitive.content
        Provider.CLAUDE -> {
            val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
            if (stopReason == "refusal") throw IOException("Claude declined to translate this batch")
            root["content"]!!.jsonArray
                .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
        }
    }

    /** Pulls the JSON object out of the reply, tolerating markdown fences or stray prose around it. */
    private fun parse(reply: String): JsonObject {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("No JSON in model reply")
        return json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var wait = 2_000L
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e is ApiException && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                logcat(LogPriority.WARN, e) { "AI translation attempt ${attempt + 1} failed, retrying" }
                delay(wait)
                wait *= 2
            }
        }
        return block()
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    class ApiException(val code: Int, body: String) : IOException("HTTP $code: $body")

    private companion object {
        const val WATERMARK = "RTMTH"
        const val BATCH_LINES = 120
        const val CONTEXT_LINES = 25
        const val MAX_ATTEMPTS = 4
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

