package eu.kanade.translation.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.translation.model.PageTranslation
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA search in translations: every translated line with the chapter and page it is on, so a name or a
 * sentence can be found again in all the chapters read and opened right there. Its own small database (the
 * translation library is not touched); a chapter indexed again replaces its own lines only.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationSearchIndex(context: Context) {

    data class Hit(
        val mangaId: Long,
        val chapterId: Long,
        val chapterName: String,
        val title: String,
        val page: String,
        val original: String,
        val translation: String,
    )

    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS lines (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    manga_id INTEGER NOT NULL,
                    chapter_id INTEGER NOT NULL,
                    chapter_name TEXT NOT NULL,
                    title TEXT NOT NULL,
                    page TEXT NOT NULL,
                    original TEXT NOT NULL,
                    translation TEXT NOT NULL
                )""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS lines_chapter ON lines(chapter_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = onCreate(db)
    }

    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    /** Indexes the lines of one translated chapter. */
    fun index(mangaId: Long, title: String, chapterId: Long, chapterName: String, pages: Map<String, PageTranslation>) {
        runCatching {
            db.beginTransaction()
            try {
                db.delete("lines", "chapter_id = ?", arrayOf("$chapterId"))
                pages.forEach { (page, translation) ->
                    translation.blocks.filter { it.text.isNotBlank() && it.translation.isNotBlank() }.forEach { b ->
                        db.insert(
                            "lines",
                            null,
                            ContentValues().apply {
                                put("manga_id", mangaId)
                                put("chapter_id", chapterId)
                                put("chapter_name", chapterName)
                                put("title", title)
                                put("page", page)
                                put("original", b.text.take(MAX_TEXT))
                                put("translation", b.translation.take(MAX_TEXT))
                            },
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not index the chapter's lines" } }
    }

    /** Lines whose original or translation holds [query] (newest chapters first). */
    fun search(query: String, limit: Int = 200): List<Hit> = runCatching {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%"
        db.query(
            "lines",
            arrayOf("manga_id", "chapter_id", "chapter_name", "title", "page", "original", "translation"),
            "original LIKE ? ESCAPE '\\' OR translation LIKE ? ESCAPE '\\'",
            arrayOf(like, like),
            null,
            null,
            "id DESC",
            "$limit",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Hit(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6)))
                }
            }
        }
    }.getOrDefault(emptyList())

    fun count(): Long = runCatching { android.database.DatabaseUtils.queryNumEntries(db, "lines") }.getOrDefault(0)

    private companion object {
        const val DB_NAME = "manhwa_translation_search.db"
        const val DB_VERSION = 1
        const val MAX_TEXT = 600
    }
}
