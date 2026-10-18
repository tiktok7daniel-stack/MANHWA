package eu.kanade.tachiyomi.data.backup.vault

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
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

    data class Options(
        val allDownloads: Boolean = false,
        /** The translated chapters that are not downloaded get their pages downloaded first, so they travel too. */
        val translatedPages: Boolean = false,
    )

    data class Progress(val done: Long, val total: Long, val name: String)

    data class Result(val files: Int, val chapters: Int, val lines: Int)

    class NotABackupException : Exception("Not a MANHWA full backup")

    const val EXTENSION = "manhwabackup"

    // region Export

    suspend fun export(context: Context, uri: Uri, options: Options, onProgress: (Progress) -> Unit): Result {
        val vault = context.appGraph.manhwaVault
        // The ids every MANHWA file uses, found before anything is written (and before the pages are fetched)
        val used = usedIds(context)
        if (options.translatedPages) {
            runCatching { downloadTranslated(context, used.first) { onProgress(Progress(0, 0, it)) } }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else logcat(LogPriority.WARN, it) { "Translated pages not all fetched" } }
        }
        val idMap = runCatching { buildIdMap(vault, used) }
            .onFailure { logcat(LogPriority.WARN, it) { "Id map of the full backup incomplete" } }
            .getOrDefault(IdMap())
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
            // 4. The map of the ids the files below use (before them: the restore reads it first)
            zip.putNextEntry(ZipEntry(ENTRY_IDS))
            zip.write(idJson.encodeToString(IdMap.serializer(), idMap).toByteArray())
            zip.closeEntry()
            // 5. Every file: the app's own, the translations and the downloaded pages
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

    private val idJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The work and chapter ids the app's files use (fusions, glossaries, edits...). */
    private fun usedIds(context: Context): Pair<Set<Long>, Set<Long>> {
        val root = context.filesDir
        val mangas = HashSet<Long>()
        val chapters = HashSet<Long>()
        root.walkTopDown()
            .onEnter { dir -> dir == root || FullBackupRules.keepDir(dir.relativeTo(root).invariantSeparatorsPath) }
            .filter { it.isFile }
            .forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val content = if (IdRemapRules.holdsIds(path)) runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() else null
                val (m, c) = IdRemapRules.idsOf(path, content)
                mangas += m
                chapters += c
            }
        return mangas to chapters
    }

    /** Each used id with what finds it again on another phone; a work not in the library travels whole. */
    private suspend fun buildIdMap(vault: ManhwaVault, used: Pair<Set<Long>, Set<Long>>): IdMap {
        val chapterRefs = used.second.mapNotNull { id ->
            runCatching { vault.chapterRepository.getChapterById(id) }.getOrNull()?.let { IdMap.ChapterRef(it.id, it.mangaId, it.url) }
        }
        val mangaIds = used.first + chapterRefs.map { it.mangaId }
        val mangas = mangaIds.mapNotNull { id ->
            val manga = runCatching { vault.mangaRepository.getMangaById(id) }.getOrNull() ?: return@mapNotNull null
            if (manga.favorite) {
                IdMap.MangaRef(manga.id, manga.source, manga.url, manga.title, favorite = true)
            } else {
                // Not in the library, so not in Mihon's backup: its details and chapters go with the map
                val chapters = runCatching { vault.chapterRepository.getChapterByMangaId(manga.id) }.getOrDefault(emptyList())
                IdMap.MangaRef(
                    manga.id, manga.source, manga.url, manga.title, favorite = false,
                    details = IdMap.Details(manga.artist, manga.author, manga.description, manga.genre, manga.status, manga.thumbnailUrl),
                    chapters = chapters.map {
                        IdMap.ChapterData(
                            it.url, it.name, it.chapterNumber, it.scanlator, it.read, it.bookmark, it.lastPageRead.toLong(),
                            it.sourceOrder, it.dateUpload?.toEpochMilliseconds(),
                        )
                    },
                )
            }
        }
        return IdMap(mangas, chapterRefs)
    }

    /**
     * Downloads the pages of every translated chapter that is not downloaded (the library's works and the works
     * the fusions use), with the app's own downloader, and waits for them: the backup then holds them too.
     */
    private suspend fun downloadTranslated(context: Context, usedMangas: Set<Long>, onStep: (String) -> Unit) {
        val graph = context.appGraph
        val vault = graph.manhwaVault
        val downloads = graph.downloadManager
        val mangas = (vault.mangaRepository.getFavorites() + usedMangas.mapNotNull { runCatching { vault.mangaRepository.getMangaById(it) }.getOrNull() })
            .distinctBy { it.id }
        val waiting = mutableListOf<Long>()
        for (manga in mangas) {
            val source = graph.sourceManager.getOrStub(manga.source)
            val translated = runCatching { graph.translationManager.getTranslatedFileNames(manga, source) }.getOrDefault(emptySet())
            if (translated.isEmpty()) continue
            val chapters = vault.chapterRepository.getChapterByMangaId(manga.id).filter { chapter ->
                graph.translationManager.getTranslationFileName(chapter) in translated &&
                    !downloads.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.source)
            }
            if (chapters.isEmpty()) continue
            onStep(manga.title)
            downloads.downloadChapters(manga, chapters)
            waiting += chapters.map { it.id }
        }
        if (waiting.isEmpty()) return
        // Until every one is done or failed; a download that stops moving for a long while (no network) is not waited for
        var left = waiting.size
        var still = 0
        while (left > 0 && still < FullBackupRules.STALL_STEPS) {
            kotlinx.coroutines.delay(FullBackupRules.WAIT_STEP_MS)
            val now = waiting.count { downloads.getQueuedDownloadOrNull(it) != null }
            still = if (now < left) 0 else still + 1
            left = now
            onStep("${waiting.size - left}/${waiting.size}")
        }
    }

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
    suspend fun import(context: Context, uri: Uri, onProgress: (Progress) -> Unit): Result {
        val vault = context.appGraph.manhwaVault
        // Old ids to the ids on this phone, once the map is read (a backup without one keeps the names as they are)
        var mangaIds: Map<Long, Long>? = null
        var chapterIds: Map<Long, Long> = emptyMap()
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
                        // Restored now, before the ids are looked for: every work and chapter is then on the phone
                        try {
                            vault.backupRestorerFactory.create(vault.backupNotifier, isSync = false).restore(Uri.fromFile(file), RestoreOptions())
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            logcat(LogPriority.WARN, e) { "Library restore of the full backup failed" }
                        } finally {
                            file.delete()
                        }
                    }
                    name == ENTRY_IDS -> {
                        val map = runCatching { idJson.decodeFromString(IdMap.serializer(), zip.readBytes().decodeToString()) }.getOrNull()
                        if (map != null) {
                            val found = resolve(vault, map)
                            mangaIds = found.first
                            chapterIds = found.second
                        }
                    }
                    name == ENTRY_COLLECTION -> {
                        lines = runCatching { vault.collection.importFrom(KeepOpen(zip)) }.getOrDefault(0)
                    }
                    name == ENTRY_PREFS -> runCatching { restorePrefs(context, zip.readBytes().decodeToString()) }
                    entry.isDirectory -> Unit
                    name.startsWith(FILES) -> {
                        val original = FullBackupRules.safePath(name.removePrefix(FILES)) ?: continue
                        val manga = mangaIds
                        // The file named after a work or a chapter takes its id on this phone (left out when it is not here)
                        val path = if (manga == null) original else IdRemapRules.renamed(original, manga::get, chapterIds::get) ?: continue
                        val target = File(root, path).canonicalFile
                        // Never outside the app's files
                        if (!target.path.startsWith(root.path + File.separator)) continue
                        if (target.exists() && entry.time > 0 && entry.time <= target.lastModified()) continue
                        target.parentFile?.mkdirs()
                        if (manga != null && IdRemapRules.holdsIds(path)) {
                            // The ids inside the file are written again too
                            val text = zip.readBytes().decodeToString()
                            val remapped = runCatching {
                                IdRemapRules.remap(path, Json.parseToJsonElement(text), manga::get, chapterIds::get).toString()
                            }.getOrNull() ?: continue
                            target.writeText(remapped)
                        } else {
                            target.outputStream().use { zip.copyTo(it, BUFFER) }
                        }
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

    /**
     * Finds each work and chapter of [map] on this phone by its source and address, adding back the works that were
     * not in the library (with their chapters). Returns old id to new id, for works and for chapters.
     */
    private suspend fun resolve(vault: ManhwaVault, map: IdMap): Pair<Map<Long, Long>, Map<Long, Long>> {
        val mangas = HashMap<Long, Long>()
        for (ref in map.mangas) {
            val here = runCatching { vault.mangaRepository.getMangaByUrlAndSourceId(ref.url, ref.source) }.getOrNull()
                ?: ref.details?.let { d -> addBack(vault, ref, d) }
                ?: continue
            mangas[ref.id] = here.id
        }
        val chapters = HashMap<Long, Long>()
        for (ref in map.chapters) {
            val manga = mangas[ref.mangaId] ?: continue
            runCatching { vault.chapterRepository.getChapterByUrlAndMangaId(ref.url, manga) }.getOrNull()?.let { chapters[ref.id] = it.id }
        }
        return mangas to chapters
    }

    /** A work of the backup that is not in the library here (a fusion's continuation): added, with its chapters. */
    private suspend fun addBack(vault: ManhwaVault, ref: IdMap.MangaRef, d: IdMap.Details): tachiyomi.domain.manga.model.Manga? = runCatching {
        val manga = vault.mangaRepository.insertNetworkManga(
            listOf(
                tachiyomi.domain.manga.model.Manga.create().copy(
                    source = ref.source, url = ref.url, title = ref.title, artist = d.artist, author = d.author,
                    description = d.description, genre = d.genre, status = d.status, thumbnailUrl = d.thumbnailUrl,
                    initialized = true,
                ),
            ),
        ).firstOrNull() ?: return@runCatching null
        val have = vault.chapterRepository.getChapterByMangaId(manga.id).map { it.url }.toSet()
        val now = kotlin.time.Clock.System.now()
        val added = ref.chapters.filter { it.url !in have }.map { c ->
            tachiyomi.domain.chapter.model.Chapter.create().copy(
                mangaId = manga.id, url = c.url, name = c.name, chapterNumber = c.number, scanlator = c.scanlator,
                read = c.read, bookmark = c.bookmark, lastPageRead = c.lastPageRead.toInt(), sourceOrder = c.sourceOrder,
                dateUpload = c.dateUpload?.let { kotlin.time.Instant.fromEpochMilliseconds(it) }, dateFetch = now,
            )
        }
        if (added.isNotEmpty()) vault.chapterRepository.updateFromRemote(emptyList(), added, emptyList())
        manga
    }.onFailure { logcat(LogPriority.WARN, it) { "${ref.title} not added back" } }.getOrNull()

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
    private const val ENTRY_IDS = "ids.json"
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

    /** Fetching translated pages: checked every few seconds, and given up after this many checks without progress. */
    const val WAIT_STEP_MS = 3_000L
    const val STALL_STEPS = 200

    /** How far, 0..100 (0 while the size is not known). */
    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

    /** A relative path from the backup made safe: no empty, "." or ".." parts, no absolute path; null when unsafe. */
    fun safePath(path: String): String? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }
}
