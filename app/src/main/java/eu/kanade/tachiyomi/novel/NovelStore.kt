package eu.kanade.tachiyomi.novel

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.translation.readTextAtomic
import eu.kanade.translation.writeTextAtomic
import ireader.core.source.model.MangaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/** A novel the reader opened or saved, as its source gave it. */
@Serializable
data class NovelEntry(
    val sourceId: Long,
    val url: String,
    val title: String,
    val cover: String = "",
    val author: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val status: Long = 0L,
    val favorite: Boolean = false,
    val addedAt: Long = 0L,
    /** How many chapters the source had the last time the novel was opened. */
    val chapterCount: Int = 0,
) {
    val key: String get() = NovelStore.key(sourceId, url)

    fun toInfo() = MangaInfo(key = url, title = title, author = author, description = description, genres = genres, status = status, cover = cover)
}

/** Where a chapter was left: how far down it, whether it was read to the end, and when. */
@Serializable
data class NovelProgress(
    val novelKey: String,
    val chapterUrl: String,
    val chapterName: String = "",
    val fraction: Float = 0f,
    val read: Boolean = false,
    val at: Long = 0L,
)

/** How the reader likes their text. */
@Serializable
data class NovelReaderSettings(
    val fontSize: Int = 18,
    val lineHeight: Float = 1.75f,
    val margin: Int = 20,
    val theme: Int = THEME_DARK,
    val font: Int = FONT_SANS,
    val justify: Boolean = true,
    /** Shows the translation under each paragraph's original instead of in its place. */
    val sideBySide: Boolean = false,
    /** Listening: speed and pitch, the voices of the narrator, of the men and of the women (by name, empty = picked by the app). */
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    val voiceNarrator: String = "",
    val voiceMale: String = "",
    val voiceFemale: String = "",
    /** Goes on to the next chapter when one ends while listening. */
    val ttsAutoNext: Boolean = true,
) {
    companion object {
        const val THEME_PAPER = 0
        const val THEME_SEPIA = 1
        const val THEME_DARK = 2
        const val THEME_AMOLED = 3
        const val FONT_SANS = 0
        const val FONT_SERIF = 1
    }
}

@Serializable
private data class NovelData(
    val novels: Map<String, NovelEntry> = emptyMap(),
    val progress: Map<String, NovelProgress> = emptyMap(),
    val settings: NovelReaderSettings = NovelReaderSettings(),
)

/**
 * MANHWA novels: the novel library, what was read and the reader's text settings, in one file of its own (the manga
 * database is not touched); a chapter's text and its Arabic translation are kept as files beside it. New fields
 * always get defaults, so an older file keeps reading.
 */
