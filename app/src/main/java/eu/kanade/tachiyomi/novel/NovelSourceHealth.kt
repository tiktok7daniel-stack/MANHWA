package eu.kanade.tachiyomi.novel

import android.content.Context
import ireader.core.source.CatalogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * MANHWA novels: whether each installed novel source really works, checked the way the app uses it: its list
 * of novels opens, and a search finds something (the manhwa page looks for a manhwa's novel by searching).
 * The last answer is kept, so the badges show at once.
 */
object NovelSourceHealth {

    enum class Status {
        /** The list opens and a search finds novels. */
        WORKING,

        /** The list opens, but searching does not work: it is skipped when looking for a manhwa's novel. */
        NO_SEARCH,

        /** Nothing opens (site down, moved, or blocked). */
        BROKEN,
    }

    @Serializable
    data class Check(val status: Status, val at: Long, val note: String = "")

    private val json = Json { ignoreUnknownKeys = true }
    private val saver = MapSerializer(String.serializer(), Check.serializer())
    private val _checks = MutableStateFlow<Map<Long, Check>>(emptyMap())
    val checks: StateFlow<Map<Long, Check>> = _checks.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "novel_source_health.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = runCatching {
            json.decodeFromString(saver, file(context).readText())
        }.getOrNull().orEmpty()
        _checks.value = saved.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap() + _checks.value
    }

    private fun save(context: Context) {
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(saver, _checks.value.mapKeys { it.key.toString() }))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    /** Checks every one of [sources], a few at a time; [onProgress] gets how many are done of how many. */
    suspend fun checkAll(
        context: Context,
        sources: List<CatalogSource>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        if (_running.value) return
        _running.value = true
        try {
            load(context)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            onProgress(0, sources.size)
            coroutineScope {
                val gate = Semaphore(PARALLEL)
                sources.map { source ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val check = checkOne(source)
                            _checks.update { it + (source.id to check) }
                            onProgress(done.incrementAndGet(), sources.size)
                        }
                    }
                }.awaitAll()
            }
            withContext(Dispatchers.IO) { save(context) }
        } finally {
            _running.value = false
        }
    }

    suspend fun checkOne(source: CatalogSource): Check {
        val now = System.currentTimeMillis()
        val list = attempt { source.getMangaList(source.getListings().firstOrNull(), 1).mangas.size }
        val search = attempt { source.getMangaList(searchFilters(source, PROBE), 1).mangas.size }
        val status = when {
            (search ?: 0) > 0 -> Status.WORKING
            (list ?: 0) > 0 -> Status.NO_SEARCH
            else -> Status.BROKEN
        }
        return Check(status, now)
    }

    private suspend fun attempt(block: suspend () -> Int): Int? = try {
        withTimeoutOrNull(TIMEOUT_MS) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /** Whether [sourceId] may be searched for a manhwa's novel (not known broken). */
    fun searchable(sourceId: Long): Boolean = _checks.value[sourceId]?.status.let { it == null || it == Status.WORKING }

    private const val PROBE = "love"
    private const val TIMEOUT_MS = 20_000L
    private const val PARALLEL = 4
}
