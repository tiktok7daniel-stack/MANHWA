package eu.kanade.translation.collection

import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * MANHWA database optimizer: the team's strongest key reviews the most reused lines of the translation
 * collection, the way an editor would. Clumsy or literal lines are rewritten, the same term is made to read the
 * same everywhere (the master codex is its reference), and wrong ones are fixed. Only what it changes is saved,
 * and the reader's own corrections are never touched.
 */
object CollectionOptimizer {

    data class Result(val reviewed: Int, val improved: Int)

    suspend fun optimize(
        collection: TranslationCollection,
        src: String = "ENGLISH",
        tgt: String = "ARABIC",
        target: String = "Arabic",
    ): Result = withContext(Dispatchers.IO) {
        val lines = collection.reviewCandidates(src, tgt, BATCH)
        if (lines.isEmpty()) return@withContext Result(0, 0)
        val codex = ManhwaCodex.Pack.entries.flatMap { ManhwaCodex.terms(it).entries }.joinToString("; ") { "${it.key} = ${it.value}" }
        val reply = appAsk(AiRouter.Lane.REVIEW) { ai ->
            ai.askJson(
                system = """
                    You are the senior editor of a manhwa scanlation team translating into $target.
                    Below are saved lines of the team's translation memory (id | source | translation). Fix only what is
                    worth fixing: a wrong meaning, a literal or clumsy sentence, a term not following the reference terms,
                    wrong gender agreement, or an English word left untranslated. Keep placeholders such as {n0} or {name0}
                    exactly as they are. Keep the tone of comics (short, natural, spoken). Leave good lines out of the reply.
                    Reference terms: $codex
                    Reply with ONLY: {"fixes":[{"id":123,"translation":"..."}]}
                """.trimIndent(),
                prompt = lines.joinToString("\n") { "${it.id} | ${it.text.replace("\n", " ")} | ${it.translation.replace("\n", " ")}" },
            )
        }
        val byId = lines.associateBy { it.id }
        var improved = 0
        (reply["fixes"] as? JsonArray).orEmpty().forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            val text = (o["translation"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val old = byId[id] ?: return@forEach
            if (text.isEmpty() || text == old.translation) return@forEach
            // A placeholder lost on the way would break the line for every number and name
            val placeholders = Regex("\\{(?:n|name)\\d+\\}")
            if (placeholders.findAll(old.translation).map { it.value }.toSet() != placeholders.findAll(text).map { it.value }.toSet()) return@forEach
            collection.improve(id, text)
            improved++
        }
        Result(lines.size, improved)
    }

    private const val BATCH = 80
}
