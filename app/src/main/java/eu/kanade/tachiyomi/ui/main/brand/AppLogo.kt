package eu.kanade.tachiyomi.ui.main.brand

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.at.ATMR

/**
 * MANHWA: the app's logos. Each one is a home-screen icon (an activity alias in the manifest), the mark drawn inside
 * the app, and the opening that plays with it unless the reader picks another.
 */
enum class AppLogo(
    val alias: String,
    @DrawableRes val mark: Int,
    val title: StringResource,
    /** The icon's background, top to bottom, and the colour its opening glows with. */
    val top: Color,
    val bottom: Color,
    val accent: Color,
    val intro: IntroStyle,
) {
    CLASSIC("LauncherClassic", R.drawable.manhwa_logo, ATMR.strings.logo_classic, Color(0xFF050507), Color(0xFF8E0F1F), Color(0xFFFF3B4E), IntroStyle.RIFT),
    NOVA("LauncherNova", R.drawable.manhwa_logo_nova, ATMR.strings.logo_nova, Color(0xFF0B1220), Color(0xFF1E3A8A), Color(0xFF3B82F6), IntroStyle.ORBIT),
    GOLD("LauncherGold", R.drawable.manhwa_logo_gold, ATMR.strings.logo_gold, Color(0xFF0A0806), Color(0xFF2A1F0A), Color(0xFFF5C451), IntroStyle.BLOOM),
    NEON("LauncherNeon", R.drawable.manhwa_logo_neon, ATMR.strings.logo_neon, Color(0xFF12002B), Color(0xFF3B0764), Color(0xFF22D3EE), IntroStyle.GLITCH),
    PAPER("LauncherPaper", R.drawable.manhwa_logo_paper, ATMR.strings.logo_paper, Color(0xFFF7F3EA), Color(0xFFEDE6D6), Color(0xFFC8102E), IntroStyle.RISE),
    ;

    companion object {
        fun of(name: String?): AppLogo = entries.firstOrNull { it.name == name } ?: CLASSIC

        /**
         * Shows [logo] on the home screen: its alias is turned on first, then the others off, so the app is never
         * left without an icon. The launcher redraws it on its own, which some take a few seconds to do.
         */
        fun applyIcon(context: Context, logo: AppLogo) {
            val pm = context.packageManager
            fun set(entry: AppLogo, on: Boolean) {
                val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                // The aliases are named in the code's namespace, whatever the build's application id
                val component = ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.${entry.alias}")
                try {
                    if (pm.getComponentEnabledSetting(component) != state) {
                        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Could not switch the launcher icon to ${entry.name}" }
                }
            }
            set(logo, true)
            entries.filter { it != logo }.forEach { set(it, false) }
        }
    }
}

/** The openings that can play on a cold start. AUTO plays the one of the logo picked. */
enum class IntroStyle(val title: StringResource) {
    AUTO(ATMR.strings.intro_auto),
    RIFT(ATMR.strings.intro_rift),
    BLOOM(ATMR.strings.intro_bloom),
    GLITCH(ATMR.strings.intro_glitch),
    ORBIT(ATMR.strings.intro_orbit),
    RISE(ATMR.strings.intro_rise),
    ;

    companion object {
        fun of(name: String?): IntroStyle = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
