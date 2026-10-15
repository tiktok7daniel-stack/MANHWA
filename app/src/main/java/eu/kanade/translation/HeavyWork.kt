package eu.kanade.translation

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * MANHWA: one gate for the memory-heavy image work of translation (decoding pages, OCR, drawing the
 * translation into the page). Live translation and drawing in the reader, and the chapters translated in the
 * background from the manga screen, used to decode and read full pages at the same time, and the app ran
 * out of memory and closed. Now only one of them works on an image at a time, and the reader always goes
 * first: the background waits while the reader has work, and for a moment after it.
 */
object HeavyWork {

    private val mutex = Mutex()
    private val readerWaiting = AtomicInteger(0)

    @Volatile private var readerQuietAt = 0L

    /** Whether background work is being held back for the reader right now. */
    val readerBusy: Boolean
        get() = readerWaiting.get() > 0 || SystemClock.elapsedRealtime() < readerQuietAt

    /** Image work for the page the reader shows or is about to show. */
    suspend fun <T> reader(block: suspend () -> T): T {
        readerWaiting.incrementAndGet()
        try {
            mutex.lock()
            try {
                return block()
            } finally {
                mutex.unlock()
            }
        } finally {
            readerWaiting.decrementAndGet()
            readerQuietAt = SystemClock.elapsedRealtime() + READER_GRACE_MS
        }
    }

    /** Image work for chapters translated in the background; waits while the reader is busy. */
    suspend fun <T> background(block: suspend () -> T): T {
        while (true) {
            while (readerWaiting.get() > 0 || SystemClock.elapsedRealtime() < readerQuietAt) delay(WAIT_MS)
            mutex.lock()
            // The reader asked in between: it goes first (the lock is fair, so it gets it next)
            if (readerWaiting.get() == 0) break
            mutex.unlock()
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private const val READER_GRACE_MS = 1_500L
    private const val WAIT_MS = 150L
}
