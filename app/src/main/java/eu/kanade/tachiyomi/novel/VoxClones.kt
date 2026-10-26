package eu.kanade.tachiyomi.novel

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.GeneratedAudio
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.novel.vox.OpenVoice
import eu.kanade.tachiyomi.novel.vox.Resampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** A voice made in VoxGate and brought here as its `.voxvoice.zip`: its id and the name it was given there. */
data class VoiceClone(val id: String, val name: String)

/**
 * MANHWA novels: the reader's own voices, made in VoxGate (the voice-cloning app) and imported here from the file it
 * exports (`<name>.voxvoice.zip`: `ref.wav`, `emb.bin`, `meta.txt`). The file holds the voice's timbre, not a speech
 * model, so the voice speaks in two steps, both on the phone: a Piper voice of the text's language (Arabic or English,
 * the ones already downloaded) says the line, and OpenVoice, the same tone-colour converter VoxGate uses, gives it the
 * timbre of the imported voice. The words, the accent and the rhythm stay the Piper voice's; the sound is the reader's.
 */
object VoxClones {

    /** An imported voice's id starts with this, so it is never taken for one of Piper's. */
    const val PREFIX = "vox:"

    private const val MODELS_URL = "https://huggingface.co/eugenehp/openvoice/resolve/main"
    private val MODEL_FILES = listOf("tone_extract.onnx", "tone_color.onnx")

    /** How strongly the timbre is swapped (0 keeps the Piper voice, 1 is all the imported one); VoxGate's default. */
    private const val TAU = 0.3f

    private val _clones = MutableStateFlow<List<VoiceClone>>(emptyList())
    val clones: StateFlow<List<VoiceClone>> = _clones.asStateFlow()

