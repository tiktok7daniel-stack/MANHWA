package eu.kanade.translation.team

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.translation.settings.HubIconTile
import eu.kanade.translation.translator.AiTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CloudSync
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.Home
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.RocketLaunch
import mihon.icons.materialsymbols.rounded.Verified
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreference

/** MANHWA home server: the pure rules (tested without a phone). */
object LocalServerRules {
    const val AUTO = "AUTO"
    const val HOME = "HOME"
    const val CLOUD = "CLOUD"

    /** A ping waits this long at most: an answer from the same house comes in well under a second. */
    const val PING_MS = 3_000L

    /** A ping is trusted this long, then asked again (on the same Wi-Fi it takes a fraction of a second). */
    const val CACHE_MS = 60_000L

    /** Failures in a row of calls that reached the laptop before it counts as away for a while. */
    const val AWAY_STRIKES = 2

    /** A failure that says the laptop could not be reached at all (not a slow or wrong answer). */
    fun unreachable(error: Throwable): Boolean = when (error) {
        is java.net.ConnectException, is java.net.UnknownHostException, is java.net.NoRouteToHostException -> true
        // A connect that timed out is no laptop; a read that timed out is a busy one
        is java.net.SocketTimeoutException -> error.message.orEmpty().contains("connect", ignoreCase = true)
        else -> error.cause?.let(::unreachable) ?: false
    }

    /** A laptop that did not answer is asked again sooner: back home, it takes over within half a minute. */
    const val AWAY_MS = 30_000L

    enum class Serving { HOME, CLOUD, PHONE }

    /**
     * Who translates: in [AUTO] the home servers that answer, else the [others] (cloud keys; none means the
     * phone's model); [HOME] the home servers only; [CLOUD] never them.
     */
    fun <T> pick(mode: String, answering: List<T>, home: List<T>, others: List<T>): List<T> = when (mode) {
        HOME -> home
        CLOUD -> others
        else -> answering.ifEmpty { others }
    }

    fun fresh(at: Long, now: Long, answered: Boolean = true): Boolean = now - at in 0 until if (answered) CACHE_MS else AWAY_MS

    /** "192.168.1.50:11434" or "laptop-name:11434" made a full server address ("http://…/v1"). */
    fun normalize(input: String): String {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if ("://" !in url) url = "http://$url"
        if (url.endsWith("/models")) url = url.removeSuffix("/models")
        if (!Regex("/v\\d+$").containsMatchIn(url)) url += "/v1"
        return url
    }

    fun modelsUrl(baseUrl: String): String = normalize(baseUrl) + "/models"

    /** Calls the laptop runs at once from the start: a GPU answers two together about as fast as one. */
    const val START_CALLS = 2.0

    /**
     * At most, unless set otherwise on the laptop's screen: more only wait in the laptop's own queue (Ollama runs
     * one or a few calls at once unless OLLAMA_NUM_PARALLEL says more; vLLM and TabbyAPI take many).
     */
    const val MAX_CALLS = 2

    /** The calls the laptop may be set to run at once. */
    val CALL_CHOICES = listOf(2, 4, 6, 8)

    /** A stored setting made a safe number of calls (2 to 8). */
    fun calls(setting: Int): Int = setting.coerceIn(CALL_CHOICES.first(), CALL_CHOICES.last())

    /** How long a batch may take on the laptop: a base, and some seconds a line (a slow GPU still finishes). */
    fun batchTimeoutMs(lines: Int): Long = (60_000L + lines * 8_000L).coerceAtLeast(150_000L)

    /** A ready model for the laptop: its Ollama name and what it is for (a translation-only model when [translator]). */
    data class Preset(val model: String, val fastest: Boolean, val translator: Boolean = false)

    /**
     * The ready models for an RTX 4060 (8 GB) with 16 GB of RAM, best first: Aya Expanse 8B fits whole on the card
     * (the fastest strong one, good in Arabic); Gemma 3 12B spills a little into the RAM (slower, more accurate);
     * Hunyuan-MT 1.5 7B is made only to translate (fits on the card, strong in Arabic, no names or notes).
     */
    val PRESETS = listOf(
        Preset("aya-expanse:8b", fastest = true),
        Preset("gemma3:12b", fastest = false),
        Preset("ali6parmak/hy-mt1.5:7b", fastest = false, translator = true),
    )

    /** The model to pick from what the laptop has: a ready one first, in the order above, else the first listed. */
    fun preferredModel(available: List<String>): String? =
        PRESETS.firstNotNullOfOrNull { p -> available.firstOrNull { it.equals(p.model, ignoreCase = true) || it.startsWith(p.model + "-", ignoreCase = true) } }
            ?: available.firstOrNull()

