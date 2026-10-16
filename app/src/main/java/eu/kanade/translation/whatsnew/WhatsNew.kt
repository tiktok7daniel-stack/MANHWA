package eu.kanade.translation.whatsnew

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Autorenew
import mihon.icons.materialsymbols.rounded.Bedtime
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Group
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.rounded.Tune
import mihon.icons.materialsymbols.rounded.Key
import mihon.icons.materialsymbols.rounded.NewReleases
import mihon.icons.materialsymbols.rounded.Psychology
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.RecordVoiceOver
import mihon.icons.materialsymbols.rounded.School
import mihon.icons.materialsymbols.rounded.SearchCheck
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.SwapCalls
import mihon.icons.materialsymbols.rounded.Verified
import mihon.icons.materialsymbols.rounded.Widgets
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA "what's new": a luxury glass showcase of this update, shown once after the opening the first time a
 * new build is opened, then never again until the next build. It can be opened by hand from the settings.
 */
object WhatsNew {

    /** One build is one update: the commit count and its commit. */
    val buildId: String get() = "${BuildConfig.COMMIT_COUNT}-${BuildConfig.COMMIT_SHA}"

    val buildLabel: String get() = BuildConfig.COMMIT_COUNT

    private fun prefs(context: Context) = context.getSharedPreferences("manhwa_whats_new", Context.MODE_PRIVATE)

    fun shouldShow(context: Context): Boolean = WhatsNewRule.shouldShow(prefs(context).getString(KEY, null), buildId)

    fun markSeen(context: Context) {
        prefs(context).edit().putString(KEY, buildId).apply()
    }

    class Feature(val icon: ImageVector, val title: StringResource, val body: StringResource)

    /**
     * What changed in this update only: the window after the intro shows these. Replaced with every update;
     * what was here before moves to the top of [earlier].
     */
    val latest: List<Feature>
        get() = listOf(
            Feature(MaterialSymbols.Rounded.Translate, ATMR.strings.wn_tn_t, ATMR.strings.wn_tn_b),
            Feature(MaterialSymbols.Rounded.Tune, ATMR.strings.wn_count_t, ATMR.strings.wn_count_b),
        )

    /** Everything from earlier updates, newest first (shown in Settings, "What's new", below [latest]). */
    val earlier: List<Feature>
        get() = listOf(
            // Build 45
            Feature(MaterialSymbols.Rounded.Group, ATMR.strings.wn_ensemble_t, ATMR.strings.wn_ensemble_b),
            Feature(MaterialSymbols.Rounded.Download, ATMR.strings.wn_shelf_t, ATMR.strings.wn_shelf_b),
            Feature(MaterialSymbols.Rounded.NewReleases, ATMR.strings.wn_whatsnew_t, ATMR.strings.wn_whatsnew_b),
            // Build 39: the five big ones first, the orchestra that joins them
            Feature(MaterialSymbols.Rounded.AutoAwesome, ATMR.strings.wn_orchestra_t, ATMR.strings.wn_orchestra_b),
            Feature(MaterialSymbols.Rounded.Psychology, ATMR.strings.wn_local_t, ATMR.strings.wn_local_b),
            Feature(MaterialSymbols.Rounded.AutoAwesome, ATMR.strings.wn_clean_t, ATMR.strings.wn_clean_b),
            Feature(MaterialSymbols.Rounded.Group, ATMR.strings.wn_brain_t, ATMR.strings.wn_brain_b),
            Feature(MaterialSymbols.Rounded.RecordVoiceOver, ATMR.strings.wn_style_t, ATMR.strings.wn_style_b),
            Feature(MaterialSymbols.Rounded.Public, ATMR.strings.wn_unified_t, ATMR.strings.wn_unified_b),
            Feature(MaterialSymbols.Rounded.Key, ATMR.strings.wn_keys_t, ATMR.strings.wn_keys_b),
            Feature(MaterialSymbols.Rounded.Verified, ATMR.strings.wn_fix_t, ATMR.strings.wn_fix_b),
            Feature(MaterialSymbols.Rounded.Public, ATMR.strings.wn_sources_t, ATMR.strings.wn_sources_b),
            Feature(MaterialSymbols.Rounded.Autorenew, ATMR.strings.wn_org_t, ATMR.strings.wn_org_b),
            Feature(MaterialSymbols.Rounded.CollectionsBookmark, ATMR.strings.wn_save_t, ATMR.strings.wn_save_b),
            Feature(MaterialSymbols.Rounded.SkipNext, ATMR.strings.wn_prefetch_t, ATMR.strings.wn_prefetch_b),
            Feature(MaterialSymbols.Rounded.Bedtime, ATMR.strings.wn_night_t, ATMR.strings.wn_night_b),
            Feature(MaterialSymbols.Rounded.Psychology, ATMR.strings.wn_consensus_t, ATMR.strings.wn_consensus_b),
            Feature(MaterialSymbols.Rounded.Group, ATMR.strings.wn_chars_t, ATMR.strings.wn_chars_b),
            Feature(MaterialSymbols.Rounded.AutoAwesome, ATMR.strings.wn_quality_t, ATMR.strings.wn_quality_b),
            Feature(MaterialSymbols.Rounded.RecordVoiceOver, ATMR.strings.wn_voice_t, ATMR.strings.wn_voice_b),
            Feature(MaterialSymbols.Rounded.School, ATMR.strings.wn_study_t, ATMR.strings.wn_study_b),
            Feature(MaterialSymbols.Rounded.SearchCheck, ATMR.strings.wn_search_t, ATMR.strings.wn_search_b),
            Feature(MaterialSymbols.Rounded.SwapCalls, ATMR.strings.wn_fallback_t, ATMR.strings.wn_fallback_b),
            Feature(MaterialSymbols.Rounded.Verified, ATMR.strings.wn_health_t, ATMR.strings.wn_health_b),
            Feature(MaterialSymbols.Rounded.Widgets, ATMR.strings.wn_widget_t, ATMR.strings.wn_widget_b),
            Feature(MaterialSymbols.Rounded.Psychology, ATMR.strings.wn_learning_t, ATMR.strings.wn_learning_b),
        )