    /** Whether the OpenVoice models are on the phone. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The models' download, 0 to 1; null when none is going on. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private fun root(context: Context) = File(context.filesDir, "vox")
    private fun modelsDir(context: Context) = File(root(context), "openvoice")
    private fun voicesDir(context: Context) = File(root(context), "voices")

    fun refresh(context: Context) {
        _ready.value = OpenVoice.filesPresent(modelsDir(context))
        _clones.value = voicesDir(context).listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "ref.wav").exists() }
            .sortedBy { it.name }
            .map { VoiceClone(it.name, nameOf(it)) }
    }

    private fun nameOf(folder: File): String =
        runCatching { File(folder, "meta.txt").readLines().firstOrNull().orEmpty() }.getOrDefault("").ifBlank { folder.name }

    // ---- The models ----

    /** Downloads the two OpenVoice models; true when they are ready. */
    suspend fun downloadModels(context: Context, network: NetworkHelper): Boolean = withContext(Dispatchers.IO) {
        if (_progress.value != null) return@withContext false
        _progress.value = 0f
        try {
            val dir = modelsDir(context).also { it.mkdirs() }
            MODEL_FILES.forEachIndexed { index, name ->
                val dest = File(dir, name)
                if (dest.exists()) return@forEachIndexed
                val tmp = File(dir, "$name.part")
                network.client.newCall(GET("$MODELS_URL/$name")).awaitSuccess().use { response ->
                    val total = response.body.contentLength().takeIf { it > 0 } ?: -1L
                    response.body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) _progress.value = (index + read.toFloat() / total) / MODEL_FILES.size
                            }
                        }
                    }
                }
                check(tmp.renameTo(dest)) { "Model file not moved" }
            }
            refresh(context)
            _ready.value
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OpenVoice models not downloaded" }
            false
        } finally {
            _progress.value = null
        }
    }

    fun deleteModels(context: Context) {
        release()
        modelsDir(context).deleteRecursively()
        refresh(context)
    }

    // ---- The voices ----

    /** Imports a `.voxvoice.zip`; returns the voice's id, or null when the file is not one. */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            var ref: ByteArray? = null
            var emb: ByteArray? = null
            var meta: String? = null
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        when (entry.name.substringAfterLast('/').lowercase()) {
                            "ref.wav" -> ref = zip.readBytes()
                            "emb.bin" -> emb = zip.readBytes()
                            "meta.txt" -> meta = zip.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                }
            }
            val wav = ref
            if (wav == null || wav.size <= 44 || wav.size > MAX_WAV) return@withContext null
            val id = "v${System.currentTimeMillis()}"
            val dir = File(voicesDir(context), id).also { it.mkdirs() }
            File(dir, "ref.wav").writeBytes(wav)
            // The timbre embedding is 256 floats; without a good one it is worked out from the clip when first needed
            emb?.takeIf { it.size == OpenVoice.EMB * 4 }?.let { File(dir, "emb.bin").writeBytes(it) }
            File(dir, "meta.txt").writeText(meta?.takeIf { it.isNotBlank() } ?: "${uri.lastPathSegment.orEmpty().substringAfterLast('/').removeSuffix(".voxvoice.zip").removeSuffix(".zip")}\n")
            refresh(context)
            id
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Voice file not imported" }
            null
        }
    }

    fun delete(context: Context, id: String) {
        release()
        File(voicesDir(context), id.removePrefix(PREFIX)).deleteRecursively()
        refresh(context)
    }

    // ---- Speaking ----

    private var openVoice: OpenVoice? = null

    @Synchronized
    private fun converter(context: Context): OpenVoice =
        openVoice ?: OpenVoice(modelsDir(context)).also { openVoice = it }

    /** Lets go of the converter (it holds its models in memory). */
    @Synchronized
    fun release() {
        runCatching { openVoice?.close() }
        openVoice = null
    }

    private fun embeddingOf(folder: File, ov: OpenVoice): FloatArray {
        val file = File(folder, "emb.bin")
        if (file.length() == OpenVoice.EMB * 4L) {
            val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(OpenVoice.EMB) { bb.getFloat() }
        }
        val (samples, rate) = readWav(File(folder, "ref.wav"))
        val emb = ov.embed(Resampler.resample(samples, rate, OpenVoice.SR))
        val bb = ByteBuffer.allocate(emb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        emb.forEach { bb.putFloat(it) }
        file.writeBytes(bb.array())
        return emb
    }

    /** The id of the Piper voice that says [text] before the timbre changes: one of the text's language, already on the phone. */
    private fun baseVoice(text: String): String? {
        val arabic = text.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' } > text.count { it.isLetter() && it !in '؀'..'ۿ' && it !in 'ݐ'..'ݿ' }
        val language = if (arabic) "ar" else "en"
        val installed = PiperVoices.installed.value
        return PiperVoices.all().firstOrNull { it.language == language && it.id in installed }?.id
    }

    /** [text] said in the imported voice [id] (with its prefix) at [speed]; null when it cannot be done now. */
    fun generate(context: Context, id: String, text: String, speed: Float): GeneratedAudio? {
        val folder = File(voicesDir(context), id.removePrefix(PREFIX))
        if (!folder.isDirectory || !OpenVoice.filesPresent(modelsDir(context))) return null
        val base = baseVoice(text) ?: return null
        return runCatching {
            val ov = converter(context)
            val emb = embeddingOf(folder, ov)
            val parts = ArrayList<FloatArray>()
            // A sentence at a time: the converter works on the whole clip at once, so short pieces keep memory and waiting small
            for (sentence in text.split(Regex("(?<=[.!?؟۔…\\n])\\s+")).filter { it.isNotBlank() }) {
                val said = PiperVoices.generate(context, base, sentence, speed) ?: continue
                val source = Resampler.resample(said.samples, said.sampleRate, OpenVoice.SR)
                if (source.size < MIN_SAMPLES) continue
                parts += ov.convert(source, emb, TAU)
            }
            if (parts.isEmpty()) return null
            val all = FloatArray(parts.sumOf { it.size })
            var at = 0
            for (p in parts) { p.copyInto(all, at); at += p.size }
            GeneratedAudio(all, OpenVoice.SR)
        }.onFailure { logcat(LogPriority.ERROR, it) { "Imported voice $id did not speak" } }.getOrNull()
    }

    /** Mono samples and rate of a 16-bit PCM WAV (the clips VoxGate writes). */
    private fun readWav(file: File): Pair<FloatArray, Int> {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rate = bb.getInt(24)
        // The samples start after the "data" chunk header (44 bytes in a plain WAV)
        var start = 44
        for (i in 12 until bytes.size - 8) {
            if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() && bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()) {
                start = i + 8
                break
            }
        }
        val n = (bytes.size - start) / 2
        return FloatArray(n) { bb.getShort(start + it * 2) / 32768f } to rate
    }

    private const val MAX_WAV = 20 * 1024 * 1024
    private const val MIN_SAMPLES = 2048
}
