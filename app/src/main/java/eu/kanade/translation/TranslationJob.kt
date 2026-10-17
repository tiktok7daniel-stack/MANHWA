package eu.kanade.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.at.ATMR
import kotlin.time.Duration.Companion.seconds

/**
 * MANHWA: keeps chapter translation alive in the background. While chapters wait in the queue this job runs in
 * the foreground (so Android does not stop the app halfway), restarts the translator if it stopped with work
 * left, and ends when the queue is done or the reader paused it. The queue itself is saved by
 * [TranslationManager], so after the app was closed or the phone restarted, the job puts it back and carries on.
 */
class TranslationJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val manager: TranslationManager by lazy { applicationContext.appGraph.translationManager }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // MANHWA: the chapter being translated, how far it is and how many wait, kept up to date while the app is
        // closed (the translation goes on in the background until the queue is done)
        val queue = manager.queueState.value
        val now = queue.firstOrNull { it.status == Translation.State.TRANSLATING }
        val waiting = queue.count { it.status == Translation.State.QUEUE }
        val percent = now?.progress?.value?.percent ?: 0
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(
                now?.let { applicationContext.stringResource(ATMR.strings.tj_now, it.manga.title, it.chapter.name) }
                    ?: applicationContext.stringResource(ATMR.strings.tj_title),
            )
            setContentText(
                if (waiting > 0) {
                    applicationContext.stringResource(ATMR.strings.tj_waiting, percent, waiting)
                } else {
                    applicationContext.stringResource(ATMR.strings.tj_text)
                },
            )
            setSmallIcon(R.drawable.ic_translate)
            setOngoing(true)
            setOnlyAlertOnce(true)
            if (now != null) setProgress(100, percent, percent == 0)
        }.build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override suspend fun doWork(): Result {
        // After the app was closed: the saved queue comes back first
        manager.restorePending()
        if (!manager.hasWork()) return Result.success()
        setForegroundSafely()
        manager.startTranslation()
        var restarts = 0
        var shown = ""
        while (!isStopped) {
            delay(2.seconds)
            // The notification follows the chapter and its percentage (only when they change)
            val queue = manager.queueState.value
            val now = queue.firstOrNull { it.status == Translation.State.TRANSLATING }
            val key = "${now?.chapter?.id}:${now?.progress?.value?.percent}:${queue.count { it.status == Translation.State.QUEUE }}"
            if (key != shown) {
                shown = key
                runCatching { setForeground(getForegroundInfo()) }
            }
            if (manager.isPaused || !manager.hasWork()) break
            if (!manager.isRunning) {
                // Stopped with chapters still waiting (a failure stopped it): carry on, a few times at most
                if (restarts >= MAX_RESTARTS) break
                restarts++
                manager.startTranslation()
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MANHWA-translation"
        private const val MAX_RESTARTS = 3
        private const val NOTIFICATION_ID = Notifications.ID_NEW_CHAPTERS - 3000

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<TranslationJob>().addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        /** Chapters waiting or being translated. */
        fun active(state: Translation.State) = state == Translation.State.QUEUE || state == Translation.State.TRANSLATING
    }
}
