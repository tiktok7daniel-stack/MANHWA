package eu.kanade.tachiyomi.novel

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.pm.PackageInfoCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
import ireader.core.source.CatalogSource
import ireader.core.source.Dependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.data.dalvik.DelegateLastClassLoaderCompat
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** A novel extension on the phone: one source, from IReader's extension format. */
data class NovelExt(
    val pkg: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val lang: String,
    val nsfw: Boolean,
    val source: CatalogSource,
    val icon: Drawable?,
    val shared: Boolean,
)

/** A novel extension a repository offers. */
data class AvailableNovelExt(
    val pkg: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val lang: String,
    val nsfw: Boolean,
    val description: String,
    val apkUrl: String,
    val iconUrl: String,
)

/**
 * MANHWA novels: IReader's novel extensions, installed from the repositories the reader adds, kept in the app's own
 * folder (no system install prompt) and loaded as IReader loads them: the class named in `source.class`, built with
 * the [Dependencies] (network and settings) the app gives it. Extensions installed on the phone as apps load too.
 */
@Inject
@SingleIn(AppScope::class)
class NovelExtensionManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: SourcePreferences,
    private val network: NetworkHelper,
    private val json: Json,
) {

    private val _installed = MutableStateFlow<List<NovelExt>>(emptyList())
    val installed: StateFlow<List<NovelExt>> = _installed.asStateFlow()

    private val _available = MutableStateFlow<List<AvailableNovelExt>>(emptyList())
    val available: StateFlow<List<AvailableNovelExt>> = _available.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val dir get() = File(context.filesDir, "novel_exts").apply { mkdirs() }
    private val lock = Mutex()
    private val clients by lazy { NovelHttpClients(network.client) }

    init {
        scope.launch(Dispatchers.IO) {
            // IReader's own repository is there from the start, once: a reader who removes it keeps it removed
            if (!preferences.novelRepoSeeded.get()) {
                preferences.novelRepos.set(preferences.novelRepos.get() + IREADER_REPO)
                preferences.novelRepoSeeded.set(true)
            }
            reload()
            refreshAvailable()
        }
    }

    /** Every loaded novel source. */
    fun sources(): List<CatalogSource> = _installed.value.map { it.source }

    fun source(id: Long): CatalogSource? = sources().firstOrNull { it.id == id }

    fun extensionOf(sourceId: Long): NovelExt? = _installed.value.firstOrNull { it.source.id == sourceId }

    suspend fun reload() = lock.withLock {
        withContext(Dispatchers.IO) {
            _installed.value = runCatching { loadAll() }
                .onFailure { logcat(LogPriority.ERROR, it) { "Novel extensions not loaded" } }
                .getOrDefault(emptyList())
        }
    }

    // ---- Repositories ----

    fun repos(): Set<String> = preferences.novelRepos.get()

    fun addRepo(url: String): Boolean {
        val clean = normalizeRepo(url) ?: return false
        preferences.novelRepos.set(preferences.novelRepos.get() + clean)
        scope.launch(Dispatchers.IO) { refreshAvailable() }
        return true
    }

    fun removeRepo(url: String) {
        preferences.novelRepos.set(preferences.novelRepos.get() - url)
        scope.launch(Dispatchers.IO) { refreshAvailable() }
    }

    /** What every repository offers now. */
    suspend fun refreshAvailable() = withContext(Dispatchers.IO) {
        _loading.value = true
        try {
            _available.value = repos().flatMap { repo ->
                runCatching { fetchIndex(repo) }
                    .onFailure { logcat(LogPriority.WARN, it) { "Novel repo $repo not read" } }
                    .getOrDefault(emptyList())
            }.distinctBy { it.pkg }.sortedWith(compareBy({ it.lang != "ar" }, { it.name.lowercase() }))
        } finally {
            _loading.value = false
        }
    }

    private suspend fun fetchIndex(repo: String): List<AvailableNovelExt> {
        val body = network.client.newCall(GET("$repo/index.min.json")).awaitSuccess().use { it.body.string() }
        return json.decodeFromString<List<IndexEntry>>(body).map {
            AvailableNovelExt(
                pkg = it.pkg,
                name = it.name,
                versionCode = it.code,
                versionName = it.version,
                lang = it.lang,
                nsfw = it.nsfw,
                description = it.description,
                apkUrl = "$repo/apk/${it.apk}",
                iconUrl = "$repo/icon/${it.pkg}.png",
            )
        }
    }

    @Serializable
    private data class IndexEntry(
        val name: String,
        val pkg: String,
        val apk: String,
        val lang: String = "",
        val code: Long = 0,
        val version: String = "",
        val nsfw: Boolean = false,
        val description: String = "",
    )

    // ---- Install ----

    /** Downloads [ext] and loads it (a newer version replaces the older one). */
    suspend fun install(ext: AvailableNovelExt, reloadAfter: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "${ext.pkg}.apk")
        try {
            network.client.newCall(GET(ext.apkUrl)).awaitSuccess().use { response ->
                tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            val info = context.packageManager.getPackageArchiveInfo(tmp.absolutePath, FLAGS)
                ?.takeIf { isExtension(it) } ?: error("Not a novel extension")
            check(info.packageName == ext.pkg) { "Package ${info.packageName} is not ${ext.pkg}" }
            val target = File(dir, "${ext.pkg}.$EXT")
            target.delete()
            tmp.copyAndSetReadOnlyTo(target, overwrite = true)
            if (!reloadAfter) return@withContext true
            reload()
            _installed.value.any { it.pkg == ext.pkg }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Novel extension ${ext.pkg} not installed" }
            false
        } finally {
            tmp.delete()
        }
    }

    /**
     * Installs every extension the repositories offer in [langs] that is not on the phone yet (18+ ones only when
     * [nsfw]), one after another. [onProgress] gets how many are done of how many; returns how many installed.
     */
    suspend fun installAll(langs: Set<String>, nsfw: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val have = _installed.value.map { it.pkg }.toSet()
        val todo = _available.value.filter { it.pkg !in have && it.lang in langs && (nsfw || !it.nsfw) }
        todo.forEachIndexed { i, ext ->
            onProgress(i, todo.size)
            install(ext, reloadAfter = false)
        }
        // Loaded once at the end, not after each one
        reload()
        onProgress(todo.size, todo.size)
        val wanted = todo.map { it.pkg }.toSet()
        return _installed.value.count { it.pkg in wanted }
    }

    suspend fun uninstall(pkg: String) {
        withContext(Dispatchers.IO) { File(dir, "$pkg.$EXT").delete() }
        reload()
    }

    /** Installed extensions a repository has a newer version of. */
    fun updates(): Map<String, AvailableNovelExt> {
        val byPkg = _available.value.associateBy { it.pkg }
        return _installed.value.mapNotNull { ext ->
            byPkg[ext.pkg]?.takeIf { it.versionCode > ext.versionCode }?.let { ext.pkg to it }
        }.toMap()
    }

    // ---- Loading (as IReader's catalog loader) ----

    @SuppressLint("QueryPermissionsNeeded")
    private fun loadAll(): List<NovelExt> {
        val pm = context.packageManager
        val shared = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(FLAGS).filter { isExtension(it) }
        }.getOrDefault(emptyList())
        val private = dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == EXT }
            .mapNotNull { file ->
                if (file.canWrite()) file.setReadOnly()
                pm.getPackageArchiveInfo(file.absolutePath, FLAGS)
                    ?.also { it.applicationInfo?.fixBasePaths(file.absolutePath) }
                    ?.takeIf { isExtension(it) }
            }
        val all = (shared.map { it to true } + private.map { it to false })
            .groupBy { it.first.packageName }
            .map { (_, both) -> both.maxBy { PackageInfoCompat.getLongVersionCode(it.first) } }
        return all.mapNotNull { (info, isShared) -> load(info, isShared) }
    }

    private fun load(info: PackageInfo, shared: Boolean): NovelExt? {
        val pm = context.packageManager
        val app = info.applicationInfo ?: return null
        val meta = app.metaData ?: return null
        val versionName = info.versionName ?: return null
        val lib = versionName.substringBefore('.').toIntOrNull()
        if (lib == null || lib !in LIB_VERSIONS) {
            logcat(LogPriority.WARN) { "Novel extension ${info.packageName}: lib $lib not supported" }
            return null
        }
        val cls = meta.getString(META_CLASS)?.trim()?.let { if (it.startsWith(".")) info.packageName + it else it } ?: return null
        val source = try {
            val loader = DelegateLastClassLoaderCompat(app.sourceDir, null, context.classLoader)
            val deps = Dependencies(clients, NovelPreferenceStore(context, info.packageName))
            Class.forName(cls, false, loader).getConstructor(Dependencies::class.java).newInstance(deps) as? CatalogSource
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Novel extension ${info.packageName}: $cls not loaded" }
            null
        } ?: return null
        return NovelExt(
            pkg = info.packageName,
            name = source.name,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = versionName,
            lang = source.lang,
            nsfw = meta.getInt(META_NSFW, 0) == 1,
            source = source,
            icon = runCatching { app.loadIcon(pm) }.getOrNull(),
            shared = shared,
        )
    }

    private fun isExtension(info: PackageInfo): Boolean = info.reqFeatures.orEmpty().any { it.name == FEATURE }

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) sourceDir = apkPath
        if (publicSourceDir == null) publicSourceDir = apkPath
    }

    companion object {
        /** IReader's own extensions repository: added once on first start, and offered in one tap after. */
        const val IREADER_REPO = "https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2"

        private const val FEATURE = "ireader"
        private const val META_CLASS = "source.class"
        private const val META_NSFW = "source.nsfw"
        private const val EXT = "ext"
        private val LIB_VERSIONS = 1..2

        private const val FLAGS = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA

        /** A repository address as the app keeps it: no trailing index file or slash. */
        fun normalizeRepo(url: String): String? {
            val u = url.trim().removeSuffix("/index.min.json").removeSuffix("/index.json").trimEnd('/')
            return u.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
    }
}
