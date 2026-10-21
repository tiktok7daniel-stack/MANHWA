package eu.kanade.tachiyomi.anime

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** What is being played: the anime, its episodes in order, and the one on screen. */
class AnimePlayback(
    val source: AnimeCatalogueSource,
    val entry: AnimeEntry,
    val episodes: List<SEpisode>,
    val index: Int,
) {
    companion object {
        /** Handed from the anime screen to the player (one at a time). */
        @Volatile
        var current: AnimePlayback? = null
    }
}

/**
 * MANHWA anime: the videos of an episode, as Aniyomi gets them: the hosters (extensions of lib 16+) or the old
 * video list, each hoster's videos, the links still to be worked out, sorted the way the source prefers.
 */
object AnimeVideos {

    suspend fun load(source: AnimeSource, episode: SEpisode): List<Video> {
        val hosters = hosters(source, episode)
        val videos = hosters.flatMap { hoster ->
            try {
                videosOf(source, hoster)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anime: hoster ${hoster.hosterName} not read" }
                emptyList()
            }
        }
        val ready = videos.mapNotNull { video ->
            if (video.initialized || source !is AnimeHttpSource) {
                video
            } else {
                try {
                    source.resolveVideo(video)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }.filter { it.videoUrl.isNotBlank() && it.videoUrl != "null" }
        return if (source is AnimeHttpSource) source.run { ready.sortVideos() } else ready
    }

    private suspend fun hosters(source: AnimeSource, episode: SEpisode): List<Hoster> {
        if (source !is AnimeHttpSource) return source.getVideoList(episode).toHosterList()
        return if (hasHosters(source)) {
            source.run { source.getHosterList(episode).sortHosters() }
        } else {
            source.run { source.getVideoList(episode).sortVideos() }.toHosterList()
        }
    }

    private suspend fun videosOf(source: AnimeSource, hoster: Hoster): List<Video> {
        val list = hoster.videoList ?: if (source is AnimeHttpSource) source.getVideoList(hoster) else emptyList()
        if (source !is AnimeHttpSource) return list
        return list.map { video -> if (video.videoUrl != "null") video else video.copy(videoUrl = source.getVideoUrl(video)) }
    }

    /** Whether the extension itself answers with hosters (lib 16+), as Aniyomi tells. */
    private fun hasHosters(source: AnimeHttpSource): Boolean {
        var current: Class<*> = source.javaClass
        while (true) {
            if (current == ParsedAnimeHttpSource::class.java || current == AnimeHttpSource::class.java) return false
            if (current.declaredMethods.any { it.name in HOSTER_METHODS }) return true
            current = current.superclass ?: return false
        }
    }

    private val HOSTER_METHODS = listOf("getHosterList", "hosterListRequest", "hosterListParse")
}
