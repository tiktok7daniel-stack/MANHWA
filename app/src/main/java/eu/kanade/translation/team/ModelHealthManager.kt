package eu.kanade.translation.team

/**
 * MANHWA model health: reads what an AI site's error really means, so every call of the app (chat, translation,
 * organizer, pre-translation, team) heals the same way instead of failing again with the same error.
 *
 * - [Problem.MaxTokens]: the answer asked for more tokens than the model gives ("max_tokens must be less than or
 *   equal to 8192"). The limit is read from the message, saved for the key, and the call is sent again with it.
 * - [Problem.ContextTooLong]: the request itself is longer than the model reads; the work must be cut in parts.
 * - [Problem.ModelInvalid]: the model is gone, renamed or not open to this key ("does not exist", "model_not_found",
 *   "decommissioned"). A working model is picked from the key's live list and the call is sent again.
 * - [Problem.RateLimited]: busy or out of quota (429), with the wait the site asks for when it tells it.
 * - [Problem.AuthFailed]: the key is wrong or revoked (401 / 403 without a model in the message).
 * - [Problem.Temporary]: server errors, timeouts, the network. Never a reason to drop a key.
 */
object ModelHealthManager {

    sealed interface Problem {
        data class MaxTokens(val limit: Int) : Problem
        data class ContextTooLong(val limit: Int?) : Problem
        data object ModelInvalid : Problem
        data class RateLimited(val retryAfterMs: Long?) : Problem
        data object AuthFailed : Problem
        data object Temporary : Problem
        data object Other : Problem
    }

    /** The states a key can be in, shown on its card and used by the router. */
    enum class Status {
        ACTIVE,
        DEGRADED,
        RATE_LIMITED,
        MODEL_INVALID,
        AUTH_FAILED,
        TEMPORARY_ERROR,
        DISABLED,
        UNKNOWN,
    }

    private val outputWords = listOf("max_tokens", "max tokens", "maxoutputtokens", "max_output_tokens", "max_completion_tokens", "output tokens", "completion tokens")
    private val contextWords = listOf("context length", "context_length", "context window", "prompt is too long", "too many tokens", "maximum context", "input is too long", "reduce the length", "request too large")
    private val modelGone = listOf(
        "does not exist", "not exist", "model_not_found", "not found", "decommissioned", "deprecated", "no longer",
        "do not have access", "does not have access", "not have access to model", "unknown model", "invalid model",
        "model not available", "not available", "unsupported model", "is not supported", "not a valid model", "no such model",
        "no endpoints found", "retired",
    )

    /** What [code] and [message] (the provider's own text) mean. [retryAfter] is the header when there was one. */
    fun classify(code: Int?, message: String?, retryAfter: String? = null): Problem {
        val m = message.orEmpty().lowercase()
        if (code == null) return if (m.isEmpty()) Problem.Other else Problem.Temporary
        if (code == 429) return Problem.RateLimited(parseRetryAfter(retryAfter, m))
        if (code in 500..599 || code == 408) return Problem.Temporary
        if (code == 400 || code == 413 || code == 422) {
            if (contextWords.any { it in m }) return Problem.ContextTooLong(readLimit(m, bareNumbers = false))
            if (outputWords.any { it in m }) readLimit(m)?.let { return Problem.MaxTokens(it) }
        }
        if (code == 400 || code == 403 || code == 404 || code == 422) {
            if ("model" in m && modelGone.any { it in m }) return Problem.ModelInvalid
        }
        if (code == 401 || code == 403) return Problem.AuthFailed
        return Problem.Other
    }

