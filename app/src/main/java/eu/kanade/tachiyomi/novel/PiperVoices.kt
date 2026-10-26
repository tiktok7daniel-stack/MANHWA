package eu.kanade.tachiyomi.novel

import android.content.Context
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.zip.ZipInputStream

/** An open-source Piper voice: its model's name, its language, who it sounds like and where it comes from. */
data class PiperVoice(
    /** As sherpa-onnx publishes it: `<lang>-<name>-<quality>`, e.g. `ar_JO-kareem-medium`. */
    val id: String,
    val label: String,
    val lang: String,
    val female: Boolean?,
    /** Free for personal use only (CC BY-NC-SA); the others are free for any use. */
    val nonCommercial: Boolean = false,
) {
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$id.tar.bz2"
    val language get() = lang.substringBefore('_')
}

/**
 * MANHWA novels: Piper, the open-source voices (rhasspy/piper, as sherpa-onnx packages them), inside the app. A
 * voice is downloaded once from sherpa-onnx's releases and then speaks on the phone with no network. The engine is
 * sherpa-onnx's library (Apache-2.0), put in the APK by the build.
 */
object PiperVoices {

    /** The voices offered: every Arabic one there is, and a choice of English men and women. */
    val catalog = listOf(
        PiperVoice("ar_JO-kareem-medium", "Kareem", "ar_JO", female = false),
        PiperVoice("ar_JO-kareem-low", "Kareem (light)", "ar_JO", female = false),
        PiperVoice("ar_JO-SA_miro-high", "Miro", "ar_JO", female = null, nonCommercial = true),
        PiperVoice("ar_JO-SA_miro_V2-high", "Miro V2", "ar_JO", female = null, nonCommercial = true),
        PiperVoice("ar_JO-SA_dii-high", "Dii", "ar_JO", female = null, nonCommercial = true),
        PiperVoice("en_US-ryan-high", "Ryan", "en_US", female = false),
        PiperVoice("en_US-lessac-high", "Lessac", "en_US", female = true),
        PiperVoice("en_US-amy-medium", "Amy", "en_US", female = true),
        PiperVoice("en_US-joe-medium", "Joe", "en_US", female = false),
        PiperVoice("en_US-kristin-medium", "Kristin", "en_US", female = true),
        PiperVoice("en_US-norman-medium", "Norman", "en_US", female = false),
        PiperVoice("en_US-hfc_female-medium", "HFC (woman)", "en_US", female = true),
        PiperVoice("en_US-hfc_male-medium", "HFC (man)", "en_US", female = false),
        PiperVoice("en_GB-cori-high", "Cori", "en_GB", female = true),
        PiperVoice("en_GB-alan-medium", "Alan", "en_GB", female = false),
        PiperVoice("en_GB-northern_english_male-medium", "Northern man", "en_GB", female = false),
        PiperVoice("en_GB-jenny_dioco-medium", "Jenny", "en_GB", female = true),
    )

