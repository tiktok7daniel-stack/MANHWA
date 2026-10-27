package eu.kanade.tachiyomi.ui.home.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * MANHWA: when a work's chapters come out. From the dates of its last chapters it finds the usual gap and, for a
 * weekly work, its day; then when the next one is due and how late it is. Pure, so it is tested on its own.
 */
object ReleaseRhythm {

    data class Rhythm(
        /** The day it comes out on, for a work that keeps to one day a week. */
        val weekday: DayOfWeek?,
        /** The usual gap between chapters, in days. */
        val gapDays: Double,
        /** When the next chapter is due (epoch millis). */
        val nextAt: Long,
        /** Whole days past [nextAt], 0 while it is not due yet. */
        val lateDays: Int,
    )

    private const val DAY = 24 * 60 * 60 * 1000L

    /** Null when there are too few dated chapters, or they come too irregularly to say. */
    fun of(uploads: List<Long>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Rhythm? {
        // Chapters uploaded together (a batch) count once
        val days = uploads.filter { it > 0 && it <= now + DAY }
            .map { it / DAY }
            .distinct()
            .sorted()
            .takeLast(12)
        if (days.size < 3) return null
        val gaps = days.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val gap = gaps[gaps.size / 2]
        if (gap <= 0.0 || gap > 60.0) return null
        val recent = days.takeLast(8).map { Instant.ofEpochMilli(it * DAY + DAY / 2).atZone(zone).dayOfWeek }
        val top = recent.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val weekly = gap in 5.0..9.0 && top != null && top.value * 2 >= recent.size
        val last = days.last() * DAY
        val nextAt = if (weekly) {
            // The first chosen weekday at least three days after the last chapter
            var d = Instant.ofEpochMilli(last + 3 * DAY).atZone(zone).toLocalDate()
            while (d.dayOfWeek != top!!.key) d = d.plusDays(1)
            d.atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            last + (gap * DAY).roundToLong()
        }
        val late = ((now - nextAt) / DAY).toInt().coerceAtLeast(0)
        return Rhythm(if (weekly) top!!.key else null, gap, nextAt, late)
    }
}
