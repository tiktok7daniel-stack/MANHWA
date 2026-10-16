package eu.kanade.translation.companion

import eu.kanade.translation.glossary.GlossaryStore
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAsk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tachiyomi.domain.manga.model.Manga
import java.util.Locale

/**
 * MANHWA smart recap: the story told up to a chapter the reader picks, never past it. It is built from the
 * chapter summaries the translator kept while translating, completed by what the team's AI knows of the
 * published story up to that chapter when a part was never translated here.
 */
object ChapterRecap {

    data class Recap(
        val story: String,
        val hero: String,
        val threads: List<String>,
        val characters: List<Pair<String, String>>,
        val fromSummaries: Int,
    )

    suspend fun recap(manga: Manga, upTo: Double, store: GlossaryStore): Recap {
        val summaries = store.story(manga.id)
            .filter { it.number < 0 || it.number <= upTo + 0.001 }
            .takeLast(MAX_SUMMARIES)
        val characters = store.notes(manga.id).entries.take(MAX_NOTES)
        val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
        val number = if (upTo % 1.0 == 0.0) upTo.toLong().toString() else upTo.toString()
        val reply = appAsk(AiRouter.Lane.CHAT) { ai ->
            ai.askJson(
                system = """
                    You are a passionate storyteller who knows manhwa, manga and manhua deeply.
                    Write a recap of the story UP TO AND INCLUDING chapter $number, in $language, vivid and well told,
                    like a "previously on" for a reader coming back. Absolutely nothing that happens after chapter $number:
                    no later events, twists, deaths, reveals or power ups. Use the chapter summaries given first; for parts
                    they don't cover, use what you know of the published story up to that chapter, and stay general when
                    unsure rather than inventing. Character names as in the notes when given.
                    Reply with ONLY: {"story":"4 to 8 short paragraphs separated by \n\n","hero":"where the main character stands now (power, goal, allies)","threads":["open mystery or conflict"],"characters":[{"name":"...","role":"one line"}]}
                """.trimIndent(),
                prompt = buildString {
                    appendLine("Title: ${manga.title}")
                    manga.genre?.takeIf { it.isNotEmpty() }?.let { appendLine("Genres: ${it.joinToString(", ")}") }
                    manga.description?.takeIf { it.isNotBlank() }?.let { appendLine("Synopsis: ${it.take(DESCRIPTION_CHARS)}") }
                    if (characters.isNotEmpty()) {
                        appendLine("Character notes:")
                        characters.forEach { (name, note) -> appendLine("- $name: $note") }
                    }
                    if (summaries.isNotEmpty()) {
                        appendLine("Chapter summaries (oldest first):")
                        summaries.forEach { appendLine("- ${it.chapter}: ${it.summary}") }
                    } else {
                        appendLine("No chapter summaries: use what you know of the story up to chapter $number.")
                    }
                },
            )
        }
        return Recap(
            story = reply.string("story"),
            hero = reply.string("hero"),
            threads = (reply["threads"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) },
            characters = (reply["characters"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = obj.string("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                name to obj.string("role")
            },
            fromSummaries = summaries.size,
        )
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private const val MAX_SUMMARIES = 80
    private const val MAX_NOTES = 30
    private const val DESCRIPTION_CHARS = 600
}
