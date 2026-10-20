package eu.kanade.translation.site

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import kotlin.time.Clock

/**
 * MANHWA: the site's own reader for a MangaTek chapter (see [SiteReader]), full screen inside the app: the pages
 * come with the site's translation drawn on them exactly as on the site, and the next chapters follow under it.
 * A chapter read to its end is marked read in the library, the one being read goes in the history.
 */
class SiteReaderActivity : BaseActivity() {

    private class Item(val chapter: Chapter, val url: String)

    // Set once from the load, read by the page's script from its own thread
    @Volatile private var items: List<Item> = emptyList()
    private var webView: WebView? = null

    // Which chapter is on screen, and the one the page was opened at (the frames under it follow it)
    private val current = mutableIntStateOf(-1)
    private val progress = mutableIntStateOf(0)
    @Volatile private var base = 0

    // The app's look (only the pages); off shows the site as it is, should something of it be missing
    private val plain = mutableStateOf(true)

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!WebViewUtil.supportsWebView(this)) {
            toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            finish()
            return
        }
        val mangaId = intent.getLongExtra(MANGA_KEY, -1L)
        val chapterId = intent.getLongExtra(CHAPTER_KEY, -1L)

        setComposeContent {
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val start = withContext(Dispatchers.IO) { load(mangaId, chapterId) }
                if (start == null) {
                    toast(ATMR.strings.site_reader_failed)
                    finish()
                    return@LaunchedEffect
                }
                base = start
                current.intValue = start
                loaded = true
            }
            Box(Modifier.fillMaxSize().background(ManhwaKit.Ink)) {
                if (loaded) {
                    AndroidView(
                        factory = { context -> createWebView(context).also { it.loadUrl(items[base].url) } },
                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    )
                }
                Bar(
                    onBack = ::finish,
                    onSiteLook = {
                        plain.value = !plain.value
                        webView?.evaluateJavascript("window.__manhwaSetPlain && window.__manhwaSetPlain(${plain.value})", null)
                    },
                    // Again from the chapter on screen, not the one the page was opened at
                    onReload = { items.getOrNull(current.intValue)?.let { base = current.intValue; webView?.loadUrl(it.url) } },
                )
            }
            BackHandler { finish() }
        }
    }

    @Composable
    private fun Bar(onBack: () -> Unit, onSiteLook: () -> Unit, onReload: () -> Unit) {
        val index = current.intValue
        val loading = progress.intValue
        // The chapter's name shows for a moment each time a new one comes on screen
        var showTitle by remember { mutableStateOf(true) }
        LaunchedEffect(index) {
            showTitle = true
            delay(TITLE_MS)
            showTitle = false
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManhwaKit.IconOrb(MaterialSymbols.AutoMirroredRounded.ArrowBack, size = 38, onClick = onBack)
                Spacer(Modifier.width(8.dp))
                AnimatedVisibility(
                    visible = showTitle && index in items.indices,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        items.getOrNull(index)?.chapter?.name.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ManhwaInk.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .glass(RoundedCornerShape(14.dp), alpha = 0.55f)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (!(showTitle && index in items.indices)) Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ManhwaKit.IconOrb(MaterialSymbols.Rounded.Public, active = !plain.value, size = 38, onClick = onSiteLook)
                Spacer(Modifier.width(8.dp))
                ManhwaKit.IconOrb(MaterialSymbols.Rounded.Refresh, size = 38, onClick = onReload)
            }
            if (loading in 1..99) {
                Spacer(Modifier.padding(top = 6.dp))
                ManhwaKit.GlowBar(loading / 100f)
            }
        }
    }

    /** The manga's chapters in reading order with their pages on the site; where [chapterId] is in them. */
    private suspend fun load(mangaId: Long, chapterId: Long): Int? = try {
        val graph = appGraph
        val manga = graph.getMangaById.await(mangaId)
        val source = manga?.let { graph.sourceManager.get(it.source) } as? HttpSource
        if (manga == null || source == null) {
            null
        } else {
            items = graph.getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
                .sortedByDescending { it.sourceOrder }
                .mapNotNull { chapter ->
                    runCatching { Item(chapter, source.getChapterUrl(chapter.toSChapter())) }.getOrNull()
                }
            items.indexOfFirst { it.chapter.id == chapterId }.takeIf { it >= 0 }
        }
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Site reader: chapters not loaded" }
        null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        setBackgroundColor(Color.BLACK)
        setDefaultSettings()
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        val baseUrl = items[base].url
        addJavascriptInterface(Bridge(), BRIDGE)
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.intValue = newProgress
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // The site and its check stay here; an ad or a link out is not opened
                return !SiteReader.allowed(request?.url?.host, baseUrl)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript("window.__manhwaIndex=$base;window.__manhwaDepth=0;${SiteReader.SCRIPT}", null)
            }
        }
        webView = this
    }

    private fun markRead(index: Int) {
        val chapter = items.getOrNull(index)?.chapter ?: return
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { appGraph.updateChapter.await(ChapterUpdate(chapter.id) { read = true }) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Site reader: chapter not marked read" } }
        }
    }

    private fun saveHistory(index: Int) {
        val chapter = items.getOrNull(index)?.chapter ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { appGraph.upsertHistory.await(HistoryUpdate(chapter.id, Clock.System.now(), 0L)) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Site reader: history not saved" } }
        }
    }

    /** What the page's script calls (from the binder thread: everything goes to the main thread). */
    private inner class Bridge {
        @JavascriptInterface
        fun now(index: Int) = runOnUiThread {
            if (index in items.indices && index != current.intValue) {
                current.intValue = index
                saveHistory(index)
            }
        }

        @JavascriptInterface
        fun done(index: Int) = runOnUiThread { markRead(index) }

        @JavascriptInterface
        fun urlAfter(index: Int): String = items.getOrNull(index + 1)?.url.orEmpty()

        @JavascriptInterface
        fun titleAfter(index: Int): String =
            items.getOrNull(index + 1)?.chapter?.name?.let { stringResource(ATMR.strings.site_reader_next, it) }.orEmpty()

        @JavascriptInterface
        fun script(): String = SiteReader.SCRIPT

        @JavascriptInterface
        fun plain(): Boolean = plain.value

        @JavascriptInterface
        fun go(index: Int) = runOnUiThread {
            val item = items.getOrNull(index) ?: return@runOnUiThread
            base = index
            current.intValue = index
            webView?.loadUrl(item.url)
        }
    }

    override fun onDestroy() {
        webView?.apply {
            removeJavascriptInterface(BRIDGE)
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val MANGA_KEY = "manga"
        private const val CHAPTER_KEY = "chapter"
        private const val BRIDGE = "ManhwaSite"
        private const val TITLE_MS = 2500L

        fun newIntent(context: Context, mangaId: Long, chapterId: Long): Intent =
            Intent(context, SiteReaderActivity::class.java).apply {
                putExtra(MANGA_KEY, mangaId)
                putExtra(CHAPTER_KEY, chapterId)
            }
    }
}
