package eu.kanade.translation.orchestra

import android.content.Context
import android.os.PowerManager
import eu.kanade.translation.ChapterPrefetchManager
import eu.kanade.translation.local.LocalAiEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import java.io.File

/**
 * MANHWA orchestra state: whether the phone plays now (a model, the setting, heat and battery), what it did
 * (lines by the phone, by the keys, judgements, calls saved) and the examples it learnt from the keys.
 */
object OrchestraStore {

    @Serializable
    data class Stats(val day: String = "", val localLines: Int = 0, val teamLines: Int = 0, val judged: Int = 0, val totalLocal: Long = 0, val totalTeam: Long = 0)

    @Serializable
    data class Example(val source: String, val draft: String, val final: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** The phone takes part right now. */
    fun active(context: Context): Boolean {
        val prefs = context.appGraph.translationPreferences
        if (!prefs.translationOrchestra().get() || !LocalAiEngine.available(context)) return false
        if (slowNow(context)) return false
        val device = ChapterPrefetchManager.device(context)
        val thermal = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0
        }.getOrDefault(0)
        return OrchestraRules.deviceOk(device.batteryPercent, device.charging, device.powerSave, thermal)
    }

    @Volatile private var slowUntil = 0L

    /** The phone was too slow for one call: it sits out for a while and the keys work alone (no waiting on it). */
    fun markSlow(context: Context) {
        slowUntil = System.currentTimeMillis() + SLOW_REST_MS
    }

    fun slowNow(context: Context): Boolean = System.currentTimeMillis() < slowUntil

    fun stats(context: Context): Stats {
        val raw = context.appGraph.translationPreferences.translationOrchestraStats().get()
        val s = runCatching { json.decodeFromString<Stats>(raw) }.getOrDefault(Stats())
        return if (s.day == today()) s else s.copy(day = today(), localLines = 0, teamLines = 0, judged = 0)
    }

    fun record(context: Context, local: Int = 0, team: Int = 0, judged: Int = 0) {
        if (local == 0 && team == 0 && judged == 0) return
        synchronized(lock) {
            val s = stats(context)
            val next = s.copy(
                localLines = s.localLines + local,
                teamLines = s.teamLines + team,
                judged = s.judged + judged,
                totalLocal = s.totalLocal + local,
                totalTeam = s.totalTeam + team,
            )
            context.appGraph.translationPreferences.translationOrchestraStats().set(json.encodeToString(next))
        }
    }

    // ---------------------------------------------------------------- the phone learns from the keys

    private fun tutorFile(context: Context) = File(context.filesDir, "orchestra_examples.json")

    @Synchronized
    fun examples(context: Context): List<Example> =
        runCatching { json.decodeFromString<List<Example>>(tutorFile(context).readText()) }.getOrDefault(emptyList())

    /** Keeps the lines where a key rewrote the phone's draft: the phone sees them as examples next time. */
    @Synchronized
    fun learn(context: Context, corrected: List<Example>) {
        if (corrected.isEmpty()) return
        val next = (examples(context).filterNot { e -> corrected.any { it.source == e.source } } + corrected).takeLast(MAX_EXAMPLES)
        runCatching { tutorFile(context).writeText(json.encodeToString(next)) }
    }

    /** The examples most like [lines] (shared words), for the phone's prompt. */
    fun examplesFor(context: Context, lines: List<String>, max: Int = 6): List<Example> =
        TutorRules.closest(examples(context), lines, max)

    private fun today(): String = java.time.LocalDate.now().toString()

    private const val MAX_EXAMPLES = 400
    private const val SLOW_REST_MS = 20 * 60_000L
}

/** Pure choice of the tutor's examples (tested without a phone). */
object TutorRules {
    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length >= 3 }.toSet()

    fun closest(all: List<OrchestraStore.Example>, lines: List<String>, max: Int): List<OrchestraStore.Example> {
        val wanted = lines.flatMap { words(it) }.toSet()
        if (wanted.isEmpty()) return all.takeLast(max)
        return all.map { it to words(it.source).count { w -> w in wanted } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
            .ifEmpty { all.takeLast(max / 2) }
    }
}
