package eu.kanade.translation.translator

import eu.kanade.translation.data.PageImage
import eu.kanade.translation.model.PageTranslation
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
                    WAIT -> delay(WAIT_MS)
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
                            val translator = store.translatorFor(member, fromLang, toLang, lane).also(::shareSettings)
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

    /** What translates when no key can: the on-device AI (with the series glossary) when there is one, else Google. */
    private fun fallbackTranslator(): TextTranslator =
        if (eu.kanade.translation.local.LocalAiEngine.available(store.appContext)) {
            eu.kanade.translation.local.LocalAiTranslator(store.appContext, fromLang, toLang, glossary = lead.glossary)
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
