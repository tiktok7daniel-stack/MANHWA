package eu.kanade.translation.local

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    suspend fun generate(
        context: Context,
        prompt: String,
        model: File? = null,
        timeoutMs: Long = CALL_TIMEOUT_MS,
        /** Forces a backend (the speed test compares them); null: the NPU or GPU when the phone can, else the CPU. */
        backend: LlmInference.Backend? = null,
        /**
         * The start of [prompt] that stays the same call after call (the rules and the reader's style): read by the
         * model once and kept, so each call reads only what is new.
         */
        prefix: String? = null,
    ): String? {
        val file = model?.takeIf { it.isFile } ?: modelFile(context) ?: return null
        val call = scope.async { answer(context, file, prompt, backend, prefix) }
        try {
            val reply = withTimeoutOrNull(timeoutMs) { call.await() }
            if (reply == null && !call.isCompleted) logcat(LogPriority.WARN) { "The on-device AI took over ${timeoutMs / 1000}s (${file.name})" }
            return reply
        } finally {
            // Not waited for any more: a call still queued is dropped (one already answering finishes, unread)
            if (!call.isCompleted) call.cancel()
        }
    }

    /**
     * MANHWA: loads the model now, in the background, so the first page does not wait for it (loading takes
     * seconds; reading the page with the OCR happens meanwhile).
     */
    fun warm(context: Context, model: File? = null) {
        val file = model?.takeIf { it.isFile } ?: runCatching { modelFile(context) }.getOrNull() ?: return
        if (slots.keys.any { it.startsWith(file.absolutePath + "|") }) return
        scope.launch { runCatching { slotFor(context, file, null) } }
    }

    /** How many tokens [text] is for [model] (the speed test counts real tokens); null when it can't tell. */
    suspend fun tokens(context: Context, text: String, model: File? = null, backend: LlmInference.Backend? = null): Int? {
        val file = model?.takeIf { it.isFile } ?: modelFile(context) ?: return null
        val slot = runCatching { slotFor(context, file, backend) }.getOrNull() ?: return null
        return slot.lock.withLock { runCatching { slot.engine.sizeInTokens(text) }.getOrNull() }
    }

    /** The backend [model] runs on now ("NPU", "GPU", "CPU"), when it is loaded. */
    fun backendOf(model: File): String? = slots.values.firstOrNull { it.path.startsWith(model.absolutePath + "|") }?.backend?.name

    /** How long loading the model took the last time, in ms (shown by the speed test). */
    @Volatile var lastLoadMs: Long = 0
        private set

    private suspend fun answer(context: Context, file: File, prompt: String, backend: LlmInference.Backend?, prefix: String?): String? {
        repeat(2) {
            val slot = runCatching { slotFor(context, file, backend) }
                .onFailure { logcat(LogPriority.WARN, it) { "The on-device AI could not load ${file.name}" } }
                .getOrNull() ?: return null
            // Each model answers one prompt at a time; different models answer at the same time
            slot.lock.withLock {
                // Freed for another model meanwhile: loaded again
                if (slots[slot.path] === slot) {
                    return runCatching {
                        slot.lastUse = System.currentTimeMillis()
                        greedy(slot, prompt, prefix)
                    }.onFailure { logcat(LogPriority.WARN, it) { "The on-device AI failed" } }.getOrNull()
                }
            }
        }
        return null
    }

    /**
     * MANHWA: the model's most likely answer (top-1, no sampling): a translation is not creative writing, and the
     * same line gives the same answer every time. A fresh session per call (nothing carried over between calls);
     * when the session can't be made, the model's plain answer as before.
     *
     * With a [prefix], the session that has read it already is copied (when the library can copy a session), so
     * the rules are not read again on every call of the chapter; anything that fails there falls back to the
     * whole prompt.
     */
    private fun greedy(slot: Slot, prompt: String, prefix: String?): String {
        val engine = slot.engine
        if (prefix != null && prefix.isNotEmpty() && prompt.startsWith(prefix) && prompt.length > prefix.length) {
            runCatching { fromPrefix(slot, prefix, prompt.substring(prefix.length)) }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "The kept rules could not be reused, the whole prompt is read" }
                    slot.dropPrefix()
                }
                .getOrNull()?.let { return it }
        }
        val session = newSession(engine) ?: return engine.generateResponse(prompt)
        return try {
            session.addQueryChunk(prompt)
            session.generateResponse()
        } finally {
            runCatching { session.close() }
        }
    }

    private fun newSession(engine: LlmInference) = runCatching {
        com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.createFromOptions(
            engine,
            com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(1)
                .build(),
        )
    }.getOrNull()

    /** The library's way to copy a session, when this version has it (looked up once). */
    private val cloneMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession::class.java.methods
                .firstOrNull { it.name == "cloneSession" && it.parameterCount == 0 }
        }.getOrNull()
    }

    /** The answer from a copy of the session that read [prefix]; null when sessions can't be copied here. */
    private fun fromPrefix(slot: Slot, prefix: String, rest: String): String? {
        val clone = cloneMethod ?: return null
        val base = slot.prefixSession(prefix) ?: return null
        val copy = clone.invoke(base) as? com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession ?: return null
        return try {
            copy.addQueryChunk(rest)
            copy.generateResponse()
        } finally {
            runCatching { copy.close() }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    /**
     * MANHWA phone ensemble: several models stay loaded together while they fit in the memory budget (part of
     * the phone's RAM); a model that does not fit frees the one used longest ago first.
     */
    private suspend fun slotFor(context: Context, file: File, backend: LlmInference.Backend?): Slot = gate.withLock {
        // The model as it is loaded already (any backend when none is asked for)
        val loaded = if (backend == null) {
            slots.values.firstOrNull { it.path.startsWith(file.absolutePath + "|") }
        } else {
            slots["${file.absolutePath}|${backend.name}"]
        }
        loaded?.let { return@withLock it }
        val budget = EnsembleRules.budgetBytes(LocalModelRam.totalBytes(context))
        while (slots.isNotEmpty() && !EnsembleRules.fits(slots.values.map { it.bytes }, file.length(), budget)) {
            val oldest = slots.values.minBy { it.lastUse }
            // Waits for its answer in progress before freeing it
            oldest.lock.withLock { oldest.close() }
            slots.remove(oldest.path)
        }
        val started = System.currentTimeMillis()
        val (engine, used) = create(context, file, backend)
        lastLoadMs = System.currentTimeMillis() - started
        Slot("${file.absolutePath}|${used.name}", file.length(), engine, used).also { slots[it.path] = it }
    }

    /**
     * MANHWA: the model runs on the phone's NPU when the library and the phone can (Snapdragon's Hexagon), else on
     * its GPU (several times faster than the CPU on most phones); a phone whose NPU or GPU can't run it falls back,
     * and is remembered so it is not tried again.
     */
    private fun create(context: Context, file: File, forced: LlmInference.Backend?): Pair<LlmInference, LlmInference.Backend> {
        val prefs = context.appGraph.translationPreferences
        fun build(backend: LlmInference.Backend) = LlmInference.createFromOptions(
            context.applicationContext,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setPreferredBackend(backend)
                .build(),
        )
        if (forced != null) return build(forced) to forced
        val npu = NpuSupport.backend()
        val marks = context.getSharedPreferences("manhwa_models", Context.MODE_PRIVATE)
        val npuKey = "npu:${file.name}"
        val npuState = marks.getString(npuKey, null)
        if (npu != null && NpuSupport.phoneHasNpu() && npuState != NPU_FAILED) {
            // Marked as tried before trying (written at once): a phone that falls over on the NPU tries it only once
            if (npuState == null) marks.edit().putString(npuKey, NPU_FAILED).commit()
            runCatching {
                build(npu).also { engine ->
                    // The first time, one tiny answer proves the model really runs there (a model not made for this
                    // NPU can load and still fail to answer)
                    if (npuState == null) {
                        runCatching { engine.generateResponse("Reply with OK.") }.onFailure { e ->
                            runCatching { engine.close() }
                            throw e
                        }
                    }
                }
            }
                .onSuccess {
                    marks.edit().putString(npuKey, NPU_WORKS).apply()
                    return it to npu
                }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "The on-device AI can't run ${file.name} on this NPU, using the GPU" }
                    marks.edit().putString(npuKey, NPU_FAILED).apply()
                }
        }
        if (!prefs.translationLocalGpuFailed().get()) {
            runCatching { return build(LlmInference.Backend.GPU) to LlmInference.Backend.GPU }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "The on-device AI can't run on this GPU, using the CPU" }
                    prefs.translationLocalGpuFailed().set(true)
                }
        }
        return build(LlmInference.Backend.CPU) to LlmInference.Backend.CPU
    }

    /** The models loaded now (for the screen). */
    fun loadedPaths(): Set<String> = slots.keys.map { it.substringBeforeLast('|') }.toSet()

    /** Frees the models not used for a while (called by the background worker). */
    suspend fun trim() {
        val now = System.currentTimeMillis()
        if (slots.values.none { now - it.lastUse >= IDLE_MS }) return
        gate.withLock {
            slots.values.filter { now - it.lastUse >= IDLE_MS }.forEach { slot ->
                if (slot.lock.tryLock()) {
                    try {
                        slot.close()
                        slots.remove(slot.path)
                    } finally {
                        slot.lock.unlock()
                    }
                }
            }
        }
    }

    private suspend fun release() {
        slots.values.toList().forEach { slot -> slot.lock.withLock { slot.close() } }
        slots.clear()
    }

    /** [path] is the model's file and its backend ("file|GPU"): the speed test can hold both at once. */
    private class Slot(val path: String, val bytes: Long, val engine: LlmInference, val backend: LlmInference.Backend) {
        val lock = Mutex()
        @Volatile var lastUse = System.currentTimeMillis()

        /** The session that has read the rules of the chapter, kept while they stay the same (one per model). */
        private var prefix: String? = null
        private var prefixed: com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession? = null

        fun prefixSession(text: String): com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession? {
            if (prefix == text) prefixed?.let { return it }
            dropPrefix()
            val session = newSession(engine) ?: return null
            session.addQueryChunk(text)
            prefix = text
            prefixed = session
            return session
        }

        fun dropPrefix() {
            runCatching { prefixed?.close() }
            prefixed = null
            prefix = null
        }

        fun close() {
            dropPrefix()
            runCatching { engine.close() }
        }
    }

    private val slots = java.util.concurrent.ConcurrentHashMap<String, Slot>()

    private const val NPU_WORKS = "works"
    private const val NPU_FAILED = "failed"

    /** Prompt and answer together: room for 12 lines with their context and the glossary. */
    private const val MAX_TOKENS = 2_048
    const val CALL_TIMEOUT_MS = 60_000L
    private const val MIN_MODEL_BYTES = 10L * 1024 * 1024
    private const val IDLE_MS = 5 * 60 * 1000L
}

