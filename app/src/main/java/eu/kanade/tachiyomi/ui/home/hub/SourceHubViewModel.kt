package eu.kanade.tachiyomi.ui.home.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
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
import java.util.concurrent.TimeUnit

/**
 * The MANHWA source hub: every installed source with its status, the ones shown on the home screen and the
 * pinned ones, plus every extension of the reader's stores ready to be added with one tap.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SourceHubViewModel(
    sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
    private val basePreferences: BasePreferences,
    getLibraryManga: GetLibraryManga,
) : ViewModel() {

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
    ) {
        val languages: List<String>
            get() = (installed.map { it.lang } + addable.map { it.lang }).distinct()
                .sortedWith(compareBy({ it !in enabledLanguages }, { it }))
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val pingClient = OkHttpClient.Builder()
        .callTimeout(PING_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val pingGate = Semaphore(PARALLEL_PINGS)

    /** One check per host: multi-language extensions share a host for dozens of sources. */
    private val hostLatency = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Long>>()
    private var pingJob = kotlinx.coroutines.SupervisorJob(viewModelScope.coroutineContext[kotlinx.coroutines.Job])

    init {
        combine(
            sourceManager.sources,
            sourcePreferences.disabledSources.changes(),
        ) { sources, disabled ->
            sources.filter { !it.isLocal() && it !is StubSource && "${it.id}" !in disabled }
                .sortedBy { it.name.lowercase() }
        }
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list ->
                _state.update { it.copy(installed = list) }
                list.forEach(::checkHealth)
            }
            .launchIn(viewModelScope)
        sourcePreferences.enabledLanguages.changes()
            .onEach { langs -> _state.update { it.copy(enabledLanguages = langs) } }
            .launchIn(viewModelScope)
        sourcePreferences.homeSources.changes()
            .onEach { ids -> _state.update { it.copy(home = ids) } }
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
        combine(extensionManager.availableExtensionsFlow, extensionManager.loadedExtensionsFlow) { available, loaded ->
            val installedPkgs = loaded.map { it.pkgName }.toSet()
            available.filter { it.pkgName !in installedPkgs }.distinctBy { it.pkgName }.sortedBy { it.name.lowercase() }
        }
            .catch { logcat(LogPriority.ERROR, it) }
            .onEach { list -> _state.update { it.copy(addable = list) } }
            .launchIn(viewModelScope)
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
            _state.update { it.copy(health = it.health + (source.id to Health(latency))) }
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

    /** Puts the pinned sources, then the fastest reachable ones in the reader's languages, on the home screen. */
    fun autoPick() {
        val s = _state.value
        val candidates = s.installed.filter { it.lang in s.enabledLanguages || it.lang == "all" }.ifEmpty { s.installed }
        val ranked = candidates.sortedWith(
            compareBy<Source>(
                { "${it.id}" !in s.pinned },
                { -(s.libraryBySource[it.id] ?: 0) },
                { s.health[it.id]?.latencyMs?.takeIf { ms -> ms >= 0 } ?: Long.MAX_VALUE },
            ),
        )
        setHome(ranked.take(AUTO_PICK).map { "${it.id}" }.toSet())
    }

    fun refreshExtensions() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { extensionManager.findAvailableExtensions() } }
    }

    /** Installs one extension and puts its sources on the home screen as soon as they load. */
    fun install(extension: Extension.Available) {
        sourcePreferences.homeSources.set(sourcePreferences.homeSources.get() + extension.sources.map { "${it.id}" })
        viewModelScope.launch(Dispatchers.IO) { runInstall(extension) }
    }

    /** Installs every safe extension in [languages] one after another, without a prompt for each. */
    fun installAll(languages: Set<String>) {
        if (_state.value.bulk != null) return
        val queue = _state.value.addable.filter { it.lang in languages && it.contentWarning == ContentWarning.SAFE }
        if (queue.isEmpty()) return
        // Installs privately so Android does not ask for every extension, then puts the reader's choice back
        val previousInstaller = basePreferences.extensionInstaller.get()
        basePreferences.extensionInstaller.set(BasePreferences.ExtensionInstaller.PRIVATE)
        _state.update { it.copy(bulk = 0 to queue.size) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                queue.forEachIndexed { index, extension ->
                    runInstall(extension)
                    _state.update { it.copy(bulk = (index + 1) to queue.size) }
                }
            } finally {
                basePreferences.extensionInstaller.set(previousInstaller)
                _state.update { it.copy(bulk = null) }
            }
        }
    }

    private suspend fun runInstall(extension: Extension.Available) = withContext(Dispatchers.IO) {
        runCatching {
            extensionManager.installExtension(extension)
                .untilDone()
                .catch { emit(InstallStep.Error) }
                .collect { step -> _state.update { s -> s.copy(installing = s.installing + (extension.pkgName to step)) } }
        }
    }

    /** The installer's step flow never completes on its own; stop at the first final step. */
    private fun Flow<InstallStep>.untilDone() = transformWhile { step ->
        emit(step)
        !step.isCompleted()
    }

    private companion object {
        const val AUTO_PICK = 6
        const val PARALLEL_PINGS = 6
        const val PING_TIMEOUT_S = 6L
    }
}
