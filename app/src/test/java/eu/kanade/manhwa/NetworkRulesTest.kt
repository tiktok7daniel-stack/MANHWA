package eu.kanade.manhwa

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