@Inject
@SingleIn(AppScope::class)
class NovelStore(
    private val context: Context,
    private val json: Json,
) {

    private val root get() = File(context.filesDir, "novel")
    private val file get() = File(root, "novels.json")
    private val _data = MutableStateFlow(read())

    private val _novels = MutableStateFlow(_data.value.novels)
    val library: StateFlow<Map<String, NovelEntry>> = _novels.asStateFlow()
    private val _progress = MutableStateFlow(_data.value.progress)
    val progress: StateFlow<Map<String, NovelProgress>> = _progress.asStateFlow()
    private val _settings = MutableStateFlow(_data.value.settings)
    val settings: StateFlow<NovelReaderSettings> = _settings.asStateFlow()

    private fun read(): NovelData = runCatching {
        val f = file
        if (!f.exists() && !File("${f.path}.bak").exists()) NovelData() else json.decodeFromString<NovelData>(f.readTextAtomic())
    }.onFailure { logcat(LogPriority.ERROR, it) { "Novel data not read" } }.getOrDefault(NovelData())

    @Synchronized
    private fun save(change: (NovelData) -> NovelData) {
        val next = change(_data.value)
        _data.value = next
        _novels.value = next.novels
        _progress.value = next.progress
        _settings.value = next.settings
        // Written off the main thread, one write after the other, so reading never waits on the disk
        writer.execute {
            runCatching {
                root.mkdirs()
                file.writeTextAtomic(json.encodeToString(NovelData.serializer(), next))
            }.onFailure { logcat(LogPriority.ERROR, it) { "Novel data not saved" } }
        }
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun entry(sourceId: Long, url: String): NovelEntry? = _novels.value[key(sourceId, url)]

    fun favorites(): List<NovelEntry> = _novels.value.values.filter { it.favorite }.sortedByDescending { it.addedAt }

    /** Saves what the source says about the novel (its library state and chapter count are kept). */
    fun remember(sourceId: Long, info: MangaInfo, chapterCount: Int? = null): NovelEntry {
        val k = key(sourceId, info.key)
        val old = _novels.value[k]
        val entry = NovelEntry(
            sourceId = sourceId,
            url = info.key,
            title = info.title.ifBlank { old?.title.orEmpty() },
            cover = info.cover.ifBlank { old?.cover.orEmpty() },
            author = info.author.ifBlank { old?.author.orEmpty() },
            description = info.description.ifBlank { old?.description.orEmpty() },
            genres = info.genres.ifEmpty { old?.genres.orEmpty() },
            status = if (info.status != 0L) info.status else old?.status ?: 0L,
            favorite = old?.favorite ?: false,
            addedAt = old?.addedAt ?: 0L,
            chapterCount = chapterCount ?: old?.chapterCount ?: 0,
        )
        save { it.copy(novels = it.novels + (k to entry)) }
        return entry
    }

    fun setFavorite(entry: NovelEntry, favorite: Boolean) {
        save { data ->
            val now = data.novels[entry.key] ?: entry
            val added = if (favorite) System.currentTimeMillis() else now.addedAt
            data.copy(novels = data.novels + (entry.key to now.copy(favorite = favorite, addedAt = added)))
        }
    }

    fun progressOf(novelKey: String, chapterUrl: String): NovelProgress? = _progress.value[pkey(novelKey, chapterUrl)]

    /** Keeps how far down [chapterUrl] the reader is; past [READ_FRACTION] it counts as read. */
    fun saveProgress(novelKey: String, chapterUrl: String, name: String, fraction: Float) {
        save { data ->
            val k = pkey(novelKey, chapterUrl)
            val old = data.progress[k]
            val read = fraction >= READ_FRACTION || old?.read == true
            data.copy(progress = data.progress + (k to NovelProgress(novelKey, chapterUrl, name, fraction, read, System.currentTimeMillis())))
        }
    }

    fun setRead(novelKey: String, chapterUrl: String, name: String, read: Boolean) {
        save { data ->
            val k = pkey(novelKey, chapterUrl)
            val old = data.progress[k] ?: NovelProgress(novelKey, chapterUrl, name)
            data.copy(progress = data.progress + (k to old.copy(read = read, fraction = if (read) 1f else 0f, at = System.currentTimeMillis())))
        }
    }

    /** The last chapter read of each novel, newest first (the history and "continue reading"). */
    fun history(): List<Pair<NovelEntry, NovelProgress>> =
        _progress.value.values.groupBy { it.novelKey }
            .mapNotNull { (k, list) -> _novels.value[k]?.let { it to list.maxBy { p -> p.at } } }
            .sortedByDescending { it.second.at }

    fun updateSettings(change: (NovelReaderSettings) -> NovelReaderSettings) {
        save { it.copy(settings = change(it.settings)) }
    }

    // ---- A chapter's text and its translation, kept as files ----

    private fun textFile(novelKey: String, chapterUrl: String, kind: String) =
        File(root, "text/${hash("$novelKey#$chapterUrl")}.$kind.txt")

    fun cachedText(novelKey: String, chapterUrl: String): String? =
        textFile(novelKey, chapterUrl, "src").takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun cacheText(novelKey: String, chapterUrl: String, text: String) {
        runCatching { textFile(novelKey, chapterUrl, "src").apply { parentFile?.mkdirs() }.writeTextAtomic(text) }
    }

    fun translation(novelKey: String, chapterUrl: String): String? =
        textFile(novelKey, chapterUrl, "ar").takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun saveTranslation(novelKey: String, chapterUrl: String, text: String) {
        runCatching { textFile(novelKey, chapterUrl, "ar").apply { parentFile?.mkdirs() }.writeTextAtomic(text) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Novel translation not saved" } }
    }

    fun deleteTranslation(novelKey: String, chapterUrl: String) {
        textFile(novelKey, chapterUrl, "ar").delete()
    }

    companion object {
        const val READ_FRACTION = 0.95f

        fun key(sourceId: Long, url: String) = "$sourceId|$url"
        private fun pkey(novelKey: String, chapterUrl: String) = "$novelKey#$chapterUrl"

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
