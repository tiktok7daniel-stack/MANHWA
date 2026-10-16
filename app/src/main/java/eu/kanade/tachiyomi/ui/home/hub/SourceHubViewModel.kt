package eu.kanade.tachiyomi.ui.home.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.AdultFilter
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.extension.model.ContentWarning
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The MANHWA source hub: every installed source with its status, the ones shown on the home screen and the
 * pinned ones, plus every extension of the reader's stores ready to be added with one tap.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SourceHubViewModel(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
    private val basePreferences: BasePreferences,
    getLibraryManga: GetLibraryManga,
    private val adultFilter: AdultFilter,
    private val sourceHealth: eu.kanade.tachiyomi.ui.home.discover.SourceHealthManager,
) : ViewModel() {

    /** One source the AI picked: an installed one or one to add, with why and whether its site answers. */
    data class AiPick(
        val name: String,
        val reason: String,
        val installed: Source? = null,
        val available: Extension.Available? = null,
        /** Site answer time for sources not installed yet: null while checking, -1 when down. */
        val latencyMs: Long? = null,
    )

    data class AiSearch(
        val loading: Boolean = false,
        val answer: String? = null,
        val picks: List<AiPick> = emptyList(),
        val error: String? = null,
    )

    /** Server check result: [latencyMs] is null while checking, -1 when unreachable. */
    data class Health(val latencyMs: Long?)

    data class State(
        val installed: List<Source> = emptyList(),
        val enabledLanguages: Set<String> = emptySet(),
        val home: Set<String> = emptySet(),
        val pinned: Set<String> = emptySet(),
        val libraryBySource: Map<Long, Int> = emptyMap(),
        val health: Map<Long, Health> = emptyMap(),
        val addable: List<Extension.Available> = emptyList(),
        val installing: Map<String, InstallStep> = emptyMap(),
        val bulk: Pair<Int, Int>? = null,
        val order: List<String> = emptyList(),
        val sort: Int = SORT_FAME,
        val hideAdult: Boolean = true,
        /** Bumped when the AI scored new sources, so lists sort again. */
        val fameVersion: Int = 0,
        val learning: Boolean = false,
        /** The installed extension behind each source, for uninstalling and updating. */
        val extensionBySource: Map<Long, Extension.Loaded> = emptyMap(),
        val autoUpdate: Boolean = true,
        val updating: Boolean = false,
        /** Sources being updated now, in order; each leaves the list a moment after it is done. */
        val updateQueue: List<Extension.Loaded> = emptyList(),
        val ai: AiSearch = AiSearch(),
        /** MANHWA source health: sources left out of the home screen by themselves, with why. */
        val hiddenFromHome: Map<Long, eu.kanade.tachiyomi.ui.home.discover.SourceHealthManager.Entry> = emptyMap(),
        /** Names of sources just installed that did not answer and were hidden from home (shown once). */
        val newlyHidden: List<String> = emptyList(),
        val recheckingHidden: Boolean = false,
    ) {
        /** Installed extensions with an update waiting. */
        val updatable: List<Extension.Loaded>
            get() = extensionBySource.values.filter { it.hasUpdate }.distinctBy { it.pkgName }

        /** Fame plus what the reader does with the source: pinned, in the library, fast server. */
        fun rank(source: Source): Int {
            val fame = SourceFame.score(source.name) ?: 0
            val pin = if ("${source.id}" in pinned) 1000 else 0
            val library = ((libraryBySource[source.id] ?: 0) * 4).coerceAtMost(30)
            val latency = health[source.id]?.latencyMs
            val speed = when {
                latency == null -> 0
                latency < 0 -> -40
                latency < 700 -> 8
                latency < 1800 -> 3
                else -> 0
            }
            return pin + fame + library + speed
        }

        // Each key is worked out once per source, then sorted: no scoring inside the comparisons
        fun sorted(list: List<Source>): List<Source> = when (sort) {
            SORT_SPEED -> list.map { it to (health[it.id]?.latencyMs?.takeIf { ms -> ms >= 0 } ?: Long.MAX_VALUE) }
                .sortedWith(compareBy<Pair<Source, Long>> { it.second }.thenBy { it.first.name.lowercase() })
                .map { it.first }
            SORT_NAME -> list.sortedBy { it.name.lowercase() }
            else -> list.map { it to rank(it) }
                .sortedWith(compareByDescending<Pair<Source, Int>> { it.second }.thenBy { it.first.name.lowercase() })
                .map { it.first }
        }

        fun sortedExtensions(list: List<Extension.Available>): List<Extension.Available> = when (sort) {
            SORT_NAME -> list.sortedBy { it.name.lowercase() }
            else -> list.map { it to (SourceFame.score(it.name) ?: 0) }
                .sortedWith(compareByDescending<Pair<Extension.Available, Int>> { it.second }.thenBy { it.first.name.lowercase() })
                .map { it.first }
        }

        /** Home sources in the reader's order (new ones at the end). */
        val homeOrdered: List<Source>
            get() {
                val onHome = installed.filter { "${it.id}" in home }
                val position = order.withIndex().associate { (i, id) -> id to i }
                return onHome.sortedBy { position["${it.id}"] ?: Int.MAX_VALUE }
            }

        val languages: List<String>
            get() {
                val priority = SourceFame.languagePriority
                return (installed.map { it.lang } + addable.map { it.lang }).distinct()
                    .sortedWith(
                        compareBy<String>(
                            { it !in enabledLanguages },
                            { priority.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                            { it },
                        ),
                    )
            }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val pingClient = OkHttpClient.Builder()
        .callTimeout(PING_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val pingGate = Semaphore(PARALLEL_PINGS)

    // Declared before init: the server checks it starts write here at once
    /** Server checks land in batches (a few times a second) instead of one screen update per source. */
    private val pendingHealth = java.util.concurrent.ConcurrentHashMap<Long, Health>()

    @Volatile
    private var flushJob: kotlinx.coroutines.Job? = null

    private val privateLock = Any()
    private var privateInstalls = 0
    private var savedInstaller: BasePreferences.ExtensionInstaller? = null

    /** One check per host: multi-language extensions share a host for dozens of sources. */
    private val hostLatency = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Long>>()
    private var pingJob = kotlinx.coroutines.SupervisorJob(viewModelScope.coroutineContext[kotlinx.coroutines.Job])

    init {
        sourceHealth.entries
            .onEach { map -> _state.update { it.copy(hiddenFromHome = map.filterValues { e -> e.hidden }) } }
            .launchIn(viewModelScope)
        SourceFame.loadAi(sourcePreferences.sourceFameAi.get())
        combine(
            sourceManager.sources,
            sourcePreferences.disabledSources.changes(),
            adultFilter.rule(),
        ) { sources, disabled, adult ->
            sources.filter { !it.isLocal() && it !is StubSource && "${it.id}" !in disabled && !adult(it.id, it.name) }
                .sortedBy { it.name.lowercase() }
        }
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list ->
                _state.update { it.copy(installed = list) }
                list.forEach(::checkHealth)
                learnFame(list.map { it.name })
            }
            .launchIn(viewModelScope)
        sourcePreferences.hubSort.changes()
            .onEach { sort -> _state.update { it.copy(sort = sort) } }
            .launchIn(viewModelScope)
        sourcePreferences.hideAdult.changes()
            .onEach { hide -> _state.update { it.copy(hideAdult = hide) } }
            .launchIn(viewModelScope)
        sourcePreferences.enabledLanguages.changes()
            .onEach { langs -> _state.update { it.copy(enabledLanguages = langs) } }
            .launchIn(viewModelScope)
        sourcePreferences.homeSources.changes()
            .onEach { ids -> _state.update { it.copy(home = ids) } }
            .launchIn(viewModelScope)
        sourcePreferences.homeSourcesOrder.changes()
            .onEach { raw -> _state.update { it.copy(order = raw.split(',').filter(String::isNotBlank)) } }
            .launchIn(viewModelScope)
        sourcePreferences.pinnedSources.changes()
            .onEach { ids -> _state.update { it.copy(pinned = ids) } }
            .launchIn(viewModelScope)
        getLibraryManga.subscribe()
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { library ->
                _state.update { s -> s.copy(libraryBySource = library.groupingBy { it.manga.source }.eachCount()) }
            }
            .launchIn(viewModelScope)
        combine(
            extensionManager.availableExtensionsFlow,
            extensionManager.loadedExtensionsFlow,
            sourcePreferences.hideAdult.changes(),
        ) { available, loaded, _ ->
            val installedPkgs = loaded.map { it.pkgName }.toSet()
            available.filter { it.pkgName !in installedPkgs && !adultFilter.hides(it) }
                .distinctBy { it.pkgName }
                .sortedBy { it.name.lowercase() }
        }
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list -> _state.update { it.copy(addable = list) } }
            .launchIn(viewModelScope)
        extensionManager.loadedExtensionsFlow
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { loaded ->
                val map = loaded.flatMap { ext -> ext.sources.map { it.id to ext } }.toMap()
                _state.update { it.copy(extensionBySource = map) }
            }
            .launchIn(viewModelScope)
        sourcePreferences.autoUpdateExtensions.changes()
            .onEach { on -> _state.update { it.copy(autoUpdate = on) } }
            .launchIn(viewModelScope)
    }

    fun setAutoUpdate(on: Boolean) {
        sourcePreferences.autoUpdateExtensions.set(on)
        if (on) updateAll()
    }

    /** Updates every source with an update: silently for the ones the app installed, through Android for the rest. */
    fun updateAll() {
        if (_state.value.updating) return
        val queue = _state.value.updatable
        if (queue.isEmpty()) return
        _state.update { s ->
            s.copy(
                updating = true,
                updateQueue = queue,
                installing = s.installing + queue.associate { it.pkgName to InstallStep.Pending },
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // One after another: private ones update silently, system ones go through Android's prompt
                queue.forEach { extension ->
                    runCatching {
                        extensionManager.updateExtension(extension)
                            .untilDone()
                            .catch { emit(InstallStep.Error) }
                            .collect { step -> _state.update { s -> s.copy(installing = s.installing + (extension.pkgName to step)) } }
                    }
                    // Seen done for a moment, then it leaves the list
                    launch {
                        kotlinx.coroutines.delay(DONE_SHOWN_MS)
                        _state.update { s -> s.copy(updateQueue = s.updateQueue.filter { it.pkgName != extension.pkgName }) }
                    }
                }
            } finally {
                _state.update { it.copy(updating = false) }
            }
        }
    }

    /** Removes the extension behind [sourceId] (with all its languages). */
    fun uninstall(sourceId: Long) {
        val extension = _state.value.extensionBySource[sourceId] ?: return
        val ids = extension.sources.map { "${it.id}" }.toSet()
        sourcePreferences.homeSources.set(sourcePreferences.homeSources.get() - ids)
        extensionManager.uninstallExtension(extension)
    }

    /**
     * AI source search: the team's AI answers a question about sources only ("the best working Arabic sources",
     * "the fastest for Solo Leveling"...) from what the app sees: the installed sources with their live server
     * check, fame and library use, and the extensions that can be added. Picks not installed get their site
     * checked too, so the reader sees at once which ones work.
     */
    fun askAi(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.ai.loading) return
        _state.update { it.copy(ai = AiSearch(loading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val s = _state.value
            val installedLines = s.installed.map { src ->
                val latency = s.health[src.id]?.latencyMs
                val status = when {
                    latency == null -> "unknown"
                    latency < 0 -> "offline"
                    else -> "online ${latency}ms"
                }
                buildString {
                    append("I | ${src.name} | ${src.lang} | $status")
                    SourceFame.score(src.name)?.let { append(" | fame $it") }
                    (s.libraryBySource[src.id] ?: 0).takeIf { it > 0 }?.let { append(" | in library $it") }
                    if ("${src.id}" in s.home) append(" | on home")
                    if (s.extensionBySource[src.id]?.hasUpdate == true) append(" | update waiting")
                }
            }
            val addable = s.addable
                .filter { it.lang in s.enabledLanguages || it.lang == "all" }
                .ifEmpty { s.addable }
                .sortedByDescending { SourceFame.score(it.name) ?: 0 }
                .take(MAX_AI_CATALOG)
            val addableLines = addable.map { ext ->
                buildString {
                    append("A | ${ext.name} | ${ext.lang} | sources ${ext.sources.size}")
                    SourceFame.score(ext.name)?.let { append(" | fame $it") }
                }
            }
            val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
            try {
                val reply = eu.kanade.translation.translator.appAsk(eu.kanade.translation.team.AiRouter.Lane.CHAT) { ai ->
                    ai.askJson(
                        "You are the source expert of a manhwa reader app. Answer ONLY about sources (sites the app reads " +
                            "from). Recommend ONLY names from the two lists below: I = installed (with a live server check), " +
                            "A = can be added. Prefer sources that are online, popular, fast to release and good quality for " +
                            "manhwa, in the languages the question asks for. Never invent names. Answer in $language. " +
                            "Reply with ONLY: {\"answer\": \"2 to 4 sentences\", \"picks\": [{\"name\": \"exact name from the lists\", " +
                            "\"reason\": \"one short line\"}]} with at most 8 picks, best first.",
                        "Question: $q\n\nInstalled:\n${installedLines.joinToString("\n")}\n\nCan be added:\n${addableLines.joinToString("\n")}",
                    )
                }
                val answer = (reply["answer"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
                val picks = (reply["picks"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { element ->
                    val o = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
                    if (name.isEmpty()) return@mapNotNull null
                    val reason = (o["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
                    val key = name.lowercase()
                    val installed = s.installed.firstOrNull { it.name.lowercase() == key }
                        ?: s.installed.firstOrNull { it.name.lowercase().contains(key) || key.contains(it.name.lowercase()) }
                    val available = if (installed == null) {
                        addable.firstOrNull { it.name.lowercase() == key }
                            ?: s.addable.firstOrNull { it.name.lowercase().contains(key) || key.contains(it.name.lowercase()) }
                    } else {
                        null
                    }
                    if (installed == null && available == null) return@mapNotNull null
                    AiPick(name = installed?.name ?: available!!.name, reason = reason, installed = installed, available = available)
                }.distinctBy { it.name }
                _state.update { it.copy(ai = AiSearch(answer = answer, picks = picks)) }
                picks.filter { it.available != null }.forEach(::checkPick)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val message = if (e is eu.kanade.translation.search.AiSearchAssistant.NotConfiguredException) NO_KEY else (e.message ?: e.javaClass.simpleName)
                _state.update { it.copy(ai = AiSearch(error = message)) }
            }
        }
    }

    fun clearAi() = _state.update { it.copy(ai = AiSearch()) }

    /** Checks the site of a source that is not installed yet, so the AI's pick shows whether it works. */
    private fun checkPick(pick: AiPick) {
        val url = pick.available?.sources?.firstNotNullOfOrNull { it.baseUrl.takeIf { u -> u.startsWith("http") } }
        viewModelScope.launch(Dispatchers.IO) {
            val latency = if (url == null) {
                -1L
            } else {
                pingGate.withPermit {
                    runCatching {
                        val start = System.nanoTime()
                        pingClient.newCall(Request.Builder().url(url).head().build()).execute().use { }
                        (System.nanoTime() - start) / 1_000_000
                    }.getOrDefault(-1L)
                }
            }
            _state.update { s ->
                s.copy(ai = s.ai.copy(picks = s.ai.picks.map { if (it.name == pick.name) it.copy(latencyMs = latency) else it }))
            }
        }
    }

    private fun checkHealth(source: Source) {
        if (_state.value.health.containsKey(source.id)) return
        val url = (source as? HttpSource)?.baseUrl?.takeIf { it.startsWith("http") } ?: return
        _state.update { it.copy(health = it.health + (source.id to Health(null))) }
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        val check = hostLatency.getOrPut(host) {
            viewModelScope.async(Dispatchers.IO + pingJob) {
                pingGate.withPermit {
                    runCatching {
                        val start = System.nanoTime()
                        pingClient.newCall(Request.Builder().url(url).head().build()).execute().use { }
                        (System.nanoTime() - start) / 1_000_000
                    }.getOrDefault(-1L)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO + pingJob) {
            val latency = runCatching { check.await() }.getOrDefault(-1L)
            pendingHealth[source.id] = Health(latency)
            flushHealthSoon()
        }
    }


    private fun flushHealthSoon() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            kotlinx.coroutines.delay(HEALTH_BATCH_MS)
            val batch = HashMap(pendingHealth)
            batch.keys.forEach { pendingHealth.remove(it) }
            if (batch.isNotEmpty()) _state.update { it.copy(health = it.health + batch) }
            if (pendingHealth.isNotEmpty()) {
                flushJob = null
                flushHealthSoon()
            }
        }
    }

    fun recheckHealth() {
        pingJob.cancel()
        pingJob = kotlinx.coroutines.SupervisorJob(viewModelScope.coroutineContext[kotlinx.coroutines.Job])
        hostLatency.clear()
        _state.update { it.copy(health = emptyMap()) }
        _state.value.installed.forEach(::checkHealth)
    }

    fun toggleHome(id: String) {
        val current = sourcePreferences.homeSources.get()
        sourcePreferences.homeSources.set(if (id in current) current - id else current + id)
    }

    fun togglePin(id: String) {
        val current = sourcePreferences.pinnedSources.get()
        sourcePreferences.pinnedSources.set(if (id in current) current - id else current + id)
    }

    fun setHome(ids: Set<String>) = sourcePreferences.homeSources.set(ids)

    /** Moves a home source up (-1) or down (+1) in the home screen order. */
    fun move(id: String, delta: Int) {
        val ids = _state.value.homeOrdered.map { "${it.id}" }.toMutableList()
        val from = ids.indexOf(id).takeIf { it >= 0 } ?: return
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from == to) return
        ids.add(to, ids.removeAt(from))
        sourcePreferences.homeSourcesOrder.set(ids.joinToString(","))
    }

    fun setSort(sort: Int) = sourcePreferences.hubSort.set(sort)

    fun setHideAdult(hide: Boolean) = sourcePreferences.hideAdult.set(hide)

    /** Lets the team's AI score the sources the built-in index doesn't know, once each. */
    private fun learnFame(names: List<String>) {
        if (SourceFame.unknown(names).isEmpty() || _state.value.learning) return
        _state.update { it.copy(learning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (SourceFame.learn(names)) {
                    sourcePreferences.sourceFameAi.set(SourceFame.aiRaw())
                    _state.update { it.copy(fameVersion = it.fameVersion + 1) }
                }
            } finally {
                _state.update { it.copy(learning = false) }
            }
        }
    }

    /**
     * AI smart pick: the most famous, fastest sources of each of the reader's languages (the big manhwa languages
     * first), with pinned ones and the ones the library uses on top, put on the home screen in that order.
     */
    fun autoPick() {
        viewModelScope.launch(Dispatchers.IO) {
            val names = _state.value.installed.map { it.name }
            if (SourceFame.learn(names)) {
                sourcePreferences.sourceFameAi.set(SourceFame.aiRaw())
                _state.update { it.copy(fameVersion = it.fameVersion + 1) }
            }
            val s = _state.value
            val reachable = s.installed.filter { (s.health[it.id]?.latencyMs ?: 0L) >= 0 }
            val candidates = reachable.filter { it.lang in s.enabledLanguages || it.lang == "all" }.ifEmpty { reachable }
            val priority = SourceFame.languagePriority
            val byLanguage = candidates.groupBy { it.lang }
                .mapValues { (_, list) -> list.sortedByDescending { s.rank(it) } }
                .toList()
                .sortedBy { (lang, _) -> priority.indexOf(lang).let { if (it < 0) Int.MAX_VALUE else it } }
            // Round robin over the languages so each one gets its best sources in
            val picked = mutableListOf<Source>()
            var round = 0
            while (picked.size < AUTO_PICK && byLanguage.any { it.second.size > round }) {
                byLanguage.forEach { (_, list) -> list.getOrNull(round)?.let { if (picked.size < AUTO_PICK) picked += it } }
                round++
            }
            val ordered = picked.sortedByDescending { s.rank(it) }.map { "${it.id}" }
            sourcePreferences.homeSources.set(ordered.toSet())
            sourcePreferences.homeSourcesOrder.set(ordered.joinToString(","))
        }
    }

    fun refreshExtensions() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { extensionManager.findAvailableExtensions() } }
    }

    /** Installs one extension and puts its sources on the home screen as soon as they load. */
    fun install(extension: Extension.Available) {
        sourcePreferences.homeSources.set(sourcePreferences.homeSources.get() + extension.sources.map { "${it.id}" })
        viewModelScope.launch(Dispatchers.IO) {
            // Installed privately: no Android prompt now, and it can update itself later
            beginPrivate()
            try {
                runInstall(extension)
            } finally {
                endPrivate()
            }
        }
    }

    /** Installs every safe extension in [languages] one after another, without a prompt for each. */
    fun installAll(languages: Set<String>) {
        if (_state.value.bulk != null) return
        val queue = _state.value.addable.filter { it.lang in languages && it.contentWarning == ContentWarning.SAFE }
        if (queue.isEmpty()) return
        // Installs privately so Android does not ask for every extension, then puts the reader's choice back
        beginPrivate()
        _state.update { it.copy(bulk = 0 to queue.size) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                queue.forEachIndexed { index, extension ->
                    runInstall(extension)
                    _state.update { it.copy(bulk = (index + 1) to queue.size) }
                }
            } finally {
                endPrivate()
                _state.update { it.copy(bulk = null) }
            }
        }
    }


    /** Switches to the private installer while any install of this screen runs, then puts the reader's choice back. */
    private fun beginPrivate() = synchronized(privateLock) {
        if (privateInstalls++ == 0) {
            savedInstaller = basePreferences.extensionInstaller.get()
            basePreferences.extensionInstaller.set(BasePreferences.ExtensionInstaller.PRIVATE)
        }
    }

    private fun endPrivate() = synchronized(privateLock) {
        if (--privateInstalls == 0) savedInstaller?.let { basePreferences.extensionInstaller.set(it) }
    }

    private suspend fun runInstall(extension: Extension.Available) = withContext(Dispatchers.IO) {
        var installed = false
        runCatching {
            extensionManager.installExtension(extension)
                .untilDone()
                .catch { emit(InstallStep.Error) }
                .collect { step ->
                    if (step == InstallStep.Installed) installed = true
                    _state.update { s -> s.copy(installing = s.installing + (extension.pkgName to step)) }
                }
        }
        if (installed) viewModelScope.launch(Dispatchers.IO) { checkNewSources(extension) }
    }

    /**
     * MANHWA: a source just added is tried once as soon as it loads; one that does not answer is hidden from
     * the home screen at once (never removed), and comes back by itself when it works.
     */
    private suspend fun checkNewSources(extension: Extension.Available) {
        val ids = extension.sources.map { it.id }
        // The new extension's sources take a moment to load after the install
        repeat(20) {
            if (ids.all { sourceManager.get(it) != null }) return@repeat
            kotlinx.coroutines.delay(500)
        }
        val home = sourcePreferences.homeSources.get()
        val hidden = runCatching { sourceHealth.checkNew(ids.filter { "$it" in home && sourceManager.get(it) != null }) }.getOrDefault(emptyList())
        if (hidden.isNotEmpty()) {
            val names = hidden.mapNotNull { id -> sourceManager.get(id)?.name }
            _state.update { it.copy(newlyHidden = it.newlyHidden + names) }
        }
    }

    fun consumeNewlyHidden() {
        _state.update { it.copy(newlyHidden = emptyList()) }
    }

    /** Tries every source hidden from home again now. */
    fun recheckHidden() {
        if (_state.value.recheckingHidden) return
        _state.update { it.copy(recheckingHidden = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { sourceHealth.recheckAll() }
            } finally {
                _state.update { it.copy(recheckingHidden = false) }
            }
        }
    }

    /** Puts a hidden source back on the home screen by hand. */
    fun restoreToHome(sourceId: Long) = sourceHealth.restore(sourceId)

    /** The installer's step flow never completes on its own; stop at the first final step. */
    private fun Flow<InstallStep>.untilDone() = transformWhile { step ->
        emit(step)
        !step.isCompleted()
    }

    companion object {
        const val SORT_FAME = 0
        const val SORT_SPEED = 1
        const val SORT_NAME = 2
        private const val AUTO_PICK = 8
        private const val MAX_AI_CATALOG = 250
        private const val HEALTH_BATCH_MS = 350L
        private const val DONE_SHOWN_MS = 1_200L
        const val NO_KEY = "no_key"
        private const val PARALLEL_PINGS = 6
        private const val PING_TIMEOUT_S = 6L
    }
}
