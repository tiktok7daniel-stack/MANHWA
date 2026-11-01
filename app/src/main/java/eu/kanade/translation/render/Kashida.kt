package eu.kanade.translation.render

/**
 * MANHWA: Arabic lines stretched with kashida (ـ), the way Arabic scanlation teams letter a box: every line but the
 * last is drawn out to the width of the widest one, the stretch put inside words, before their last letter, one
 * word after another, never after a letter that does not join forward (ا د ذ ر ز و ة ى) nor inside لا.
 * Pure Kotlin (the width is measured by the caller): tested without a phone.
 */
object Kashida {

    const val TATWEEL = 'ـ'

    /** Most kashidas put in one place: a longer one reads as a gap, not a stretch. */
    private const val MAX_AT_ONE = 4

    /** Letters that join the letter after them. */
    private val joinsForward = "بتثجحخسشصضطظعغفقكلمنهيئـ".toSet()

    private fun arabic(c: Char) = c in 'ء'..'ي'

    /**
     * Where a kashida may go in [line]: after index i when line[i] joins forward and line[i + 1] is an Arabic letter
     * of the same word (not the alef of لا). Ordered for a natural stretch: in each word the place before its last
     * letter first, then going back, words taken in turn.
     */
    fun places(line: String): List<Int> {
        val words = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        for (i in line.indices) {
            val c = line[i]
            if (!arabic(c) && c != TATWEEL) {
                if (current.isNotEmpty()) words += current
                current = mutableListOf()
                continue
            }
            val next = line.getOrNull(i + 1) ?: continue
            if (c in joinsForward && arabic(next) && !(c == 'ل' && next in "اأإآ")) current += i
        }
        if (current.isNotEmpty()) words += current
        // Each word's places from its end backwards, the words interleaved
        val backwards = words.map { it.reversed() }
        val out = mutableListOf<Int>()
        var round = 0
        while (true) {
            var any = false
            for (w in backwards) w.getOrNull(round)?.let { out += it; any = true }
            if (!any) break
            round++
        }
        return out
    }

    /**
     * [line] stretched with kashidas until it is as wide as [target] (it never goes past it); [measure] gives the
     * drawn width of a text. The line as it is when it has no place for one or is already wide enough.
     */
    fun justify(line: String, target: Float, measure: (String) -> Float): String {
        val spots = places(line)
        if (spots.isEmpty()) return line
        val one = measure(TATWEEL.toString()).takeIf { it > 0f } ?: return line
        var missing = target - measure(line)
        if (missing < one) return line
        val count = IntArray(line.length)
        var k = 0
        var stalled = 0
        while (missing >= one && stalled < spots.size) {
            val at = spots[k % spots.size]
            k++
            if (count[at] >= MAX_AT_ONE) {
                stalled++
                continue
            }
            stalled = 0
            count[at]++
            missing -= one
        }
        val out = StringBuilder(line.length + count.sum())
        for (i in line.indices) {
            out.append(line[i])
            repeat(count[i]) { out.append(TATWEEL) }
        }
        return out.toString()
    }
}
