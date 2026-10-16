package eu.kanade.tachiyomi.ui.library.ai

import eu.kanade.tachiyomi.source.model.SManga

/**
 * MANHWA library rules, kept pure so they can be tested without a phone.
 *
 * The "Completed" rule is strict: a series is finished only when its SOURCE says so (status Completed or
 * Publishing finished). How much the reader has read never makes a series finished, whatever the AI answers.
 */
object LibraryRules {

    /** The only test of "finished" in the whole app: the status the source gives. */
    fun isFinished(status: Long): Boolean {
        val s = status.toInt()
        return s == SManga.COMPLETED || s == SManga.PUBLISHING_FINISHED
    }

    private val completedNames = setOf(
        "completed", "complete", "finished", "ended", "done", "مكتملة", "مكتمل", "منتهية", "منتهي", "المكتملة", "完结", "완결",
    )

    /** Whether a category name means "Completed" (in any of the usual spellings). */
    fun isCompletedName(name: String): Boolean = normalize(name) in completedNames.map(::normalize)

    /** A name reduced to what makes it the same category: case, "&"/"and"/"و", spaces and marks ignored. */
    fun normalize(name: String): String = name.lowercase()
        .replace("&", " ").replace(" and ", " ").replace(" و", " ")
        .replace(Regex("[\\p{Punct}·•،]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Groups with the same meaning merged (the first spelling wins), and the strict completed rule applied:
     * a series the source does not call finished is taken out of any "Completed" group; groups left empty go.
     */
    fun clean(groups: List<LibraryOrganizer.Group>, statusById: Map<Long, Long>): List<LibraryOrganizer.Group> {
        val merged = LinkedHashMap<String, LibraryOrganizer.Group>()
        groups.forEach { g ->
            val key = normalize(g.name)
            if (key.isEmpty()) return@forEach
            val old = merged[key]
            merged[key] = if (old == null) g else old.copy(mangaIds = (old.mangaIds + g.mangaIds).distinct(), about = old.about.ifBlank { g.about })
        }
        return merged.values.map { g ->
            if (isCompletedName(g.name)) g.copy(mangaIds = g.mangaIds.filter { id -> statusById[id]?.let(::isFinished) == true }) else g
        }.filter { it.mangaIds.isNotEmpty() }
    }

    /**
     * The categories a series ends with after a full reorganize: the reader's own categories (those the
     * organizer does not manage) stay as they are, the managed ones become [assigned], and "Completed" follows
     * the strict rule both ways.
     */
    fun finalCategories(
        current: List<Long>,
        assigned: Set<Long>,
        managed: Set<Long>,
        completedId: Long?,
        finished: Boolean,
    ): List<Long> {
        val own = current.filter { it != 0L && it !in managed && it != completedId }
        val next = (own + assigned.filter { it != completedId }).toMutableList()
        if (completedId != null && finished) next += completedId
        return next.distinct()
    }

    /** Categories the organizer created that are now empty: the only ones it may remove. */
    fun removableEmpty(created: Set<String>, counts: Map<String, Int>): Set<String> =
        counts.filter { (name, count) -> count == 0 && created.any { normalize(it) == normalize(name) } }.keys
}
