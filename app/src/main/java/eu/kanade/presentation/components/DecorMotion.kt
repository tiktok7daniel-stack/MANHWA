package eu.kanade.presentation.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * MANHWA battery: when the app's decorative motion (frames, effects, glows, the poster, the music bars) may move, and
 * how fast. One clock for all of it, about 30 frames a second (15 when the phone saves power or runs hot), that
 * stands still when nobody has touched the screen for a while, when the phone's animations are off, or when it is
 * too hot. It picks up again at the next touch. The reader, the player and every real transition are not affected.
 */
object DecorMotion {

    enum class Level { FULL, LITE, OFF }

    /** No touch for this long and the decoration rests. */
    private const val IDLE_MS = 20_000L

    /** While music plays the bars dance a little longer before resting. */
    private const val IDLE_MUSIC_MS = 90_000L

    private val _awake = MutableStateFlow(true)
    val awake: StateFlow<Boolean> = _awake.asStateFlow()

    private val _level = MutableStateFlow(Level.FULL)
    val level: StateFlow<Level> = _level.asStateFlow()

    @Volatile private var lastTouch = SystemClock.uptimeMillis()

    private val scope = MainScope()
    private var watcher: Job? = null
    private var started = false

    /** A touch anywhere: the decoration wakes (cheap: called for every touch event). */
    fun touched() {
        lastTouch = SystemClock.uptimeMillis()
        if (!_awake.value) {
            _awake.value = true
            watch()
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = refresh(app, intent)
        }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            app.getSystemService(PowerManager::class.java)?.addThermalStatusListener { refresh(app, null) }
        }
        refresh(app, sticky)
        watch()
    }

    @Volatile private var batteryLow = false

    private fun refresh(context: Context, battery: Intent?) {
        if (battery != null && battery.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            batteryLow = level >= 0 && level * 100 / scale <= 20 && !plugged
        }
        val power = context.getSystemService(PowerManager::class.java)
        val saving = power?.isPowerSaveMode == true
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus ?: 0 else 0
        _level.value = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Level.OFF
            saving || batteryLow || thermal >= PowerManager.THERMAL_STATUS_MODERATE -> Level.LITE
            else -> Level.FULL
        }
    }

    /** Puts the decoration to rest once the screen has been left alone; runs only while it is awake. */
    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            while (_awake.value) {
                delay(5_000)
                val limit = if (eu.kanade.tachiyomi.music.MusicPlayer.state.value.playing) IDLE_MUSIC_MS else IDLE_MS
                if (SystemClock.uptimeMillis() - lastTouch > limit) _awake.value = false
            }
        }
    }
}

/** The decoration's clock, in seconds; it only moves while [DecorMotion] lets it. Read it while drawing. */
val LocalDecorTime = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(STILL_DECOR_TIME) }

/** The moment still decoration is shown at (gems lit, light half way round). */
const val STILL_DECOR_TIME = 1.7f

/** Where a loop of [seconds] stands now, 0 to 1. */
fun State<Float>.loop(seconds: Float): Float = (value / seconds).let { it - floor(it) }

/** A smooth swing 0 to 1 and back over [seconds]. */
fun State<Float>.swing(seconds: Float): Float = 0.5f - 0.5f * cos(value / seconds * 2f * PI.toFloat())

/**
 * Runs the decoration's clock for everything inside. It ticks by real time between frames it asks for itself, so
 * the screen is only redrawn about 30 times a second for decoration (15 when saving power), and not at all while
 * it rests or the app is in the background.
 */
@Composable
fun ProvideDecorClock(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember { DecorMotion.start(context) }
    val reduce = rememberReduceMotion()
    val awake by DecorMotion.awake.collectAsState()
    val level by DecorMotion.level.collectAsState()
    val time = remember { mutableFloatStateOf(STILL_DECOR_TIME) }
    if (awake && !reduce && level != DecorMotion.Level.OFF) {
        LaunchedEffect(level) {
            val step = if (level == DecorMotion.Level.FULL) 33L else 66L
            var last = SystemClock.uptimeMillis()
            while (true) {
                delay(step)
                // Waits for a frame, so nothing ticks while the app is out of sight
                withFrameMillis { }
                val now = SystemClock.uptimeMillis()
                // A long pause (the app was away) does not jump the motion ahead
                time.floatValue += ((now - last).coerceAtMost(100L)) / 1000f
                last = now
            }
        }
    }
    CompositionLocalProvider(LocalDecorTime provides time, content = content)
}

/** A value going from [from] to [to] over [millis] and starting again, on the decoration clock. */
@Composable
fun decorLoop(from: Float, to: Float, millis: Int): State<Float> {
    val decor = LocalDecorTime.current
    return remember(decor, from, to, millis) { androidx.compose.runtime.derivedStateOf { from + (to - from) * decor.loop(millis / 1000f) } }
}

/** A value going from [from] to [to] over [millis] and back, on the decoration clock. */
@Composable
fun decorSwing(from: Float, to: Float, millis: Int): State<Float> {
    val decor = LocalDecorTime.current
    return remember(decor, from, to, millis) { androidx.compose.runtime.derivedStateOf { from + (to - from) * decor.swing(2 * millis / 1000f) } }
}
