package eu.kanade.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import java.util.concurrent.TimeUnit

/**
 * ML Kit: Google's small translation model on the phone (no key, no internet once its language is downloaded).
 * MANHWA: each bubble goes as one sentence (the OCR's lines joined, not line by line), in sentence case, with the
 * work's names put in first; the language of each bubble is read from its letters (an English bubble is not sent
 * as Chinese because of the setting); the answer is cleaned and polished like Google's.
 */
class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {

    /** The work's names and terms (source to translation), put in before the text is sent. */
    var glossary: Map<String, String> = emptyMap()

    /** One translator per source language met in the chapter. */
    private val translators = HashMap<String, Translator>()
    private val downloaded = HashSet<String>()

    private val conditions = DownloadConditions.Builder().build()

    private fun translatorFor(source: String): Translator? {
        val target = TranslateLanguage.fromLanguageTag(toLang.code) ?: return null
        if (source == target) return null
        return translators.getOrPut(source) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }
    }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        pages.values.flatMap { it.blocks }.forEach { block ->
            if (block.text.isBlank()) return@forEach
            val source = OfflineRules.sourceOf(block.text, fromLang.code)
            val translator = translatorFor(source)
            if (translator == null) {
                block.translation = block.text
                return@forEach
            }
            if (downloaded.add(source)) {
                runCatching { Tasks.await(translator.downloadModelIfNeeded(conditions), 3, TimeUnit.MINUTES) }
                    .onFailure { downloaded.remove(source); throw it }
            }
            val sent = OfflineRules.prepare(FastTranslatorRules.protectTerms(GoogleRules.cleanSource(block.text), glossary), source)
            val answer = Tasks.await(translator.translate(sent), 30, TimeUnit.SECONDS).orEmpty().trim()
            block.translation = if (answer.isBlank()) "" else finish(answer, block.text)
        }
    }

    private fun finish(translation: String, source: String): String {
        val clean = TextSanitizer.clean(translation, source, toLang)
        return if (toLang == TextTranslatorLanguage.ARABIC) ArabicPolisher.polish(clean) else clean
    }

    override fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}

/** The pure rules of [MLKitTranslator] (tested without a phone). */
object OfflineRules {

    /**
     * The language of [text] read from its letters: Hangul is Korean, kana Japanese, Han characters alone Chinese,
     * Latin letters English; [setting] when nothing tells (numbers, punctuation).
     */
    fun sourceOf(text: String, setting: String): String {
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        for (c in text) {
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.LATIN -> latin++
                else -> {}
            }
        }
        return when {
            hangul > 0 && hangul >= latin -> "ko"
            kana > 0 -> "ja"
            han > 0 && han >= latin -> "zh"
            latin >= 2 -> "en"
            else -> setting
        }
    }

    /** English in capitals goes in sentence case (the small model reads capitals worst); other languages as they are. */
    fun prepare(text: String, source: String): String = if (source == "en") GoogleRules.prepare(text) else text
}
