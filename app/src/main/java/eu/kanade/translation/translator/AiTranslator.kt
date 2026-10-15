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
    /** Called when a retired model was replaced by a working one, so the choice can be saved. */
    private val onModelReplaced: (String) -> Unit = {},
) : TextTranslator {

    enum class Provider(val defaultModel: String, val defaultBaseUrl: String) {
        GEMINI("gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta"),
        OPENROUTER("google/gemini-2.5-flash", "https://openrouter.ai/api/v1"),
        OPENAI_COMPATIBLE("gpt-4o-mini", "https://api.openai.com/v1"),
        CLAUDE("claude-opus-5-5", "https://api.anthropic.com/v1"),
    }

    @Volatile private var model = model.ifBlank { provider.defaultModel }
    private val baseUrl = baseUrl.ifBlank { provider.defaultBaseUrl }.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** The series glossary (source term -> translation) every translation must follow. */
    var glossary: Map<String, String> = emptyMap()

    /** Hybrid mode: blocks already hold a fast machine draft the model should improve, not ignore. */
    var useDrafts: Boolean = false

    /** When set, the model also reports new names and terms, collected in [discoveredTerms]. */
    var learnTerms: Boolean = false

    /** New names and terms the model found, to be saved to the series glossary. */
    val discoveredTerms = LinkedHashMap<String, String>()

    /** MANHWA: what each glossary term is (kind, gender, how they speak), shown next to it. Empty: as before. */
    var glossaryNotes: Map<String, String> = emptyMap()

    /** MANHWA: ask new terms with their kind and gender too (collected in [discoveredDetails]). Off: as before. */
    var richTerms: Boolean = false

    /** MANHWA: kind and gender of the new terms, by source term. */
    val discoveredDetails = LinkedHashMap<String, TermInfo>()

    class TermInfo(val kind: String, val gender: String)

    /** MANHWA: what happened in the chapters before (a few lines), given as context. Empty: as before. */
    var storySoFar: String = ""

    /**
     * MANHWA: every bubble is sent with how many characters fit it, so translations come out the right length
     * instead of shrinking the text. Off: as before.
     */
    var fitHints: Boolean = false

    /** MANHWA: rules and corrections learnt from earlier chapters, to follow. Empty: as before. */
    var lessons: String = ""

    /** MANHWA: also ask a one-line summary of each batch, collected in [summaries]. Off: as before. */
    var askSummary: Boolean = false

    val summaries = mutableListOf<String>()

    /**
     * MANHWA team: a busy or failing call is handed back at once instead of being retried and moved to a
     * lighter model here, so the team can give the work to another key. Off: as before.
     */
    var fastFail: Boolean = false

    /**
     * MANHWA usage meter: every answer's status, headers (where the provider says how much is left) and,
     * for errors, the body. Unset: as before.
     */
    var onResponse: ((Int, okhttp3.Headers, String) -> Unit)? = null

    /** The model this translator uses now (it can change when a retired model is replaced). */
    val currentModel: String get() = model

    /**
     * MANHWA: progress for chapters translated from the manga screen: called after every batch with
     * (batches done, batches). Unset (the reader's live translation) nothing changes.
     */
    var onBatchDone: ((Int, Int) -> Unit)? = null

    /** MANHWA: called when a call failed and is about to be tried again, with what went wrong. */
    var onRetry: ((Throwable) -> Unit)? = null

    private fun collectTerms(output: JsonObject) {
        if (askSummary) {
            (output[SUMMARY_KEY] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { summaries += it.take(300) }
        }
        if (!learnTerms) return
        (output[TERMS_KEY] as? JsonObject)?.forEach { (source, value) ->
            // A plain translation, or (rich terms) {"t": translation, "kind": ..., "gender": ...}
            val details = value as? JsonObject
            val translation = (details?.get("t") ?: value).let { (it as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty()
            if (source.isNotBlank() && translation.isNotBlank() && glossary.keys.none { it.equals(source, ignoreCase = true) }) {
                discoveredTerms[source.trim()] = translation
                if (details != null) {
                    discoveredDetails[source.trim()] = TermInfo(
                        kind = (details["kind"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                        gender = (details["gender"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                    )
                }
            }
        }
    }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val context = ArrayDeque<String>()
        val all = batches(pages)
        for ((i, batch) in all.withIndex()) {
            translateTextBatch(batch, pages, context)
            onBatchDone?.invoke(i + 1, all.size)
        }
    }

    /**
     * Vision mode: the model sees each page together with the OCR draft, so it can fix misread text,
     * drop false detections, add bubbles the OCR missed and translate with the scene in mind.
     * A batch that fails in vision mode falls back to text-only translation.
     */
    override suspend fun translate(pages: MutableMap<String, PageTranslation>, images: Map<String, PageImage>) {
        if (images.isEmpty()) return translate(pages)
        val context = ArrayDeque<String>()
        val all = visionBatches(pages, images)
        for ((i, batch) in all.withIndex()) {
            try {
                translateVisionBatch(batch, pages, images, context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Vision translation failed, falling back to text for $batch" }
                translateTextBatch(batch.filter { pages[it]!!.blocks.isNotEmpty() }, pages, context)
            }
            onBatchDone?.invoke(i + 1, all.size)
        }
    }

    private suspend fun translateTextBatch(
        batch: List<String>,
        pages: MutableMap<String, PageTranslation>,
        context: ArrayDeque<String>,
    ) {
        if (batch.isEmpty()) return
        val input = buildJsonObject {
            batch.forEach { name ->
                putJsonArray(name) {
                    pages[name]!!.blocks.forEach {
                        add(if (useDrafts && it.translation.isNotBlank()) "${it.text} $DRAFT_MARK ${it.translation}" else it.text)
                    }
                }
            }
            if (fitHints) {
                putJsonObject(LIMITS_KEY) {
                    batch.forEach { name -> putJsonArray(name) { pages[name]!!.blocks.forEach { add(charBudget(it)) } } }
                }
            }
        }
        val output = withRetry { parse(request(systemPrompt(vision = false), listOf(Part.Text(userPrompt(input, context))))) }
        collectTerms(output)
        val kinds = output[KINDS_KEY] as? JsonObject
        for (name in batch) {
            val page = pages[name]!!
            val translated = (output[name] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
            val pageKinds = (kinds?.get(name) as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
            page.blocks.forEachIndexed { i, block ->
                block.translation = translated?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: block.text
                pageKinds?.getOrNull(i)?.let { block.kind = it }
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
        collectTerms(output)
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
                    // Returned empty: noise, or a piece the model joined into the bubble next to it. Kept
                    // without a translation so its lines are still erased with that bubble (PageOcr.finish)
                    translation.isNullOrBlank() -> {
                        block.translation = ""
                        kept += block
                    }
                    else -> {
                        bubble.string("text")?.takeIf { it.isNotBlank() }?.let { block.text = it }
                        block.translation = translation
                        bubble.string("kind")?.let { block.kind = it }
                        kept += block
                    }
                }
            }
            val tiles = images[name]?.tiles.orEmpty()
            (result["missed"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { missed ->
                missedBlock(missed, page, tiles)?.let { kept += it }
            }
            kept.filter { it.translation.isNotBlank() }.forEach { context.remember(it.translation) }
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
            if (useDrafts && block.translation.isNotBlank()) put("draft", block.translation)
            if (fitHints) put("max", charBudget(block))
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
            kind = missed.string("kind").orEmpty(),
        ).apply { needsSnap = true }
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

    /** MANHWA scout: a term found in a chapter before it is translated. */
    class ScoutTerm(val source: String, val translation: String, val kind: String, val gender: String, val note: String)

    /**
     * MANHWA scout: reads a whole chapter's text in one call before it is translated and lists its names and
     * terms (characters with gender and how they speak, skills, places, titles), so every key of the team uses
     * the same ones from the first page.
     */
    suspend fun scout(chapterText: String): List<ScoutTerm> {
        val system = buildString {
            appendLine("You prepare a ${fromLang.label} comic chapter for translation into ${toLang.label}. Reply with JSON only.")
            if (glossary.isNotEmpty()) {
                appendLine("Already known (do not repeat): " + glossary.keys.take(MAX_GLOSSARY).joinToString(", "))
            }
            if (storySoFar.isNotBlank()) appendLine("Story so far: ${storySoFar.trim()}")
        }
        val prompt = buildString {
            appendLine("List the NEW proper nouns and terms in this chapter: character names, skills, ranks, titles, sects, places,")
            appendLine("items. For characters give their gender when the text makes it clear and, in a few words, how they speak.")
            appendLine("Reply as {\"terms\": [{\"s\": \"<as written>\", \"t\": \"<translation>\", \"kind\":")
            appendLine("\"character|skill|place|title|group|item\", \"gender\": \"male|female|\", \"note\": \"<how they speak, or empty>\"}]}.")
            appendLine("At most 25 terms. Chapter text:")
            append(chapterText.take(SCOUT_MAX_CHARS))
        }
        val reply = withRetry { parse(request(system, listOf(Part.Text(prompt)))) }
        return (reply["terms"] as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val source = str("s")
            val translation = str("t")
            if (source.isBlank() || translation.isBlank()) null else ScoutTerm(source, translation, str("kind"), str("gender"), str("note").take(80))
        }
    }

    /**
     * MANHWA editor: reads translated lines ([id] to source and translation) and returns only the ones it
     * corrects (wrong meaning, a name not as in the glossary, a gender that does not match the character,
     * awkward wording), as id to the corrected translation.
     */
    suspend fun review(lines: Map<String, kotlin.Pair<String, String>>): Map<String, String> {
        if (lines.isEmpty()) return emptyMap()
        val system = buildString {
            appendLine("You are the final editor of a ${fromLang.label} comic translated into ${toLang.label}. Reply with JSON only.")
            if (glossary.isNotEmpty()) {
                appendLine("Series glossary (must be followed):")
                glossary.entries.take(MAX_GLOSSARY).forEach { (source, translation) ->
                    val note = glossaryNotes[source]
                    appendLine(if (note.isNullOrBlank()) "- $source => $translation" else "- $source => $translation ($note)")
                }
            }
            if (storySoFar.isNotBlank()) appendLine("Story so far: ${storySoFar.trim()}")
            if (lessons.isNotBlank()) appendLine("Learnt from earlier corrections (follow them):\n${lessons.trim()}")
            if (instructions.isNotBlank()) appendLine("Reader's instructions: ${instructions.trim()}")
        }
        val fixes = mutableMapOf<String, String>()
        for (chunk in lines.entries.chunked(REVIEW_LINES)) {
            val input = buildJsonObject {
                chunk.forEach { (id, pair) ->
                    putJsonObject(id) {
                        put("src", pair.first)
                        put("tr", pair.second)
                    }
                }
            }
            val prompt = buildString {
                appendLine("Check every translation against its source, in order (they are consecutive bubbles of the chapter).")
                appendLine("Fix only real problems: wrong meaning, names not as in the glossary, verbs/pronouns that do not match")
                appendLine("the character's gender, unnatural or too literal wording, text left untranslated. Keep good lines as they are.")
                appendLine("Reply as {\"fixes\": {\"<id>\": \"<corrected translation>\"}} with only the lines you changed ({} if none).")
                append(input.toString())
            }
            val reply = withRetry { parse(request(system, listOf(Part.Text(prompt)))) }
            (reply["fixes"] as? JsonObject)?.forEach { (id, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull?.trim()
                if (!text.isNullOrBlank() && id in lines) fixes[id] = text
            }
        }
        return fixes
    }

    /** Whether this engine can search the web (Gemini, Claude and OpenRouter can). */
    val canSearchWeb: Boolean get() = provider != Provider.OPENAI_COMPATIBLE

    /** Same as [askJson] but the model may search the web first. */
    suspend fun askJsonWithWeb(system: String, prompt: String): JsonObject =
        withRetry { parse(request(system, listOf(Part.Text(prompt)), web = true)) }

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
            appendLine("{\"<page>\": {\"bubbles\": [{\"id\": 0, \"text\": \"<corrected source text>\", \"translation\": \"...\", \"kind\": \"speech\"}],")
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
            appendLine("1. Reply with ONLY a JSON object with exactly the same page keys (plus \"$TERMS_KEY\" and \"$KINDS_KEY\" when asked), and the same number of strings per key in the same order.")
        }
        appendLine("0. kind of each bubble is one of: speech, shout (yelling, battle cries), thought (inner voice, whisper),")
        appendLine("   narration (captions), system (game/status windows, notifications), sfx (sound effects).")
        if (!vision) {
            appendLine("   In text mode add a top-level key \"$KINDS_KEY\" with the same page keys, listing the kind of every bubble in order.")
        }
        appendLine("2. Write natural, fluent $target the way a skilled human localizer would: keep each character's voice, tone and emotion, adapt idioms, and keep lines short enough to fit a speech bubble.")
        if (fitHints) {
            appendLine(
                if (vision) {
                    "   Each bubble has \"max\": about how many characters fit it. Stay within it when you can (shorter wording, same meaning)."
                } else {
                    "   \"$LIMITS_KEY\" is not text to translate: it gives, per page, about how many characters fit each bubble. Stay within it when you can (shorter wording, same meaning). Do not output it."
                },
            )
        }
        appendLine("3. OCR text can be noisy or split mid-sentence: use the neighbouring bubbles to recover the intended meaning and silently fix obvious OCR mistakes.")
        appendLine("4. Keep names of people, places, sects and techniques consistent across the chapter and with the glossary.")
        appendLine("5. Translate sound effects briefly; keep strings made only of punctuation or symbols unchanged.")
        appendLine("6. Replace watermarks, scanlation credits, site names and URLs with exactly \"$WATERMARK\".")
        if (toLang == TextTranslatorLanguage.ARABIC) {
            appendLine("7. Use clear Modern Standard Arabic suited to comics, with Arabic punctuation (، ؟ !).")
        }
        if (useDrafts) {
            appendLine()
            appendLine("Each bubble comes with a fast machine translation (after \"$DRAFT_MARK\" or in \"draft\"). It is often literal,")
            appendLine("wrong about gender, tone and names. Read the whole page first, understand who speaks to whom and the")
            appendLine("scene, then write the final translation: fix meaning and context, gender agreement (he/she, verb forms),")
            appendLine("register and the glossary. Never output the marker or the source text.")
        }
        if (glossary.isNotEmpty()) {
            appendLine()
            appendLine("Series glossary. Always translate these exactly like this:")
            glossary.entries.take(MAX_GLOSSARY).forEach { (source, translation) ->
                val note = glossaryNotes[source]
                appendLine(if (note.isNullOrBlank()) "- $source => $translation" else "- $source => $translation ($note)")
            }
            if (glossaryNotes.isNotEmpty()) {
                appendLine("Use each character's gender for every verb, pronoun and adjective that refers to them, and keep how they speak.")
            }
        }
        if (storySoFar.isNotBlank()) {
            appendLine()
            appendLine("Story so far (earlier chapters, for context only):")
            appendLine(storySoFar.trim())
        }
        if (lessons.isNotBlank()) {
            appendLine()
            appendLine("Learnt from corrections of earlier translations (always follow these):")
            appendLine(lessons.trim())
        }
        if (learnTerms) {
            appendLine()
            appendLine("Also add a top-level key \"$TERMS_KEY\" (next to the page keys) with up to 12 NEW proper nouns and terms")
            appendLine("from these pages that are not in the glossary: character names, skills, ranks, titles, sects, places,")
            if (richTerms) {
                appendLine("items. Format: {\"$TERMS_KEY\": {\"<source term as written>\": {\"t\": \"<your translation>\", \"kind\":")
                appendLine("\"character|skill|place|title|group|item\", \"gender\": \"male|female|\"}}}. Gender only for characters, when")
                appendLine("the pages make it clear. Use {} if none.")
            } else {
                appendLine("items. Format: {\"$TERMS_KEY\": {\"<source term as written>\": \"<your translation>\"}}. Use {} if none.")
            }
        }
        if (askSummary) {
            appendLine()
            appendLine("Also add a top-level key \"$SUMMARY_KEY\" with one short sentence in English: what happens in these pages")
            appendLine("(who, to whom, what). It is kept as context for the next chapters.")
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

    private suspend fun request(system: String, parts: List<Part>, web: Boolean = false): String = withContext(Dispatchers.IO) {
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
                        // JSON mode can't be combined with search grounding
                        if (!web) put("responseMimeType", "application/json")
                    }
                    if (web) putJsonArray("tools") { addJsonObject { putJsonObject("google_search") {} } }
                },
                mapOf("x-goog-api-key" to apiKey),
            )
            Provider.OPENROUTER, Provider.OPENAI_COMPATIBLE -> Triple(
                "$baseUrl/chat/completions",
                buildJsonObject {
                    put("model", if (web && provider == Provider.OPENROUTER) "$model:online" else model)
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
                    if (web) {
                        putJsonArray("tools") {
                            addJsonObject {
                                put("type", "web_search_20260209")
                                put("name", "web_search")
                                put("max_uses", 5)
                            }
                        }
                    }
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
            onResponse?.invoke(response.code, response.headers, if (response.isSuccessful) "" else text.take(500))
            if (!response.isSuccessful) throw ApiException(response.code, text.take(500))
            extractText(json.parseToJsonElement(text).jsonObject)
        }
    }

    private fun extractText(root: JsonObject): String = when (provider) {
        Provider.GEMINI -> {
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray)
                ?: throw IOException(
                    "Gemini returned no text (" +
                        ((candidate?.get("finishReason") as? JsonPrimitive)?.contentOrNull
                            ?: ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
                            ?: "empty reply") + ")",
                )
            parts.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
        }
        Provider.OPENROUTER, Provider.OPENAI_COMPATIBLE -> {
            val message = (((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject)
            (message?.get("content") as? JsonPrimitive)?.contentOrNull ?: throw IOException("The AI returned no text")
        }
        Provider.CLAUDE -> {
            val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
            if (stopReason == "refusal") throw IOException("Claude declined to translate this batch")
            ((root["content"] as? JsonArray) ?: throw IOException("Claude returned no text"))
                .filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text" }
                .joinToString("") { (it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        }
    }

    /** Pulls the JSON object out of the reply, tolerating markdown fences or stray prose around it. */
    private fun parse(reply: String): JsonObject {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("No JSON in model reply")
        return json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject
    }

    /**
     * When the provider says the model is gone (Google retires Gemini models for new keys), switches to the
     * model it recommends, or to the newest default, and reports it so the setting is updated.
     */
    private fun replaceRetiredModel(e: ApiException): Boolean {
        if (e.code != 404 && e.code != 400) return false
        val body = e.message.orEmpty()
        if (!body.contains("model", ignoreCase = true) ||
            !(body.contains("not found", ignoreCase = true) || body.contains("no longer available", ignoreCase = true) ||
                body.contains("NOT_FOUND") || body.contains("deprecated", ignoreCase = true))
        ) {
            return false
        }
        val suggested = Regex("""use (?:models/)?([A-Za-z0-9][A-Za-z0-9._]*-[A-Za-z0-9._\-]+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.trimEnd('.')
        val next = suggested?.takeIf { it != model } ?: provider.defaultModel.takeIf { it != model } ?: return false
        logcat(LogPriority.WARN) { "Model $model is retired, switching to $next" }
        model = next
        onModelReplaced(next)
        return true
    }

    /** Busy or overloaded model (503 / 429 / 5xx): try the next lighter model right away. */
    private fun switchToFallback(e: Exception): Boolean {
        if (e !is ApiException) return false
        val busy = e.code == 429 || e.code in 500..504 ||
            e.message.orEmpty().let { it.contains("overloaded", true) || it.contains("high demand", true) || it.contains("UNAVAILABLE") }
        if (!busy) return false
        val chain = FALLBACK_MODELS[provider].orEmpty()
        val next = chain.firstOrNull { it != model && it !in triedFallbacks } ?: return false
        triedFallbacks += model
        logcat(LogPriority.WARN) { "Model $model is busy (${e.code}), trying $next" }
        model = next
        return true
    }

    private val triedFallbacks = mutableSetOf<String>()

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var wait = 1_000L
        var replaced = 0
        triedFallbacks.clear()
        repeat(MAX_ATTEMPTS + 2) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e is ApiException && replaced < 2 && replaceRetiredModel(e)) {
                    replaced++
                    return@repeat
                }
                // MANHWA team: no waiting here, the team hands the work to another key at once
                if (fastFail) throw e
                if (switchToFallback(e)) return@repeat
                if (e is ApiException && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                logcat(LogPriority.WARN, e) { "AI translation attempt ${attempt + 1} failed, retrying" }
                if (e !is CancellationException) onRetry?.invoke(e)
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

    class ApiException(val code: Int, body: String) : IOException("HTTP $code: ${readable(body)}") {
        private companion object {
            /** The provider's own error message when the body is JSON, otherwise the body. */
            fun readable(body: String): String =
                Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)?.groupValues?.get(1)
                    ?.replace("\\n", " ")?.replace("\\\"", "\"")
                    ?: body
        }
    }

    private companion object {
        const val DRAFT_MARK = "|||"
        val FALLBACK_MODELS = mapOf(
            Provider.GEMINI to listOf("gemini-flash-latest", "gemini-flash-lite-latest"),
            Provider.OPENROUTER to listOf("openrouter/auto"),
        )
        const val TERMS_KEY = "_terms"
        const val SUMMARY_KEY = "_summary"
        const val SCOUT_MAX_CHARS = 12_000
        const val LIMITS_KEY = "_limits"

        /**
         * About how many characters of translation fit [block]: its bubble (or its text box) divided by the
         * size of one letter of the original, with room for line spacing.
         */
        fun charBudget(block: TranslationBlock): Int {
            val area = if (block.hasBubble) block.bubbleW * block.bubbleH * 0.7f else block.width * block.height * 1.25f
            val letter = (block.symWidth.coerceAtLeast(1f)) * (block.symHeight.coerceAtLeast(1f)) * 1.6f
            return (area / letter).toInt().coerceIn(MIN_BUDGET, MAX_BUDGET)
        }

        private const val MIN_BUDGET = 6
        private const val MAX_BUDGET = 400
        const val REVIEW_LINES = 120
        const val KINDS_KEY = "_kinds"
        const val MAX_GLOSSARY = 200
        const val WATERMARK = "RTMTH"
        const val BATCH_LINES = 120
        const val CONTEXT_LINES = 25
        const val MAX_ATTEMPTS = 4
        const val VISION_PAGES = 3
        const val VISION_SLICES = 8
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

