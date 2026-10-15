package eu.kanade.tachiyomi.ui.home.stats

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * MANHWA: the numbers of the reading game, from the library and its per-chapter reading history: the streak,
 * every manhwa's level and the reader's hunter rank. Read on the IO dispatcher; nothing here touches the network.
 */
@Inject
@SingleIn(AppScope::class)
class ReadingProgressEngine(
    private val getLibraryManga: GetLibraryManga,
    private val historyRepository: HistoryRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences,
) {

    /** The level a manhwa had when last seen, and when it last went up (0: never seen going up). */
    @kotlinx.serialization.Serializable
    private data class Seen(val level: Int, val upAt: Long = 0)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Compares every level with the one seen last time: a level that went up is marked with the time, so its card
     * celebrates for a day. A manhwa seen for the first time is only remembered (no celebration for old reading).
     */
    private fun levelUps(series: List<Series>): Map<Long, Long> {
        val seen = runCatching {
            json.decodeFromString<Map<String, Seen>>(sourcePreferences.manhwaSeenLevels.get())
        }.getOrDefault(emptyMap()).toMutableMap()
        val now = System.currentTimeMillis()
        series.forEach { s ->
            val key = s.manga.id.toString()
            val before = seen[key]
            seen[key] = when {
                before == null -> Seen(s.level.level)
                s.level.level > before.level -> Seen(s.level.level, now)
                else -> before.copy(level = s.level.level)
            }
        }
        sourcePreferences.manhwaSeenLevels.set(json.encodeToString<Map<String, Seen>>(seen.toMap()))
        return seen.mapNotNull { (key, value) ->
            val id = key.toLongOrNull() ?: return@mapNotNull null
            if (value.upAt > 0 && now - value.upAt < CELEBRATE_MS) id to value.upAt else null
        }.toMap()
    }

    data class Series(
        val manga: Manga,
        val read: Int,
        val total: Int,
        val minutes: Long,
        val level: ReadingLevels.Level,
        val lastRead: Long,
        /** When the level last went up, if within the last day (the card celebrates it), else 0. */
        val leveledUpAt: Long = 0,
    ) {
        val completed: Boolean get() = total > 0 && read >= total
        val readingNow: Boolean get() = read < total && System.currentTimeMillis() - lastRead < READING_NOW_MS
    }

    data class Snapshot(
        val streak: ReadingLevels.Streak,
        val hunter: ReadingLevels.Hunter,
        val totalChapters: Int,
        /** Manhwa with at least one chapter read, most recently read first. */
        val series: List<Series>,
    )

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val days = HashSet<LocalDate>()
        val series = getLibraryManga.await().filter { it.readCount > 0 }.map { item ->
            val history = runCatching { historyRepository.getHistoryByMangaId(item.manga.id) }.getOrDefault(emptyList())
            var readMs = 0L
            history.forEach { h ->
                val at = h.readAt ?: return@forEach
                days += Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(zone).toLocalDate()
                if (h.readDuration > 0) readMs += h.readDuration
            }
            val read = item.readCount.toInt()
            // The history keeps time only for chapters read in this app: the rest is estimated
            val minutes = maxOf(readMs / 60_000, read.toLong() * ReadingLevels.MINUTES_PER_CHAPTER)
            Series(
                manga = item.manga,
                read = read,
                total = item.totalChapters.toInt(),
                minutes = minutes,
                level = ReadingLevels.level(read),
                lastRead = item.lastRead?.toEpochMilliseconds() ?: 0L,
            )
        }.sortedByDescending { it.lastRead }
            .let { list ->
                val ups = levelUps(list)
                list.map { s -> ups[s.manga.id]?.let { s.copy(leveledUpAt = it) } ?: s }
            }
        val total = series.sumOf { it.read }
        Snapshot(
            streak = ReadingLevels.streak(days, LocalDate.now(zone)),
            hunter = ReadingLevels.hunter(total, series.map { it.level.level }),
            totalChapters = total,
            series = series,
        )
    }

    private companion object {
        const val CELEBRATE_MS = 24 * 60 * 60 * 1000L
        const val READING_NOW_MS = 30L * 24 * 60 * 60 * 1000
    }

    /** The chapter to read next in [mangaId]: the first unread one in reading order, or the last one. */
    suspend fun nextChapter(mangaId: Long): Chapter? = withContext(Dispatchers.IO) {
        val chapters = getChaptersByMangaId.await(mangaId).sortedWith(compareBy({ it.chapterNumber }, { it.sourceOrder * -1 }))
        chapters.firstOrNull { !it.read } ?: chapters.lastOrNull()
    }
}
