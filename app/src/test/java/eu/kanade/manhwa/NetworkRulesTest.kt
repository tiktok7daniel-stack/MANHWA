package eu.kanade.manhwa

import eu.kanade.tachiyomi.network.NetworkProblemLog.Cause
import eu.kanade.tachiyomi.network.NetworkRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkRulesTest {

    private fun ips(vararg s: String) = s.map { InetAddress.getByName(it).address }

    @Test
    fun `dead or empty answers mean the network hides the site`() {
        NetworkRules.blocked(emptyList()) shouldBe true
        NetworkRules.blocked(ips("0.0.0.0")) shouldBe true
        NetworkRules.blocked(ips("127.0.0.1", "::1")) shouldBe true
        NetworkRules.blocked(ips("::")) shouldBe true
    }

    @Test
    fun `real and local addresses are kept`() {
        NetworkRules.blocked(ips("104.18.2.3")) shouldBe false
        NetworkRules.blocked(ips("0.0.0.0", "172.67.1.1")) shouldBe false
        // A server on the home network (Komga and the like) still works
        NetworkRules.blocked(ips("192.168.1.20")) shouldBe false
        NetworkRules.blocked(ips("2606:4700::6810:1")) shouldBe false
    }

    @Test
    fun `only safe requests that never reached the site are tried again`() {
        NetworkRules.retryable("GET", UnknownHostException("x")) shouldBe true
        NetworkRules.retryable("GET", ConnectException("refused")) shouldBe true
        NetworkRules.retryable("GET", SSLHandshakeException("bad cert")) shouldBe true
        NetworkRules.retryable("GET", SocketTimeoutException("connect timed out")) shouldBe true
        NetworkRules.retryable("GET", SocketTimeoutException("timeout")) shouldBe false
        NetworkRules.retryable("POST", UnknownHostException("x")) shouldBe false
        NetworkRules.retryable("GET", IOException("Canceled")) shouldBe false
    }
}

class DownloadRetryRulesTest {

    @Test
    fun `a failed chapter waits longer each time`() {
        eu.kanade.tachiyomi.data.download.DownloadRetryRules.backoffSeconds(1) shouldBe 5L
        eu.kanade.tachiyomi.data.download.DownloadRetryRules.backoffSeconds(2) shouldBe 15L
        eu.kanade.tachiyomi.data.download.DownloadRetryRules.backoffSeconds(3) shouldBe 30L
    }

    @Test
    fun `a failed connection to an IPv6 address is told apart`() {
        NetworkRules.ipv6ConnectFailure(ConnectException("Failed to connect to /[2606:4700:3037::ac43:dedc]:443")) shouldBe true
        NetworkRules.ipv6ConnectFailure(ConnectException("Failed to connect to /104.21.5.6:443")) shouldBe false
        NetworkRules.ipv6ConnectFailure(UnknownHostException("[2606:4700::1]")) shouldBe false
    }

    @Test
    fun `on a network with broken IPv6 only IPv4 addresses are used when there are any`() {
        val both = listOf("2606:4700::1", "104.21.5.6")
        NetworkRules.ordered(both, { '.' in it }, preferV4 = true) shouldBe listOf("104.21.5.6")
        NetworkRules.ordered(both, { '.' in it }, preferV4 = false) shouldBe both
        NetworkRules.ordered(listOf("2606:4700::1"), { '.' in it }, preferV4 = true) shouldBe listOf("2606:4700::1")
    }

    @Test
    fun `failures are explained`() {
        NetworkRules.diagnose(ConnectException("Failed to connect to /[2606:4700::1]:443"), 0, online = true) shouldBe Cause.IPV6
        NetworkRules.diagnose(ConnectException("Failed to connect to /104.21.5.6:443"), 0, online = true) shouldBe Cause.UNREACHABLE
        NetworkRules.diagnose(UnknownHostException("site"), 0, online = true) shouldBe Cause.DNS
        NetworkRules.diagnose(UnknownHostException("site"), 0, online = false) shouldBe Cause.OFFLINE
        NetworkRules.diagnose(SocketTimeoutException("timeout"), 0, online = true) shouldBe Cause.SERVER
        NetworkRules.diagnose(IOException("Failed to bypass Cloudflare"), 0, online = true) shouldBe Cause.PROTECTION
        NetworkRules.diagnose(null, 403, online = true) shouldBe Cause.PROTECTION
        NetworkRules.diagnose(null, 502, online = true) shouldBe Cause.SERVER
    }
}

