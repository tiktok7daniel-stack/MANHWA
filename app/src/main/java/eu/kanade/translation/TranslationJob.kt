package eu.kanade.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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
import java.util.concurrent.TimeUnit
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
        // In the foreground now, Android lets the service start: it carries on there (sticky, kept awake)
        if (TranslationService.start(applicationContext)) return Result.success()
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
                // Stopped with chapters still waiting (a failure stopped it): carry on, waiting longer each time,
                // and never let the queue go while chapters wait
                restarts++
                delay((RESTART_BASE_S * restarts.coerceAtMost(MAX_BACKOFF_STEPS)).seconds)
                if (isStopped || manager.isPaused || !manager.hasWork()) break
                if (!manager.isRunning) manager.startTranslation()
            } else {
                restarts = 0
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MANHWA-translation"
        private const val RESTART_BASE_S = 10
        private const val MAX_BACKOFF_STEPS = 6
        private const val NOTIFICATION_ID = Notifications.ID_NEW_CHAPTERS - 3000

        /**
         * MANHWA: translation goes on in [TranslationService] (sticky, the processor kept awake); this job only
         * when Android does not let a service start right now (it starts the service itself when it can).
         */
        fun start(context: Context) {
            if (!TranslationService.start(context)) startJob(context)
        }

        fun startJob(context: Context) {
            val request = OneTimeWorkRequestBuilder<TranslationJob>().addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        private const val RESUME_TAG = "MANHWA-translation-resume"

        /**
         * While chapters wait, Android wakes the app every 15 minutes to carry on, even after it closed the app
         * to free memory or the screen has been off for long; stopped again when the queue is empty.
         */
        fun keepResuming(context: Context, on: Boolean) {
            val work = WorkManager.getInstance(context)
            if (on) {
                val request = PeriodicWorkRequestBuilder<TranslationResumeJob>(15, TimeUnit.MINUTES).addTag(RESUME_TAG).build()
                work.enqueueUniquePeriodicWork(RESUME_TAG, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                work.cancelUniqueWork(RESUME_TAG)
            }
        }

        /** Chapters waiting or being translated. */
        fun active(state: Translation.State) = state == Translation.State.QUEUE || state == Translation.State.TRANSLATING
    }
}

/** MANHWA: puts the saved queue back and starts the translation again when Android had stopped the app. */
class TranslationResumeJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = applicationContext.appGraph.translationManager
        manager.restorePending()
        if (manager.hasWork() && !manager.isPaused) TranslationJob.start(applicationContext)
        return Result.success()
    }
}
