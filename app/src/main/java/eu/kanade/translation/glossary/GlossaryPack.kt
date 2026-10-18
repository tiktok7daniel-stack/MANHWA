package eu.kanade.translation.glossary

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * MANHWA glossary pack: one series' names and terms (with kind, gender and notes) and its story so far, in a
 * small JSON file. Importing adds what is missing and fills what entries lack; nothing the reader has is replaced
 * (a pack marked [Pack.replace] replaces the AI's terms, never the reader's own edits).
 */
object GlossaryPack {

    @Serializable
    data class Pack(
        val format: String = "",
        val version: Int = 1,
        val entries: List<GlossaryStore.Entry> = emptyList(),
        val story: List<GlossaryStore.ChapterSummary> = emptyList(),
        /** MANHWA: a cleaned glossary that replaces the AI's terms (the reader's own edits stay). */
        val replace: Boolean = false,
    )

    const val FORMAT = "manhwa-glossary"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun encode(entries: List<GlossaryStore.Entry>, story: List<GlossaryStore.ChapterSummary>): String = json.encodeToString(Pack(format = FORMAT, entries = entries, story = story))

    /** The entries of a pack, or null when the text is not a glossary pack. */
    fun decode(text: String): Pack? = runCatching { json.decodeFromString<Pack>(text) }.getOrNull()?.takeIf { it.format == FORMAT }

    fun write(store: GlossaryStore, mangaId: Long, out: OutputStream) {
        out.write(encode(store.get(mangaId), store.story(mangaId)).toByteArray())
    }

    /** Returns how many entries the pack held. */
    fun read(store: GlossaryStore, mangaId: Long, input: InputStream): Int {
        val pack = decode(input.readBytes().decodeToString()) ?: throw IllegalArgumentException("Not a glossary pack")
        store.importEntries(mangaId, pack.entries, pack.replace)
        pack.story.forEach { store.addChapterSummary(mangaId, it.chapter, it.summary, it.number) }
        return pack.entries.size
    }
}
