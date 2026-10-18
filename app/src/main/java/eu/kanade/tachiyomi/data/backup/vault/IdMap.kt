package eu.kanade.tachiyomi.data.backup.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * MANHWA full backup: the app's own numbers for works and chapters (the database ids) are not the same on another
 * phone, or after the app is installed again. Many MANHWA files are tied to them (fusions, glossaries, the story,
 * lessons, bubble edits, linked sources...). The backup carries this map of every id those files use: the work's
 * source and address (and the chapter's address), which are the same everywhere. On restore each id is found again
 * by them, and the files are written with the new ids. A work joined by a fusion but not in the library (the
 * continuation on another language's source) travels whole, with its chapters, and is added back.
 */
@Serializable
data class IdMap(val mangas: List<MangaRef> = emptyList(), val chapters: List<ChapterRef> = emptyList()) {

    @Serializable
    data class MangaRef(
        val id: Long,
        val source: Long,
        val url: String,
        val title: String,
        val favorite: Boolean,
        /** For a work not in the library: what is needed to add it back, and its chapters. */
        val details: Details? = null,
        val chapters: List<ChapterData> = emptyList(),
    )

    @Serializable
    data class Details(
        val artist: String? = null,
        val author: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: Long = 0,
        val thumbnailUrl: String? = null,
    )

    @Serializable
    data class ChapterData(
        val url: String,
        val name: String,
        val number: Double,
        val scanlator: String? = null,
        val read: Boolean = false,
        val bookmark: Boolean = false,
        val lastPageRead: Long = 0,
        val sourceOrder: Long = 0,
        val dateUpload: Long? = null,
    )

    @Serializable
    data class ChapterRef(val id: Long, val mangaId: Long, val url: String)
}

/** The pure rules of [IdMap]: which files hold ids and how they are written again (tested without a phone). */
object IdRemapRules {

    /** JSON files of the app's files whose content holds work or chapter ids. */
    val contentFiles = setOf(
        "chapter_fusions.json",
        "fusion_offers.json",
        "unified_sources.json",
        "glossary_works.json",
        "favorite_drops.json",
        "fresh_chapters.json",
        "auto_organize_log.json",
    )

    /** Files whose top-level keys are work ids. */
    private val keyedByManga = setOf("fusion_offers.json", "unified_sources.json", "glossary_works.json")

    private val mangaFields = setOf("mangaId", "baseMangaId", "otherMangaId")
    private val mangaLists = setOf("mangaIds", "dismissed")
    private val chapterFields = setOf("chapterId")

    private val perManga = Regex("^(glossary|story|lessons)/(\\d+)\\.json$")
    private val perEdit = Regex("^translation_edits/([dl])(\\d+)\\.json$")
    private val perCheckpoint = Regex("^translation_checkpoints/(\\d+)(\\.(read|done)\\.json(\\.bak)?)$")

    /**
     * [path] (relative to the app's files) with the new id in its name: the same path when it holds no id, null
     * when it does and that id was not found again (the file is left out rather than given to another work).
     */
    fun renamed(path: String, manga: (Long) -> Long?, chapter: (Long) -> Long?): String? {
        perManga.find(path)?.let { m ->
            val id = manga(m.groupValues[2].toLong()) ?: return null
            return "${m.groupValues[1]}/$id.json"
        }
        perEdit.find(path)?.let { m ->
            val id = chapter(m.groupValues[2].toLong()) ?: return null
            return "translation_edits/${m.groupValues[1]}$id.json"
        }
        perCheckpoint.find(path)?.let { m ->
            val id = chapter(m.groupValues[1].toLong()) ?: return null
            return "translation_checkpoints/$id${m.groupValues[2]}"
        }
        return path
    }

    /** Whether [path] names a file whose content holds ids. */
    fun holdsIds(path: String): Boolean = path in contentFiles

    /**
     * [element] (the content of the file [name]) with every work and chapter id replaced: an entry of a list or a
     * map whose id was not found again is left out; a single field that was not found keeps nothing wrong either
     * (its object is left out of its list).
     */
    fun remap(name: String, element: JsonElement, manga: (Long) -> Long?, chapter: (Long) -> Long?): JsonElement {
        val inner = walk(element, manga, chapter) ?: return JsonObject(emptyMap())
        if (name !in keyedByManga || inner !is JsonObject) return inner
        return JsonObject(
            inner.entries.mapNotNull { (key, value) ->
                val id = key.toLongOrNull() ?: return@mapNotNull key to value
                manga(id)?.let { it.toString() to value }
            }.toMap(),
        )
    }

    /** Null: this object holds an id that was not found again. */
    private fun walk(element: JsonElement, manga: (Long) -> Long?, chapter: (Long) -> Long?): JsonElement? = when (element) {
        is JsonArray -> JsonArray(element.mapNotNull { walk(it, manga, chapter) })
        is JsonObject -> {
            val out = LinkedHashMap<String, JsonElement>()
            var lost = false
            for ((key, value) in element) {
                when {
                    key in mangaFields -> {
                        val id = (value as? JsonPrimitive)?.longOrNull
                        val mapped = id?.let(manga)
                        if (id != null && mapped == null) lost = true
                        out[key] = mapped?.let { JsonPrimitive(it) } ?: value
                    }
                    key in chapterFields -> {
                        val id = (value as? JsonPrimitive)?.longOrNull
                        val mapped = id?.let(chapter)
                        if (id != null && mapped == null) lost = true
                        out[key] = mapped?.let { JsonPrimitive(it) } ?: value
                    }
                    key in mangaLists && value is JsonArray -> {
                        out[key] = JsonArray(value.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.let(manga)?.let { JsonPrimitive(it) } })
                    }
                    else -> out[key] = walk(value, manga, chapter) ?: JsonObject(emptyMap())
                }
            }
            if (lost) null else JsonObject(out)
        }
        else -> element
    }

    /** Files a custom backup (a few works) carries, filtered to those works. */
    val customContentFiles = setOf("chapter_fusions.json", "glossary_works.json", "unified_sources.json")

    /**
     * [incoming] (from a backup) merged into [existing] (on this phone) without losing anything here: the entries
     * of a map keyed by work are added when this phone has none for that work, the fusions of works without one
     * here are added, and the dismissed works are joined. Other files: the backup's copy.
     */
    fun merge(name: String, existing: JsonElement, incoming: JsonElement): JsonElement {
        if (existing !is JsonObject || incoming !is JsonObject) return incoming
        if (name in keyedByManga) return JsonObject(incoming + existing)
        if (name == "chapter_fusions.json") {
            val here = (existing["fusions"] as? JsonArray).orEmpty()
            val bases = here.mapNotNull { (it as? JsonObject)?.get("baseMangaId")?.let { b -> (b as? JsonPrimitive)?.longOrNull } }.toSet()
            val added = (incoming["fusions"] as? JsonArray).orEmpty().filter { f ->
                val base = ((f as? JsonObject)?.get("baseMangaId") as? JsonPrimitive)?.longOrNull
                base != null && base !in bases
            }
            val dismissed = ((existing["dismissed"] as? JsonArray).orEmpty() + (incoming["dismissed"] as? JsonArray).orEmpty()).distinct()
            return JsonObject(existing + mapOf("fusions" to JsonArray(here + added), "dismissed" to JsonArray(dismissed)))
        }
        return incoming
    }

    /** Every work and chapter id that [path] and its [content] (when it holds ids) use. */
    fun idsOf(path: String, content: JsonElement?): Pair<Set<Long>, Set<Long>> {
        val mangas = HashSet<Long>()
        val chapters = HashSet<Long>()
        val keepManga: (Long) -> Long? = { mangas += it; it }
        val keepChapter: (Long) -> Long? = { chapters += it; it }
        renamed(path, keepManga, keepChapter)
        if (content != null && holdsIds(path)) remap(path, content, keepManga, keepChapter)
        return mangas to chapters
    }
}
