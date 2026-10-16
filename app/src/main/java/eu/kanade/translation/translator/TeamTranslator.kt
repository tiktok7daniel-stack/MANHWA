package eu.kanade.translation.translator

import eu.kanade.translation.data.PageImage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.style.BubbleKind
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.team.ChapterPlanner
import eu.kanade.translation.team.TeamStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA translation team: the reader's keys translate one chapter together.
 *
 * The chapter is split by [ChapterPlanner] into batches of about the same weight, heaviest first, at least one
 * for every call that can run at a time. Every key works on as many batches at once as its strength allows
 * (strong 3, average 2, weak 1), so faster keys simply take more. A key that is busy or out of quota rests and
 * its batch goes straight to another key; a key the provider refuses leaves the team. A batch no key could do is
 * translated by Google, so the chapter always ends.
 *
 * [lead] is the strongest key and the team's face: what the chapter translator sets on it (glossary, notes,
 * story so far, progress and retry callbacks) is given to every key, and what the keys find (new terms, their
 * kind and gender, the summary) is gathered back on it.
 */
class TeamTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val store: TeamStore,
    private val members: List<TeamStore.Member>,
    /** Whose work this is, for the router's priorities: the page being read, or a chapter. */
    private val lane: AiRouter.Lane = AiRouter.Lane.CHAPTER,
) : TextTranslator {

    private val leadMember = members.maxBy { store.strength(it.id) }

    /** The team's face for the chapter translator (see the class description); it never translates by itself. */
    val lead: AiTranslator = store.translatorFor(leadMember, fromLang, toLang)

    /** Called once the chapter is split, with the plan and how many calls run at a time. */
    var onPlan: ((ChapterPlanner.Plan, Int) -> Unit)? = null

    /** Which key is working on what right now (key id to the number of batches it holds). */
    var onWorking: ((Map<String, Int>) -> Unit)? = null

    /** MANHWA: pages whose batch is translated, so the reader can show them before the whole chapter is done. */
    var onPagesDone: ((Map<String, PageTranslation>) -> Unit)? = null

    /**
     * MANHWA: the lines translated last (source to translation), given to every new batch and page as context.
     * A chapter starts empty; while reading, it carries on from page to page.
     */
    @Volatile private var recent: List<kotlin.Pair<String, String>> = emptyList()

    /**
     * MANHWA: in a chapter, the lines of every finished page by its place in the chapter. A batch gets the lines of
     * the pages right BEFORE it as context, never those of later pages that happened to finish first.
     */
    private val pageLines = java.util.concurrent.ConcurrentHashMap<Int, List<kotlin.Pair<String, String>>>()

    private fun linesOf(page: PageTranslation): List<kotlin.Pair<String, String>> = page.blocks
        .filter { it.translation.isNotBlank() && it.text.isNotBlank() }
        .map { it.text to it.translation }

    private fun contextBefore(position: Int): List<kotlin.Pair<String, String>> =
        pageLines.filterKeys { it < position }.toSortedMap().values.flatten().takeLast(RECENT_LINES)

    private fun remember(translated: Collection<PageTranslation>) {
        val lines = translated.flatMap { page -> page.blocks }
            .filter { it.translation.isNotBlank() && it.text.isNotBlank() }
            .map { it.text to it.translation }
        if (lines.isNotEmpty()) recent = (recent + lines).takeLast(RECENT_LINES)
    }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) = translate(pages, emptyMap())

    /** True when some of the last pages had to be translated by Google (no key could do them). */
    @Volatile var fellBack = false
        private set

    override suspend fun translate(pages: MutableMap<String, PageTranslation>, images: Map<String, PageImage>) {
        fellBack = false
        learnt = emptyMap()
        if (lane != AiRouter.Lane.LIVE) {
            recent = emptyList()
            pageLines.clear()
        }
        if (pages.isEmpty()) return
        // MANHWA hybrid orchestra: the model on the phone and the keys play the chapter together
        if (lane != AiRouter.Lane.LIVE && !orchestrating && eu.kanade.translation.orchestra.OrchestraStore.active(store.appContext)) {
            orchestrating = true
            try {
                orchestrate(pages, images)
            } finally {
                orchestrating = false
            }
            return
        }
        // The page being read with more than one key: the keys race when the first one is slow
        if (lane == AiRouter.Lane.LIVE && members.size > 1) return translateRacing(pages, images)
        // Calls the keys can run at once now (they grow while the keys keep up, see AiRouter.report)
        val slots = members.sumOf { store.router.capacity(it.id) }
        val plan = ChapterPlanner.plan(
            pages.mapValues { (name, page) -> statsOf(page, name in images, lead.glossary.keys) },
            slots = slots,
        )
        onPlan?.invoke(plan, slots)
        val total = plan.batches.size
        if (total == 0) return

        val lock = Mutex()
        val queue = ArrayDeque(plan.batches)
        val attempts = HashMap<ChapterPlanner.Batch, Int>()
        val leftOver = mutableListOf<ChapterPlanner.Batch>()
        val summaries = sortedMapOf<Int, MutableList<String>>()
        val order = pages.keys.withIndex().associate { it.value to it.index }
        val working = HashMap<String, Int>()
        var inFlight = 0
        var done = 0

        /** The next batch for [member]: heavy ones for strong keys, image batches for keys that read images. */
        suspend fun take(member: TeamStore.Member, vision: Boolean): ChapterPlanner.Batch? {
            while (true) {
                val outcome: Any? = lock.withLock {
                    if (queue.isEmpty()) return@withLock if (inFlight == 0) STOP else WAIT
                    // Every key resting for a while and nothing in flight: no more waiting, what is left goes
                    // to the fallback (the phone's model or Google) so the chapter still ends translated
                    if (inFlight == 0 && allRestingLong()) {
                        leftOver += queue
                        queue.clear()
                        return@withLock STOP
                    }
                    if (store.health.value[member.id]?.resting == true) return@withLock WAIT
                    if (members.none { it.id == member.id } || store.active().none { it.id == member.id }) return@withLock STOP
                    // The router's permit, taken only when free: no batch is held by a worker that can't start it
                    // (the page being read goes before chapters on the same key)
                    if (!store.router.tryAcquire(member.id, lane)) return@withLock WAIT
                    val pick = queue.firstOrNull { !it.needsImage || vision } ?: queue.first()
                    queue.remove(pick)
                    inFlight++
                    working[member.id] = (working[member.id] ?: 0) + 1
                    onWorking?.invoke(working.toMap())
                    pick
                }
                when (outcome) {
                    STOP -> return null
                    WAIT -> {
                        // Waiting on a busy or resting key is still work in progress for the chapter
                        lead.onAlive?.invoke()
                        delay(WAIT_MS)
                    }
                    else -> return outcome as ChapterPlanner.Batch
                }
            }
        }

        coroutineScope {
            for (member in members) {
                val vision = store.analysis.value[member.id]?.vision ?: true
                // As many workers as a key could ever run; the router's permits decide how many work at once
                repeat(AiRouter.MAX_CAPACITY) {
                    launch {
                        while (true) {
                            val batch = take(member, vision) ?: break
                            // Built under the permit: if it can't be built, the permit and the batch go back
                            val translator = try {
                                store.translatorFor(member, fromLang, toLang, lane).also(::shareSettings)
                            } catch (e: Exception) {
                                if (e is CancellationException) {
                                    store.router.release(member.id, lane)
                                    throw e
                                }
                                logcat(LogPriority.WARN, e) { "Team key ${member.provider} could not start" }
                                store.router.release(member.id, lane)
                                lock.withLock {
                                    inFlight--
                                    working[member.id] = ((working[member.id] ?: 1) - 1).coerceAtLeast(0)
                                    queue.addFirst(batch)
                                }
                                break
                            }
                            if (lane != AiRouter.Lane.LIVE) translator.recentLines = contextBefore(batch.pages.minOf { order.getValue(it) })
                            val part = batch.pages.associateWithTo(mutableMapOf()) { pages.getValue(it) }
                            val partImages = if (vision) images.filterKeys { it in batch.pages } else emptyMap()
                            val started = System.currentTimeMillis()
                            var ok = false
                            try {
                                withTimeout(BATCH_TIMEOUT_MS) { translator.translate(part, partImages) }
                                ok = true
                                // Speed per weight point, so a light batch and a heavy one count the same
                                val perPoint = (System.currentTimeMillis() - started) / batch.weight.coerceAtLeast(1f)
                                store.reportSuccess(member.id, (perPoint * REFERENCE_POINTS).toLong())
                                store.router.report(member.id, null)
                            } catch (e: Exception) {
                                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                                logcat(LogPriority.WARN, e) { "Team key ${member.provider} failed a batch, handing it over" }
                                store.reportFailure(member.id, e, TeamStore.restFor(e))
                                store.router.report(member.id, e)
                                lead.onRetry?.invoke(e)
                            } finally {
                                translator.close()
                                store.router.release(member.id, lane)
                                store.router.count(lane)
                            }
                            lock.withLock {
                                inFlight--
                                working[member.id] = ((working[member.id] ?: 1) - 1).coerceAtLeast(0)
                                onWorking?.invoke(working.toMap())
                                if (ok) {
                                    gather(translator, summaries, batch.pages.minOf { order.getValue(it) })
                                    remember(part.values)
                                    part.forEach { (name, page) -> pageLines[order.getValue(name)] = linesOf(page) }
                                    onPagesDone?.invoke(part.toMap())
                                    done++
                                    lead.onBatchDone?.invoke(done, total)
                                } else {
                                    val tries = (attempts[batch] ?: 0) + 1
                                    attempts[batch] = tries
                                    if (tries < MAX_ATTEMPTS) queue.addFirst(batch) else leftOver += batch
                                }
                            }
                        }
                    }
                }
            }
        }

        // What no key could do (all of them failed or left): Google, so the chapter still ends translated
        val rest = leftOver + queue
        if (rest.isNotEmpty()) {
            fellBack = true
            // MANHWA: the on-device AI when the reader has one, else Google
            val google = fallbackTranslator()
            for (batch in rest) {
                runCatching { google.translate(batch.pages.associateWithTo(mutableMapOf()) { pages.getValue(it) }) }
                    .onFailure { if (it is CancellationException) throw it }
                done++
                lead.onBatchDone?.invoke(done, total)
            }
        }
        lead.summaries.clear()
        summaries.values.forEach { lead.summaries += it }
        // OpenRouter tells the credit left on a key: read again after the chapter
        members.forEach { store.usage.refreshOpenRouter(it) }
        // MANHWA quota meter: calls a whole chapter takes (the scout and the check included), for the forecast
        if (lane == AiRouter.Lane.CHAPTER && done > 0) members.forEach { store.keyHealth.chapterDone(it.id, total + 2) }
    }

    /**
     * MANHWA: the page being read, raced. The best key starts; if it has not answered within its usual time
     * ([raceDelay]), the next key gets the same page too, and the first good answer wins (the other call is
     * stopped). A key that fails hands over at once. If every key fails, Google translates the page.
     */
    private suspend fun translateRacing(pages: MutableMap<String, PageTranslation>, images: Map<String, PageImage>): Unit = coroutineScope {
        val health = store.health.value
        val active = store.active().map { it.id }.toSet()
        val order = members.filter { it.id in active }
            .sortedWith(compareBy<TeamStore.Member> { health[it.id]?.resting == true }.thenByDescending { store.strength(it.id) })

        class Outcome(val result: Map<String, PageTranslation>?, val translator: AiTranslator)

        val outcomes = Channel<Outcome>(Channel.UNLIMITED)
        val jobs = mutableListOf<Job>()
        var launched = 0
        var finished = 0

        fun launchNext(): Boolean {
            val member = order.getOrNull(launched) ?: return false
            launched++
            jobs += launch {
                val vision = store.analysis.value[member.id]?.vision ?: true
                store.router.acquire(member.id, lane)
                val translator = store.translatorFor(member, fromLang, toLang, lane).also(::shareSettings)
                // Every racer works on its own copy; the winner's copy is put on the page
                val copy = pages.mapValuesTo(mutableMapOf()) { (_, page) -> page.copy(blocks = page.blocks.map { it.copy() }.toMutableList()) }
                val outcome = try {
                    withTimeout(BATCH_TIMEOUT_MS) { translator.translate(copy, if (vision) images else emptyMap()) }
                    store.reportOk(member.id)
                    store.router.report(member.id, null)
                    Outcome(copy, translator)
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    logcat(LogPriority.WARN, e) { "Team key ${member.provider} failed the page, the next key takes it" }
                    store.reportFailure(member.id, e, TeamStore.restFor(e))
                    store.router.report(member.id, e)
                    Outcome(null, translator)
                } finally {
                    translator.close()
                    store.router.release(member.id, lane)
                    store.router.count(lane)
                }
                outcomes.send(outcome)
            }
            return true
        }

        var winner: Outcome? = null
        if (launchNext()) {
            while (winner == null) {
                val canRace = launched < MAX_RACERS && launched < order.size
                val next = when {
                    canRace -> withTimeoutOrNull(raceDelay(order[launched - 1])) { outcomes.receive() }
                    finished < launched -> outcomes.receive()
                    else -> break
                }
                if (next == null) {
                    // The key is slow: the next one races it
                    launchNext()
                    continue
                }
                finished++
                if (next.result != null) {
                    winner = next
                } else if (finished >= launched && !launchNext()) {
                    break
                }
            }
        }
        jobs.forEach { it.cancel() }
        val won = winner
        if (won?.result != null) {
            // In place: the caller keeps its own page objects
            won.result.forEach { (name, page) -> pages[name]?.blocks = page.blocks }
            won.translator.discoveredTerms.forEach { (source, translation) -> lead.discoveredTerms.putIfAbsent(source, translation) }
            won.translator.discoveredDetails.forEach { (source, info) -> lead.discoveredDetails.putIfAbsent(source, info) }
            remember(pages.values)
        } else {
            fellBack = true
            runCatching { fallbackTranslator().translate(pages) }.onFailure { if (it is CancellationException) throw it }
        }
        Unit
    }

    @Volatile private var orchestrating = false

    /**
     * MANHWA hybrid orchestra: the phone drafts every line at once (free); short plain lines keep the phone's
     * line, the rest goes to the keys with the phone's draft to improve (far fewer tokens than from zero); every
     * line a key rewrote is kept as an example the phone follows next time.
     */
    private suspend fun orchestrate(pages: MutableMap<String, PageTranslation>, images: Map<String, PageImage>) {
        val context = store.appContext
        // MANHWA phone ensemble: every model on the phone in its role (the quick one drafts, the strong one rereads)
        val lineup = eu.kanade.translation.local.PhoneEnsemble.lineup(context)
        val style = store.styleInstruction(toLang)
        val drafter = eu.kanade.translation.local.LocalAiTranslator(
            context,
            fromLang,
            toLang,
            instructions = style,
            glossary = lead.glossary,
            model = lineup?.drafter,
        )
        val blocks = pages.values.flatMap { it.blocks }.filter { it.text.isNotBlank() && it.translation.isBlank() }
        // The phone drafts within a time limit, telling the chapter it is still moving after every few lines;
        // what it has not drafted by then goes to the keys from the source. A phone too slow for one call
        // sits out for a while (the keys work alone, nobody waits on it).
        val heartbeat = { lead.onAlive?.invoke(); Unit }
        val drafts = runCatching {
            drafter.draftLines(blocks.map { it.text }, deadline = System.currentTimeMillis() + DRAFT_BUDGET_MS) { heartbeat() }
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        if (drafter.slow) eu.kanade.translation.orchestra.OrchestraStore.markSlow(context)
        if (drafts == null || drafts.all { it == null }) {
            // The phone could not play this time: the keys do it all as before
            translate(pages, images)
            return
        }
        val terms = lead.glossary.keys
        // By identity: a line's translation changes while it is in these (its hash would change too)
        val toTeam: MutableSet<TranslationBlock> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        val drafted = java.util.IdentityHashMap<TranslationBlock, String>()
        var local = 0
        blocks.forEachIndexed { i, block ->
            val draft = drafts.getOrNull(i)
            val route = eu.kanade.translation.orchestra.OrchestraRules.route(block.text, draft.orEmpty(), block.kind, terms, draft != null)
            if (route == eu.kanade.translation.orchestra.OrchestraRules.Route.LOCAL && draft != null) {
                block.translation = draft
                block.by = "local"
                local++
            } else {
                if (draft != null) {
                    block.translation = draft
                    drafted[block] = draft
                }
                toTeam += block
            }
        }
        // The lines the phone keeps are reread by the strong model while the keys work on the rest
        val kept = blocks.filter { it.by == "local" }
        val polish: suspend () -> Unit = polish@{
            val polisher = lineup?.takeIf { it.drafter != it.polisher }?.polisher ?: return@polish
            if (kept.isEmpty()) return@polish
            if (eu.kanade.translation.orchestra.OrchestraStore.slowNow(context)) return@polish
            val better = runCatching {
                eu.kanade.translation.local.LocalAiTranslator(context, fromLang, toLang, instructions = style, glossary = lead.glossary, model = polisher)
                    .polishLines(kept.map { it.text }, kept.map { it.translation }, deadline = System.currentTimeMillis() + POLISH_BUDGET_MS) { heartbeat() }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return@polish
            kept.forEachIndexed { i, b -> better.getOrNull(i)?.let { b.translation = it } }
        }
        kotlinx.coroutines.coroutineScope {
            val polishing = launch { polish() }
            orchestrateTeam(pages, images, toTeam, drafted)
            // The keys are done: the rereading gets a short while more, then the chapter goes on without it
            if (withTimeoutOrNull(POLISH_BUDGET_MS) { polishing.join() } == null) polishing.cancel()
        }
        eu.kanade.translation.orchestra.OrchestraStore.record(context, local = local, team = toTeam.size)
    }

    /** The keys' part of the orchestra: the lines the phone did not keep, with its drafts; the phone learns after. */
    private suspend fun orchestrateTeam(
        pages: MutableMap<String, PageTranslation>,
        images: Map<String, PageImage>,
        toTeam: MutableSet<TranslationBlock>,
        drafted: java.util.IdentityHashMap<TranslationBlock, String>,
    ) {
        val context = store.appContext
        if (toTeam.isNotEmpty()) {
            // Only the pages with lines left go to the keys, holding only those lines (the same objects)
            val rest = pages.mapNotNull { (name, page) ->
                val left = page.blocks.filter { it in toTeam }
                if (left.isEmpty()) null else name to page.copy(blocks = left.toMutableList())
            }.toMap(LinkedHashMap())
            val draftsBefore = lead.useDrafts
            lead.useDrafts = drafted.isNotEmpty() || draftsBefore
            try {
                translate(rest, images.filterKeys { it in rest })
            } finally {
                lead.useDrafts = draftsBefore
            }
            // The keys may have changed the pages' bubble lists (a watermark dropped, a missed bubble added):
            // carried back to the real pages, the phone's lines staying in their place
            rest.forEach { (name, copy) ->
                val page = pages[name] ?: return@forEach
                val kept: MutableSet<TranslationBlock> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
                kept += copy.blocks
                val before: MutableSet<TranslationBlock> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
                before += page.blocks
                page.blocks = (page.blocks.filter { it !in toTeam || it in kept } + copy.blocks.filter { it !in before }).toMutableList()
            }
            toTeam.forEach { if (it.by.isBlank()) it.by = if (fellBack) "fallback" else "team" }
            // The phone learns from what the keys changed
            val corrected = drafted.mapNotNull { (block, draft) ->
                val final = block.translation.trim()
                if (final.isBlank() || final == draft || fellBack) null else eu.kanade.translation.orchestra.OrchestraStore.Example(block.text, draft, final)
            }
            eu.kanade.translation.orchestra.OrchestraStore.learn(context, corrected)
        }
    }

    /** Every key of the team rests for longer than [LONG_REST_MS] (or is out): waiting for them is pointless. */
    private fun allRestingLong(): Boolean {
        val now = System.currentTimeMillis()
        val health = store.health.value
        val active = store.active().map { it.id }.toSet()
        return members.all { m -> m.id !in active || (health[m.id]?.restingUntil ?: 0L) - now > LONG_REST_MS }
    }

    /** What translates when no key can: the on-device AI (with the series glossary) when there is one, else Google. */
    private fun fallbackTranslator(): TextTranslator =
        if (eu.kanade.translation.local.LocalAiEngine.available(store.appContext)) {
            eu.kanade.translation.local.LocalAiTranslator(store.appContext, fromLang, toLang, glossary = lead.glossary)
                // The phone can be slow: it tells the chapter it is moving after every few lines
                .also { it.onBatchDone = { _, _ -> lead.onAlive?.invoke() } }
        } else {
            GoogleTranslator(fromLang, toLang)
        }

    /** How long the page waits on [member] before another key races it: its usual time, within 4–12 s. */
    private fun raceDelay(member: TeamStore.Member): Long {
        val usual = store.health.value[member.id]?.latencyMs ?: 0L
        return (usual * 3 / 2).coerceIn(RACE_MIN_MS, RACE_MAX_MS)
    }

    /** New names the keys found so far in this chapter (a copy, replaced under the team's lock). */
    @Volatile private var learnt: Map<String, String> = emptyMap()

    /** What the chapter translator set on [lead], given to a key's translator for one batch. */
    private fun shareSettings(translator: AiTranslator) {
        // The glossary plus the names other keys found in earlier batches, so every key writes them the same way
        translator.glossary = learnt + lead.glossary
        translator.learnTerms = lead.learnTerms
        translator.glossaryNotes = lead.glossaryNotes
        translator.richTerms = lead.richTerms
        translator.storySoFar = lead.storySoFar
        translator.lessons = lead.lessons
        translator.recentLines = recent
        translator.fitHints = lead.fitHints
        translator.askSummary = lead.askSummary
        translator.useDrafts = lead.useDrafts
        translator.skipSfx = lead.skipSfx
        // With other keys around, a busy key hands its batch over at once instead of waiting
        translator.fastFail = members.size > 1
    }

    /** What a key found while translating a batch, gathered on [lead] (called under the team's lock). */
    private fun gather(translator: AiTranslator, summaries: MutableMap<Int, MutableList<String>>, position: Int) {
        translator.discoveredTerms.forEach { (source, translation) -> lead.discoveredTerms.putIfAbsent(source, translation) }
        learnt = LinkedHashMap(lead.discoveredTerms)
        translator.discoveredDetails.forEach { (source, info) -> lead.discoveredDetails.putIfAbsent(source, info) }
        if (translator.summaries.isNotEmpty()) summaries.getOrPut(position) { mutableListOf() } += translator.summaries
    }

    /**
     * MANHWA: runs the editor's review with the team's strongest key and its strongest model (a key that fails
     * hands the review to the next one), with what the chapter translator set on [lead].
     */
    suspend fun <T> asEditor(block: suspend (AiTranslator) -> T): T =
        store.router.ask(AiRouter.Lane.REVIEW, fromLang, toLang) { editor ->
            editor.glossary = lead.glossary
            editor.glossaryNotes = lead.glossaryNotes
            editor.storySoFar = lead.storySoFar
            editor.lessons = lead.lessons
            block(editor)
        }

    /**
     * MANHWA: bubbles the OCR is not sure of, read again from their own image by a key that sees images. Returns id to
     * the corrected source text; empty when no key can read images or the call failed (the OCR reading stays).
     */
    suspend fun reread(crops: List<kotlin.Pair<String, ByteArray>>): Map<String, String> {
        if (crops.isEmpty()) return emptyMap()
        return runCatching {
            store.router.ask(
                AiRouter.Lane.CHAPTER,
                fromLang,
                toLang,
                prefer = { store.analysis.value[it.id]?.vision == true },
            ) { reader -> reader.reread(crops) }
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
    }

    override fun close() {
        lead.close()
    }

    companion object {
        private val STOP = Any()
        private val WAIT = Any()
        private const val WAIT_MS = 150L

        /** How long the phone may draft a chapter before the keys take the rest from the source. */
        private const val DRAFT_BUDGET_MS = 45_000L

        /** How long the strong phone model may reread the lines the phone keeps. */
        private const val POLISH_BUDGET_MS = 30_000L

        /** Keys resting longer than this are not waited for: the fallback takes what is left. */
        private const val LONG_REST_MS = 45_000L
        private const val MAX_ATTEMPTS = 3
        private const val MAX_RACERS = 2
        private const val RECENT_LINES = 30
        private const val RACE_MIN_MS = 4_000L
        private const val RACE_MAX_MS = 12_000L
        private const val BATCH_TIMEOUT_MS = 150_000L
        private const val REFERENCE_POINTS = 8f

        private val wordPattern = Regex("\\b[A-Z][a-zA-Z'-]{2,}")

        /** What a page holds, for the plan: bubbles, text, sounds, names the glossary does not know, image. */
        fun statsOf(page: PageTranslation, needsImage: Boolean, known: Set<String>): ChapterPlanner.PageStats {
            val texts = page.blocks.map { it.text }
            val sounds = page.blocks.count { BubbleKind.parse(it.kind) == BubbleKind.SFX }
            val names = texts.asSequence()
                .flatMap { text -> wordPattern.findAll(text).map { it.value }.drop(1) }
                .filter { word -> known.none { it.equals(word, ignoreCase = true) } }
                .distinct()
                .count()
            return ChapterPlanner.PageStats(
                bubbles = page.blocks.size,
                characters = texts.sumOf { it.length },
                sounds = sounds,
                newNames = names,
                needsImage = needsImage,
            )
        }
    }
}