    /** The model ids of an OpenAI-style list ({"data":[{"id":…}]}); null when it is not one. */
    fun modelIds(body: String): List<String>? = runCatching {
        Json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
    }.getOrNull()
}

/** The home server on the team screen: whether it answers now, and the way to its settings. */
@Composable
internal fun HomeServerCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val store = remember { context.appGraph.teamStore }
    val members by store.members.collectAsState()
    val home = members.firstOrNull { it.isHomeServer }
    // Asked again while the screen is open: the laptop may come and go
    val answers by produceState<Boolean?>(null, home?.baseUrl) {
        val url = home?.baseUrl ?: return@produceState
        while (true) {
            // Asked whatever the "translate on" choice, so the card never stays on "checking"
            store.pingHome(force = true)
            value = store.homeKnown(url)
            kotlinx.coroutines.delay(10_000)
        }
    }
    val (text, color) = when {
        home == null -> stringResource(ATMR.strings.hs_card_none) to Color.White.copy(alpha = 0.6f)
        answers == true -> stringResource(ATMR.strings.hs_card_on, home.model) to Color(0xFF3DDC97)
        answers == false -> (stringResource(ATMR.strings.hs_card_off) + (store.homeProblem(home.baseUrl)?.let { "\n$it" } ?: "")) to Color(0xFFFFB020)
        else -> stringResource(ATMR.strings.hs_card_checking) to Color.White.copy(alpha = 0.6f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), accent = answers == true, alpha = 0.5f)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HubIconTile(MaterialSymbols.Rounded.Home, size = 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(ATMR.strings.hs_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

/**
 * MANHWA home server: the reader's laptop (Ollama, LM Studio, llama.cpp) as a member of the translation team. It
 * translates while it answers; away from home the other keys or the phone's model take over by themselves.
 */
class HomeServerScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val store = remember { context.appGraph.teamStore }
        val prefs = remember { context.appGraph.translationPreferences }
        val scope = rememberCoroutineScope()
        val existing = remember { store.members.value.firstOrNull { it.isHomeServer } }
        var address by remember { mutableStateOf(existing?.baseUrl ?: "") }
        var model by remember { mutableStateOf(existing?.model ?: LocalServerRules.PRESETS.first().model) }
        var key by remember { mutableStateOf(existing?.key ?: "") }
        var models by remember { mutableStateOf<List<String>?>(null) }
        var testing by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        var saved by remember { mutableStateOf(false) }
        val mode by prefs.translationServerMode().collectPreference()
        val rocket by prefs.translationTranslateOnly().collectPreference()
        val calls by prefs.translationHomeCalls().collectPreference()
        val okTemplate = stringResource(ATMR.strings.hs_test_ok)
        val failText = stringResource(ATMR.strings.hs_test_fail)
        val memberName = stringResource(ATMR.strings.hs_member_name)
        val missingText = stringResource(ATMR.strings.hs_missing)
        val bottom = WindowInsets.navigationBars.asPaddingValues()

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.hs_title),
                    subtitle = stringResource(ATMR.strings.hs_subtitle),
                    onBack = navigator::pop,
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Section {
                            OutlinedTextField(
                                value = address,
                                onValueChange = { address = it },
                                label = { Text(stringResource(ATMR.strings.hs_address)) },
                                placeholder = { Text("http://192.168.1.50:11434/v1") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                label = { Text(stringResource(ATMR.strings.hs_model)) },
                                placeholder = { Text(LocalServerRules.PRESETS.first().model) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = key,
                                onValueChange = { key = it },
                                label = { Text(stringResource(ATMR.strings.hs_key)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ManhwaKit.GhostButton(
                                text = if (testing) stringResource(ATMR.strings.hs_testing) else stringResource(ATMR.strings.hs_test),
                                icon = MaterialSymbols.Rounded.Refresh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (testing) return@GhostButton
                                testing = true
                                result = null
                                scope.launch {
                                    val url = LocalServerRules.normalize(address)
                                    address = url
                                    val check = withContext(Dispatchers.IO) { store.homeCheck(url, key) }
                                    val found = check.models
                                    models = found
                                    if (found != null && (model.isBlank() || model !in found)) model = LocalServerRules.preferredModel(found).orEmpty()
                                    // The reason it failed, so a wrong port or a closed firewall can be told apart
                                    result = if (found == null) failText + (check.problem?.let { "\n$it" } ?: "") else okTemplate.replace("%1\$d", "${found.size}")
                                    testing = false
                                }
                            }
                            result?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = if (models != null) Color(0xFF3DDC97) else Color(0xFFFFB020))
                            }
                            models?.takeIf { it.isNotEmpty() }?.let { list ->
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    list.take(30).forEach { id ->
                                        FilterChip(selected = id == model, onClick = { model = id }, label = { Text(id) })
                                    }
                                }
                            }
                            ManhwaKit.NeonButton(
                                text = stringResource(ATMR.strings.hs_save),
                                icon = MaterialSymbols.Rounded.Verified,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val url = LocalServerRules.normalize(address)
                                if (url.isBlank() || model.isBlank()) {
                                    result = missingText
                                    return@NeonButton
                                }
                                val current = store.members.value.firstOrNull { it.isHomeServer }
                                val member = (current ?: store.add(key, AiTranslator.Provider.OPENAI_COMPATIBLE, model)).copy(
                                    provider = AiTranslator.Provider.OPENAI_COMPATIBLE.name,
                                    key = key.trim(),
                                    model = model.trim(),
                                    baseUrl = url,
                                    site = KeySites.HOME_SERVER,
                                    siteName = memberName,
                                    enabled = true,
                                )
                                store.update(member)
                                scope.launch { store.pingHome(force = true) }
                                saved = true
                            }
                            if (saved) Text(stringResource(ATMR.strings.hs_saved), style = MaterialTheme.typography.bodySmall, color = Color(0xFF3DDC97))
                        }
                    }
                    item {
                        Section {
                            Text(stringResource(ATMR.strings.hs_mode), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    LocalServerRules.AUTO to ATMR.strings.hs_mode_auto,
                                    LocalServerRules.HOME to ATMR.strings.hs_mode_home,
                                    LocalServerRules.CLOUD to ATMR.strings.hs_mode_cloud,
                                ).forEach { (value, label) ->
                                    FilterChip(selected = mode == value, onClick = { prefs.translationServerMode().set(value) }, label = { Text(stringResource(label)) })
                                }
                            }
                            Help(stringResource(ATMR.strings.hs_mode_help))
                        }
                    }
                    item {
                        Section {
                            Text(stringResource(ATMR.strings.hs_calls), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LocalServerRules.CALL_CHOICES.forEach { value ->
                                    FilterChip(
                                        selected = LocalServerRules.calls(calls) == value,
                                        onClick = { prefs.translationHomeCalls().set(value) },
                                        label = { Text(value.toString()) },
                                    )
                                }
                            }
                            Help(stringResource(ATMR.strings.hs_calls_help))
                        }
                    }
                    item {
                        Section {
                            Text(stringResource(ATMR.strings.hs_servers_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
                            Help(stringResource(ATMR.strings.hs_servers))
                        }
                    }
                    item {
                        Section {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HubIconTile(MaterialSymbols.Rounded.RocketLaunch, size = 36)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(ATMR.strings.hs_rocket),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(checked = rocket, onCheckedChange = { prefs.translationTranslateOnly().set(it) })
                            }
                            Help(stringResource(ATMR.strings.hs_rocket_help))
                        }
                    }
                    item {
                        Section {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HubIconTile(MaterialSymbols.Rounded.CloudSync, size = 36)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(ATMR.strings.hs_away_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
                            }
                            Help(stringResource(ATMR.strings.hs_tailscale))
                        }
                    }
                    item {
                        Section {
                            Text(stringResource(ATMR.strings.hs_models_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
                            Help(stringResource(ATMR.strings.hs_models))
                            // MANHWA: the ready models, one tap to use, the command to download them one tap to copy
                            LocalServerRules.PRESETS.forEach { preset ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .glass(RoundedCornerShape(18.dp), accent = preset.model == model, alpha = 0.4f)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        preset.model + if (preset.fastest) " · " + stringResource(ATMR.strings.hs_preset_best) else "",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Black,
                                        color = if (preset.fastest) Color(0xFF3DDC97) else Color.White,
                                    )
                                    Help(
                                        stringResource(
                                            when {
                                                preset.fastest -> ATMR.strings.hs_preset_fast
                                                preset.translator -> ATMR.strings.hs_preset_mt
                                                else -> ATMR.strings.hs_preset_quality
                                            },
                                        ),
                                    )
                                    Text("ollama pull ${preset.model}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.9f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ManhwaKit.GhostButton(stringResource(ATMR.strings.hs_preset_copy), icon = MaterialSymbols.Rounded.ContentCopy, modifier = Modifier.weight(1f)) {
                                            context.getSystemService(android.content.ClipboardManager::class.java)
                                                ?.setPrimaryClip(android.content.ClipData.newPlainText("MANHWA", "ollama pull ${preset.model}"))
                                        }
                                        ManhwaKit.NeonButton(stringResource(ATMR.strings.hs_preset_use), icon = MaterialSymbols.Rounded.Verified, modifier = Modifier.weight(1f)) {
                                            model = preset.model
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Section {
                            Text(stringResource(ATMR.strings.hs_setup_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
                            Help(stringResource(ATMR.strings.hs_setup))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Section(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), alpha = 0.5f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }

    @Composable
    private fun Help(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
    }
}
