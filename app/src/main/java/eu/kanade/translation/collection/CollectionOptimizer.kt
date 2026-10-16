package eu.kanade.translation.collection

import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicInteger

/**
 * MANHWA database optimizer: the team reviews the whole translation collection the way an editor would,
 * batch after batch, several batches at once on different keys. Clumsy or literal lines are rewritten, the same
 * term is made to read the same everywhere (the master codex terms of each batch are its reference), wrong
 * gender or number agreement is fixed. Only what changes is saved; the reader's own corrections and the
 * placeholders ({n0}, {name0}) are never touched.
 */
object CollectionOptimizer {

    data class Result(val reviewed: Int, val improved: Int)

    suspend fun optimize(
        collection: TranslationCollection,
        src: String = "ENGLISH",
        tgt: String = "ARABIC",
        target: String = "Arabic",
        maxLines: Int = MAX_LINES,
        onProgress: (reviewed: Int, improved: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val reviewed = AtomicInteger()
        val improved = AtomicInteger()
        var offset = 0
        while (offset < maxLines) {
            // A few batches at once: the router spreads them over the team's keys
            val batches = (0 until PARALLEL).map { k -> collection.reviewCandidates(src, tgt, BATCH, offset + k * BATCH) }
                .filter { it.isNotEmpty() }
            if (batches.isEmpty()) break
            coroutineScope {
                batches.map { lines ->
                    async {
                        val fixed = runCatching { reviewBatch(collection, lines, target) }
                            .onFailure { logcat(LogPriority.WARN, it) { "An optimizer batch failed" } }
                            .getOrDefault(0)
                        reviewed.addAndGet(lines.size)
                        improved.addAndGet(fixed)
                        onProgress(reviewed.get(), improved.get())
                    }
                }.awaitAll()
            }
            if (batches.size < PARALLEL || batches.any { it.size < BATCH }) break
            offset += PARALLEL * BATCH
        }
        Result(reviewed.get(), improved.get())
    }

    private suspend fun reviewBatch(collection: TranslationCollection, lines: List<TranslationCollection.Entry>, target: String): Int {
        val text = lines.joinToString("\n") { it.text }
        val reference = ManhwaCodex.termsIn(text, ManhwaCodex.defaultPacks).entries.joinToString("; ") { "${it.key} = ${it.value}" }
        val reply = appAsk(AiRouter.Lane.REVIEW) { ai ->
            ai.askJson(
                system = """
                    You are the senior editor of a manhwa scanlation team translating into $target.
                    Below are saved lines of the team's translation memory (id | source | translation). Fix only what is
                    worth fixing: a wrong meaning, a literal or clumsy sentence, a term not following the reference terms,
                    wrong gender agreement, a counted noun not agreeing with its number, or an English word left untranslated.
                    Keep placeholders such as {n0}, {name0} or §s§ exactly as they are. Keep the tone of comics (short,
                    natural, spoken). Leave good lines out of the reply.
                    ${if (reference.isNotEmpty()) "Reference terms: $reference" else ""}
                    Reply with ONLY: {"fixes":[{"id":123,"translation":"..."}]}
                """.trimIndent(),
                prompt = lines.joinToString("\n") { "${it.id} | ${it.text.replace("\n", " ")} | ${it.translation.replace("\n", " ")}" },
            )
        }
        val byId = lines.associateBy { it.id }
        val placeholders = Regex("\\{(?:n|name)\\d+\\}|§s§")
        var improved = 0
        (reply["fixes"] as? JsonArray).orEmpty().forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            val fixed = (o["translation"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val old = byId[id] ?: return@forEach
            if (fixed.isEmpty() || fixed == old.translation) return@forEach
            // A placeholder lost on the way would break the line for every number and name
            if (placeholders.findAll(old.translation).map { it.value }.toSet() != placeholders.findAll(fixed).map { it.value }.toSet()) return@forEach
            collection.improve(id, fixed)
            improved++
        }
        return improved
    }

    private const val BATCH = 60
    private const val PARALLEL = 3
    private const val MAX_LINES = 6_000
}
