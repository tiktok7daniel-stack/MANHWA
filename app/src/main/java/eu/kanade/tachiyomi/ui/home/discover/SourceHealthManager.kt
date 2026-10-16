package eu.kanade.tachiyomi.ui.home.discover

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager

/**
 * MANHWA source health: a home source that keeps failing leaves the home screen by itself, and comes back by
 * itself when it works again. Nothing is deleted or uninstalled: the reader's list of home sources is never
 * changed, the home screen only skips the sources marked hidden here, and the Source Hub shows why.
 *
 * Failures count only when the phone is online and other sources answered in the same round, so a dropped
 * connection never empties the home screen. A hidden source is tried again in the background with a growing
 * wait (6, 12, then 24 hours).
 */
@Inject
@SingleIn(AppScope::class)
class SourceHealthManager(
    private val context: Context,
    private val sourceManager: SourceManager,
) {

    @Serializable
    data class Entry(
        val fails: Int = 0,
        val lastFail: Long = 0,
        val lastSuccess: Long = 0,
        val lastCheck: Long = 0,
        val hiddenAt: Long = 0,
        val nextCheck: Long = 0,
        val rechecks: Int = 0,
        val reason: String? = null,
        /** Installed lately and not yet seen working: one failure is enough to hide it. */
        val fresh: Boolean = false,
    ) {
        val hidden: Boolean get() = hiddenAt > 0
    }

    private val prefs = context.getSharedPreferences("manhwa_source_health", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<Long, Entry>> = _entries.asStateFlow()

    private fun load(): Map<Long, Entry> = runCatching {
        json.decodeFromString<Map<String, Entry>>(prefs.getString(KEY, null) ?: "{}")
            .mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap()
    }.getOrDefault(emptyMap())

    private fun save(map: Map<Long, Entry>) {
        _entries.value = map
        prefs.edit().putString(KEY, json.encodeToString(map.mapKeys { it.key.toString() })).apply()
    }

    fun isHidden(sourceId: Long): Boolean = _entries.value[sourceId]?.hidden == true

    /**
     * One round of home loading: [results] says which sources answered. Returns the sources hidden or brought
     * back by this round.
     */
    fun recordRound(results: Map<Long, Pair<Boolean, String?>>): SourceHealthRules.Change {
        val online = runCatching { context.isOnline() }.getOrDefault(true)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val (next, change) = SourceHealthRules.round(_entries.value, results, online, now)
            save(next)
            if (change.hidden.isNotEmpty()) logcat(LogPriority.WARN) { "Sources hidden from home: ${change.hidden}" }
            return change
        }
    }

    /** A source installed just now: tried once; if it does not answer it is hidden from home at once. */
    suspend fun checkNew(sourceIds: Collection<Long>): List<Long> = withContext(Dispatchers.IO) {
        synchronized(lock) { save(_entries.value + sourceIds.associateWith { (_entries.value[it] ?: Entry()).copy(fresh = true) }) }
        val results = sourceIds.associateWith { probe(it) }
        recordRound(results).hidden
    }

    /** Hidden sources whose wait is over are tried again (from the background worker). */
    suspend fun recheckDue(): SourceHealthRules.Change = withContext(Dispatchers.IO) {
        if (!runCatching { context.isOnline() }.getOrDefault(true)) return@withContext SourceHealthRules.Change()
        val now = System.currentTimeMillis()
        val due = _entries.value.filter { (_, e) -> e.hidden && e.nextCheck <= now }.keys
        if (due.isEmpty()) return@withContext SourceHealthRules.Change()
        val results = due.associateWith { probe(it) }
        synchronized(lock) {
            val (next, change) = SourceHealthRules.recheck(_entries.value, results, now)
            save(next)
            change
        }
    }

    /** Tries now every hidden source (the "check now" button), whatever its wait. */
    suspend fun recheckAll(): SourceHealthRules.Change = withContext(Dispatchers.IO) {
        val hidden = _entries.value.filterValues { it.hidden }.keys
        if (hidden.isEmpty()) return@withContext SourceHealthRules.Change()
        val results = hidden.associateWith { probe(it) }
        synchronized(lock) {
            val (next, change) = SourceHealthRules.recheck(_entries.value, results, System.currentTimeMillis())
            save(next)
            change
        }
    }

    /** Shows a hidden source on the home screen again by hand (its record starts over). */
    fun restore(sourceId: Long) {
        synchronized(lock) { save(_entries.value - sourceId) }
    }

    private suspend fun probe(sourceId: Long): Pair<Boolean, String?> {
        val source = sourceManager.get(sourceId) as? CatalogueSource ?: return false to "Source not installed"
        return try {
            val page = withTimeoutOrNull(PROBE_TIMEOUT_MS) { source.getPopularManga(1) } ?: return false to "No answer in 30 s"
            if (page.mangas.isEmpty()) false to "The source returned nothing" else true to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false to (e.message?.take(160) ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val KEY = "entries"
        const val PROBE_TIMEOUT_MS = 30_000L
    }
}

/** The pure rules behind [SourceHealthManager], kept apart so they can be tested without a phone. */
object SourceHealthRules {

    data class Change(val hidden: List<Long> = emptyList(), val restored: List<Long> = emptyList())

    const val FAILS_TO_HIDE = 3
    private const val HOUR = 60 * 60 * 1000L

    /** Wait before the next try of a hidden source: 6, 12, then 24 hours. */
    fun backoff(rechecks: Int): Long = when (rechecks) {
        0 -> 6 * HOUR
        1 -> 12 * HOUR
        else -> 24 * HOUR
    }

    fun round(
        entries: Map<Long, SourceHealthManager.Entry>,
        results: Map<Long, Pair<Boolean, String?>>,
        online: Boolean,
        now: Long,
    ): Pair<Map<Long, SourceHealthManager.Entry>, Change> {
        // Offline, or every source failed at once (more than one): the phone's connection, not the sources
        val countable = online && (results.size <= 1 || results.values.any { it.first })
        val next = entries.toMutableMap()
        val hidden = mutableListOf<Long>()
        val restored = mutableListOf<Long>()
        results.forEach { (id, result) ->
            val (ok, reason) = result
            val old = entries[id] ?: SourceHealthManager.Entry()
            next[id] = when {
                ok -> {
                    if (old.hidden) restored += id
                    SourceHealthManager.Entry(lastSuccess = now, lastCheck = now)
                }
                !countable -> old.copy(lastCheck = now)
                else -> {
                    val fails = old.fails + 1
                    val hide = !old.hidden && (fails >= FAILS_TO_HIDE || old.fresh)
                    if (hide) hidden += id
                    old.copy(
                        fails = fails,
                        lastFail = now,
                        lastCheck = now,
                        reason = reason ?: old.reason,
                        hiddenAt = if (hide) now else old.hiddenAt,
                        nextCheck = if (hide) now + backoff(0) else old.nextCheck,
                        rechecks = if (hide) 0 else old.rechecks,
                        fresh = false,
                    )
                }
            }
        }
        return next to Change(hidden, restored)
    }

    fun recheck(
        entries: Map<Long, SourceHealthManager.Entry>,
        results: Map<Long, Pair<Boolean, String?>>,
        now: Long,
    ): Pair<Map<Long, SourceHealthManager.Entry>, Change> {
        val next = entries.toMutableMap()
        val restored = mutableListOf<Long>()
        results.forEach { (id, result) ->
            val old = entries[id] ?: return@forEach
            next[id] = if (result.first) {
                restored += id
                SourceHealthManager.Entry(lastSuccess = now, lastCheck = now)
            } else {
                old.copy(
                    lastCheck = now,
                    lastFail = now,
                    fails = old.fails + 1,
                    reason = result.second ?: old.reason,
                    rechecks = old.rechecks + 1,
                    nextCheck = now + backoff(old.rechecks + 1),
                )
            }
        }
        return next to Change(restored = restored)
    }
}
