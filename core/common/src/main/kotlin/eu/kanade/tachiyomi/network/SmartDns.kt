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
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

/**
 * MANHWA: sites open on any network. The network's own DNS is asked first (fast, and local servers keep working);
 * when it cannot find a site, answers with a dead address (how many networks block a site), or the site cannot be
 * reached through what it answered, the address is asked again over HTTPS from Cloudflare, then Google, and that
 * site keeps using it until the phone changes network.
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

    /** Sites this network could not reach through its own DNS. */
    private val viaSecure = ConcurrentHashMap.newKeySet<String>()

    /** Called with the client once it is built, to drop connections made on the network the phone just left. */
    @Volatile var onNetworkChanged: (() -> Unit)? = null

    init {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = changed()
                    override fun onLost(network: Network) = changed()
                },
            )
        }
    }

    private fun changed() {
        viaSecure.clear()
        runCatching { onNetworkChanged?.invoke() }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname !in viaSecure) {
            val system = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                emptyList()
            }
            if (!NetworkRules.blocked(system.map { it.address })) return system
            viaSecure += hostname
        }
        return secureLookup(hostname)
    }

    private fun secureLookup(hostname: String): List<InetAddress> {
        var last: Exception? = null
        for (dns in secure) {
            try {
                val found = dns.lookup(hostname)
                if (!NetworkRules.blocked(found.map { it.address })) return found
            } catch (e: Exception) {
                last = e
            }
        }
        // Nothing better: the network's own answer, as before
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            last?.let { e.addSuppressed(it) }
            throw e
        }
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
            return try {
                chain.proceed(request)
            } catch (e: IOException) {
                val host = request.url.host
                if (chain.call().isCanceled() ||
                    !NetworkRules.retryable(request.method, e) ||
                    !avoidSystemFor(host)
                ) {
                    throw e
                }
                chain.proceed(request)
            }
        }
    }
}

/** MANHWA: the pure rules behind [SmartDns], tested on their own. */
object NetworkRules {

    /** An answer that leads nowhere: empty, or only "this" / loopback addresses (a network blocking the site). */
    fun blocked(addresses: List<ByteArray>): Boolean =
        addresses.isEmpty() || addresses.all { dead(it) }

    private fun dead(a: ByteArray): Boolean = when (a.size) {
        4 -> a[0].toInt() == 0 || a[0].toInt() == 127
        16 -> a.dropLast(1).all { it.toInt() == 0 } && (a[15].toInt() == 0 || a[15].toInt() == 1)
        else -> true
    }

    /** Only safe requests, and only when the site was not reached (or the connection was misdirected). */
    fun retryable(method: String, e: IOException): Boolean {
        if (method != "GET" && method != "HEAD") return false
        return when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException -> true
            is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
            is InterruptedIOException -> false
            else -> false
        }
    }
}
