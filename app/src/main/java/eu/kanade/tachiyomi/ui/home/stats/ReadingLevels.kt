package eu.kanade.tachiyomi.ui.home.stats

import java.time.LocalDate

/**
 * MANHWA: the reading game. A streak of reading days that grows through stages, a level for every manhwa from
 * the chapters read in it, and a hunter rank for the reader from all of them. Pure functions, no Android.
 */
object ReadingLevels {

    /** Chapters read in one manhwa for each level. */
    const val CHAPTERS_PER_LEVEL = 5

    /** Minutes one chapter takes when the reading history does not say. */
    const val MINUTES_PER_CHAPTER = 4

    enum class StreakStage(val from: Int) {
        NONE(0),
        SPARK(1),
        FLAME(7),
        BLAZE(30),
        INFERNO(100),
        ;

        /** Where the next stage starts, or null at the last one. */
        val nextAt: Int? get() = entries.getOrNull(ordinal + 1)?.from

        companion object {
            fun of(days: Int): StreakStage = entries.last { days >= it.from }
        }
    }

    data class Streak(
        /** Reading days in a row, up to today (or up to yesterday while today is still open). */
        val days: Int,
        val longest: Int,
        val stage: StreakStage,
        /** 0..1 of the way from this stage to the next one (1 at the last stage). */
        val toNext: Float,
        /** Days left to the next stage, or null at the last one. */
        val daysToNext: Int?,
        /** The last 7 days, oldest first: read that day or not. */
        val week: List<Boolean>,
        val readToday: Boolean,
    )

    fun streak(readDays: Set<LocalDate>, today: LocalDate): Streak {
        val readToday = today in readDays
        var cursor = if (readToday) today else today.minusDays(1)
        var days = 0
        while (cursor in readDays) {
            days++
            cursor = cursor.minusDays(1)
        }
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        readDays.sorted().forEach { day ->
            run = if (previous?.plusDays(1) == day) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }
        val stage = StreakStage.of(days)
        val next = stage.nextAt
        val toNext = if (next == null) 1f else ((days - stage.from).toFloat() / (next - stage.from)).coerceIn(0f, 1f)
        return Streak(
            days = days,
            longest = maxOf(longest, days),
            stage = stage,
            toNext = toNext,
            daysToNext = next?.let { it - days },
            week = (6 downTo 0).map { today.minusDays(it.toLong()) in readDays },
            readToday = readToday,
        )
    }

    data class Level(
        val level: Int,
        /** Chapters read inside the current level. */
        val into: Int,
        /** Chapters still to read for the next level. */
        val toNext: Int,
        val rank: Rank,
        val title: Title,
    ) {
        val fraction: Float get() = into.toFloat() / CHAPTERS_PER_LEVEL
    }

    /** What the reader is called in a manhwa, by level: from newcomer to master of the work. */
    enum class Title(val fromLevel: Int) { NOVICE(1), FOLLOWER(5), DEVOTED(10), ADDICT(25), MASTER(50) }

    /** Letter ranks a manhwa's level shows, from E to S. */
    enum class Rank(val fromLevel: Int) { E(1), D(5), C(10), B(20), A(35), S(50) }

    fun level(chaptersRead: Int): Level {
        val read = chaptersRead.coerceAtLeast(0)
        val level = 1 + read / CHAPTERS_PER_LEVEL
        val into = read % CHAPTERS_PER_LEVEL
        return Level(
            level = level,
            into = into,
            toNext = CHAPTERS_PER_LEVEL - into,
            rank = Rank.entries.last { level >= it.fromLevel },
            title = Title.entries.last { level >= it.fromLevel },
        )
    }

    /** The reader's rank, from all their levels and chapters together. */
    enum class HunterRank(val from: Int) {
        E(0),
        D(60),
        C(200),
        B(500),
        A(1200),
        S(2500),
        MONARCH(5000),
        ;

        val nextAt: Int? get() = entries.getOrNull(ordinal + 1)?.from
    }

    data class Hunter(val rank: HunterRank, val points: Int, val toNext: Float, val pointsToNext: Int?)

    /** Points: every chapter read counts 1, every level reached in a manhwa 5 more. */
    fun hunter(totalChapters: Int, levels: List<Int>): Hunter {
        val points = totalChapters.coerceAtLeast(0) + levels.sumOf { (it - 1).coerceAtLeast(0) } * 5
        val rank = HunterRank.entries.last { points >= it.from }
        val next = rank.nextAt
        val toNext = if (next == null) 1f else ((points - rank.from).toFloat() / (next - rank.from)).coerceIn(0f, 1f)
        return Hunter(rank, points, toNext, next?.let { it - points })
    }
}
