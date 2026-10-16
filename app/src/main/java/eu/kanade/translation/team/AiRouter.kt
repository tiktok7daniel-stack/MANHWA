package eu.kanade.translation.team

import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA: the one door every AI call of the app goes through, so the team's keys serve what matters first.
 *
 * - **Priorities.** Every call belongs to a [Lane]. The page being read ([Lane.LIVE]) always gets a key at once,
 *   even while chapters are translated in the background (it may use one call more than a key's usual share);
 *   then chapter translation and the editor's review, then chat and search, then the home screen and other
 *   extras. When a key is busy, waiting calls are let in by lane first, then by arrival.
 * - **A reserve.** The last [RESERVE_PERCENT]% of a key's daily quota is kept for translation: chat and the home
 *   screen do not use a key that low.
 * - **The right model.** A key set to choose its model by itself uses a light model for chat and extras and its
 *   strongest one for the review; translation keeps the key's balanced default. A model the reader chose is
 *   always used as it is.
 * - **Failover.** A call that fails because the key is busy, out of quota, refused or unreachable moves to the
 *   next key at once; a key that keeps failing rests longer each time (see [TeamStore.reportFailure]).
 * - **A record.** Calls made today by each lane, so the reader sees where the quota went.
 */
class AiRouter internal constructor(
    private val store: TeamStore,
    private val record: Preference<String>,
) {

    enum class Lane(val rank: Int) {
        LIVE(0),
        CHAPTER(1),
        REVIEW(1),
        CHAT(2),
        BACKGROUND(3),
    }

    class NoKeyException : IllegalStateException("No AI key can take this call")

    private class Waiter(val lane: Lane, val order: Long, val gate: CompletableDeferred<Unit> = CompletableDeferred())

    private val lock = Any()
    private val running = HashMap<String, MutableMap<Lane, Int>>()
    private val waiting = HashMap<String, MutableList<Waiter>>()
    private var arrivals = 0L

    private val _working = MutableStateFlow<Map<String, Map<Lane, Int>>>(emptyMap())

    /** What every key is doing right now: calls in flight by lane. */
    val working: StateFlow<Map<String, Map<Lane, Int>>> = _working.asStateFlow()

    @Serializable
    private data class Day(val day: String = "", val calls: Map<String, Int> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    private val _today = MutableStateFlow(loadDay())

    private val _todayLanes = MutableStateFlow(_today.value.toLanes())

    /** Calls made today by each lane. */
    val todayLanes: StateFlow<Map<Lane, Int>> = _todayLanes.asStateFlow()

    private fun loadDay(): Day = runCatching { json.decodeFromString<Day>(record.get()) }.getOrDefault(Day())
        .let { if (it.day == UsageMeter.today()) it else Day(UsageMeter.today()) }

    private fun Day.toLanes(): Map<Lane, Int> = calls.mapNotNull { (k, v) -> Lane.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap()

    /** Counts one call of [lane] for today. */
    fun count(lane: Lane) {
        synchronized(lock) {
            val current = _today.value.let { if (it.day == UsageMeter.today()) it else Day(UsageMeter.today()) }
            val next = current.copy(calls = current.calls + (lane.name to (current.calls[lane.name] ?: 0) + 1))
            _today.value = next
            _todayLanes.value = next.toLanes()
            record.set(json.encodeToString(next))
        }
    }

    /**
     * Calls each key may run at once, learnt while it works (additive increase, multiplicative decrease, the way
     * the internet itself shares a line): every answer that comes back adds a little, so a key with room climbs
     * to [MAX_CAPACITY] by itself; a "too busy" answer halves it at once. It starts from the key's strength.
     */
    private val adaptive = HashMap<String, Double>()

    private val _capacities = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** The calls each key may run at once right now (what it learnt). */
    val capacities: StateFlow<Map<String, Int>> = _capacities.asStateFlow()

    private fun startingCapacity(memberId: String): Double {
        val strength = store.strength(memberId)
        return when {
            strength >= STRONG -> 3.0
            strength >= AVERAGE -> 2.0
            else -> 1.0
        }
    }

    /** Calls [memberId] may run at once now. */
    fun capacity(memberId: String): Int = synchronized(lock) {
        (adaptive[memberId] ?: startingCapacity(memberId)).toInt().coerceIn(1, MAX_CAPACITY)
    }

    /**
     * How a call on [memberId] ended: null when it worked (one step up), or the error. Busy, overloaded or slow
     * answers halve the key's calls; other errors (a bad request) say nothing about its room and change nothing.
     */
    fun report(memberId: String, error: Throwable?) {
        synchronized(lock) {
            val current = adaptive[memberId] ?: startingCapacity(memberId)
            val next = when {
                error == null -> (current + 1.0 / current).coerceAtMost(MAX_CAPACITY.toDouble())
                congested(error) -> (current / 2).coerceAtLeast(1.0)
                else -> current
            }
            adaptive[memberId] = next
            _capacities.value = adaptive.mapValues { (_, v) -> v.toInt().coerceIn(1, MAX_CAPACITY) }
            // Room grew: calls waiting for this key may go now
            pumpLocked(memberId)
        }
    }

    private fun congested(error: Throwable): Boolean = when (error) {
        is AiTranslator.ApiException -> error.code == 429 || error.code in 500..504
        is TimeoutCancellationException -> true
        else -> false
    }

    /** Takes a permit for [memberId] only if one is free right now (the team's workers ask again a moment later). */
    fun tryAcquire(memberId: String, lane: Lane): Boolean = synchronized(lock) {
        if (canStart(memberId, lane)) {
            start(memberId, lane)
            true
        } else {
            false
        }
    }

    private fun limit(lane: Lane, capacity: Int): Int = when (lane) {
        // The page on screen never waits behind background work
        Lane.LIVE -> capacity + 1
        Lane.CHAPTER, Lane.REVIEW -> capacity
        // A question in the chat gets one call of its own, so it never waits for a whole chapter
        Lane.CHAT -> capacity + 1
        // Extras only use a key that is doing nothing else
        Lane.BACKGROUND -> 1
    }

    /** Whether [lane] may use [member] at all: chat and extras leave the last part of the quota to translation. */
    fun allowed(member: TeamStore.Member, lane: Lane): Boolean {
        if (lane == Lane.LIVE || lane == Lane.CHAPTER || lane == Lane.REVIEW) return true
        val level = store.usage.level(member)
        return !level.exhausted && level.percent >= RESERVE_PERCENT
    }

    /** The model [member] uses for [lane]: the reader's choice, or the one that suits the work. */
    fun modelFor(member: TeamStore.Member, lane: Lane): String {
        if (member.model.isNotBlank()) return member.model
        // Automatic: the model picked from the key's own live list, kept up to date by the team
        val site = KeySites.byId(member.siteOrGuess)
        // MANHWA: never the provider's own default on another site (Cohere is not "gpt-4o-mini")
        val auto = member.autoModel.takeIf { it.isNotBlank() && it !in member.deadModels }
            ?: site?.defaultModel?.takeIf { it.isNotBlank() && it !in member.deadModels }
            ?: member.providerEnum.defaultModel
        // Sites of their own (Mistral, Cerebras, a custom router...) have one automatic model for everything
        val ownSite = member.siteOrGuess == KeySites.CUSTOM ||
            (site != null && site.protocol == eu.kanade.translation.translator.AiTranslator.Provider.OPENAI_COMPATIBLE && site.id != "openai")
        // A light or strong model is used only while the key really has it (a dead one falls back to the automatic one)
        val live = store.keyHealth[member.id].models
        fun usable(model: String) = model.isNotBlank() && model !in member.deadModels && (live.isEmpty() || model in live)
        return when (lane) {
            Lane.CHAT, Lane.BACKGROUND -> if (ownSite) auto else member.providerEnum.lightModel.takeIf(::usable) ?: auto
            Lane.REVIEW -> if (ownSite) auto else member.providerEnum.strongModel.takeIf(::usable) ?: auto
            Lane.LIVE, Lane.CHAPTER -> auto
        }
    }

    // ---- Permits: one per call in flight on a key ----

    private fun inFlight(memberId: String): Int = running[memberId]?.values?.sum() ?: 0

    private fun canStart(memberId: String, lane: Lane, self: Waiter? = null): Boolean {
        if (inFlight(memberId) >= limit(lane, capacity(memberId))) return false
        if ((lane == Lane.BACKGROUND || lane == Lane.CHAT) && (running[memberId]?.get(lane) ?: 0) >= 1) return false
        // Nobody jumps the queue: a call already waiting in a more urgent lane, or earlier in the same one, goes first
        return waiting[memberId].orEmpty().none { other ->
            other !== self && (other.lane.rank < lane.rank || (other.lane.rank == lane.rank && (self == null || other.order < self.order)))
        }
    }

    private fun start(memberId: String, lane: Lane) {
        val lanes = running.getOrPut(memberId) { mutableMapOf() }
        lanes[lane] = (lanes[lane] ?: 0) + 1
        publish()
    }

    private fun publish() {
        _working.value = running.mapValues { (_, lanes) -> lanes.filterValues { it > 0 }.toMap() }.filterValues { it.isNotEmpty() }
    }

    /** Waits until [memberId] may make one more call for [lane]. Every acquire is followed by one [release]. */
    suspend fun acquire(memberId: String, lane: Lane) {
        val waiter = synchronized(lock) {
            if (canStart(memberId, lane)) {
                start(memberId, lane)
                return
            }
            Waiter(lane, arrivals++).also { waiting.getOrPut(memberId) { mutableListOf() } += it }
        }
        try {
            waiter.gate.await()
        } catch (e: CancellationException) {
            synchronized(lock) {
                // Let in already: the permit is ours and goes back
                if (waiting[memberId]?.remove(waiter) != true) releaseLocked(memberId, lane)
            }
            throw e
        }
    }

    fun release(memberId: String, lane: Lane) {
        synchronized(lock) { releaseLocked(memberId, lane) }
    }

    private fun releaseLocked(memberId: String, lane: Lane) {
        running[memberId]?.let { lanes ->
            lanes[lane] = ((lanes[lane] ?: 1) - 1).coerceAtLeast(0)
        }
        pumpLocked(memberId)
    }

    /** Lets in the waiting calls, most urgent lane first, then by arrival, while the key has room. */
    private fun pumpLocked(memberId: String) {
        val queue = waiting[memberId] ?: return publish()
        while (queue.isNotEmpty()) {
            val next = queue.minWith(compareBy<Waiter>({ it.lane.rank }, { it.order }))
            if (!canStart(memberId, next.lane, self = next)) break
            queue.remove(next)
            start(memberId, next.lane)
            next.gate.complete(Unit)
        }
        publish()
    }

    // ---- One call, any key ----

    /**
     * Runs [block] with a translator of the best key that may take [lane], moving to the next key when one is
     * busy, out of quota, refused or unreachable. [prefer] limits the keys (e.g. those that can search the web).
     * Throws [NoKeyException] when no key can take the call.
     */
    suspend fun <T> ask(
        lane: Lane,
        from: TextRecognizerLanguage = TextRecognizerLanguage.ENGLISH,
        to: TextTranslatorLanguage = store.targetLanguage(),
        prefer: (TeamStore.Member) -> Boolean = { true },
        block: suspend (AiTranslator) -> T,
    ): T = withContext(Dispatchers.IO) {
        val candidates = rankedFor(lane, prefer)
        if (candidates.isEmpty()) throw NoKeyException()
        var last: Throwable? = null
        for (member in candidates) {
            acquire(member.id, lane)
            try {
                val translator = store.translatorFor(member, from, to, lane)
                // One key at a time here: a failing call goes to the next key instead of waiting
                translator.fastFail = candidates.size > 1
                val result = block(translator)
                count(lane)
                store.reportOk(member.id)
                report(member.id, null)
                return@withContext result
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                count(lane)
                last = e
                store.reportFailure(member.id, e, TeamStore.restFor(e))
                report(member.id, e)
                if (!handOver(e)) throw e
                logcat(LogPriority.WARN, e) { "Key ${member.providerEnum} failed a $lane call, trying the next one" }
            } finally {
                release(member.id, lane)
            }
        }
        throw last ?: NoKeyException()
    }

    /**
     * MANHWA smart switching: the keys that may take [lane], best first by health, quota left, error rate,
     * strength, speed and load ([ModelHealthManager.rank]). Disabled keys are out; keys cooling down after an
     * error come last, only when nothing else is left.
     */
    fun rankedFor(lane: Lane, prefer: (TeamStore.Member) -> Boolean = { true }): List<TeamStore.Member> {
        val health = store.health.value
        val members = store.active().filter { prefer(it) && allowed(it, lane) }
        val now = System.currentTimeMillis()
        val byId = members.associateBy { it.id }
        val ranked = ModelHealthManager.rank(
            members.map { m ->
                val kh = store.keyHealth[m.id]
                val h = health[m.id]
                ModelHealthManager.Candidate(
                    id = m.id,
                    status = kh.statusEnum,
                    cooldownUntil = maxOf(kh.cooldownUntil, h?.restingUntil ?: 0L),
                    strength = store.strength(m.id),
                    quotaPercent = store.usage.level(m).percent,
                    errorRate = kh.errorRate,
                    latencyMs = h?.latencyMs ?: 0L,
                    inFlight = synchronized(lock) { inFlight(m.id) },
                )
            },
            now,
        ).mapNotNull { byId[it.id] }
        // Nothing ranked (all refused): keep the old order so the reader still gets the real error
        return ranked.ifEmpty { members.sortedByDescending { store.strength(it.id) } }
    }

    /** Whether another key may succeed where this one failed. */
    private fun handOver(e: Throwable): Boolean = when (e) {
        is AiTranslator.ApiException -> e.code == 401 || e.code == 403 || e.code == 408 || e.code == 429 || e.code in 500..599 ||
            e.problem.let { it is ModelHealthManager.Problem.ContextTooLong || it == ModelHealthManager.Problem.ModelInvalid || it is ModelHealthManager.Problem.MaxTokens }
        is TimeoutCancellationException -> true
        is java.io.IOException -> true
        else -> false
    }

    companion object {
        /** Percent of a key's daily quota kept for translation. */
        const val RESERVE_PERCENT = 20

        /** The most calls one key may run at once, however well it does. */
        const val MAX_CAPACITY = 6
        private const val STRONG = 70
        private const val AVERAGE = 40
    }
}
