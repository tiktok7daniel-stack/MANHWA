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

    /** Who gave a line; [LOCAL] is the model on the phone (below every key, never over one of their lines). */
    enum class Source(val confidence: Int) { LOCAL(40), TEAM(60), EDITOR(80), USER(100) }

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

    class Stats(val lines: Long, val bytes: Long, val todayLookups: Long, val todayHits: Long, val allHits: Long, val templates: Long = 0)

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
                    last_used INTEGER NOT NULL DEFAULT 0,
                    reviewed INTEGER NOT NULL DEFAULT 0,
                    added INTEGER NOT NULL DEFAULT 0
                )""",
            )
            db.execSQL("CREATE UNIQUE INDEX segments_key ON segments(src, tgt, key)")
            db.execSQL("CREATE INDEX segments_used ON segments(user, last_used)")
            db.execSQL("CREATE TABLE stats (day TEXT PRIMARY KEY, lookups INTEGER NOT NULL DEFAULT 0, hits INTEGER NOT NULL DEFAULT 0)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // MANHWA review: which lines were checked (the reader's own corrections were) and when each came in
                db.execSQL("ALTER TABLE segments ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE segments ADD COLUMN added INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE segments SET reviewed = 1 WHERE user = 1")
                db.execSQL("UPDATE segments SET added = last_used")
            }
        }
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
    fun lookup(src: String, tgt: String, text: String, glossary: Map<String, String>, acceptNear: Boolean = false): Hit? {
        // MANHWA: a line learnt with the phone model's leaked reply ("draft ⇒ draft (=)") is never given back
        lookupIn(src, tgt, text, glossary, acceptNear)?.let { return it.takeUnless { h -> eu.kanade.translation.local.LocalAiRules.leaked(h.translation) } }
        // MANHWA: with the Egyptian dialect, a line known only in formal Arabic (the phrasebook) is still a draft
        val base = tgt.removeSuffix(DIALECT_SUFFIX)
        if (base == tgt) return null
        return lookupIn(src, base, text, glossary, acceptNear)
            ?.takeUnless { eu.kanade.translation.local.LocalAiRules.leaked(it.translation) }
            ?.let { Hit(it.translation, sure = false, match = it.match) }
    }

    private fun lookupIn(src: String, tgt: String, text: String, glossary: Map<String, String>, acceptNear: Boolean): Hit? = runCatching {
        if (text.isBlank() || text.length > MAX_LENGTH) return null
        val keyed = TextKey.key(text, glossary)
        find(src, tgt, keyed.exact)?.let { row ->
            return Hit(row.translation, isSure(row), Match.EXACT)
        }
        if (keyed.isTemplate) {
            find(src, tgt, keyed.template)?.let { row ->
                // MANHWA: the counted noun agrees with this line's number (11 دقيقة, not 11 دقائق)
                val filled = TextKey.fill(row.translation, keyed, glossary, row.arabic)?.let(eu.kanade.translation.translator.ArabicGrammarEngine::fixCounts) ?: return@let
                // A template is a system line or a counter by nature: sure unless it is context-sensitive
                return Hit(filled, row.user || !row.sensitive, Match.TEMPLATE)
            }
        }
        // MANHWA auto-templates: a learnt shape, its bracketed name translated from the glossary, the codex or here
        TemplateShapes.lookupKey(text)?.let { shape ->
            find(src, tgt, shape.key)?.takeIf { it.uses >= SHAPE_SURE_USES && !it.sensitive }?.let { row ->
                val inner = glossary.entries.firstOrNull { it.key.equals(shape.inner, ignoreCase = true) }?.value
                    ?: find(src, tgt, TextKey.key(shape.inner).exact)?.translation
                    ?: ManhwaCodex.lookup(shape.inner, ManhwaCodex.defaultPacks)
                if (inner != null) {
                    TemplateShapes.fill(row.translation, shape, inner, row.arabic)?.let { return Hit(it, true, Match.TEMPLATE) }
                }
            }
        }
        // MANHWA fast mode: a saved line this close is written as it is (otherwise only a hint for the AI)
        near(src, tgt, keyed.exact)?.let { (translation, score) ->
            return Hit(translation, acceptNear && score >= eu.kanade.translation.translator.FastTranslatorRules.FAST_NEAR, Match.NEAR)
        }
        null
    }.onFailure { logcat(LogPriority.WARN, it) { "Collection lookup failed" } }.getOrNull()

    /** A line read a little differently (one letter): only a suggestion for the AI. */
    private fun near(src: String, tgt: String, exact: String): Pair<String, Float>? {
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
            best?.let { it to bestScore }
        }
    }

    /**
     * MANHWA built-in phrasebook: common lines known before any chapter is translated, reused with no AI call.
     * Only rows not in the collection yet are added (a learnt or corrected line is never touched); they count
     * as sure, and a different translation learnt later marks them context-sensitive like any other line.
     * Returns how many were added.
     */
    fun seedPhrasebook(src: String, tgt: String, pairs: List<Pair<String, String>>): Int = runCatching {
        var added = 0
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for ((text, translation) in pairs) {
                if (text.isBlank() || translation.isBlank()) continue
                // Lines with numbers are saved as templates ("You gained {n0} EXP!"), so every number matches
                val keyed = TextKey.key(text, emptyMap())
                val template = if (keyed.isTemplate) TextKey.templateOf(translation, keyed, emptyMap()) else null
                val key = if (template != null) keyed.template else keyed.exact
                val saved = template ?: translation.trim()
                val id = db.insertWithOnConflict(
                    "segments",
                    null,
                    ContentValues().apply {
                        put("src", src)
                        put("tgt", tgt)
                        put("key", key)
                        put("original", text.trim())
                        put("translation", saved)
                        put("arabic", if (TextKey.arabicStyle(translation)) 1 else 0)
                        put("kind", if (template != null) "system" else "")
                        put("confidence", Source.EDITOR.confidence)
                        put("uses", SURE_USES)
                        put("mangas", 1)
                        put("last_manga", 0)
                        put("last_used", now)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (id != -1L) {
                    added++
                    cache.remove(cacheKey(src, tgt, key))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        added
    }.onFailure { logcat(LogPriority.ERROR, it) { "Could not load the phrasebook" } }.getOrDefault(0)

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
                    if (eu.kanade.translation.local.LocalAiRules.leaked(translation)) continue
                    val keyed = TextKey.key(line.text, glossary)
                    val template = TextKey.templateOf(translation, keyed, glossary)
                    // Saved as a template when its numbers and names are found in the translation, else word for word
                    val (key, saved) = if (keyed.isTemplate && template != null) keyed.template to template else keyed.exact to translation
                    save(src, tgt, key, line.text.trim(), saved, TextKey.arabicStyle(translation), line.kind, mangaId, source, now)
                    // MANHWA auto-templates: "[Fireball] has reached Lv. 3" teaches "[{skill}] has reached Lv. {n}"
                    TemplateShapes.shapeOf(line.text, translation)?.let { (shapeKey, shapeTranslation) ->
                        save(src, tgt, shapeKey, line.text.trim(), shapeTranslation, TextKey.arabicStyle(translation), "", mangaId, source, now)
                    }
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
                    put("reviewed", if (source == Source.USER) 1 else 0)
                    put("added", now)
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
        when (CollectionMerge.decide(source, existing[4] as Int, byUser, sameTranslation)) {
            CollectionMerge.Action.USER -> {
                // The reader's correction wins and stays
                values.put("translation", translation)
                values.put("arabic", if (arabic) 1 else 0)
                values.put("user", 1)
                values.put("sensitive", 0)
                values.put("reviewed", 1)
            }
            CollectionMerge.Action.KEEP -> Unit
            CollectionMerge.Action.REPLACE -> {
                // A stronger hand (the editor over the team, a key over the phone) fixed the line: its version
                values.put("translation", translation)
                values.put("arabic", if (arabic) 1 else 0)
                // A new wording the reader has not seen: it goes to the next review
                values.put("reviewed", 0)
            }
            // The phone's other wording of a key's line changes nothing (and does not count as a use)
            CollectionMerge.Action.SKIP -> return
            // Two different translations for the same line: it depends on who speaks, so only a hint from now on
            CollectionMerge.Action.SENSITIVE -> {
                values.put("sensitive", 1)
                values.put("reviewed", 0)
            }
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
        val templates = db.compileStatement("SELECT COUNT(*) FROM segments WHERE key LIKE '%{n%' OR key LIKE '%{name%'").simpleQueryForLong()
        Stats(lines, sizeBytes(), lookups, hits, allHits, templates)
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
    /** MANHWA optimizer: the lines worth a review, most reused first (the reader's own corrections are left alone). */
    fun reviewCandidates(src: String, tgt: String, limit: Int, offset: Int = 0): List<Entry> = runCatching {
        db.query(
            "segments",
            arrayOf("id", "original", "translation", "kind", "uses", "mangas", "sensitive", "user"),
            "src = ? AND tgt = ? AND user = 0 AND key NOT LIKE 'shape:%'",
            arrayOf(src, tgt),
            null,
            null,
            // A steady order, so batch after batch covers every line once while lines are being improved
            "id",
            "$offset, $limit",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getInt(6) == 1, c.getInt(7) == 1))
                }
            }
        }
    }.getOrDefault(emptyList())

    /** A better translation from the team's editor: kept as sure, but still not the reader's own. */
    fun improve(id: Long, translation: String) {
        if (translation.isBlank()) return
        db.update(
            "segments",
            ContentValues().apply {
                put("translation", translation.trim())
                put("sensitive", 0)
                put("confidence", Source.EDITOR.confidence)
                put("uses", SURE_USES)
                // The editor's new wording goes to the next review
                put("reviewed", 0)
            },
            "id = ? AND user = 0",
            arrayOf(id.toString()),
        )
        cache.evictAll()
    }

    fun edit(id: Long, translation: String) {
        if (translation.isBlank()) return
        db.update(
            "segments",
            ContentValues().apply {
                put("translation", translation.trim())
                put("user", 1)
                put("sensitive", 0)
                put("confidence", Source.USER.confidence)
                put("reviewed", 1)
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
        // A whole, consistent copy even while chapters are translating (Android 11+: SQLite writes it itself)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val tmp = File(context.cacheDir, "collection_export.db").apply { delete() }
            try {
                db.execSQL("VACUUM INTO ?", arrayOf(tmp.path))
                tmp.inputStream().use { it.copyTo(target) }
                return
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "VACUUM INTO failed, copying the file" }
            } finally {
                tmp.delete()
            }
        }
        runCatching { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        context.getDatabasePath(DB_NAME).inputStream().use { it.copyTo(target) }
    }

    /** MANHWA review: how many lines nobody has checked yet, and the oldest one's date (0 when none). */
    fun unreviewed(): Pair<Long, Long> = runCatching {
        db.rawQuery("SELECT COUNT(*), IFNULL(MIN(added), 0) FROM segments WHERE user = 0 AND reviewed = 0", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L
        }
    }.getOrDefault(0L to 0L)

    /** MANHWA review: writes the lines nobody has checked yet, and every series' [glossaries], as a review file; returns how many lines. */
    fun exportReview(target: java.io.OutputStream, glossaries: List<CollectionReview.Glossary>): Int {
        val lines = mutableListOf<CollectionReview.Line>()
        var from = Long.MAX_VALUE
        var to = 0L
        db.query(
            "segments",
            arrayOf("key", "src", "tgt", "original", "translation", "kind", "uses", "sensitive", "added"),
            "user = 0 AND reviewed = 0",
            null,
            null,
            null,
            "added, id",
        ).use { c ->
            while (c.moveToNext()) {
                lines += CollectionReview.Line(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6), c.getInt(7) == 1)
                from = minOf(from, c.getLong(8))
                to = maxOf(to, c.getLong(8))
            }
        }
        target.write(CollectionReview.encode(lines, glossaries, if (lines.isEmpty()) 0 else from, to, System.currentTimeMillis()).toByteArray())
        return lines.size
    }

    /** What a review file changed: lines corrected, approved as they were, and deleted; and the glossaries it holds. */
    class ReviewResult(val corrected: Int, val approved: Int, val deleted: Int, val glossaries: List<CollectionReview.Glossary>)

    /**
     * MANHWA review: a corrected review file. Each line in it is reviewed: a new wording replaces the AI's and counts
     * as the reader's own (sure from now on); the same wording is approved; an empty one deletes the line. A line
     * the reader corrected on the phone meanwhile keeps the reader's. Null when the file is not a review file.
     */
    fun importReview(source: java.io.InputStream): ReviewResult? {
        val parsed = CollectionReview.decode(source.use { it.readBytes().decodeToString() }) ?: return null
        val lines = parsed.lines
        var corrected = 0
        var approved = 0
        var deleted = 0
        db.beginTransaction()
        try {
            for (line in lines) {
                val saved = db.query(
                    "segments",
                    arrayOf("id", "translation"),
                    "src = ? AND tgt = ? AND key = ? AND user = 0",
                    arrayOf(line.src, line.tgt, line.k),
                    null,
                    null,
                    null,
                    "1",
                ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null } ?: continue
                val id = saved.first.toString()
                when (CollectionReview.outcome(line, saved.second)) {
                    CollectionReview.Outcome.DELETED -> {
                        db.delete("segments", "id = ?", arrayOf(id))
                        deleted++
                    }
                    CollectionReview.Outcome.APPROVED -> {
                        db.update(
                            "segments",
                            ContentValues().apply {
                                put("reviewed", 1)
                                put("sensitive", if (line.sensitive) 1 else 0)
                                // Checked and right: sure from now on, unless it depends on who speaks
                                put("user", if (line.sensitive) 0 else 1)
                                if (!line.sensitive) put("confidence", Source.USER.confidence)
                            },
                            "id = ?",
                            arrayOf(id),
                        )
                        approved++
                    }
                    CollectionReview.Outcome.CORRECTED -> {
                        val translation = line.translation.trim()
                        db.update(
                            "segments",
                            ContentValues().apply {
                                put("translation", translation)
                                put("arabic", if (TextKey.arabicStyle(translation)) 1 else 0)
                                put("reviewed", 1)
                                put("sensitive", if (line.sensitive) 1 else 0)
                                // Sure from now on, unless it depends on who speaks
                                put("user", if (line.sensitive) 0 else 1)
                                put("confidence", Source.USER.confidence)
                            },
                            "id = ?",
                            arrayOf(id),
                        )
                        corrected++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cache.evictAll()
        // One more round in the list of reviews (newest first)
        val round = "${System.currentTimeMillis()},$corrected,$approved,$deleted"
        rounds.edit().putString(ROUNDS_KEY, (listOf(round) + reviewRounds().map { it.raw }).take(MAX_ROUNDS).joinToString(";")).apply()
        return ReviewResult(corrected, approved, deleted, parsed.glossaries)
    }

    /** One review imported: when, and what it changed. */
    class ReviewRound(val at: Long, val corrected: Int, val approved: Int, val deleted: Int, val raw: String)

    private val rounds by lazy { context.getSharedPreferences("manhwa_collection_review", Context.MODE_PRIVATE) }

    /** MANHWA review: every review imported so far, newest first; the next one holds what came after the first. */
    fun reviewRounds(): List<ReviewRound> = rounds.getString(ROUNDS_KEY, "").orEmpty().split(';').mapNotNull { raw ->
        val parts = raw.split(',').mapNotNull { it.toLongOrNull() }
        if (parts.size == 4) ReviewRound(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), raw) else null
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
                // All or nothing: a crash half-way leaves the collection as it was
                db.beginTransaction()
                try {
                db.execSQL(
                    """INSERT OR IGNORE INTO segments(src, tgt, key, original, translation, arabic, kind, confidence, uses, mangas, last_manga, sensitive, user, last_used)
                       SELECT src, tgt, key, original, translation, arabic, kind, confidence, uses, mangas, last_manga, sensitive, user, last_used FROM other.segments""",
                )
                db.execSQL(
                    """UPDATE segments SET translation = (SELECT o.translation FROM other.segments o WHERE o.src = segments.src AND o.tgt = segments.tgt AND o.key = segments.key), user = 1
                       WHERE user = 0 AND EXISTS (SELECT 1 FROM other.segments o WHERE o.src = segments.src AND o.tgt = segments.tgt AND o.key = segments.key AND o.user = 1)""",
                )
                // The reader's own lines need no review
                db.execSQL("UPDATE segments SET reviewed = 1 WHERE user = 1 AND reviewed = 0")
                db.execSQL("UPDATE segments SET added = last_used WHERE added = 0")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
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
        const val ROUNDS_KEY = "rounds"
        const val MAX_ROUNDS = 30
        // 2: MANHWA review (reviewed, added)
        const val DB_VERSION = 2
        const val CACHE_LINES = 5_000
        const val MAX_LENGTH = 500

        /** A plain line is reused as it is after it came back this many times with the same translation. */
        const val SURE_USES = 3

        /** See [eu.kanade.translation.style.StyleProfile.libraryKey]: "ARABIC_EG" for spoken Egyptian. */
        const val DIALECT_SUFFIX = "_EG"

        /** A learnt shape is used once it came back twice with the same translation. */
        const val SHAPE_SURE_USES = 2
        const val NEAR_MIN_LENGTH = 10
        const val NEAR_PREFIX = 4
        const val NEAR_CANDIDATES = 40
        const val NEAR_SIMILARITY = 0.9f
        const val TRIM_STEP = 2_000
        const val TRIM_ROUNDS = 50
    }
}

/** MANHWA: what happens when a line already in the collection is learnt again (pure, tested without a phone). */
object CollectionMerge {

    enum class Action { USER, KEEP, REPLACE, SKIP, SENSITIVE }

    /**
     * [source] gives the line again; the row there has [existingConfidence], was written by the reader ([byUser])
     * or not, and has the same translation ([same]) or another one.
     * - The reader always wins; nobody replaces the reader.
     * - The same wording again: one more use.
     * - The editor, or any key over a line only the phone gave: its wording replaces it.
     * - The phone's own other wording of a stronger source's line: nothing changes, not even the use count.
     * - Two equal sources disagree: the line depends on who speaks, a hint only from now on.
     */
    fun decide(source: TranslationCollection.Source, existingConfidence: Int, byUser: Boolean, same: Boolean): Action = when {
        source == TranslationCollection.Source.USER -> Action.USER
        byUser || same -> Action.KEEP
        source == TranslationCollection.Source.EDITOR -> Action.REPLACE
        // A key's line over one only the phone gave
        existingConfidence <= TranslationCollection.Source.LOCAL.confidence && source.confidence > existingConfidence -> Action.REPLACE
        source == TranslationCollection.Source.LOCAL && existingConfidence > source.confidence -> Action.SKIP
        else -> Action.SENSITIVE
    }
}
