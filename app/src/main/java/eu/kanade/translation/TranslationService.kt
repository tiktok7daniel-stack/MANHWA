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

    @Volatile private var lastStartId = 0

    /** The work went to the job after an error: this service ends instead of going on. */
    @Volatile private var handedToJob = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Foreground at once: Android gives a started service a few seconds to show its notification
        if (!goForeground()) {
            // Android did not let it into the foreground now (started again from the background): the job carries
            // on, translating itself (it knows the service could not)
            failedAt = System.currentTimeMillis()
            TranslationJob.startJob(applicationContext, replace = true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        if (loop?.isActive != true) loop = scope.launch { run() }
        // Closed for memory: Android starts it again by itself
        return START_STICKY
    }

    private suspend fun run() {
        val manager = applicationContext.appGraph.translationManager
        try {
            runCatching { manager.restorePending() }
                .onFailure { logcat(LogPriority.ERROR, it) { "Saved translation queue not put back" } }
            hold(true)
            if (manager.hasWork()) manager.startTranslation()
            var restarts = 0
            var shown = ""
            while (scope.isActive) {
                delay(2.seconds)
                // Renewed every turn (cheap when held): a long stuck page never lets the lock lapse for good
                hold(true)
                val queue = manager.queueState.value
                val now = queue.firstOrNull { it.status == Translation.State.TRANSLATING }
                val key = "${now?.chapter?.id}:${now?.progress?.value?.percent}:${queue.count { it.status == Translation.State.QUEUE }}"
                if (key != shown) {
                    shown = key
                    goForeground()
                }
                if (manager.isPaused) break
                if (!manager.hasWork()) {
                    // MANHWA: chapters that failed for a passing reason are tried again after a wait, a few times
                    if (!manager.retryFailed()) break
                    delay(RETRY_WAIT_S.seconds)
                    manager.startTranslation()
                    continue
                }
                if (!manager.isRunning) {
                    // Stopped with chapters still waiting (a failure stopped it): carry on, waiting longer each time
                    restarts++
                    delay((RESTART_BASE_S * restarts.coerceAtMost(MAX_BACKOFF_STEPS)).seconds)
                    if (manager.isPaused || !manager.hasWork()) continue
                    if (!manager.isRunning) manager.startTranslation()
                } else {
                    restarts = 0
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Anything unexpected: the job takes over rather than the queue waiting
            logcat(LogPriority.ERROR, e) { "Translation service stopped by an error, the job takes over" }
            handedToJob = true
            failedAt = System.currentTimeMillis()
            TranslationJob.startJob(applicationContext, replace = true)
        } finally {
            if (scope.isActive) stop()
        }
    }

    private fun stop() {
        val manager = applicationContext.appGraph.translationManager
        // A chapter queued while this one winds down keeps the service going instead of being dropped
        if (manager.hasWork() && !manager.isPaused && !handedToJob) {
            loop = scope.launch { run() }
            return
        }
        hold(false)
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    /** The processor stays awake while chapters are translated (the screen may go off). */
    private fun hold(on: Boolean) {
        if (on) {
            // Held: its time is pushed back, so it lasts as long as the queue
            wakeLock?.takeIf { it.isHeld }?.let {
                it.acquire(WAKE_LOCK_MS)
                return
            }
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
        failedAt = System.currentTimeMillis()
        if (applicationContext.appGraph.translationManager.hasWork()) TranslationJob.startJob(applicationContext, replace = true)
        stop()
    }

    override fun onDestroy() {
        hold(false)
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** A share of an hour, renewed with the notification, so a lock is never left behind. */
        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val RESTART_BASE_S = 10
        private const val RETRY_WAIT_S = 30

        /** True while the service is in the foreground and translating (the job leaves the work to it then). */
        @Volatile var running = false

        /** When the service last could not run (the job then translates itself for a while). */
        @Volatile var failedAt = 0L
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
