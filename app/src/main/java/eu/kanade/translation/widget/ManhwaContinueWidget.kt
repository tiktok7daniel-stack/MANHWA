package eu.kanade.translation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA home-screen widget: the dark glass card of the app with the last work read, its chapter, how many of
 * its next chapters are already translated and waiting, and a "Continue" button that opens the reader where
 * the reader stopped. Refreshed when the reader closes and every hour.
 */
class ManhwaContinueWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraws every MANHWA widget on the home screen (no-op when there is none). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, ManhwaContinueWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            scope.launch { render(context.applicationContext, manager, ids) }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.manhwa_widget)
            runCatching {
                val graph = context.appGraph
                val last = withTimeoutOrNull(5_000) { graph.getHistory.subscribe("").first() }?.firstOrNull()
                if (last == null) {
                    views.setTextViewText(R.id.manhwa_widget_title, context.getString(R.string.manhwa_widget_empty))
                    views.setTextViewText(R.id.manhwa_widget_subtitle, "")
                    views.setOnClickPendingIntent(R.id.manhwa_widget_root, open(context, Intent(context, MainActivity::class.java)))
                } else {
                    val number = last.chapterNumber.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
                    val ready = waitingTranslated(context, last.mangaId)
                    views.setTextViewText(R.id.manhwa_widget_title, last.title)
                    views.setTextViewText(
                        R.id.manhwa_widget_subtitle,
                        if (ready > 0) context.getString(R.string.manhwa_widget_ready, number, ready) else context.getString(R.string.manhwa_widget_chapter, number),
                    )
                    val reader = open(context, ReaderActivity.newIntent(context, last.mangaId, last.chapterId))
                    views.setOnClickPendingIntent(R.id.manhwa_widget_continue, reader)
                    views.setOnClickPendingIntent(R.id.manhwa_widget_root, reader)
                }
            }.onFailure { logcat(LogPriority.WARN, it) { "The widget could not be drawn" } }
            ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
        }

        /** Unread chapters of the series already translated. */
        private suspend fun waitingTranslated(context: Context, mangaId: Long): Int = runCatching {
            val graph = context.appGraph
            val manga = graph.getMangaById.await(mangaId) ?: return 0
            val source = graph.sourceManager.get(manga.source) ?: return 0
            val files = graph.translationManager.getTranslatedFileNames(manga, source)
            if (files.isEmpty()) return 0
            graph.getChaptersByMangaId.await(mangaId).count { !it.read && graph.translationManager.getTranslationFileName(it) in files }
        }.getOrDefault(0)

        private fun open(context: Context, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context,
                intent.hashCode(),
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
