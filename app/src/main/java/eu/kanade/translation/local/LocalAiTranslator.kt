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
    /** How the model is asked (the speed test also runs the old way, to compare). */
    private val tuning: LocalAiRules.Tuning = LocalAiRules.Tuning.FAST,
) : TextTranslator {

    private val batch: Int get() = tuning.linesPerCall

    private val fallback = GoogleTranslator(fromLang, toLang)

    /** MANHWA: the last lines this translator did, given to the next call so pronouns and tone carry on. */
    private var recent: List<Pair<String, String>> = emptyList()

    /** The model's line finished like every engine's: clean, the glossary's names, polished Arabic. */
    private fun finish(translation: String, source: String): String {
        val clean = eu.kanade.translation.translator.TextSanitizer.clean(translation, source, toLang)
        if (toLang != TextTranslatorLanguage.ARABIC) return clean
        val repaired = eu.kanade.translation.translator.LeakRepair.repair(clean, glossary)
        val named = eu.kanade.translation.glossary.GlossaryEnforcer.fix(source, repaired, glossary)
        return eu.kanade.translation.translator.ArabicPolisher.polish(named)
    }

    /** Called after every few lines, so the chapter shows it is moving (done, total steps). */
    var onBatchDone: ((Int, Int) -> Unit)? = null

    /** True when the last draft stopped early because the phone was too slow (see [draftLines]). */
    @Volatile var slow = false
        private set
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val blocks = pages.values.flatMap { it.blocks }.filter { it.text.isNotBlank() }
        // MANHWA phone ensemble with no key: the quick model drafts, the strong model rereads the doubtful lines
        val lineup = if (model == null) PhoneEnsemble.lineup(context) else null
        if (lineup != null && lineup.drafter != lineup.polisher) {
            // The second model loads while the first one drafts
            LocalAiEngine.warm(context, lineup.polisher)
            val drafter = LocalAiTranslator(context, fromLang, toLang, instructions, glossary, lineup.drafter, tuning)
            val steps = (blocks.size + batch - 1) / batch * 2
            val drafts = runCatching { drafter.draftLines(blocks.map { it.text }) { n -> onBatchDone?.invoke(n, steps) } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (drafts != null) {
                val polisher = LocalAiTranslator(context, fromLang, toLang, instructions, glossary, lineup.polisher, tuning)
                // MANHWA speed first: only the lines the checks doubt are read again (a good draft costs nothing more)
                val sources = blocks.map { it.text }
                val doubtful = sources.indices.filter { i -> LocalAiRules.doubtful(sources[i], drafts.getOrNull(i), glossary, toLang.code) }
                val polished = drafts.toMutableList()
                if (doubtful.isNotEmpty()) {
                    val again = runCatching {
                        polisher.polishLines(doubtful.map { sources[it] }, doubtful.map { drafts[it] }) { n -> onBatchDone?.invoke(steps / 2 + n, steps) }
                    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                    again?.forEachIndexed { n, t -> if (t != null) polished[doubtful[n]] = t }
                }
                val left = mutableListOf<TranslationBlock>()
                blocks.forEachIndexed { i, b ->
                    val t = polished.getOrNull(i) ?: drafts.getOrNull(i)
                    if (t != null) {
                        b.translation = finish(t, b.text)
                        b.by = "local"
                    } else {
                        left += b
                    }
                }
                left.forEach { b -> b.translation = runCatching { fallback.translateText(b.text.replace('\n', ' ')) }.getOrDefault("").ifBlank { b.text } }
                return
            }
        }
        LocalAiEngine.warm(context, model)
        val missing = mutableListOf<Int>()
        slow = false
        val chunks = (blocks.size + batch - 1) / batch
        blocks.chunked(batch).forEachIndexed { chunkIndex, chunk ->
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
            if (answers == null && System.currentTimeMillis() - started >= LocalAiRules.timeoutFor(chunk.size) - 1_000) slow = true
            chunk.forEachIndexed { i, block ->
                val t = answers?.getOrNull(i)?.trim().orEmpty()
                if (LocalAiRules.usable(block.text, t, toLang.code)) {
                    block.translation = finish(t, block.text)
                    block.by = "local"
                } else {
                    missing += chunkIndex * batch + i
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
        lines.chunked(batch).forEachIndexed { n, chunk ->
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
            if (answers == null && System.currentTimeMillis() - started >= LocalAiRules.timeoutFor(chunk.size) - 1_000) slow = true
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
        lines.indices.chunked(batch).forEachIndexed { n, idx ->
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
                appendLine(LocalAiRules.replyRule(tuning, idx.size))
                idx.forEachIndexed { n, i ->
                    appendLine("${n + 1}. ${lines[i].replace('\n', ' ').take(300)}")
                    appendLine("   draft: ${drafts.getOrNull(i).orEmpty().take(300)}")
                }
            }
            val answers = runCatching {
                LocalAiEngine.generate(context, prompt, model, LocalAiRules.timeoutFor(idx.size))?.let { LocalAiRules.answers(it, idx.size, json) }
            }
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
            if (toLang == TextTranslatorLanguage.ARABIC) {
                appendLine("Arabic: verbs and pronouns agree with who speaks and who is spoken to (man or woman), from the names and the lines before.")
            }
            if (terms.isNotEmpty()) appendLine("Names and terms: " + terms.joinToString("; ") { "${it.key} = ${it.value}" })
            if (instructions.isNotBlank()) appendLine(instructions.take(800))
            // What the big models corrected before: the phone writes like them
            val examples = eu.kanade.translation.orchestra.OrchestraStore.examplesFor(context, lines)
            if (examples.isNotEmpty()) {
                appendLine("Write like these corrected examples:")
                examples.forEach { appendLine("\"${it.source.take(100)}\" -> \"${it.final.take(100)}\"") }
            }
            // The lines just before, so "you", "he" and "she" keep pointing at the same people
            if (recent.isNotEmpty()) {
                appendLine("Just before (context only, do not translate again):")
                recent.forEach { (s, t) -> appendLine("\"${s.take(100)}\" -> \"${t.take(100)}\"") }
            }
            appendLine("Translate each numbered line. " + LocalAiRules.replyRule(tuning, lines.size))
            lines.forEachIndexed { i, l -> appendLine("${i + 1}. ${l.take(300)}") }
        }
        val reply = LocalAiEngine.generate(context, prompt, model, LocalAiRules.timeoutFor(lines.size), tuning.backend) ?: return null
        val answers = LocalAiRules.answers(reply, lines.size, json) ?: return null
        recent = (recent + lines.zip(answers).filter { it.second.isNotBlank() }).takeLast(RECENT_LINES)
        return answers
    }

    /**
     * MANHWA speed test: [lines] translated by this model alone and finished (no Google for what it misses);
     * null for a line it gave nothing usable for, or all null when the call failed.
     */
    suspend fun benchLines(lines: List<String>): List<String?>? {
        val answers = runCatching { translateLines(lines) }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return null
        return lines.mapIndexed { i, src ->
            answers.getOrNull(i)?.trim()?.takeIf { LocalAiRules.usable(src, it, toLang.code) }?.let { finish(it, src) }
        }
    }

    override fun close() = Unit

    private companion object {

        /** Few: a small model reads every word of its prompt before it answers. */
        const val RECENT_LINES = 4
    }
}

/** The pure rules of the on-device translator (tested without a phone). */
object LocalAiRules {

    /**
     * How the phone model is asked. [FAST] (the default): the answer as numbered lines (no JSON quotes, commas and
     * escapes: fewer tokens to write, and writing is what a phone model is slow at), 12 lines a call (the prompt is
     * read once for more lines). [LEGACY]: the way it was asked before (JSON, 8 lines), kept for the speed test.
     */
    data class Tuning(
        val numbered: Boolean,
        val linesPerCall: Int,
        val backend: com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend? = null,
    ) {
        companion object {
            val FAST = Tuning(numbered = true, linesPerCall = 12)
            val LEGACY = Tuning(numbered = false, linesPerCall = 8, backend = com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend.CPU)
        }
    }

    fun replyRule(tuning: Tuning, count: Int): String =
        if (tuning.numbered) {
            "Reply with ONLY the $count translations, one per line, numbered like the input (1. ...), nothing else."
        } else {
            "Reply with ONLY a JSON object: {\"t\":[\"...\",\"...\"]} with $count items in order."
        }

    /** The [count] answers of a reply, numbered lines or the JSON list, whichever the model wrote. */
    fun answers(reply: String, count: Int, json: Json = Json { ignoreUnknownKeys = true; isLenient = true }): List<String>? =
        parseNumbered(reply, count) ?: parse(reply, json)

    private val numberedLine = Regex("^\\s*(\\d{1,3})\\s*[.)\\-:،]\\s*(.*)$")

    /** "1. ...", "2) ..." lines, in their places (a missing number is an empty answer); null when there are none. */
    fun parseNumbered(reply: String, count: Int): List<String>? {
        val found = HashMap<Int, String>()
        reply.lines().forEach { line ->
            val m = numberedLine.find(line) ?: return@forEach
            val n = m.groupValues[1].toInt()
            if (n in 1..count && n !in found) found[n] = m.groupValues[2].trim().trim('"', '“', '”')
        }
        if (found.isEmpty()) return null
        return (1..count).map { found[it].orEmpty() }
    }

    /** Time for one call: loading aside, a phone writes about 5 to 15 tokens a second, a line takes about 25. */
    fun timeoutFor(lines: Int): Long = (BASE_TIMEOUT_MS + lines * PER_LINE_MS).coerceAtMost(MAX_TIMEOUT_MS)

    /**
     * A draft worth a second look: none, unusable, English words left in it, a name of the glossary the source
     * has but the draft lost, or far longer than the line.
     */
    fun doubtful(source: String, draft: String?, glossary: Map<String, String>, targetCode: String): Boolean {
        if (draft == null || !usable(source, draft, targetCode)) return true
        if (targetCode == "ar" && Regex("[A-Za-z]{3,}").findAll(draft).any { w -> w.value.any(Char::isLowerCase) }) return true
        val lost = glossary.entries.any { (term, arabic) ->
            arabic.isNotBlank() && term.length >= 3 && source.contains(term, ignoreCase = true) && !draft.contains(arabic)
        }
        if (lost) return true
        return draft.length > source.length * 3 + 25
    }

    /** The value below which [share] of [values] fall (p50 = 0.5, p95 = 0.95), nearest rank. */
    fun percentile(values: List<Long>, share: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val rank = kotlin.math.ceil(share * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /**
     * chrF (character n-gram F-score, n = 1..6, recall weighted twice): how close a translation is to the right
     * one, 0..100. The usual score for Arabic, where a word takes many forms.
     */
    fun chrF(hypothesis: String, reference: String, maxN: Int = 6, beta: Double = 2.0): Double {
        val h = hypothesis.replace(Regex("\\s+"), " ").trim()
        val r = reference.replace(Regex("\\s+"), " ").trim()
        if (h.isEmpty() || r.isEmpty()) return 0.0
        var precision = 0.0
        var recall = 0.0
        var orders = 0
        for (n in 1..maxN) {
            val hg = grams(h, n)
            val rg = grams(r, n)
            if (hg.isEmpty() || rg.isEmpty()) continue
            val common = hg.entries.sumOf { (g, c) -> minOf(c, rg[g] ?: 0) }.toDouble()
            precision += common / hg.values.sum()
            recall += common / rg.values.sum()
            orders++
        }
        if (orders == 0) return 0.0
        precision /= orders
        recall /= orders
        if (precision + recall == 0.0) return 0.0
        val b2 = beta * beta
        return 100 * (1 + b2) * precision * recall / (b2 * precision + recall)
    }

    private fun grams(text: String, n: Int): Map<String, Int> {
        val t = text.replace(" ", "")
        if (t.length < n) return emptyMap()
        val out = HashMap<String, Int>()
        for (i in 0..t.length - n) out.merge(t.substring(i, i + n), 1, Int::plus)
        return out
    }

    private const val BASE_TIMEOUT_MS = 20_000L
    private const val PER_LINE_MS = 6_000L
    private const val MAX_TIMEOUT_MS = 120_000L

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
