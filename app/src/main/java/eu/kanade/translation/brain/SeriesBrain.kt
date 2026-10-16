package eu.kanade.translation.brain

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.translation.glossary.WorkIdentity
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAiConfigured
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * MANHWA work brain: what a work is about, learnt chapter by chapter. Who the characters are, what they are to
 * each other (brother, enemy, master...), their rank or level and how it changes, the places and groups, and a
 * timeline of what happened in each chapter.
 *
 * It gives the translator the context a human translator keeps in their head (a hero who became S-rank at
 * chapter 80 is called so after it), and answers the reader's questions without spoilers: only what was learnt
 * up to the last chapter the reader read is ever shown or used to answer ([BrainRules.upTo]). One brain per
 * work, shared by every source of it ([WorkIdentity]).
 */
@Inject
@SingleIn(AppScope::class)
class SeriesBrain(private val context: Context) {

    @Serializable
    data class Fact(val chapter: Double, val text: String)

    @Serializable
    data class Entity(
        val name: String,
        val arabic: String = "",
        /** character, place, group, item, skill */
        val type: String = "character",
        val details: String = "",
        val firstChapter: Double = -1.0,
        val history: List<Fact> = emptyList(),
    )

    @Serializable
    data class Relation(val a: String, val b: String, val kind: String, val since: Double = -1.0)

    @Serializable
    data class Event(val chapter: Double, val name: String, val text: String)