    private const val KEY = "seen_build"
}

/** The pure rule (tested without a phone): shown once for every build not seen yet. */
object WhatsNewRule {
    fun shouldShow(seen: String?, current: String): Boolean = current.isNotBlank() && seen != current
}

private val Obsidian = Color(0xFF060204)
private val Crimson = Color(0xFFE5173F)
private val Ember = Color(0xFFFF6A2B)

/**
 * The showcase over the whole app, after the 3D opening: it springs in, and "Explore the update" closes it
 * softly and remembers this build was seen.
 */
@Composable
fun BoxScope.WhatsNewShowcase(onDismiss: () -> Unit) {
    val scale = remember { Animatable(0.82f) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, tween(380)) }
        scale.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow))
    }
    fun close() {
        scope.launch {
            launch { scale.animateTo(0.9f, tween(220)) }
            alpha.animateTo(0f, tween(240))
            onDismiss()
        }
    }
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Obsidian.copy(alpha = 0.92f))))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .widthIn(max = 520.dp)
                .fillMaxHeight(0.92f)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .neonGlow(RoundedCornerShape(32.dp), Crimson, 0.28f, 22.dp)
                .glass(RoundedCornerShape(32.dp), accent = true, alpha = 0.92f),
        ) {
            ShowcaseBody(Modifier.weight(1f))
            Box(Modifier.padding(18.dp)) {
                ManhwaKit.NeonButton(stringResource(ATMR.strings.wn_start), icon = MaterialSymbols.Rounded.AutoAwesome, modifier = Modifier.fillMaxWidth()) { close() }
            }
        }
    }
}

@Composable
private fun ShowcaseBody(modifier: Modifier = Modifier, withEarlier: Boolean = false) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header() }
        if (withEarlier) item { SectionTitle(stringResource(ATMR.strings.wn_latest)) }
        items(WhatsNew.latest) { f -> FeatureCard(f) }
        if (withEarlier) {
            item { SectionTitle(stringResource(ATMR.strings.wn_earlier)) }
            items(WhatsNew.earlier) { f -> FeatureCard(f) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Black,
        color = Ember,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .neonGlow(RoundedCornerShape(26.dp), Crimson, 0.5f, 18.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(Crimson, Ember)))
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MaterialSymbols.Rounded.NewReleases, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(ATMR.strings.wn_badge, WhatsNew.buildLabel),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Crimson.copy(alpha = 0.85f), Ember.copy(alpha = 0.85f))))
                .padding(horizontal = 14.dp, vertical = 5.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(ATMR.strings.wn_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(ATMR.strings.wn_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FeatureCard(f: WhatsNew.Feature) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), alpha = 0.5f)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .neonGlow(RoundedCornerShape(15.dp), Crimson, 0.3f, 10.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Crimson.copy(alpha = 0.9f), Ember.copy(alpha = 0.75f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(f.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(f.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
            Text(stringResource(f.body), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
        }
    }
}

/** "What's new" opened by hand from the settings: the same showcase as a screen. */
class WhatsNewScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = stringResource(ATMR.strings.wn_menu), onBack = navigator::pop)
                ShowcaseBody(Modifier.weight(1f), withEarlier = true)
                Box(Modifier.padding(18.dp)) {
                    ManhwaKit.NeonButton(stringResource(ATMR.strings.wn_start), icon = MaterialSymbols.Rounded.AutoAwesome, modifier = Modifier.fillMaxWidth()) { navigator.pop() }
                }
            }
        }
    }
}
