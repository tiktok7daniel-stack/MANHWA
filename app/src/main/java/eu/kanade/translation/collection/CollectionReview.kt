package eu.kanade.translation.collection

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MANHWA collection review: the lines the AI saved that nobody has checked yet, in a JSON file the reader
 * corrects (or gives to an AI to correct) and imports back. Every line in the imported file counts as reviewed,
 * so the next export only holds what the AI added after it. Pure, tested without a phone.
 */
object CollectionReview {

    const val FORMAT = "manhwa-collection-review"

    /** What the reviewer is told, at the top of the file. */
    const val RULES = "Correct \"translation\" only. Keep {n}, {n1}, {name} and the like exactly as they are " +
        "(they are filled with this line's own numbers and names). An empty translation deletes the line. " +
        "Set \"sensitive\" to true when the line depends on who speaks (he/she, polite/rude). Do not change \"k\"."

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

    @Serializable
    data class File(
        val format: String = "",
        val rules: String = "",
        val exportedAt: Long = 0,
        val from: Long = 0,
        val to: Long = 0,
        val count: Int = 0,
        val lines: List<Line> = emptyList(),
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

    fun encode(lines: List<Line>, from: Long, to: Long, now: Long): String =
        json.encodeToString(File(FORMAT, RULES, now, from, to, lines.size, lines))

    /** The lines of a review file, or null when it is not one. */
    fun decode(text: String): List<Line>? = runCatching {
        json.decodeFromString<File>(text.trim().removePrefix("﻿").trim())
    }.getOrNull()?.takeIf { it.format == FORMAT }?.lines?.filter { it.k.isNotBlank() && it.src.isNotBlank() && it.tgt.isNotBlank() }

    enum class Outcome { CORRECTED, APPROVED, DELETED }

    /** What importing [line] does to a saved translation [saved]. */
    fun outcome(line: Line, saved: String): Outcome = when {
        line.translation.isBlank() -> Outcome.DELETED
        line.translation.trim() == saved -> Outcome.APPROVED
        else -> Outcome.CORRECTED
    }
}
