package eu.kanade.tachiyomi.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.common.i18n.stringResource
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * MANHWA music: the reader's own songs (imported from the phone, kept inside the app), each with its picture, the
 * plate its title sits on and whether its card has the chrome frame; and which songs play on which work, how they
 * follow each other, and the scene behind the work's page. One JSON file, written whole on its own thread.
 */
class MusicStore private constructor(private val context: Context) {

    @Serializable
    data class Track(
        val id: String,
        val title: String,
        val artist: String = "",
        val file: String,
        val cover: String? = null,
        /** The plate under the title: THORN, CRYSTAL, GOLD or NONE. */
        val plate: String = "THORN",
        val chrome: Boolean = true,
        val durationMs: Long = 0L,
        /** The end that is not played (TikTok's sound at the end of its videos). */
        val trimEndMs: Long = 0L,
    )

    @Serializable
    data class WorkMusic(
        val tracks: List<String> = emptyList(),
        /** NEXT (one after the other), ONE (the same song again) or SHUFFLE. */
        val mode: String = "NEXT",
        /** The scene behind the page, or null for the one of the cover's colour. */
        val scene: String? = null,
        /** MANHWA title look: the font, the plate and how the cover moves (null: the work's own default). */
        val titleFont: String? = null,
        val titlePlate: String? = null,
        val motion: String? = null,
        /** MANHWA effects: the one behind the cover, the one over the page, their colour (null: the cover's). */
        val coverEffect: String? = null,
        val pageEffect: String? = null,
        val effectColor: String? = null,
        /** Whether the effects pulse with the song. */
        val effectBeat: Boolean = true,
    )

    @Serializable
    data class Library(
        val tracks: List<Track> = emptyList(),
        val works: Map<String, WorkMusic> = emptyMap(),
        val autoplay: Boolean = true,
        val volume: Float = 0.7f,
        /** The reader's own background pictures (file names in the scenes folder). */
        val customScenes: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "music").apply { mkdirs() }
    private val audioDir = File(dir, "audio").apply { mkdirs() }
    private val artDir = File(dir, "art").apply { mkdirs() }
    private val sceneDir = File(dir, "scenes").apply { mkdirs() }
    private val file = File(dir, "library.json")
    private val writer = Executors.newSingleThreadExecutor()

    private val _library = MutableStateFlow(read())
    val library: StateFlow<Library> = _library.asStateFlow()

    private fun lib() = _library.value

    private fun looksLikeId(text: String) = text.trim().matches(Regex("[0-9a-fA-F_-]{16,}"))

    fun setTitleLook(mangaId: Long, font: String? = work(mangaId).titleFont, plate: String? = work(mangaId).titlePlate, motion: String? = work(mangaId).motion) =
        changeWork(mangaId) { it.copy(titleFont = font, titlePlate = plate, motion = motion) }

    fun setEffects(
        mangaId: Long,
        cover: String? = work(mangaId).coverEffect,
        page: String? = work(mangaId).pageEffect,
        color: String? = work(mangaId).effectColor,
        beat: Boolean = work(mangaId).effectBeat,
    ) = changeWork(mangaId) { it.copy(coverEffect = cover, pageEffect = page, effectColor = color, effectBeat = beat) }

    fun lookFlow(mangaId: Long): Flow<WorkMusic> = library.map { it.works[mangaId.toString()] ?: WorkMusic() }.distinctUntilChanged()

    fun work(mangaId: Long): WorkMusic = _library.value.works[mangaId.toString()] ?: WorkMusic()

    fun tracksOf(mangaId: Long): List<Track> {
        val lib = _library.value
        return work(mangaId).tracks.mapNotNull { id -> lib.tracks.firstOrNull { it.id == id } }
    }

    fun sceneFlow(mangaId: Long): Flow<String?> = library.map { it.works[mangaId.toString()]?.scene }.distinctUntilChanged()

    fun audioFile(track: Track) = File(audioDir, track.file)

    fun coverFile(track: Track) = track.cover?.let { File(artDir, it) }?.takeIf { it.exists() }

