package eu.kanade.tachiyomi.data.backup.vault

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * MANHWA full backup: everything the reader did, in one file that can be put back on this phone after the app is
 * removed, or given to someone else, and the app comes back as it was:
 * - the Mihon backup with every setting (library, categories, chapters, read marks, history, tracking, the team's
 *   keys, the app's and the sources' settings),
 * - the translation collection, and every file MANHWA keeps (glossaries, story, lessons, edits, characters,
 *   fusions, the brain, the installed sources),
 * - every translated chapter (its translation files) with the downloaded pages of the works that have
 *   translations, so they open translated at once; with [Options.allDownloads], every downloaded chapter too.
 * The phone models are left out (gigabytes, downloaded again from their page). Written and read as a stream, so a
 * backup of many gigabytes never sits in the memory; restoring never replaces what is already on the phone.
 */
object FullBackup {

    data class Options(val allDownloads: Boolean = false)

    data class Progress(val done: Long, val total: Long, val name: String)

    data class Result(val files: Int, val chapters: Int, val lines: Int)

    class NotABackupException : Exception("Not a MANHWA full backup")

    const val EXTENSION = "manhwabackup"

    // region Export

    suspend fun export(context: Context, uri: Uri, options: Options, onProgress: (Progress) -> Unit): Result {
        val vault = context.appGraph.manhwaVault
        val items = plan(context, options)
        val total = items.sumOf { it.size }
        var done = 0L
        var files = 0
        var chapters = 0
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("The file could not be written")
        ZipOutputStream(BufferedOutputStream(out, BUFFER)).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write("{\"format\":$FORMAT,\"createdAt\":${System.currentTimeMillis()}}".toByteArray())
            zip.closeEntry()
            // 1. The Mihon backup, with the private settings (the team's keys, the trackers' logins)
            onProgress(Progress(done, total, ENTRY_MIHON))
            val mihon = File(context.cacheDir, "full_backup.tachibk").apply { delete() }
            try {
                vault.backupCreatorFactory.create(isAutoBackup = false)
                    .backup(Uri.fromFile(mihon), BackupOptions(privateSettings = true))
                zip.putNextEntry(ZipEntry(ENTRY_MIHON))
                mihon.inputStream().use { it.copyTo(zip, BUFFER) }
                zip.closeEntry()
            } finally {
                mihon.delete()
            }
            // 2. The translation collection
            zip.putNextEntry(ZipEntry(ENTRY_COLLECTION))
            runCatching { vault.collection.exportTo(zip) }
                .onFailure { logcat(LogPriority.WARN, it) { "Collection left out of the full backup" } }
            zip.closeEntry()
            // 3. MANHWA's small settings
            zip.putNextEntry(ZipEntry(ENTRY_PREFS))
            zip.write(prefsJson(context).toByteArray())
            zip.closeEntry()
            // 4. Every file: the app's own, the translations and the downloaded pages
            for (item in items) {
                onProgress(Progress(done, total, item.name))
                val entry = ZipEntry(item.name).apply { if (item.time > 0) time = item.time }
                // Pictures and archives are compressed already: stored as they are, much quicker
                zip.setLevel(if (FullBackupRules.packed(item.name)) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
                zip.putNextEntry(entry)
                runCatching { item.open().use { it.copyTo(zip, BUFFER) } }
                    .onFailure { logcat(LogPriority.WARN, it) { "${item.name} left out of the full backup" } }
                zip.closeEntry()
                done += item.size
                files++
                if (item.name.startsWith(TRANSLATIONS) && item.name.endsWith(".json")) chapters++
            }
        }
        onProgress(Progress(total, total, ""))
        return Result(files, chapters, 0)
    }

    private class Item(val name: String, val size: Long, val time: Long, val open: () -> InputStream)

