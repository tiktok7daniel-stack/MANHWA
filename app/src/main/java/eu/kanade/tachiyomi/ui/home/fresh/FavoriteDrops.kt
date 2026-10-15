package eu.kanade.tachiyomi.ui.home.fresh

import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.at.ATMR
import java.io.File
import mihon.icons.materialsymbols.R as MaterialSymbolsR

/**
 * MANHWA favorite drops: a new chapter of a manhwa in the library. Each one shows a glass banner inside the app,
 * a high priority notification with the cover outside it, and stays in the bell's archive.
 */
@Inject
@SingleIn(AppScope::class)
class FavoriteDrops(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
) {

    @Serializable
    data class Drop(
        val mangaId: Long,
        val chapterId: Long,
        val title: String,
        val chapterName: String,
        val chapterNumber: Double = -1.0,
        val sourceName: String = "",
        val thumbnail: String? = null,
        val at: Long = System.currentTimeMillis(),
    ) {
        val key get() = "$mangaId|$chapterId"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "favorite_drops.json")
    private val mutex = Mutex()

    private val _live = MutableSharedFlow<Drop>(extraBufferCapacity = 32)

    /** Drops as they happen, for the in-app banner. */
    val live: SharedFlow<Drop> = _live.asSharedFlow()

    private val _archive = MutableStateFlow<List<Drop>>(emptyList())

    /** Every drop of the last weeks, newest first, for the bell. */
    val archive: StateFlow<List<Drop>> = _archive.asStateFlow()

    init {
        runCatching { file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<List<Drop>>(it) } }
            .getOrNull()
            ?.let { _archive.value = it }
    }

    /**
     * Records new chapters of [manga]. With [notify], also posts the system notification (the library update
     * posts its own, so it passes false).
     */
    suspend fun post(manga: Manga, chapters: List<Chapter>, sourceName: String, notify: Boolean) {
        if (chapters.isEmpty() || !manga.favorite) return
        val known = _archive.value.map { it.key }.toSet()
        val drops = chapters.sortedByDescending { it.chapterNumber }.map { chapter ->
            Drop(
                mangaId = manga.id,
                chapterId = chapter.id,
                title = manga.title,
                chapterName = chapter.name,
                chapterNumber = chapter.chapterNumber,
                sourceName = sourceName,
                thumbnail = manga.thumbnailUrl,
            )
        }.filter { it.key !in known }
        if (drops.isEmpty()) return
        mutex.withLock {
            _archive.update { (drops + it).distinctBy { d -> d.key }.take(MAX_ARCHIVE) }
            withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(_archive.value)) } }
        }
        // One banner per manhwa: the newest chapter speaks for the others
        _live.tryEmit(drops.first())
        if (notify) notify(manga, chapters.maxBy { it.chapterNumber }, chapters.size, sourceName)
    }

    fun clear() {
        _archive.value = emptyList()
        runCatching { file.delete() }
    }

    /** The system notification: heads-up, the cover big, read now and translate in one tap. */
    suspend fun notify(manga: Manga, chapter: Chapter, count: Int, sourceName: String) {
        val hidden = securityPreferences.hideNotificationContent.get()
        val cover = if (hidden) null else loadCover(manga, circle = false)
        val icon = if (hidden) null else loadCover(manga, circle = true)
        val number = if (chapter.isRecognizedNumber) formatChapterNumber(chapter.chapterNumber) else chapter.name
        val text = if (hidden) {
            context.stringResource(ATMR.strings.drop_text_hidden)
        } else {
            context.stringResource(ATMR.strings.drop_text, number, manga.title, sourceName)
        }
        context.notify(manga.id.hashCode(), Notifications.CHANNEL_FAVORITE_DROPS) {
            setSmallIcon(R.drawable.ic_launcher_monochrome)
            setContentTitle(if (hidden) context.stringResource(ATMR.strings.drop_badge) else manga.title)
            setContentText(text)
            setSubText(if (count > 1) context.stringResource(ATMR.strings.drop_more, count - 1) else context.stringResource(ATMR.strings.drop_badge))
            if (icon != null) setLargeIcon(icon)
            if (cover != null) {
                setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(cover)
                        .bigLargeIcon(null as Bitmap?)
                        .setSummaryText(text),
                )
            } else {
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
            }
            priority = NotificationCompat.PRIORITY_HIGH
            setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            setColor(0xFFFF3B4E.toInt())
            setGroup(Notifications.GROUP_NEW_CHAPTERS)
            setAutoCancel(true)
            setContentIntent(NotificationReceiver.openChapterPendingActivity(context, manga, chapter))
            addAction(
                MaterialSymbolsR.drawable.rounded_book,
                context.stringResource(ATMR.strings.drop_read_now),
                NotificationReceiver.openChapterPendingActivity(context, manga, chapter),
            )
            addAction(
                MaterialSymbolsR.drawable.rounded_translate,
                context.stringResource(ATMR.strings.drop_translate),
                NotificationReceiver.translateChapterPendingBroadcast(context, manga, chapter),
            )
        }
    }

    private suspend fun loadCover(manga: Manga, circle: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(manga.asMangaCover())
                .allowHardware(false)
                .size(if (circle) ICON_SIZE else PICTURE_SIZE)
                .apply { if (circle) transformations(CircleCropTransformation()) }
                .build()
            context.imageLoader.execute(request).image?.asDrawable(context.resources)?.getBitmapOrNull()
        }.getOrNull()
    }

    private companion object {
        const val MAX_ARCHIVE = 150
        const val ICON_SIZE = 256
        const val PICTURE_SIZE = 720
    }
}
