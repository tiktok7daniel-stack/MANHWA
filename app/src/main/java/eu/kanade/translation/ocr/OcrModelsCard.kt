package eu.kanade.translation.ocr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** MANHWA PaddleOCR models: what is on the phone, one tap to download (with progress and a check), or remove. */
@Composable
fun OcrModelsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val states by OcrModels.status.collectAsState()
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(ATMR.strings.ocr_models_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
        Text(stringResource(ATMR.strings.ocr_models_info), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
        OcrModels.Pack.entries.forEach { pack ->
            // Read again whenever a download moves on
            val status = remember(states) { OcrModels.statusOf(context, pack) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (pack == OcrModels.Pack.MAIN) ATMR.strings.ocr_pack_main else ATMR.strings.ocr_pack_korean),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            when (status) {
                                OcrModels.Status.Ready -> stringResource(ATMR.strings.ocr_pack_ready)
                                is OcrModels.Status.Downloading -> stringResource(ATMR.strings.ocr_pack_downloading, status.percent)
                                is OcrModels.Status.Failed -> stringResource(ATMR.strings.ocr_pack_failed, status.reason)
                                OcrModels.Status.Missing -> stringResource(ATMR.strings.ocr_pack_size, pack.sizeMb)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (status) {
                                OcrModels.Status.Ready -> Color(0xFF3DDC97)
                                is OcrModels.Status.Failed -> Color(0xFFFF4D6A)
                                is OcrModels.Status.Downloading -> Color(0xFFFFB020)
                                OcrModels.Status.Missing -> Color.White.copy(alpha = 0.6f)
                            },
                        )
                    }
                }
                if (status is OcrModels.Status.Downloading) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxWidth(status.percent / 100f).height(6.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
                when (status) {
                    OcrModels.Status.Missing -> ManhwaKit.NeonButton(stringResource(ATMR.strings.ocr_pack_download), icon = MaterialSymbols.Rounded.Download, modifier = Modifier.fillMaxWidth()) {
                        OcrModels.download(context, pack)
                    }
                    is OcrModels.Status.Failed -> ManhwaKit.NeonButton(stringResource(ATMR.strings.ocr_pack_retry), icon = MaterialSymbols.Rounded.Refresh, modifier = Modifier.fillMaxWidth()) {
                        OcrModels.download(context, pack)
                    }
                    OcrModels.Status.Ready -> ManhwaKit.GhostButton(stringResource(ATMR.strings.ocr_pack_delete), icon = MaterialSymbols.Rounded.Delete, modifier = Modifier.fillMaxWidth()) {
                        OcrModels.delete(context, pack)
                    }
                    is OcrModels.Status.Downloading -> Unit
                }
            }
        }
    }
}
