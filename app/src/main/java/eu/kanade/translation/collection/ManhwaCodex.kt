package eu.kanade.translation.collection

import android.app.Application
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * MANHWA master codex: the standard Arabic for the terms every manhwa genre repeats, in six packs (system and
 * hunters, murim and cultivation, fantasy and nobility, modern action, games and stats, dramatic expressions),
 * with English and Korean sources. It lives in assets/codex/codex.tsv (pack, source, Arabic).
 *
 * The terms found in a chapter go to the team with the series glossary, so the same term reads the same in
 * every manhwa from the first chapter, English or raw Korean, without waiting for the AI to settle on one. The
 * series glossary always wins over the codex.
 */
object ManhwaCodex {

    enum class Pack(val id: String) {
        SYSTEM("system"),
        MURIM("murim"),
        FANTASY("fantasy"),
        MODERN("modern"),
        GAMING("gaming"),
        IDIOMS("idioms"),
    }

    val defaultPacks: Set<String> = Pack.entries.map { it.id }.toSet()

    @Volatile
    private var data: Map<Pack, Map<String, String>>? = null

    private fun load(): Map<Pack, Map<String, String>> {
        data?.let { return it }
        val packs = Pack.entries.associateWith { LinkedHashMap<String, String>() }
        runCatching {
            Injekt.get<Application>().assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size != 3) return@forEach
                    val pack = Pack.entries.firstOrNull { it.id == parts[0].trim() } ?: return@forEach
                    val source = parts[1].trim()
                    val arabic = parts[2].trim()
                    if (source.isNotEmpty() && arabic.isNotEmpty()) packs.getValue(pack).putIfAbsent(source, arabic)
                }
            }
        }.onFailure { logcat(LogPriority.ERROR, it) { "Could not read the master codex" } }
        return packs.also { data = it }
    }

    fun terms(pack: Pack): Map<String, String> = load()[pack].orEmpty()

    fun size(pack: Pack): Int = terms(pack).size

    /** Every term of every pack, for the optimizer's reference. */
    fun all(packs: Set<String> = defaultPacks): Map<String, String> =
        Pack.entries.filter { it.id in packs }.flatMap { terms(it).entries }.associate { it.key to it.value }

    private val regexes = HashMap<String, Regex>()

    private fun latinRegex(term: String) = synchronized(regexes) {
        regexes.getOrPut(term) { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }
    }

    private fun isHangul(text: String) = text.any { it in '가'..'힯' }

    /**
     * The codex terms of the enabled [packs] that appear in [text], longest first so "Mana Stone" wins over
     * "Mana". English terms match whole words; Korean terms match inside words too, since Korean glues its
     * particles on (헌터가, 던전에서).
     */
    fun termsIn(text: String, packs: Set<String>): Map<String, String> {
        if (packs.isEmpty() || text.isBlank()) return emptyMap()
        val korean = isHangul(text)
        val found = LinkedHashMap<String, String>()
        Pack.entries.filter { it.id in packs }
            .flatMap { terms(it).entries }
            .filter { (term, _) -> isHangul(term) == korean }
            .sortedByDescending { it.key.length }
            .forEach { (term, arabic) ->
                if (found.size >= MAX_TERMS) return@forEach
                if (found.keys.any { it.contains(term, ignoreCase = true) }) return@forEach
                val present = if (korean) text.contains(term) else latinRegex(term).containsMatchIn(text)
                if (present) found[term] = arabic
            }
        return found
    }

    /** A term of the enabled packs, looked up exactly (any case), for the leak guard. */
    fun lookup(term: String, packs: Set<String>): String? {
        val wanted = term.trim()
        return Pack.entries.filter { it.id in packs }.firstNotNullOfOrNull { pack ->
            terms(pack).entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }?.value
        }
    }

    private const val ASSET = "codex/codex.tsv"
    private const val MAX_TERMS = 120
}
