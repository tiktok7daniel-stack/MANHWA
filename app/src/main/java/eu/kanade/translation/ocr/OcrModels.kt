package eu.kanade.translation.ocr

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * MANHWA PaddleOCR models: downloaded once into the app (about 21 MB for English, Chinese and Japanese, about
 * 14 MB more for Korean), each file checked after the download (its exact fingerprint where it is known, else
 * that it is a real model), from its first source and, when that fails, from a mirror.
 */
object OcrModels {

    /** One file of a pack from one source: where it is, and (when known) its SHA-256. */
    data class Remote(val url: String, val sha256: String? = null)

    /** A source gives every file of a pack (files of one source always match each other). */
    data class Source(val name: String, val files: Map<String, Remote>)

    enum class Pack(val id: String, val sizeMb: Int) {
        /** PP-OCRv5 detector and the multilingual recognizer: English, Chinese, Japanese. */
        MAIN("ppocrv5", 21),

        /** PP-OCRv5 Korean recognizer (the detector comes from [MAIN]). */
        KOREAN("ppocrv5_korean", 14),

        /** PP-OCRv5 Arabic recognizer (the detector comes from [MAIN]): reads Arabic pages aloud. */
        ARABIC("ppocrv5_arabic", 8),
    }

    sealed interface Status {
        data object Missing : Status
        data class Downloading(val percent: Int) : Status
        data object Ready : Status
        data class Failed(val reason: String) : Status
    }

    const val DET = "det.onnx"
    const val REC = "rec.onnx"
    const val DICT = "dict.txt"

    private const val GITHUB_LFS = "https://media.githubusercontent.com/media/jingsongliujing/OnnxOCR/main/onnxocr/models/ppocrv5"
    private const val GITHUB_RAW = "https://raw.githubusercontent.com/jingsongliujing/OnnxOCR/main/onnxocr/models/ppocrv5"
    private const val PADDLE_RAW = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/dict"
    private const val HF = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main"
    private const val HF_MIRROR = "https://hf-mirror.com/monkt/paddleocr-onnx/resolve/main"

    private const val DET_SHA = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
    private const val REC_SHA = "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5"
    private const val DICT_SHA = "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b"

    fun sources(pack: Pack): List<Source> = when (pack) {
        Pack.MAIN -> listOf(
            // Checked: these three files, byte for byte (the dictionary is the same file in PaddleOCR's own repo)
            Source(
                "GitHub",
                mapOf(
                    DET to Remote("$GITHUB_LFS/det/det.onnx", DET_SHA),
                    REC to Remote("$GITHUB_LFS/rec/rec.onnx", REC_SHA),
                    DICT to Remote("$GITHUB_RAW/ppocrv5_dict.txt", DICT_SHA),
                ),
            ),
            Source(
                "GitHub (PaddleOCR dictionary)",
                mapOf(
                    DET to Remote("$GITHUB_LFS/det/det.onnx", DET_SHA),
                    REC to Remote("$GITHUB_LFS/rec/rec.onnx", REC_SHA),
                    DICT to Remote("$PADDLE_RAW/ppocrv5_dict.txt", DICT_SHA),
                ),
            ),
            Source(
                "Hugging Face",
                mapOf(
                    DET to Remote("$HF/detection/v5/det.onnx"),
                    REC to Remote("$HF/languages/chinese/rec.onnx"),
                    DICT to Remote("$HF/languages/chinese/dict.txt"),
                ),
            ),
        )
        Pack.KOREAN -> listOf(
            Source("Hugging Face", mapOf(REC to Remote("$HF/languages/korean/rec.onnx"), DICT to Remote("$HF/languages/korean/dict.txt"))),
            Source("HF mirror", mapOf(REC to Remote("$HF_MIRROR/languages/korean/rec.onnx"), DICT to Remote("$PADDLE_RAW/ppocrv5_korean_dict.txt"))),
        )
        Pack.ARABIC -> listOf(
            Source("Hugging Face", mapOf(REC to Remote("$HF/languages/arabic/rec.onnx"), DICT to Remote("$HF/languages/arabic/dict.txt"))),
            Source("HF mirror", mapOf(REC to Remote("$HF_MIRROR/languages/arabic/rec.onnx"), DICT to Remote("$PADDLE_RAW/ppocrv5_arabic_dict.txt"))),
        )
    }

    fun dir(context: Context, pack: Pack): File = File(context.filesDir, "ocr/${pack.id}").apply { mkdirs() }

