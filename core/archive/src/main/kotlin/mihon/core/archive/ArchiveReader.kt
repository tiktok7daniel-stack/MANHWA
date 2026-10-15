package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads entries straight from the memory-mapped archive. The mapping is only released once the reader is closed
 * AND every stream it handed out is closed: leaving a chapter while a page is still decoding used to unmap the
 * memory under the native reader and crash the app (SIGSEGV in libarchive).
 */
class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val size = pfd.statSize
    private val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    private val lock = Any()
    private var closed = false
    private var users = 0
    private var unmapped = false

    private fun acquire() = synchronized(lock) {
        if (closed) throw IOException("Archive is closed")
        users++
    }

    private fun release() = synchronized(lock) {
        users--
        if (closed && users == 0) unmap()
    }

    private fun unmap() {
        if (unmapped) return
        unmapped = true
        Os.munmap(address, size)
    }

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T {
        acquire()
        try {
            return ArchiveInputStream(address, size).use {
                block(generateSequence { it.getNextEntry() })
            }
        } finally {
            release()
        }
    }

    fun getInputStream(entryName: String): InputStream? {
        acquire()
        val archive = try {
            ArchiveInputStream(address, size)
        } catch (e: Throwable) {
            release()
            throw e
        }
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return Guarded(archive)
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            release()
            throw e
        }
        archive.close()
        release()
        return null
    }

    /** Gives the mapping back when the last open stream closes. */
    private inner class Guarded(stream: InputStream) : FilterInputStream(stream) {
        private val done = AtomicBoolean(false)

        override fun close() {
            try {
                super.close()
            } finally {
                if (done.compareAndSet(false, true)) release()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (users == 0) unmap()
        }
    }
}
