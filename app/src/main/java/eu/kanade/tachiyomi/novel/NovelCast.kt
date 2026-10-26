package eu.kanade.tachiyomi.novel

import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAiConfigured
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA novels: who speaks in each paragraph of a chapter, a man or a woman, so the narration voice hands over to
 * the right character voice. The words of the paragraph tell it first ("قالت" against "قال", "she said" against
 * "he said"); when the AI team is set up it reads the whole chapter once and settles the rest. A chapter's answer
 * is kept for this run of the app.
 */
object NovelCast {

    private const val MAX_CHARS = 14_000
    private val cache = HashMap<String, Map<Int, Role>>()

    private val FEMALE = Regex("(?<!\\p{L})[وف]?(قالت|صرخت|همست|أجابت|اجابت|ردت|ردّت|سألت|تمتمت|ضحكت|صاحت|she said|she asked|she whispered|she shouted|she replied)(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val MALE = Regex("(?<!\\p{L})[وف]?(قال|صرخ|همس|أجاب|اجاب|رد|ردّ|سأل|تمتم|ضحك|صاح|he said|he asked|he whispered|he shouted|he replied)(?!\\p{L})", RegexOption.IGNORE_CASE)

    /** The speaker of each paragraph that has speech, by the words around it alone. */
    fun guess(paragraphs: List<String>): Map<Int, Role> = buildMap {
        paragraphs.forEachIndexed { i, p ->
            if (!NovelSpeaker.hasSpeech(p)) return@forEachIndexed
            when {
                FEMALE.containsMatchIn(p) -> put(i, Role.FEMALE)
                MALE.containsMatchIn(p) -> put(i, Role.MALE)
            }
        }
    }

    /** The speaker of each paragraph with speech: the words first, then the AI for the whole chapter when it can. */
    suspend fun cast(chapterKey: String, paragraphs: List<String>): Map<Int, Role> {
        cache[chapterKey]?.let { return it }
        val guessed = guess(paragraphs)
        if (!appAiConfigured()) return guessed
        val speaking = paragraphs.indices.filter { NovelSpeaker.hasSpeech(paragraphs[it]) }
        if (speaking.isEmpty()) return guessed
        val result = try {
            val numbered = buildString {
                for ((i, p) in paragraphs.withIndex()) {
                    if (length > MAX_CHARS) break
                    append(i).append(": ").append(p).append('\n')
                }
            }
            val answer: JsonObject = appAsk(AiRouter.Lane.BACKGROUND) { ai ->
                ai.askJson(
                    "You read a novel chapter and say who speaks in each paragraph that has dialogue: a male or a " +
                        "female character. Reply with ONLY this JSON: {\"g\": {\"paragraph number\": \"m\" or \"f\"}}, " +
                        "for the paragraphs with dialogue only.",
                    numbered,
                )
            }
            val g = answer["g"] as? JsonObject
            guessed + speaking.mapNotNull { i ->
                when ((g?.get(i.toString()) as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                    "f" -> i to Role.FEMALE
                    "m" -> i to Role.MALE
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Chapter speakers not read by the AI" }
            guessed
        }
        cache[chapterKey] = result
        return result
    }
}
