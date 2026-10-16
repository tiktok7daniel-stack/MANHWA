package eu.kanade.translation.orchestra

/**
 * MANHWA hybrid orchestra, the rules (pure, tested without a phone). Every chapter is played by the whole band:
 *
 * 1. the translation library gives what it already knows (free, instant);
 * 2. the model on the phone drafts every other line at once (free, no internet);
 * 3. [route] keeps the phone's line where a short, plain line needs nothing more, and sends the rest, drafts
 *    included, to the team's keys, which improve instead of starting from zero (far fewer tokens per line);
 * 4. where two keys disagree, the phone judges (free);
 * 5. every correction a key makes to a phone draft is kept as an example the phone follows next time.
 *
 * Heat and battery are respected: on a hot phone, low battery or battery saver the phone sits out ([deviceOk]).
 */
object OrchestraRules {

    enum class Route { LOCAL, TEAM }

    /** Short, everyday lines the phone's model translates as well as a big model. */
    const val LOCAL_MAX_CHARS = 28
    const val LOCAL_MAX_WORDS = 5

    private val structured = Regex("[\\[\\]{}<>:=+%#]|\\d")

    /**
     * Where a line goes once the phone drafted it: the phone keeps short plain lines with a good draft; names of
     * the glossary, system windows, numbers and anything long or doubtful go to the keys.
     */
    fun route(source: String, draft: String, kind: String, glossaryTerms: Collection<String>, draftUsable: Boolean): Route {
        if (!draftUsable) return Route.TEAM
        val text = source.trim()
        if (text.length > LOCAL_MAX_CHARS) return Route.TEAM
        if (text.split(Regex("\\s+")).count { it.isNotBlank() } > LOCAL_MAX_WORDS) return Route.TEAM
        if (kind.contains("system", true) || kind.contains("window", true) || kind.contains("narration", true)) return Route.TEAM
        if (structured.containsMatchIn(text)) return Route.TEAM
        if (glossaryTerms.any { it.length >= 2 && text.contains(it, ignoreCase = true) }) return Route.TEAM
        if (draft.length > text.length * 3 + 10) return Route.TEAM
        return Route.LOCAL
    }

    /** Thermal status from Android (0 none … 6 shutdown): from 3 (severe) the phone stops drafting. */
    const val THERMAL_LIMIT = 3

    fun deviceOk(batteryPercent: Int, charging: Boolean, powerSave: Boolean, thermal: Int): Boolean {
        if (thermal >= THERMAL_LIMIT) return false
        if (powerSave) return false
        return charging || batteryPercent >= MIN_BATTERY
    }

    const val MIN_BATTERY = 20

    /** Calls a key saved: lines the phone kept, counted in batches of about [LINES_PER_CALL]. */
    fun callsSaved(localLines: Int): Int = (localLines + LINES_PER_CALL - 1) / LINES_PER_CALL

    const val LINES_PER_CALL = 12
}
