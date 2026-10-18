package eu.kanade.translation.glossary

import eu.kanade.translation.writeTextAtomic
import eu.kanade.translation.readTextAtomic
import eu.kanade.translation.existsAtomic
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-series glossary of names and terms (characters, skills, ranks, places) and how they are translated.
 * The AI fills it while translating; the reader can edit it, and every later translation follows it.
 */
@Inject
@SingleIn(AppScope::class)
class GlossaryStore(private val context: Context) {

    /**
     * A term and its translation. MANHWA: also what it is ([kind]: character, skill, place, title, group, item),
     * a character's [gender] (male/female, so Arabic verbs and pronouns agree) and a short [note] (how they speak).
     * Glossaries saved before have none of these and load as they are.
     */
    @Serializable
    data class Entry(
        val source: String,
        val translation: String,
        val edited: Boolean = false,
        val kind: String = "",
        val gender: String = "",
        val note: String = "",
    )

    /** What happened in a chapter, in a sentence or three, kept to give the next chapters their context. */
    @Serializable
    data class ChapterSummary(
        val chapter: String,
        val summary: String,
        /** MANHWA: the chapter's number (-1 when unknown), so a recap can stop at the last chapter read. */
        val number: Double = -1.0,
    )

    /** A term whose translation the reader changed: shown translations are updated right away. */
    data class Edit(val mangaId: Long, val source: String, val oldTranslation: String, val newTranslation: String)

    private val _edits = MutableSharedFlow<Edit>(extraBufferCapacity = 16)
    val edits: SharedFlow<Edit> = _edits.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val cache = ConcurrentHashMap<Long, MutableStateFlow<List<Entry>>>()

    private fun file(mangaId: Long) = File(File(context.filesDir, "glossary").apply { mkdirs() }, "$mangaId.json")

    fun flow(mangaId: Long): StateFlow<List<Entry>> = state(mangaId).asStateFlow()

    fun get(mangaId: Long): List<Entry> = state(mangaId).value

    /**
     * source term -> translation, for the translator prompt. MANHWA: the same work in the library from other
     * sources ([WorkIdentity]) shares its terms; this series' own entries win.
     */
    fun asMap(mangaId: Long): Map<String, String> {
        val own = get(mangaId).associate { it.source to it.translation }
        val shared = siblings(mangaId).flatMap { get(it) }.associate { it.source to it.translation }
        if (shared.isEmpty()) return own
        val ownLower = own.keys.map { it.lowercase() }.toSet()
        return shared.filterKeys { it.lowercase() !in ownLower } + own
    }

    // ---------------------------------------------------------------- one work, many sources

    private fun worksFile() = File(context.filesDir, "glossary_works.json")

    private val works: MutableMap<Long, String> by lazy {
        ConcurrentHashMap(
            runCatching { json.decodeFromString<Map<String, String>>(worksFile().readTextAtomic()) }.getOrDefault(emptyMap())
                .mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap(),
        )
    }

    /**
     * Notes that [mangaId] (a series in the library) is the work called [title], so other sources of the same
     * work share its dictionary. Only library series are registered.
     */
    @Synchronized
    fun registerWork(mangaId: Long, title: String) {
        val key = WorkIdentity.key(title)
        if (key.length < WorkIdentity.MIN_KEY || works[mangaId] == key) return
        works[mangaId] = key
        runCatching { worksFile().writeTextAtomic(json.encodeToString(works.mapKeys { it.key.toString() })) }
    }

    /** Other series of the same work (another source), newest registered last. */
    fun siblings(mangaId: Long): List<Long> {
        val key = works[mangaId] ?: return emptyList()
        return works.filter { (id, k) -> k == key && id != mangaId }.keys.toList()
    }

    /**
     * Adds terms found by the AI without touching entries that already exist. MANHWA: [details] (kind and
     * gender) fill in what an entry does not know yet, but never change what the reader edited.
     */
    fun merge(
        mangaId: Long,
        terms: Map<String, String>,
        details: Map<String, Pair<String, String>> = emptyMap(),
        notes: Map<String, String> = emptyMap(),
    ) {
        if (terms.isEmpty() && details.isEmpty() && notes.isEmpty()) return
        update(mangaId) { list ->
            val known = list.map { it.source.lowercase() }.toSet()
            val completed = list.map { entry ->
                if (entry.edited) return@map entry
                val (kind, gender) = details.entries.firstOrNull { it.key.equals(entry.source, ignoreCase = true) }?.value ?: ("" to "")
                val note = notes.entries.firstOrNull { it.key.equals(entry.source, ignoreCase = true) }?.value.orEmpty()
                entry.copy(kind = entry.kind.ifBlank { kind }, gender = entry.gender.ifBlank { gender }, note = entry.note.ifBlank { note })
            }
            completed + terms.filter { (s, t) -> s.isNotBlank() && t.isNotBlank() && s.lowercase() !in known }
                .map { (s, t) ->
                    val (kind, gender) = details[s] ?: ("" to "")
                    Entry(s.trim(), t.trim(), kind = kind, gender = gender, note = notes[s].orEmpty())
                }
        }
    }

