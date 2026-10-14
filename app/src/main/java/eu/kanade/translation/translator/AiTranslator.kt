package eu.kanade.translation.translator

import android.util.Base64
import eu.kanade.translation.data.PageImage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
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
        for (batch in batches(pages)) translateTextBatch(batch, pages, context)
    }

    /**
     * Vision mode: the model sees each page together with the OCR draft, so it can fix misread text,
     * drop false detections, add bubbles the OCR missed and translate with the scene in mind.
     * A batch that fails in vision mode falls back to text-only translation.
     */
    override suspend fun translate(pages: MutableMap<String, PageTranslation>, images: Map<String, PageImage>) {
        if (images.isEmpty()) return translate(pages)
        val context = ArrayDeque<String>()
        for (batch in visionBatches(pages, images)) {
            try {
                translateVisionBatch(batch, pages, images, context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Vision translation failed, falling back to text for $batch" }
                translateTextBatch(batch.filter { pages[it]!!.blocks.isNotEmpty() }, pages, context)
            }
        }
    }

    private suspend fun translateTextBatch(
        batch: List<String>,
        pages: MutableMap<String, PageTranslation>,
        context: ArrayDeque<String>,
    ) {
        if (batch.isEmpty()) return
        val input = buildJsonObject {
            batch.forEach { name -> putJsonArray(name) { pages[name]!!.blocks.forEach { add(it.text) } } }
        }
        val output = withRetry { parse(request(systemPrompt(vision = false), listOf(Part.Text(userPrompt(input, context))))) }
        for (name in batch) {
            val page = pages[name]!!
            val translated = (output[name] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
            page.blocks.forEachIndexed { i, block ->
                block.translation = translated?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: block.text
                context.remember(block.translation)
            }
            page.blocks = page.blocks.filterNot { it.translation.contains(WATERMARK) }.toMutableList()
        }
    }

    private suspend fun translateVisionBatch(
        batch: List<String>,
        pages: MutableMap<String, PageTranslation>,
        images: Map<String, PageImage>,
        context: ArrayDeque<String>,
    ) {
        val input = buildJsonObject {
            for (name in batch) {
                val page = pages[name]!!
                val tiles = images[name]?.tiles.orEmpty()
                putJsonObject(name) {
                    put("slices", tiles.size)
                    putJsonArray("bubbles") {
                        page.blocks.forEachIndexed { i, block -> addBubble(i, block, page, tiles) }
                    }
                }
            }
        }
        val parts = buildList {
            add(Part.Text(userPrompt(input, context)))
            for (name in batch) {
                val tiles = images[name]?.tiles.orEmpty()
                tiles.forEachIndexed { i, tile ->
                    add(Part.Text("Page \"$name\", slice $i of ${tiles.size}:"))
                    add(Part.Image(tile.jpeg))
                }
            }
        }
        val output = withRetry { parse(request(systemPrompt(vision = true), parts)) }
        for (name in batch) {
            val page = pages[name]!!
            val result = output[name] as? JsonObject
            if (result == null) {
                if (page.blocks.isEmpty()) continue
                throw IOException("Vision reply is missing page $name")
            }
            val byId = (result["bubbles"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .associateBy { it["id"]?.jsonPrimitive?.intOrNull }
            val kept = mutableListOf<TranslationBlock>()
            page.blocks.forEachIndexed { i, block ->
                val bubble = byId[i]
                val translation = bubble?.string("translation")
                when {
                    // Not returned at all: keep the OCR text so nothing silently disappears
                    bubble == null -> {
                        block.translation = block.text
                        kept += block
                    }
                    // Returned empty: the model judged it not to be real text
                    translation.isNullOrBlank() -> Unit
                    else -> {
                        bubble.string("text")?.takeIf { it.isNotBlank() }?.let { block.text = it }
                        block.translation = translation
                        kept += block
                    }
                }
            }
            val tiles = images[name]?.tiles.orEmpty()
            (result["missed"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { missed ->
                missedBlock(missed, page, tiles)?.let { kept += it }
            }
            kept.forEach { context.remember(it.translation) }
            page.blocks = kept.filterNot { it.translation.contains(WATERMARK) }.toMutableList()
        }
    }

    private fun JsonArrayBuilder.addBubble(id: Int, block: TranslationBlock, page: PageTranslation, tiles: List<PageImage.Tile>) {
        val centerY = block.y + block.height / 2
        val tileIndex = tiles.indexOfFirst { centerY < it.top + it.height }.takeIf { it >= 0 } ?: (tiles.size - 1)
        val tile = tiles.getOrNull(tileIndex)
        addJsonObject {
            put("id", id)
            put("ocr", block.text)
            if (tile != null && page.imgWidth > 0) {
                put("slice", tileIndex)
                putJsonArray("box_2d") {
                    add(norm(block.y - tile.top, tile.height))
                    add(norm(block.x, page.imgWidth))
                    add(norm(block.y + block.height - tile.top, tile.height))
                    add(norm(block.x + block.width, page.imgWidth))
                }
            }
        }
    }

    private fun norm(value: Float, size: Float): Int = (value / size * 1000).toInt().coerceIn(0, 1000)

    /** Builds a block for a bubble the OCR missed, from the model's 0–1000 box inside one slice. */
    private fun missedBlock(missed: JsonObject, page: PageTranslation, tiles: List<PageImage.Tile>): TranslationBlock? {
        val translation = missed.string("translation")?.takeIf { it.isNotBlank() } ?: return null
        val tile = tiles.getOrNull(missed["slice"]?.jsonPrimitive?.intOrNull ?: 0) ?: return null
        val box = (missed["box_2d"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.floatOrNull } ?: return null
        if (box.size != 4 || page.imgWidth <= 0) return null
        val (yMin, xMin, yMax, xMax) = box.map { it.coerceIn(0f, 1000f) / 1000f }
        val width = (xMax - xMin) * page.imgWidth
        val height = (yMax - yMin) * tile.height
        if (width < 4 || height < 4) return null
        val glyph = (page.imgWidth * 0.02f).coerceAtMost(height)
        return TranslationBlock(
            text = missed.string("text").orEmpty(),
            translation = translation,
            width = width,
            height = height,
            x = xMin * page.imgWidth,
            y = tile.top + yMin * tile.height,
            symHeight = glyph,
            symWidth = glyph,
            angle = 0f,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun ArrayDeque<String>.remember(line: String) {
        addLast(line)
        if (size > CONTEXT_LINES) removeFirst()
    }

    /** Small batches of whole pages so every slice stays within the model's image limits. */
    private fun visionBatches(pages: Map<String, PageTranslation>, images: Map<String, PageImage>): List<List<String>> {
        val result = mutableListOf<MutableList<String>>()
        var slices = 0
        for (name in pages.keys) {
            val count = images[name]?.tiles?.size ?: 0
            if (result.isEmpty() || result.last().size >= VISION_PAGES || slices + count > VISION_SLICES) {
                result += mutableListOf<String>()
                slices = 0
            }
            result.last() += name
            slices += count
        }
        return result
    }

    /** One-off JSON question to the configured model, used by features other than translation (e.g. AI search). */
    suspend fun askJson(system: String, prompt: String): JsonObject =
        withRetry { parse(request(system, listOf(Part.Text(prompt)))) }

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

    private fun systemPrompt(vision: Boolean): String = buildString {
        val target = toLang.label
        appendLine("You are an expert comic localizer (manga, manhwa, manhua) translating scanlations into $target.")
        if (vision) {
            appendLine("Source language: ${fromLang.label}. You can see the pages: each page is sent as one or more image slices,")
            appendLine("top to bottom, each preceded by a label. The JSON lists, per page, the bubbles found by OCR, with")
            appendLine("an id, the OCR text (it may be wrong, split or noise) and box_2d = [ymin, xmin, ymax, xmax] in 0-1000")
            appendLine("coordinates of its slice.")
            appendLine()
            appendLine("Reply with ONLY this JSON shape:")
            appendLine("{\"<page>\": {\"bubbles\": [{\"id\": 0, \"text\": \"<corrected source text>\", \"translation\": \"...\"}],")
            appendLine(" \"missed\": [{\"slice\": 0, \"box_2d\": [ymin, xmin, ymax, xmax], \"text\": \"...\", \"translation\": \"...\"}]}}")
            appendLine()
            appendLine("Rules:")
            appendLine("1. Return every OCR id. Read the image to correct the OCR text. If an entry is not real text (art, noise), set its translation to \"\".")
            appendLine("   In \"missed\", add only speech bubbles, captions or meaningful sound effects that the OCR did not list, with a tight box_2d in that slice.")
        } else {
            appendLine("Source language: ${fromLang.label}. Input is a JSON object: keys are page image names,")
            appendLine("values are the OCR'd speech bubbles of that page in reading order.")
            appendLine()
            appendLine("Rules:")
            appendLine("1. Reply with ONLY a JSON object with exactly the same keys, and the same number of strings per key in the same order.")
        }
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

    private sealed interface Part {
        class Text(val text: String) : Part
        class Image(val jpeg: ByteArray) : Part {
            val base64: String get() = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        }
    }

    private suspend fun request(system: String, parts: List<Part>): String = withContext(Dispatchers.IO) {
        val textOnly = parts.all { it is Part.Text }
        val (url, body, headers) = when (provider) {
            Provider.GEMINI -> Triple(
                "$baseUrl/models/$model:generateContent",
                buildJsonObject {
                    putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
                    putJsonArray("contents") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                parts.forEach { part ->
                                    addJsonObject {
                                        when (part) {
                                            is Part.Text -> put("text", part.text)
                                            is Part.Image -> putJsonObject("inline_data") {
                                                put("mime_type", "image/jpeg")
                                                put("data", part.base64)
                                            }
                                        }
                                    }
                                }
                            }
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
                            if (textOnly) {
                                put("content", parts.joinToString("\n") { (it as Part.Text).text })
                            } else {
                                putJsonArray("content") {
                                    parts.forEach { part ->
                                        addJsonObject {
                                            when (part) {
                                                is Part.Text -> {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                }
                                                is Part.Image -> {
                                                    put("type", "image_url")
                                                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,${part.base64}") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                buildMap {
                    if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                    if (provider == Provider.OPENROUTER) put("X-Title", "MANHWA")
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
                            putJsonArray("content") {
                                parts.forEach { part ->
                                    addJsonObject {
                                        when (part) {
                                            is Part.Text -> {
                                                put("type", "text")
                                                put("text", part.text)
                                            }
                                            is Part.Image -> {
                                                put("type", "image")
                                                putJsonObject("source") {
                                                    put("type", "base64")
                                                    put("media_type", "image/jpeg")
                                                    put("data", part.base64)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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
        const val VISION_PAGES = 3
        const val VISION_SLICES = 8
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

