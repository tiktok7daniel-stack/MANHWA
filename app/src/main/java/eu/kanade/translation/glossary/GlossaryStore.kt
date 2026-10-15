package eu.kanade.translation.glossary

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
    data class ChapterSummary(val chapter: String, val summary: String)

    /** A term whose translation the reader changed: shown translations are updated right away. */
    data class Edit(val mangaId: Long, val source: String, val oldTranslation: String, val newTranslation: String)

    private val _edits = MutableSharedFlow<Edit>(extraBufferCapacity = 16)
    val edits: SharedFlow<Edit> = _edits.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val cache = ConcurrentHashMap<Long, MutableStateFlow<List<Entry>>>()

    private fun file(mangaId: Long) = File(File(context.filesDir, "glossary").apply { mkdirs() }, "$mangaId.json")

    fun flow(mangaId: Long): StateFlow<List<Entry>> = state(mangaId).asStateFlow()

    fun get(mangaId: Long): List<Entry> = state(mangaId).value

    /** source term -> translation, for the translator prompt. */
    fun asMap(mangaId: Long): Map<String, String> = get(mangaId).associate { it.source to it.translation }

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
    fun notes(mangaId: Long): Map<String, String> = get(mangaId).mapNotNull { entry ->
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
        storyFile(mangaId).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<List<ChapterSummary>>(it) }
    }.getOrNull().orEmpty()

    /** Terms from a backup: the ones not known here are added; known ones keep theirs, only filling what they lack. */
    fun importEntries(mangaId: Long, entries: List<Entry>) {
        if (entries.isEmpty()) return
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
    fun storySoFar(mangaId: Long): String = runCatching {
        storyFile(mangaId).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<List<ChapterSummary>>(it) }
    }.getOrNull().orEmpty().takeLast(STORY_CHAPTERS).joinToString("\n") { "${it.chapter}: ${it.summary}" }

    /** Keeps [summary] for [chapter] (the latest few chapters only). */
    @Synchronized
    fun addChapterSummary(mangaId: Long, chapter: String, summary: String) {
        if (summary.isBlank()) return
        runCatching {
            val file = storyFile(mangaId)
            val list = file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<List<ChapterSummary>>(it) }.orEmpty()
            val next = (list.filterNot { it.chapter == chapter } + ChapterSummary(chapter, summary.take(600))).takeLast(STORY_KEEP)
            file.writeText(json.encodeToString(next))
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
        file(mangaId).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<List<Entry>>(it) }
    }.onFailure { logcat(LogPriority.WARN, it) }.getOrNull().orEmpty()

    private companion object {
        const val STORY_CHAPTERS = 3
        const val STORY_KEEP = 10
    }

    @Synchronized
    private fun update(mangaId: Long, change: (List<Entry>) -> List<Entry>) {
        val flow = state(mangaId)
        val next = change(flow.value).sortedBy { it.source.lowercase() }
        flow.value = next
        runCatching { file(mangaId).writeText(json.encodeToString(next)) }.onFailure { logcat(LogPriority.ERROR, it) }
    }
}
