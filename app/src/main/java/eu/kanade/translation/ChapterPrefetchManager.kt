package eu.kanade.translation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.translation.team.QuotaForecast
import eu.kanade.translation.team.TeamStore
import tachiyomi.domain.translation.TranslationPreferences

/**
 * MANHWA chapter prefetch: decides when the next chapter may be translated in the background while one is read.
 * The reader's own "translate the next chapter" setting always does it; the automatic one does it only when the
 * team has enough working keys with quota left, and never on low battery, battery saver or without internet.
 */
object ChapterPrefetchManager {

    /** Share of the chapter read before the next one starts. */
    const val START_AT = 0.35

    /** Working keys the automatic prefetch needs (one for reading, the others for the next chapter). */
    const val MIN_KEYS = 3

    const val MIN_BATTERY = 20

    data class Device(val batteryPercent: Int, val charging: Boolean, val powerSave: Boolean, val online: Boolean)

    /** The pure rule, so it can be tested without a phone. */
    fun allowed(manual: Boolean, auto: Boolean, workingKeys: Int, device: Device, phoneModel: Boolean = false): Boolean {
        // MANHWA: the phone model needs no internet for a chapter already downloaded
        if (!device.online && !phoneModel) return false
        if (manual) return true
        // MANHWA: the phone model translates ahead with no key at all (when the phone is cool enough, see below)
        if (!auto || (workingKeys < MIN_KEYS && !phoneModel)) return false
        // MANHWA: the phone model works at full strength whatever the battery (the reader's choice)
        if (phoneModel) return true
        if (device.powerSave) return false
        return device.charging || device.batteryPercent >= MIN_BATTERY
    }

    fun device(context: Context): Device {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return Device(
            batteryPercent = if (level < 0 || scale <= 0) 100 else level * 100 / scale,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            powerSave = power?.isPowerSaveMode == true,
            online = runCatching { context.isOnline() }.getOrDefault(true),
        )
    }

    /** Keys that work and still have more than the background floor of their quota today. */
    fun workingKeys(store: TeamStore): Int = store.active().count { m ->
        val level = store.usage.level(m)
        !level.exhausted && level.percent > QuotaForecast.BACKGROUND_FLOOR_PERCENT
    }

    fun shouldPrefetch(context: Context, store: TeamStore, prefs: TranslationPreferences): Boolean =
        allowed(
            manual = prefs.translationPretranslateNext().get(),
            auto = prefs.translationAutoPrefetch().get(),
            workingKeys = workingKeys(store),
            device = device(context),
            // Only a cool phone, on charge or with battery to spare: the heat and battery guard says FULL
            phoneModel = eu.kanade.translation.local.LocalAiEngine.available(context) &&
                eu.kanade.translation.local.LocalAiRules.governor(eu.kanade.translation.local.PhoneState.read(context)) ==
                eu.kanade.translation.local.LocalAiRules.Power.FULL,
        )
}
