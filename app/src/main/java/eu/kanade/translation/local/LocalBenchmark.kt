package eu.kanade.translation.local

import android.content.Context
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * MANHWA speed and accuracy test of the phone model, on the phone itself: the same English manhwa lines are
 * translated into Arabic the old way ([LocalAiRules.Tuning.LEGACY]: CPU, JSON, 8 lines a call) and the new way
 * ([LocalAiRules.Tuning.FAST]: GPU when the phone can, numbered lines, 12 a call), and each is measured:
 * - time per line, p50 and p95 (from the time of each call shared by its lines),
 * - tokens written a second (the model's own count of the answer),
 * - the app's memory while the model runs (PSS),
 * - accuracy: chrF against the right translations below, and how many lines came back usable.
 * Nothing is sent anywhere; the result is shown and can be copied.
 */
object LocalBenchmark {

    data class Run(
        val label: String,
        val backend: String,
        val loadMs: Long,
        val p50Ms: Long,
        val p95Ms: Long,
        val tokensPerSecond: Double?,
        val pssMb: Int,
        val chrF: Double,
        val usable: Int,
        val total: Int,
        val totalMs: Long,
    )

    data class Report(val model: String, val legacy: Run?, val fast: Run?) {
        /** The new way is faster and at least as accurate (within a point of chrF, the noise of a short test). */
        val passed: Boolean
            get() = legacy != null && fast != null && fast.p50Ms < legacy.p50Ms && fast.chrF >= legacy.chrF - 1.0 && fast.usable >= legacy.usable

        fun text(): String = buildString {
            appendLine("MANHWA phone model test · $model")
            listOfNotNull(legacy, fast).forEach { r ->
                appendLine(
                    String.format(
                        Locale.US,
                        "%s [%s] load %d ms · p50 %d ms/line · p95 %d ms/line · %s tok/s · %d MB · chrF %.1f · %d/%d lines · %.1f s",
                        r.label, r.backend, r.loadMs, r.p50Ms, r.p95Ms, r.tokensPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "?",
                        r.pssMb, r.chrF, r.usable, r.total, r.totalMs / 1000.0,
                    ),
                )
            }
            if (legacy != null && fast != null) {
                appendLine(String.format(Locale.US, "Speed-up p50: %.2fx · chrF %+.1f · %s", legacy.p50Ms.toDouble() / fast.p50Ms.coerceAtLeast(1), fast.chrF - legacy.chrF, if (passed) "PASS" else "FAIL"))
            }
        }
    }

    /** Runs both ways on [model] (the main model when null). Takes a few minutes on a slow phone. */
    suspend fun run(context: Context, model: File? = null, onStep: (String) -> Unit = {}): Report = withContext(Dispatchers.Default) {
        val file = model ?: LocalAiEngine.modelFile(context) ?: error("No phone model")
        val name = PhoneEnsemble.nameOf(context, file)
        onStep("legacy")
        val legacy = runCatching { measure(context, file, "Before", LocalAiRules.Tuning.LEGACY) }.getOrNull()
        onStep("fast")
        val fast = runCatching { measure(context, file, "Now", LocalAiRules.Tuning.FAST) }.getOrNull()
        Report(name, legacy, fast)
    }

    private suspend fun measure(context: Context, file: File, label: String, tuning: LocalAiRules.Tuning): Run {
        // Loaded first (and timed apart): the per-line times are the translating alone
        val loadStart = System.currentTimeMillis()
        LocalAiEngine.tokens(context, "warm", file, tuning.backend)
        val loadMs = System.currentTimeMillis() - loadStart
        val translator = LocalAiTranslator(
            context, TextRecognizerLanguage.ENGLISH, TextTranslatorLanguage.ARABIC,
            glossary = GLOSSARY, model = file, tuning = tuning,
        )
        val sources = LINES.map { it.first }
        val out = ArrayList<String?>()
        val perLine = ArrayList<Long>()
        var written = 0
        var writingMs = 0L
        val started = System.currentTimeMillis()
        sources.chunked(tuning.linesPerCall).forEach { chunk ->
            val callStart = System.currentTimeMillis()
            // The model alone (no Google for what it misses): what it can do is what is measured
            val answers = translator.benchLines(chunk)
            val took = System.currentTimeMillis() - callStart
            repeat(chunk.size) { perLine += took / chunk.size }
            writingMs += took
            chunk.indices.forEach { i ->
                val t = answers?.getOrNull(i)
                out += t
                if (t != null) written += LocalAiEngine.tokens(context, t, file, tuning.backend) ?: 0
            }
        }
        val totalMs = System.currentTimeMillis() - started
        // A line the model could not give scores nothing
        val scores = LINES.mapIndexed { i, (_, right) -> out.getOrNull(i)?.let { LocalAiRules.chrF(it, right) } ?: 0.0 }
        return Run(
            label = label,
            backend = LocalAiEngine.backendOf(file) ?: tuning.backend?.name ?: "?",
            loadMs = loadMs,
            p50Ms = LocalAiRules.percentile(perLine, 0.5),
            p95Ms = LocalAiRules.percentile(perLine, 0.95),
            tokensPerSecond = if (written > 0 && writingMs > 0) written * 1000.0 / writingMs else null,
            pssMb = pssMb(),
            chrF = scores.average(),
            usable = out.count { it != null },
            total = LINES.size,
            totalMs = totalMs,
        )
    }

    private fun pssMb(): Int = runCatching {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        info.totalPss / 1024
    }.getOrDefault(0)

    private val GLOSSARY = mapOf("Jinwoo" to "جين وو", "Hae-in" to "هاي إن", "Hunter Association" to "جمعية الصيادين")

    /** English manhwa lines and their right Arabic (speech, a woman and a man speaking, system texts, shouts). */
    val LINES: List<Pair<String, String>> = listOf(
        "Where do you think you're going?" to "إلى أين تظن نفسك ذاهبًا؟",
        "Jinwoo, are you okay?" to "جين وو، هل أنت بخير؟",
        "Hae-in, you shouldn't have come here." to "هاي إن، ما كان يجب أن تأتي إلى هنا.",
        "I'm not afraid of you anymore." to "لم أعد أخافك.",
        "She said she would wait for me." to "قالت إنها ستنتظرني.",
        "Get out of my way!" to "ابتعد عن طريقي!",
        "This is the last time I'm warning you." to "هذه آخر مرة أحذرك فيها.",
        "The Hunter Association won't ignore this." to "جمعية الصيادين لن تتجاهل هذا.",
        "[You have leveled up!]" to "[لقد ارتفع مستواك!]",
        "[Quest complete. Reward: 3 skill points]" to "[اكتملت المهمة. المكافأة: 3 نقاط مهارة]",
        "Thank you for saving me." to "شكرًا لأنك أنقذتني.",
        "Don't worry, I'll protect you." to "لا تقلق، سأحميك.",
        "Mom, I'm home!" to "أمي، لقد عدت!",
        "He's stronger than he looks." to "إنه أقوى مما يبدو.",
        "We have to leave before sunrise." to "يجب أن نغادر قبل شروق الشمس.",
        "What a pathetic excuse." to "يا له من عذر مثير للشفقة.",
        "I'll never forgive you!" to "لن أسامحك أبدًا!",
        "Is that all you've got?" to "أهذا كل ما لديك؟",
        "Let's finish this." to "لننهِ هذا.",
        "You remembered my name?" to "هل تذكرت اسمي؟",
        "The gate is closing!" to "البوابة تُغلق!",
        "I didn't mean to hurt her." to "لم أقصد أن أؤذيها.",
        "Stay behind me." to "ابقَ خلفي.",
        "It's been a long time, old friend." to "لقد مر وقت طويل يا صديقي القديم.",
    )
}
