package eu.kanade.translation.companion

import android.content.Context
import eu.kanade.translation.glossary.GlossaryStore
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAiConfigured
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.Locale

/**
 * MANHWA story map: the key events of a work on a timeline and who is who to whom, only up to the last chapter read
 * (never a spoiler). It works for every work: the chapter summaries the translator kept come first; a work read
 * already in Arabic (or never translated here) is mapped from what the team's AI knows of the published story up
 * to that chapter, with the cast AniList lists to anchor the names. The map is made once per new chapter read and
 * kept, so opening it again costs nothing.
 */
object StoryMap {

    enum class EventType { BATTLE, DEATH, REVEAL, POWER, MEET, TURN, OTHER }

    enum class RelationType { ENEMY, ALLY, FAMILY, LOVE, RIVAL, MASTER, OTHER }

    @Serializable
    data class Event(val chapter: String, val number: Double, val type: String, val title: String, val text: String) {
        val kind: EventType get() = runCatching { EventType.valueOf(type.uppercase()) }.getOrDefault(EventType.OTHER)
    }

    @Serializable
    data class Relation(val a: String, val b: String, val type: String, val note: String = "") {
        val kind: RelationType get() = runCatching { RelationType.valueOf(type.uppercase()) }.getOrDefault(RelationType.OTHER)
    }

    @Serializable
    data class Map(val upTo: Double, val events: List<Event>, val relations: List<Relation>, val fromAi: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context, mangaId: Long) = File(context.filesDir, "manhwa_storymap/$mangaId.json")

    /** Drops the kept map, so the next load makes it again. */
    fun forget(context: Context, mangaId: Long) {
        file(context, mangaId).delete()
    }

    fun cached(context: Context, mangaId: Long): Map? =
        runCatching { json.decodeFromString(Map.serializer(), file(context, mangaId).readText()) }.getOrNull()

    /**
     * The map up to chapter [upTo]. Reuses the kept one when nothing new was read; asks the AI when [useAi] and a
     * key is set; otherwise the summaries themselves are the timeline.
     */
    suspend fun load(
        context: Context,
        mangaId: Long,
        title: String,
        upTo: Double,
        store: GlossaryStore,
        useAi: Boolean,
        description: String? = null,
        cast: List<String> = emptyList(),
    ): Map =
        withContext(Dispatchers.IO) {
            val summaries = store.story(mangaId).filter { it.number < 0 || it.number <= upTo + 0.001 }.sortedBy { it.number }
            val old = cached(context, mangaId)
            if (old != null && old.upTo >= upTo && (old.fromAi || !useAi)) return@withContext old
            val plain = Map(
                upTo = upTo,
                events = summaries.map { Event(it.chapter, it.number, "OTHER", it.chapter, it.summary) },
                relations = old?.relations.orEmpty(),
                fromAi = false,
            )
            // Without summaries the AI tells the published story itself, so any work read past chapter 1 has a map
            if (!useAi || !appAiConfigured() || (summaries.isEmpty() && upTo < 1.0)) return@withContext plain
            val made = runCatching { ask(title, summaries, store.notes(mangaId), upTo, description, cast) }
                .onFailure { logcat(LogPriority.WARN, it) { "Story map for $title" } }
                .getOrNull() ?: return@withContext plain
            runCatching {
                file(context, mangaId).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(Map.serializer(), made))
            }
            made
        }

    private suspend fun ask(
        title: String,
        summaries: List<GlossaryStore.ChapterSummary>,
        notes: kotlin.collections.Map<String, String>,
        upTo: Double,
        description: String?,
        cast: List<String>,
    ): Map {
        val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
        val number = if (upTo % 1.0 == 0.0) upTo.toLong().toString() else upTo.toString()
        val reply = appAsk(AiRouter.Lane.CHAT) { ai ->
            ai.askJson(
                system = """
                    You map the story of a manhwa UP TO AND INCLUDING chapter $number, in $language. Absolutely nothing that happens
                    after chapter $number: no later events, deaths, reveals or power ups. Use the chapter summaries when given; for the
                    parts they don't cover, use what you know of the published story up to that chapter. When unsure of a detail,
                    leave it out rather than invent it.
                    Pick the 8 to 25 key events (battles, deaths, reveals, power ups, first meetings, turning points), oldest first,
                    each with its chapter. Then list who is what to whom among the named characters.
                    Reply with ONLY: {"events":[{"chapter":"as given","number":12,"type":"BATTLE|DEATH|REVEAL|POWER|MEET|TURN","title":"3 to 6 words","text":"one sentence"}],
                    "relations":[{"a":"name","b":"name","type":"ENEMY|ALLY|FAMILY|LOVE|RIVAL|MASTER","note":"a few words"}]}
                """.trimIndent(),
                prompt = buildString {
                    appendLine("Title: $title")
                    appendLine("Read up to chapter: $number")
                    description?.takeIf { it.isNotBlank() }?.let { appendLine("Synopsis: ${it.take(600)}") }
                    if (cast.isNotEmpty()) appendLine("Characters (AniList): ${cast.take(25).joinToString(", ")}")
                    if (notes.isNotEmpty()) {
                        appendLine("Character notes:")
                        notes.entries.take(30).forEach { (name, note) -> appendLine("- $name: $note") }
                    }
                    if (summaries.isNotEmpty()) {
                        appendLine("Chapter summaries (oldest first):")
                        summaries.takeLast(160).forEach { appendLine("- ${it.chapter} (#${it.number}): ${it.summary}") }
                    } else {
                        appendLine("No chapter summaries: use what you know of the published story up to chapter $number.")
                    }
                },
            )
        }
        val events = (reply["events"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val number = (o["number"] as? JsonPrimitive)?.doubleOrNull ?: -1.0
            if (number > upTo + 0.001) return@mapNotNull null
            Event(o.s("chapter"), number, o.s("type"), o.s("title"), o.s("text")).takeIf { it.text.isNotBlank() || it.title.isNotBlank() }
        }
        val relations = (reply["relations"] as? JsonArray).orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            Relation(o.s("a"), o.s("b"), o.s("type"), o.s("note")).takeIf { it.a.isNotBlank() && it.b.isNotBlank() && it.a != it.b }
        }.take(40)
        return Map(upTo, events, relations, fromAi = true)
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
}
