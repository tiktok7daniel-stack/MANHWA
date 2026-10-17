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
import kotlinx.coroutines.launch
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

    /** The reader's own Arabic, for translators built outside [translatorFor] (the phone's model). */
    fun styleInstruction(to: TextTranslatorLanguage): String = eu.kanade.translation.style.StyleProfile.instruction(prefs, to.name)

    /** The app, for parts of the team that need it (the on-device AI fallback). */
    val appContext: Context get() = context

    /** How much each key has left (see [UsageMeter]). */
    val usage = UsageMeter(prefs.translationTeamUsage())

    /** MANHWA: each key's health, limits and errors (see [KeyHealthStore]). */
    val keyHealth = KeyHealthStore(prefs.translationTeamKeyHealth())

    /** MANHWA: each key's record of the last days, for the "best keys" report. */
    val record = KeyRecord(prefs.translationTeamRecord())

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
        /** MANHWA: the site the key comes from ([KeySites] id, or [KeySites.CUSTOM]). */
        val site: String = "",
        /** The name the reader gave a custom site. */
        val siteName: String = "",
        /** The model picked automatically from the key's live list (when no model is chosen by hand). */
        val autoModel: String = "",
        /** When [autoModel] was last checked against the live list. */
        val modelCheckedAt: Long = 0,
        /** MANHWA model health: the longest answer the model gives (read from the site's error), 0 = not known. */
        val maxOutput: Int = 0,
        /** The longest request the model reads, in tokens, 0 = not known. */
        val contextLimit: Int = 0,
        /** Models found dead for this key: never picked again. */
        val deadModels: List<String> = emptyList(),
    ) {
        /** The site of this key; keys added before sites existed are told from their provider and server. */
        val siteOrGuess: String
            get() = site.ifBlank {
                when (providerEnum) {
                    AiTranslator.Provider.GEMINI -> "gemini"
                    AiTranslator.Provider.OPENROUTER -> "openrouter"
                    AiTranslator.Provider.GROQ -> "groq"
                    AiTranslator.Provider.DEEPSEEK -> "deepseek"
                    AiTranslator.Provider.CLAUDE -> "claude"
                    AiTranslator.Provider.OPENAI_COMPATIBLE ->
                        if (baseUrl.isBlank()) "openai" else KeySites.byUrl(baseUrl)?.id ?: KeySites.CUSTOM
                }
            }

        /** "Groq", "NaraRouter", or the custom site's name. */
        val displayName: String
            get() = siteName.ifBlank { KeySites.byId(site)?.name.orEmpty() }.ifBlank {
                when (providerEnum) {
                    AiTranslator.Provider.GEMINI -> "Gemini"
                    AiTranslator.Provider.OPENROUTER -> "OpenRouter"
                    AiTranslator.Provider.OPENAI_COMPATIBLE -> "OpenAI"
                    AiTranslator.Provider.CLAUDE -> "Claude"
                    AiTranslator.Provider.GROQ -> "Groq"
                    AiTranslator.Provider.DEEPSEEK -> "DeepSeek"
                }
            }

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
        /** Failures in a row: a key that keeps failing rests longer each time. */
        val streak: Int = 0,
    ) {
        val resting: Boolean get() = System.currentTimeMillis() < restingUntil
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val healScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val _members = MutableStateFlow(load())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _analysis = MutableStateFlow(loadAnalysis())
    val analysis: StateFlow<Map<String, Analysis>> = _analysis.asStateFlow()

    private val _health = MutableStateFlow<Map<String, Health>>(emptyMap())
    val health: StateFlow<Map<String, Health>> = _health.asStateFlow()

    /** MANHWA: every AI call of the app goes through it (priorities, reserve, model per task, failover). */
    val router: AiRouter by lazy { AiRouter(this, prefs.translationTeamLanes()) }

    /** The language translations go to, as set by the reader. */
    fun targetLanguage(): TextTranslatorLanguage = TextTranslatorLanguage.fromPref(prefs.translateToLanguage())

    private val analyzing = ConcurrentHashMap.newKeySet<String>()
    private val _analyzingIds = MutableStateFlow<Set<String>>(emptySet())
    val analyzingIds: StateFlow<Set<String>> = _analyzingIds.asStateFlow()

    /**
     * Every change to the keys goes through here: two threads never write over each other's change. Made before
     * the init block below, which may add the first key (made after it, it was still null there: a crash).
     */
    private val membersLock = Any()

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

    private fun mutate(change: (List<Member>) -> List<Member>) = synchronized(membersLock) { save(change(_members.value)) }

    private fun mutateAnalysis(change: (Map<String, Analysis>) -> Map<String, Analysis>) =
        synchronized(membersLock) { saveAnalysis(change(_analysis.value)) }

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
        val member = Member(
            provider = (provider ?: detectProvider(key)).name,
            key = key.trim(),
            model = model.trim(),
            site = if (provider == null) KeySites.forKey(key)?.id.orEmpty() else "",
        )
        mutate { list -> list + member }
        return member
    }

    fun update(member: Member) {
        mutate { list -> list.map { if (it.id == member.id) member else it } }
        // A changed key or model is a different member: what was learnt about it no longer holds
        mutateAnalysis { map -> map - member.id }
        _health.update { it - member.id }
        keyHealth.forget(member.id)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        mutate { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    fun remove(id: String) {
        usage.forget(id)
        keyHealth.forget(id)
        record.forget(id)
        mutate { list -> list.filterNot { it.id == id } }
        mutateAnalysis { map -> map - id }
        _health.update { it - id }
    }

    /** The members the team works with: enabled, with a key, and not known to be broken. */
    fun active(): List<Member> = _members.value.filter { m ->
        m.enabled && m.key.isNotBlank() && _analysis.value[m.id]?.let { it.ok || it.limited } != false && !usage.exhausted(m) &&
            keyHealth[m.id].statusEnum != ModelHealthManager.Status.DISABLED
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
        runCatching { refreshModels() }
        _members.value.filter { it.enabled && it.key.isNotBlank() && _analysis.value[it.id] == null && it.id !in analyzing }
            .forEach { analyze(it) }
    }

    fun reportSuccess(id: String, latencyMs: Long) {
        _health.update { map ->
            val old = map[id] ?: Health()
            val latency = if (old.latencyMs == 0L) latencyMs else (old.latencyMs * 0.7 + latencyMs * 0.3).toLong()
            map + (id to old.copy(latencyMs = latency, done = old.done + 1, restingUntil = 0, streak = 0))
        }
        keyHealth.success(id, "")
        record.success(id, latencyMs)
    }

    /** A call that worked, whose time says nothing about the key's speed (chat, search, review). */
    fun reportOk(id: String) {
        _health.update { map ->
            val old = map[id] ?: Health()
            map + (id to old.copy(done = old.done + 1, restingUntil = 0, streak = 0))
        }
        keyHealth.success(id, "")
        record.success(id, null)
    }

    /** A failed call: a busy or limited key rests for [restMs] while the others carry on. */
    fun reportFailure(id: String, error: Throwable, restMs: Long) {
        _health.update { map ->
            val old = map[id] ?: Health()
            map + (
                id to old.copy(
                    failed = old.failed + 1,
                    lastError = error.message?.take(160) ?: error.javaClass.simpleName,
                    streak = old.streak + 1,
                    // Three failures in a row and more: the key rests 2, 4, 6… minutes (at most 10) before it is tried
                    restingUntil = maxOf(
                        if (restMs > 0) System.currentTimeMillis() + restMs else old.restingUntil,
                        if (old.streak + 1 >= BREAKER_AFTER) {
                            System.currentTimeMillis() + (BREAKER_STEP_MS * (old.streak + 2 - BREAKER_AFTER)).coerceAtMost(BREAKER_MAX_MS)
                        } else {
                            0L
                        },
                    ),
                )
                )
        }
        // MANHWA: the site dropped or renamed the model: a working one is picked from its list by itself
        if (error is AiTranslator.ApiException && (error.code == 400 || error.code == 404) && modelProblem(error.message)) {
            _members.value.firstOrNull { it.id == id }?.let { member -> healScope.launch { runCatching { refreshModel(member) } } }
        }
        // MANHWA key health: what went wrong is recorded; a temporary error only rests the key
        val problem = (error as? AiTranslator.ApiException)?.problem
            ?: if (error is java.io.IOException || error is kotlinx.coroutines.TimeoutCancellationException) ModelHealthManager.Problem.Temporary else ModelHealthManager.Problem.Other
        val status = keyHealth.failure(id, problem, error.message)
        record.failure(id, problem)
        // A key the provider refused twice in a row (wrong or revoked) is out until it is checked again
        if (status == ModelHealthManager.Status.DISABLED) {
            mutateAnalysis { map -> map + (id to Analysis(ok = false, error = error.message?.take(160))) }
        }
    }

    /** MANHWA: the answer limit the site reported for [id]'s model, kept so no call asks for more again. */
    private fun setLimit(id: String, limit: Int) {
        mutate { list -> list.map { if (it.id == id) it.copy(maxOutput = limit) else it } }
        keyHealth.edit(id) { it.copy(maxTokens = limit) }
    }

    private fun setContextLimit(id: String, limit: Int?) {
        if (limit == null) return
        mutate { list -> list.map { if (it.id == id) it.copy(contextLimit = limit) else it } }
        keyHealth.edit(id) { it.copy(contextLimit = limit) }
    }

    /** [dead] is not open to [id]: remembered, a model chosen by hand that died goes back to automatic. */
    private fun markDead(id: String, dead: String, next: String) {
        mutate { list ->
            list.map {
                if (it.id != id) {
                    it
                } else {
                    it.copy(
                        model = if (it.model == dead) "" else it.model,
                        autoModel = next,
                        modelCheckedAt = System.currentTimeMillis(),
                        deadModels = (it.deadModels + dead).distinct().takeLast(20),
                    )
                }
            }
        }
        keyHealth.edit(id) { it.copy(model = next, status = ModelHealthManager.Status.ACTIVE.name) }
    }

    /** A translator for [member], set the way the reader set translation (instructions, temperature…). */
    fun translatorFor(
        member: Member,
        from: TextRecognizerLanguage,
        to: TextTranslatorLanguage,
        lane: AiRouter.Lane = AiRouter.Lane.CHAPTER,
    ): AiTranslator = AiTranslator(
        fromLang = from,
        toLang = to,
        provider = member.providerEnum,
        apiKey = member.key,
        model = router.modelFor(member, lane),
        baseUrl = when {
            member.baseUrl.isNotBlank() -> member.baseUrl.trim()
            KeySites.byId(member.site) != null -> KeySites.byId(member.site)!!.baseUrl
            member.providerEnum == AiTranslator.Provider.OPENAI_COMPATIBLE -> prefs.translationEngineBaseUrl().get().trim()
            else -> ""
        },
        maxOutputTokens = (prefs.translationEngineMaxOutputTokens().get().toIntOrNull() ?: 16000)
            .let { wanted -> if (member.maxOutput > 0) wanted.coerceAtMost(member.maxOutput) else wanted },
        temperature = prefs.translationEngineTemperature().get().toFloatOrNull() ?: 0.4f,
        // MANHWA: the reader's own Arabic (dialect, honorifics, what their choices taught)
        instructions = prefs.translationInstructions().get() + eu.kanade.translation.style.StyleProfile.instruction(prefs, to.name),
        // A model the site retired is replaced by itself: the automatic choice follows
        onModelReplaced = { next -> if (member.model.isBlank()) setAutoModel(member.id, next) },
        siteDefaultModel = KeySites.byId(member.siteOrGuess)?.defaultModel.orEmpty(),
        knownDeadModels = member.deadModels.toSet(),
    ).apply {
        onLimit = { limit -> setLimit(member.id, limit) }
        onContextLimit = { limit -> setContextLimit(member.id, limit) }
        onModelDead = { dead, next -> markDead(member.id, dead, next) }
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
        val name = "${member.displayName} ${member.key.takeLast(4)}"
        context.notify(NOTIFICATION_BASE + (member.id.hashCode() and 0xFFF), Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_translate)
            setContentTitle(context.stringResource(if (out) ATMR.strings.team_out_title else ATMR.strings.team_low_title, name))
            setContentText(context.stringResource(ATMR.strings.team_low_text, level.percent))
            setAutoCancel(true)
        }
    }

    /** Saves the automatic model of [id] without forgetting what was learnt about the key. */
    private fun setAutoModel(id: String, model: String) {
        mutate { list -> list.map { if (it.id == id) it.copy(autoModel = model, modelCheckedAt = System.currentTimeMillis()) else it } }
    }

    /**
     * MANHWA: keeps [member]'s model alive by itself. Reads the key's live model list and picks the best one
     * (the newest Gemini Flash, the site's known good model, or the best translation model by name). A model
     * chosen by hand that the site no longer has is dropped, so the key goes back to automatic.
     * Returns the member as saved.
     */
    suspend fun refreshModel(member: Member): Member = withContext(Dispatchers.IO) {
        val models = runCatching { fetchModels(member) }.onFailure { logcat(LogPriority.WARN, it) { "Models of ${member.displayName} not read" } }
            .getOrNull() ?: return@withContext member
        val site = KeySites.byId(member.siteOrGuess)
        val best = KeySites.bestModel(models, member.providerEnum, site?.defaultModel ?: member.providerEnum.defaultModel, exclude = member.deadModels.toSet())
        val manualGone = member.model.isNotBlank() && models.isNotEmpty() && member.model !in models
        val updated = member.copy(
            model = if (manualGone) "" else member.model,
            autoModel = best ?: member.autoModel,
            modelCheckedAt = System.currentTimeMillis(),
        )
        keyHealth.edit(member.id) { it.copy(models = models.take(200), lastChecked = System.currentTimeMillis()) }
        if (manualGone) logcat(LogPriority.WARN) { "Model ${member.model} is gone from ${member.displayName}, back to automatic ($best)" }
        mutate { list -> list.map { if (it.id == member.id) updated else it } }
        updated
    }

    /** Every automatic key gets its model checked against its site once a day (or now with [force]). */
    suspend fun refreshModels(force: Boolean = false) {
        val now = System.currentTimeMillis()
        _members.value.filter { it.enabled && it.key.isNotBlank() && (force || now - it.modelCheckedAt > MODEL_CHECK_MS) }
            .forEach { refreshModel(it) }
    }

    /** What [checkAll] found: keys that work, keys it repaired (model, limit or site), and keys still broken with why. */
    data class CheckSummary(val working: Int, val fixed: List<String>, val broken: List<Pair<String, String>>, val total: Int)

    /**
     * MANHWA "check and fix all keys": for every key, the site is told from the key and its address, the live
     * model list is read, the best model is picked (dead ones left out), a tiny real request is made (which also
     * lowers the answer limit or replaces the model when the site complains), what the key can do is noted, and
     * its health is saved. Nothing is deleted: a key that still fails only shows why.
     */
    suspend fun checkAll(onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }): CheckSummary = withContext(Dispatchers.IO) {
        val list = _members.value.filter { it.key.isNotBlank() }
        val fixed = mutableListOf<String>()
        val broken = mutableListOf<Pair<String, String>>()
        var working = 0
        list.forEachIndexed { index, original ->
            val name = "${original.displayName} ${original.key.takeLast(4)}"
            onProgress(index, list.size, name)
            // 1. The site, from how the key looks and where it points
            val site = original.site.ifBlank {
                KeySites.forKey(original.key)?.takeIf { it.protocol == original.providerEnum }?.id
                    ?: original.baseUrl.takeIf { it.isNotBlank() }?.let { KeySites.byUrl(it)?.id }
                    ?: ""
            }
            var member = if (site != original.site) original.copy(site = site).also { m -> mutate { list -> list.map { if (it.id == m.id) m else it } } } else original
            // 2–3. The live models and the best of them
            member = refreshModel(member)
            // 4. A tiny real request: heals the answer limit and a dead model by itself
            val result = analyzeNow(member)
            val now = _members.value.firstOrNull { it.id == member.id } ?: member
            val model = router.modelFor(now, AiRouter.Lane.CHAPTER)
            // 5. What the key can do
            val vision = readsImages(now.providerEnum, model)
            val web = now.providerEnum == AiTranslator.Provider.GEMINI || now.providerEnum == AiTranslator.Provider.CLAUDE ||
                now.providerEnum == AiTranslator.Provider.OPENROUTER
            // 6. Its health
            if (result.ok) {
                working++
                keyHealth.edit(now.id) {
                    it.copy(
                        status = ModelHealthManager.Status.ACTIVE.name,
                        model = model,
                        lastChecked = System.currentTimeMillis(),
                        lastSuccess = System.currentTimeMillis(),
                        authStrikes = 0,
                        streak = 0,
                        cooldownUntil = 0,
                        disabledReason = null,
                        vision = vision,
                        webSearch = web,
                    )
                }
                val changed = now.site != original.site || now.maxOutput != original.maxOutput ||
                    router.modelFor(original, AiRouter.Lane.CHAPTER) != model
                if (changed) fixed += name
            } else {
                val code = Regex("HTTP (\\d{3})").find(result.error.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                val problem = ModelHealthManager.classify(code, result.error)
                keyHealth.failure(now.id, problem, result.error)
                keyHealth.edit(now.id) { it.copy(lastChecked = System.currentTimeMillis(), vision = vision, webSearch = web) }
                broken += name to (result.error ?: "")
            }
        }
        onProgress(list.size, list.size, "")
        CheckSummary(working, fixed, broken, list.size)
    }

    /** What a pasted site address turned out to be: its API server, the protocol it speaks and its models. */
    data class ResolvedSite(val baseUrl: String, val protocol: AiTranslator.Provider, val models: List<String>)

    /**
     * MANHWA any-site keys: finds the API server of whatever the reader pasted ("router.bynara.id",
     * "https://api.example.com", a page of the site...) by trying the usual paths (/v1, /api/v1, /openai/v1...)
     * until one lists models for [key]. Null when none answers; throws when the key is refused.
     */
    suspend fun resolveSite(input: String, key: String): ResolvedSite? = withContext(Dispatchers.IO) {
        KeySites.byUrl(input)?.let { site ->
            val probe = Member(provider = site.protocol.name, key = key, site = site.id)
            val models = runCatching { fetchModels(probe) }.getOrDefault(emptyList())
            return@withContext ResolvedSite(site.baseUrl, site.protocol, models)
        }
        val host = KeySites.hostOf(input).orEmpty()
        val protocol = when {
            "anthropic" in host -> AiTranslator.Provider.CLAUDE
            "googleapis" in host -> AiTranslator.Provider.GEMINI
            else -> AiTranslator.Provider.OPENAI_COMPATIBLE
        }
        var refused: Exception? = null
        for (candidate in KeySites.candidates(input)) {
            val probe = Member(provider = protocol.name, key = key, baseUrl = candidate, site = KeySites.CUSTOM)
            try {
                val models = fetchModels(probe)
                if (models.isNotEmpty()) return@withContext ResolvedSite(candidate, protocol, models)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is AiTranslator.ApiException && (e.code == 401 || e.code == 403)) refused = e
            }
        }
        refused?.let { throw it }
        null
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

    /**
     * [heals]: models already replaced in this check (up to [MAX_HEALS]); [probing]: the key is being tried on
     * another site, which is not looked for again.
     */
    private suspend fun analyzeNow(member: Member, heals: Int = 0, probing: Boolean = false): Analysis {
        analyzing += member.id
        _analyzingIds.value = analyzing.toSet()
        // Automatic keys start on a model their site really has
        @Suppress("NAME_SHADOWING")
        val member = if (heals == 0 && member.model.isBlank() && member.autoModel.isBlank()) refreshModel(member) else member
        var failCode: Int? = null
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
            failCode = code
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
        // The model is gone or wrong: it is left out and another from the site's list is tried, a few times
        if (!result.ok && !result.limited && heals < MAX_HEALS && modelProblem(result.error)) {
            val failed = member.model.ifBlank { member.autoModel }
            val fixed = refreshModel(withDeadModel(member, failed))
            val next = fixed.model.ifBlank { fixed.autoModel }
            if (next.isNotBlank() && next != failed) return analyzeNow(fixed, heals + 1, probing)
            // No other model to try (the list could not be read): the key stays as it was
            mutate { list -> list.map { if (it.id == member.id) member else it } }
        }
        // MANHWA: a plain "sk-" key refused here may be from another site whose keys look the same (NaraRouter,
        // DeepSeek...): each is tried, and the key moves to the first that takes it. Only on 401 (the key is not
        // this site's at all): a 403 can be a working key held back, which is never shown to other sites
        if (!result.ok && !probing && failCode == 401) {
            val others = KeySites.skAlternatives(member.key, member.siteOrGuess)
            if (others.isNotEmpty()) return probeSites(member, result, others)
        }
        mutateAnalysis { map -> map + (member.id to result) }
        _health.update { it - member.id }
        return result
    }

    /** [member] with [dead] left out of its models (a hand-picked one goes back to automatic), saved. */
    private fun withDeadModel(member: Member, dead: String): Member {
        if (dead.isBlank()) return member
        val updated = member.copy(
            model = if (member.model == dead) "" else member.model,
            autoModel = if (member.autoModel == dead) "" else member.autoModel,
            deadModels = (member.deadModels + dead).distinct().takeLast(20),
        )
        mutate { list -> list.map { if (it.id == member.id) updated else it } }
        return updated
    }

    /**
     * Tries the key refused on [member]'s site on each of [others]: a site that lists models for it and then
     * answers keeps it. When none does, the key stays as it was and the error names every site tried.
     */
    private suspend fun probeSites(member: Member, refused: Analysis, others: List<KeySites.Site>): Analysis {
        var accepted = false
        try {
            for (site in others) {
                val moved = member.copy(
                    provider = site.protocol.name,
                    site = site.id,
                    siteName = "",
                    baseUrl = "",
                    model = "",
                    autoModel = "",
                    deadModels = emptyList(),
                )
                val models = runCatching { fetchModels(moved) }.getOrDefault(emptyList())
                if (models.isEmpty()) continue
                mutate { list -> list.map { if (it.id == member.id) moved else it } }
                val result = analyzeNow(moved, probing = true)
                // Taken: it answered, or it is out of quota for now (a server error says nothing about the key)
                if (result.ok || (result.limited && result.error.orEmpty().startsWith("HTTP 429"))) {
                    accepted = true
                    keyHealth.forget(member.id)
                    logcat(LogPriority.INFO) { "Key ${member.key.takeLast(4)} moved from ${member.displayName} to ${site.name}" }
                    return result
                }
            }
        } finally {
            // None took it (or the check was stopped): back to where it was
            if (!accepted) mutate { list -> list.map { if (it.id == member.id) member else it } }
        }
        val tried = (listOf(member.displayName) + others.map { it.name }).joinToString(", ")
        val result = refused.copy(error = context.stringResource(ATMR.strings.team_sites_tried, tried, refused.error.orEmpty()).take(300))
        mutateAnalysis { map -> map + (member.id to result) }
        _health.update { it - member.id }
        return result
    }

    private fun modelProblem(error: String?): Boolean {
        val e = error.orEmpty().lowercase()
        return "model" in e && listOf("not found", "not exist", "does not exist", "invalid", "unknown", "deprecated", "no longer", "decommissioned", "unsupported", "not available")
            .any { it in e }
    }

    companion object {
        private const val BREAKER_AFTER = 3
        private const val BREAKER_STEP_MS = 120_000L
        private const val BREAKER_MAX_MS = 600_000L

        /** How long a key rests after [error]: busy or out of quota rests longer than a glitch. */
        fun restFor(error: Throwable): Long = when {
            error is AiTranslator.ApiException && error.code == 429 -> 60_000L
            error is AiTranslator.ApiException && error.code in 500..504 -> 20_000L
            error is kotlinx.coroutines.TimeoutCancellationException -> 20_000L
            error is java.io.IOException -> 10_000L
            else -> 0L
        }

        private const val DEFAULT_STRENGTH = 50

        /** Models replaced in one check before the key is called broken. */
        private const val MAX_HEALS = 3
        private const val MODEL_CHECK_MS = 24 * 60 * 60 * 1000L
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
