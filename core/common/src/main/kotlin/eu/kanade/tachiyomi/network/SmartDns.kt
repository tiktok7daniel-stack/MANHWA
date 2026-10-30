package eu.kanade.tachiyomi.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

/**
 * MANHWA: sites open on any network. The network's own DNS is asked first (fast, and local servers keep working);
 * when it cannot find a site, answers with a dead address or a private one (how many networks block a site, or
 * send it to their own warning page), is too slow to answer, or the site cannot be reached through what it
 * answered (refused, reset, or a certificate that is not the site's), the address is asked again over HTTPS from
 * Cloudflare, then Google. What each network needed is remembered for that network, so going back to a Wi-Fi or
 * to mobile data finds its sites at once.
 */
class SmartDns(context: Context) : Dns {

    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(10.seconds)
        .readTimeout(10.seconds)
        .build()

    private val secure: List<Dns> = listOf(
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("162.159.36.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
            )
            .build(),
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4"),
                InetAddress.getByName("2001:4860:4860::8888"),
            )
            .build(),
    )

    /** What every network the phone was on needed: the sites it could not reach through its own DNS, and IPv4. */
    private class NetworkMemory {
        val viaSecure: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var preferV4 = false
    }

    private val memories = ConcurrentHashMap<Long, NetworkMemory>()

    @Volatile private var memory = NetworkMemory()

    /** Sites this network could not reach through its own DNS. */
    private val viaSecure: MutableSet<String> get() = memory.viaSecure

    /**
     * MANHWA: this network failed to connect over IPv6 (many mobile networks have a broken IPv6 route while the
     * home Wi-Fi works): sites are reached over IPv4 on it.
     */
    val preferV4: Boolean get() = memory.preferV4

    /** The network's own DNS runs here, so a slow one can be overtaken by the secure DNS. */
    private val systemLookups = Executors.newCachedThreadPool { r -> Thread(r, "smart-dns").apply { isDaemon = true } }

    /** Whether [hostname] is reached through the secure DNS on this network (for the problem log). */
    fun usesSecureDns(hostname: String): Boolean = hostname in viaSecure

    /** Called with the client once it is built, to drop connections made on the network the phone just left. */
    @Volatile var onNetworkChanged: (() -> Unit)? = null

    init {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = changed(network)
                    override fun onLost(network: Network) = changed(null)
                },
            )
        }
    }

    private fun changed(network: Network?) {
        // Each network keeps what it learnt; a network never seen starts fresh
        memory = network?.let { memories.getOrPut(it.networkHandle) { NetworkMemory() } } ?: NetworkMemory()
        runCatching { onNetworkChanged?.invoke() }
    }

    override fun lookup(hostname: String): List<InetAddress> =
        NetworkRules.ordered(rawLookup(hostname), { it.address.size == 4 }, preferV4)

    private fun rawLookup(hostname: String): List<InetAddress> {
        if (hostname !in viaSecure) {
            val asked: Future<List<InetAddress>> = systemLookups.submit<List<InetAddress>> {
                try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: UnknownHostException) {
                    emptyList()
                }
            }
            val system = try {
                asked.get(SYSTEM_BUDGET_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // The network's DNS is slow: the secure DNS answers, and the network's still may if that fails
                val secure = runCatching { secureLookup(hostname, fallBack = false) }.getOrNull()
                if (secure != null && !NetworkRules.blockedFor(hostname, secure.map { it.address })) {
                    // Kept for this network, so the next look does not wait for the slow DNS again
                    viaSecure += hostname
                    return secure
                }
                runCatching { asked.get(SYSTEM_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            if (!NetworkRules.blockedFor(hostname, system.map { it.address })) return system
            viaSecure += hostname
        }
        return secureLookup(hostname)
    }

    private fun secureLookup(hostname: String, fallBack: Boolean = true): List<InetAddress> {
        var last: Exception? = null
        for (dns in secure) {
            try {
                val found = dns.lookup(hostname)
                if (!NetworkRules.blocked(found.map { it.address })) return found
            } catch (e: Exception) {
                last = e
            }
        }
        if (!fallBack) throw last as? UnknownHostException ?: UnknownHostException(hostname)
        // Nothing better: the network's own answer, as before
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            last?.let { e.addSuppressed(it) }
            throw e
        }
    }

    private companion object {
        /** How long the network's own DNS has before the secure DNS is asked alongside it. */
        const val SYSTEM_BUDGET_MS = 2_500L

        /** How much longer the network's DNS is waited for when the secure DNS could not answer either. */
        const val SYSTEM_WAIT_MS = 6_000L
    }

    /** Marks [hostname] as unreachable through this network's DNS; true the first time. */
    fun avoidSystemFor(hostname: String): Boolean = viaSecure.add(hostname)

    /**
     * Retries a page or image once over the secure DNS when the site could not be reached at all (no response
     * came back), so a site the network hides or misdirects still loads.
     */
    inner class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                // The network's IPv6 route is broken: IPv4 from now on, and this request once more
                if (!chain.call().isCanceled() && !preferV4 && NetworkRules.ipv6ConnectFailure(e) &&
                    (request.method == "GET" || request.method == "HEAD")
                ) {
                    memory.preferV4 = true
                    runCatching { onNetworkChanged?.invoke() }
                    try {
                        return chain.proceed(request)
                    } catch (again: IOException) {
                        // Over IPv4 the site still could not be reached: the secure DNS is tried once below
                        return retrySecure(chain, again)
                    }
                }
                return retrySecure(chain, e)
            }
        }

        /** Once more over the secure DNS, when the site was not reached at all through the network's own. */
        private fun retrySecure(chain: Interceptor.Chain, e: IOException): Response {
            val request = chain.request()
            if (chain.call().isCanceled() ||
                !NetworkRules.retryable(request.method, e) ||
                !avoidSystemFor(request.url.host)
            ) {
                throw e
            }
            return chain.proceed(request)
        }
    }
}