    @Serializable
    data class Brain(
        val entities: List<Entity> = emptyList(),
        val relations: List<Relation> = emptyList(),
        val events: List<Event> = emptyList(),
        val learnt: List<Double> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, MutableStateFlow<Brain>>()

    private fun dir() = File(context.filesDir, "brain").apply { mkdirs() }
    private fun keyOf(title: String, mangaId: Long) = WorkIdentity.key(title).takeIf { it.length >= WorkIdentity.MIN_KEY } ?: "m$mangaId"
    private fun file(key: String) = File(dir(), "$key.json")

    fun flow(mangaId: Long, title: String): StateFlow<Brain> = state(keyOf(title, mangaId)).asStateFlow()

    fun get(mangaId: Long, title: String): Brain = state(keyOf(title, mangaId)).value

    private fun state(key: String) = cache.getOrPut(key) {
        MutableStateFlow(runCatching { json.decodeFromString<Brain>(file(key).readText()) }.getOrDefault(Brain()))
    }

    private fun save(key: String, brain: Brain) {
        state(key).value = brain
        runCatching { file(key).writeText(json.encodeToString(brain)) }.onFailure { logcat(LogPriority.WARN, it) }
    }

    /** The facts the translator gets for a chapter: who is who and what they are now, up to [chapter]. */
    fun contextFor(mangaId: Long, title: String, chapter: Double): String = BrainRules.describe(BrainRules.upTo(get(mangaId, title), chapter), MAX_CONTEXT)

    /**
     * Learns from one translated chapter in the background (the team's lowest lane): its summary and some of
     * its lines. A chapter is learnt once.
     */
    fun learnLater(mangaId: Long, title: String, chapterName: String, number: Double, summary: String, lines: List<Pair<String, String>>) {
        if (summary.isBlank() && lines.isEmpty()) return
        scope.launch {
            runCatching { learn(mangaId, title, chapterName, number, summary, lines) }
                .onFailure { logcat(LogPriority.WARN, it) { "The work brain could not learn from $chapterName" } }
        }
    }

    suspend fun learn(mangaId: Long, title: String, chapterName: String, number: Double, summary: String, lines: List<Pair<String, String>>) {
        val key = keyOf(title, mangaId)
        val brain = get(mangaId, title)
        if (number >= 0 && number in brain.learnt) return
        if (!appAiConfigured()) {
            // Without a key the timeline still grows from the translation's own summary
            if (summary.isNotBlank()) save(key, BrainRules.addEvent(brain, Event(number, chapterName, summary.take(MAX_EVENT)), number))
            return
        }
        val known = brain.entities.take(60).joinToString("; ") { "${it.name} (${it.type}${if (it.details.isNotBlank()) ": " + it.details.take(60) else ""})" }
        val reply = appAsk(AiRouter.Lane.BACKGROUND) { ai ->
            ai.askJson(
                system = """
                    You keep the story bible of the manhwa "$title" for its Arabic translators. From the chapter below,
                    write down only what it shows: characters, places, groups, items and skills with a short Arabic
                    description, a character's rank, level or title when the chapter states it, relations between
                    characters (in Arabic: أخ، عدو، معلم، تلميذ، حبيب، حليف...), and one or two Arabic sentences on
                    what happened. Do not guess beyond this chapter.
                    Reply with ONLY: {"entities":[{"name":"","arabic":"","type":"character|place|group|item|skill","details":"","fact":""}],
                    "relations":[{"a":"","b":"","kind":""}],"event":""}
                """.trimIndent(),
                prompt = buildString {
                    if (known.isNotEmpty()) appendLine("Already known: $known")
                    appendLine("Chapter: $chapterName")
                    if (summary.isNotBlank()) appendLine("Summary: $summary")
                    appendLine("Lines (original | translation):")
                    lines.take(MAX_LINES).forEach { (o, t) -> appendLine("${o.replace("\n", " ").take(160)} | ${t.replace("\n", " ").take(160)}") }
                },
            )
        }
        val entities = (reply["entities"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            BrainRules.NewEntity(name, o.str("arabic").orEmpty(), o.str("type") ?: "character", o.str("details").orEmpty(), o.str("fact").orEmpty())
        }
        val relations = (reply["relations"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Relation(o.str("a") ?: return@mapNotNull null, o.str("b") ?: return@mapNotNull null, o.str("kind") ?: return@mapNotNull null, number)
        }
        val event = reply.str("event")?.ifBlank { null } ?: summary.take(MAX_EVENT)
        save(key, BrainRules.merge(get(mangaId, title), entities, relations, Event(number, chapterName, event), number))
    }

    /** Answers a question about the work with only what is known up to [readUpTo] (no spoilers). */
    suspend fun ask(mangaId: Long, title: String, readUpTo: Double, question: String): String = withContext(Dispatchers.IO) {
        val facts = BrainRules.describe(BrainRules.upTo(get(mangaId, title), readUpTo), MAX_ANSWER_CONTEXT)
        appAsk(AiRouter.Lane.CHAT) { ai ->
            ai.askJson(
                system = """
                    You answer a manhwa reader's question in Arabic, using ONLY the story bible below, which stops at the
                    last chapter they read. Never reveal anything after it and never guess the future. If the bible
                    does not tell, say so kindly. Short, friendly, in Arabic.
                    Reply with ONLY: {"a":"..."}
                """.trimIndent(),
                prompt = "Work: $title\nRead up to chapter: ${BrainRules.number(readUpTo)}\nStory bible:\n$facts\n\nQuestion: $question",
            )
        }.str("a").orEmpty()
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private companion object {
        const val MAX_LINES = 80
        const val MAX_EVENT = 400
        const val MAX_CONTEXT = 1_800
        const val MAX_ANSWER_CONTEXT = 6_000
    }
}

/** The pure rules of the work brain (tested without a phone). */
object BrainRules {

    data class NewEntity(val name: String, val arabic: String, val type: String, val details: String, val fact: String)

    fun number(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()

    /** Only what was known by chapter [chapter]: later facts, relations, events and characters are left out. */
    fun upTo(brain: SeriesBrain.Brain, chapter: Double): SeriesBrain.Brain {
        if (chapter < 0) return brain
        return SeriesBrain.Brain(
            entities = brain.entities.filter { it.firstChapter <= chapter }
                .map { e -> e.copy(history = e.history.filter { it.chapter <= chapter }) },
            relations = brain.relations.filter { it.since <= chapter },
            events = brain.events.filter { it.chapter <= chapter },
            learnt = brain.learnt.filter { it <= chapter },
        )
    }

    fun merge(
        brain: SeriesBrain.Brain,
        found: List<NewEntity>,
        relations: List<SeriesBrain.Relation>,
        event: SeriesBrain.Event,
        chapter: Double,
    ): SeriesBrain.Brain {
        val byName = LinkedHashMap<String, SeriesBrain.Entity>()
        brain.entities.forEach { byName[it.name.lowercase()] = it }
        found.forEach { f ->
            val key = f.name.lowercase()
            val old = byName[key]
            val fact = f.fact.takeIf { it.isNotBlank() }?.let { SeriesBrain.Fact(chapter, it.take(240)) }
            byName[key] = if (old == null) {
                SeriesBrain.Entity(f.name, f.arabic, f.type, f.details.take(240), chapter, listOfNotNull(fact))
            } else {
                old.copy(
                    arabic = old.arabic.ifBlank { f.arabic },
                    details = f.details.takeIf { it.isNotBlank() }?.take(240) ?: old.details,
                    history = (old.history + listOfNotNull(fact)).takeLast(MAX_HISTORY),
                    firstChapter = if (old.firstChapter < 0) chapter else old.firstChapter,
                )
            }
        }
        val allRelations = (brain.relations + relations).distinctBy { "${it.a.lowercase()}|${it.b.lowercase()}|${it.kind}" }.takeLast(MAX_RELATIONS)
        return addEvent(brain.copy(entities = byName.values.toList().takeLast(MAX_ENTITIES), relations = allRelations), event, chapter)
    }

    fun addEvent(brain: SeriesBrain.Brain, event: SeriesBrain.Event, chapter: Double): SeriesBrain.Brain =
        brain.copy(
            events = (brain.events.filterNot { it.chapter == chapter && chapter >= 0 } + event).sortedBy { it.chapter }.takeLast(MAX_EVENTS),
            learnt = (brain.learnt + chapter).distinct().sorted().takeLast(MAX_EVENTS),
        )

    /** A compact text of the brain for a prompt, at most [maxChars]. */
    fun describe(brain: SeriesBrain.Brain, maxChars: Int): String = buildString {
        brain.entities.sortedByDescending { it.history.size }.forEach { e ->
            val now = e.history.lastOrNull()?.text.orEmpty()
            append("- ${e.name}${if (e.arabic.isNotBlank()) " (${e.arabic})" else ""} [${e.type}]: ${e.details}")
            if (now.isNotBlank()) append(" · ch ${number(e.history.last().chapter)}: $now")
            appendLine()
            if (length > maxChars) return@buildString
        }
        brain.relations.forEach {
            appendLine("- ${it.a} → ${it.b}: ${it.kind}")
            if (length > maxChars) return@buildString
        }
        brain.events.takeLast(6).forEach {
            appendLine("- ch ${number(it.chapter)}: ${it.text}")
            if (length > maxChars) return@buildString
        }
    }.take(maxChars).trimEnd()

    private const val MAX_HISTORY = 12
    private const val MAX_RELATIONS = 200
    private const val MAX_ENTITIES = 300
    private const val MAX_EVENTS = 400
}