    /** The token limit in a message like "must be less than or equal to 8192" or "maximum of 4096". */
    fun readLimit(message: String, bareNumbers: Boolean = true): Int? {
        // The status code ("HTTP 400") is not a limit
        val m = message.lowercase().replace(",", "").replace(Regex("(?:http|status|code|error)\\s*:?\\s*\\d{3}\\b"), " ")
        val patterns = listOf(
            Regex("less than or equal to\\s*`?(\\d{3,7})"),
            Regex("(?:at most|maximum of|max(?:imum)? is|maximum value(?: is)?|up to|limit(?: is| of)?|<=)\\s*`?(\\d{3,7})"),
            Regex("(?:max_tokens|max tokens|max_completion_tokens|maxoutputtokens|context length)[^0-9]{0,40}?(\\d{3,7})"),
            Regex("(\\d{3,7})\\s*(?:tokens|token)"),
        )
        // "must be <= 8192, got 16000" or "16000 > 8192": the smallest number named is the limit. Bare numbers count
        // too, but not those inside a model name ("command-a-03-2025") or a word
        val named = patterns.flatMap { p -> p.findAll(m).mapNotNull { it.groupValues[1].toIntOrNull() }.toList() }
        if (named.isEmpty()) return null
        if (!bareNumbers) return named.filter { it in 256..2_000_000 }.minOrNull()
        val bare = Regex("(?<![\\w.-])(\\d{3,7})(?![\\w.-])").findAll(m).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return (named + bare).filter { it in 256..2_000_000 }.minOrNull()
    }

    fun parseRetryAfter(header: String?, message: String = ""): Long? {
        header?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceIn(1_000, 3_600_000) }
        val inMessage = Regex("(?:try again in|retry after|retry in)\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|s|sec|seconds|m|min|minutes)?").find(message.lowercase())
            ?: return null
        val value = inMessage.groupValues[1].toDoubleOrNull() ?: return null
        val ms = when (inMessage.groupValues[2]) {
            "ms" -> value
            "m", "min", "minutes" -> value * 60_000
            else -> value * 1000
        }
        return ms.toLong().coerceIn(1_000, 3_600_000)
    }

    /** The status a key gets after [problem]; [authStrikes] counts refusals in a row. */
    fun statusAfter(problem: Problem, authStrikes: Int): Status = when (problem) {
        is Problem.MaxTokens, is Problem.ContextTooLong -> Status.DEGRADED
        Problem.ModelInvalid -> Status.MODEL_INVALID
        is Problem.RateLimited -> Status.RATE_LIMITED
        // One refusal can be a glitch of the site; only two in a row put the key out
        Problem.AuthFailed -> if (authStrikes >= AUTH_STRIKES_TO_DISABLE) Status.DISABLED else Status.AUTH_FAILED
        Problem.Temporary -> Status.TEMPORARY_ERROR
        Problem.Other -> Status.DEGRADED
    }

    /** How long a key rests after [problem] before the router gives it work again. */
    fun cooldownFor(problem: Problem, streak: Int): Long = when (problem) {
        is Problem.RateLimited -> problem.retryAfterMs ?: (60_000L * (1 shl streak.coerceIn(0, 4)))
        Problem.Temporary -> (15_000L * (streak + 1)).coerceAtMost(300_000L)
        Problem.AuthFailed -> 30 * 60_000L
        else -> 0L
    }

    const val AUTH_STRIKES_TO_DISABLE = 2

    /**
     * Pure ranking of keys for a call, best first: usable states before tired ones, then fewer errors, more
     * quota left, faster, then less used. Keys that are disabled or still cooling down are left out (unless
     * nothing else is left, then the one that recovers first).
     */
    data class Candidate(
        val id: String,
        val status: Status,
        val cooldownUntil: Long,
        val strength: Int,
        val quotaPercent: Int,
        val errorRate: Float,
        val latencyMs: Long,
        val inFlight: Int,
        val capable: Boolean = true,
    )

    fun rank(candidates: List<Candidate>, now: Long): List<Candidate> {
        val usable = candidates.filter { it.capable && it.status != Status.DISABLED }
        val ready = usable.filter { it.cooldownUntil <= now && it.status != Status.AUTH_FAILED }
        if (ready.isEmpty()) return usable.filter { it.status != Status.AUTH_FAILED }.sortedBy { it.cooldownUntil }
        return ready.sortedWith(
            compareBy<Candidate> { statusRank(it.status) }
                .thenBy { (it.errorRate * 10).toInt() }
                .thenByDescending { it.quotaPercent / 25 }
                .thenByDescending { it.strength }
                .thenBy { it.latencyMs }
                .thenBy { it.inFlight },
        )
    }

    private fun statusRank(s: Status): Int = when (s) {
        Status.ACTIVE -> 0
        Status.UNKNOWN -> 1
        Status.DEGRADED, Status.MODEL_INVALID -> 2
        Status.TEMPORARY_ERROR -> 3
        Status.RATE_LIMITED -> 4
        else -> 5
    }
}
