package eu.kanade.translation.search

import eu.kanade.presentation.components.ManhwaInk

import mihon.icons.materialsymbols.rounded.Check
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.Send
import mihon.icons.materialsymbols.rounded.AutoAwesome
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** Glass chat where Danghwa AI finds a series in every source and ranks them by latest chapter. */
class AiChatScreen(private val initialPrompt: String? = null) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<AiChatViewModel>()
        val messages by viewModel.messages.collectAsState()
        val busy by viewModel.busy.collectAsState()
        var input by rememberSaveable { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size, (messages.lastOrNull() as? AiChatViewModel.Message.Assistant)?.hits?.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
        }

        val send = { text: String ->
            viewModel.send(text)
            input = ""
        }

        LaunchedEffect(initialPrompt) {
            if (!initialPrompt.isNullOrBlank() && messages.isEmpty()) viewModel.send(initialPrompt)
        }

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Header(onBack = navigator::pop)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Welcome(showSuggestions = messages.isEmpty(), onSuggestion = send) }
                    items(messages) { message ->
                        when (message) {
                            is AiChatViewModel.Message.User -> UserBubble(message.text)
                            is AiChatViewModel.Message.Assistant -> AssistantBubble(
                                message = message,
                                onOpen = { navigator.push(MangaScreen(it, true)) },
                                onScreen = { key ->
                                    when (key) {
                                        "sources_hub" -> navigator.push(eu.kanade.tachiyomi.ui.home.hub.SourceHubScreen())
                                        "fresh" -> navigator.push(eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersScreen())
                                        "organizer" -> navigator.push(eu.kanade.tachiyomi.ui.library.ai.AiLibraryOrganizerScreen())
                                        "smart_download" -> navigator.push(eu.kanade.tachiyomi.ui.home.smart.SmartDownloadScreen())
                                        "downloads" -> navigator.push(eu.kanade.tachiyomi.ui.download.DownloadQueueScreen)
                                        "wrapped" -> navigator.push(eu.kanade.tachiyomi.ui.home.wrapped.WrappedScreen())
                                    }
                                },
                            )
                        }
                    }
                }
                InputBar(value = input, busy = busy, onValueChange = { input = it }, onSend = { send(input) })
            }
        }
    }

    @Composable
    private fun Header(onBack: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = null)
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF7A0A2E))),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = ManhwaInk.text)
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(ATMR.strings.ai_chat_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(ATMR.strings.ai_chat_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun Welcome(showSuggestions: Boolean, onSuggestion: (String) -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), accent = true).padding(16.dp)) {
            Text(stringResource(ATMR.strings.ai_chat_welcome), style = MaterialTheme.typography.bodyMedium)
            if (showSuggestions) {
                Row(
                    modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        stringResource(ATMR.strings.ai_chat_suggestion_1),
                        stringResource(ATMR.strings.ai_chat_suggestion_2),
                        stringResource(ATMR.strings.ai_chat_suggestion_3),
                        stringResource(ATMR.strings.ai_chat_suggestion_4),
                        stringResource(ATMR.strings.ai_chat_suggestion_5),
                    ).forEach { suggestion ->
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .glass(RoundedCornerShape(50))
                                .clickable { onSuggestion(suggestion) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun UserBubble(text: String) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = text,
                color = ManhwaInk.text,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF8E0F1F))),
                        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }

    @Composable
    private fun AssistantBubble(message: AiChatViewModel.Message.Assistant, onOpen: (Long) -> Unit, onScreen: (String) -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (message.text.isNotBlank()) Text(message.text, style = MaterialTheme.typography.bodyMedium)
            message.status?.let { status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(
                            when (status) {
                                AiChatViewModel.Status.THINKING -> ATMR.strings.ai_chat_thinking
                                AiChatViewModel.Status.SEARCHING -> ATMR.strings.ai_chat_searching
                                AiChatViewModel.Status.CHECKING_CHAPTERS -> ATMR.strings.ai_chat_checking
                                AiChatViewModel.Status.SEARCHING_WEB -> ATMR.strings.ai_chat_searching_web
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            message.error?.let { error ->
                Text(
                    text = if (error == AiChatViewModel.NOT_CONFIGURED) {
                        stringResource(ATMR.strings.ai_search_not_configured)
                    } else {
                        stringResource(ATMR.strings.ai_search_failed, error)
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (message.searched && message.hits.isEmpty()) {
                Text(
                    stringResource(ATMR.strings.ai_chat_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.hits.forEachIndexed { index, hit -> HitCard(hit, best = index == 0, onClick = { onOpen(hit.manga.id) }) }
            message.done.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                    androidx.compose.material3.Icon(
                        mihon.icons.materialsymbols.MaterialSymbols.Rounded.Check,
                        contentDescription = null,
                        tint = ManhwaInk.text,
                        modifier = Modifier.size(14.dp),
                    )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
            message.updates.forEach { update -> UpdateRow(update, onClick = { onOpen(update.mangaId) }) }
            message.fresh.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(14.dp), alpha = 0.35f)
                        .clickable { onOpen(item.mangaId) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.chapterName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                }
            }
            message.screen?.let { key ->
                Text(
                    stringResource(ATMR.strings.ai_open_screen),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onScreen(key) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            message.webSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (message.sites.isNotEmpty()) {
                val context = LocalContext.current
                Text(
                    stringResource(ATMR.strings.ai_chat_web_sites),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                message.sites.forEach { site ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(14.dp), alpha = 0.35f)
                            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, site.url.toUri())) } }
                            .padding(12.dp),
                    ) {
                        Text(site.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            site.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        site.latest?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HitCard(hit: AiChatViewModel.SourceHit, best: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(16.dp), accent = best, alpha = 0.4f)
                .clickable(onClick = onClick)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                data = hit.manga.asMangaCover(),
                modifier = Modifier.width(52.dp),
                shape = RoundedCornerShape(10.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                if (best) {
                    Text(
                        text = stringResource(ATMR.strings.ai_chat_best).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    hit.manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${hit.sourceName} · ${hit.sourceLang.uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                val details = buildList {
                    hit.latestChapter?.let { add(stringResource(ATMR.strings.ai_chat_chapter, formatChapter(it))) }
                    if (hit.chapterCount > 0) add(stringResource(ATMR.strings.ai_chat_chapters, hit.chapterCount))
                    if (hit.lastUpload > 0) add(DateUtils.getRelativeTimeSpanString(hit.lastUpload).toString())
                }
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    @Composable
    private fun UpdateRow(update: UpdatesWithRelations, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(14.dp), alpha = 0.35f)
                .clickable(onClick = onClick)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Square(
                data = update.coverData,
                modifier = Modifier.width(40.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(update.mangaTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    update.chapterName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                DateUtils.getRelativeTimeSpanString(update.dateFetch.toEpochMilliseconds()).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    private fun formatChapter(number: Float): String =
        if (number % 1f == 0f) number.toInt().toString() else number.toString()

    @Composable
    private fun InputBar(value: String, busy: Boolean, onValueChange: (String) -> Unit, onSend: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .glass(RoundedCornerShape(28.dp), accent = true, alpha = 0.7f)
                .padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(ATMR.strings.ai_chat_hint)) },
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF8E0F1F))),
                        CircleShape,
                    )
                    .clickable(enabled = !busy && value.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ManhwaInk.text)
                } else {
                    Icon(MaterialSymbols.AutoMirroredRounded.Send, contentDescription = null, tint = ManhwaInk.text)
                }
            }
        }
    }
}
