package eu.kanade.translation.team

import kotlin.math.max

/**
 * MANHWA: understands a chapter before it is translated: how heavy every page is and how to split the work.
 *
 * A page's weight grows with its bubbles, how much text they hold, sound effects, names the glossary does not
 * know yet, and whether the model has to look at the picture. Pages are grouped into batches of about the same
 * weight (not the same number of pages), heaviest first, so the slow part starts early and the light pages
 * fill the gaps; heavy batches go to the strongest keys.
 */
object ChapterPlanner {

    enum class Level { EMPTY, LIGHT, NORMAL, HEAVY }

    /** What a page holds, measured on the phone after the text was read. */
    class PageStats(
        val bubbles: Int,
        val characters: Int,
        val sounds: Int = 0,
        val newNames: Int = 0,
        val needsImage: Boolean = false,
    )

    /** A batch for one call: its pages in reading order, its weight, and whether it needs a key that reads images. */
    class Batch(val pages: List<String>, val weight: Float, val heavy: Boolean, val needsImage: Boolean)

    class Plan(
        val batches: List<Batch>,
        val levels: Map<String, Level>,
        val totalWeight: Float,
    ) {
        fun count(level: Level) = levels.values.count { it == level }
    }

    fun weight(stats: PageStats): Float {
        if (stats.bubbles == 0) return 0f
        return stats.bubbles * 1f +
            stats.characters / CHARS_PER_POINT +
            stats.sounds * 0.5f +
            stats.newNames * 0.75f +
            (if (stats.needsImage) IMAGE_WEIGHT else 0f)
    }

    fun level(weight: Float): Level = when {
        weight <= 0f -> Level.EMPTY
        weight < LIGHT_BELOW -> Level.LIGHT
        weight < HEAVY_FROM -> Level.NORMAL
        else -> Level.HEAVY
    }

    /**
     * Splits [pages] (name to stats, in reading order) into batches of about [maxWeight] and at most [maxPages]
     * pages (fewer when images are sent), heaviest first. With [slots] calls at a time the batches are made
     * smaller, so every key gets work. [emptyPages] are pages with no text at all; they are only counted.
     */
    fun plan(
        pages: Map<String, PageStats>,
        emptyPages: Collection<String> = emptyList(),
        maxWeight: Float = MAX_BATCH_WEIGHT,
        maxPages: Int = MAX_BATCH_PAGES,
        maxImagePages: Int = MAX_IMAGE_PAGES,
        slots: Int = 1,
    ): Plan {
        val order = pages.keys.withIndex().associate { it.value to it.index }
        val weights = pages.mapValues { weight(it.value) }
        val total = weights.values.sum()
        // Enough batches for every slot, but none so small that the call costs more than its text
        val limitWeight = if (slots > 1) (total / slots * 1.15f).coerceIn(MIN_BATCH_WEIGHT, maxWeight) else maxWeight
        val levels = weights.mapValues { level(it.value) } + emptyPages.associateWith { Level.EMPTY }
        // First fit, heaviest pages first: every batch ends up about as heavy as the others
        val open = mutableListOf<MutableList<String>>()
        val openWeight = mutableListOf<Float>()
        for ((name, w) in weights.entries.filter { it.value > 0f }.sortedByDescending { it.value }) {
            val image = pages.getValue(name).needsImage
            val limit = if (image) maxImagePages else maxPages
            val slot = open.indices.firstOrNull { i ->
                val sameKind = pages.getValue(open[i].first()).needsImage == image
                sameKind && open[i].size < limit && openWeight[i] + w <= limitWeight
            }
            if (slot == null) {
                open += mutableListOf(name)
                openWeight += w
            } else {
                open[slot] += name
                openWeight[slot] = openWeight[slot] + w
            }
        }
        val batches = open.indices.map { i ->
            val names = open[i].sortedBy { order[it] }
            Batch(
                pages = names,
                weight = openWeight[i],
                heavy = names.any { levels[it] == Level.HEAVY },
                needsImage = pages.getValue(names.first()).needsImage,
            )
        }.sortedByDescending { it.weight }
        return Plan(batches, levels, total)
    }

    /** About how long the plan takes with [slots] calls at a time, each weight point taking [msPerPoint]. */
    fun estimateMs(plan: Plan, slots: Int, msPerPoint: Long = MS_PER_POINT): Long {
        if (plan.batches.isEmpty()) return 0
        val rounds = (plan.batches.size + max(1, slots) - 1) / max(1, slots)
        val heaviest = plan.batches.maxOf { it.weight }
        return (rounds * max(heaviest, plan.totalWeight / plan.batches.size) * msPerPoint).toLong()
    }

    private const val CHARS_PER_POINT = 60f
    private const val IMAGE_WEIGHT = 1.5f
    private const val LIGHT_BELOW = 4f
    private const val HEAVY_FROM = 10f
    const val MAX_BATCH_WEIGHT = 28f
    const val MIN_BATCH_WEIGHT = 6f
    const val MAX_BATCH_PAGES = 6
    const val MAX_IMAGE_PAGES = 3
    private const val MS_PER_POINT = 450L
}
