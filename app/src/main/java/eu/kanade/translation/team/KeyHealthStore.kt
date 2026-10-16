package eu.kanade.translation.team

import eu.kanade.translation.team.ModelHealthManager.Problem
import eu.kanade.translation.team.ModelHealthManager.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference

/**
 * MANHWA key health: what the app knows about each key, kept across restarts. Every call of the app ends in
 * [success] or [failure], so the card, the router and the "check and fix all keys" button all read the same
 * record. Nothing here ever deletes a key: a temporary error only rests it, and a key is put out of work only
 * after it was refused twice in a row ([ModelHealthManager.AUTH_STRIKES_TO_DISABLE]).
 */
class KeyHealthStore(private val pref: Preference<String>) {

    @Serializable
    data class KeyHealth(
        val status: String = Status.UNKNOWN.name,
        val model: String = "",
        val models: List<String> = emptyList(),
        val maxTokens: Int = 0,
        val contextLimit: Int = 0,
        val lastChecked: Long = 0,
        val lastSuccess: Long = 0,
        val lastError: String? = null,
        val lastErrorAt: Long = 0,
        val errorCount: Int = 0,
        val successCount: Int = 0,
        val disabledReason: String? = null,
        val cooldownUntil: Long = 0,
        val rateLimited: Boolean = false,
        val authStrikes: Int = 0,
        val streak: Int = 0,
        val vision: Boolean = false,
        val webSearch: Boolean = false,
        /** Calls and tokens of today ([day]), for the daily quota meter. */
        val day: String = "",
        val callsToday: Int = 0,
        val tokensToday: Long = 0,
        /** Calls one chapter took, on average (for "enough for about N chapters"). */
        val callsPerChapter: Float = 0f,
    ) {
        val statusEnum: Status get() = Status.entries.firstOrNull { it.name == status } ?: Status.UNKNOWN
        val errorRate: Float get() = if (errorCount + successCount == 0) 0f else errorCount.toFloat() / (errorCount + successCount)
        fun coolingDown(now: Long = System.currentTimeMillis()) = cooldownUntil > now
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, KeyHealth>> = _all.asStateFlow()

    private fun load(): Map<String, KeyHealth> =
        runCatching { json.decodeFromString<Map<String, KeyHealth>>(pref.get()) }.getOrDefault(emptyMap())

    operator fun get(id: String): KeyHealth = _all.value[id] ?: KeyHealth()

    fun edit(id: String, change: (KeyHealth) -> KeyHealth) {
        synchronized(lock) {
            val today = UsageMeter.today()
            val old = (_all.value[id] ?: KeyHealth()).let { if (it.day != today) it.copy(day = today, callsToday = 0, tokensToday = 0) else it }
            val next = _all.value + (id to change(old))
            _all.value = next
            pref.set(json.encodeToString(next))
        }
    }

    fun forget(id: String) {
        synchronized(lock) {
            val next = _all.value - id
            _all.value = next
            pref.set(json.encodeToString(next))
        }
    }

    fun success(id: String, model: String, tokens: Long = 0) = edit(id) {
        it.copy(
            status = Status.ACTIVE.name,
            model = model.ifBlank { it.model },
            lastSuccess = System.currentTimeMillis(),
            successCount = it.successCount + 1,
            authStrikes = 0,
            streak = 0,
            rateLimited = false,
            cooldownUntil = 0,
            disabledReason = null,
            callsToday = it.callsToday + 1,
            tokensToday = it.tokensToday + tokens,
        )
    }

    /** Records a failed call; returns the status the key is in now. */
    fun failure(id: String, problem: Problem, message: String?): Status {
        var status = Status.UNKNOWN
        edit(id) {
            val strikes = if (problem == Problem.AuthFailed) it.authStrikes + 1 else it.authStrikes
            status = ModelHealthManager.statusAfter(problem, strikes)
            val now = System.currentTimeMillis()
            it.copy(
                status = status.name,
                lastError = message?.take(200),
                lastErrorAt = now,
                errorCount = it.errorCount + 1,
                authStrikes = strikes,
                streak = it.streak + 1,
                rateLimited = problem is Problem.RateLimited,
                cooldownUntil = maxOf(it.cooldownUntil, now + ModelHealthManager.cooldownFor(problem, it.streak)),
                disabledReason = if (status == Status.DISABLED) message?.take(200) else it.disabledReason,
                maxTokens = (problem as? Problem.MaxTokens)?.limit ?: it.maxTokens,
                contextLimit = (problem as? Problem.ContextTooLong)?.limit ?: it.contextLimit,
                callsToday = it.callsToday + 1,
            )
        }
        return status
    }

    /** Calls of one finished chapter, folded into the key's average. */
    fun chapterDone(id: String, calls: Int) = edit(id) {
        val avg = if (it.callsPerChapter <= 0f) calls.toFloat() else it.callsPerChapter * 0.7f + calls * 0.3f
        it.copy(callsPerChapter = avg)
    }
}
