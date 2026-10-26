package eu.kanade.tachiyomi.novel.vox

// MANHWA: OpenVoice V2 tone-colour conversion, ported from the reader's VoxGate app (same models, same maths).

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenVoice V2 tone-colour conversion on ONNX Runtime.
 * Keeps the words/accent/prosody of the source audio and swaps in the timbre of a reference voice.
 * Mirrors tools/test_openvoice.py (22.05 kHz, 1024-point STFT, hop 256).
 */
class OpenVoice(dir: File) {
    private val env = OrtEnvironment.getEnvironment()
    private val extractor: OrtSession
    private val converter: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        extractor = env.createSession(File(dir, "tone_extract.onnx").absolutePath, opts)
        converter = env.createSession(File(dir, "tone_color.onnx").absolutePath, opts)
    }

    /** 256-float speaker (timbre) embedding of [audio], which must be mono 22.05 kHz in -1..1. */
    fun embed(audio: FloatArray): FloatArray {
        val spec = Spectrogram.compute(audio)
        // extractor wants frame-major [1, T, 513]
        val frameMajor = FloatArray(spec.frames * BINS)
        for (t in 0 until spec.frames) for (b in 0 until BINS) frameMajor[t * BINS + b] = spec.data[b * spec.frames + t]
        OnnxTensor.createTensor(env, FloatBuffer.wrap(frameMajor), longArrayOf(1, spec.frames.toLong(), BINS.toLong())).use { input ->
            extractor.run(mapOf("input" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val v = out[0].value as Array<FloatArray>
                return v[0].copyOf(EMB)
            }
        }
    }

    /** Re-voice [source] (mono 22.05 kHz) so it sounds like [targetEmbedding]. Returns 22.05 kHz audio. */
    fun convert(source: FloatArray, targetEmbedding: FloatArray, tau: Float = 0.3f): FloatArray {
        val sourceEmbedding = embed(source)
        val spec = Spectrogram.compute(source)
        val tensors = ArrayList<OnnxTensor>()
        try {
            tensors += OnnxTensor.createTensor(env, FloatBuffer.wrap(spec.data), longArrayOf(1, BINS.toLong(), spec.frames.toLong()))
            tensors += OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(spec.frames.toLong())), longArrayOf(1))
            tensors += OnnxTensor.createTensor(env, FloatBuffer.wrap(sourceEmbedding), longArrayOf(1, EMB.toLong(), 1))
            tensors += OnnxTensor.createTensor(env, FloatBuffer.wrap(targetEmbedding), longArrayOf(1, EMB.toLong(), 1))
            tensors += OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(tau)), longArrayOf(1))
            val inputs = mapOf(
                "audio" to tensors[0], "audio_length" to tensors[1],
                "src_tone" to tensors[2], "dest_tone" to tensors[3], "tau" to tensors[4],
            )
            converter.run(inputs).use { out ->
                @Suppress("UNCHECKED_CAST")
                val v = out[0].value as Array<Array<FloatArray>>
                return v[0][0]
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    fun close() {
        extractor.close()
        converter.close()
    }

    companion object {
        const val SR = 22050
        const val BINS = 513
        const val EMB = 256

        fun filesPresent(dir: File) = File(dir, "tone_extract.onnx").exists() && File(dir, "tone_color.onnx").exists()
    }
}

class Spec(val data: FloatArray, val frames: Int) // data is bin-major: [513][frames]

object Spectrogram {
    private const val N = 1024
    private const val HOP = 256
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / N)).toFloat() } // periodic Hann
    private val cosT = FloatArray(N / 2) { cos(2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { (-sin(2.0 * PI * it / N)).toFloat() }
    private val rev = IntArray(N).also { r ->
        val bits = 10
        for (i in 0 until N) {
            var x = i
            var y = 0
            repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }

    fun compute(audio: FloatArray): Spec {
        val pad = (N - HOP) / 2
        val padded = FloatArray(audio.size + 2 * pad)
        for (i in padded.indices) {
            var j = i - pad
            if (j < 0) j = -j                                  // reflect
            if (j >= audio.size) j = 2 * (audio.size - 1) - j
            padded[i] = audio[j.coerceIn(0, audio.size - 1)]
        }
        val frames = 1 + (padded.size - N) / HOP
        val out = FloatArray(OpenVoice.BINS * frames)
        val re = FloatArray(N)
        val im = FloatArray(N)
        for (t in 0 until frames) {
            val off = t * HOP
            for (i in 0 until N) { re[rev[i]] = padded[off + i] * window[i]; im[rev[i]] = 0f }
            fft(re, im)
            for (b in 0 until OpenVoice.BINS) {
                out[b * frames + t] = sqrt(re[b] * re[b] + im[b] * im[b] + 1e-6f)
            }
        }
        return Spec(out, frames)
    }

    // In-place iterative radix-2 FFT; input must already be bit-reversed.
    private fun fft(re: FloatArray, im: FloatArray) {
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var start = 0
            while (start < N) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val tr = re[j + half] * wr - im[j + half] * wi
                    val ti = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}

/** Windowed-sinc resampler (mono float). */
object Resampler {
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = to.toDouble() / from
        val outLen = (x.size * ratio).toInt()
        val cutoff = minOf(1.0, ratio) * 0.97
        val zc = 12
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val c = i / ratio
            val lo = (c - zc / cutoff).toInt().coerceAtLeast(0)
            val hi = (c + zc / cutoff).toInt().coerceAtMost(x.size - 1)
            var acc = 0.0
            var norm = 0.0
            for (j in lo..hi) {
                val d = (j - c) * cutoff
                val s = if (d == 0.0) 1.0 else sin(PI * d) / (PI * d)
                val w = 0.5 + 0.5 * cos(PI * d / zc).let { if (kotlin.math.abs(d) < zc) it else -1.0 }
                val k = s * w * cutoff
                acc += x[j] * k
                norm += k
            }
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }
}
