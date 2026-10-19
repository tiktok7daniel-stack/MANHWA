package eu.kanade.translation.collection

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MANHWA collection review: the lines the AI saved that nobody has checked yet, in a JSON file the reader
 * corrects (or gives to an AI to correct) and imports back. Every line in the imported file counts as reviewed,
 * so the next export only holds what the AI added after it. The file also holds every series' names glossary, so
 * names are corrected in the same place: an imported glossary is that series' whole glossary from then on (and the
 * collection's lines with a changed name follow it). Pure, tested without a phone.
 */
object CollectionReview {

    const val FORMAT = "manhwa-collection-review"

    /** What the reviewer is told, at the top of the file. */
    const val RULES = "Correct \"translation\" only. Keep {n}, {n1}, {name} and the like exactly as they are " +
        "(they are filled with this line's own numbers and names). An empty translation deletes the line. " +
        "Set \"sensitive\" to true when the line depends on who speaks (he/she, polite/rude). Do not change \"k\". " +
        "In \"glossaries\", correct each name's Arabic \"translation\"; a name removed from a list is removed from that " +
        "series. Do not change \"mangaId\"."

    @Serializable
    data class Line(
        val k: String,
        val src: String,
        val tgt: String,
        val original: String,
        val translation: String,
        val kind: String = "",
        val uses: Int = 0,
        val sensitive: Boolean = false,
    )

    /** A name or term of a series' glossary. */
    @Serializable
    data class Term(
        val source: String,
        val translation: String,
        val kind: String = "",
        val gender: String = "",
        val note: String = "",
    )

    /** One series' whole names glossary. */
    @Serializable
    data class Glossary(val mangaId: Long, val title: String = "", val terms: List<Term> = emptyList())

    /** What a review file holds. */
    class Parsed(val lines: List<Line>, val glossaries: List<Glossary>)

    @Serializable
    data class File(
        val format: String = "",
        val rules: String = "",
        val exportedAt: Long = 0,
        val from: Long = 0,
        val to: Long = 0,
        val count: Int = 0,
        val lines: List<Line> = emptyList(),
        val glossaries: List<Glossary> = emptyList(),
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        allowTrailingComma = true
        allowComments = true
        coerceInputValues = true
    }

    fun encode(lines: List<Line>, glossaries: List<Glossary>, from: Long, to: Long, now: Long): String =
        json.encodeToString(File(FORMAT, RULES, now, from, to, lines.size, lines, glossaries))

    /** The lines and glossaries of a review file, or null when it is not one. */
    fun decode(text: String): Parsed? = runCatching {
        json.decodeFromString<File>(text.trim().removePrefix("\uFEFF").trim())
    }.getOrNull()?.takeIf { it.format == FORMAT }?.let { file ->
        Parsed(
            lines = file.lines.filter { it.k.isNotBlank() && it.src.isNotBlank() && it.tgt.isNotBlank() },
            glossaries = file.glossaries.map { g -> g.copy(terms = g.terms.filter { it.source.isNotBlank() && it.translation.isNotBlank() }) },
        )
    }

    enum class Outcome { CORRECTED, APPROVED, DELETED }

    /** What importing [line] does to a saved translation [saved]. */
    fun outcome(line: Line, saved: String): Outcome = when {
        line.translation.isBlank() -> Outcome.DELETED
        line.translation.trim() == saved -> Outcome.APPROVED
        else -> Outcome.CORRECTED
    }
}
