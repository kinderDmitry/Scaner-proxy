package com.happ.proxyfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyItemTest {
    private fun item(status: String, latency: Int?) = ProxyItem(
        id = "test",
        host = "203.0.113.10",
        port = 443,
        protocol = "SOCKS5",
        source = "unit-test",
        rawConfig = "socks5://example",
        checkStatus = status,
        latencyMs = latency,
        checkedAt = 1L
    )

    @Test
    fun parserExtractsCountryFlagAndLabelsInternetSource() {
        val parsed = ConfigParser.parseConfig(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#%F0%9F%87%BA%F0%9F%87%B8%20United%20States"
        )

        assertTrue(parsed != null)
        assertEquals("US", parsed?.countryCode)
        assertTrue(parsed?.country?.isNotBlank() == true)
        assertTrue(parsed?.source?.startsWith("Сеть · ") == true)
    }

    @Test
    fun parserExtractsCountryCodeFromBracketedServerName() {
        val parsed = ConfigParser.parseConfig(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#Node%20%5BDE%5D"
        )

        assertEquals("DE", parsed?.countryCode)
        assertTrue(parsed?.country?.isNotBlank() == true)
    }

    @Test
    fun successfulGenerate204IsVerifiedAndScored() {
        val proxy = item("Проверен через generate_204 · HTTP 204", 85)

        assertTrue(proxy.isTunnelVerified)
        assertTrue(proxy.isTcpReachable)
        assertEquals(90, proxy.qualityScore)
    }

    @Test
    fun rawTcpReachabilityDoesNotPretendTunnelWasVerified() {
        val proxy = item("TCP доступен · скорость туннеля не измерена: нужен core VLESS", 24)

        assertTrue(proxy.isTcpReachable)
        assertFalse(proxy.isTunnelVerified)
        assertEquals(0, proxy.qualityScore)
    }

    @Test
    fun failedGenerate204DoesNotExposeLatencyOrQuality() {
        val proxy = item("Проверка generate_204 не пройдена", null)

        assertFalse(proxy.isTunnelVerified)
        assertFalse(proxy.isTcpReachable)
        assertEquals(0, proxy.qualityScore)
    }

    @Test
    fun unexpectedHttpResponseIsNotCountedAsWorkingProxy() {
        val proxy = item("Проверка generate_204: неожиданный HTTP 407", null)

        assertFalse(proxy.isTunnelVerified)
        assertFalse(proxy.isTcpReachable)
        assertEquals(0, proxy.qualityScore)
    }
}
