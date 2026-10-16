package eu.kanade.translation.study

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** MANHWA study list: lines the reader saved to learn from (original, translation, where they come from). */
class StudyList(context: Context) {

    @Serializable
    data class Item(val original: String, val translation: String, val title: String = "", val at: Long = System.currentTimeMillis())

    private val file = File(context.filesDir, "study_list.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun all(): List<Item> = runCatching { json.decodeFromString<List<Item>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun add(item: Item) {
        val next = (all().filterNot { it.original == item.original } + item).takeLast(MAX)
        runCatching { file.writeText(json.encodeToString(next)) }
    }

    @Synchronized
    fun remove(original: String) {
        runCatching { file.writeText(json.encodeToString(all().filterNot { it.original == original })) }
    }

    fun contains(original: String) = all().any { it.original == original }

    private companion object {
        const val MAX = 2_000
    }
}
