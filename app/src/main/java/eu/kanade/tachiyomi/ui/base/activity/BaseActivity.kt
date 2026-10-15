package eu.kanade.tachiyomi.ui.base.activity

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegateImpl
import eu.kanade.tachiyomi.util.system.prepareTabletUiContext
import mihon.app.di.appGraph

open class BaseActivity :
    AppCompatActivity(),
    SecureActivityDelegate by SecureActivityDelegateImpl(),
    ThemingDelegate by ThemingDelegateImpl() {

    override fun attachBaseContext(newBase: Context) {
        val uiPreferences = newBase.appGraph.uiPreferences
        super.attachBaseContext(newBase.prepareTabletUiContext(uiPreferences))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppTheme(this)
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
    }

    override fun onResume() {
        super.onResume()
        // The display can change (folding phones, external screens), so ask again
        requestHighestRefreshRate()
    }

    /**
     * MANHWA: asks for the fastest refresh rate the screen has (90, 120, 144 Hz...) at its current resolution, so
     * scrolling and animations run at the highest frame rate the phone supports.
     */
    private fun requestHighestRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay)
                ?: return
            val current = display.mode
            val best = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }
                ?: return
            val attributes = window.attributes
            if (attributes.preferredDisplayModeId != best.modeId || attributes.preferredRefreshRate != best.refreshRate) {
                attributes.preferredDisplayModeId = best.modeId
                attributes.preferredRefreshRate = best.refreshRate
                window.attributes = attributes
            }
        }
    }
}
