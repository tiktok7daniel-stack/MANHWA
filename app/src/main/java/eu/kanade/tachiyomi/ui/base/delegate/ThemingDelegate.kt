package eu.kanade.tachiyomi.ui.base.delegate

import android.app.Activity
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.tachiyomi.R

interface ThemingDelegate {
    fun applyAppTheme(activity: Activity)

    companion object {
        /** MANHWA Glass only: the base theme with the pure black overlay the glass sits on. */
        @Suppress("UNUSED_PARAMETER")
        fun getThemeResIds(appTheme: AppTheme, isAmoled: Boolean): List<Int> {
            return listOf(R.style.Theme_Tachiyomi, R.style.ThemeOverlay_Tachiyomi_Amoled)
        }
    }
}

class ThemingDelegateImpl : ThemingDelegate {
    override fun applyAppTheme(activity: Activity) {
        ThemingDelegate.getThemeResIds(AppTheme.DANGHWA, true).forEach(activity::setTheme)
    }
}