/** MANHWA: the pure rules behind [SmartDns], tested on their own. */
object NetworkRules {

    /** An answer that leads nowhere: empty, or only "this" / loopback addresses (a network blocking the site). */
    fun blocked(addresses: List<ByteArray>): Boolean =
        addresses.isEmpty() || addresses.all { dead(it) }

    /**
     * [blocked], or an answer that sends a public site to a private address (10.x, 172.16-31.x, 192.168.x,
     * 100.64-127.x): a network showing its own warning page instead. Names of the home network (one word, or
     * .local, .lan, .home, .internal) may live at private addresses and are left alone.
     */
    fun blockedFor(hostname: String, addresses: List<ByteArray>): Boolean {
        if (blocked(addresses)) return true
        val local = '.' !in hostname || LOCAL_SUFFIXES.any { hostname.endsWith(it, ignoreCase = true) }
        return !local && addresses.all { isPrivate(it) }
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")

    private fun isPrivate(a: ByteArray): Boolean {
        if (a.size != 4) return false
        val b0 = a[0].toInt() and 0xFF
        val b1 = a[1].toInt() and 0xFF
        return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 100 && b1 in 64..127)
    }

    private fun dead(a: ByteArray): Boolean = when (a.size) {
        4 -> a[0].toInt() == 0 || a[0].toInt() == 127
        16 -> a.dropLast(1).all { it.toInt() == 0 } && (a[15].toInt() == 0 || a[15].toInt() == 1)
        else -> true
    }

    private val ipv6Literal = Regex("\\[[0-9a-fA-F]*:[0-9a-fA-F:.]*]")

    /** A connection that failed to an IPv6 address ("Failed to connect to /[2606:4700::…]:443"). */
    fun ipv6ConnectFailure(e: IOException): Boolean {
        val connect = e is ConnectException || e is NoRouteToHostException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
        return connect && e.message?.let { ipv6Literal.containsMatchIn(it) } == true
    }

    /**
     * MANHWA: what a failed request most likely means, for the network problem log: [error] when nothing came
     * back, else the response [code]; [online] whether the phone had a network at all.
     */
    fun diagnose(error: IOException?, code: Int, online: Boolean): NetworkProblemLog.Cause {
        if (error == null) {
            return when (code) {
                403, 503 -> NetworkProblemLog.Cause.PROTECTION
                429 -> NetworkProblemLog.Cause.SERVER
                else -> if (code >= 500) NetworkProblemLog.Cause.SERVER else NetworkProblemLog.Cause.OTHER
            }
        }
        if (!online) return NetworkProblemLog.Cause.OFFLINE
        if (error.message?.contains("cloudflare", ignoreCase = true) == true) return NetworkProblemLog.Cause.PROTECTION
        return when {
            ipv6ConnectFailure(error) -> NetworkProblemLog.Cause.IPV6
            error is UnknownHostException -> NetworkProblemLog.Cause.DNS
            error is ConnectException || error is NoRouteToHostException || error is SSLException -> NetworkProblemLog.Cause.UNREACHABLE
            error is SocketTimeoutException && error.message?.contains("connect", ignoreCase = true) == true -> NetworkProblemLog.Cause.UNREACHABLE
            error is SocketTimeoutException -> NetworkProblemLog.Cause.SERVER
            error.message?.contains("reset", ignoreCase = true) == true -> NetworkProblemLog.Cause.UNREACHABLE
            else -> NetworkProblemLog.Cause.OTHER
        }
    }

    /** With [preferV4], only the IPv4 addresses when there are any; otherwise the answer as it came. */
    fun <T> ordered(addresses: List<T>, isV4: (T) -> Boolean, preferV4: Boolean): List<T> {
        if (!preferV4) return addresses
        return addresses.filter(isV4).ifEmpty { addresses }
    }

    private val RESET_WORDS = listOf("connection reset", "unexpected end of stream", "broken pipe")

    /** Only safe requests, and only when the site was not reached (or the connection was misdirected). */
    fun retryable(method: String, e: IOException): Boolean {
        if (method != "GET" && method != "HEAD") return false
        return when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException -> true
            is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
            is InterruptedIOException -> false
            // A connection cut by the network as it starts (how some networks block a site by its name)
            else -> e.message?.let { m -> RESET_WORDS.any { m.contains(it, ignoreCase = true) } } == true
        }
    }
}
