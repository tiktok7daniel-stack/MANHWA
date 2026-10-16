package eu.kanade.translation.local

import android.content.Context
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.GoogleTranslator
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * MANHWA on-device translator: the page's bubbles translated by the model on the phone ([LocalAiEngine]), a few
 * lines at a time, following the series glossary and the reader's style. Any line the model leaves out or gets
 * wrong is done by Google, so a page always ends translated.
 */
class LocalAiTranslator(
    private val context: Context,
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val instructions: String = "",
    var glossary: Map<String, String> = emptyMap(),
    /** The phone model that answers (null: the main one). */
    private val model: java.io.File? = null,
) : TextTranslator {

    private val fallback = GoogleTranslator(fromLang, toLang)

    /** Called after every few lines, so the chapter shows it is moving (done, total steps). */
    var onBatchDone: ((Int, Int) -> Unit)? = null

    /** True when the last draft stopped early because the phone was too slow (see [draftLines]). */
    @Volatile var slow = false
        private set
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val blocks = pages.values.flatMap { it.blocks }.filter { it.text.isNotBlank() }
        // MANHWA phone ensemble with no key: the quick model drafts, the strong model rereads every line
        val lineup = if (model == null) PhoneEnsemble.lineup(context) else null
        if (lineup != null && lineup.drafter != lineup.polisher) {
            val drafter = LocalAiTranslator(context, fromLang, toLang, instructions, glossary, lineup.drafter)
            val steps = (blocks.size + LINES_PER_CALL - 1) / LINES_PER_CALL * 2
            val drafts = runCatching { drafter.draftLines(blocks.map { it.text }) { n -> onBatchDone?.invoke(n, steps) } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (drafts != null) {
                val polisher = LocalAiTranslator(context, fromLang, toLang, instructions, glossary, lineup.polisher)
                val polished = runCatching { polisher.polishLines(blocks.map { it.text }, drafts) { n -> onBatchDone?.invoke(steps / 2 + n, steps) } }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(drafts)
                val left = mutableListOf<TranslationBlock>()
                blocks.forEachIndexed { i, b ->
                    val t = polished.getOrNull(i) ?: drafts.getOrNull(i)
                    if (t != null) {
                        b.translation = t
                        b.by = "local"
                    } else {
                        left += b
                    }
                }
                left.forEach { b -> b.translation = runCatching { fallback.translateText(b.text.replace('\n', ' ')) }.getOrDefault("").ifBlank { b.text } }
                return
            }
        }
        val missing = mutableListOf<Int>()
        slow = false
        val chunks = (blocks.size + LINES_PER_CALL - 1) / LINES_PER_CALL
        blocks.chunked(LINES_PER_CALL).forEachIndexed { chunkIndex, chunk ->
            onBatchDone?.invoke(chunkIndex, chunks)
            // A phone too slow for one call is not asked again in this chapter: Google does the rest
            val started = System.currentTimeMillis()
            val answers = if (slow) {
                null
            } else {
                runCatching { translateLines(chunk.map { it.text.replace('\n', ' ') }) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }
            if (answers == null && System.currentTimeMillis() - started >= LocalAiEngine.CALL_TIMEOUT_MS - 1_000) slow = true
            chunk.forEachIndexed { i, block ->
                val t = answers?.getOrNull(i)?.trim().orEmpty()
                if (LocalAiRules.usable(block.text, t, toLang.code)) {
                    block.translation = t
                    block.by = "local"
                } else {
                    missing += chunkIndex * LINES_PER_CALL + i
                }
            }
        }
        if (missing.isEmpty()) return
        // What the model could not do: Google, line by line
        val rest = missing.mapNotNull { blocks.getOrNull(it) }
        rest.forEach { b -> b.translation = runCatching { fallback.translateText(b.text.replace('\n', ' ')) }.getOrDefault("").ifBlank { b.text } }
    }

    /**
     * MANHWA orchestra: the phone's draft of every line (null where it has none worth keeping), without the
     * Google fallback: the team's keys do what the phone cannot.
     */
    suspend fun draftLines(lines: List<String>, deadline: Long = Long.MAX_VALUE, onStep: ((Int) -> Unit)? = null): List<String?> {
        val out = ArrayList<String?>(lines.size)
        slow = false
        lines.chunked(LINES_PER_CALL).forEachIndexed { n, chunk ->
            onStep?.invoke(n)
            // Out of time (or the phone proved too slow): the rest has no draft, the keys do it from the source
            if (slow || System.currentTimeMillis() >= deadline) {
                repeat(chunk.size) { out += null }
                return@forEachIndexed
            }
            val started = System.currentTimeMillis()
            val answers = runCatching { translateLines(chunk.map { it.replace('\n', ' ') }) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (answers == null && System.currentTimeMillis() - started >= LocalAiEngine.CALL_TIMEOUT_MS - 1_000) slow = true
            chunk.forEachIndexed { i, src ->
                val t = answers?.getOrNull(i)?.trim().orEmpty()
                out += t.takeIf { LocalAiRules.usable(src, it, toLang.code) }
            }
        }
        return out
    }

    /**
     * MANHWA phone ensemble: this model rereads [drafts] of [lines] (another model's) and returns the better line
     * for each: its own rewrite when it is usable, else the draft (null where neither is).
     */
    suspend fun polishLines(lines: List<String>, drafts: List<String?>, deadline: Long = Long.MAX_VALUE, onStep: ((Int) -> Unit)? = null): List<String?> {
        val out = ArrayList<String?>(lines.size)
        lines.indices.chunked(LINES_PER_CALL).forEachIndexed { n, idx ->
            onStep?.invoke(n)
            if (System.currentTimeMillis() >= deadline) {
                idx.forEach { out += drafts.getOrNull(it) }
                return@forEachIndexed
            }
            val terms = glossary.entries.filter { (k, _) -> idx.any { lines[it].contains(k, ignoreCase = true) } }.take(30)
            val prompt = buildString {
                appendLine("You review draft ${toLang.label} translations of manhwa speech bubbles. Fix meaning errors, unnatural wording and wrong names; keep good lines as they are. Short, natural, spoken.")
                if (terms.isNotEmpty()) appendLine("Names and terms: " + terms.joinToString("; ") { "${it.key} = ${it.value}" })
                if (instructions.isNotBlank()) appendLine(instructions.take(800))
                appendLine("Reply with ONLY a JSON object: {\"t\":[\"...\"]} with ${idx.size} items in order.")
                idx.forEachIndexed { n, i ->
                    appendLine("${n + 1}. ${lines[i].replace('\n', ' ').take(300)}")
                    appendLine("   draft: ${drafts.getOrNull(i).orEmpty().take(300)}")
                }
            }
            val answers = runCatching { LocalAiEngine.generate(context, prompt, model)?.let { LocalAiRules.parse(it, json) } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            idx.forEachIndexed { n, i ->
                val t = answers?.getOrNull(n)?.trim().orEmpty()
                out += t.takeIf { LocalAiRules.usable(lines[i], it, toLang.code) } ?: drafts.getOrNull(i)
            }
        }
        return out
    }

    private suspend fun translateLines(lines: List<String>): List<String>? {
        val terms = glossary.entries.filter { (k, _) -> lines.any { it.contains(k, ignoreCase = true) } }.take(30)
        val prompt = buildString {
            appendLine("You translate manhwa speech bubbles into ${toLang.label}. Short, natural, spoken. Keep names as given.")
            if (terms.isNotEmpty()) appendLine("Names and terms: " + terms.joinToString("; ") { "${it.key} = ${it.value}" })
            if (instructions.isNotBlank()) appendLine(instructions.take(800))
            // What the big models corrected before: the phone writes like them
            val examples = eu.kanade.translation.orchestra.OrchestraStore.examplesFor(context, lines)
            if (examples.isNotEmpty()) {
                appendLine("Write like these corrected examples:")
                examples.forEach { appendLine("\"${it.source.take(100)}\" -> \"${it.final.take(100)}\"") }
            }
            appendLine("Translate each numbered line. Reply with ONLY a JSON object: {\"t\":[\"...\",\"...\"]} with ${lines.size} items in order.")
            lines.forEachIndexed { i, l -> appendLine("${i + 1}. ${l.take(300)}") }
        }
        val reply = LocalAiEngine.generate(context, prompt, model) ?: return null
        return LocalAiRules.parse(reply, json)
    }

    override fun close() = Unit

    private companion object {
        const val LINES_PER_CALL = 8
    }
}

/** The pure rules of the on-device translator (tested without a phone). */
object LocalAiRules {

    /** The "t" list of the model's reply, wherever the JSON sits in it. */
    fun parse(reply: String, json: Json = Json { ignoreUnknownKeys = true; isLenient = true }): List<String>? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        return (root["t"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }

    /** A translation worth keeping: not empty, not the source again, in Arabic letters when the target is Arabic. */
    fun usable(source: String, translation: String, targetCode: String): Boolean {
        if (translation.isBlank()) return false
        if (translation.equals(source.trim(), ignoreCase = true) && source.any { it.isLetter() }) return false
        if (targetCode == "ar") {
            val letters = translation.filter { it.isLetter() }
            if (letters.isEmpty()) return true
            return letters.count { it in '؀'..'ۿ' } * 2 >= letters.length
        }
        return true
    }
}
