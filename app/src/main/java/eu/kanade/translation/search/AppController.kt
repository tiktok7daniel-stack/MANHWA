package eu.kanade.translation.search

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.source.local.isLocal
import java.util.concurrent.TimeUnit

/**
 * What MANHWA AI knows and can change in the app: it reads the library, the sources, the extensions that
 * can be added and the main settings, and carries out the reader's requests on them (home sources, pins,
 * fastest sources, installing extensions, languages, translation settings, opening a screen or a series).
 */
@Inject
@SingleIn(AppScope::class)
class AppController(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
    private val getLibraryManga: GetLibraryManga,
    private val translationPreferences: TranslationPreferences,
    private val fresh: FreshChaptersEngine,
) {

    /** One thing done, with what the chat should show for it. */
    data class Outcome(
        val done: String? = null,
        val screen: String? = null,
        val library: List<Pair<LibraryManga, Source>> = emptyList(),
        val fresh: List<FreshChaptersEngine.Item> = emptyList(),
    )

    private val pingClient = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()

    private suspend fun installedSources(): List<Source> {
        val disabled = sourcePreferences.disabledSources.get()
        return sourceManager.getAll().filter { !it.isLocal() && it !is StubSource && "${it.id}" !in disabled }
    }

    /** The app as the AI sees it, kept short enough for every request. */
    suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        val home = sourcePreferences.homeSources.get()
        val pinned = sourcePreferences.pinnedSources.get()
        val languages = sourcePreferences.enabledLanguages.get()
        val sources = installedSources()
        val library = runCatching { getLibraryManga.await() }.getOrDefault(emptyList())
        val addable = runCatching { extensionManager.availableExtensionsFlow.first() }.getOrDefault(emptyList())
            .filter { it.lang in languages || it.lang == "all" }
        val installedPkgs = runCatching { extensionManager.loadedExtensionsFlow.first() }.getOrDefault(emptyList()).map { it.pkgName }.toSet()
        buildString {
            appendLine("APP STATE (use it to answer and to act):")
            appendLine("Enabled source languages: ${languages.joinToString()}")
            appendLine("Auto translation when a chapter opens: ${translationPreferences.liveTranslation().get()}; target language: ${translationPreferences.translateToLanguage().get()}")
            appendLine("Installed sources (${sources.size}) — [home] shown on the home screen, [pin] pinned:")
            sources.sortedBy { "${it.id}" !in home }.take(MAX_SOURCES).forEach { s ->
                val tags = listOfNotNull("home".takeIf { "${s.id}" in home }, "pin".takeIf { "${s.id}" in pinned }).joinToString(",")
                appendLine("- ${s.name} (${s.lang})${if (tags.isNotEmpty()) " [$tags]" else ""}")
            }
            val notInstalled = addable.filter { it.pkgName !in installedPkgs }.distinctBy { it.pkgName }
            appendLine("Extensions that can be installed (${notInstalled.size}): " + notInstalled.take(MAX_EXTENSIONS).joinToString { "${it.name} (${it.lang})" })
            appendLine("Library (${library.size} series) — id | title | status | read/total | unread:")
            library.sortedByDescending { it.lastRead?.toEpochMilliseconds() ?: 0L }.take(MAX_LIBRARY).forEach { item ->
                val status = when (item.manga.status) {
                    1L -> "ongoing"
                    2L, 4L -> "completed"
                    6L -> "hiatus"
                    5L -> "cancelled"
                    else -> "?"
                }
                appendLine("${item.manga.id} | ${item.manga.title} | $status | ${item.readCount}/${item.totalChapters} | ${item.unreadCount}")
            }
            val feed = fresh.state.value.items
            if (feed.isNotEmpty()) {
                appendLine("Newest chapters on the home sources: " + feed.take(MAX_FRESH).joinToString { "${it.title} — ${it.chapterName}" })
            }
        }
    }

    suspend fun execute(action: JsonObject, say: (key: String, arg: String) -> String): Outcome? {
        val type = action.str("type") ?: return null
        return when (type) {
            "home_add_source", "home_remove_source", "pin_source" -> {
                val source = findSource(action.str("name") ?: return null) ?: return Outcome(say("not_found", action.str("name").orEmpty()))
                val id = "${source.id}"
                when (type) {
                    "home_add_source" -> sourcePreferences.homeSources.set(sourcePreferences.homeSources.get() + id)
                    "home_remove_source" -> sourcePreferences.homeSources.set(sourcePreferences.homeSources.get() - id)
                    else -> sourcePreferences.pinnedSources.set(sourcePreferences.pinnedSources.get() + id)
                }
                Outcome(say(type, source.name))
            }
            "fast_sources" -> {
                val count = (action.num("count") ?: 6L).toInt().coerceIn(1, 12)
                val picked = fastest(count)
                if (picked.isEmpty()) return Outcome(say("not_found", "sources"))
                sourcePreferences.homeSources.set(picked.map { "${it.id}" }.toSet())
                Outcome(say(type, picked.joinToString { it.name }))
            }
            "install_extension" -> {
                val name = action.str("name") ?: return null
                val extension = findExtension(name) ?: return Outcome(say("not_found", name))
                sourcePreferences.homeSources.set(sourcePreferences.homeSources.get() + extension.sources.map { "${it.id}" })
                withContext(Dispatchers.IO) {
                    runCatching {
                        extensionManager.installExtension(extension).transformWhile { emit(it); !it.isCompleted() }.collect {}
                    }
                }
                Outcome(say(type, extension.name))
            }
            "enable_language" -> {
                val lang = action.str("lang")?.lowercase() ?: return null
                sourcePreferences.enabledLanguages.set(sourcePreferences.enabledLanguages.get() + lang)
                runCatching { extensionManager.findAvailableExtensions() }
                Outcome(say(type, lang))
            }
            "set_auto_translate" -> {
                val on = (action["on"] as? JsonPrimitive)?.booleanOrNull ?: return null
                translationPreferences.liveTranslation().set(on)
                Outcome(say(if (on) "auto_on" else "auto_off", ""))
            }
            "set_target_language" -> {
                val lang = action.str("lang")?.uppercase() ?: return null
                val known = eu.kanade.translation.translator.TextTranslatorLanguage.entries.firstOrNull { it.name == lang || it.code.equals(lang, true) }
                    ?: return Outcome(say("not_found", lang))
                translationPreferences.translateToLanguage().set(known.name)
                Outcome(say(type, known.name))
            }
            "open_library" -> {
                val ids = (action["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }.toSet()
                val library = runCatching { getLibraryManga.await() }.getOrDefault(emptyList())
                val picked = library.filter { it.manga.id in ids }.mapNotNull { item -> sourceManager.get(item.manga.source)?.let { item to it } }
                Outcome(library = picked.take(10))
            }
            "fresh_chapters" -> {
                if (fresh.isStale() || fresh.state.value.items.isEmpty()) fresh.refresh()
                val items = fresh.state.value.items
                val filter = action.str("source")?.let { findSource(it) }
                val list = items.filter { filter == null || it.sourceId == filter.id }.take(12)
                Outcome(say(type, "${list.size}"), fresh = list)
            }
            "open_screen" -> {
                val screen = action.str("screen")?.takeIf { it in SCREENS } ?: return null
                Outcome(screen = screen)
            }
            else -> null
        }
    }

    private suspend fun findSource(name: String): Source? {
        val wanted = normalize(name)
        val all = installedSources()
        return all.firstOrNull { normalize(it.name) == wanted }
            ?: all.firstOrNull { normalize(it.name).contains(wanted) || wanted.contains(normalize(it.name)) }
    }

    private suspend fun findExtension(name: String): Extension.Available? {
        val wanted = normalize(name)
        val list = runCatching { extensionManager.availableExtensionsFlow.first() }.getOrDefault(emptyList())
        return list.firstOrNull { normalize(it.name) == wanted }
            ?: list.firstOrNull { normalize(it.name).contains(wanted) || it.sources.any { s -> normalize(s.name) == wanted } }
    }

    /** The reachable sources in the reader's languages, quickest server first; pinned ones always stay. */
    private suspend fun fastest(count: Int): List<Source> = withContext(Dispatchers.IO) {
        val languages = sourcePreferences.enabledLanguages.get()
        val pinned = sourcePreferences.pinnedSources.get()
        val candidates = installedSources().filter { it.lang in languages || it.lang == "all" }
        val gate = Semaphore(6)
        val timed = coroutineScope {
            candidates.map { source ->
                async {
                    gate.withPermit {
                        val url = (source as? HttpSource)?.baseUrl ?: return@withPermit null
                        withTimeoutOrNull(7_000L) {
                            runCatching {
                                val start = System.nanoTime()
                                pingClient.newCall(Request.Builder().url(url).head().build()).execute().use { }
                                source to (System.nanoTime() - start) / 1_000_000
                            }.getOrNull()
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        timed.sortedWith(compareBy({ "${it.first.id}" !in pinned }, { it.second })).take(count).map { it.first }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        private const val MAX_SOURCES = 60
        private const val MAX_EXTENSIONS = 80
        private const val MAX_LIBRARY = 150
        private const val MAX_FRESH = 25
        val SCREENS = setOf("sources_hub", "fresh", "organizer", "smart_download", "downloads", "wrapped")

        /** The actions the AI may ask for, appended to its instructions. */
        val ACTIONS = """
              {"type": "home_add_source", "name": "<installed source>"}  show a source on the home screen
              {"type": "home_remove_source", "name": "<installed source>"}
              {"type": "pin_source", "name": "<installed source>"}
              {"type": "fast_sources", "count": 6}  put the fastest reachable sources on the home screen
              {"type": "install_extension", "name": "<extension that can be installed>"}  add it and show it on home
              {"type": "enable_language", "lang": "<code like ar, en, es>"}
              {"type": "set_auto_translate", "on": true|false}  translate chapters automatically when they open
              {"type": "set_target_language", "lang": "ARABIC|ENGLISH|..."}
              {"type": "open_library", "ids": [<library ids>]}  show series of the library the answer is about
              {"type": "fresh_chapters", "source": "<source name or omit>"}  newest chapters of all home sources
              {"type": "open_screen", "screen": "sources_hub|fresh|organizer|smart_download|downloads|wrapped"}
        """.trimIndent()
    }
}
