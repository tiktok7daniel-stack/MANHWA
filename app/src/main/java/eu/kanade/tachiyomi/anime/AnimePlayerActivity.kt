package eu.kanade.tachiyomi.anime

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Speed
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.Tune
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA anime: the episode player (Media3). The controls of the player itself (play, seek, subtitles, speed)
 * and, on top, the episode's name, the quality, an 85-second skip (the opening) and the next episode. It goes
 * on from where the episode was left, keeps the place every few seconds and goes to the next one at the end.
 */
@OptIn(UnstableApi::class)
class AnimePlayerActivity : BaseActivity() {

    private var player: ExoPlayer? = null
    private var playback: AnimePlayback? = null
    private var saver: Job? = null

    private var videos by mutableStateOf<List<Video>>(emptyList())
    private var videoIndex by mutableStateOf(0)
    private var episodeIndex by mutableStateOf(0)
    private var loading by mutableStateOf(true)
    private var controlsShown by mutableStateOf(true)
    private var showQualities by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pb = AnimePlayback.current
        if (pb == null) {
            finish()
            return
        }
        playback = pb
        episodeIndex = intent.getIntExtra(INDEX_KEY, pb.index)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val exo = ExoPlayer.Builder(this).build().also { player = it }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    saveNow()
                    next()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                logcat(LogPriority.ERROR, e) { "Anime: video not played" }
                // The next quality, if there is one, else the error
                if (videoIndex + 1 < videos.size) {
                    play(videoIndex + 1, keepPosition = true)
                } else {
                    error = e.errorCodeName
                }
            }
        })

        setComposeContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            this.player = exo
                            setShowSubtitleButton(true)
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsShown = v == android.view.View.VISIBLE })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                error?.let {
                    Text(
                        stringResource(ATMR.strings.anime_play_failed, it),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).glass(RoundedCornerShape(16.dp)).padding(16.dp),
                    )
                }
                if (controlsShown) TopControls(Modifier.align(Alignment.TopCenter))
                if (showQualities) Qualities(Modifier.align(Alignment.CenterEnd))
            }
            BackHandler { finish() }
        }
        loadEpisode()
    }

    @Composable
    private fun TopControls(modifier: Modifier) {
        val pb = playback ?: return
        Row(
            modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ManhwaKit.IconOrb(MaterialSymbols.AutoMirroredRounded.ArrowBack, size = 38) { finish() }
            Column(Modifier.weight(1f)) {
                Text(pb.entry.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pb.episodes.getOrNull(episodeIndex)?.name.orEmpty(),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (videos.size > 1) ManhwaKit.IconOrb(MaterialSymbols.Rounded.Tune, active = showQualities, size = 38) { showQualities = !showQualities }
            ManhwaKit.IconOrb(MaterialSymbols.Rounded.Speed, size = 38) {
                player?.let { it.seekTo(it.currentPosition + SKIP_MS) }
            }
            if (episodeIndex + 1 < pb.episodes.size) ManhwaKit.IconOrb(MaterialSymbols.Rounded.SkipNext, size = 38) { next() }
        }
    }

    @Composable
    private fun Qualities(modifier: Modifier) {
        LazyColumn(
            modifier.padding(16.dp).glass(RoundedCornerShape(18.dp), alpha = 0.8f).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(videos) { i, video ->
                Text(
                    video.videoTitle.ifBlank { "${i + 1}" },
                    color = if (i == videoIndex) MaterialTheme.colorScheme.primary else Color.White,
                    fontWeight = if (i == videoIndex) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .pressClickable {
                            showQualities = false
                            play(i, keepPosition = true)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }

    private fun loadEpisode() {
        val pb = playback ?: return
        val episode = pb.episodes.getOrNull(episodeIndex) ?: return finish()
        saveNow()
        loading = true
        error = null
        videos = emptyList()
        player?.stop()
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { AnimeVideos.load(pb.source, episode) }
                    .onFailure { logcat(LogPriority.ERROR, it) { "Anime: videos not found" } }
                    .getOrDefault(emptyList())
            }
            if (found.isEmpty()) {
                loading = false
                error = getString(android.R.string.unknownName)
                toast(ATMR.strings.anime_no_videos)
                return@launch
            }
            videos = found
            val preferred = found.indexOfFirst { it.preferred }.takeIf { it >= 0 } ?: 0
            play(preferred, keepPosition = false)
        }
    }

    /** Plays the [index]th video of the episode, from the place it was left (or where it is now). */
    private fun play(index: Int, keepPosition: Boolean) {
        val pb = playback ?: return
        val exo = player ?: return
        val video = videos.getOrNull(index) ?: return
        val episode = pb.episodes[episodeIndex]
        val position = if (keepPosition) {
            exo.currentPosition
        } else {
            appGraph.animeStore.progressOf(pb.entry.key, episode.url)?.takeIf { !it.seen }?.positionMs ?: 0L
        }
        videoIndex = index
        error = null
        val headers = (video.headers ?: (pb.source as? AnimeHttpSource)?.headers)
            ?.toMultimap()?.mapValues { it.value.firstOrNull().orEmpty() }.orEmpty()
        val dataSource = OkHttpDataSource.Factory(appGraph.networkHelper.client).setDefaultRequestProperties(headers)
        val subtitles = video.subtitleTracks.map { track ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(track.url))
                .setMimeType(subtitleMime(track.url))
                .setLanguage(track.lang)
                .setLabel(track.lang)
                .setSelectionFlags(if (track == video.subtitleTracks.first()) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(video.videoUrl)
            .setSubtitleConfigurations(subtitles)
            .apply { if (video.videoUrl.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        exo.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(item), position)
        exo.prepare()
        exo.playWhenReady = true
        loading = false
        startSaving()
    }

    private fun subtitleMime(url: String): String {
        val u = url.substringBefore('?').lowercase()
        return when {
            u.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            u.endsWith(".ass") || u.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            u.endsWith(".ttml") || u.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.TEXT_VTT
        }
    }

    private fun next() {
        val pb = playback ?: return
        if (episodeIndex + 1 >= pb.episodes.size) return
        episodeIndex += 1
        loadEpisode()
    }

    private fun startSaving() {
        saver?.cancel()
        saver = lifecycleScope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                saveNow()
            }
        }
    }

    private fun saveNow() {
        val pb = playback ?: return
        val exo = player ?: return
        val episode = pb.episodes.getOrNull(episodeIndex) ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: return
        appGraph.animeStore.saveProgress(pb.entry.key, episode.url, episode.name, exo.currentPosition, duration)
    }

    override fun onPause() {
        saveNow()
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        saver?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val INDEX_KEY = "episode_index"
        private const val SKIP_MS = 85_000L
        private const val SAVE_EVERY_MS = 5_000L

        fun newIntent(context: Context, index: Int): Intent =
            Intent(context, AnimePlayerActivity::class.java).putExtra(INDEX_KEY, index)
    }
}
