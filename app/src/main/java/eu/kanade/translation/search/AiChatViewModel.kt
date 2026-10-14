package eu.kanade.translation.search

import android.content.Context
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.translation.TranslationManager
import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.at.ATMR
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
    private val context: Context,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val setMangaCategories: SetMangaCategories,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val downloadManager: DownloadManager,
    private val translationManager: TranslationManager,
    private val getUpdates: GetUpdates,
) : ViewModel() {

    /** One source that has the series, with how far it is translated there. */
    data class SourceHit(
        val manga: Manga,
        val sourceName: String,
        val sourceLang: String,
        val latestChapter: Float?,
        val chapterCount: Int,
        val lastUpload: Long,
        val source: Source,
        val rawChapters: List<SChapter>,
    )

    sealed interface Message {
        data class User(val text: String) : Message
        data class Assistant(
            val text: String = "",
            val status: Status? = null,
            val hits: List<SourceHit> = emptyList(),
            val error: String? = null,
            val searched: Boolean = false,
            val done: List<String> = emptyList(),
            val updates: List<UpdatesWithRelations> = emptyList(),
            val sites: List<WebSite> = emptyList(),
            val webSummary: String? = null,
        ) : Message
    }

    /** A website found on the web that publishes the series. */
    data class WebSite(val name: String, val url: String, val latest: String?)

    enum class Status { THINKING, SEARCHING, CHECKING_CHAPTERS, SEARCHING_WEB }

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
                val (reply, queries, actions) = understand(request)
                updateAssistant { it.copy(text = reply, status = if (queries.isEmpty()) null else Status.SEARCHING) }
                var hits = emptyList<SourceHit>()
                if (queries.isNotEmpty()) {
                    hits = findInSources(queries) { updateAssistant { a -> a.copy(status = it) } }
                    updateAssistant { it.copy(status = null, hits = hits, searched = true) }
                }
                val wantsWeb = actions.any { (it["type"] as? JsonPrimitive)?.contentOrNull == "web_search" }
                if (queries.isNotEmpty() && (hits.isEmpty() || wantsWeb)) {
                    updateAssistant { it.copy(status = Status.SEARCHING_WEB) }
                    val (summary, sites) = searchWeb(request, queries)
                    updateAssistant { it.copy(status = null, webSummary = summary, sites = sites) }
                }
                if (actions.isNotEmpty()) {
                    val (done, updates) = runActions(actions, hits.firstOrNull())
                    updateAssistant { it.copy(status = null, done = done, updates = updates) }
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

    private fun buildAi(): AiTranslator {
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        val keyMissing = translationPreferences.translationEngineApiKey().get().isBlank() &&
            engine != TextTranslators.OPENAI
        if (!engine.isAi || keyMissing) throw AiSearchAssistant.NotConfiguredException()
        return engine.build(translationPreferences) as AiTranslator
    }

    /** Looks on the web for the sites that publish the series, when the reader's sources don't have it. */
    private suspend fun searchWeb(request: String, queries: List<String>): Pair<String?, List<WebSite>> {
        val ai = buildAi()
        try {
            if (!ai.canSearchWeb) return context.stringResource(ATMR.strings.ai_web_unavailable) to emptyList()
            val prompt = "Reader's request: $request\nSeries titles: ${queries.joinToString(" / ")}"
            val json = ai.askJsonWithWeb(WEB_SYSTEM, prompt)
            val summary = (json["summary"] as? JsonPrimitive)?.contentOrNull
            val sites = (json["sites"] as? JsonArray).orEmpty().mapNotNull { element ->
                val site = element as? JsonObject ?: return@mapNotNull null
                val url = site.str("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                WebSite(name = site.str("name") ?: url, url = url, latest = site.str("latest"))
            }.take(8)
            return summary to sites
        } finally {
            ai.close()
        }
    }

    /** Asks the AI what the reader means: a short answer in their language plus titles to search for. */
    private suspend fun understand(request: String): Triple<String, List<String>, List<JsonObject>> {
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
            val actions = (json["actions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            return Triple(reply, queries, actions)
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
            source = source,
            rawChapters = chapters,
        )
    }

    /** Carries out what the reader asked for, on the best match when the action is about a series. */
    private suspend fun runActions(
        actions: List<JsonObject>,
        best: SourceHit?,
    ): Pair<List<String>, List<UpdatesWithRelations>> {
        val done = mutableListOf<String>()
        var updates = emptyList<UpdatesWithRelations>()
        for (action in actions) {
            try {
                when (action.str("type")) {
                    "create_category" -> action.str("name")?.let { name ->
                        ensureCategory(name)
                        done += context.stringResource(ATMR.strings.ai_done_category, name)
                    }
                    "add_to_library" -> {
                        val hit = best ?: continue
                        syncChapters(hit)
                        updateManga.awaitUpdateFavorite(hit.manga.id, true)
                        val category = action.str("category")
                        if (category != null) {
                            setMangaCategories.await(hit.manga.id, listOf(ensureCategory(category).id))
                            done += context.stringResource(ATMR.strings.ai_done_library_category, hit.manga.title, category)
                        } else {
                            done += context.stringResource(ATMR.strings.ai_done_library, hit.manga.title)
                        }
                    }
                    "download", "translate" -> {
                        val hit = best ?: continue
                        val translate = action.str("type") == "translate"
                        syncChapters(hit)
                        val from = action.num("from") ?: 0.0
                        val to = action.num("to") ?: Double.MAX_VALUE
                        val chapters = getChaptersByMangaId.await(hit.manga.id)
                            .filter { it.chapterNumber in from..to }
                            .sortedBy { it.chapterNumber }
                            .take(MAX_CHAPTERS)
                        val (downloaded, missing) = chapters.partition {
                            downloadManager.isChapterDownloaded(it.name, it.scanlator, it.url, hit.manga.title, hit.manga.source)
                        }
                        if (translate) {
                            downloaded.forEach { translationManager.translateChapter(hit.manga, it) }
                            missing.forEach { translationManager.translateAfterDownload += it.id }
                        }
                        if (missing.isNotEmpty()) downloadManager.downloadChapters(hit.manga, missing)
                        done += context.stringResource(
                            if (translate) ATMR.strings.ai_done_translate else ATMR.strings.ai_done_download,
                            chapters.size,
                            hit.manga.title,
                        )
                    }
                    "new_chapters" -> {
                        val days = (action.num("days") ?: 7.0).toLong().coerceIn(1, 60)
                        updates = getUpdates.await(read = false, after = Clock.System.now() - days.days).take(40)
                        done += context.stringResource(ATMR.strings.ai_done_updates, updates.size, days)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                done += context.stringResource(ATMR.strings.ai_search_failed, e.message.orEmpty())
            }
        }
        return done to updates
    }

    private suspend fun syncChapters(hit: SourceHit) {
        if (hit.rawChapters.isNotEmpty()) syncChaptersWithSource.await(hit.rawChapters, hit.manga, hit.source)
    }

    private suspend fun ensureCategory(name: String): Category {
        getCategories.await().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        createCategoryWithName.await(name)
        return getCategories.await().first { it.name.equals(name, ignoreCase = true) }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun normalize(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        private val WEB_SYSTEM = """
            Search the web to find which websites publish the comic the reader is looking for (official
            platforms first, e.g. Webtoon, Tapas, Kakao, Naver, then reader sites), and how far each one is.
            Reply in the reader's language with ONLY this JSON:
            {"summary": "one or two sentences", "sites": [{"name": "...", "url": "https://...", "latest": "Chapter 120"}]}
            Only include sites you actually found, with working URLs; use [] if none.
        """.trimIndent()

        const val NOT_CONFIGURED = "not_configured"
        private const val PARALLEL_SOURCES = 6
        private const val SOURCE_TIMEOUT_MS = 25_000L
        private const val MAX_CHAPTERS = 200

        private val SYSTEM = """
            You are MANHWA AI, an expert on manga, manhwa, manhua and webtoons: titles in every language,
            authors, plots, genres, status and what readers usually mean by nicknames or vague descriptions.
            The reader may ask in any language; always reply in the reader's language.
            Reply with ONLY this JSON:
            {"reply": "...", "queries": ["...", "..."], "actions": []}
            - "reply": 1 to 3 short sentences. Name the series you think they mean (with its other titles if useful)
              and one line about it. If you are unsure, say so briefly and give the likeliest candidates.
              If they ask for recommendations, recommend and explain in one line each.
            - "queries": up to 4 search strings to look the series up on manga sites, best first: the most common
              English or romanized title, the official English title, the native title. For recommendations, the
              title of the top pick. Use [] if the message is not about finding a series.
            - "actions": what the reader asks the app to do, carried out on the series you found (the app picks
              the source with the latest chapter). Use only these objects:
              {"type": "add_to_library", "category": "<name or omit>"}  add it to the library, optionally in a category
              {"type": "create_category", "name": "<name>"}
              {"type": "download", "from": <first chapter number>, "to": <last chapter number>}
              {"type": "translate", "from": <n>, "to": <n>}  download (if needed) and translate those chapters
              {"type": "new_chapters", "days": <n>}  show new unread chapters of the library
              {"type": "web_search"}  also look on the web for the sites that publish it
              Omit "from" to start at the first chapter. Use [] when nothing should be done.
            Do not invent chapter counts or sources; the app checks those itself.
        """.trimIndent()
    }
}
