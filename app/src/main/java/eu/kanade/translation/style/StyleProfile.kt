package eu.kanade.translation.style

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.TranslationPreferences

/**
 * MANHWA translation fingerprint: the reader's own Arabic. A dialect (formal, light formal or spoken Egyptian),
 * how honorifics are written, and what the reader's choices taught ("this one reads better than that one"),
 * all turned into rules every translation follows. Spoken Egyptian lines are kept apart in the translation
 * library (their own language key), so formal lines never come back in an Egyptian chapter and the other way round.
 */
object StyleProfile {

    enum class Dialect { FUSHA, LIGHT, EGYPTIAN }

    enum class Honorifics { KEEP, ARABIC }

    @Serializable
    data class Choice(val source: String, val chosen: String, val rejected: String, val at: Long = System.currentTimeMillis())

    @Serializable
    data class Profile(val choices: List<Choice> = emptyList(), val shorter: Int = 0, val longer: Int = 0)

    private val json = Json { ignoreUnknownKeys = true }

    fun dialect(prefs: TranslationPreferences): Dialect =
        Dialect.entries.firstOrNull { it.name == prefs.translationStyleDialect().get() } ?: Dialect.LIGHT

    fun honorifics(prefs: TranslationPreferences): Honorifics =
        Honorifics.entries.firstOrNull { it.name == prefs.translationStyleHonorifics().get() } ?: Honorifics.ARABIC

    fun profile(prefs: TranslationPreferences): Profile =
        runCatching { json.decodeFromString<Profile>(prefs.translationStyleProfile().get()) }.getOrDefault(Profile())

    /** The rules every AI translation into Arabic follows for this reader. */
    fun instruction(prefs: TranslationPreferences, targetName: String): String =
        if (!targetName.equals("ARABIC", ignoreCase = true)) "" else instructionFor(dialect(prefs), honorifics(prefs), profile(prefs))

    /** The translation library's language key: spoken Egyptian lines are kept apart from formal ones. */
    fun libraryTarget(prefs: TranslationPreferences, targetName: String): String = libraryKey(targetName, dialect(prefs))

    fun libraryKey(targetName: String, dialect: Dialect): String =
        if (targetName.equals("ARABIC", ignoreCase = true) && dialect == Dialect.EGYPTIAN) "ARABIC_EG" else targetName

    fun instructionFor(dialect: Dialect, honorifics: Honorifics, profile: Profile): String = buildString {
        appendLine()
        appendLine("Reader's Arabic style (follow it in every line):")
        appendLine(
            when (dialect) {
                Dialect.FUSHA -> "- Write in correct Modern Standard Arabic (fusha), dignified but natural, with full grammar."
                Dialect.LIGHT -> "- Write in light, modern fusha as Arabic scanlation teams do: short, spoken-sounding, never stiff."
                Dialect.EGYPTIAN -> "- Write in spoken Egyptian Arabic (ammiya) the way young Egyptians talk: \"إنت\", \"عايز\", \"إزاي\", \"كده\", " +
                    "\"دلوقتي\", \"مش\". Keep names, skills and system windows clear; a system window may stay in light fusha."
            },
        )
        appendLine(
            when (honorifics) {
                Honorifics.KEEP -> "- Keep Korean and Japanese honorifics as readers know them, in Arabic letters: هيونغ، نونا، سونباي، سان، ساما."
                Honorifics.ARABIC -> "- Turn honorifics into natural Arabic: hyung = أخي/أخويا الكبير، noona = أختي الكبيرة، sunbae/senpai = سينيوري/الأقدم، -nim/-sama = سيدي."
            },
        )
        when {
            profile.shorter >= profile.longer + 2 -> appendLine("- The reader prefers the shorter, punchier wording.")
            profile.longer >= profile.shorter + 2 -> appendLine("- The reader prefers the fuller, more detailed wording.")
        }
        val examples = profile.choices.takeLast(MAX_EXAMPLES)
        if (examples.isNotEmpty()) {
            appendLine("- The reader chose these over the alternatives; write like the chosen ones:")
            examples.forEach { appendLine("  \"${it.source.take(80)}\" -> \"${it.chosen.take(80)}\" (not \"${it.rejected.take(80)}\")") }
        }
    }.trimEnd()

    /** Remembers one choice between two translations of the same line. */
    fun record(prefs: TranslationPreferences, source: String, chosen: String, rejected: String) {
        val old = profile(prefs)
        prefs.translationStyleProfile().set(json.encodeToString(withChoice(old, source, chosen, rejected)))
    }

    fun withChoice(old: Profile, source: String, chosen: String, rejected: String): Profile =
        old.copy(
            choices = (old.choices.filterNot { it.source == source } + Choice(source, chosen, rejected)).takeLast(MAX_KEEP),
            shorter = old.shorter + if (chosen.length < rejected.length) 1 else 0,
            longer = old.longer + if (chosen.length > rejected.length) 1 else 0,
        )

    private const val MAX_EXAMPLES = 6
    private const val MAX_KEEP = 40
}