    /** Copies the song at [uri] into the app (with its own picture when the file carries one) and adds it to [mangaId]. */
    suspend fun import(uri: Uri, mangaId: Long?): Track? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "song"
            val ext = name.substringAfterLast('.', "mp3").take(5).lowercase()
            val id = UUID.randomUUID().toString()
            val isVideo = resolver.getType(uri)?.startsWith("video/") == true || ext in VIDEO_EXTENSIONS
            var target = File(audioDir, "$id.$ext")
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
            // MANHWA: a video (a TikTok clip, say) becomes a song: its sound is taken out as it is (no re-encoding), a
            // frame of it becomes the song's picture, and the video itself is not kept
            var videoFrame: ByteArray? = null
            if (isVideo) {
                videoFrame = VideoToAudio.frame(target)
                val audio = File(audioDir, "$id.m4a")
                if (VideoToAudio.extract(target, audio)) {
                    target.delete()
                    target = audio
                }
            }
            var title = name.substringBeforeLast('.')
            var artist = ""
            var duration = 0L
            var cover: String? = null
            runCatching {
                MediaMetadataRetriever().apply {
                    setDataSource(target.absolutePath)
                    // TikTok and other apps put an id (a long run of hex) as the title: that is no name
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() && !looksLikeId(it) }?.let { title = it }
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                    duration = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    (embeddedPicture ?: videoFrame)?.let { bytes ->
                        File(artDir, "$id.img").writeBytes(bytes)
                        cover = "$id.img"
                    }
                    release()
                }
            }
            if (looksLikeId(title)) title = context.stringResource(tachiyomi.i18n.at.ATMR.strings.music_sound, lib().tracks.size + 1)
            // A video's last seconds are cut off: TikTok puts its own sound there
            val trim = if (isVideo && duration > VIDEO_END_TRIM_MS * 2) VIDEO_END_TRIM_MS else 0L
            val track = Track(id = id, title = title, artist = artist, file = target.name, cover = cover, durationMs = duration, trimEndMs = trim)
            save { lib ->
                val works = if (mangaId != null) {
                    val key = mangaId.toString()
                    val w = lib.works[key] ?: WorkMusic()
                    lib.works + (key to w.copy(tracks = w.tracks + id))
                } else {
                    lib.works
                }
                lib.copy(tracks = lib.tracks + track, works = works)
            }
            track
        }.onFailure { logcat(LogPriority.ERROR, it) { "Could not import a song" } }.getOrNull()
    }

    /** A picture of the reader's own for [trackId]. */
    suspend fun setCover(trackId: String, uri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            val name = "$trackId-${System.currentTimeMillis()}.img"
            context.contentResolver.openInputStream(uri)?.use { input -> File(artDir, name).outputStream().use { input.copyTo(it) } }
            val old = _library.value.tracks.firstOrNull { it.id == trackId }?.cover
            edit(trackId) { it.copy(cover = name) }
            if (old != null && old != name) File(artDir, old).delete()
        }.onFailure { logcat(LogPriority.ERROR, it) { "Could not set a song picture" } }
    }

    fun edit(trackId: String, change: (Track) -> Track) = save { lib ->
        lib.copy(tracks = lib.tracks.map { if (it.id == trackId) change(it) else it })
    }

    /** Removes the song from the app (its file too) and from every work. */
    fun delete(trackId: String) {
        val track = _library.value.tracks.firstOrNull { it.id == trackId } ?: return
        save { lib ->
            lib.copy(
                tracks = lib.tracks.filterNot { it.id == trackId },
                works = lib.works.mapValues { (_, w) -> w.copy(tracks = w.tracks - trackId) },
            )
        }
        writer.execute {
            audioFile(track).delete()
            track.cover?.let { File(artDir, it).delete() }
        }
    }

    /** A scene value naming one of the reader's own pictures. */
    fun sceneFile(scene: String?): File? =
        scene?.takeIf { it.startsWith(CUSTOM) }?.let { File(sceneDir, it.removePrefix(CUSTOM)) }?.takeIf { it.exists() }

    /** Adds a picture from the phone as a background (kept in the app); its scene value, or null. */
    suspend fun addScene(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = "${UUID.randomUUID()}.img"
            context.contentResolver.openInputStream(uri)?.use { input -> File(sceneDir, name).outputStream().use { input.copyTo(it) } }
                ?: return@runCatching null
            save { it.copy(customScenes = it.customScenes + name) }
            CUSTOM + name
        }.onFailure { logcat(LogPriority.ERROR, it) { "Could not add a background" } }.getOrNull()
    }

    /** Removes one of the reader's pictures; works that used it go back to their colour's scene. */
    fun deleteScene(scene: String) {
        val name = scene.removePrefix(CUSTOM)
        save { lib ->
            lib.copy(
                customScenes = lib.customScenes - name,
                works = lib.works.mapValues { (_, w) -> if (w.scene == scene) w.copy(scene = null) else w },
            )
        }
        writer.execute { File(sceneDir, name).delete() }
    }

    fun setOnWork(mangaId: Long, trackId: String, on: Boolean) = changeWork(mangaId) { w ->
        w.copy(tracks = if (on) (w.tracks - trackId) + trackId else w.tracks - trackId)
    }

    fun setMode(mangaId: Long, mode: String) = changeWork(mangaId) { it.copy(mode = mode) }

    fun setScene(mangaId: Long, scene: String?) = changeWork(mangaId) { it.copy(scene = scene) }

    fun setAutoplay(on: Boolean) = save { it.copy(autoplay = on) }

    fun setVolume(volume: Float) = save { it.copy(volume = volume.coerceIn(0f, 1f)) }

    private fun changeWork(mangaId: Long, change: (WorkMusic) -> WorkMusic) = save { lib ->
        val key = mangaId.toString()
        lib.copy(works = lib.works + (key to change(lib.works[key] ?: WorkMusic())))
    }

    @Synchronized
    private fun save(change: (Library) -> Library) {
        val next = change(_library.value)
        _library.value = next
        writer.execute {
            runCatching {
                val tmp = File(dir, "library.json.tmp")
                tmp.writeText(json.encodeToString(Library.serializer(), next))
                if (!tmp.renameTo(file)) {
                    file.writeText(json.encodeToString(Library.serializer(), next))
                    tmp.delete()
                }
            }.onFailure { logcat(LogPriority.ERROR, it) { "Could not save the music library" } }
        }
    }

    private fun read(): Library = runCatching {
        if (file.exists()) json.decodeFromString(Library.serializer(), file.readText()) else Library()
    }.getOrElse {
        logcat(LogPriority.ERROR, it) { "Could not read the music library" }
        Library()
    }

    companion object {
        const val CUSTOM = "CUSTOM:"

        private const val VIDEO_END_TRIM_MS = 4_000L

        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "3gp", "mkv")

        @Volatile
        private var instance: MusicStore? = null

        fun get(context: Context): MusicStore = instance ?: synchronized(this) {
            instance ?: MusicStore(context.applicationContext).also { instance = it }
        }
    }
}
