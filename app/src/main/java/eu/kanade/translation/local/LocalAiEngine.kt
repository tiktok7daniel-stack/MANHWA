package eu.kanade.translation.local

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Several models stay loaded together while they fit in the phone's memory (the phone ensemble); each is freed
 * when it has not been used for a while.
 */
object LocalAiEngine {

    private val gate = Mutex()

    private fun modelDir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    /** The imported model file, when there is one and the reader keeps the on-device AI on. */
    fun modelFile(context: Context): File? {
        val prefs = context.appGraph.translationPreferences
        if (!prefs.translationLocalAi().get()) return null
        val path = prefs.translationLocalModelPath().get()
        return path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.length() > MIN_MODEL_BYTES }
    }

    fun available(context: Context): Boolean = runCatching { modelFile(context) != null }.getOrDefault(false)

    /** The second model chosen as judge, when there is one (else the main model judges). */
    fun judgeFile(context: Context): File? {
        val path = context.appGraph.translationPreferences.translationLocalJudgePath().get()
        return path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.length() > MIN_MODEL_BYTES }
    }

    /** Uses a model already on the phone (downloaded from the catalogue) as the main model. */
    fun use(context: Context, file: File) {
        context.appGraph.translationPreferences.translationLocalModelPath().set(file.absolutePath)
    }

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

    /**
     * One answer from the model; null when there is no model, it failed, or it took longer than [timeoutMs].
     * The model works in its own scope: a caller that stops waiting (time out, chapter cancelled) is never held
     * by the phone, and the model finishes its answer in the background before its next one.
     */
    suspend fun generate(context: Context, prompt: String, model: File? = null, timeoutMs: Long = CALL_TIMEOUT_MS): String? {
        val file = model?.takeIf { it.isFile } ?: modelFile(context) ?: return null
        val call = scope.async { answer(context, file, prompt) }
        try {
            val reply = withTimeoutOrNull(timeoutMs) { call.await() }
            if (reply == null && !call.isCompleted) logcat(LogPriority.WARN) { "The on-device AI took over ${timeoutMs / 1000}s (${file.name})" }
            return reply
        } finally {
            // Not waited for any more: a call still queued is dropped (one already answering finishes, unread)
            if (!call.isCompleted) call.cancel()
        }
    }

    private suspend fun answer(context: Context, file: File, prompt: String): String? {
        repeat(2) {
            val slot = runCatching { slotFor(context, file) }
                .onFailure { logcat(LogPriority.WARN, it) { "The on-device AI could not load ${file.name}" } }
                .getOrNull() ?: return null
            // Each model answers one prompt at a time; different models answer at the same time
            slot.lock.withLock {
                // Freed for another model meanwhile: loaded again
                if (slots[slot.path] === slot) {
                    return runCatching {
                        slot.lastUse = System.currentTimeMillis()
                        slot.engine.generateResponse(prompt)
                    }.onFailure { logcat(LogPriority.WARN, it) { "The on-device AI failed" } }.getOrNull()
                }
            }
        }
        return null
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    /**
     * MANHWA phone ensemble: several models stay loaded together while they fit in the memory budget (part of
     * the phone's RAM); a model that does not fit frees the one used longest ago first.
     */
    private suspend fun slotFor(context: Context, file: File): Slot = gate.withLock {
        slots[file.absolutePath]?.let { return@withLock it }
        val budget = EnsembleRules.budgetBytes(LocalModelRam.totalBytes(context))
        while (slots.isNotEmpty() && !EnsembleRules.fits(slots.values.map { it.bytes }, file.length(), budget)) {
            val oldest = slots.values.minBy { it.lastUse }
            // Waits for its answer in progress before freeing it
            oldest.lock.withLock { runCatching { oldest.engine.close() } }
            slots.remove(oldest.path)
        }
        val engine = LlmInference.createFromOptions(
            context.applicationContext,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .build(),
        )
        Slot(file.absolutePath, file.length(), engine).also { slots[it.path] = it }
    }

    /** The models loaded now (for the screen). */
    fun loadedPaths(): Set<String> = slots.keys.toSet()

    /** Frees the models not used for a while (called by the background worker). */
    suspend fun trim() {
        val now = System.currentTimeMillis()
        if (slots.values.none { now - it.lastUse >= IDLE_MS }) return
        gate.withLock {
            slots.values.filter { now - it.lastUse >= IDLE_MS }.forEach { slot ->
                if (slot.lock.tryLock()) {
                    try {
                        runCatching { slot.engine.close() }
                        slots.remove(slot.path)
                    } finally {
                        slot.lock.unlock()
                    }
                }
            }
        }
    }

    private suspend fun release() {
        slots.values.toList().forEach { slot -> slot.lock.withLock { runCatching { slot.engine.close() } } }
        slots.clear()
    }

    private class Slot(val path: String, val bytes: Long, val engine: LlmInference) {
        val lock = Mutex()
        @Volatile var lastUse = System.currentTimeMillis()
    }

    private val slots = java.util.concurrent.ConcurrentHashMap<String, Slot>()

    private const val MAX_TOKENS = 1_536
    const val CALL_TIMEOUT_MS = 60_000L
    private const val MIN_MODEL_BYTES = 10L * 1024 * 1024
    private const val IDLE_MS = 5 * 60 * 1000L
}

/** The phone's total memory in bytes (0 when unknown). */
object LocalModelRam {
    fun totalBytes(context: Context): Long = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)
}
