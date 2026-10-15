package eu.kanade.translation.learning

import eu.kanade.translation.glossary.GlossaryStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream
import java.io.OutputStream

/**
 * MANHWA: one file with everything the translation knows about the reader's manhwa: each one's glossary
 * (names, kind, gender, notes), story so far and lessons, plus the lessons for the whole app. Manhwa are matched
 * by title and source, so the file works on another phone, where the same manhwa have other ids.
 */
class KnowledgeBackup(
    private val glossaryStore: GlossaryStore,
    private val lessonStore: LessonStore,
) {

    @Serializable
    data class Item(
        val title: String,
        val source: Long,
        val glossary: List<GlossaryStore.Entry> = emptyList(),
        val story: List<GlossaryStore.ChapterSummary> = emptyList(),
        val lessons: LessonStore.Book = LessonStore.Book(),
    )

    @Serializable
    data class File(val version: Int = 1, val items: List<Item> = emptyList(), val global: LessonStore.Book = LessonStore.Book())

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** Writes the knowledge of [library] (the reader's manhwa) to [out]. Returns how many manhwa had some. */
    fun export(library: List<Manga>, out: OutputStream): Int {
        val items = library.mapNotNull { manga ->
            val item = Item(
                title = manga.title,
                source = manga.source,
                glossary = glossaryStore.get(manga.id),
                story = glossaryStore.story(manga.id),
                lessons = lessonStore.book(manga.id),
            )
            item.takeIf { it.glossary.isNotEmpty() || it.story.isNotEmpty() || it.lessons.lessons.isNotEmpty() || it.lessons.rules.isNotEmpty() }
        }
        out.write(json.encodeToString(File(items = items, global = lessonStore.book(LessonStore.GLOBAL))).toByteArray())
        return items.size
    }

    /**
     * Adds a file's knowledge to this phone's: terms not known here are added (the ones known keep theirs),
     * the story is taken where there is none, lessons and rules are added. Returns how many manhwa matched.
     */
    fun import(library: List<Manga>, input: InputStream): Int {
        val file = json.decodeFromString<File>(input.readBytes().decodeToString())
        var matched = 0
        for (item in file.items) {
            val manga = library.firstOrNull { it.title.equals(item.title, ignoreCase = true) && it.source == item.source }
                ?: library.firstOrNull { it.title.equals(item.title, ignoreCase = true) }
                ?: continue
            matched++
            glossaryStore.importEntries(manga.id, item.glossary)
            if (glossaryStore.story(manga.id).isEmpty()) item.story.forEach { glossaryStore.addChapterSummary(manga.id, it.chapter, it.summary) }
            lessonStore.mergeBook(manga.id, item.lessons, manga.title)
        }
        lessonStore.mergeBook(LessonStore.GLOBAL, file.global, "")
        return matched
    }
}