    /** Whether this APK has the engine (the build adds it for the phone's processor). */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("sherpa-onnx-jni") }.isSuccess
    }

    /** The folder-name prefix of the reader's own voices (imported from a file), so they never clash with the catalog. */
    private const val OWN = "my_"

    /** The reader's own voices, imported from a file (see [importVoice]). */
    private val _own = MutableStateFlow<List<PiperVoice>>(emptyList())
    val own: StateFlow<List<PiperVoice>> = _own.asStateFlow()

    /** The catalog and the reader's own voices. */
    fun all(): List<PiperVoice> = catalog + _own.value

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    /** Voices being downloaded, and how far (0 to 1). */
    private val _downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = _downloading.asStateFlow()

    private fun root(context: Context) = File(context.filesDir, "piper")
    private fun dir(context: Context, id: String) = File(root(context), id)

    fun refresh(context: Context) {
        VoxClones.refresh(context)
        _own.value = root(context).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(OWN) && model(context, it.name) != null }
            .sortedBy { it.name }
            .map { readOwn(it) }
        _installed.value = all().map { it.id }.filter { model(context, it) != null }.toSet()
    }

    /** An own voice from its folder; `voice.json` (name, lang, female) is optional. */
    private fun readOwn(folder: File): PiperVoice {
        val meta = runCatching { JSONObject(File(folder, "voice.json").readText()) }.getOrNull()
        val lang = meta?.optString("lang").orEmpty().ifBlank { "ar" }
        val female = if (meta != null && meta.has("female") && !meta.isNull("female")) meta.optBoolean("female") else null
        return PiperVoice(folder.name, meta?.optString("name").orEmpty().ifBlank { folder.name.removePrefix(OWN) }, lang, female)
    }

    /**
     * Imports a voice the reader made elsewhere: a zip holding (in a folder or at its root) one `.onnx` model and its
     * `tokens.txt`, and optionally `espeak-ng-data/` (taken from a voice already on the phone when it is missing) and
     * `voice.json` (`{"name": "My voice", "lang": "ar", "female": false}`). Returns the voice's id, or null when the
     * file is not a voice this engine can speak.
     */
    suspend fun importVoice(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val staging = File(root(context), "import.part")
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            val base = staging.canonicalPath + File.separator
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(staging, entry.name)
                    // Nothing may be written outside the staging folder
                    if (!out.canonicalPath.startsWith(base)) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            // The voice's files are at the root of the zip or in the one folder it holds
            val inner = staging.takeIf { f -> f.listFiles().orEmpty().any { it.extension == "onnx" } }
                ?: staging.listFiles().orEmpty().singleOrNull { it.isDirectory }
                ?: return@withContext null
            if (inner.listFiles().orEmpty().none { it.extension == "onnx" } || !File(inner, "tokens.txt").exists()) return@withContext null
            if (!File(inner, "espeak-ng-data").isDirectory) {
                val donor = catalog.map { dir(context, it.id) }.firstOrNull { File(it, "espeak-ng-data").isDirectory }
                    ?: return@withContext null
                File(donor, "espeak-ng-data").copyRecursively(File(inner, "espeak-ng-data"), overwrite = true)
            }
            val name = runCatching { JSONObject(File(inner, "voice.json").readText()).optString("name") }.getOrNull().orEmpty()
                .ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/').substringBeforeLast('.') }
                .ifBlank { "voice" }
            val id = OWN + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "voice" }
            val target = dir(context, id)
            release(id)
            target.deleteRecursively()
            check(inner.renameTo(target)) { "Voice folder not moved" }
            refresh(context)
            id.takeIf { model(context, it) != null }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Voice file not imported" }
            null
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The voice's files when it is on the phone: the model, its tokens and the phoneme data. */
    private fun model(context: Context, id: String): Triple<File, File, File>? {
        val d = dir(context, id)
        val onnx = d.listFiles()?.firstOrNull { it.extension == "onnx" } ?: return null
        val tokens = File(d, "tokens.txt").takeIf { it.exists() } ?: return null
        val data = File(d, "espeak-ng-data").takeIf { it.isDirectory } ?: return null
        return Triple(onnx, tokens, data)
    }

    /** Downloads and unpacks [voice]; true when it is ready to speak. */
    suspend fun download(context: Context, network: NetworkHelper, voice: PiperVoice): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "piper-${voice.id}.tar.bz2")
        val target = dir(context, voice.id)
        val staging = File(root(context), "${voice.id}.part")
        _downloading.value = _downloading.value + (voice.id to 0f)
        try {
            network.client.newCall(GET(voice.url)).awaitSuccess().use { response ->
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
                            if (total > 0) _downloading.value = _downloading.value + (voice.id to (read.toFloat() / total) * 0.9f)
                        }
                    }
                }
            }
            staging.deleteRecursively()
            staging.mkdirs()
            unpack(tmp, staging)
            // The archive holds one folder (vits-piper-<id>); its content becomes the voice's folder
            val inner = staging.listFiles()?.singleOrNull { it.isDirectory } ?: staging
            target.deleteRecursively()
            check(inner.renameTo(target)) { "Voice folder not moved" }
            refresh(context)
            model(context, voice.id) != null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Piper voice ${voice.id} not downloaded" }
            false
        } finally {
            tmp.delete()
            staging.deleteRecursively()
            _downloading.value = _downloading.value - voice.id
        }
    }

    private fun unpack(archive: File, into: File) {
        val base = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(into, entry.name)
                // Nothing may be written outside the voice's folder
                if (!out.canonicalPath.startsWith(base)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    fun delete(context: Context, id: String) {
        release(id)
        dir(context, id).deleteRecursively()
        refresh(context)
    }

    // ---- Speaking ----

    private val engines = HashMap<String, OfflineTts>()

    /** The engine of voice [id], loaded once (null when the voice is not on the phone or the engine is missing). */
    @Synchronized
    fun engine(context: Context, id: String): OfflineTts? {
        engines[id]?.let { return it }
        if (!available) return null
        val (onnx, tokens, data) = model(context, id) ?: return null
        return try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = onnx.absolutePath, tokens = tokens.absolutePath, dataDir = data.absolutePath),
                    numThreads = 2,
                ),
            )
            OfflineTts(config = config).also { engines[id] = it }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Piper voice $id not loaded" }
            null
        }
    }

    @Synchronized
    private fun release(id: String) {
        engines.remove(id)?.release()
    }

    /** Lets go of every loaded voice (they hold their model in memory). */
    @Synchronized
    fun releaseAll() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
        VoxClones.release()
    }

    /** [text] said in voice [id] at [speed], as samples and their rate; null when the voice cannot speak. */
    fun generate(context: Context, id: String, text: String, speed: Float): com.k2fsa.sherpa.onnx.GeneratedAudio? {
        // The reader's own voices (made in VoxGate) are a Piper voice with its timbre changed
        if (id.startsWith(VoxClones.PREFIX)) return VoxClones.generate(context, id, text, speed)
        val tts = engine(context, id) ?: return null
        return runCatching { tts.generate(text = text, sid = 0, speed = speed) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Piper voice $id did not speak" } }
            .getOrNull()
    }

    /** A float audio track for [sampleRate], playing as it is written. */
    fun newTrack(sampleRate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }
}
