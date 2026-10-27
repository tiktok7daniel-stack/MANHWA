package eu.kanade.tachiyomi.music

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * MANHWA: the sound of a video as a song, the way Lark Player turns an MP4 into music. The audio track is copied out
 * as it is into an .m4a (AAC, which phone and TikTok videos use), so it is fast and loses nothing. A video whose
 * sound cannot go into an .m4a is kept whole: the player plays its sound and leaves the picture.
 */
object VideoToAudio {

    fun extract(video: File, out: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(video.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/mp4a") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
            val buffer = ByteBuffer.allocate(size.coerceAtLeast(64 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.offset = 0
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            out.length() > 0
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not take the sound out of a video" }
            out.delete()
            false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    /** A frame from a second into the video, as JPEG, for the song's picture. */
    fun frame(video: File): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val bitmap = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                bitmap.recycle()
                out.toByteArray()
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()
}