/**
 * MANHWA: the NPU, when this version of MediaPipe offers it (looked up by name, so the app builds and runs with a
 * version that does not) and the phone has one it can use (Qualcomm Snapdragon).
 */
object NpuSupport {
    fun backend(): LlmInference.Backend? = runCatching { LlmInference.Backend.valueOf("NPU") }.getOrNull()

    fun phoneHasNpu(): Boolean = runCatching {
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else ""
        NpuRules.qualcomm(soc, android.os.Build.HARDWARE, android.os.Build.BOARD)
    }.getOrDefault(false)
}

/** The pure rule of [NpuSupport] (tested without a phone). */
object NpuRules {
    fun qualcomm(soc: String?, hardware: String?, board: String?): Boolean =
        soc.equals("QTI", ignoreCase = true) || soc.equals("Qualcomm", ignoreCase = true) ||
            listOfNotNull(hardware, board).any { it.contains("qcom", ignoreCase = true) }
}

/** The phone's total memory in bytes (0 when unknown). */
object LocalModelRam {
    fun totalBytes(context: Context): Long = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)
}

/** MANHWA: the phone's heat and battery now, for [LocalAiRules.governor]. */
object PhoneState {
    fun read(context: Context): LocalAiRules.State = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        LocalAiRules.State(
            thermal = thermal,
            battery = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = battery.isCharging,
            saver = power.isPowerSaveMode,
        )
    }.getOrDefault(LocalAiRules.State(thermal = 0, battery = 100, charging = true, saver = false))
}
