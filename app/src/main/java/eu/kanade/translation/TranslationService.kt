package eu.kanade.translation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.at.ATMR
import kotlin.time.Duration.Companion.seconds

/**
 * MANHWA: chapter translation at full strength with the app left or the screen off. A foreground service of its
 * own (not a job Android may put off): it keeps the processor awake while chapters wait, and when Android or the
 * phone maker closes the app to free memory, Android starts this service again by itself (sticky), the saved queue
 * comes back and each chapter goes on from the page it reached ([ChapterCheckpoint]). It stops when the queue is
 * done or the reader paused it. [TranslationJob] stays as the way in when Android does not let a service start.
 */
class TranslationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground at once: Android gives a started service a few seconds to show its notification
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        // Closed for memory: Android starts it again by itself
        return START_STICKY
    }

    private suspend fun run() {
        val manager = applicationContext.appGraph.translationManager
        runCatching { manager.restorePending() }
        if (!manager.hasWork()) {
            stop()
            return
        }
        hold(true)
        manager.startTranslation()
        var restarts = 0
        var shown = ""
        while (scope.isActive) {
            delay(2.seconds)
            val queue = manager.queueState.value
            val now = queue.firstOrNull { it.status == Translation.State.TRANSLATING }
            val key = "${now?.chapter?.id}:${now?.progress?.value?.percent}:${queue.count { it.status == Translation.State.QUEUE }}"
            if (key != shown) {
                shown = key
                goForeground()
            }
            if (manager.isPaused || !manager.hasWork()) break
            if (!manager.isRunning) {
                // Stopped with chapters still waiting (a failure stopped it): carry on, waiting longer each time
                restarts++
                delay((RESTART_BASE_S * restarts.coerceAtMost(MAX_BACKOFF_STEPS)).seconds)
                if (manager.isPaused || !manager.hasWork()) break
                if (!manager.isRunning) manager.startTranslation()
            } else {
                restarts = 0
            }
        }
        stop()
    }

    private fun stop() {
        hold(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The processor stays awake while chapters are translated (the screen may go off). */
    private fun hold(on: Boolean) {
        if (on) {
            if (wakeLock?.isHeld == true) return
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MANHWA:translation")
                .apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_MS)
                }
        } else {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
        }
    }

    private fun goForeground(): Boolean = runCatching {
        // Renewed with every notification update, so a long queue never outlives it
        if (wakeLock?.isHeld == true) wakeLock?.acquire(WAKE_LOCK_MS)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        true
    }.onFailure { logcat(LogPriority.WARN, it) { "Translation service could not go foreground" } }.getOrDefault(false)

    private fun notification(): android.app.Notification {
        val manager = applicationContext.appGraph.translationManager
        val queue = manager.queueState.value
        val now = queue.firstOrNull { it.status == Translation.State.TRANSLATING }
        val waiting = queue.count { it.status == Translation.State.QUEUE }
        val percent = now?.progress?.value?.percent ?: 0
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        return notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(
                now?.let { stringResource(ATMR.strings.tj_now, it.manga.title, it.chapter.name) }
                    ?: stringResource(ATMR.strings.tj_title),
            )
            setContentText(
                if (waiting > 0) stringResource(ATMR.strings.tj_waiting, percent, waiting) else stringResource(ATMR.strings.tj_text),
            )
            setSmallIcon(R.drawable.ic_translate)
            setOngoing(true)
            setOnlyAlertOnce(true)
            open?.let { setContentIntent(it) }
            if (now != null) setProgress(100, percent, percent == 0)
        }.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android's time limit for this kind of service (not for special use, kept for older types): the job takes over
        TranslationJob.startJob(applicationContext)
        stop()
    }

    override fun onDestroy() {
        hold(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** A share of an hour, renewed with the notification, so a lock is never left behind. */
        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val RESTART_BASE_S = 10
        private const val MAX_BACKOFF_STEPS = 6
        // Its own: the job's notification goes away when the job hands over, this one stays
        private const val NOTIFICATION_ID = Notifications.ID_NEW_CHAPTERS - 3001

        /** Starts the service; false when Android does not let a service start now (the caller uses the job). */
        fun start(context: Context): Boolean = runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, TranslationService::class.java))
            true
        }.onFailure { logcat(LogPriority.WARN, it) { "Translation service not started, the job takes over" } }.getOrDefault(false)
    }
}
