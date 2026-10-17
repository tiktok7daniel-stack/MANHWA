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
     * MANHWA: the ONE category a series ends with after a full reorganize (a series is never shown in two
     * places). A category the reader chose by hand (one the organizer does not manage) wins; else "Completed"
     * when the source calls the series finished; else the category the organizer assigned. With nothing to
     * give, the series keeps the first category it had.
     */
    fun finalCategories(
        current: List<Long>,
        assigned: Set<Long>,
        managed: Set<Long>,
        completedId: Long?,
        finished: Boolean,
    ): List<Long> {
        val own = current.firstOrNull { it != 0L && it !in managed && it != completedId }
        val one = own
            ?: completedId?.takeIf { finished }
            ?: assigned.firstOrNull { it != completedId }
            ?: current.firstOrNull { it != 0L && it != completedId }
        return listOfNotNull(one)
    }

    /**
     * MANHWA: one category for a series already in several (the library put right once): the same choice as
     * [finalCategories], with the managed category it already had in place of a new assignment.
     */
    fun oneCategory(current: List<Long>, managed: Set<Long>, completedId: Long?, finished: Boolean): List<Long> =
        finalCategories(current, current.filter { it in managed }.toSet(), managed, completedId, finished)

    /**
     * MANHWA: one card per work in the library: entries of the same work from two sources (same title once
     * cleaned, see [eu.kanade.translation.glossary.WorkIdentity]) show once, the one read last (else the first).
     * Titles too short to tell stay as they are. Nothing is removed, the others are only not shown twice.
     */
    fun <T> oneCardPerWork(items: List<T>, titleOf: (T) -> String, lastReadOf: (T) -> Long): List<T> {
        val keep = items.withIndex()
            .groupBy { (index, item) ->
                eu.kanade.translation.glossary.WorkIdentity.key(titleOf(item))
                    .takeIf { it.length >= eu.kanade.translation.glossary.WorkIdentity.MIN_KEY } ?: "#$index"
            }
            .values
            .map { same -> same.maxWith(compareBy<IndexedValue<T>> { lastReadOf(it.value) }.thenByDescending { it.index }).index }
            .toSet()
        return items.filterIndexed { index, _ -> index in keep }
    }

    private val broadNames = setOf(
        "action", "fantasy", "action fantasy", "fantasy action", "adventure", "action adventure", "shounen", "shonen",
        "supernatural", "manhwa", "manga", "manhua", "webtoon", "general", "other", "misc",
        "أكشن", "اكشن", "فانتازيا", "أكشن فانتازيا", "اكشن فانتازيا", "مغامرة", "مغامرات", "خارق للطبيعة", "عام", "أخرى",
    ).map(::normalize).toSet()

    /**
     * MANHWA: a catch-all category ("Action & Fantasy", "Action", "Fantasy"...) that tells the reader nothing,
     * since nearly every series is one: series are put by the premise of their story instead.
     */
    fun isBroad(name: String): Boolean = normalize(name) in broadNames

    /** Categories the organizer created that are now empty: the only ones it may remove. */
    fun removableEmpty(created: Set<String>, counts: Map<String, Int>): Set<String> =
        counts.filter { (name, count) -> count == 0 && created.any { normalize(it) == normalize(name) } }.keys
}