    /** Everything that goes in, with its size (for the progress). */
    private fun plan(context: Context, options: Options): List<Item> {
        val items = mutableListOf<Item>()
        val root = context.filesDir
        root.walkTopDown()
            .onEnter { dir -> dir == root || FullBackupRules.keepDir(dir.relativeTo(root).invariantSeparatorsPath) }
            .filter { it.isFile && FullBackupRules.keepFile(it.name) }
            .forEach { file ->
                items += Item(FILES + file.relativeTo(root).invariantSeparatorsPath, file.length(), file.lastModified()) { file.inputStream() }
            }
        val storage = context.appGraph.manhwaVault.storageManager
        val translations = storage.getTranslationsDirectory()
        val translated = HashSet<String>()
        translations?.let { dir ->
            walk(dir, "") { path, file ->
                items += Item(TRANSLATIONS + path, file.length(), file.lastModified()) { file.openInputStream() }
                // "source/work/chapter.json": the work has translations
                path.split('/').takeIf { it.size >= 3 }?.let { translated += it[0] + "/" + it[1] }
            }
        }
        storage.getDownloadsDirectory()?.let { dir ->
            dir.listFiles()?.filter { it.isDirectory }?.forEach { source ->
                source.listFiles()?.filter { it.isDirectory }?.forEach { work ->
                    val key = "${source.name}/${work.name}"
                    if (options.allDownloads || key in translated) {
                        walk(work, key + "/") { path, file ->
                            items += Item(DOWNLOADS + path, file.length(), file.lastModified()) { file.openInputStream() }
                        }
                    }
                }
            }
        }
        return items
    }