    /** MANHWA: what each term is, for the translator ("character, female, speaks coldly"); only terms that say something. */
    fun notes(mangaId: Long): Map<String, String> = (siblings(mangaId).flatMap { get(it) } + get(mangaId)).mapNotNull { entry ->
        val parts = listOfNotNull(
            entry.kind.takeIf { it.isNotBlank() },
            entry.gender.takeIf { it.isNotBlank() },
            entry.note.takeIf { it.isNotBlank() },
        )
        if (parts.isEmpty()) null else entry.source to parts.joinToString(", ")
    }.toMap()

    /** MANHWA: sets what a term is and how a character speaks; it counts as the reader's own edit. */
    fun describe(mangaId: Long, source: String, kind: String, gender: String, note: String) {
        update(mangaId) { list ->
            list.map {
                if (it.source.equals(source, ignoreCase = true)) it.copy(kind = kind.trim(), gender = gender.trim(), note = note.trim(), edited = true) else it
            }
        }
    }

    // ---------------------------------------------------------------- story so far

    private fun storyFile(mangaId: Long) = File(File(context.filesDir, "story").apply { mkdirs() }, "$mangaId.json")

    /** Every kept chapter summary, oldest first. */
    fun story(mangaId: Long): List<ChapterSummary> = runCatching {
        storyFile(mangaId).takeIf { it.existsAtomic() }?.readTextAtomic()?.let { json.decodeFromString<List<ChapterSummary>>(it) }
    }.getOrNull().orEmpty()

    /**
     * Terms from a backup: the ones not known here are added; known ones keep theirs, only filling what they lack.
     * MANHWA [replace]: a cleaned glossary takes the place of the AI's terms; the reader's own edits stay.
     */
    fun importEntries(mangaId: Long, entries: List<Entry>, replace: Boolean = false) {
        if (entries.isEmpty()) return
        if (replace) {
            update(mangaId) { list ->
                val mine = list.filter { it.edited }
                val taken = mine.map { it.source.lowercase() }.toSet()
                mine + entries.filter { it.source.isNotBlank() && it.source.lowercase() !in taken }.distinctBy { it.source.lowercase() }
            }
            return
        }
        update(mangaId) { list ->
            val known = list.associateBy { it.source.lowercase() }
            val filled = list.map { entry ->
                val other = entries.firstOrNull { it.source.equals(entry.source, ignoreCase = true) } ?: return@map entry
                entry.copy(kind = entry.kind.ifBlank { other.kind }, gender = entry.gender.ifBlank { other.gender }, note = entry.note.ifBlank { other.note })
            }
            filled + entries.filter { it.source.isNotBlank() && it.source.lowercase() !in known }
        }
    }

    /** The last chapters' summaries, oldest first, to give the translator the story so far. */
    fun storySoFar(mangaId: Long): String {
        // The same work from another source tells the story when this one has none yet
        val own = story(mangaId)
        val list = own.ifEmpty { siblings(mangaId).map { story(it) }.maxByOrNull { it.size }.orEmpty() }
        return list.takeLast(STORY_CHAPTERS).joinToString("\n") { "${it.chapter}: ${it.summary}" }
    }

    /** Keeps [summary] for [chapter] (the latest few chapters only). */
    @Synchronized
    fun addChapterSummary(mangaId: Long, chapter: String, summary: String, number: Double = -1.0) {
        if (summary.isBlank()) return
        runCatching {
            val file = storyFile(mangaId)
            val list = file.takeIf { it.existsAtomic() }?.readTextAtomic()?.let { json.decodeFromString<List<ChapterSummary>>(it) }.orEmpty()
            val next = (list.filterNot { it.chapter == chapter } + ChapterSummary(chapter, summary.take(600), number)).takeLast(STORY_KEEP)
            file.writeTextAtomic(json.encodeToString(next))
        }.onFailure { logcat(LogPriority.WARN, it) }
    }

    fun put(mangaId: Long, source: String, translation: String) {
        if (source.isBlank()) return
        val old = get(mangaId).firstOrNull { it.source.equals(source, ignoreCase = true) }?.translation
        if (!old.isNullOrBlank() && old != translation.trim() && translation.isNotBlank()) {
            _edits.tryEmit(Edit(mangaId, source.trim(), old, translation.trim()))
        }
        update(mangaId) { list ->
            list.filterNot { it.source.equals(source, ignoreCase = true) } + Entry(source.trim(), translation.trim(), edited = true)
        }
    }

    fun remove(mangaId: Long, source: String) {
        update(mangaId) { list -> list.filterNot { it.source == source } }
    }

    private fun state(mangaId: Long): MutableStateFlow<List<Entry>> = cache.getOrPut(mangaId) {
        MutableStateFlow(load(mangaId))
    }

    private fun load(mangaId: Long): List<Entry> = runCatching {
        file(mangaId).takeIf { it.existsAtomic() }?.readTextAtomic()?.let { json.decodeFromString<List<Entry>>(it) }
    }.onFailure { logcat(LogPriority.WARN, it) }.getOrNull().orEmpty()

    private companion object {
        const val STORY_CHAPTERS = 3
        // Enough for a recap of a long series; each summary is at most 600 characters
        const val STORY_KEEP = 60
    }

    @Synchronized
    private fun update(mangaId: Long, change: (List<Entry>) -> List<Entry>) {
        val flow = state(mangaId)
        val next = change(flow.value).sortedBy { it.source.lowercase() }
        flow.value = next
        runCatching { file(mangaId).writeTextAtomic(json.encodeToString(next)) }.onFailure { logcat(LogPriority.ERROR, it) }
    }
}
