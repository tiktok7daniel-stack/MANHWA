package eu.kanade.tachiyomi.music

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA music: plays a work's songs while its page or its chapters are open. Opening the work starts them (fading
 * in); leaving it fades them out; the app going to the background pauses them. The songs follow each other, repeat
 * one, or shuffle, as set for the work. One player for the whole app, on the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object MusicPlayer {

    data class State(
        val mangaId: Long? = null,
        val queue: List<MusicStore.Track> = emptyList(),
        val index: Int = 0,
        val playing: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val mode: String = "NEXT",
    ) {
        val track: MusicStore.Track? get() = queue.getOrNull(index)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = MainScope()
    private var player: ExoPlayer? = null
    private var store: MusicStore? = null
    private var leaveJob: Job? = null
    private var fadeJob: Job? = null
    private var tickJob: Job? = null
    private var pausedByBackground = false

    private fun ensure(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        store = MusicStore.get(app)
        return ExoPlayer.Builder(app, beatRenderers(app)).build().also { p ->
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            p.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.update { it.copy(playing = isPlaying) }
                        if (isPlaying) tick() else tickJob?.cancel()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        _state.update { it.copy(index = p.currentMediaItemIndex, positionMs = 0L, durationMs = p.duration.coerceAtLeast(0L)) }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // A song whose file is gone or unreadable: on to the next one
                        logcat(LogPriority.WARN, error) { "Music could not play a song" }
                        if (p.mediaItemCount > 1 && p.hasNextMediaItem()) {
                            p.seekToNextMediaItem()
                            p.prepare()
                            p.play()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) _state.update { it.copy(durationMs = p.duration.coerceAtLeast(0L)) }
                    }
                },
            )
            player = p
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        if (p.isPlaying) {
                            pausedByBackground = true
                            p.pause()
                        }
                    }

                    override fun onStart(owner: LifecycleOwner) {
                        if (pausedByBackground && _state.value.mangaId != null) fadeIn()
                        pausedByBackground = false
                    }
                },
            )
        }
    }

    /** The usual renderers, with the sound also passed through [MusicBeat] so the effects can follow the beat. */
    private fun beatRenderers(context: Context) = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(MusicBeat)))
                .build()
    }

    /** The work [mangaId] is on screen (its page or a chapter): its songs play, unless they already are. */
    fun attach(context: Context, mangaId: Long) {
        leaveJob?.cancel()
        val p = ensure(context)
        val s = store ?: return
        val tracks = s.tracksOf(mangaId)
        if (_state.value.mangaId == mangaId && _state.value.queue.map { it.id } == tracks.map { it.id }) {
            if (!p.isPlaying && s.library.value.autoplay && !pausedByUser) fadeIn()
            return
        }
        load(mangaId, tracks, startIndex = 0, play = s.library.value.autoplay)
    }

    /** The work's page or chapter closed: unless another screen of the same work opens at once, the music fades out. */
    fun leave(mangaId: Long) {
        if (_state.value.mangaId != mangaId) return
        leaveJob?.cancel()
        leaveJob = scope.launch {
            delay(1_500)
            fadeOut(stop = true)
        }
    }

    /** The work's songs changed (added, removed): the queue follows, keeping the song playing when it stays. */
    fun refresh(context: Context, mangaId: Long, playIndex: Int? = null) {
        ensure(context)
        val s = store ?: return
        val tracks = s.tracksOf(mangaId)
        val current = _state.value.track?.id
        val keep = tracks.indexOfFirst { it.id == current }.takeIf { it >= 0 && playIndex == null }
        if (keep != null && _state.value.mangaId == mangaId) {
            if (tracks.map { it.id } != _state.value.queue.map { it.id }) {
                val pos = player?.currentPosition ?: 0L
                load(mangaId, tracks, keep, play = player?.isPlaying == true, seekMs = pos)
            }
            return
        }
        load(mangaId, tracks, playIndex ?: 0, play = playIndex != null || player?.isPlaying == true)
    }

    private var pausedByUser = false

    fun toggle(context: Context) {
        val p = ensure(context)
        if (p.isPlaying) {
            // The reader's own pause is at once, no fade
            pausedByUser = true
            fadeJob?.cancel()
            p.pause()
            p.volume = store?.library?.value?.volume ?: 0.7f
            _state.update { it.copy(playing = false) }
        } else {
            pausedByUser = false
            if (p.mediaItemCount == 0) _state.value.mangaId?.let { refresh(context, it, 0) } else fadeIn()
        }
    }

    fun next() = player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekTo(0, 0L) }

    fun previous() = player?.let { if (it.currentPosition > 3_000 || !it.hasPreviousMediaItem()) it.seekTo(0L) else it.seekToPreviousMediaItem() }

    fun playAt(context: Context, index: Int) {
        val p = ensure(context)
        if (index in 0 until p.mediaItemCount && index < _state.value.queue.size) {
            p.seekTo(index, 0L)
            pausedByUser = false
            fadeIn()
        }
    }

    fun seek(fraction: Float) = player?.let { p ->
        if (p.duration > 0) p.seekTo((p.duration * fraction.coerceIn(0f, 1f)).toLong())
        _state.update { it.copy(positionMs = p.currentPosition) }
    }

    fun setMode(context: Context, mangaId: Long, mode: String) {
        MusicStore.get(context).setMode(mangaId, mode)
        applyMode(mode)
    }

    fun setVolume(context: Context, volume: Float) {
        MusicStore.get(context).setVolume(volume)
        player?.volume = volume
    }

    private fun applyMode(mode: String) {
        val p = player ?: return
        p.repeatMode = if (mode == "ONE") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        p.shuffleModeEnabled = mode == "SHUFFLE"
        _state.update { it.copy(mode = mode) }
    }

    private fun load(mangaId: Long, tracks: List<MusicStore.Track>, startIndex: Int, play: Boolean, seekMs: Long = 0L) {
        val p = player ?: return
        val s = store ?: return
        fadeJob?.cancel()
        p.stop()
        p.clearMediaItems()
        _state.value = State(mangaId = mangaId, queue = tracks, index = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)), mode = s.work(mangaId).mode)
        if (tracks.isEmpty()) return
        p.setMediaItems(tracks.map { item(s, it) }, _state.value.index, seekMs)
        applyMode(s.work(mangaId).mode)
        p.prepare()
        if (play && !pausedByUser) fadeIn() else p.volume = s.library.value.volume
    }

    /** A song as the player takes it, its trimmed end cut off. */
    private fun item(s: MusicStore, track: MusicStore.Track): MediaItem {
        val builder = MediaItem.Builder().setUri(android.net.Uri.fromFile(s.audioFile(track))).setMediaId(track.id)
        if (track.trimEndMs > 0 && track.durationMs > track.trimEndMs) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(track.durationMs - track.trimEndMs).build(),
            )
        }
        return builder.build()
    }

    private fun fadeIn() {
        val p = player ?: return
        val target = store?.library?.value?.volume ?: 0.7f
        fadeJob?.cancel()
        fadeJob = scope.launch {
            p.volume = 0f
            p.play()
            for (i in 1..12) {
                p.volume = target * i / 12f
                delay(25)
            }
        }
    }

    private fun fadeOut(stop: Boolean) {
        val p = player ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val from = p.volume
            for (i in 19 downTo 0) {
                p.volume = from * i / 20f
                delay(35)
            }
            p.pause()
            p.volume = store?.library?.value?.volume ?: 0.7f
            if (stop) {
                p.stop()
                p.clearMediaItems()
                _state.value = State()
                pausedByUser = false
            }
        }
    }

    private fun tick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                player?.let { p -> _state.update { it.copy(positionMs = p.currentPosition, durationMs = p.duration.coerceAtLeast(0L)) } }
                delay(250)
            }
        }
    }
}
