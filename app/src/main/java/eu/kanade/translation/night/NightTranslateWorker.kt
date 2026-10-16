package eu.kanade.translation.night

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * MANHWA night translation: while the phone charges on Wi-Fi at night, the new chapters of the works in the
 * reader's library are downloaded and translated, so they open translated in the morning.
 *
 * Only works whose source is in English: an Arabic source (or any other language) is already readable or is
 * not what the team translates, and chapters already translated are skipped. At most [PER_WORK] new chapters
 * per work and [PER_NIGHT] a night, and it stops while the keys still have [KEEP_PERCENT]% of their daily
 * quota, so the morning's reading is never short of it. The translation library is used first by the
 * translator itself; only missing lines go to the AI.
 */
class NightTranslateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        val prefs = graph.translationPreferences
        if (!prefs.translationNightly().get()) return Result.success()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour !in START_HOUR until END_HOUR) return Result.success()
        val store = graph.teamStore
        // With no working key, the on-device AI translates the night's chapters for free
        val local = eu.kanade.translation.local.LocalAiEngine.available(applicationContext)
        if (store.active().isEmpty() && !local) return Result.success()
        val queued = runCatching { pick() }.onFailure { logcat(LogPriority.WARN, it) { "Night translation could not list chapters" } }
            .getOrDefault(emptyList())
        var sent = 0
        for ((manga, chapter) in queued) {
            if (!quotaLeft()) break
            runCatching {
                val dm = graph.downloadManager
                if (dm.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.source)) {
                    graph.translationManager.translateChapter(manga, chapter)
                } else {
                    // Downloaded first; the downloader hands it to the translator when it is done
                    graph.translationManager.translateAfterDownload += chapter.id
                    dm.downloadChapters(manga, listOf(chapter))
                }
                sent++
            }.onFailure { logcat(LogPriority.WARN, it) }
        }
        if (sent > 0) logcat(LogPriority.INFO) { "Night translation queued $sent chapters" }
        return Result.success()
    }

    /** New unread chapters of library works from English sources, not translated yet. */
    private suspend fun pick(): List<Pair<tachiyomi.domain.manga.model.Manga, tachiyomi.domain.chapter.model.Chapter>> {
        val graph = applicationContext.appGraph
        val out = mutableListOf<Pair<tachiyomi.domain.manga.model.Manga, tachiyomi.domain.chapter.model.Chapter>>()
        for (manga in graph.getFavorites.await()) {
            if (out.size >= PER_NIGHT) break
            val source = graph.sourceManager.get(manga.source) as? CatalogueSource ?: continue
            if (!NightRules.translatable(source.lang)) continue
            val chapters = graph.getChaptersByMangaId.await(manga.id)
            val unread = chapters.filter { !it.read }.sortedBy { it.chapterNumber }
            val fresh = unread.filter { c ->
                !NightRules.looksArabic(c.name) &&
                    !graph.translationManager.isChapterTranslated(c.name, c.scanlator, manga.title, manga.source)
            }.take(PER_WORK)
            fresh.forEach { if (out.size < PER_NIGHT) out += manga to it }
        }
        return out
    }

    /** True while some key has more than [KEEP_PERCENT]% of today's quota left. */
    private fun quotaLeft(): Boolean {
        val store = applicationContext.appGraph.teamStore
        if (store.active().isEmpty()) return eu.kanade.translation.local.LocalAiEngine.available(applicationContext)
        return store.active().any { m -> store.usage.level(m).let { !it.exhausted && it.percent > KEEP_PERCENT } }
    }

    companion object {
        private const val WORK_NAME = "manhwa_night_translate"
        const val PER_WORK = 2
        const val PER_NIGHT = 20
        const val KEEP_PERCENT = 30
        const val START_HOUR = 1
        const val END_HOUR = 6

        /** Every hour while charging on Wi-Fi; the work itself only runs between 1 and 6 at night. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NightTranslateWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            context.workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The pure rules of the night translation (tested without a phone). */
object NightRules {
    /** Only English sources are translated at night ("en", "en-US"...). "all" sources are skipped: their language is not known. */
    fun translatable(sourceLang: String): Boolean = sourceLang.lowercase().let { it == "en" || it.startsWith("en-") }

    /** A chapter title written in Arabic is already Arabic. */
    fun looksArabic(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { it in '؀'..'ۿ' } * 2 > letters.length
    }
}
