package eu.kanade.translation

import kotlinx.coroutines.delay

/**
 * MANHWA: keeps the phone from heating up. The phone's own heat level (Android's thermal status, read by
 * [eu.kanade.presentation.components.DecorMotion]) slows the work nobody is waiting for: chapters translated in the
 * background rest between pages, translating ahead stops, automatic colours and decoration rest. What the reader
 * is looking at right now is never held back.
 */
object ThermalGuard {

    /** Android's thermal status (0 none, 1 light, 2 moderate, 3 severe and up). */
    @Volatile var status: Int = 0

    /** Warm: work in the background slows down, nothing optional starts. */
    val warm: Boolean get() = status >= LIGHT

    /** Hot: work in the background waits until the phone cools down. */
    val hot: Boolean get() = status >= MODERATE

    /** How long background work rests before each page at [level]. */
    fun pauseFor(level: Int): Long = when {
        level >= MODERATE -> HOT_PAUSE_MS
        level >= LIGHT -> WARM_PAUSE_MS
        else -> 0L
    }

    /** Called before each page of background work: a rest when warm, a wait while hot. */
    suspend fun coolDown() {
        // Hot: background work stops until the phone is only warm, a few minutes in all for one hot spell (not
        // for every page), then it goes on slowly
        while (hot) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (hotSince == 0L) hotSince = now
            if (now - hotSince >= MAX_WAIT_MS) break
            delay(CHECK_MS)
        }
        if (!hot) hotSince = 0L
        pauseFor(status).takeIf { it > 0 }?.let { delay(it) }
    }

    /** When the phone became hot (0 while it is not): the wait is shared by all pages of one hot spell. */
    @Volatile private var hotSince = 0L

    private const val LIGHT = 1
    private const val MODERATE = 2
    private const val WARM_PAUSE_MS = 1_500L
    private const val HOT_PAUSE_MS = 6_000L
    private const val CHECK_MS = 5_000L
    private const val MAX_WAIT_MS = 3 * 60_000L
}
