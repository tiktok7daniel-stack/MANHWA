package eu.kanade.tachiyomi.data.download

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the last download error of each chapter in memory, so the chapter list can show why a
 * download failed even when the error notification is hidden (muted notifications, Do Not Disturb).
 */
object DownloadErrorLog {

    private val errors = ConcurrentHashMap<String, String>()

    @Volatile
    var lastError: String? = null
        private set

    fun record(mangaId: Long?, chapterName: String?, message: String) {
        lastError = message
        if (mangaId != null && chapterName != null) errors[key(mangaId, chapterName)] = message
    }

    fun get(mangaId: Long, chapterName: String): String? = errors[key(mangaId, chapterName)] ?: lastError

    private fun key(mangaId: Long, chapterName: String) = "$mangaId/$chapterName"
}
