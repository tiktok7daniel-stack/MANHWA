package eu.kanade.tachiyomi.data.backup.vault

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.at.ATMR
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA: the full backup made or put back in the background ([FullBackup]), with a notification showing how far
 * it is: a backup of a gigabyte or more goes on when the screen is left or the phone is locked.
 */
class FullBackupWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        val restore = inputData.getBoolean(KEY_RESTORE, false)
        setForegroundSafely()
        var last = 0L
        val onProgress: (FullBackup.Progress) -> Unit = { p ->
            val now = System.currentTimeMillis()
            // Not more than twice a second: the notification and the screen follow without slowing the copy
            if (now - last >= 500 || p.done >= p.total) {
                last = now
                val percent = FullBackupRules.percent(p.done, p.total)
                setProgressAsync(workDataOf(KEY_PERCENT to percent, KEY_NAME to p.name))
                runCatching { context.notificationManager.notify(NOTIFICATION_ID, notification(restore, percent).build()) }
            }
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                if (restore) {
                    FullBackup.import(context, uri, onProgress)
                } else {
                    FullBackup.export(context, uri, FullBackup.Options(
                            allDownloads = inputData.getBoolean(KEY_ALL, false),
                            translatedPages = inputData.getBoolean(KEY_PAGES, false),
                        ), onProgress)
                }
            }
            Result.success(workDataOf(KEY_FILES to result.files, KEY_CHAPTERS to result.chapters, KEY_LINES to result.lines))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Full backup failed" }
            Result.failure(workDataOf(KEY_ERROR to (if (e is FullBackup.NotABackupException) ERROR_NOT_BACKUP else e.message.orEmpty())))
        } finally {
            runCatching { context.notificationManager.cancel(NOTIFICATION_ID) }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notification(inputData.getBoolean(KEY_RESTORE, false), 0).build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private fun notification(restore: Boolean, percent: Int) =
        NotificationCompat.Builder(context, Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.stringResource(if (restore) ATMR.strings.full_backup_restoring else ATMR.strings.full_backup_making))
            .setContentText("$percent%")
            .setProgress(100, percent, percent == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

    /** Where the work stands, for the screen. */
    data class State(val running: Boolean, val percent: Int, val name: String, val finished: WorkInfo?)

    companion object {
        private const val TAG = "ManhwaFullBackup"
        private const val NOTIFICATION_ID = -511

        private const val KEY_URI = "uri"
        private const val KEY_RESTORE = "restore"
        private const val KEY_ALL = "all_downloads"
        private const val KEY_PAGES = "translated_pages"
        const val KEY_PERCENT = "percent"
        const val KEY_NAME = "name"
        const val KEY_FILES = "files"
        const val KEY_CHAPTERS = "chapters"
        const val KEY_LINES = "lines"
        const val KEY_ERROR = "error"
        const val ERROR_NOT_BACKUP = "not_backup"

        fun backup(context: Context, uri: Uri, allDownloads: Boolean, translatedPages: Boolean) =
            start(context, uri, restore = false, allDownloads, translatedPages)

        fun restore(context: Context, uri: Uri) = start(context, uri, restore = true, allDownloads = false, translatedPages = false)

        private fun start(context: Context, uri: Uri, restore: Boolean, allDownloads: Boolean, translatedPages: Boolean) {
            val request = OneTimeWorkRequestBuilder<FullBackupWorker>()
                .addTag(TAG)
                .setInputData(workDataOf(KEY_URI to uri.toString(), KEY_RESTORE to restore, KEY_ALL to allDownloads, KEY_PAGES to translatedPages))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        /** The latest backup or restore, live. */
        fun state(context: Context): Flow<State?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(TAG).map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                State(
                    running = !info.state.isFinished,
                    percent = info.progress.getInt(KEY_PERCENT, 0),
                    name = info.progress.getString(KEY_NAME).orEmpty(),
                    finished = info.takeIf { it.state.isFinished },
                )
            }
    }
}
