package eu.kanade.translation.translator

import eu.kanade.translation.data.PageImage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.style.BubbleKind
import eu.kanade.translation.team.ChapterPlanner
import eu.kanade.translation.team.TeamStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
) : TextTranslator {

    private val leadMember = members.maxBy { store.strength(it.id) }

    /** The team's face for the chapter translator (see the class description); it never translates by itself. */
    val lead: AiTranslator = store.translatorFor(leadMember, fromLang, toLang)

    /** Called once the chapter is split, with the plan and how many calls run at a time. */
    var onPlan: ((ChapterPlanner.Plan, Int) -> Unit)? = null

    /** Which key is working on what right now (key id to the number of batches it holds). */
    var onWorking: ((Map<String, Int>) -> Unit)? = null

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) = translate(pages, emptyMap())

    override suspend fun translate(pages: MutableMap<String, PageTranslation>, images: Map<String, PageImage>) {
        if (pages.isEmpty()) return
        val slots = members.associateWith { slotsFor(it) }
        val plan = ChapterPlanner.plan(
            pages.mapValues { (name, page) -> statsOf(page, name in images, lead.glossary.keys) },
            slots = slots.values.sum(),
        )
        onPlan?.invoke(plan, slots.values.sum())
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
            for ((member, count) in slots) {
                val vision = store.analysis.value[member.id]?.vision ?: true
                repeat(count) {
                    launch {
                        while (true) {
                            val batch = take(member, vision) ?: break
                            val translator = store.translatorFor(member, fromLang, toLang).also(::shareSettings)
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
                            } catch (e: Exception) {
                                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                                logcat(LogPriority.WARN, e) { "Team key ${member.provider} failed a batch, handing it over" }
                                store.reportFailure(member.id, e, restFor(e))
                                lead.onRetry?.invoke(e)
                            } finally {
                                translator.close()
                            }
                            lock.withLock {
                                inFlight--
                                working[member.id] = ((working[member.id] ?: 1) - 1).coerceAtLeast(0)
                                onWorking?.invoke(working.toMap())
                                if (ok) {
                                    gather(translator, summaries, batch.pages.minOf { order.getValue(it) })
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
            val google = GoogleTranslator(fromLang, toLang)
            for (batch in rest) {
                runCatching { google.translate(batch.pages.associateWithTo(mutableMapOf()) { pages.getValue(it) }) }
                    .onFailure { if (it is CancellationException) throw it }
                done++
                lead.onBatchDone?.invoke(done, total)
            }
        }
        lead.summaries.clear()
        summaries.values.forEach { lead.summaries += it }
    }

    /** What the chapter translator set on [lead], given to a key's translator for one batch. */
    private fun shareSettings(translator: AiTranslator) {
        translator.glossary = lead.glossary
        translator.learnTerms = lead.learnTerms
        translator.glossaryNotes = lead.glossaryNotes
        translator.richTerms = lead.richTerms
        translator.storySoFar = lead.storySoFar
        translator.askSummary = lead.askSummary
        translator.useDrafts = lead.useDrafts
        // With other keys around, a busy key hands its batch over at once instead of waiting
        translator.fastFail = members.size > 1
    }

    /** What a key found while translating a batch, gathered on [lead] (called under the team's lock). */
    private fun gather(translator: AiTranslator, summaries: MutableMap<Int, MutableList<String>>, position: Int) {
        translator.discoveredTerms.forEach { (source, translation) -> lead.discoveredTerms.putIfAbsent(source, translation) }
        translator.discoveredDetails.forEach { (source, info) -> lead.discoveredDetails.putIfAbsent(source, info) }
        if (translator.summaries.isNotEmpty()) summaries.getOrPut(position) { mutableListOf() } += translator.summaries
    }

    /** Calls a key may run at once: strong keys 3, average 2, weak 1; a lone key at most 2, to spare its quota. */
    private fun slotsFor(member: TeamStore.Member): Int {
        val strength = store.strength(member.id)
        val slots = when {
            strength >= STRONG -> 3
            strength >= AVERAGE -> 2
            else -> 1
        }
        return if (members.size == 1) slots.coerceAtMost(2) else slots
    }

    /** How long a key rests after [error]: busy or out of quota rests longer than a glitch. */
    private fun restFor(error: Throwable): Long = when {
        error is AiTranslator.ApiException && error.code == 429 -> 60_000L
        error is AiTranslator.ApiException && error.code in 500..504 -> 20_000L
        error is TimeoutCancellationException -> 20_000L
        error is java.io.IOException -> 10_000L
        else -> 0L
    }

    override fun close() {
        lead.close()
    }

    companion object {
        private val STOP = Any()
        private val WAIT = Any()
        private const val WAIT_MS = 400L
        private const val MAX_ATTEMPTS = 3
        private const val BATCH_TIMEOUT_MS = 150_000L
        private const val REFERENCE_POINTS = 8f
        private const val STRONG = 70
        private const val AVERAGE = 40

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
