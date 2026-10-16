package eu.kanade.translation.team

/**
 * MANHWA daily quota meter: how many more chapters a key can translate today, from the share of its quota
 * left (what the site reports, or the meter's estimate), the calls it made today and the calls one chapter
 * takes on this key (learnt; [DEFAULT_CALLS_PER_CHAPTER] until then).
 */
object QuotaForecast {

    const val DEFAULT_CALLS_PER_CHAPTER = 6f

    /** Calls left today, or null when nothing has been used yet (no basis for a guess). */
    fun callsLeft(percentLeft: Int, usedToday: Int): Int? {
        if (usedToday <= 0 || percentLeft >= 100) return null
        if (percentLeft <= 0) return 0
        val total = usedToday * 100f / (100 - percentLeft)
        return (total - usedToday).toInt().coerceAtLeast(0)
    }

    fun chaptersLeft(percentLeft: Int, usedToday: Int, callsPerChapter: Float): Int? {
        val left = callsLeft(percentLeft, usedToday) ?: return null
        val perChapter = if (callsPerChapter > 0f) callsPerChapter else DEFAULT_CALLS_PER_CHAPTER
        return (left / perChapter).toInt()
    }

    fun chaptersLeft(kh: KeyHealthStore.KeyHealth, level: UsageMeter.Level): Int? =
        if (level.exhausted) 0 else chaptersLeft(level.percent, level.requests, kh.callsPerChapter)

    /** Background work (pre-translation, the organizer, night translation) stops below this share left. */
    const val BACKGROUND_FLOOR_PERCENT = 15
}
