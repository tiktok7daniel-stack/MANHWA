package eu.kanade.translation.collection

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.translation.style.BubbleKind
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.time.LocalDate

/**
 * MANHWA "Collection": one translation library for every manhwa, kept in a database on storage (not in memory).
 *
 * Every line translated anywhere is saved once ([learn]); a line that comes back counts up instead of being
 * saved again. While a chapter is translated, every line found here is written at once ([lookup]) and only the
 * new ones go to the AI. Lines are matched word for word, as templates with other numbers or other names
 * ([TextKey]), or as near matches (a letter read differently), which are only handed to the AI as a suggestion.
 *
 * A line is reused as it is only when it is safe: the reader corrected it, or it is a system message, a sound
 * or a template, or it came back at least [SURE_USES] times with the same translation. A line saved with two
 * different translations (gender, context: "Are you okay?") is marked context-sensitive and is only a hint.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationCollection(private val context: Context) {

    enum class Source(val confidence: Int) { TEAM(60), EDITOR(80), USER(100) }

    enum class Match { EXACT, TEMPLATE, NEAR }

    /** A saved translation for a line: [sure] ones are written straight away, the others go to the AI as a hint. */
    class Hit(val translation: String, val sure: Boolean, val match: Match)

    /** One line and its translation, as it was translated in a chapter. */
    class Line(val text: String, val translation: String, val kind: String)

    class Entry(
        val id: Long,
        val text: String,
        val translation: String,
        val kind: String,
        val uses: Int,
        val mangas: Int,
        val sensitive: Boolean,
        val user: Boolean,
    )

    class Stats(val lines: Long, val bytes: Long, val todayLookups: Long, val todayHits: Long, val allHits: Long)

    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE segments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    src TEXT NOT NULL,
                    tgt TEXT NOT NULL,
                    key TEXT NOT NULL,
                    original TEXT NOT NULL,
                    translation TEXT NOT NULL,
                    arabic INTEGER NOT NULL DEFAULT 0,
                    kind TEXT NOT NULL DEFAULT '',
                    confidence INTEGER NOT NULL DEFAULT 60,
                    uses INTEGER NOT NULL DEFAULT 1,
                    mangas INTEGER NOT NULL DEFAULT 1,
                    last_manga INTEGER NOT NULL DEFAULT 0,
                    sensitive INTEGER NOT NULL DEFAULT 0,
                    user INTEGER NOT NULL DEFAULT 0,
                    last_used INTEGER NOT NULL DEFAULT 0
                )""",
            )
            db.execSQL("CREATE UNIQUE INDEX segments_key ON segments(src, tgt, key)")
            db.execSQL("CREATE INDEX segments_used ON segments(user, last_used)")
            db.execSQL("CREATE TABLE stats (day TEXT PRIMARY KEY, lookups INTEGER NOT NULL DEFAULT 0, hits INTEGER NOT NULL DEFAULT 0)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    /** Recently used lines, so a chapter full of repeats never asks the database twice. */
    private val cache = LruCache<String, Row>(CACHE_LINES)

    private class Row(
        val id: Long,
        val translation: String,
        val arabic: Boolean,
        val kind: String,
        val uses: Int,
        val sensitive: Boolean,
        val user: Boolean,
    )

    private fun cacheKey(src: String, tgt: String, key: String) = "$src|$tgt|$key"

    private fun find(src: String, tgt: String, key: String): Row? {
        cache.get(cacheKey(src, tgt, key))?.let { return it }
        return db.query(
            "segments",
            arrayOf("id", "translation", "arabic", "kind", "uses", "sensitive", "user"),
            "src = ? AND tgt = ? AND key = ?",
            arrayOf(src, tgt, key),
            null,
            null,
            null,
            "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            Row(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getString(3), c.getInt(4), c.getInt(5) == 1, c.getInt(6) == 1)
        }.also { cache.put(cacheKey(src, tgt, key), it) }
    }

    private fun isSure(row: Row): Boolean {
        if (row.user) return true
        if (row.sensitive) return false
        val kind = BubbleKind.parse(row.kind)
        return kind == BubbleKind.SYSTEM || kind == BubbleKind.SFX || row.uses >= SURE_USES
    }

    /**
     * The saved translation of [text] (from language [src] to [tgt]), with this line's own numbers and the
     * series' own names put in, or null when the line is new. [glossary] is the series glossary.
     */
    fun lookup(src: String, tgt: String, text: String, glossary: Map<String, String>): Hit? = runCatching {
        if (text.isBlank() || text.length > MAX_LENGTH) return null
        val keyed = TextKey.key(text, glossary)
        find(src, tgt, keyed.exact)?.let { row ->
            return Hit(row.translation, isSure(row), Match.EXACT)
        }
        if (keyed.isTemplate) {
            find(src, tgt, keyed.template)?.let { row ->
                val filled = TextKey.fill(row.translation, keyed, glossary, row.arabic) ?: return@let
                // A template is a system line or a counter by nature: sure unless it is context-sensitive
                return Hit(filled, row.user || !row.sensitive, Match.TEMPLATE)
            }
        }
        near(src, tgt, keyed.exact)?.let { return Hit(it, false, Match.NEAR) }
        null
    }.onFailure { logcat(LogPriority.WARN, it) { "Collection lookup failed" } }.getOrNull()

    /** A line read a little differently (one letter): only a suggestion for the AI. */
    private fun near(src: String, tgt: String, exact: String): String? {
        if (exact.length < NEAR_MIN_LENGTH) return null
        return db.query(
            "segments",
            arrayOf("key", "translation"),
            "src = ? AND tgt = ? AND key LIKE ? AND key NOT LIKE '%{n%' AND key NOT LIKE '%{name%'",
            arrayOf(src, tgt, exact.take(NEAR_PREFIX).replace("%", "").replace("_", "") + "%"),
            null,
            null,
            "uses DESC",
            NEAR_CANDIDATES.toString(),
        ).use { c ->
            var best: String? = null
            var bestScore = NEAR_SIMILARITY
            while (c.moveToNext()) {
                val score = TextKey.similarity(exact, c.getString(0))
                if (score >= bestScore) {
                    bestScore = score
                    best = c.getString(1)
                }
            }
            best
        }
    }

    /**
     * Saves the lines of a translated chapter. A new line is added once; a line already saved with the same
     * translation counts up; with a different one it becomes context-sensitive, unless [source] is the reader
     * (whose correction replaces it and is never overwritten by the AI).
     */
    fun learn(src: String, tgt: String, mangaId: Long, glossary: Map<String, String>, lines: List<Line>, source: Source) {
        if (lines.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                for (line in lines) {
                    val translation = line.translation.trim()
                    if (line.text.isBlank() || translation.isBlank() || line.text.length > MAX_LENGTH) continue
                    if (translation.equals(line.text.trim(), ignoreCase = true)) continue
                    val keyed = TextKey.key(line.text, glossary)
                    val template = TextKey.templateOf(translation, keyed, glossary)
                    // Saved as a template when its numbers and names are found in the translation, else word for word
                    val (key, saved) = if (keyed.isTemplate && template != null) keyed.template to template else keyed.exact to translation
                    save(src, tgt, key, line.text.trim(), saved, TextKey.arabicStyle(translation), line.kind, mangaId, source, now)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            trimIfNeeded()
        }.onFailure { logcat(LogPriority.ERROR, it) { "Could not save lines to the collection" } }
    }

    private fun save(
        src: String,
        tgt: String,
        key: String,
        original: String,
        translation: String,
        arabic: Boolean,
        kind: String,
        mangaId: Long,
        source: Source,
        now: Long,
    ) {
        val existing = db.query(
            "segments",
            arrayOf("id", "translation", "user", "last_manga", "confidence"),
            "src = ? AND tgt = ? AND key = ?",
            arrayOf(src, tgt, key),
            null,
            null,
            null,
            "1",
        ).use { c -> if (c.moveToFirst()) arrayOf(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), c.getInt(4)) else null }
        cache.remove(cacheKey(src, tgt, key))
        if (existing == null) {
            db.insert(
                "segments",
                null,
                ContentValues().apply {
                    put("src", src)
                    put("tgt", tgt)
                    put("key", key)
                    put("original", original)
                    put("translation", translation)
                    put("arabic", if (arabic) 1 else 0)
                    put("kind", kind)
                    put("confidence", source.confidence)
                    put("last_manga", mangaId)
                    put("user", if (source == Source.USER) 1 else 0)
                    put("last_used", now)
                },
            )
            return
        }
        val id = existing[0] as Long
        val sameTranslation = existing[1] as String == translation
        val byUser = existing[2] as Int == 1
        val otherManga = existing[3] as Long != mangaId
        val values = ContentValues().apply {
            put("last_used", now)
            put("last_manga", mangaId)
            if (kind.isNotBlank()) put("kind", kind)
            put("confidence", maxOf(existing[4] as Int, source.confidence))
        }
        when {
            source == Source.USER -> {
                // The reader's correction wins and stays
                values.put("translation", translation)
                values.put("arabic", if (arabic) 1 else 0)
                values.put("user", 1)
                values.put("sensitive", 0)
            }
            byUser || sameTranslation -> Unit
            source == Source.EDITOR -> {
                // The editor fixed the team's line: its version replaces it
                values.put("translation", translation)
                values.put("arabic", if (arabic) 1 else 0)
            }
            // Two different translations for the same line: it depends on who speaks, so only a hint from now on
            else -> values.put("sensitive", 1)
        }
        db.update("segments", values, "id = ?", arrayOf(id.toString()))
        db.execSQL(
            "UPDATE segments SET uses = uses + 1, mangas = mangas + ? WHERE id = ?",
            arrayOf<Any>(if (otherManga) 1 else 0, id),
        )
    }

    /** Counts what a chapter took from the collection, for "today X% came from the collection". */
    fun record(lookups: Int, hits: Int) {
        if (lookups <= 0) return
        runCatching {
            val day = LocalDate.now().toString()
            db.execSQL("INSERT OR IGNORE INTO stats(day, lookups, hits) VALUES (?, 0, 0)", arrayOf(day))
            db.execSQL("UPDATE stats SET lookups = lookups + ?, hits = hits + ? WHERE day = ?", arrayOf<Any>(lookups, hits, day))
        }
    }

    fun stats(): Stats = runCatching {
        val lines = db.compileStatement("SELECT COUNT(*) FROM segments").simpleQueryForLong()
        val day = LocalDate.now().toString()
        val (lookups, hits) = db.rawQuery("SELECT lookups, hits FROM stats WHERE day = ?", arrayOf(day)).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L
        }
        val allHits = db.compileStatement("SELECT IFNULL(SUM(hits), 0) FROM stats").simpleQueryForLong()
        Stats(lines, sizeBytes(), lookups, hits, allHits)
    }.getOrElse { Stats(0, sizeBytes(), 0, 0, 0) }

    fun sizeBytes(): Long {
        val file = context.getDatabasePath(DB_NAME)
        return listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
    }

    /** Lines for the collection screen: the most used first, or those matching [query]. */
    fun entries(query: String, kind: String?, limit: Int = 200): List<Entry> = runCatching {
        val where = buildList {
            if (query.isNotBlank()) add("(original LIKE ? OR translation LIKE ?)")
            if (!kind.isNullOrBlank()) add("kind = ?")
        }.joinToString(" AND ").ifBlank { null }
        val args = buildList {
            if (query.isNotBlank()) {
                add("%$query%")
                add("%$query%")
            }
            if (!kind.isNullOrBlank()) add(kind)
        }.toTypedArray()
        db.query(
            "segments",
            arrayOf("id", "original", "translation", "kind", "uses", "mangas", "sensitive", "user"),
            where,
            args,
            null,
            null,
            "uses DESC, last_used DESC",
            limit.toString(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getInt(6) == 1, c.getInt(7) == 1))
                }
            }
        }
    }.getOrElse { emptyList() }

    /** The reader's own translation for a saved line: it wins from now on. */
    fun edit(id: Long, translation: String) {
        if (translation.isBlank()) return
        db.update(
            "segments",
            ContentValues().apply {
                put("translation", translation.trim())
                put("user", 1)
                put("sensitive", 0)
                put("confidence", Source.USER.confidence)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
        cache.evictAll()
    }

    /**
     * The reader changed how a term is translated ([old] to [new]): every saved line with that term takes the new
     * translation (the reader's own corrections stay as they are).
     */
    fun replaceTerm(source: String, old: String, new: String) {
        if (source.isBlank() || old.isBlank() || new.isBlank() || old == new) return
        runCatching {
            db.execSQL(
                "UPDATE segments SET translation = REPLACE(translation, ?, ?) WHERE user = 0 AND original LIKE ? AND translation LIKE ?",
                arrayOf<Any>(old, new, "%$source%", "%$old%"),
            )
            cache.evictAll()
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not update the collection for a term" } }
    }

    fun setSensitive(id: Long, sensitive: Boolean) {
        db.update("segments", ContentValues().apply { put("sensitive", if (sensitive) 1 else 0) }, "id = ?", arrayOf(id.toString()))
        cache.evictAll()
    }

    fun delete(id: Long) {
        db.delete("segments", "id = ?", arrayOf(id.toString()))
        cache.evictAll()
    }

    fun clear() {
        db.delete("segments", null, null)
        db.delete("stats", null, null)
        cache.evictAll()
        runCatching { db.execSQL("VACUUM") }
    }

    /** Size limit in bytes from the setting (0 = none). */
    var limitBytes: Long = 0L

    /** Over the limit: the lines unused the longest go first; the reader's corrections are never removed. */
    private fun trimIfNeeded() {
        val limit = limitBytes
        if (limit <= 0L || sizeBytes() <= limit) return
        repeat(TRIM_ROUNDS) {
            db.execSQL(
                "DELETE FROM segments WHERE id IN (SELECT id FROM segments WHERE user = 0 ORDER BY last_used ASC LIMIT ?)",
                arrayOf<Any>(TRIM_STEP),
            )
            cache.evictAll()
            runCatching { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
            if (sizeBytes() <= limit) return
        }
        runCatching { db.execSQL("VACUUM") }
    }

    /** Copies the whole collection to [target] (a backup, or to move it to another phone). */
    fun exportTo(target: java.io.OutputStream) {
        runCatching { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        context.getDatabasePath(DB_NAME).inputStream().use { it.copyTo(target) }
    }

    /**
     * Adds the lines of an exported collection to this one: new lines are added, lines already here keep
     * theirs, except where the imported one is the reader's correction. Returns how many lines were added.
     */
    fun importFrom(source: java.io.InputStream): Int {
        val tmp = File(context.cacheDir, "collection_import.db")
        source.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return try {
            val before = db.compileStatement("SELECT COUNT(*) FROM segments").simpleQueryForLong()
            db.execSQL("ATTACH DATABASE ? AS other", arrayOf(tmp.path))
            try {
                db.execSQL(
                    """INSERT OR IGNORE INTO segments(src, tgt, key, original, translation, arabic, kind, confidence, uses, mangas, last_manga, sensitive, user, last_used)
                       SELECT src, tgt, key, original, translation, arabic, kind, confidence, uses, mangas, last_manga, sensitive, user, last_used FROM other.segments""",
                )
                db.execSQL(
                    """UPDATE segments SET translation = (SELECT o.translation FROM other.segments o WHERE o.src = segments.src AND o.tgt = segments.tgt AND o.key = segments.key), user = 1
                       WHERE user = 0 AND EXISTS (SELECT 1 FROM other.segments o WHERE o.src = segments.src AND o.tgt = segments.tgt AND o.key = segments.key AND o.user = 1)""",
                )
            } finally {
                db.execSQL("DETACH DATABASE other")
            }
            cache.evictAll()
            (db.compileStatement("SELECT COUNT(*) FROM segments").simpleQueryForLong() - before).toInt()
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val DB_NAME = "translation_collection.db"
        const val DB_VERSION = 1
        const val CACHE_LINES = 5_000
        const val MAX_LENGTH = 500

        /** A plain line is reused as it is after it came back this many times with the same translation. */
        const val SURE_USES = 3
        const val NEAR_MIN_LENGTH = 10
        const val NEAR_PREFIX = 4
        const val NEAR_CANDIDATES = 40
        const val NEAR_SIMILARITY = 0.9f
        const val TRIM_STEP = 2_000
        const val TRIM_ROUNDS = 50
    }
}
