package eu.kanade.translation.team

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import java.time.LocalDate

/**
 * MANHWA: how each key did, day by day, over the last [KeyReportRules.DAYS] days (calls that worked, that failed
 * and why, how fast it answered), kept across restarts so the reader can see which keys translated well lately.
 */
class KeyRecord(private val store: Preference<String>) {

    @Serializable
    data class Day(
        val day: String,
        val ok: Int = 0,
        val failed: Int = 0,
        val latencySum: Long = 0,
        val timed: Int = 0,
        /** Busy or out of quota. */
        val limited: Int = 0,
        /** The site refused the key. */
        val refused: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val _days = MutableStateFlow(load())

    /** Key id to its last days, oldest first. */
    val days: StateFlow<Map<String, List<Day>>> = _days.asStateFlow()

    private fun load(): Map<String, List<Day>> =
        runCatching { json.decodeFromString<Map<String, List<Day>>>(store.get()) }.getOrDefault(emptyMap())

    private fun change(id: String, update: (Day) -> Day) = synchronized(lock) {
        val today = LocalDate.now().toString()
        val first = LocalDate.now().minusDays(KeyReportRules.DAYS.toLong() - 1).toString()
        val list = _days.value[id].orEmpty().filter { it.day >= first }
        val current = list.lastOrNull()?.takeIf { it.day == today } ?: Day(today)
        val next = list.filter { it.day != today } + update(current)
        _days.value = _days.value + (id to next)
        runCatching { store.set(json.encodeToString(_days.value)) }
    }

    fun success(id: String, latencyMs: Long?) = change(id) { d ->
        if (latencyMs != null && latencyMs > 0) {
            d.copy(ok = d.ok + 1, latencySum = d.latencySum + latencyMs, timed = d.timed + 1)
        } else {
            d.copy(ok = d.ok + 1)
        }
    }

    fun failure(id: String, problem: ModelHealthManager.Problem) = change(id) { d ->
        d.copy(
            failed = d.failed + 1,
            limited = d.limited + if (problem is ModelHealthManager.Problem.RateLimited) 1 else 0,
            refused = d.refused + if (problem is ModelHealthManager.Problem.AuthFailed) 1 else 0,
        )
    }

    fun forget(id: String) = synchronized(lock) {
        _days.value = _days.value - id
        runCatching { store.set(json.encodeToString(_days.value)) }
    }

    /** What [id] did over the recorded days. */
    fun totals(id: String): KeyReportRules.Totals {
        val first = LocalDate.now().minusDays(KeyReportRules.DAYS.toLong() - 1).toString()
        val list = _days.value[id].orEmpty().filter { it.day >= first }
        val timed = list.sumOf { it.timed }
        return KeyReportRules.Totals(
            ok = list.sumOf { it.ok },
            failed = list.sumOf { it.failed },
            avgLatencyMs = if (timed > 0) list.sumOf { it.latencySum } / timed else 0,
            limited = list.sumOf { it.limited },
            refused = list.sumOf { it.refused },
        )
    }
}

/** MANHWA: the pure rules of the "best keys" report (tested without a phone). */
object KeyReportRules {

    /** How many days the report looks back. */
    const val DAYS = 14

    data class Totals(val ok: Int, val failed: Int, val avgLatencyMs: Long, val limited: Int = 0, val refused: Int = 0) {
        val calls: Int get() = ok + failed
        val successRate: Float get() = if (calls == 0) 0f else ok.toFloat() / calls
    }

    enum class Verdict { EXCELLENT, GOOD, WEAK, UNUSED }

    /** 0–100: mostly how often it worked, then speed, how much it did, and the model's strength (1–100). */
    fun score(t: Totals, strength: Int): Int {
        if (t.calls == 0) return 0
        val speed = if (t.avgLatencyMs <= 0) 0.6f else (SPEED_REFERENCE_MS / t.avgLatencyMs.toFloat()).coerceIn(0.2f, 1f)
        val volume = (kotlin.math.ln(1f + t.ok) / kotlin.math.ln(1f + VOLUME_FULL)).coerceIn(0f, 1f)
        val power = strength.coerceIn(0, 100) / 100f
        return (t.successRate * 55 + speed * 20 + volume * 10 + power * 15).toInt().coerceIn(0, 100)
    }

    fun verdict(t: Totals, score: Int): Verdict = when {
        t.calls == 0 -> Verdict.UNUSED
        t.successRate < WEAK_RATE || score < WEAK_SCORE -> Verdict.WEAK
        score >= EXCELLENT_SCORE && t.successRate >= EXCELLENT_RATE && t.ok >= EXCELLENT_MIN_CALLS -> Verdict.EXCELLENT
        else -> Verdict.GOOD
    }

    /** Sites by the average score of their used keys, best first (site id to score). */
    fun rankSites(keys: List<Pair<String, Int>>): List<Pair<String, Int>> =
        keys.filter { it.second > 0 }
            .groupBy({ it.first }, { it.second })
            .map { (site, scores) -> site to scores.average().toInt() }
            .sortedByDescending { it.second }

    private const val SPEED_REFERENCE_MS = 4_000f
    private const val VOLUME_FULL = 50f
    private const val WEAK_RATE = 0.7f
    private const val WEAK_SCORE = 40
    private const val EXCELLENT_SCORE = 70
    private const val EXCELLENT_RATE = 0.9f
    private const val EXCELLENT_MIN_CALLS = 10
}
