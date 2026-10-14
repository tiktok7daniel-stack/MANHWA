package eu.kanade.translation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.TextTranslators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.source.local.isLocal

/**
 * MANHWA AI: a chat that understands what series the reader means, searches every enabled source for it,
 * opens each match's chapter list and ranks the sources by how far their translation has got.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AiChatViewModel(
    private val translationPreferences: TranslationPreferences,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
) : ViewModel() {

    /** One source that has the series, with how far it is translated there. */
    data class SourceHit(
        val manga: Manga,
        val sourceName: String,
        val sourceLang: String,
        val latestChapter: Float?,
        val chapterCount: Int,
        val lastUpload: Long,
    )

    sealed interface Message {
        data class User(val text: String) : Message
        data class Assistant(
            val text: String = "",
            val status: Status? = null,
            val hits: List<SourceHit> = emptyList(),
            val error: String? = null,
            val searched: Boolean = false,
        ) : Message
    }

    enum class Status { THINKING, SEARCHING, CHECKING_CHAPTERS }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val history = ArrayDeque<String>()

    fun send(text: String) {
        val request = text.trim()
        if (request.isEmpty() || _busy.value) return
        _busy.value = true
        _messages.update { it + Message.User(request) + Message.Assistant(status = Status.THINKING) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (reply, queries) = understand(request)
                updateAssistant { it.copy(text = reply, status = if (queries.isEmpty()) null else Status.SEARCHING) }
                if (queries.isNotEmpty()) {
                    val hits = findInSources(queries) { updateAssistant { a -> a.copy(status = it) } }
                    updateAssistant { it.copy(status = null, hits = hits, searched = true) }
                }
                remember("Reader: $request", "Assistant: $reply")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                updateAssistant {
                    it.copy(status = null, error = if (e is AiSearchAssistant.NotConfiguredException) NOT_CONFIGURED else e.message)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private fun updateAssistant(transform: (Message.Assistant) -> Message.Assistant) {
        _messages.update { list ->
            val last = list.lastOrNull() as? Message.Assistant ?: return@update list
            list.dropLast(1) + transform(last)
        }
    }

    private fun remember(vararg lines: String) {
        lines.forEach { history.addLast(it.take(600)) }
        while (history.size > 12) history.removeFirst()
    }

    /** Asks the AI what the reader means: a short answer in their language plus titles to search for. */
    private suspend fun understand(request: String): Pair<String, List<String>> {
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        val keyMissing = translationPreferences.translationEngineApiKey().get().isBlank() &&
            engine != TextTranslators.OPENAI
        if (!engine.isAi || keyMissing) throw AiSearchAssistant.NotConfiguredException()
        val ai = engine.build(translationPreferences) as AiTranslator
        try {
            val prompt = buildString {
                if (history.isNotEmpty()) {
                    appendLine("Conversation so far:")
                    history.forEach { appendLine(it) }
                    appendLine()
                }
                append("Reader: ").append(request)
            }
            val json = ai.askJson(SYSTEM, prompt)
            val reply = (json["reply"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val queries = (json["queries"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(4)
            return reply to queries
        } finally {
            ai.close()
        }
    }

    private suspend fun findInSources(queries: List<String>, onStatus: (Status) -> Unit): List<SourceHit> {
        val enabledLanguages = sourcePreferences.enabledLanguages.get()
        val disabledSources = sourcePreferences.disabledSources.get()
        val sources = sourceManager.getAll()
            .filter { !it.isLocal() && it.lang in enabledLanguages && "${it.id}" !in disabledSources }
        val wanted = queries.map(::normalize).filter { it.length >= 2 }
        val gate = Semaphore(PARALLEL_SOURCES)

        val matches = coroutineScope {
            sources.map { source -> async { gate.withPermit { searchSource(source, queries, wanted) } } }.awaitAll()
        }.filterNotNull()

        onStatus(Status.CHECKING_CHAPTERS)
        val hits = coroutineScope {
            matches.map { (source, manga) -> async { gate.withPermit { checkChapters(source, manga) } } }.awaitAll()
        }
        return hits.sortedWith(
            compareByDescending<SourceHit> { it.latestChapter ?: -1f }
                .thenByDescending { it.lastUpload }
                .thenByDescending { it.chapterCount },
        )
    }

    private suspend fun searchSource(source: Source, queries: List<String>, wanted: List<String>): Pair<Source, Manga>? {
        for (query in queries.take(2)) {
            val page = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                runCatching { source.getSearchManga(1, query, source.getFilterList()) }.getOrNull()
            } ?: continue
            val match = page.mangas.firstOrNull { manga ->
                val title = normalize(manga.title)
                wanted.any { it == title || (it.length >= 4 && (title.contains(it) || it.contains(title))) }
            } ?: continue
            val local = networkToLocalManga(listOf(match.toDomainManga(source.id))).firstOrNull() ?: continue
            return source to local
        }
        return null
    }

    private suspend fun checkChapters(source: Source, manga: Manga): SourceHit {
        val chapters = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            runCatching {
                source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
            }.getOrNull()
        }.orEmpty()
        return SourceHit(
            manga = manga,
            sourceName = source.name,
            sourceLang = source.lang,
            latestChapter = chapters.maxOfOrNull { it.chapter_number }?.takeIf { it >= 0 },
            chapterCount = chapters.size,
            lastUpload = chapters.maxOfOrNull { it.date_upload } ?: 0L,
        )
    }

    private fun normalize(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        const val NOT_CONFIGURED = "not_configured"
        private const val PARALLEL_SOURCES = 6
        private const val SOURCE_TIMEOUT_MS = 25_000L

        private val SYSTEM = """
            You are MANHWA AI, an expert on manga, manhwa, manhua and webtoons: titles in every language,
            authors, plots, genres, status and what readers usually mean by nicknames or vague descriptions.
            The reader may ask in any language; always reply in the reader's language.
            Reply with ONLY this JSON:
            {"reply": "...", "queries": ["...", "..."]}
            - "reply": 1 to 3 short sentences. Name the series you think they mean (with its other titles if useful)
              and one line about it. If you are unsure, say so briefly and give the likeliest candidates.
              If they ask for recommendations, recommend and explain in one line each.
            - "queries": up to 4 search strings to look the series up on manga sites, best first: the most common
              English or romanized title, the official English title, the native title. For recommendations, the
              title of the top pick. Use [] if the message is not about finding a series.
            Do not invent chapter counts or sources; the app checks those itself.
        """.trimIndent()
    }
}
