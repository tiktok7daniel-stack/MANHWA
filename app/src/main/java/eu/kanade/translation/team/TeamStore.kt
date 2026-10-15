package eu.kanade.translation.team

import android.content.Context
import dev.zacsweers.metro.AppScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.at.ATMR
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * MANHWA translation team: the reader's API keys (any number, any provider), what each one can do, and how
 * strong it is right now.
 *
 * A key is [analyze]d when it is added: does it work (and why not), how fast it answers, its model, whether it
 * reads images. From that it gets a [Member.strength] (1–100); while translating, every answer, error and busy
 * signal moves it up or down ([reportSuccess], [reportFailure]), so the work goes where it is done best. The
 * strongest key is the team's lead (editor and scout); the others translate.
 */
@Inject
@SingleIn(AppScope::class)
class TeamStore(private val context: Context, private val prefs: TranslationPreferences) {

    /** How much each key has left (see [UsageMeter]). */
    val usage = UsageMeter(prefs.translationTeamUsage())

    /** Keys already warned about today ("almost out", "out"), so each warning comes once. */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    @Serializable
    data class Member(
        val id: String = UUID.randomUUID().toString(),
        val provider: String,
        val key: String,
        val model: String = "",
        val enabled: Boolean = true,
        /** Requests a day the key may make (Gemini tells nothing), 0 = a default by model. */
        val dailyLimit: Int = 0,
        /** Server address for OpenAI-compatible keys (empty = the provider's own). */
        val baseUrl: String = "",
    ) {
        val providerEnum: AiTranslator.Provider
            get() = AiTranslator.Provider.entries.firstOrNull { it.name == provider } ?: AiTranslator.Provider.GEMINI
    }

    /** What the analysis found: [ok] or why not, speed, model, images, and the strength it gives. */
    @Serializable
    data class Analysis(
        val ok: Boolean,
        val error: String? = null,
        val latencyMs: Long = 0,
        val model: String = "",
        val vision: Boolean = false,
        val limited: Boolean = false,
        val strength: Int = 0,
        val at: Long = System.currentTimeMillis(),
    )

