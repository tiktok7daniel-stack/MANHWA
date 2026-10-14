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

    @Serializable
    data class Entry(val source: String, val translation: String, val edited: Boolean = false)

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

    /** Adds terms found by the AI without touching entries that already exist. */
    fun merge(mangaId: Long, terms: Map<String, String>) {
        if (terms.isEmpty()) return
        update(mangaId) { list ->
            val known = list.map { it.source.lowercase() }.toSet()
            list + terms.filter { (s, t) -> s.isNotBlank() && t.isNotBlank() && s.lowercase() !in known }
                .map { (s, t) -> Entry(s.trim(), t.trim()) }
        }
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

    @Synchronized
    private fun update(mangaId: Long, change: (List<Entry>) -> List<Entry>) {
        val flow = state(mangaId)
        val next = change(flow.value).sortedBy { it.source.lowercase() }
        flow.value = next
        runCatching { file(mangaId).writeText(json.encodeToString(next)) }.onFailure { logcat(LogPriority.ERROR, it) }
    }
}
