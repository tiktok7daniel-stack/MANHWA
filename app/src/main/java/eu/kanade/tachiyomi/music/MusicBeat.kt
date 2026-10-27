package eu.kanade.tachiyomi.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * MANHWA music: how loud the song is right now, and how strong its bass is, read from the sound itself as the player
 * plays it (no microphone, no permission). The effects round a work move with it.
 *
 * The player hands the sound over a little before it is heard, so each reading waits [LATENCY_NS] before it counts.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object MusicBeat : TeeAudioProcessor.AudioBufferSink {

    private const val SLOTS = 64
    private const val LATENCY_NS = 180_000_000L

    // A small ring of readings with the time each one is heard; written by the audio thread, read by the screen
    private val times = LongArray(SLOTS)
    private val levels = FloatArray(SLOTS)
    private val basses = FloatArray(SLOTS)

    @Volatile private var head = 0

    @Volatile private var encoding = C.ENCODING_PCM_16BIT

    @Volatile private var channels = 2

    @Volatile private var sampleRate = 44_100

    // One-pole low-pass state, for the bass
    private var low = 0f

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = sampleRateHz.coerceAtLeast(1)
        this.channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        low = 0f
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = data.position()
        val end = data.limit()
        // Only 16-bit and float sound is read; any other kind leaves the effects still rather than wild
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return
        val float = encoding == C.ENCODING_PCM_FLOAT
        val step = if (float) 4 else 2
        val frame = step * channels
        if (end - start < frame) return
        // ~150 Hz cut for the bass
        val alpha = (2f * Math.PI.toFloat() * 150f / sampleRate).coerceIn(0.001f, 1f)
        var sum = 0.0
        var bassSum = 0.0
        var count = 0
        var i = start
        while (i + frame <= end) {
            // The first channel is enough to hear the beat
            val s = if (float) data.getFloat(i) else data.getShort(i) / 32768f
            low += alpha * (s - low)
            sum += s * s
            bassSum += low * low
            count++
            i += frame
        }
        if (count == 0) return
        val next = (head + 1) % SLOTS
        times[next] = System.nanoTime() + LATENCY_NS
        levels[next] = sqrt(sum / count).toFloat()
        basses[next] = sqrt(bassSum / count).toFloat()
        head = next
    }

    /** The newest reading already heard: (loudness, bass), each about 0..1. */
    fun now(): Pair<Float, Float> {
        val t = System.nanoTime()
        var i = head
        repeat(SLOTS) {
            if (times[i] != 0L && times[i] <= t) return levels[i] to basses[i]
            i = (i - 1 + SLOTS) % SLOTS
        }
        return 0f to 0f
    }
}

/**
 * MANHWA: the beat of the work's song, 0..1, for the effects. It jumps up with each hit and falls back slowly, so
 * the light pulses with the bass. 0 while nothing of [workId]'s plays.
 */
@Composable
fun rememberBeat(workId: Long): State<Float> {
    val beat = remember { mutableFloatStateOf(0f) }
    val music by MusicPlayer.state.collectAsState()
    val on = music.playing && music.mangaId == workId
    LaunchedEffect(on) {
        if (!on) {
            beat.floatValue = 0f
            return@LaunchedEffect
        }
        // The loudness is measured against the song's own recent level, so a quiet song pulses as well as a loud one
        var average = 0.05f
        var bassAverage = 0.03f
        while (true) {
            // MANHWA battery: about 30 readings a second is plenty for the eye, and costs far less than every frame
            // of a fast screen
            kotlinx.coroutines.delay(33)
            withFrameNanos { }
            val (level, bass) = MusicBeat.now()
            average += (level - average) * 0.04f
            bassAverage += (bass - bassAverage) * 0.04f
            val hit = ((bass / bassAverage.coerceAtLeast(0.005f)) - 0.9f).coerceIn(0f, 1.6f) / 1.6f
            val body = (level / average.coerceAtLeast(0.01f) * 0.5f).coerceIn(0f, 1f)
            val target = (hit * 0.75f + body * 0.25f).coerceIn(0f, 1f)
            val current = beat.floatValue
            beat.floatValue = if (target > current) current + (target - current) * 0.8f else current + (target - current) * 0.16f
        }
    }
    return beat
}
