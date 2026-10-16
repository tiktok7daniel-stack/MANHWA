package eu.kanade.translation.collection

import android.content.Context
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA phrasebook: the everyday lines of manhwa ("What?!", "Let's go!", "Level up!") bundled with the app,
 * put once in the translation collection, so they are translated on the phone in no time and with no AI call.
 * New lines still go to the team and are learnt for the next time.
 */
object Phrasebook {

    /** Bump when the bundled files change, so new lines get added. */
    private const val VERSION = 3
    private const val PREFS = "manhwa_phrasebook"

    /** Bundled files: asset path to (source, target) collection language names. */
    private val books = listOf("phrasebook/en_ar.tsv" to ("ENGLISH" to "ARABIC"))

    fun seedIfNeeded(context: Context, collection: TranslationCollection) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt("version", 0) >= VERSION) return
        var added = 0
        books.forEach { (asset, langs) ->
            val pairs = runCatching {
                context.assets.open(asset).bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }
                        .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                        .toList()
                }
            }.onFailure { logcat(LogPriority.WARN, it) { "Could not read $asset" } }.getOrDefault(emptyList())
            added += collection.seedPhrasebook(langs.first, langs.second, pairs)
        }
        prefs.edit().putInt("version", VERSION).apply()
        logcat { "Phrasebook: $added lines added to the collection" }
    }
}
