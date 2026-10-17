package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.model.PageTranslation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * MANHWA: a chapter's progress kept on the disk while it is translated: the pages already read and the pages
 * already translated. When Android closes the app (or the phone needs the memory) the chapter carries on from
 * the page it reached instead of starting again; it is removed once the chapter is saved or taken out of the queue.
 */
object ChapterCheckpoint {

    @Serializable
    data class Read(
        val processed: Set<String> = emptySet(),
        val pages: Map<String, PageTranslation> = emptyMap(),
        val complete: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun dir(context: Context) = File(context.filesDir, "translation_checkpoints")
    private fun readFile(context: Context, chapterId: Long) = File(dir(context), "$chapterId.read.json")
    private fun doneFile(context: Context, chapterId: Long) = File(dir(context), "$chapterId.done.json")

    fun loadRead(context: Context, chapterId: Long): Read? = synchronized(lock) {
        val file = readFile(context, chapterId)
        if (!file.existsAtomic()) return null
        runCatching { json.decodeFromString<Read>(file.readTextAtomic()) }
            .onFailure { logcat(LogPriority.WARN, it) { "Reading checkpoint of $chapterId unusable" } }
            .getOrNull()
    }

    fun saveRead(context: Context, chapterId: Long, read: Read) = synchronized(lock) {
        runCatching {
            dir(context).mkdirs()
            readFile(context, chapterId).writeTextAtomic(json.encodeToString(read))
        }.onFailure { logcat(LogPriority.WARN, it) { "Reading checkpoint of $chapterId not saved" } }
        Unit
    }

    fun loadDone(context: Context, chapterId: Long): Map<String, PageTranslation> = synchronized(lock) {
        val file = doneFile(context, chapterId)
        if (!file.existsAtomic()) return emptyMap()
        runCatching { json.decodeFromString<Map<String, PageTranslation>>(file.readTextAtomic()) }.getOrDefault(emptyMap())
    }

    /** Adds [pages] (translated) to what is kept for [chapterId]. */
    fun addDone(context: Context, chapterId: Long, pages: Map<String, PageTranslation>) {
        if (pages.isEmpty()) return
        synchronized(lock) {
            runCatching {
                val file = doneFile(context, chapterId)
                val before = if (file.existsAtomic()) {
                    runCatching { json.decodeFromString<Map<String, PageTranslation>>(file.readTextAtomic()) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
                dir(context).mkdirs()
                file.writeTextAtomic(json.encodeToString(before + pages))
            }.onFailure { logcat(LogPriority.WARN, it) { "Translated pages of $chapterId not kept" } }
        }
    }

    fun clear(context: Context, chapterId: Long) = synchronized(lock) {
        listOf(readFile(context, chapterId), doneFile(context, chapterId)).forEach { file ->
            file.delete()
            File("${file.path}.bak").delete()
            File("${file.path}.new").delete()
        }
    }

    /** Progress left behind by chapters no longer in any queue, after [MAX_AGE_MS]. */
    fun prune(context: Context) = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        Unit
    }

    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
}

/** The pure rules of [ChapterCheckpoint] (tested without a phone). */
object CheckpointRules {

    /**
     * Puts the kept translations back on the chapter's freshly read [pages]: a page whose lines are the same
     * (same count, same text) takes its translated lines. Returns the pages fully restored, which are not sent
     * to the translator again.
     */
    fun applyDone(pages: MutableMap<String, PageTranslation>, done: Map<String, PageTranslation>): Set<String> {
        val restored = mutableSetOf<String>()
        for ((name, kept) in done) {
            val page = pages[name] ?: continue
            if (page.blocks.size != kept.blocks.size) continue
            if (page.blocks.zip(kept.blocks).any { (a, b) -> a.text != b.text }) continue
            if (kept.blocks.none { it.translation.isNotBlank() }) continue
            page.blocks.zip(kept.blocks).forEach { (a, b) ->
                a.translation = b.translation
                if (a.kind.isBlank()) a.kind = b.kind
            }
            restored += name
        }
        return restored
    }
}
