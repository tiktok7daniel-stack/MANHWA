package eu.kanade.translation.team

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.translation.translator.appAiConfigured
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.OpenInNew
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/** What the Add-a-key window starts with when it is opened from a site card. */
data class KeyPrefill(val site: String, val baseUrl: String = "", val name: String = "")

/** Opens [url] in Chrome when it is there, else in whatever browser the phone has. */
fun openInBrowser(context: Context, url: String) {
    val target = if ("://" in url) url else "https://$url"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent(intent).setPackage("com.android.chrome"))
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(intent) }
    }
}

fun isArabicUi(): Boolean = Locale.getDefault().language == "ar"

/** A glass, glowing button that opens a link (or anything else) in one tap. */
@Composable
fun SiteLinkButton(text: String, icon: ImageVector = MaterialSymbols.Rounded.OpenInNew, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neonGlow(shape, colors.primary, 0.18f, 8.dp)
            .clip(shape)
            .background(colors.primary.copy(alpha = 0.14f))
            .border(1.dp, colors.primary.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.primary, modifier = Modifier.weight(1f))
    }
}

/**
 * MANHWA key finder: the reader asks where to get free API keys and the team's AI answers with the best
 * sites, what each gives for free, and a card per site with its key page (one tap to Chrome) and "add a key
 * from this site". Before any key exists, the built-in guide of known sites answers instead.
 */
@Composable
fun KeyAdvisorSheet(onDismiss: () -> Unit, onAddFrom: (KeyPrefill) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var question by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var intro by remember { mutableStateOf<String?>(null) }
    var answers by remember { mutableStateOf<List<Advice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val quick = listOf(
        stringResource(ATMR.strings.keys_q_free),
        stringResource(ATMR.strings.keys_q_fast),
        stringResource(ATMR.strings.keys_q_vision),
        stringResource(ATMR.strings.keys_q_new),
    )
    val noKey = stringResource(ATMR.strings.keys_advisor_no_key)
    val failed = stringResource(ATMR.strings.keys_advisor_failed)

    fun ask(text: String) {
        if (text.isBlank() || working) return
        question = text
        if (!appAiConfigured()) {
            error = noKey
            return
        }
        working = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { askAdvisor(text) } }
            working = false
            result.onSuccess { (i, list) ->
                intro = i
                answers = list
            }.onFailure { error = it.message ?: failed }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 12.dp)
                .glass(shape, accent = true, alpha = 0.95f)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(ATMR.strings.keys_advisor_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MaterialSymbols.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height12())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { q ->
                    Text(
                        q,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.primary.copy(alpha = 0.18f))
                            .border(1.dp, colors.primary.copy(alpha = 0.5f), RoundedCornerShape(50))
                            .clickable { ask(q) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height12())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    if (question.isEmpty()) {
                        Text(stringResource(ATMR.strings.keys_advisor_hint), color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.bodyMedium)
                    }
                    BasicTextField(
                        value = question,
                        onValueChange = { question = it },
                        textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(ManhwaKit.accentBrush()).clickable { ask(question) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (working) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height12())
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                error?.let { Text(it, color = Color(0xFFFFB020), style = MaterialTheme.typography.bodyMedium) }
                intro?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
                answers.forEach { advice ->
                    AdviceCard(advice, onOpen = { openInBrowser(context, advice.keysUrl) }, onAdd = { onAddFrom(advice.prefill()) })
                }
                // The built-in guide is always there, and is the answer before any key exists
                Text(
                    stringResource(ATMR.strings.keys_builtin_guide),
                    color = colors.primary,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                KeySites.all.forEach { site ->
                    val advice = Advice(site.name, site.keysUrl, site.baseUrl, if (isArabicUi()) site.freeAr else site.freeEn, "", site.id)
                    AdviceCard(advice, onOpen = { openInBrowser(context, site.keysUrl) }, onAdd = { onAddFrom(advice.prefill()) })
                }
            }
        }
    }
}

private fun Modifier.height12() = this.then(Modifier.padding(top = 12.dp))

/** One site in the advisor's answer. [siteId] is set when it is a site the app knows. */
private data class Advice(val name: String, val keysUrl: String, val baseUrl: String, val free: String, val why: String, val siteId: String?) {
    fun prefill() = if (siteId != null) KeyPrefill(siteId) else KeyPrefill(KeySites.CUSTOM, baseUrl.ifBlank { keysUrl }, name)
}

@Composable
private fun AdviceCard(advice: Advice, onOpen: () -> Unit, onAdd: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), alpha = 0.45f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(advice.name, color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
        Text(advice.keysUrl, color = colors.primary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        if (advice.free.isNotBlank()) Text(advice.free, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        if (advice.why.isNotBlank()) Text(advice.why, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManhwaKit.GhostButton(stringResource(ATMR.strings.keys_open_chrome), Modifier.weight(1f), MaterialSymbols.Rounded.OpenInNew, onOpen)
            ManhwaKit.NeonButton(stringResource(ATMR.strings.keys_add_from_site), Modifier.weight(1f), MaterialSymbols.Rounded.Add, onAdd)
        }
    }
}

/** Asks the team's AI (searching the web when its key can) for the best sites to get free keys. */
private suspend fun askAdvisor(question: String): Pair<String, List<Advice>> {
    val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
    val known = KeySites.all.joinToString("\n") { "- ${it.name}: keys ${it.keysUrl}, API ${it.baseUrl}, free: ${it.freeEn}" }
    val system = """
        You help a manga and manhwa translation app's reader find API keys for AI models, especially free ones or
        ones with free credit. Answer in $language. Recommend 4 to 8 sites that really exist, best first for
        translating comics (quality, speed, free quota), with the exact page where keys are made and the
        OpenAI-compatible API base URL when there is one. Sites the app already knows:
        $known
        You may add others you know of (routers, free model hosts). Never invent a URL; leave baseUrl empty when unsure.
        Reply with ONLY: {"intro":"one or two sentences","sites":[{"name":"...","keysUrl":"https://...","baseUrl":"https://.../v1","free":"what is free and its limits","why":"why it is good for translation"}]}
    """.trimIndent()
    val reply = appAsk(eu.kanade.translation.team.AiRouter.Lane.CHAT) { ai ->
        if (ai.canSearchWeb) ai.askJsonWithWeb(system, question) else ai.askJson(system, question)
    }
    val intro = (reply["intro"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val sites = (reply["sites"] as? JsonArray).orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val name = str("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val keysUrl = str("keysUrl")
        // A site the app knows keeps the app's own exact links
        val knownSite = KeySites.all.firstOrNull { it.name.equals(name, true) } ?: KeySites.byUrl(keysUrl) ?: KeySites.byUrl(str("baseUrl"))
        Advice(
            name = knownSite?.name ?: name,
            keysUrl = knownSite?.keysUrl ?: keysUrl.takeIf { it.startsWith("http") } ?: return@mapNotNull null,
            baseUrl = knownSite?.baseUrl ?: str("baseUrl"),
            free = str("free"),
            why = str("why"),
            siteId = knownSite?.id,
        )
    }
    return intro to sites
}
