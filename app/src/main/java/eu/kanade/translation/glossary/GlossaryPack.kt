package eu.kanade.translation.glossary

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.InputStream
import java.io.OutputStream

/**
 * MANHWA glossary pack: one series' names and terms (with kind, gender and notes) and its story so far, in a
 * small JSON file. The reader exports it, corrects the names in any editor and imports it back: the file's
 * translations then win, and a pack marked [Pack.replace] (every export) is the whole glossary, so a line deleted
 * from the file is deleted here too. A file touched by an editor still reads (a BOM, comments, a trailing comma,
 * a bare list of entries or a plain {"name": "translation"} object).
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

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        allowTrailingComma = true
        allowComments = true
        coerceInputValues = true
    }

    fun encode(entries: List<GlossaryStore.Entry>, story: List<GlossaryStore.ChapterSummary>): String =
        json.encodeToString(Pack(format = FORMAT, entries = entries, story = story, replace = true))

    /** The entries of a pack, or null when the text is not a glossary. */
    fun decode(text: String): Pack? = runCatching {
        val element = json.parseToJsonElement(text.trim().removePrefix("\uFEFF").trim())
        when {
            element is JsonArray -> Pack(format = FORMAT, entries = element.mapNotNull(::entry))
            element is JsonObject && "entries" in element -> json.decodeFromJsonElement<Pack>(element)
                .takeIf { it.format == FORMAT || it.format.isBlank() && it.entries.isNotEmpty() }
            // A plain {"name": "translation"} object
            element is JsonObject && element.values.isNotEmpty() && element.values.all { it is JsonPrimitive && it.isString } ->
                Pack(format = FORMAT, entries = element.map { (k, v) -> GlossaryStore.Entry(k, (v as JsonPrimitive).content) })
            else -> null
        }
    }.getOrNull()?.let { pack -> pack.copy(entries = pack.entries.filter { it.source.isNotBlank() && it.translation.isNotBlank() }) }

    private fun entry(element: JsonElement): GlossaryStore.Entry? =
        (element as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<GlossaryStore.Entry>(it) }.getOrNull() }

    fun write(store: GlossaryStore, mangaId: Long, out: OutputStream) {
        out.write(encode(store.get(mangaId), store.story(mangaId)).toByteArray())
    }

    /** Returns how many entries the pack held. */
    fun read(store: GlossaryStore, mangaId: Long, input: InputStream): Int {
        val pack = decode(input.readBytes().decodeToString()) ?: throw IllegalArgumentException("Not a glossary pack")
        store.importPack(mangaId, pack.entries, pack.replace)
        pack.story.forEach { store.addChapterSummary(mangaId, it.chapter, it.summary, it.number) }
        return pack.entries.size
    }
}
