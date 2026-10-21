package eu.kanade.tachiyomi.anime

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
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

/** An anime extension on the phone (installed by the app, or as an app of its own). */
data class AnimeExt(
    val pkg: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val lang: String,
    val nsfw: Boolean,
    val sources: List<AnimeSource>,
    val icon: Drawable?,
    val shared: Boolean,
)

/** An anime extension a repository offers. */
data class AvailableAnimeExt(
    val pkg: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val lang: String,
    val nsfw: Boolean,
    val apkUrl: String,
    val iconUrl: String,
)

/**
 * MANHWA anime: the anime extensions of Aniyomi (`tachiyomi.animeextension`), installed from the repositories the
 * reader adds, kept in the app's own folder (no system install prompt), and loaded like Aniyomi loads them. Apps
 * installed on the phone as anime extensions are loaded too.
 */
@Inject
@SingleIn(AppScope::class)
class AnimeExtensionManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: SourcePreferences,
    private val network: NetworkHelper,
    private val json: Json,
) {

    private val _installed = MutableStateFlow<List<AnimeExt>>(emptyList())
    val installed: StateFlow<List<AnimeExt>> = _installed.asStateFlow()

    private val _available = MutableStateFlow<List<AvailableAnimeExt>>(emptyList())
    val available: StateFlow<List<AvailableAnimeExt>> = _available.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val dir get() = File(context.filesDir, "anime_exts").apply { mkdirs() }
    private val lock = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            reload()
            refreshAvailable()
        }
    }

    /** Every anime source of the loaded extensions. */
    fun sources(): List<AnimeCatalogueSource> = _installed.value.flatMap { it.sources }.filterIsInstance<AnimeCatalogueSource>()

    fun source(id: Long): AnimeCatalogueSource? = sources().firstOrNull { it.id == id }

    fun extensionOf(sourceId: Long): AnimeExt? = _installed.value.firstOrNull { ext -> ext.sources.any { it.id == sourceId } }

    suspend fun reload() = lock.withLock {
        withContext(Dispatchers.IO) {
            _installed.value = runCatching { loadAll() }
                .onFailure { logcat(LogPriority.ERROR, it) { "Anime extensions not loaded" } }
                .getOrDefault(emptyList())
        }
    }

    // ---- Repositories ----

    fun repos(): Set<String> = preferences.animeRepos.get()

    fun addRepo(url: String): Boolean {
        val clean = normalizeRepo(url) ?: return false
        preferences.animeRepos.set(preferences.animeRepos.get() + clean)
        scope.launch(Dispatchers.IO) { refreshAvailable() }
        return true
    }

    fun removeRepo(url: String) {
        preferences.animeRepos.set(preferences.animeRepos.get() - url)
        scope.launch(Dispatchers.IO) { refreshAvailable() }
    }

    /** What every repository offers now. */
    suspend fun refreshAvailable() = withContext(Dispatchers.IO) {
        _loading.value = true
        try {
            _available.value = repos().flatMap { repo ->
                runCatching { fetchIndex(repo) }
                    .onFailure { logcat(LogPriority.WARN, it) { "Anime repo $repo not read" } }
                    .getOrDefault(emptyList())
            }.distinctBy { it.pkg }.sortedBy { it.name.lowercase() }
        } finally {
            _loading.value = false
        }
    }

    private suspend fun fetchIndex(repo: String): List<AvailableAnimeExt> {
        val body = network.client.newCall(GET("$repo/index.min.json")).awaitSuccess().use { it.body.string() }
        return json.decodeFromString<List<IndexEntry>>(body).map {
            AvailableAnimeExt(
                pkg = it.pkg,
                name = it.name.substringAfter("Aniyomi: "),
                versionCode = it.code,
                versionName = it.version,
                lang = it.lang,
                nsfw = it.nsfw == 1,
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
        val code: Long,
        val version: String,
        val nsfw: Int = 0,
    )

    // ---- Install ----

    /** Downloads [ext] and loads it (the newer version replaces the older one). */
    suspend fun install(ext: AvailableAnimeExt): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "${ext.pkg}.apk")
        try {
            network.client.newCall(GET(ext.apkUrl)).awaitSuccess().use { response ->
                tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            val info = context.packageManager.getPackageArchiveInfo(tmp.absolutePath, FLAGS)
                ?.takeIf { isExtension(it) } ?: error("Not an anime extension")
            check(info.packageName == ext.pkg) { "Package ${info.packageName} is not ${ext.pkg}" }
            val target = File(dir, "${ext.pkg}.$EXT")
            target.delete()
            tmp.copyAndSetReadOnlyTo(target, overwrite = true)
            reload()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Anime extension ${ext.pkg} not installed" }
            false
        } finally {
            tmp.delete()
        }
    }

    suspend fun uninstall(pkg: String) {
        withContext(Dispatchers.IO) { File(dir, "$pkg.$EXT").delete() }
        reload()
    }

    /** Installed extensions a repository has a newer version of. */
    fun updates(): Map<String, AvailableAnimeExt> {
        val byPkg = _available.value.associateBy { it.pkg }
        return _installed.value.mapNotNull { ext -> byPkg[ext.pkg]?.takeIf { it.versionCode > ext.versionCode }?.let { ext.pkg to it } }.toMap()
    }

    // ---- Loading (as Aniyomi's AnimeExtensionLoader) ----

    @SuppressLint("QueryPermissionsNeeded")
    private fun loadAll(): List<AnimeExt> {
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
        // The newer of the two when the same extension is both
        val all = (shared.map { it to true } + private.map { it to false })
            .groupBy { it.first.packageName }
            .map { (_, both) -> both.maxBy { PackageInfoCompat.getLongVersionCode(it.first) } }
        return all.mapNotNull { (info, isShared) -> load(info, isShared) }
    }

    private fun load(info: PackageInfo, shared: Boolean): AnimeExt? {
        val pm = context.packageManager
        val app = info.applicationInfo ?: return null
        val meta = app.metaData ?: return null
        val name = meta.getString(META_NAME) ?: pm.getApplicationLabel(app).toString().substringAfter("Aniyomi: ")
        val versionName = info.versionName ?: return null
        val lib = meta.getInt(META_LIB).takeUnless { it == 0 }?.toDouble() ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (lib == null || lib !in LIB_VERSIONS) {
            logcat(LogPriority.WARN) { "Anime extension $name: lib $lib not supported" }
            return null
        }
        val nsfw = meta.getInt(META_CONTENT_WARNING) > 0 || meta.getInt(META_NSFW) == 1
        val loader = try {
            DelegateLastClassLoaderCompat(app.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Anime extension $name not loaded" }
            return null
        }
        val sources = (meta.getString(META_CLASS) ?: return null).split(";")
            .map { it.trim().let { cls -> if (cls.startsWith(".")) info.packageName + cls else cls } }
            .flatMap { cls ->
                try {
                    when (val obj = Class.forName(cls, false, loader).getDeclaredConstructor().newInstance()) {
                        is AnimeSource -> listOf(obj)
                        is AnimeSourceFactory -> obj.createSources()
                        else -> emptyList()
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Anime extension $name: $cls not loaded" }
                    emptyList()
                }
            }
        if (sources.isEmpty()) return null
        val langs = sources.map { it.lang }.toSet()
        return AnimeExt(
            pkg = info.packageName,
            name = name,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = versionName,
            lang = if (langs.size == 1) langs.first() else "all",
            nsfw = nsfw,
            sources = sources,
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
        private const val FEATURE = "tachiyomi.animeextension"
        private const val META_CLASS = "tachiyomi.animeextension.class"
        private const val META_NSFW = "tachiyomi.animeextension.nsfw"
        private const val META_NAME = "aniyomix.name"
        private const val META_LIB = "aniyomix.extensionLib"
        private const val META_CONTENT_WARNING = "aniyomix.contentWarning"
        private const val EXT = "ext"
        private val LIB_VERSIONS = listOf(14.0, 15.0, 16.0, 17.0)

        @Suppress("DEPRECATION")
        private val FLAGS = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

        /** A repository address as the app keeps it: no trailing index file or slash. */
        fun normalizeRepo(url: String): String? {
            val u = url.trim().removeSuffix("/index.min.json").removeSuffix("/index.json").removeSuffix("/repo.json").trimEnd('/')
            return u.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
    }
}
