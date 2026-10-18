package eu.kanade.tachiyomi.data.backup.vault

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.util.system.workManager
import eu.kanade.translation.collection.TranslationCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * MANHWA Vault: everything of the app in one encrypted file. The full Mihon backup (library, categories,
 * chapters, history, tracking, settings) plus what only MANHWA keeps: glossaries, story summaries, lessons,
 * bubble edits, the translation collection, the organizer log and the favorite drops. AES-256-GCM with a key
 * from the reader's password (PBKDF2, 120 000 rounds); a wrong password or a changed file never opens.
 *
 * Sync: the vault can live in a folder the reader picks (a Google Drive, OneDrive or Syncthing folder). Each
 * device writes it there and, when another device wrote a newer one, merges it first.
 */
@Inject
@SingleIn(AppScope::class)
class ManhwaVault(
    private val context: Context,
    internal val backupCreatorFactory: BackupCreator.Factory,
    internal val collection: TranslationCollection,
    /** For the full backup ([FullBackup]): where the translations and the downloaded chapters are. */
    internal val storageManager: tachiyomi.domain.storage.service.StorageManager,
    internal val downloadCache: eu.kanade.tachiyomi.data.download.DownloadCache,
    /** For the full backup: the library restored before the ids are found again, and the works it adds back. */
    internal val backupRestorerFactory: eu.kanade.tachiyomi.data.backup.restore.BackupRestorer.Factory,
    internal val backupNotifier: eu.kanade.tachiyomi.data.backup.BackupNotifier,
    internal val mangaRepository: tachiyomi.domain.manga.repository.MangaRepository,
    internal val chapterRepository: tachiyomi.domain.chapter.repository.ChapterRepository,
) {

    class WrongPasswordException : Exception("Wrong password")
    class NotAVaultException : Exception("Not a MANHWA vault")

    data class Header(val device: String, val createdAt: Long)

    data class ImportResult(val files: Int, val lines: Int)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    var folder: String?
        get() = prefs.getString(KEY_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_FOLDER, value).apply()

    /** Kept in the app's private storage only, so the automatic sync can run. */
    var password: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    val lastSync: Long get() = prefs.getLong(KEY_LAST_SYNC, 0L)

    // region Export

    /** Writes the whole vault to [uri] (a document the reader created). */
    suspend fun export(uri: Uri, password: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bytes = seal(pack(), password)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        }
    }

    private suspend fun pack(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // 1. The full Mihon backup
            val mihon = File(context.cacheDir, "vault_mihon.tachibk").apply {
                delete()
                createNewFile()
            }
            try {
                backupCreatorFactory.create(isAutoBackup = false).backup(Uri.fromFile(mihon), BackupOptions())
                zip.putNextEntry(ZipEntry(ENTRY_MIHON))
                mihon.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            } finally {
                mihon.delete()
            }
            // 2. The translation collection
            zip.putNextEntry(ZipEntry(ENTRY_COLLECTION))
            runCatching { collection.exportTo(zip) }.onFailure { logcat(LogPriority.WARN, it) { "Collection left out of the vault" } }
            zip.closeEntry()
            // 3. MANHWA's own files
            manhwaFiles().forEach { (path, file) ->
                zip.putNextEntry(ZipEntry("$FILES_PREFIX$path").apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manhwaFiles(): List<Pair<String, File>> {
        val root = context.filesDir
        val out = mutableListOf<Pair<String, File>>()
        DIRS.forEach { dir ->
            File(root, dir).walkTopDown().filter { it.isFile }.forEach { out += it.relativeTo(root).path to it }
        }
        FILES.forEach { name -> File(root, name).takeIf { it.isFile }?.let { out += name to it } }
        return out
    }

    private fun seal(plain: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { data ->
            data.write(MAGIC)
            data.writeByte(VERSION)
            data.write(salt)
            data.write(iv)
            data.writeUTF(deviceId)
            data.writeLong(System.currentTimeMillis())
        }
        // The header is bound to the content: changing the device or the date breaks the file
        val head = header.toByteArray()
        cipher.updateAAD(head)
        return head + cipher.doFinal(plain)
    }

    // endregion

    // region Import

    /** Reads only the unencrypted header of a vault: who wrote it and when. */
    fun peek(bytes: ByteArray): Header = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        if (!magic.contentEquals(MAGIC)) throw NotAVaultException()
        if (data.readByte().toInt() != VERSION) throw NotAVaultException()
        data.skipBytes(SALT_BYTES + IV_BYTES)
        Header(data.readUTF(), data.readLong())
    }

    /** Opens the vault at [uri] and merges it into the app. */
    suspend fun import(uri: Uri, password: String): ImportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            unpack(open(bytes, password))
        }
    }

    private fun open(bytes: ByteArray, password: String): ByteArray {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        if (!magic.contentEquals(MAGIC) || data.readByte().toInt() != VERSION) throw NotAVaultException()
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val iv = ByteArray(IV_BYTES).also(data::readFully)
        data.readUTF()
        data.readLong()
        val headLength = bytes.size - data.available()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(bytes, 0, headLength)
        return try {
            cipher.doFinal(bytes, headLength, bytes.size - headLength)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    /**
     * Merges: the Mihon backup is restored on top of the library (Mihon's restore adds and updates, it never
     * deletes), the collection keeps both sides' lines, and each MANHWA file is taken when it is newer than
     * the one here or missing.
     */
    private fun unpack(plain: ByteArray): ImportResult {
        var files = 0
        var lines = 0
        val root = context.filesDir.canonicalFile
        ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == ENTRY_MIHON -> {
                        val file = File(context.cacheDir, "vault_restore.tachibk")
                        file.outputStream().use { zip.copyTo(it) }
                        BackupRestoreWorker.start(context.workManager, Uri.fromFile(file), RestoreOptions())
                    }
                    entry.name == ENTRY_COLLECTION -> {
                        val bytes = zip.readBytes()
                        if (bytes.isNotEmpty()) lines = runCatching { collection.importFrom(ByteArrayInputStream(bytes)) }.getOrDefault(0)
                    }
                    entry.name.startsWith(FILES_PREFIX) -> {
                        val target = File(root, entry.name.removePrefix(FILES_PREFIX)).canonicalFile
                        // Never outside the app's files
                        if (!target.path.startsWith(root.path + File.separator)) continue
                        if (target.exists() && entry.time > 0 && entry.time <= target.lastModified()) continue
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        if (entry.time > 0) target.setLastModified(entry.time)
                        files++
                    }
                }
                zip.closeEntry()
            }
        }
        return ImportResult(files, lines)
    }

    // endregion

    // region Sync

    enum class SyncResult { NO_FOLDER, NO_PASSWORD, WROTE, MERGED_AND_WROTE, WRONG_PASSWORD, FAILED }

    /**
     * Syncs with the vault in the reader's folder: merges it first when another device wrote a newer one,
     * then writes this device's vault there.
     */
    suspend fun sync(): SyncResult {
        val folderUri = folder ?: return SyncResult.NO_FOLDER
        val password = password?.takeIf { it.isNotEmpty() } ?: return SyncResult.NO_PASSWORD
        return withContext(Dispatchers.IO) {
            try {
                val dir = UniFile.fromUri(context, Uri.parse(folderUri)) ?: return@withContext SyncResult.FAILED
                var merged = false
                dir.findFile(SYNC_NAME)?.let { existing ->
                    val bytes = existing.openInputStream().use { it.readBytes() }
                    val header = runCatching { peek(bytes) }.getOrNull()
                    if (header != null && header.device != deviceId && header.createdAt > prefs.getLong(KEY_LAST_MERGED, 0L)) {
                        mutex.withLock { unpack(open(bytes, password)) }
                        prefs.edit().putLong(KEY_LAST_MERGED, header.createdAt).apply()
                        merged = true
                    }
                }
                val file = dir.findFile(SYNC_NAME) ?: dir.createFile(SYNC_NAME) ?: return@withContext SyncResult.FAILED
                val bytes = mutex.withLock { seal(pack(), password) }
                file.openOutputStream().use { out ->
                    (out as? java.io.FileOutputStream)?.channel?.truncate(0)
                    out.write(bytes)
                }
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                if (merged) SyncResult.MERGED_AND_WROTE else SyncResult.WROTE
            } catch (e: WrongPasswordException) {
                SyncResult.WRONG_PASSWORD
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Vault sync failed" }
                SyncResult.FAILED
            }
        }
    }

    /** The automatic sync: at most every [minIntervalMs], and only once a folder and a password are set. */
    suspend fun syncIfDue(minIntervalMs: Long = DAY_MS) {
        if (folder == null || password.isNullOrEmpty()) return
        if (System.currentTimeMillis() - lastSync < minIntervalMs) return
        sync()
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** [syncIfDue] in the background, for callers that are going away (the reader closing). */
    fun syncSoon(minIntervalMs: Long = DAY_MS) {
        scope.launch { runCatching { syncIfDue(minIntervalMs) } }
    }

    // endregion

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    companion object {
        const val EXTENSION = "manhwavault"
        const val SYNC_NAME = "MANHWA.manhwavault"
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS

        private val MAGIC = byteArrayOf('M'.code.toByte(), 'N'.code.toByte(), 'H'.code.toByte(), 'V'.code.toByte())
        private const val VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val ITERATIONS = 120_000

        private const val ENTRY_MIHON = "mihon.tachibk"
        private const val ENTRY_COLLECTION = "collection.db"
        private const val FILES_PREFIX = "files/"
        private val DIRS = listOf("glossary", "story", "lessons", "translation_edits")
        private val FILES = listOf("favorite_drops.json", "fresh_chapters.json", "auto_organize_log.json")

        private const val PREFS = "manhwa_vault"
        private const val KEY_DEVICE = "device"
        private const val KEY_FOLDER = "folder"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_MERGED = "last_merged"
    }
}
