package ireader.core.http

/**
 * MANHWA: cookies are shared already (the app's network client and the WebView use one cookie store), so there is
 * nothing to copy between them.
 */
class CookieSynchronizer : CookieSynchronizerInterface {
    override fun syncFromWebView(url: String) = Unit
    override fun syncToWebView(url: String) = Unit
    override fun clearAll() = Unit
}

/**
 * Common cookie synchronizer interface for platform implementations
 */
interface CookieSynchronizerInterface {
    fun syncFromWebView(url: String)
    fun syncToWebView(url: String)
    fun clearAll()
}
