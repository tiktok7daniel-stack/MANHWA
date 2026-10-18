package eu.kanade.tachiyomi.ui.home.smart

import eu.kanade.presentation.components.ManhwaInk

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState

/** Space used by downloads, per series, plus the size of saved translations. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SmartDownloadViewModel(
    private val storageManager: StorageManager,
    val preferences: DownloadPreferences,
) : ViewModel() {

    data class Usage(val name: String, val bytes: Long)

    data class State(
        val loading: Boolean = true,
        val total: Long = 0,
        val translations: Long = 0,
        val top: List<Usage> = emptyList(),
        val series: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        measure()
    }

    private var measureJob: kotlinx.coroutines.Job? = null

    fun measure() {
        measureJob?.cancel()
        _state.update { it.copy(loading = true) }
        measureJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                scan()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage not set up or permission lost: show what we have instead of crashing
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun scan() {
        run {
            val perSeries = mutableListOf<Usage>()
            storageManager.getDownloadsDirectory()?.listFiles()?.forEach { sourceDir ->
                sourceDir.listFiles()?.forEach { mangaDir ->
                    if (mangaDir.isDirectory) perSeries += Usage(mangaDir.name.orEmpty(), sizeOf(mangaDir))
                }
            }
            val translations = storageManager.getTranslationsDirectory()?.let(::sizeOf) ?: 0
            val sorted = perSeries.sortedByDescending { it.bytes }
            _state.update {
                State(
                    loading = false,
                    total = sorted.sumOf { u -> u.bytes },
                    translations = translations,
                    top = sorted.take(6),
                    series = sorted.size,
                )
            }
        }
    }

    private fun sizeOf(file: UniFile): Long =
        if (file.isDirectory) file.listFiles()?.sumOf(::sizeOf) ?: 0 else file.length()
}

/** "Smart download": read ahead, free space after reading, and a chart of what uses the storage. */
class SmartDownloadScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<SmartDownloadViewModel>()
        val state by viewModel.state.collectAsState()
        val prefs = viewModel.preferences
        val ahead by prefs.autoDownloadWhileReading.collectPrefAsState()
        val removeSlots by prefs.removeAfterReadSlots.collectPrefAsState()
        val removeMarked by prefs.removeAfterMarkedAsRead.collectPrefAsState()
        val fmt = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.smart_title),
                    subtitle = stringResource(ATMR.strings.smart_subtitle),
                    onBack = navigator::pop,
                )
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        StorageCard(state = state, fmt = fmt)
                    }
                    item {
                        SettingCard(
                            title = stringResource(ATMR.strings.smart_preload),
                            body = stringResource(ATMR.strings.smart_preload_body),
                            checked = ahead > 0,
                            onChecked = { prefs.autoDownloadWhileReading.set(if (it) 2 else 0) },
                        ) {
                            if (ahead > 0) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(ATMR.strings.smart_preload_count),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ManhwaInk.text.copy(alpha = 0.6f),
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(1, 2, 3, 5, 10).forEach { n ->
                                        ManhwaKit.Chip("$n", ahead == n) { prefs.autoDownloadWhileReading.set(n) }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SettingCard(
                            title = stringResource(ATMR.strings.smart_cleanup),
                            body = stringResource(ATMR.strings.smart_cleanup_body),
                            checked = removeSlots >= 0,
                            onChecked = { prefs.removeAfterReadSlots.set(if (it) 0 else -1) },
                        ) {
                            if (removeSlots >= 0) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(ATMR.strings.smart_cleanup_keep),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ManhwaInk.text.copy(alpha = 0.6f),
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0, 1, 2, 3, 5).forEach { n ->
                                        ManhwaKit.Chip("$n", removeSlots == n) { prefs.removeAfterReadSlots.set(n) }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SettingCard(
                            title = stringResource(ATMR.strings.smart_marked),
                            body = stringResource(ATMR.strings.smart_marked_body),
                            checked = removeMarked,
                            onChecked = { prefs.removeAfterMarkedAsRead.set(it) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StorageCard(state: SmartDownloadViewModel.State, fmt: (Long) -> String) {
        val colors = MaterialTheme.colorScheme
        val palette = listOf(
            colors.primary,
            lerp(colors.primary, Color.White, 0.35f),
            Color(0xFF9B5CFF),
            Color(0xFFFFB020),
            Color(0xFF00D1E0),
            Color(0xFF3DDC97),
        )
        val all = state.total + state.translations
        val sweep by animateFloatAsState(if (state.loading) 0f else 1f, animationSpec = tween(1_200), label = "donut")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(28.dp), accent = true, alpha = 0.45f)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize().neonGlow(CircleShape, colors.primary, 0.12f, 14.dp)) {
                        val stroke = 18.dp.toPx()
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        val topLeft = Offset(stroke / 2, stroke / 2)
                        drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                        if (all > 0) {
                            var start = -90f
                            val parts = state.top.map { it.bytes } +
                                listOf((state.total - state.top.sumOf { it.bytes }).coerceAtLeast(0), state.translations)
                            parts.forEachIndexed { i, bytes ->
                                if (bytes <= 0) return@forEachIndexed
                                val angle = 360f * bytes / all * sweep
                                val color = when {
                                    i < state.top.size -> palette[i % palette.size]
                                    i == state.top.size -> Color.White.copy(alpha = 0.3f)
                                    else -> Color(0xFF4F8BFF)
                                }
                                drawArc(color, start, (angle - 1.5f).coerceAtLeast(0.5f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                                start += angle
                            }
                        }
                    }
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(fmt(all), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = ManhwaInk.text)
                            Text(
                                stringResource(ATMR.strings.smart_series, state.series),
                                style = MaterialTheme.typography.labelSmall,
                                color = ManhwaInk.text.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.top.forEachIndexed { i, usage ->
                        Legend(palette[i % palette.size], usage.name, fmt(usage.bytes))
                    }
                    if (state.translations > 0) {
                        Legend(Color(0xFF4F8BFF), stringResource(ATMR.strings.smart_translations), fmt(state.translations))
                    }
                }
            }
        }
    }

    @Composable
    private fun Legend(color: Color, name: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = ManhwaInk.text.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ManhwaInk.text)
        }
    }

    @Composable
    private fun SettingCard(
        title: String,
        body: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit,
        extra: @Composable () -> Unit = {},
    ) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), accent = checked, alpha = 0.42f)
                .clickable { onChecked(!checked) }
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(body, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = onChecked,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colors.primary,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                        uncheckedBorderColor = Color.White.copy(alpha = 0.2f),
                    ),
                )
            }
            extra()
        }
    }
}
