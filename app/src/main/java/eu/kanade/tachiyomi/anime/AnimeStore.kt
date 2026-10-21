package eu.kanade.tachiyomi.anime

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.translation.readTextAtomic
import eu.kanade.translation.writeTextAtomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** An anime the reader saved, as its source gave it. */
@Serializable
data class AnimeEntry(
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = 0,
    val favorite: Boolean = false,
    val addedAt: Long = 0L,
) {
    val key: String get() = AnimeStore.key(sourceId, url)
}

/** Where an episode was left: the position, its length, whether it was seen to the end, and when. */
@Serializable
data class EpisodeProgress(
    val animeKey: String,
    val episodeUrl: String,
    val episodeName: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val seen: Boolean = false,
    val at: Long = 0L,
)

@Serializable
private data class AnimeData(
    val anime: Map<String, AnimeEntry> = emptyMap(),
    val progress: Map<String, EpisodeProgress> = emptyMap(),
)

/**
 * MANHWA anime: the anime library and what was watched, in one file of its own (the manga database is not touched).
 * New fields always get defaults, so an older file keeps reading.
 */
@Inject
@SingleIn(AppScope::class)
class AnimeStore(
    private val context: Context,
    private val json: Json,
) {

    private val file get() = File(context.filesDir, "anime/anime.json")
    private val _data = MutableStateFlow(read())

    private val _anime = MutableStateFlow(_data.value.anime)
    val library: StateFlow<Map<String, AnimeEntry>> = _anime.asStateFlow()
    private val _progress = MutableStateFlow(_data.value.progress)
    val progress: StateFlow<Map<String, EpisodeProgress>> = _progress.asStateFlow()

    private fun read(): AnimeData = runCatching {
        val f = file
        if (!f.exists() && !File("${f.path}.bak").exists()) AnimeData() else json.decodeFromString<AnimeData>(f.readTextAtomic())
    }.onFailure { logcat(LogPriority.ERROR, it) { "Anime data not read" } }.getOrDefault(AnimeData())

    @Synchronized
    private fun save(change: (AnimeData) -> AnimeData) {
        val next = change(_data.value)
        _data.value = next
        _anime.value = next.anime
        _progress.value = next.progress
        runCatching {
            file.parentFile?.mkdirs()
            file.writeTextAtomic(json.encodeToString(AnimeData.serializer(), next))
        }.onFailure { logcat(LogPriority.ERROR, it) { "Anime data not saved" } }
    }

    fun entry(sourceId: Long, url: String): AnimeEntry? = _anime.value[key(sourceId, url)]

    fun favorites(): List<AnimeEntry> = _anime.value.values.filter { it.favorite }.sortedByDescending { it.addedAt }

    /** Saves what the source says about the anime (its library state is kept). */
    fun remember(sourceId: Long, anime: SAnime): AnimeEntry {
        val k = key(sourceId, anime.url)
        val old = _anime.value[k]
        val entry = AnimeEntry(
            sourceId = sourceId,
            url = anime.url,
            title = anime.title,
            thumbnailUrl = anime.thumbnail_url ?: old?.thumbnailUrl,
            description = anime.description ?: old?.description,
            genre = anime.genre ?: old?.genre,
            status = anime.status,
            favorite = old?.favorite ?: false,
            addedAt = old?.addedAt ?: 0L,
        )
        save { it.copy(anime = it.anime + (k to entry)) }
        return entry
    }

    fun setFavorite(entry: AnimeEntry, favorite: Boolean) {
        save { data ->
            val now = data.anime[entry.key] ?: entry
            data.copy(anime = data.anime + (entry.key to now.copy(favorite = favorite, addedAt = if (favorite) System.currentTimeMillis() else now.addedAt)))
        }
    }

    fun progressOf(animeKey: String, episodeUrl: String): EpisodeProgress? = _progress.value[pkey(animeKey, episodeUrl)]

    fun saveProgress(animeKey: String, episodeUrl: String, name: String, positionMs: Long, durationMs: Long) {
        // Seen when less than the last 8% (the end credits) is left
        val seen = durationMs > 0 && positionMs >= durationMs * SEEN_FRACTION
        save { data ->
            val old = data.progress[pkey(animeKey, episodeUrl)]
            val p = EpisodeProgress(animeKey, episodeUrl, name, positionMs, durationMs, seen || old?.seen == true, System.currentTimeMillis())
            data.copy(progress = data.progress + (pkey(animeKey, episodeUrl) to p))
        }
    }

    fun setSeen(animeKey: String, episodeUrl: String, name: String, seen: Boolean) {
        save { data ->
            val k = pkey(animeKey, episodeUrl)
            val old = data.progress[k] ?: EpisodeProgress(animeKey, episodeUrl, name)
            data.copy(progress = data.progress + (k to old.copy(seen = seen, positionMs = if (seen) old.positionMs else 0L, at = System.currentTimeMillis())))
        }
    }

    /** The last episode watched of each anime, newest first (the history and "continue"). */
    fun history(): List<Pair<AnimeEntry, EpisodeProgress>> =
        _progress.value.values.groupBy { it.animeKey }
            .mapNotNull { (k, list) -> _anime.value[k]?.let { it to list.maxBy { p -> p.at } } }
            .sortedByDescending { it.second.at }

    companion object {
        const val SEEN_FRACTION = 0.92

        fun key(sourceId: Long, url: String) = "$sourceId|$url"
        private fun pkey(animeKey: String, episodeUrl: String) = "$animeKey#$episodeUrl"
    }
}
