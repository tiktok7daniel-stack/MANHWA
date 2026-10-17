package eu.kanade.tachiyomi.ui.library.ai

import eu.kanade.translation.writeTextAtomic
import eu.kanade.translation.readTextAtomic
import eu.kanade.translation.existsAtomic
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAiConfigured
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.at.ATMR
import java.io.File
import java.util.Locale

/**
 * MANHWA library organizer: sorts the library into professional manhwa categories (system and leveling,
 * regression, murim, isekai, romance...) with the team's AI, remembers the scheme the reader approved, and puts
 * every series added to the library into it by itself, without asking. Without a key a built-in genre map does it.
 */
@Inject
@SingleIn(AppScope::class)
class LibraryOrganizer(
    private val context: Context,
    private val getLibraryManga: GetLibraryManga,
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val deleteCategory: tachiyomi.domain.category.interactor.DeleteCategory,
    private val setMangaCategories: SetMangaCategories,
    private val sourceManager: SourceManager,
    private val preferences: LibraryPreferences,
) {

    data class Group(val name: String, val mangaIds: List<Long>, val about: String = "")

    @Serializable
    data class SchemeCategory(val name: String, val about: String = "")

    /** One series the organizer placed by itself; [previous] lets the reader undo it. */
    @Serializable
    data class Placed(
        val mangaId: Long,
        val title: String,
        val categories: List<String>,
        val previous: List<Long>,
        val at: Long = System.currentTimeMillis(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private val logFile get() = File(context.filesDir, "auto_organize_log.json")

    private val _placed = MutableSharedFlow<List<Placed>>(extraBufferCapacity = 8)

    /** Series placed by the automatic organizer, as it happens, for the in-app banner. */
    val placed: SharedFlow<List<Placed>> = _placed.asSharedFlow()

    private val _log = MutableStateFlow<List<Placed>>(emptyList())

    /** The latest automatic placements, newest first. */
    val log: StateFlow<List<Placed>> = _log.asStateFlow()

    /** What a full reorganize did, for the library bar's button. */
    data class Result(val changed: Int, val created: Int, val removed: Int, val at: Long = System.currentTimeMillis())

    private val _running = MutableStateFlow(false)

    /** True while the organizer works (the library bar's button spins). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastResult = MutableStateFlow<Result?>(null)
    val lastResult: StateFlow<Result?> = _lastResult.asStateFlow()

    /** Ids already handled by the watcher in this run of the app. */
    private val handled = HashSet<Long>()

    @Volatile
    private var watching = false

    init {
        runCatching { logFile.takeIf { it.existsAtomic() }?.readTextAtomic()?.let { json.decodeFromString<List<Placed>>(it) } }
            .getOrNull()
            ?.let { _log.value = it }
    }

    fun scheme(): List<SchemeCategory> =
        runCatching { json.decodeFromString<List<SchemeCategory>>(preferences.organizerScheme.get()) }.getOrDefault(emptyList())

    fun clearScheme() = preferences.organizerScheme.set("")

    private fun language(): String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)

    private val arabic get() = Locale.getDefault().language == "ar"

    // region Whole library

    /**
     * Splits the whole library into categories. Big libraries go in batches: the first one draws the scheme, the
     * next ones fill the same categories so names never come back twice under another spelling.
     */
    suspend fun organizeAll(instruction: String): List<Group> {
        val library = getLibraryManga.await()
        if (library.isEmpty()) return emptyList()
        preferences.organizerInstruction.set(instruction.trim())
        val existing = userCategories().map { it.name }
        val merged = LinkedHashMap<String, Group>()
        val batches = library.chunked(BATCH)
        batches.forEachIndexed { index, batch ->
            val known = merged.values.map { SchemeCategory(it.name, it.about) }
            val lines = describe(batch)
            val reply = appAsk(AiRouter.Lane.REVIEW) { ai ->
                ai.askJson(
                    system = librarianRules() + "\n" + if (index == 0 || known.isEmpty()) {
                        """
                        Draw the categories for this library: 5 to 12 of them, short names (1 to 3 words) in ${language()}.
                        ${if (existing.isNotEmpty()) "The reader already has these categories, keep using the ones that fit: ${existing.joinToString(", ")}." else ""}
                        Give each category a one line "about" telling what goes in it.
                        Reply with ONLY: {"categories":[{"name":"...","about":"...","ids":[123,456]}]}
                        """.trimIndent()
                    } else {
                        """
                        Use these categories (name: about):
                        ${known.joinToString("\n") { "- ${it.name}: ${it.about}" }}
                        Add a new category only when a series fits none of them.
                        Reply with ONLY: {"categories":[{"name":"...","about":"...","ids":[123,456]}]}
                        """.trimIndent()
                    },
                    prompt = buildString {
                        if (instruction.isNotBlank()) appendLine("Reader's instruction (follow it first): ${instruction.trim()}")
                        appendLine("Library, one series per line (id|title|genres|origin|status|progress|author|story):")
                        append(lines)
                    },
                )
            }
            parseGroups(reply, batch.map { it.manga.id }.toSet()).forEach { group ->
                val key = group.name.lowercase()
                val old = merged[key]
                merged[key] = if (old == null) group else old.copy(mangaIds = (old.mangaIds + group.mangaIds).distinct())
            }
        }
        return merged.values.toList()
    }

    /** No AI at all: the built-in genre map, for the whole library. */
    suspend fun organizeAllLocally(): List<Group> = localGroups(getLibraryManga.await())

    /**
     * Creates the missing categories, then sets each series' categories, and remembers the scheme for the
     * automatic organizer. Returns how many series changed.
     */
    suspend fun apply(groups: List<Group>, keepExisting: Boolean, remember: Boolean = true): Int {
        val library = getLibraryManga.await().associateBy { it.manga.id }
        // The strict completed rule, whatever the AI answered: only what the source calls finished
        @Suppress("NAME_SHADOWING")
        val groups = LibraryRules.clean(groups, library.mapValues { it.value.manga.status })
        if (groups.all { it.mangaIds.isEmpty() }) return 0
        val assigned = assign(groups)
        assigned.forEach { (mangaId, ids) ->
            val current = library[mangaId]?.categories.orEmpty().filter { it != 0L }
            // MANHWA: one category per series: the one it has stays when asked to keep it, else the new one
            val result = if (keepExisting && current.isNotEmpty()) current.take(1) else ids.take(1)
            setMangaCategories.await(mangaId, result)
        }
        if (remember) {
            val saved = scheme().associateBy { it.name.lowercase() }.toMutableMap()
            groups.forEach { g -> saved[g.name.lowercase()] = SchemeCategory(g.name, g.about.ifBlank { saved[g.name.lowercase()]?.about.orEmpty() }) }
            preferences.organizerScheme.set(json.encodeToString(saved.values.toList()))
        }
        return assigned.size
    }

    /** Category ids for each series of [groups], creating the categories that don't exist yet. */
    private suspend fun assign(groups: List<Group>): Map<Long, Set<Long>> {
        val existing = userCategories()
        val made = mutableListOf<String>()
        groups.map { it.name }.distinctBy { LibraryRules.normalize(it) }.forEach { name ->
            if (existing.none { LibraryRules.normalize(it.name) == LibraryRules.normalize(name) }) {
                createCategoryWithName.await(name)
                made += name
            }
        }
        if (made.isNotEmpty()) preferences.organizerCreated.set(preferences.organizerCreated.get() + made)
        val byName = userCategories().associateBy { LibraryRules.normalize(it.name) }
        val assigned = HashMap<Long, MutableSet<Long>>()
        groups.forEach { group ->
            val categoryId = byName[LibraryRules.normalize(group.name)]?.id ?: return@forEach
            // MANHWA: one category per series: the first group that names it wins
            group.mangaIds.forEach { id -> if (id !in assigned) assigned[id] = mutableSetOf(categoryId) }
        }
        return assigned
    }

    private suspend fun userCategories() = getCategories.await().filterNot { it.isSystemCategory }

    // endregion

    // region Automatic

    /** Starts watching the library: each series added to it is placed in its categories a moment later. */
    @OptIn(FlowPreview::class)
    fun watch() {
        if (watching) return
        watching = true
        if (preferences.autoOrganizeSince.get() == 0L) preferences.autoOrganizeSince.set(System.currentTimeMillis())
        scope.launch {
            getLibraryManga.subscribe()
                .debounce(SETTLE_MS)
                .collect { library -> runCatching { catchUp(library) }.onFailure { logcat(LogPriority.WARN, it) } }
        }
    }

    /** Places the series added since the last run, and moves the finished ones. Safe to call any time. */
    suspend fun catchUp(current: List<LibraryManga>? = null) = mutex.withLock {
        if (!preferences.autoOrganize.get()) return@withLock
        val library = current ?: getLibraryManga.await()
        val since = preferences.autoOrganizeSince.get()
        val default = preferences.defaultCategory.get().toLong()
        val fresh = library.filter { item ->
            val at = item.manga.favoriteAt?.toEpochMilliseconds() ?: 0L
            val mine = item.categories.filter { it != 0L }
            // Only series nobody placed yet: no category, or just the reader's default one
            at > since && item.manga.id !in handled && (mine.isEmpty() || mine == listOf(default))
        }
        if (fresh.isNotEmpty()) {
            place(fresh)
            handled += fresh.map { it.manga.id }
            preferences.autoOrganizeSince.set(fresh.maxOf { it.manga.favoriteAt?.toEpochMilliseconds() ?: 0L }.coerceAtLeast(since))
        }
        if (preferences.autoCompletedMove.get()) moveCompleted(library)
    }

    private suspend fun place(items: List<LibraryManga>) {
        val groups = runCatching { if (appAiConfigured()) placeWithAi(items) else emptyList() }
            .onFailure { logcat(LogPriority.WARN, it) }
            .getOrDefault(emptyList())
            .ifEmpty { localGroups(items) }
        if (groups.isEmpty()) return
        val before = items.associate { it.manga.id to it.categories.filter { c -> c != 0L } }
        val assigned = assign(groups)
        val names = userCategories().associate { it.id to it.name }
        val placed = assigned.mapNotNull { (mangaId, ids) ->
            val item = items.firstOrNull { it.manga.id == mangaId } ?: return@mapNotNull null
            val previous = before[mangaId].orEmpty()
            // MANHWA: a series the reader already put in a category stays there alone; else it gets one
            if (previous.isNotEmpty()) return@mapNotNull null
            val one = ids.take(1)
            setMangaCategories.await(mangaId, one)
            Placed(mangaId, item.manga.title, one.mapNotNull { names[it] }, previous)
        }
        if (placed.isEmpty()) return
        _log.value = (placed + _log.value).take(MAX_LOG)
        saveLog()
        _placed.tryEmit(placed)
    }

    private suspend fun placeWithAi(items: List<LibraryManga>): List<Group> {
        val scheme = scheme().ifEmpty { userCategories().map { SchemeCategory(it.name) } }.filterNot { LibraryRules.isBroad(it.name) }
        val instruction = preferences.organizerInstruction.get()
        val reply = appAsk(AiRouter.Lane.BACKGROUND) { ai ->
            ai.askJson(
                system = librarianRules() + "\n" + """
                    The reader just added these series to the library. Read each one's story and put it in EXACTLY ONE of the reader's categories (a series is never in two):
                    ${if (scheme.isEmpty()) "(none yet)" else scheme.joinToString("\n") { "- ${it.name}${if (it.about.isNotBlank()) ": ${it.about}" else ""}" }}
                    Create a new category (short name in ${language()}) only when none fits.
                    Reply with ONLY: {"categories":[{"name":"...","about":"...","ids":[123]}]}
                """.trimIndent(),
                prompt = buildString {
                    if (instruction.isNotBlank()) appendLine("Reader's style: $instruction")
                    appendLine("New series (id|title|genres|origin|status|progress|author|story):")
                    append(describe(items))
                },
            )
        }
        return parseGroups(reply, items.map { it.manga.id }.toSet()).filterNot { LibraryRules.isBroad(it.name) }
    }

    /** The reader's "Completed" category, in any usual spelling. */
    private suspend fun completedCategory() = context.stringResource(ATMR.strings.org_completed_category).let { name ->
        userCategories().firstOrNull { it.name.equals(name, ignoreCase = true) || LibraryRules.isCompletedName(it.name) }
    }

    /**
     * The strict "Completed" rule both ways: a series the source calls finished goes into the reader's
     * "Completed" category, and one the source does not call finished leaves it (reading progress never counts).
     * Returns how many series changed.
     */
    private suspend fun moveCompleted(library: List<LibraryManga>): Int {
        val category = completedCategory() ?: return 0
        var changed = 0
        library.forEach { item ->
            val finished = LibraryRules.isFinished(item.manga.status)
            val inside = category.id in item.categories
            if (finished == inside) return@forEach
            val mine = item.categories.filter { it != 0L && it != category.id }
            // MANHWA: a finished series is in "Completed" only
            setMangaCategories.await(item.manga.id, if (finished) listOf(category.id) else mine)
            changed++
        }
        return changed
    }

    /**
     * MANHWA: every series that is in several categories is put in one (libraries organized before a series was
     * kept in one place): the reader's own category first, then "Completed" for a finished series, then the
     * organizer's. No series is removed. Returns how many series changed.
     */
    suspend fun oneCategoryEach(): Int = mutex.withLock {
        val library = getLibraryManga.await()
        val completed = completedCategory()
        val managedKeys = (scheme().map { it.name } + preferences.organizerCreated.get()).map(LibraryRules::normalize).toSet()
        val managed = userCategories().filter { LibraryRules.normalize(it.name) in managedKeys }.map { it.id }.toSet()
        var changed = 0
        library.distinctBy { it.manga.id }.forEach { item ->
            val current = item.categories.filter { it != 0L }.distinct()
            if (current.size <= 1) return@forEach
            val one = LibraryRules.oneCategory(current, managed, completed?.id, LibraryRules.isFinished(item.manga.status))
            if (one.isEmpty()) return@forEach
            setMangaCategories.await(item.manga.id, one)
            changed++
        }
        changed
    }

    /**
     * MANHWA full reorganize (the library bar's button, and once a day by itself): the saved scheme is used to
     * put every series in its right categories, wrong ones are corrected, missing categories are created,
     * duplicates with the same meaning are merged, the strict "Completed" rule is applied, and categories the
     * organizer itself created that are now empty are removed. The reader's own categories are never removed or
     * emptied. Never runs twice at once.
     */
    suspend fun reorganize(): Result? {
        if (_running.value) return null
        return mutex.withLock {
            _running.value = true
            try {
                reorganizeLocked()
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun reorganizeLocked(): Result {
        val library = getLibraryManga.await()
        val beforeCategories = userCategories()
        val before = library.associate { it.manga.id to it.categories.filter { c -> c != 0L }.toSet() }
        if (library.isEmpty()) return Result(0, 0, 0).also { _lastResult.value = it }
        val scheme = scheme()
        val raw = runCatching { if (appAiConfigured()) placeAllWithScheme(library, scheme) else emptyList() }
            .onFailure { logcat(LogPriority.WARN, it) { "The organizer AI failed, using the genre map" } }
            .getOrDefault(emptyList())
            .ifEmpty { localGroups(library) }
        val groups = LibraryRules.clean(raw, library.associate { it.manga.id to it.manga.status })
        val assigned = assign(groups)
        val categories = userCategories()
        val completed = completedCategory()
        // Managed: what the scheme and the organizer stand for; everything else belongs to the reader alone
        val schemeKeys = (scheme.map { it.name } + groups.map { it.name } + preferences.organizerCreated.get()).map(LibraryRules::normalize).toSet()
        val managed = categories.filter { LibraryRules.normalize(it.name) in schemeKeys }.map { it.id }.toSet()
        var changed = 0
        library.forEach { item ->
            val id = item.manga.id
            val target = assigned[id] ?: return@forEach
            val next = LibraryRules.finalCategories(
                current = item.categories,
                assigned = target,
                managed = managed,
                completedId = completed?.id,
                finished = LibraryRules.isFinished(item.manga.status),
            )
            if (next.toSet() != before[id]) {
                setMangaCategories.await(id, next)
                changed++
            }
        }
        // Series the AI did not place still follow the completed rule
        changed += moveCompleted(getLibraryManga.await().filter { it.manga.id !in assigned })
        // Duplicates of one meaning made by the organizer: their series move to the first, then they go
        val created = preferences.organizerCreated.get()
        var removed = 0
        userCategories().groupBy { LibraryRules.normalize(it.name) }.values.filter { it.size > 1 }.forEach { same ->
            val keep = same.firstOrNull { c -> created.none { LibraryRules.normalize(it) == LibraryRules.normalize(c.name) } && c.name !in created } ?: same.first()
            same.filter { it.id != keep.id && it.name in created }.forEach { dup ->
                getLibraryManga.await().filter { dup.id in it.categories }.forEach { item ->
                    setMangaCategories.await(item.manga.id, (item.categories.filter { it != 0L && it != dup.id } + keep.id).distinct())
                }
                deleteCategory.await(dup.id)
                removed++
            }
        }
        // Empty categories the organizer created (never the reader's own)
        val nowLibrary = getLibraryManga.await()
        val counts = userCategories().associate { c -> c.name to nowLibrary.count { c.id in it.categories } }
        val empty = LibraryRules.removableEmpty(created, counts)
        userCategories().filter { it.name in empty && it.id != completed?.id }.forEach {
            deleteCategory.await(it.id)
            removed++
        }
        if (removed > 0) preferences.organizerCreated.set(created.filterNot { name -> userCategories().none { it.name == name } }.toSet())
        val newCount = userCategories().count { c -> beforeCategories.none { it.id == c.id } }
        preferences.organizerLastFull.set(System.currentTimeMillis())
        return Result(changed, newCount, removed).also { _lastResult.value = it }
    }

    /** Every series of the library put in the saved scheme's categories (new ones only when nothing fits). */
    private suspend fun placeAllWithScheme(library: List<LibraryManga>, scheme: List<SchemeCategory>): List<Group> {
        // MANHWA: catch-all categories ("Action & Fantasy") are never offered again: every series gets its premise
        val known = scheme.ifEmpty { userCategories().map { SchemeCategory(it.name) } }.filterNot { LibraryRules.isBroad(it.name) }
        val instruction = preferences.organizerInstruction.get()
        val merged = mutableListOf<Group>()
        library.chunked(BATCH).forEach { batch ->
            val categories = (known + merged.map { SchemeCategory(it.name, it.about) }).distinctBy { LibraryRules.normalize(it.name) }
            val reply = appAsk(AiRouter.Lane.REVIEW) { ai ->
                ai.askJson(
                    system = librarianRules() + "\n" + """
                        Put every series below in EXACTLY ONE of these categories (name: about); a series is never in two:
                        ${categories.joinToString("\n") { "- ${it.name}${if (it.about.isNotBlank()) ": ${it.about}" else ""}" }}
                        Decide from each series' story. Fix series that are in a wrong category. Create a new category
                        (short name in ${language()}, named after a premise) when a series' premise fits none of them. Never put a series in a "Completed" category unless its status
                        is "completed": reading progress never makes a series completed.
                        Reply with ONLY: {"categories":[{"name":"...","about":"...","ids":[123,456]}]}
                    """.trimIndent(),
                    prompt = buildString {
                        if (instruction.isNotBlank()) appendLine("Reader's instruction (follow it first): $instruction")
                        appendLine("Library, one series per line (id|title|genres|origin|status|progress|author|story):")
                        append(describe(batch))
                    },
                )
            }
            merged += parseGroups(reply, batch.map { it.manga.id }.toSet()).filterNot { LibraryRules.isBroad(it.name) }
        }
        return merged
    }

    /** The full reorganize by itself, at most once a day (from the background worker). */
    suspend fun dailyIfDue() {
        if (!preferences.autoOrganize.get()) return
        if (System.currentTimeMillis() - preferences.organizerLastFull.get() < DAY_MS) return
        if (scheme().isEmpty() && userCategories().isEmpty()) return
        reorganize()
    }

    /** Puts a series back where it was before the organizer placed it. */
    fun undo(entry: Placed) {
        scope.launch {
            setMangaCategories.await(entry.mangaId, entry.previous)
            _log.value = _log.value.filterNot { it.mangaId == entry.mangaId && it.at == entry.at }
            saveLog()
        }
    }

    private fun saveLog() {
        runCatching { logFile.writeTextAtomic(json.encodeToString(_log.value)) }
    }

    // endregion

    // region AI helpers

    private fun librarianRules() = """
        You are a professional manhwa, manga and manhua librarian who knows the webtoon world deeply.
        Read each series' STORY (its description) and what you know of the title, and classify it by the PREMISE of
        its story: what the story is about. The source's genre tags are only a hint, never the reason.
        Never use broad catch-all categories such as "Action", "Fantasy", "Action & Fantasy", "Adventure",
        "Shounen", "Supernatural" or "Manhwa": nearly every series is action and fantasy, so they tell the reader
        nothing. Choose the specific premise instead, for example: regression / return to the past,
        reincarnation and transmigration (into a novel or a game), system and leveling, dungeons, gates and hunters,
        towers, murim and martial arts, cultivation, isekai / another world, revenge, academy and school, villain or
        dark protagonist, apocalypse and survival, game and virtual reality, military and war, lords and kingdom
        building, monsters and taming, necromancers and summoners, gangs and street fights, romance, villainess and
        noble romance, BL, GL, horror and thriller, comedy, slice of life, historical, sports.
        Merge premises that always go together (for example "System & Leveling"). Never make a category of one
        series unless the reader asks. Keep the categories balanced: no category holds more than about a quarter of
        the library; when one would, split it by premise. Do not make categories by reading progress.
        A "Completed" category holds ONLY series whose status is "completed". Never judge a series completed from
        how much of it was read.
        Every series goes in EXACTLY ONE category: the premise its story is most about. A series is never in two.
    """.trimIndent()

    private suspend fun describe(items: List<LibraryManga>): String {
        val sources = items.map { it.manga.source }.distinct().associateWith { id ->
            val source = runCatching { sourceManager.getOrStub(id) }.getOrNull()
            listOfNotNull(source?.name, (source as? CatalogueSource)?.lang).joinToString(" ")
        }
        return items.joinToString("\n") { item ->
            val m = item.manga
            val origin = sources[m.source].orEmpty()
            val status = when (m.status.toInt()) {
                SManga.ONGOING -> "ongoing"
                SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> "completed"
                SManga.ON_HIATUS -> "hiatus"
                SManga.CANCELLED -> "cancelled"
                else -> "unknown"
            }
            val story = m.description.orEmpty().replace(Regex("\\s+"), " ").trim().take(STORY_CHARS)
            listOf(
                m.id,
                m.title.replace("|", " "),
                m.genre.orEmpty().take(10).joinToString(","),
                origin,
                status,
                "read ${item.readCount}/${item.totalChapters}",
                listOfNotNull(m.author, m.artist).distinct().joinToString(", ").take(60),
                story.replace("|", " "),
            ).joinToString("|")
        }
    }

    private fun parseGroups(reply: JsonObject, valid: Set<Long>): List<Group> {
        val merged = LinkedHashMap<String, Group>()
        (reply["categories"] as? JsonArray).orEmpty().forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(MAX_NAME).orEmpty()
            val about = (obj["about"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val ids = (obj["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
                .filter { it in valid }.distinct()
            if (name.isEmpty() || ids.isEmpty()) return@forEach
            val old = merged[name.lowercase()]
            merged[name.lowercase()] = if (old == null) Group(name, ids, about) else old.copy(mangaIds = (old.mangaIds + ids).distinct())
        }
        return merged.values.toList()
    }

    // endregion

    // region Built-in genre map

    private class Theme(val en: String, val ar: String, val words: List<String>, val story: List<String> = emptyList())

    /** The specific themes come first: a regression murim series is better found under those than "Action". */
    private val themes = listOf(
        Theme(
            "System & Leveling", "نظام وتلفيل",
            listOf("system", "leveling", "level up", "game", "gamer", "virtual reality", "نظام", "تلفيل", "ألعاب", "시스템", "레벨", "게임", "sistema", "videojuegos"),
            listOf("system", "level up", "leveling", "status window", "نظام", "المستوى"),
        ),
        Theme(
            "Regression & Rebirth", "عودة بالزمن وتناسخ",
            listOf("regression", "regressor", "reincarnation", "rebirth", "time travel", "transmigration", "عودة بالزمن", "تناسخ", "سفر عبر الزمن", "회귀", "환생", "빙의", "reencarnación", "regresión"),
            listOf("regress", "returned to the past", "second chance", "reincarnat", "reborn", "time travel", "عاد بالزمن", "تناسخ", "ولد من جديد"),
        ),
        Theme(
            "Murim & Martial Arts", "موريم وفنون قتالية",
            listOf("murim", "martial arts", "wuxia", "cultivation", "xianxia", "xuanhuan", "فنون قتالية", "موريم", "زراعة", "무협", "무림", "artes marciales"),
            listOf("murim", "martial", "sect", "cultivat", "موريم", "الطائفة"),
        ),
        Theme("Isekai", "إيسيكاي", listOf("isekai", "another world", "عالم آخر", "إيسيكاي", "ايسكاي", "이세계"), listOf("another world", "isekai", "عالم آخر")),
        Theme("Dungeons & Hunters", "دانجن وصيادين", listOf("dungeon", "tower", "hunter", "دانجن", "زنزانة", "برج", "صياد", "던전", "헌터", "탑"), listOf("dungeon", "hunter", "gate", "tower", "البوابة", "الصيادين")),
        Theme("BL", "BL", listOf("yaoi", "boys love", "boys' love", "bl", "shounen ai", "danmei", "بويز لوف")),
        Theme("GL", "GL", listOf("yuri", "girls love", "girls' love", "gl", "shoujo ai", "يوري")),
        Theme("Romance", "رومانسي", listOf("romance", "josei", "shoujo", "رومانسي", "رومانسية", "رومنسي", "로맨스", "romance")),
        Theme("Horror & Thriller", "رعب وإثارة", listOf("horror", "thriller", "psychological", "mystery", "survival", "رعب", "إثارة", "غموض", "نفسي", "공포", "스릴러", "terror", "misterio")),
        Theme("Drama & School", "دراما ومدرسة", listOf("drama", "school life", "school", "slice of life", "دراما", "مدرسي", "حياة مدرسية", "شريحة من الحياة", "드라마", "학원", "일상")),
        Theme("Comedy", "كوميديا", listOf("comedy", "كوميدي", "كوميديا", "코미디", "comedia")),
        Theme("Historical", "تاريخي", listOf("historical", "تاريخي", "사극", "histórico")),
        Theme("Sports", "رياضة", listOf("sports", "رياضة", "رياضي", "스포츠", "deportes")),
        Theme("Revenge", "انتقام", listOf("revenge", "انتقام", "복수", "venganza"), listOf("revenge", "avenge", "vengeance", "انتقام", "ينتقم")),
        Theme("Academy", "أكاديمية", listOf("academy", "أكاديمية", "아카데미"), listOf("academy", "أكاديمية")),
        Theme("Apocalypse & Survival", "نهاية العالم والبقاء", listOf("apocalypse", "post-apocalyptic", "zombie", "نهاية العالم", "زومبي", "아포칼립스", "좀비"), listOf("apocalypse", "zombie", "end of the world", "نهاية العالم", "زومبي")),
        Theme("Villain & Dark Hero", "شرير وبطل مظلم", listOf("villain", "anti-hero", "شرير", "악역", "villano"), listOf("villain", "demon king", "الشرير", "ملك الشياطين")),
        Theme("Action & Fantasy", "أكشن وفانتازيا", listOf("action", "fantasy", "adventure", "supernatural", "magic", "أكشن", "فانتازيا", "مغامرة", "خارق للطبيعة", "سحر", "액션", "판타지", "acción", "fantasía", "aventura")),
    )

    private val wordRegex = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun matches(text: String, word: String): Boolean =
        if (word.all { it.code < 128 }) {
            wordRegex.getOrPut(word) { Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)") }.containsMatchIn(text)
        } else {
            text.contains(word)
        }

    private suspend fun localGroups(items: List<LibraryManga>): List<Group> {
        val existing = userCategories().map { it.name }
        val out = LinkedHashMap<String, MutableList<Long>>()
        items.forEach { item ->
            val genres = item.manga.genre.orEmpty().map { it.lowercase().trim() }
            val story = item.manga.description.orEmpty().lowercase()
            // MANHWA: the story says what a series is about; the genre tags only help, and the broad theme
            // ("Action & Fantasy") is only used when nothing more specific is found
            val scored = themes.map { theme ->
                val g = genres.count { genre -> theme.words.any { matches(genre, it) } }
                val s = theme.story.count { matches(story, it) } * 2
                theme to g + s
            }.filter { it.second > 0 }
            val specific = scored.filterNot { LibraryRules.isBroad(it.first.en) }
            val picked = specific.ifEmpty { scored }
                // Order of the list breaks ties: the specific themes before the broad ones
                .sortedByDescending { it.second }
                // MANHWA: one category per series, never two
                .take(1)
                .map { it.first }
            picked.forEach { theme ->
                val name = existing.firstOrNull { it.equals(theme.en, true) || it.equals(theme.ar, true) } ?: if (arabic) theme.ar else theme.en
                out.getOrPut(name) { mutableListOf() } += item.manga.id
            }
        }
        return out.map { (name, ids) -> Group(name, ids.distinct()) }
    }

    // endregion

    private companion object {
        const val BATCH = 60
        const val STORY_CHARS = 220
        const val MAX_NAME = 40
        const val MAX_LOG = 40
        const val SETTLE_MS = 3_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