    private fun walk(dir: UniFile, prefix: String, onFile: (String, UniFile) -> Unit) {
        dir.listFiles()?.forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) {
                walk(child, "$prefix$name/", onFile)
            } else if (FullBackupRules.keepFile(name)) {
                onFile(prefix + name, child)
            }
        }
    }

    // endregion

    // region Import

    /**
     * Puts the backup at [uri] back: the library is restored by Mihon's own restore (it adds and updates, never
     * deletes), the collection keeps both sides' lines, and every file is written only where nothing is yet (a
     * MANHWA file also when the backup's copy is newer).
     */
    fun import(context: Context, uri: Uri, onProgress: (Progress) -> Unit): Result {
        val vault = context.appGraph.manhwaVault
        val total = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 } ?: 0L
        var read = 0L
        var files = 0
        var chapters = 0
        var lines = 0
        var valid = false
        val root = context.filesDir.canonicalFile
        val storage = vault.storageManager
        val input = context.contentResolver.openInputStream(uri) ?: error("The file could not be opened")
        ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                onProgress(Progress(read, total, name))
                when {
                    name == ENTRY_MANIFEST -> valid = true
                    name == ENTRY_MIHON -> {
                        val file = File(context.cacheDir, "full_restore.tachibk")
                        file.outputStream().use { zip.copyTo(it, BUFFER) }
                        BackupRestoreWorker.start(context.workManager, Uri.fromFile(file), RestoreOptions())
                    }
                    name == ENTRY_COLLECTION -> {
                        lines = runCatching { vault.collection.importFrom(KeepOpen(zip)) }.getOrDefault(0)
                    }
                    name == ENTRY_PREFS -> runCatching { restorePrefs(context, zip.readBytes().decodeToString()) }
                    entry.isDirectory -> Unit
                    name.startsWith(FILES) -> {
                        val path = FullBackupRules.safePath(name.removePrefix(FILES)) ?: continue
                        val target = File(root, path).canonicalFile
                        // Never outside the app's files
                        if (!target.path.startsWith(root.path + File.separator)) continue
                        if (target.exists() && entry.time > 0 && entry.time <= target.lastModified()) continue
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it, BUFFER) }
                        if (entry.time > 0) target.setLastModified(entry.time)
                        files++
                    }
                    name.startsWith(TRANSLATIONS) -> {
                        val dir = storage.getTranslationsDirectory() ?: continue
                        if (put(dir, name.removePrefix(TRANSLATIONS), zip)) {
                            files++
                            if (name.endsWith(".json")) chapters++
                        }
                    }
                    name.startsWith(DOWNLOADS) -> {
                        val dir = storage.getDownloadsDirectory() ?: continue
                        if (put(dir, name.removePrefix(DOWNLOADS), zip)) files++
                    }
                }
                read += entry.compressedSize.takeIf { it > 0 } ?: 0L
                zip.closeEntry()
            }
        }
        if (!valid) throw NotABackupException()
        // The downloaded chapters and their translations are seen at once
        runCatching { vault.downloadCache.invalidateCache() }
        onProgress(Progress(total, total, ""))
        return Result(files, chapters, lines)
    }

    /** Writes the entry at [path] under [dir] when no file is there yet; true when written. */
    private fun put(dir: UniFile, path: String, input: InputStream): Boolean {
        val safe = FullBackupRules.safePath(path) ?: return false
        val parts = safe.split('/')
        var current = dir
        for (part in parts.dropLast(1)) {
            current = current.findFile(part)?.takeIf { it.isDirectory } ?: current.createDirectory(part) ?: return false
        }
        val name = parts.last()
        if (current.findFile(name) != null) return false
        val file = current.createFile(name) ?: return false
        file.openOutputStream().use { input.copyTo(it, BUFFER) }
        return true
    }

    /** Lets a reader that closes its stream read one entry without closing the whole backup. */
    private class KeepOpen(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    // endregion

    // region Small settings

    private fun prefsJson(context: Context): String {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val names = dir.listFiles().orEmpty().map { it.name.removeSuffix(".xml") }.filter(FullBackupRules::keepPrefs)
        val out = buildMap {
            names.forEach { name ->
                val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                put(
                    name,
                    JsonObject(
                        values.mapNotNull { (key, value) ->
                            val v = when (value) {
                                is Boolean -> JsonObject(mapOf("b" to JsonPrimitive(value)))
                                is Int -> JsonObject(mapOf("i" to JsonPrimitive(value)))
                                is Long -> JsonObject(mapOf("l" to JsonPrimitive(value)))
                                is Float -> JsonObject(mapOf("f" to JsonPrimitive(value)))
                                is String -> JsonObject(mapOf("s" to JsonPrimitive(value)))
                                is Set<*> -> JsonObject(mapOf("set" to JsonArray(value.map { JsonPrimitive(it.toString()) })))
                                else -> null
                            }
                            v?.let { key to it }
                        }.toMap(),
                    ),
                )
            }
        }
        return JsonObject(out).toString()
    }

    private fun restorePrefs(context: Context, text: String) {
        val root = Json.parseToJsonElement(text).jsonObject
        root.forEach { (name, values) ->
            if (!FullBackupRules.keepPrefs(name)) return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            (values as? JsonObject)?.forEach { (key, wrapped) ->
                // What is on the phone already stays
                if (prefs.contains(key)) return@forEach
                val v = (wrapped as? JsonObject) ?: return@forEach
                val (kind, raw) = v.entries.firstOrNull() ?: return@forEach
                val p = raw as? JsonPrimitive
                when (kind) {
                    "b" -> p?.booleanOrNull?.let { edit.putBoolean(key, it) }
                    "i" -> p?.longOrNull?.let { edit.putInt(key, it.toInt()) }
                    "l" -> p?.longOrNull?.let { edit.putLong(key, it) }
                    "f" -> p?.contentOrNull?.toFloatOrNull()?.let { edit.putFloat(key, it) }
                    "s" -> p?.contentOrNull?.let { edit.putString(key, it) }
                    "set" -> (raw as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()?.let { edit.putStringSet(key, it) }
                }
            }
            edit.apply()
        }
    }

    // endregion

    private const val FORMAT = 1
    private const val BUFFER = 1 shl 16
    private const val ENTRY_MANIFEST = "manhwa_full_backup.json"
    private const val ENTRY_MIHON = "mihon.tachibk"
    private const val ENTRY_COLLECTION = "collection.db"
    private const val ENTRY_PREFS = "prefs.json"
    private const val FILES = "files/"
    private const val TRANSLATIONS = "translations/"
    private const val DOWNLOADS = "downloads/"
}

/** The pure rules of [FullBackup] (tested without a phone). */
object FullBackupRules {

    /** Folders of the app's files left out: the phone models (gigabytes, downloaded again) and passing work. */
    private val skippedDirs = setOf("models", "datastore", "tmp", "cache")

    fun keepDir(relativePath: String): Boolean = relativePath.substringBefore('/') !in skippedDirs

    /** Unfinished downloads and temporary copies are left out. */
    fun keepFile(name: String): Boolean =
        !name.endsWith(".tmp", ignoreCase = true) && !name.endsWith(".part", ignoreCase = true) &&
            !name.endsWith(".new") && !name.endsWith("_tmp")

    /**
     * MANHWA's own small settings files: kept, but not the vault's password, the model downloads in progress, the
     * background service's state or the "what's new" seen marks (they belong to this phone only).
     */
    fun keepPrefs(name: String): Boolean =
        name.startsWith("manhwa_") && name !in setOf("manhwa_vault", "manhwa_models", "manhwa_background", "manhwa_whats_new")

    /** Already compressed: stored as it is. */
    fun packed(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heif", "jxl", "cbz", "zip", "apk", "onnx", "tflite", "task")
    }

    /** How far, 0..100 (0 while the size is not known). */
    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

    /** A relative path from the backup made safe: no empty, "." or ".." parts, no absolute path; null when unsafe. */
    fun safePath(path: String): String? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }
}
