package eu.kanade.translation.orchestra

import android.content.Context
import eu.kanade.translation.local.LocalAiEngine

/**
 * MANHWA orchestra judge: when two keys gave two good translations of one line, the model on the phone (the
 * judge model when the reader chose one) reads both with the series' terms and the reader's style and says
 * which reads better; with several judge models on the phone they all vote. Free: no key's quota is spent on judging.
 */
object LocalJudge {

    /** 1 for [a], 2 for [b], null when the judge did not answer clearly. */
    suspend fun pick(context: Context, source: String, a: String, b: String, style: String, glossary: Map<String, String>): Int? {
        val terms = glossary.entries.filter { source.contains(it.key, ignoreCase = true) }.take(12)
        val prompt = buildString {
            appendLine("You judge two Arabic translations of one manhwa line. Pick the one that is more faithful, natural and spoken.")
            if (terms.isNotEmpty()) appendLine("Required terms: " + terms.joinToString("; ") { "${it.key} = ${it.value}" })
            if (style.isNotBlank()) appendLine(style.take(600))
            appendLine("Line: $source")
            appendLine("1: $a")
            appendLine("2: $b")
            appendLine("Answer with only the number 1 or 2.")
        }
        // MANHWA phone ensemble: every judge model votes, the most votes win (the strongest breaks a tie)
        val judges = eu.kanade.translation.local.PhoneEnsemble.lineup(context)?.judges
            ?: listOfNotNull(LocalAiEngine.judgeFile(context))
        if (judges.isEmpty()) return LocalAiEngine.generate(context, prompt)?.let(JudgeRules::parse)
        val votes = judges.map { judge -> LocalAiEngine.generate(context, prompt, judge)?.let(JudgeRules::parse) }
        return eu.kanade.translation.local.EnsembleRules.vote(votes)
    }
}

/** Pure reading of the judge's answer (tested without a phone). */
object JudgeRules {
    fun parse(reply: String): Int? {
        val digits = reply.filter { it == '1' || it == '2' || it == '١' || it == '٢' }
        val first = digits.firstOrNull() ?: return null
        return if (first == '1' || first == '١') 1 else 2
    }
}
