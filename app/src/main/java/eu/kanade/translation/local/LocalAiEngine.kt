package eu.kanade.translation.local

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * MANHWA on-device AI: a small language model that runs on the phone itself (Google MediaPipe LLM Inference),
 * with no key, no quota and no internet. The reader imports the model file once (a MediaPipe ".task" model such
 * as Gemma or Qwen, from the model's official page); it is copied into the app and used:
 * - as the translator when no key of the team works (no more "quota is over"),
 * - in place of Google as the last fallback of the team,
 * - for night translation, free.
 * One model is loaded at a time and freed when it has not been used for a while.
 */
object LocalAiEngine {

    private val gate = Mutex()
    @Volatile private var engine: LlmInference? = null
    @Volatile private var loadedPath: String? = null
    @Volatile private var lastUse = 0L

    private fun modelDir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    /** The imported model file, when there is one and the reader keeps the on-device AI on. */
    fun modelFile(context: Context): File? {
        val prefs = context.appGraph.translationPreferences
        if (!prefs.translationLocalAi().get()) return null
        val path = prefs.translationLocalModelPath().get()
        return path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.length() > MIN_MODEL_BYTES }
    }

    fun available(context: Context): Boolean = runCatching { modelFile(context) != null }.getOrDefault(false)

    /** Copies the model the reader picked into the app; returns its size, or throws when it is not a model. */
    suspend fun import(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        val name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        require(name.endsWith(".task", ignoreCase = true) || name.endsWith(".bin", ignoreCase = true) || name.isEmpty()) {
            "Not a MediaPipe model (.task)"
        }
        val target = File(modelDir(context), "local_model.task")
        val tmp = File(modelDir(context), "local_model.part")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ?: error("The file could not be opened")
        require(tmp.length() > MIN_MODEL_BYTES) { "The file is too small to be a model" }
        gate.withLock {
            release()
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
        context.appGraph.translationPreferences.translationLocalModelPath().set(target.absolutePath)
        target.length()
    }

    /** Removes the imported model (frees its space). */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        gate.withLock {
            release()
            context.appGraph.translationPreferences.translationLocalModelPath().get().takeIf { it.isNotBlank() }?.let { File(it).delete() }
            context.appGraph.translationPreferences.translationLocalModelPath().set("")
        }
    }

    /** One answer from the model; null when there is no model or it failed. */
    suspend fun generate(context: Context, prompt: String): String? = withContext(Dispatchers.Default) {
        val file = modelFile(context) ?: return@withContext null
        gate.withLock {
            runCatching {
                val llm = engine?.takeIf { loadedPath == file.absolutePath } ?: run {
                    release()
                    LlmInference.createFromOptions(
                        context.applicationContext,
                        LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(file.absolutePath)
                            .setMaxTokens(MAX_TOKENS)
                            .build(),
                    ).also {
                        engine = it
                        loadedPath = file.absolutePath
                    }
                }
                lastUse = System.currentTimeMillis()
                llm.generateResponse(prompt)
            }.onFailure { logcat(LogPriority.WARN, it) { "The on-device AI failed" } }.getOrNull()
        }
    }

    /** Frees the model's memory when it was not used for a while (called by the background worker). */
    suspend fun trim() {
        if (engine == null || System.currentTimeMillis() - lastUse < IDLE_MS) return
        gate.withLock { release() }
    }

    private fun release() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    private const val MAX_TOKENS = 1_536
    private const val MIN_MODEL_BYTES = 10L * 1024 * 1024
    private const val IDLE_MS = 5 * 60 * 1000L
}
