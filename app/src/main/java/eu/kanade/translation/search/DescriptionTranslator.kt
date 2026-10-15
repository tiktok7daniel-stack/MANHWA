package eu.kanade.translation.search

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.glass
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.GoogleTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** "✦ Translate description": the synopsis in the reader's language, via the configured AI engine. */
@Composable
fun DescriptionTranslator(description: String?, modifier: Modifier = Modifier) {
    if (description.isNullOrBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var translated by rememberSaveable(description) { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val notConfigured = stringResource(ATMR.strings.ai_search_not_configured)

    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .glass(RoundedCornerShape(50))
                .clickable(enabled = !loading) {
                    if (translated != null) {
                        translated = null
                        return@clickable
                    }
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            translated = translate(context.appGraph.translationPreferences, description)
                                ?: run {
                                    error = notConfigured
                                    null
                                }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            error = e.message
                        } finally {
                            loading = false
                        }
                    }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    MaterialSymbols.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (translated == null) ATMR.strings.desc_translate else ATMR.strings.desc_show_original,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        translated?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(16.dp), accent = true, alpha = 0.4f)
                    .padding(14.dp),
            )
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Translates with the AI when possible; if it is not set up, busy or failing, Google Translate does it. */
private suspend fun translate(prefs: TranslationPreferences, text: String): String? = withContext(Dispatchers.IO) {
    val ai = eu.kanade.translation.translator.appAi()
    val target = TextTranslatorLanguage.fromPref(prefs.translateToLanguage())
    val google = GoogleTranslator(TextRecognizerLanguage.ENGLISH, target)
    if (ai == null) {
        return@withContext google.translateText(text).ifBlank { null }
    }
    try {
        val json = ai.askJson(
            "Translate this comic synopsis into natural ${target.label}. Keep names consistent. " +
                "Reply with ONLY this JSON: {\"translation\": \"...\"}",
            text,
        )
        (json["translation"] as? JsonPrimitive)?.contentOrNull ?: google.translateText(text)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        google.translateText(text).ifBlank { throw e }
    } finally {
        ai.close()
    }
}
