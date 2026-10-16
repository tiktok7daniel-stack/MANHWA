package eu.kanade.translation

import android.util.AtomicFile
import java.io.File

/*
 * MANHWA safe files: the reader's glossary, corrections, edits and the like are written whole or not at all.
 * A crash, a kill or a full disk in the middle of a save leaves the last good copy, never a cut file that would
 * load as empty and then be saved over (losing everything it held).
 */

/** Writes [text] to this file all at once (through a side file, then a rename). */
fun File.writeTextAtomic(text: String) {
    parentFile?.mkdirs()
    val file = AtomicFile(this)
    val out = file.startWrite()
    try {
        out.write(text.toByteArray())
        file.finishWrite(out)
    } catch (e: Throwable) {
        file.failWrite(out)
        throw e
    }
}

/** Reads a file written by [writeTextAtomic] (a save cut short rolls back to the last good copy). */
fun File.readTextAtomic(): String = AtomicFile(this).readFully().decodeToString()

/** The file is there, or a save of it was cut short and its last good copy is. */
fun File.existsAtomic(): Boolean = exists() || File("$path.bak").exists()
