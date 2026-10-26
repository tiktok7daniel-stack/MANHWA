package eu.kanade.tachiyomi.novel

import android.content.Context
import eu.kanade.translation.readTextAtomic
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.GoogleTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.appAiConfigured
import eu.kanade.translation.translator.appAsk
import eu.kanade.translation.writeTextAtomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * MANHWA novels: a chapter's paragraphs in the reader's language. The AI team translates them in batches that keep
 * the paragraph count, with the novel's own names glossary so a name reads the same in every chapter (the AI adds
 * the names it meets to it); Google Translate takes a batch the AI cannot. The result is kept, so a chapter is
 * translated once.
 */
object NovelTranslator {

    private const val BATCH_CHARS = 3_200
    private const val MAX_GLOSSARY = 120

    private val json = Json { ignoreUnknownKeys = true }

    /** Translates [paragraphs] of [novelKey]; [onProgress] gets the paragraphs done so far and the total. */
    suspend fun translate(
        context: Context,
        novelKey: String,
        title: String,
        paragraphs: List<String>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<String> = withContext(Dispatchers.IO) {
        val prefs = context.appGraph.translationPreferences
        val target = TextTranslatorLanguage.fromPref(prefs.translateToLanguage())
        val google = GoogleTranslator(TextRecognizerLanguage.ENGLISH, target)
        val glossary = readGlossary(context, novelKey).toMutableMap()
        val out = ArrayList<String>(paragraphs.size)
        val batches = batches(paragraphs)
        var done = 0
        onProgress(0, paragraphs.size)
        for (batch in batches) {
            val translated = if (appAiConfigured()) {
                try {
                    askAi(target.label, title, batch, glossary)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Novel batch not translated by the AI" }
                    null
                }
            } else {
                null
            } ?: batch.map { p -> runCatching { google.translateText(p) }.getOrDefault(p).ifBlank { p } }
            out += translated
            done += batch.size
            onProgress(done, paragraphs.size)
        }
        writeGlossary(context, novelKey, glossary)
        out
    }

    /** Paragraphs grouped so that a batch stays small enough to come back whole. */
    internal fun batches(paragraphs: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var size = 0
        for (p in paragraphs) {
            if (current.isNotEmpty() && size + p.length > BATCH_CHARS) {
                result += current
                current = mutableListOf()
                size = 0
            }
            current += p
            size += p.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private suspend fun askAi(language: String, title: String, batch: List<String>, glossary: MutableMap<String, String>): List<String>? {
        val system = "You translate web novels and light novels into natural, literary $language. " +
            "Translate every paragraph fully and faithfully, keep its tone and its dialogue marks, never summarise, " +
            "never merge or split paragraphs. Use the glossary for every name and term in it. " +
            "Reply with ONLY this JSON: {\"p\": [one translated string per paragraph, same count and order], " +
            "\"names\": {\"original name\": \"translated name\"} for new character, place and skill names}."
        val names = glossary.entries.take(MAX_GLOSSARY).joinToString("\n") { "${it.key} = ${it.value}" }
        val prompt = buildString {
            append("Novel: ").append(title).append('\n')
            if (names.isNotBlank()) append("Glossary:\n").append(names).append('\n')
            append("Paragraphs (JSON array):\n")
            append(json.encodeToString(JsonArray.serializer(), JsonArray(batch.map { JsonPrimitive(it) })))
        }
        val answer: JsonObject = appAsk(AiRouter.Lane.CHAPTER) { ai -> ai.askJson(system, prompt) }
        val list = (answer["p"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: return null
        if (list.size != batch.size || list.any { it.isBlank() }) return null
        (answer["names"] as? JsonObject)?.forEach { (k, v) ->
            val value = (v as? JsonPrimitive)?.contentOrNull
            if (k.isNotBlank() && !value.isNullOrBlank() && k !in glossary) glossary[k] = value
        }
        return list
    }

    // ---- The novel's names, one small file each ----

    private fun glossaryFile(context: Context, novelKey: String): File {
        val name = MessageDigest.getInstance("SHA-1").digest(novelKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "novel/glossary/$name.json")
    }

    fun readGlossary(context: Context, novelKey: String): Map<String, String> = runCatching {
        val f = glossaryFile(context, novelKey)
        if (!f.exists()) {
            emptyMap()
        } else {
            (json.parseToJsonElement(f.readTextAtomic()) as? JsonObject)
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                ?.toMap()
                .orEmpty()
        }
    }.getOrDefault(emptyMap())

    private fun writeGlossary(context: Context, novelKey: String, glossary: Map<String, String>) {
        runCatching {
            val f = glossaryFile(context, novelKey).apply { parentFile?.mkdirs() }
            f.writeTextAtomic(json.encodeToString(JsonObject.serializer(), JsonObject(glossary.mapValues { JsonPrimitive(it.value) })))
        }.onFailure { logcat(LogPriority.ERROR, it) { "Novel glossary not saved" } }
    }
}