    /** Live state while translating: resting after a busy signal, how fast it has been, and its record. */
    data class Health(
        val restingUntil: Long = 0,
        val latencyMs: Long = 0,
        val done: Int = 0,
        val failed: Int = 0,
        val lastError: String? = null,
    ) {
        val resting: Boolean get() = System.currentTimeMillis() < restingUntil
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _members = MutableStateFlow(load())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _analysis = MutableStateFlow(loadAnalysis())
    val analysis: StateFlow<Map<String, Analysis>> = _analysis.asStateFlow()

    private val _health = MutableStateFlow<Map<String, Health>>(emptyMap())
    val health: StateFlow<Map<String, Health>> = _health.asStateFlow()

    private val analyzing = ConcurrentHashMap.newKeySet<String>()
    private val _analyzingIds = MutableStateFlow<Set<String>>(emptySet())
    val analyzingIds: StateFlow<Set<String>> = _analyzingIds.asStateFlow()

    init {
        // First time: the key already saved for the translation engine becomes the first member
        if (_members.value.isEmpty()) {
            val key = prefs.translationEngineApiKey().get().trim()
            if (key.isNotEmpty()) {
                add(key, model = prefs.translationEngineModel().get().trim())
            }
        }
    }

    private fun load(): List<Member> = runCatching {
        json.decodeFromString<List<Member>>(prefs.translationTeamMembers().get())
    }.onFailure { logcat(LogPriority.WARN, it) }.getOrDefault(emptyList())

    private fun loadAnalysis(): Map<String, Analysis> = runCatching {
        json.decodeFromString<Map<String, Analysis>>(prefs.translationTeamAnalysis().get())
    }.getOrDefault(emptyMap())

    private fun save(list: List<Member>) {
        _members.value = list
        prefs.translationTeamMembers().set(json.encodeToString(list))
    }

    private fun saveAnalysis(map: Map<String, Analysis>) {
        _analysis.value = map
        prefs.translationTeamAnalysis().set(json.encodeToString(map))
    }

    /** Adds a key; its provider is told from how it looks unless given. Returns the new member. */
    fun add(key: String, provider: AiTranslator.Provider? = null, model: String = ""): Member {
        val member = Member(provider = (provider ?: detectProvider(key)).name, key = key.trim(), model = model.trim())
        save(_members.value + member)
        return member
    }

    fun update(member: Member) {
        save(_members.value.map { if (it.id == member.id) member else it })
        // A changed key or model is a different member: what was learnt about it no longer holds
        saveAnalysis(_analysis.value - member.id)
        _health.update { it - member.id }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(_members.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(id: String) {
        usage.forget(id)
        save(_members.value.filterNot { it.id == id })
        saveAnalysis(_analysis.value - id)
        _health.update { it - id }
    }

    /** The members the team works with: enabled, with a key, and not known to be broken. */
    fun active(): List<Member> = _members.value.filter { m ->
        m.enabled && m.key.isNotBlank() && _analysis.value[m.id]?.let { it.ok || it.limited } != false && !usage.exhausted(m)
    }

    /** Strength now (1–100): the analysis, adjusted by how the member has done while translating. */
    fun strength(id: String): Int {
        val base = _analysis.value[id]?.strength?.takeIf { it > 0 } ?: DEFAULT_STRENGTH
        val health = _health.value[id] ?: return base
        val tries = health.done + health.failed
        val record = if (tries == 0) 1f else (health.done + 1f) / (tries + 1f)
        val speed = if (health.latencyMs > 0) (SPEED_REFERENCE_MS / health.latencyMs.toFloat()).coerceIn(0.5f, 1.5f) else 1f
        return (base * record * speed).roundToInt().coerceIn(1, 100)
    }

    /** Each active member's share of the work (0–100), from its strength. */
    fun shares(): Map<String, Int> {
        val list = active().filterNot { _health.value[it.id]?.resting == true }
        val total = list.sumOf { strength(it.id) }.coerceAtLeast(1)
        return list.associate { it.id to strength(it.id) * 100 / total }
    }

    /** The strongest active member: the team's editor and scout. */
    fun lead(): Member? {
        val ready = active()
        // A lead that is resting right now hands over to the next strongest key
        return (ready.filterNot { _health.value[it.id]?.resting == true }.ifEmpty { ready }).maxByOrNull { strength(it.id) }
    }

    /**
     * The team's lead as a translator, for the app's other AI features (search, chat, home, descriptions).
     * Null when the team has no working key. The caller closes it.
     */
    fun leadTranslator(
        from: TextRecognizerLanguage = TextRecognizerLanguage.fromPref(prefs.translateFromLanguage()),
        to: TextTranslatorLanguage = TextTranslatorLanguage.fromPref(prefs.translateToLanguage()),
    ): AiTranslator? = lead()?.let { translatorFor(it, from, to) }

    /** Keys never checked (or changed since) are checked now, one after the other. */
    suspend fun analyzeUnchecked() = withContext(Dispatchers.IO) {
        _members.value.filter { it.enabled && it.key.isNotBlank() && _analysis.value[it.id] == null && it.id !in analyzing }
            .forEach { analyze(it) }
    }

    fun reportSuccess(id: String, latencyMs: Long) {
        _health.update { map ->
            val old = map[id] ?: Health()
            val latency = if (old.latencyMs == 0L) latencyMs else (old.latencyMs * 0.7 + latencyMs * 0.3).toLong()
            map + (id to old.copy(latencyMs = latency, done = old.done + 1, restingUntil = 0))
        }
    }

    /** A failed call: a busy or limited key rests for [restMs] while the others carry on. */
    fun reportFailure(id: String, error: Throwable, restMs: Long) {
        _health.update { map ->
            val old = map[id] ?: Health()
            map + (
                id to old.copy(
                    failed = old.failed + 1,
                    lastError = error.message?.take(160) ?: error.javaClass.simpleName,
                    restingUntil = if (restMs > 0) System.currentTimeMillis() + restMs else old.restingUntil,
                )
                )
        }
        // A key the provider refuses (wrong or revoked) is out until it is analysed again
        if (error is AiTranslator.ApiException && (error.code == 401 || error.code == 403)) {
            saveAnalysis(_analysis.value + (id to Analysis(ok = false, error = error.message?.take(160))))
        }
    }

    /** A translator for [member], set the way the reader set translation (instructions, temperature…). */
    fun translatorFor(member: Member, from: TextRecognizerLanguage, to: TextTranslatorLanguage): AiTranslator = AiTranslator(
        fromLang = from,
        toLang = to,
        provider = member.providerEnum,
        apiKey = member.key,
        model = member.model,
        baseUrl = when {
            member.baseUrl.isNotBlank() -> member.baseUrl.trim()
            member.providerEnum == AiTranslator.Provider.OPENAI_COMPATIBLE -> prefs.translationEngineBaseUrl().get().trim()
            else -> ""
        },
        maxOutputTokens = prefs.translationEngineMaxOutputTokens().get().toIntOrNull() ?: 16000,
        temperature = prefs.translationEngineTemperature().get().toFloatOrNull() ?: 0.4f,
        instructions = prefs.translationInstructions().get(),
    ).apply {
        onResponse = { code, headers, body ->
            usage.record(member, code, headers, body)
            warnIfLow(member)
        }
    }

    /** Tells the reader once a day when a key is almost out (under 20%) or out, so it can be changed in time. */
    private fun warnIfLow(member: Member) {
        val level = usage.level(member)
        val out = level.exhausted || level.percent == 0
        if (!out && level.percent >= LOW_PERCENT) return
        val tag = "${member.id}:${UsageMeter.today()}:${if (out) "out" else "low"}"
        if (!warned.add(tag)) return
        val name = "${member.providerEnum.name.lowercase().replaceFirstChar { it.uppercase() }} ${member.key.takeLast(4)}"
        context.notify(NOTIFICATION_BASE + (member.id.hashCode() and 0xFFF), Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_translate)
            setContentTitle(context.stringResource(if (out) ATMR.strings.team_out_title else ATMR.strings.team_low_title, name))
            setContentText(context.stringResource(ATMR.strings.team_low_text, level.percent))
            setAutoCancel(true)
        }
    }

    /**
     * Tries [member] with a tiny translation: does it answer (and why not), how fast, with which model, and
     * whether it reads images. Saves and returns what it found.
     */
    suspend fun analyze(member: Member): Analysis = withContext(Dispatchers.IO) { analyzeNow(member) }

    /** The models [member]'s key may use, read from its provider off the main thread. */
    suspend fun fetchModels(member: Member): List<String> = withContext(Dispatchers.IO) {
        translatorFor(member, TextRecognizerLanguage.ENGLISH, TextTranslatorLanguage.ENGLISH).listModels()
    }

    private suspend fun analyzeNow(member: Member): Analysis {
        analyzing += member.id
        _analyzingIds.value = analyzing.toSet()
        val result = try {
            val translator = translatorFor(member, TextRecognizerLanguage.ENGLISH, TextTranslatorLanguage.fromPref(prefs.translateToLanguage()))
            try {
                translator.fastFail = true
                val started = System.currentTimeMillis()
                val reply = translator.askJson(
                    "You check that a translation key works. Reply with JSON only.",
                    "Translate \"Hello, friend!\" into ${translator.toLang.label}. Reply as {\"t\": \"...\"}.",
                )
                val latency = System.currentTimeMillis() - started
                val answered = reply["t"]?.jsonPrimitive?.content?.isNotBlank() == true
                val model = translator.currentModel
                Analysis(
                    ok = answered,
                    error = if (answered) null else "No answer",
                    latencyMs = latency,
                    model = model,
                    vision = readsImages(member.providerEnum, model),
                    strength = strengthOf(model, latency),
                ).also { usage.refreshOpenRouter(member) }
            } finally {
                translator.close()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val code = (e as? AiTranslator.ApiException)?.code
            Analysis(
                ok = false,
                // Busy or out of quota for now: still a working key, it just rests
                limited = code == 429 || (code != null && code in 500..504),
                error = e.message?.take(200) ?: e.javaClass.simpleName,
                strength = if (code == 429) DEFAULT_STRENGTH / 2 else 0,
            )
        } finally {
            analyzing -= member.id
            _analyzingIds.value = analyzing.toSet()
        }
        saveAnalysis(_analysis.value + (member.id to result))
        _health.update { it - member.id }
        return result
    }

    companion object {
        private const val DEFAULT_STRENGTH = 50
        private const val LOW_PERCENT = 20
        private const val NOTIFICATION_BASE = 0x3A000
        private const val SPEED_REFERENCE_MS = 2_500f

        /** The provider a key belongs to, from how it looks. */
        fun detectProvider(key: String): AiTranslator.Provider {
            val k = key.trim()
            return when {
                k.startsWith("sk-ant") -> AiTranslator.Provider.CLAUDE
                k.startsWith("gsk_") -> AiTranslator.Provider.GROQ
                k.startsWith("sk-or") -> AiTranslator.Provider.OPENROUTER
                k.startsWith("sk-") -> AiTranslator.Provider.OPENAI_COMPATIBLE
                else -> AiTranslator.Provider.GEMINI
            }
        }

        private fun readsImages(provider: AiTranslator.Provider, model: String): Boolean {
            val m = model.lowercase()
            return when (provider) {
                AiTranslator.Provider.GEMINI, AiTranslator.Provider.CLAUDE -> true
                AiTranslator.Provider.OPENROUTER -> listOf("gemini", "claude", "gpt-4o", "gpt-4.1", "gpt-5", "vision", "vl", "pixtral", "llama-4").any { it in m }
                AiTranslator.Provider.OPENAI_COMPATIBLE -> listOf("gpt-4o", "gpt-4.1", "gpt-5", "vision", "vl").any { it in m }
                AiTranslator.Provider.GROQ -> listOf("vision", "llama-4", "scout", "maverick").any { it in m }
                AiTranslator.Provider.DEEPSEEK -> false
            }
        }

        /** 1–100 from the model's class and how fast it answered. */
        fun strengthOf(model: String, latencyMs: Long): Int {
            val m = model.lowercase()
            val tier = when {
                listOf("opus", "pro", "sonnet", "gpt-5", "gpt-4.1", "gpt-4o", "large").any { it in m } && "mini" !in m && "lite" !in m -> 1.3f
                listOf("lite", "nano", "8b", "7b").any { it in m } -> 0.8f
                else -> 1.0f
            }
            val speed = (SPEED_REFERENCE_MS / latencyMs.coerceAtLeast(300).toFloat()).coerceIn(0.4f, 1.6f)
            return (60 * tier * speed).roundToInt().coerceIn(5, 100)
        }
    }
}
