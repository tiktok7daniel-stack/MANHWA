package eu.kanade.translation.team

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.bubbleCharBudget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA second opinion: the lines that matter most or look doubtful (at most [MAX_LINES] a chapter) are
 * translated again by a DIFFERENT key, and each line keeps the better of the two translations by a score made
 * on the phone ([score]): following the glossary and the codex, no English left, fitting its bubble, passing the
 * quality check, and agreeing with a character's gender. Only lines the second key clearly does better change.
 */
object TranslationConsensusEngine {

    const val MAX_LINES = 12

    /** Points the second translation must win by to replace the first. */
    const val MARGIN = 5

    /** Choices offered to the reader per chapter at most. */
    const val MAX_TIES = 3

    data class Outcome(
        val reviewed: Int,
        val replaced: Int,
        val replacedLines: List<Pair<TranslationBlock, String>> = emptyList(),
        /** Lines where both keys did about as well: (source, kept, other), for the reader to choose. */
        val ties: List<Triple<String, String, String>> = emptyList(),
    )

    private val latinWord = Regex("(?<![A-Z])[A-Za-z][a-z'-]{3,}")
    private val femaleSource = Regex("\\b(she|her|hers|herself|girl|woman|lady|miss|madam|sister|mother|queen|princess)\\b", RegexOption.IGNORE_CASE)
    private val maleSource = Regex("\\b(he|him|his|himself|boy|man|sir|brother|father|king|prince)\\b", RegexOption.IGNORE_CASE)

    /**
     * How good [candidate] is as the translation of [source] (higher is better). [genders] is term to
     * "male"/"female" from the glossary; [budget] the letters its bubble holds (0 = no bubble).
     */
    fun score(
        source: String,
        candidate: String,
        glossary: Map<String, String>,
        genders: Map<String, String> = emptyMap(),
        budget: Int = 0,
        targetLatin: Boolean = false,
    ): Int {
        val tr = candidate.trim()
        if (tr.isEmpty()) return -100
        var points = 0
        points += if (QualityCheck.check(source, tr, glossary, targetLatin) == null) 30 else -20
        // The glossary and the codex: every term of the source written the agreed way
        glossary.forEach { (term, translated) ->
            if (term.length < 2 || translated.isBlank()) return@forEach
            if (Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
                points += if (tr.contains(translated, ignoreCase = true)) 10 else -10
            }
        }
        // English left in a translation written in another script
        if (!targetLatin) {
            val kept = glossary.values.filter { v -> v.any { it in 'a'..'z' || it in 'A'..'Z' } }
            val leaks = latinWord.findAll(tr).count { m -> kept.none { it.contains(m.value, ignoreCase = true) } }
            points -= leaks * 15
        }
        // It has to fit its bubble
        if (budget > 0) points += if (tr.length <= budget * 1.15f) 8 else -8
        // A woman spoken of in the source: feminine Arabic, and the other way round
        if (!targetLatin && tr.any { it in '؀'..'ۿ' }) {
            val female = femaleSource.containsMatchIn(source) || genders.any { (t, g) -> g.startsWith("f", true) && source.contains(t, true) }
            val male = maleSource.containsMatchIn(source) || genders.any { (t, g) -> g.startsWith("m", true) && source.contains(t, true) }
            if (female && !male) points += if (Regex("(?<!\\p{L})هي(?!\\p{L})|ها(?!\\p{L})|ت(?!\\p{L})").containsMatchIn(tr)) 4 else 0
            if (male && !female && Regex("(?<!\\p{L})هي(?!\\p{L})").containsMatchIn(tr) && !Regex("(?<!\\p{L})هو(?!\\p{L})").containsMatchIn(tr)) points -= 6
        }
        return points
    }

    /** The lines worth a second opinion: the doubtful ones first, then system windows and named lines. */
    fun pick(pages: Map<String, PageTranslation>, doubts: Collection<TranslationBlock>, newTerms: Set<String>): List<TranslationBlock> {
        val all = pages.values.flatMap { it.blocks }.filter { it.translation.isNotBlank() && it.text.isNotBlank() && !it.kind.equals("sfx", true) }
        val important = all.filter { b ->
            b.kind.contains("system", true) || b.kind.contains("window", true) ||
                newTerms.any { t -> t.length >= 2 && b.text.contains(t, ignoreCase = true) }
        }
        return (doubts.filter { it in all } + important).distinct().take(MAX_LINES)
    }

    /**
     * Asks a key other than [firstKeyId] for its own translation of [lines] and keeps, line by line, the better
     * one. Does nothing (and costs nothing) when the team has no second key.
     */
    suspend fun review(
        store: TeamStore,
        firstKeyId: String?,
        lines: List<TranslationBlock>,
        glossary: Map<String, String>,
        genders: Map<String, String>,
        title: String,
        targetLabel: String,
        targetLatin: Boolean,
    ): Outcome {
        if (lines.isEmpty()) return Outcome(0, 0)
        val others = store.active().filter { it.id != firstKeyId }
        if (others.isEmpty()) return Outcome(0, 0)
        val reply = runCatching {
            store.router.ask(AiRouter.Lane.REVIEW, prefer = { it.id != firstKeyId }) { ai ->
                ai.askJson(
                    system = """
                        You translate manhwa ("$title") into $targetLabel for a professional scanlation team.
                        Translate each numbered line naturally, short and spoken, keeping names as the glossary says.
                        ${if (glossary.isNotEmpty()) "Glossary: " + glossary.entries.take(60).joinToString("; ") { "${it.key} = ${it.value}" } else ""}
                        Reply with ONLY: {"t":["line 1 translation","line 2 translation", ...]} in the same order.
                    """.trimIndent(),
                    prompt = lines.withIndex().joinToString("\n") { (i, b) -> "${i + 1}. ${b.text.replace("\n", " ")}" },
                )
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Second opinion failed, keeping the first translation" } }.getOrNull()
            ?: return Outcome(0, 0)
        val alternatives = (reply["t"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val replaced = mutableListOf<Pair<TranslationBlock, String>>()
        val ties = mutableListOf<Triple<String, String, String>>()
        lines.forEachIndexed { i, block ->
            val alt = alternatives.getOrNull(i)?.trim().orEmpty()
            if (alt.isEmpty() || alt == block.translation) return@forEachIndexed
            val budget = if (block.hasBubble) bubbleCharBudget(block) else 0
            val first = score(block.text, block.translation, glossary, genders, budget, targetLatin)
            val second = score(block.text, alt, glossary, genders, budget, targetLatin)
            if (second >= first + MARGIN) {
                replaced += block to block.translation
                block.translation = alt
            } else if (kotlin.math.abs(second - first) < MARGIN) {
                ties += Triple(block.text, block.translation, alt)
            }
        }
        return Outcome(lines.size, replaced.size, replaced, ties.take(MAX_TIES))
    }
}