    fun file(context: Context, pack: Pack, name: String): File = File(dir(context, pack), name)

    /** Every file of [pack] is on the phone (and, for Korean, the detector of [Pack.MAIN] too). */
    fun ready(context: Context, pack: Pack): Boolean {
        val own = sources(pack).first().files.keys.all { file(context, pack, it).isFile }
        return own && (pack == Pack.MAIN || ready(context, Pack.MAIN))
    }

    private val _status = MutableStateFlow<Map<Pack, Status>>(emptyMap())
    val status: StateFlow<Map<Pack, Status>> = _status.asStateFlow()

    fun statusOf(context: Context, pack: Pack): Status =
        _status.value[pack]?.takeIf { it !is Status.Ready } ?: if (ready(context, pack)) Status.Ready else Status.Missing

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Downloads [pack] in the background (Korean and Arabic bring the main pack first). Does nothing while one runs. */
    fun download(context: Context, pack: Pack) {
        val app = context.applicationContext
        val now = _status.value[pack]
        if (now is Status.Downloading) return
        _status.update { it + (pack to Status.Downloading(0)) }
        scope.launch {
            if (pack != Pack.MAIN && !ready(app, Pack.MAIN)) fetch(app, Pack.MAIN)
            fetch(app, pack)
        }
    }

    /** The models the chosen engine needs, fetched by themselves on Wi-Fi (never on mobile data). */
    fun autoDownload(context: Context, pack: Pack) {
        if (ready(context, pack) || _status.value[pack] is Status.Downloading) return
        if (_status.value[pack] is Status.Failed) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (cm.activeNetwork == null || cm.isActiveNetworkMetered) return
        download(context, pack)
    }

    fun delete(context: Context, pack: Pack) {
        dir(context, pack).deleteRecursively()
        PaddleOcrEngine.release()
        _status.update { it - pack }
    }

    private fun fetch(context: Context, pack: Pack) {
        var lastError = "no source"
        for (source in sources(pack)) {
            val ok = runCatching { fetchSource(context, pack, source) }
                .onFailure {
                    lastError = "${source.name}: ${it.message}"
                    logcat(LogPriority.WARN, it) { "OCR models ${pack.id} not fetched from ${source.name}" }
                }
                .isSuccess
            if (ok) {
                _status.update { it + (pack to Status.Ready) }
                PaddleOcrEngine.release()
                return
            }
        }
        _status.update { it + (pack to Status.Failed(lastError.take(160))) }
    }

    /** Every file of [source] into a side folder, checked, then moved in together (never a half pack). */
    private fun fetchSource(context: Context, pack: Pack, source: Source) {
        val client = context.appGraph.networkHelper.client
        val staging = File(context.cacheDir, "ocr_${pack.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val total = source.files.size
            source.files.entries.forEachIndexed { index, (name, remote) ->
                val target = File(staging, name)
                val request = Request.Builder().url(remote.url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} for $name")
                    val body = response.body
                    val length = body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                val part = if (length > 0) done.toFloat() / length else 0f
                                val percent = ((index + part) / total * 100).toInt().coerceIn(0, 99)
                                _status.update { it + (pack to Status.Downloading(percent)) }
                            }
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!OcrModelRules.valid(name, target.length(), sha, remote.sha256, target.inputStream().use { it.read() })) {
                        error("$name failed its check")
                    }
                }
            }
            // All checked: the pack replaces the old one in one go
            val final = dir(context, pack)
            source.files.keys.forEach { name ->
                val from = File(staging, name)
                val to = File(final, name)
                if (!from.renameTo(to)) {
                    from.copyTo(to, overwrite = true)
                }
            }
        } finally {
            staging.deleteRecursively()
        }
    }
}

/** The pure checks of a downloaded OCR file (tested without a phone). */
object OcrModelRules {

    /**
     * A downloaded file is good when its SHA-256 is the one expected (where one is known); otherwise a model
     * must be a real ONNX file (over 1 MB, starting like one) and a dictionary must hold text.
     */
    fun valid(name: String, size: Long, sha: String, expected: String?, firstByte: Int): Boolean {
        if (expected != null) return sha.equals(expected, ignoreCase = true)
        return if (name.endsWith(".onnx")) {
            // An ONNX model is a protobuf whose first field (the IR version) starts with byte 0x08
            size > 1_000_000 && firstByte == 0x08
        } else {
            size > 500
        }
    }
}
