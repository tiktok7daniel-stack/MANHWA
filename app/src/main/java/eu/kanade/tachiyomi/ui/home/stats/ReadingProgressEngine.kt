package eu.kanade.tachiyomi.ui.home.stats

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {

    data class Series(
        val manga: Manga,
        val read: Int,
        val total: Int,
        val minutes: Long,
        val level: ReadingLevels.Level,
        val lastRead: Long,
    )

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
        val total = series.sumOf { it.read }
        Snapshot(
            streak = ReadingLevels.streak(days, LocalDate.now(zone)),
            hunter = ReadingLevels.hunter(total, series.map { it.level.level }),
            totalChapters = total,
            series = series,
        )
    }

    /** The chapter to read next in [mangaId]: the first unread one in reading order, or the last one. */
    suspend fun nextChapter(mangaId: Long): Chapter? = withContext(Dispatchers.IO) {
        val chapters = getChaptersByMangaId.await(mangaId).sortedWith(compareBy({ it.chapterNumber }, { it.sourceOrder * -1 }))
        chapters.firstOrNull { !it.read } ?: chapters.lastOrNull()
    }
}
