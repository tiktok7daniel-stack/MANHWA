package eu.kanade.tachiyomi.ui.home.discover

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.at.ATMR

/**
 * MANHWA: the home's sections, each one shown or hidden from Settings > Appearance > Home sections.
 * [key] names the stored switch ("home_show_<key>"), so it never changes; "New chapters" starts hidden.
 */
enum class HomeSection(val key: String, val title: StringResource, val shownByDefault: Boolean = true) {
    HERO("hero", ATMR.strings.home_sec_hero),
    QUICK("quick", ATMR.strings.home_sec_quick),
    DASHBOARD("dashboard", ATMR.strings.home_dashboard),
    TOGGLES("toggles", ATMR.strings.home_toggles),
    ALERTS("alerts", ATMR.strings.alert_title),
    CONTINUE("continue", ATMR.strings.home_continue),
    NEW("new", ATMR.strings.home_new_chapters, shownByDefault = false),
    TOP10("top10", ATMR.strings.home_top10),
    RECS("recs", ATMR.strings.recs_title),
    FEED("feed", ATMR.strings.home_from_sources),
    GENRES("genres", ATMR.strings.home_explore),
    COMPLETED("completed", ATMR.strings.home_completed),
    AI("ai", ATMR.strings.home_ai_title),
    RAILS("rails", ATMR.strings.home_sec_rails),
    LISTS("lists", ATMR.strings.home_my_lists),
}
