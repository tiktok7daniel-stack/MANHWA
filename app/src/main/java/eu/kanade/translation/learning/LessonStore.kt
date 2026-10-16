package eu.kanade.translation.learning

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.translation.collection.TranslationCollection
import eu.kanade.translation.glossary.GlossaryStore
import eu.kanade.translation.translator.AiTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * MANHWA: what the translation team learnt from its mistakes.
 *
 * Every correction is a lesson (the source, the wrong translation, the right one, who corrected it): the
 * editor's fixes, the reader's own corrections in the collection, and the reader's glossary edits. The latest
 * lessons go with every chapter translation as examples to follow, and every [COMPRESS_EVERY] new lessons the
 * team's lead turns them into a few short rules ("Hunter is صيّاد, never هنتر"). Rules are kept per manhwa and
 * for the whole app; the reader sees, edits and removes them. The reader's corrections always count first.
 */
@Inject
@SingleIn(AppScope::class)
class LessonStore(
    private val context: Context,
    private val glossaryStore: GlossaryStore,
    private val collection: TranslationCollection,
) {

    enum class Origin { USER, EDITOR }

    @Serializable
    data class Lesson(
        val source: String,
        val wrong: String,
        val right: String,
        val origin: String,
        val at: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class Book(
        val lessons: List<Lesson> = emptyList(),
        val rules: List<String> = emptyList(),
        /** Lessons added since the rules were last written. */
        val fresh: Int = 0,
        /** The manhwa's title, for the screen. */
        val title: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Goes up on every change, so the screen reloads. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        // A glossary edit by the reader is a lesson, and fixes the term in every saved line of the collection
        glossaryStore.edits
            .onEach { edit ->
                add(edit.mangaId, Lesson(edit.source, edit.oldTranslation, edit.newTranslation, Origin.USER.name))
                collection.replaceTerm(edit.source, edit.oldTranslation, edit.newTranslation)
            }
            .launchIn(scope)
    }

    private fun file(mangaId: Long) =
        File(File(context.filesDir, "lessons").apply { mkdirs() }, if (mangaId == GLOBAL) "global.json" else "$mangaId.json")

    @Synchronized
    fun book(mangaId: Long): Book = runCatching {
        file(mangaId).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<Book>(it) }
    }.onFailure { logcat(LogPriority.WARN, it) }.getOrNull() ?: Book()

    @Synchronized
    private fun write(mangaId: Long, book: Book) {
        runCatching { file(mangaId).writeText(json.encodeToString(book)) }.onFailure { logcat(LogPriority.ERROR, it) }
        _revision.value++
    }

    /** Keeps [lesson] for [mangaId] ([GLOBAL] for the whole app); the oldest go once there are too many. */
    @Synchronized
    fun add(mangaId: Long, lesson: Lesson, title: String = "") {
        if (lesson.right.isBlank() || lesson.right.trim() == lesson.wrong.trim()) return
        val book = book(mangaId)
        val lessons = (book.lessons.filterNot { it.source == lesson.source && it.origin == lesson.origin } + lesson).takeLast(MAX_LESSONS)
        write(mangaId, book.copy(lessons = lessons, fresh = book.fresh + 1, title = title.ifBlank { book.title }))
    }

    /** Every book of lessons there is: the app's, then each manhwa's (id to book). */
    fun books(): List<kotlin.Pair<Long, Book>> {
        val dir = File(context.filesDir, "lessons")
        val ids = dir.listFiles().orEmpty().mapNotNull { f ->
            when {
                f.name == "global.json" -> GLOBAL
                else -> f.nameWithoutExtension.toLongOrNull()
            }
        }
        return (listOf(GLOBAL) + ids.filter { it != GLOBAL }).distinct().map { it to book(it) }
    }

    /** Adds a backup's lessons and rules to [mangaId]'s (nothing here is removed). */
    @Synchronized
    fun mergeBook(mangaId: Long, other: Book, title: String) {
        if (other.lessons.isEmpty() && other.rules.isEmpty()) return
        val book = book(mangaId)
        val lessons = (book.lessons + other.lessons.filter { o -> book.lessons.none { it.source == o.source && it.origin == o.origin } })
            .sortedBy { it.at }
            .takeLast(MAX_LESSONS)
        write(mangaId, book.copy(lessons = lessons, rules = (book.rules + other.rules).distinct().take(MAX_RULES), title = book.title.ifBlank { title }))
    }

    fun setRules(mangaId: Long, rules: List<String>) {
        write(mangaId, book(mangaId).copy(rules = rules.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_RULES)))
    }

    fun removeLesson(mangaId: Long, lesson: Lesson) {
        val book = book(mangaId)
        write(mangaId, book.copy(lessons = book.lessons - lesson))
    }

    /**
     * What goes with a chapter's translation: the rules (this manhwa's, then the app's) and the latest
     * corrections as examples, the reader's first. Empty when nothing was learnt yet.
     */
    fun forPrompt(mangaId: Long): String {
        val own = book(mangaId)
        val global = book(GLOBAL)
        val rules = (own.rules + global.rules).distinct().take(MAX_RULES)
        val examples = (own.lessons + global.lessons)
            .sortedWith(compareByDescending<Lesson> { it.origin == Origin.USER.name }.thenByDescending { it.at })
            .take(MAX_EXAMPLES)
        if (rules.isEmpty() && examples.isEmpty()) return ""
        countApplied()
        return buildString {
            rules.forEach { appendLine("- $it") }
            examples.forEach { appendLine("- \"${it.source.take(120)}\": not \"${it.wrong.take(120)}\" but \"${it.right.take(120)}\"") }
        }.trim()
    }

    /**
     * Every [COMPRESS_EVERY] new lessons, the team's lead turns them (with the rules so far) into a few short
     * rules. One small call; a failure keeps the rules as they were.
     */
    suspend fun compressIfDue(mangaId: Long, ai: AiTranslator) {
        val book = book(mangaId)
        if (book.fresh < COMPRESS_EVERY || book.lessons.isEmpty()) return
        compress(mangaId, ai)
    }

    /** MANHWA stats for the lessons screen: what was learnt, of which kind, and how often it was used. */
    data class Stats(
        val books: Int,
        val rules: Int,
        val lessons: Int,
        val fromReader: Int,
        val fromEditor: Int,
        val names: Int,
        val gender: Int,
        val style: Int,
        val terms: Int,
        val grammar: Int,
        val applied: Long,
    )

    fun stats(): Stats {
        val all = books().map { it.second }
        val rules = all.flatMap { it.rules }
        val lessons = all.flatMap { it.lessons }
        fun count(vararg words: String) = rules.count { rule -> words.any { rule.contains(it, ignoreCase = true) } }
        val names = count("name", "اسم", "أسماء")
        val gender = count("gender", "male", "female", "مذكر", "مؤنث", "جنس", "تأنيث", "تذكير")
        val style = count("tone", "style", "voice", "formal", "casual", "أسلوب", "نبرة", "لهجة", "رسمي")
        val terms = count("term", "skill", "title", "مصطلح", "لقب", "مهارة")
        return Stats(
            books = all.count { it.rules.isNotEmpty() || it.lessons.isNotEmpty() },
            rules = rules.size,
            lessons = lessons.size,
            fromReader = lessons.count { it.origin == Origin.USER.name },
            fromEditor = lessons.count { it.origin == Origin.EDITOR.name },
            names = names,
            gender = gender,
            style = style,
            terms = terms,
            grammar = (rules.size - names - gender - style - terms).coerceAtLeast(0),
            applied = statsPrefs.getLong(KEY_APPLIED, 0L),
        )
    }

    private val statsPrefs by lazy { context.getSharedPreferences("manhwa_lessons_stats", Context.MODE_PRIVATE) }

    /** Counts a chapter or page that was translated with the lessons in its prompt. */
    private fun countApplied() {
        statsPrefs.edit().putLong(KEY_APPLIED, statsPrefs.getLong(KEY_APPLIED, 0L) + 1).apply()
    }

    /**
     * MANHWA: turns every book's corrections into rules now, not only every [COMPRESS_EVERY] corrections, with
     * the team's strongest key. Returns how many books got new rules.
     */
    suspend fun extractAll(): Int {
        var updated = 0
        books().filter { (_, book) -> book.lessons.isNotEmpty() }.forEach { (id, before) ->
            runCatching { eu.kanade.translation.translator.appAsk(eu.kanade.translation.team.AiRouter.Lane.REVIEW) { ai -> compress(id, ai) } }
            if (book(id).rules != before.rules) updated++
        }
        _revision.value = _revision.value + 1
        return updated
    }

    private suspend fun compress(mangaId: Long, ai: AiTranslator) {
        val book = book(mangaId)
        if (book.lessons.isEmpty()) return
        runCatching {
            val prompt = buildString {
                appendLine("These corrections were made to comic translations into ${ai.toLang.label}.")
                appendLine("Write at most $MAX_RULES short, general rules a translator must follow from now on")
                appendLine("(names, terms, gender agreement, tone, wording). Keep the useful old rules. Reply as {\"rules\": [\"...\"]}.")
                if (book.rules.isNotEmpty()) {
                    appendLine("Old rules:")
                    book.rules.forEach { appendLine("- $it") }
                }
                appendLine("Corrections:")
                book.lessons.takeLast(COMPRESS_INPUT).forEach {
                    appendLine("- \"${it.source.take(150)}\": \"${it.wrong.take(150)}\" -> \"${it.right.take(150)}\"")
                }
            }
            val reply = ai.askJson("You turn translation corrections into short rules. Reply with JSON only.", prompt)
            val rules = (reply["rules"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(160) }
            if (rules.isNotEmpty()) write(mangaId, book(mangaId).copy(rules = rules.take(MAX_RULES), fresh = 0))
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            logcat(LogPriority.WARN, it) { "Could not write the rules" }
        }
    }

    companion object {
        /** The book of lessons for the whole app (corrections in the collection). */
        const val GLOBAL = 0L
        private const val MAX_LESSONS = 80
        private const val MAX_RULES = 10
        private const val MAX_EXAMPLES = 8
        private const val COMPRESS_EVERY = 10
        private const val COMPRESS_INPUT = 40
        private const val KEY_APPLIED = "applied"
    }
}
