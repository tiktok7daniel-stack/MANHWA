package eu.kanade.tachiyomi.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MANHWA: every time a site could not be opened (a source, a chapter list, a chapter's pages, an image), what
 * happened is kept on the phone: the site (never the full address), the network it was on, the error and what it
 * most likely means. The last [MAX_ENTRIES] stay, a problem repeated on the same site within a minute once.
 * Nothing leaves the phone; the reader copies or shares the log when they want to.
 */
object NetworkProblemLog {

    /** What a failure most likely means (see [NetworkRules.diagnose]). */
    enum class Cause { IPV6, DNS, UNREACHABLE, PROTECTION, SERVER, OFFLINE, OTHER }

    data class Entry(
        val at: Long,
        val host: String,
        val method: String,
        /** "wifi", "mobile", "vpn", "ethernet", "none" or "other". */
        val network: String,
        val secureDns: Boolean,
        val ipv4Only: Boolean,
        val error: String,
        val cause: Cause,
    )

    private val lock = Any()

    @Volatile private var file: File? = null

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** The problems kept, newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (file != null) return@synchronized
        val f = File(context.filesDir, FILE_NAME)
        file = f
        _entries.value = runCatching { f.readLines().mapNotNull(::parse) }.getOrDefault(emptyList()).take(MAX_ENTRIES)
    }

    fun record(entry: Entry) = synchronized(lock) {
        val list = _entries.value
        val repeated = list.any {
            it.host == entry.host && it.cause == entry.cause && entry.at - it.at < REPEAT_WINDOW_MS
        }
        if (repeated) return@synchronized
        val next = (listOf(entry) + list).take(MAX_ENTRIES)
        _entries.value = next
        runCatching { file?.writeText(next.joinToString("\n", transform = ::format)) }
    }

    fun clear() = synchronized(lock) {
        _entries.value = emptyList()
        runCatching { file?.delete() }
    }

    /** The log as text, newest first, to copy or share. */
    fun text(): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return _entries.value.joinToString("\n") { e ->
            listOf(
                date.format(Date(e.at)),
                e.host,
                e.method,
                "network=${e.network}",
                if (e.secureDns) "secure-dns" else "system-dns",
                if (e.ipv4Only) "ipv4-only" else "ipv4+ipv6",
                e.cause.name,
                e.error,
            ).joinToString(" | ")
        }
    }

    private fun format(e: Entry): String = listOf(
        e.at, e.host, e.method, e.network, e.secureDns, e.ipv4Only, e.cause.name, e.error.replace('\t', ' ').replace('\n', ' '),
    ).joinToString("\t")

    private fun parse(line: String): Entry? {
        val p = line.split('\t')
        if (p.size < 8) return null
        return Entry(
            at = p[0].toLongOrNull() ?: return null,
            host = p[1],
            method = p[2],
            network = p[3],
            secureDns = p[4].toBoolean(),
            ipv4Only = p[5].toBoolean(),
            cause = Cause.entries.firstOrNull { it.name == p[6] } ?: Cause.OTHER,
            error = p[7],
        )
    }

    /** The network the phone is on now. */
    fun networkType(context: Context): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching "none"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }.getOrDefault("other")

    /**
     * Records the requests that could not be done, or that the site refused (403, 429, 5xx). The first interceptor
     * of the client, so it sees what is left after every retry.
     */
    class Recorder(private val context: Context, private val smartDns: () -> SmartDns?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (!chain.call().isCanceled()) log(request.url.host, request.method, e, 0)
                throw e
            }
            if (response.code == 403 || response.code == 429 || response.code >= 500) {
                log(request.url.host, request.method, null, response.code)
            }
            return response
        }

        private fun log(host: String, method: String, error: IOException?, code: Int) {
            runCatching {
                val network = networkType(context)
                val dns = smartDns()
                record(
                    Entry(
                        at = System.currentTimeMillis(),
                        host = host,
                        method = method,
                        network = network,
                        secureDns = dns?.usesSecureDns(host) == true,
                        ipv4Only = dns?.preferV4 == true,
                        error = error?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}".take(MAX_ERROR) } ?: "HTTP $code",
                        cause = NetworkRules.diagnose(error, code, online = network != "none"),
                    ),
                )
            }
        }
    }

    private const val FILE_NAME = "network_problems.log"
    private const val MAX_ENTRIES = 200
    private const val MAX_ERROR = 300
    private const val REPEAT_WINDOW_MS = 60_000L
}
