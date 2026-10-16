package eu.kanade.translation.local

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import mihon.app.di.appGraph
import okhttp3.Request
import java.io.File

/**
 * MANHWA model shelf: the strongest models that run on a phone with MediaPipe, from their official pages on
 * Hugging Face. The file to download is read from the model's page itself (so a renamed file is still found),
 * downloaded by Android's own download manager (resumes, shows in the notifications, goes on in the
 * background) into the app's own folder, then used as the main translator or as the judge.
 *
 * Gemma models ask their readers to accept Google's licence on the model page first; after that the reader's own
 * Hugging Face token (kept only on the phone) lets the download start. Nothing is bypassed.
 */
object ModelCatalog {

    data class Model(
        val repo: String,
        val name: String,
        /** About how big the file is, in GB. */
        val sizeGb: Float,
        /** 1–5: how well it translates manhwa into Arabic, from what these models are known for. */
        val strength: Int,
        /** RAM the phone should have, in GB. */
        val minRamGb: Int,
        val gated: Boolean,
        val noteEn: String,
        val noteAr: String,
    ) {
        val page: String get() = "https://huggingface.co/$repo"
    }

    val models: List<Model> = listOf(
        Model(
            "google/gemma-3n-E4B-it-litert-preview", "Gemma 3n E4B", 4.4f, 5, 8, gated = true,
            "Google's strongest phone model: the best Arabic of them all. Needs a powerful phone.",
            "أقوى موديل موبايل من جوجل: أحسن عربي فيهم كلهم. محتاج موبايل قوي.",
        ),
        Model(
            "google/gemma-3n-E2B-it-litert-preview", "Gemma 3n E2B", 3.1f, 4, 6, gated = true,
            "Almost as good, lighter and faster.",
            "قريب منه في الجودة، وأخف وأسرع.",
        ),
        Model(
            "litert-community/Phi-4-mini-instruct", "Phi-4 mini", 3.9f, 4, 8, gated = false,
            "Microsoft's small model: strong reasoning, good as the judge.",
            "موديل مايكروسوفت الصغير: تفكير قوي، وكويس كحَكَم.",
        ),
        Model(
            "litert-community/Qwen2.5-3B-Instruct", "Qwen 2.5 3B", 3.0f, 4, 6, gated = false,
            "Good at Arabic, Korean and Chinese; open licence.",
            "كويس في العربي والكوري والصيني؛ رخصة مفتوحة.",
        ),
        Model(
            "litert-community/Qwen2.5-1.5B-Instruct", "Qwen 2.5 1.5B", 1.6f, 3, 4, gated = false,
            "Light and quick, a good first draft.",
            "خفيف وسريع، مسودة أولى كويسة.",
        ),
        Model(
            "litert-community/Gemma3-1B-IT", "Gemma 3 1B", 0.55f, 2, 3, gated = true,
            "The smallest: for older phones.",
            "الأصغر: للموبايلات القديمة.",
        ),
    )

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val percent: Int) : Status
        data class Ready(val file: File) : Status
        data class Failed(val reason: String) : Status
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun folder(context: Context): File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    /** The phone's memory, in GB (to say which models suit it). */
    fun ramGb(context: Context): Int = runCatching {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        Math.round(info.totalMem / (1024.0 * 1024 * 1024)).toInt()
    }.getOrDefault(0)

    private fun prefs(context: Context) = context.getSharedPreferences("manhwa_models", Context.MODE_PRIVATE)

    /** Where [model] stands now: on the phone, downloading (with how much), failed, or not there. */
    fun status(context: Context, model: Model): Status {
        val p = prefs(context)
        val fileName = p.getString("file:${model.repo}", null)
        val id = p.getLong("id:${model.repo}", -1L)
        if (id >= 0) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (state) {
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED ->
                            return Status.Downloading(if (total > 0) (done * 100 / total).toInt() else 0)
                        DownloadManager.STATUS_FAILED -> return Status.Failed("Download failed (${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})")
                    }
                }
            }
        }
        val file = fileName?.let { File(folder(context), it) }
        if (file == null || !file.isFile || file.length() <= 10L * 1024 * 1024) return Status.NotDownloaded
        // A finished download joins the team at once when the phone has no working model yet
        val settings = context.appGraph.translationPreferences
        val main = settings.translationLocalModelPath().get()
        if (main.isBlank() || !File(main).isFile) LocalAiEngine.use(context, file)
        return Status.Ready(file)
    }

    /**
     * Starts downloading [model]: its page is read for the .task file, then Android downloads it. Throws with a
     * clear reason when the page refuses (a gated model whose licence was not accepted, or no token).
     */
    suspend fun download(context: Context, model: Model, wifiOnly: Boolean): Unit = withContext(Dispatchers.IO) {
        val token = context.appGraph.translationPreferences.translationHfToken().get().trim()
        val file = pickFile(context, model, token)
        val target = File(folder(context), file.substringAfterLast('/'))
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(Uri.parse("https://huggingface.co/${model.repo}/resolve/main/$file"))
            .setTitle("MANHWA · ${model.name}")
            .setDescription(file)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        if (token.isNotEmpty()) request.addRequestHeader("Authorization", "Bearer $token")
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        prefs(context).edit().putLong("id:${model.repo}", id).putString("file:${model.repo}", target.name).apply()
    }

    /** Stops a download and removes the model's file. */
    fun delete(context: Context, model: Model) {
        val p = prefs(context)
        val id = p.getLong("id:${model.repo}", -1L)
        if (id >= 0) runCatching { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
        val file = p.getString("file:${model.repo}", null)?.let { File(folder(context), it) }
        file?.delete()
        p.edit().remove("id:${model.repo}").remove("file:${model.repo}").apply()
        // A deleted model is no longer the main one or the judge
        val settings = context.appGraph.translationPreferences
        if (file != null) {
            if (settings.translationLocalModelPath().get() == file.absolutePath) settings.translationLocalModelPath().set("")
            if (settings.translationLocalJudgePath().get() == file.absolutePath) settings.translationLocalJudgePath().set("")
        }
    }

    /** The .task file of the model, read from its page. */
    private fun pickFile(context: Context, model: Model, token: String): String {
        val request = Request.Builder().url("https://huggingface.co/api/models/${model.repo}")
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        val text = context.appGraph.networkHelper.client.newCall(request).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw IllegalStateException(GATED)
                r.code == 404 -> throw IllegalStateException(NOT_FOUND)
                !r.isSuccessful -> throw IllegalStateException("HTTP ${r.code}")
            }
            r.body.string()
        }
        val root = json.parseToJsonElement(text).jsonObject
        if ((root["gated"] as? JsonPrimitive)?.contentOrNull.let { it != null && it != "false" } && token.isEmpty()) {
            throw IllegalStateException(GATED)
        }
        val files = (root["siblings"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("rfilename")?.let { f -> (f as? JsonPrimitive)?.contentOrNull } }
        return CatalogRules.bestFile(files) ?: throw IllegalStateException(NOT_FOUND)
    }

    const val GATED = "gated"
    const val NOT_FOUND = "not_found"
}

/** Pure choice of the file to download (tested without a phone). */
object CatalogRules {
    /** The model's MediaPipe file: a .task, the 4-bit one first (smaller, made for phones), then 8-bit. */
    fun bestFile(files: List<String>): String? {
        val tasks = files.filter { it.endsWith(".task", ignoreCase = true) }
        if (tasks.isEmpty()) return null
        return tasks.firstOrNull { it.contains("int4", true) || it.contains("q4", true) }
            ?: tasks.firstOrNull { it.contains("q8", true) || it.contains("int8", true) }
            ?: tasks.first()
    }

    /** Whether a phone with [ramGb] GB runs [model] well. */
    fun fits(model: ModelCatalog.Model, ramGb: Int): Boolean = ramGb <= 0 || ramGb >= model.minRamGb
}
