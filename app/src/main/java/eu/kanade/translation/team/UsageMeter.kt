package eu.kanade.translation.team

import eu.kanade.translation.translator.AiTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.preference.Preference
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * MANHWA: how much each key has left, as a percentage that goes down while it is used.
 *
 * Where the provider tells: OpenRouter gives the credit left on the key ([refreshOpenRouter]); Claude and OpenAI
 * send the requests left in their rate window with every answer. Gemini tells nothing, so the requests of the
 * day are counted against a daily limit (by model, editable), reset every day; when Gemini answers "quota
 * exceeded" before the count says so, the estimate is corrected and the key rests until tomorrow.
 */
class UsageMeter(private val store: Preference<String>) {

    @Serializable
    data class Usage(
        val day: String = today(),
        val requests: Int = 0,
        val errors: Int = 0,
        val lastUsed: Long = 0,
        /** 0–100 left as the provider said it, or -1 when it does not say. */
        val reportedLeft: Int = -1,
        /** The daily limit learnt from a "quota exceeded" (Gemini), 0 = not learnt. */
        val learntLimit: Int = 0,
        /** Out for today: quota exceeded. */
        val exhausted: Boolean = false,
    )

    /** What the screen shows: percentage left, whether it is the provider's own number, the day's counts. */
    class Level(val percent: Int, val real: Boolean, val requests: Int, val errors: Int, val lastUsed: Long, val exhausted: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    private val _usage = MutableStateFlow(load())
    val usage: StateFlow<Map<String, Usage>> = _usage.asStateFlow()

    private fun load(): Map<String, Usage> = runCatching { json.decodeFromString<Map<String, Usage>>(store.get()) }.getOrDefault(emptyMap())

    private fun current(id: String): Usage {
        val u = _usage.value[id] ?: return Usage()
        // A new day: the counts start again, what the provider reports stays until it says otherwise
        return if (u.day == today()) u else Usage(reportedLeft = u.reportedLeft)
    }

    /** One change at a time: parallel calls of one key never lose each other's count. */
    private val lock = Any()

    private fun put(id: String, usage: Usage) = synchronized(lock) {
        _usage.update { it + (id to usage) }
        store.set(json.encodeToString(_usage.value))
    }

    /** Every answer of [member]'s key: counted, and read for what the provider says is left. */
    fun record(member: TeamStore.Member, code: Int, headers: Headers, errorBody: String) = synchronized(lock) {
        val old = current(member.id)
        var next = old.copy(
            day = today(),
            requests = old.requests + 1,
            errors = old.errors + if (code in 200..299) 0 else 1,
            lastUsed = System.currentTimeMillis(),
        )
        leftFromHeaders(member.providerEnum, headers)?.let { next = next.copy(reportedLeft = it) }
        if (code == 429 && quotaExceeded(errorBody)) {
            // Out for today: what was counted so far is the real daily limit
            next = next.copy(exhausted = true, learntLimit = if (member.providerEnum == AiTranslator.Provider.GEMINI) next.requests else old.learntLimit)
        }
        put(member.id, next)
    }

    fun level(member: TeamStore.Member): Level {
        val u = current(member.id)
        val (percent, real) = when {
            // MANHWA: the laptop has no daily quota: always full, however many requests it answered today
            member.isHomeServer -> 100 to true
            u.exhausted -> 0 to true
            u.reportedLeft >= 0 -> u.reportedLeft to true
            else -> {
                val limit = (u.learntLimit.takeIf { it > 0 } ?: member.dailyLimit.takeIf { it > 0 } ?: defaultDailyLimit(member))
                ((1f - u.requests.toFloat() / limit) * 100).roundToInt().coerceIn(0, 100) to false
            }
        }
        return Level(percent, real, u.requests, u.errors, u.lastUsed, u.exhausted)
    }

    /** Out of quota for today: the team does not give it work until tomorrow. */
    fun exhausted(member: TeamStore.Member): Boolean = !member.isHomeServer && current(member.id).exhausted

    /** OpenRouter's credit left on the key, as a percentage of its limit (keys without a limit: unknown). */
    suspend fun refreshOpenRouter(member: TeamStore.Member) {
        if (member.providerEnum != AiTranslator.Provider.OPENROUTER) return
        // Asked at most once a minute: the reader translates page by page
        val now = System.currentTimeMillis()
        if (now - (lastRefresh[member.id] ?: 0L) < REFRESH_EVERY_MS) return
        lastRefresh[member.id] = now
        val left = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/key")
                    .header("Authorization", "Bearer ${member.key}")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val data = json.parseToJsonElement(response.body.string()).jsonObject["data"] as? JsonObject
                    val limit = data?.get("limit")?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
                    val remaining = data["limit_remaining"]?.jsonPrimitive?.doubleOrNull
                        ?: (limit - (data["usage"]?.jsonPrimitive?.doubleOrNull ?: 0.0))
                    if (limit <= 0.0) null else (remaining / limit * 100).roundToInt().coerceIn(0, 100)
                }
            }.getOrNull()
        } ?: return
        synchronized(lock) { put(member.id, current(member.id).copy(reportedLeft = left)) }
    }

    private val lastRefresh = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun forget(id: String) = synchronized(lock) {
        _usage.update { it - id }
        store.set(json.encodeToString(_usage.value))
    }

    companion object {
        private const val REFRESH_EVERY_MS = 60_000L
        private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

        /** Google resets Gemini's daily quota at midnight Pacific time. */
        fun today(): String = LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()

        /** A rough free-tier daily limit by model; the reader can set the real one. */
        fun defaultDailyLimit(member: TeamStore.Member): Int {
            if (member.providerEnum != AiTranslator.Provider.GEMINI) return 1_000
            val model = member.model.ifBlank { member.providerEnum.defaultModel }.lowercase()
            return when {
                "lite" in model -> 1_000
                "pro" in model -> 100
                else -> 250
            }
        }

        private fun quotaExceeded(body: String): Boolean {
            val b = body.lowercase()
            return "quota" in b || "per day" in b || "perday" in b || "insufficient" in b || "credits" in b
        }

        /** Requests left in the rate window, from Claude's and OpenAI's headers, as a percentage. */
        private fun leftFromHeaders(provider: AiTranslator.Provider, headers: Headers): Int? {
            val (remaining, limit) = when (provider) {
                AiTranslator.Provider.CLAUDE ->
                    headers["anthropic-ratelimit-requests-remaining"] to headers["anthropic-ratelimit-requests-limit"]
                AiTranslator.Provider.OPENAI_COMPATIBLE, AiTranslator.Provider.OPENROUTER,
                AiTranslator.Provider.GROQ, AiTranslator.Provider.DEEPSEEK,
                ->
                    headers["x-ratelimit-remaining-requests"] to headers["x-ratelimit-limit-requests"]
                AiTranslator.Provider.GEMINI -> return null
            }
            val r = remaining?.toDoubleOrNull() ?: return null
            val l = limit?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            return (r / l * 100).roundToInt().coerceIn(0, 100)
        }
    }
}
