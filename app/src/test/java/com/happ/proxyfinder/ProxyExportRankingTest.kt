package com.happ.proxyfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyExportRankingTest {
    private fun verified(id: String, latency: Int, protocol: String = "VLESS", raw: String = "vless://$id@example.com:443"): ProxyItem =
        ProxyItem(
            id = id,
            host = "example.com",
            port = 443,
            protocol = protocol,
            source = "test",
            rawConfig = raw,
            checkStatus = "Проверен через HTTPS-туннель · HTTP 204",
            latencyMs = latency,
            checkedAt = 1_800_000_000_000L
        )

    @Test
    fun `export contains only verified supported configs and removes duplicates`() {
        val fast = verified("fast", 45)
        val slow = verified("slow", 420)
        val unverified = verified("unverified", 30).copy(checkStatus = "TCP доступен · HTTPS-туннель не подтверждён")
        val unsupported = verified("unsupported", 20, protocol = "VMESS")

        val result = rankExportableProxies(listOf(slow, unverified, fast, unsupported, fast.copy(id = "duplicate")))

        assertEquals(listOf("fast", "slow"), result.map { it.id })
    }

    @Test
    fun `active country and protocol filters restrict the exported source set`() {
        val germanyVless = verified("de-vless", 42).copy(country = "Germany", countryCode = "DE")
        val germanySocks = verified("de-socks", 55, protocol = "SOCKS5", raw = "socks://example.com:1080")
            .copy(country = "Germany", countryCode = "DE")
        val franceVless = verified("fr-vless", 30).copy(country = "France", countryCode = "FR")

        val filtered = filterProxyResults(
            listOf(germanyVless, germanySocks, franceVless),
            query = "",
            selectedProtocols = setOf("VLESS"),
            selectedCountries = setOf("Germany"),
            onlyAvailable = false,
            onlyFavorites = false
        )

        assertEquals(listOf("de-vless"), rankExportableProxies(filtered).map { it.id })
    }

    @Test
    fun `country code filter matches country code and export remains empty when no country matches`() {
        val germany = verified("de-vless", 42).copy(country = "Germany", countryCode = "DE")

        val filtered = filterProxyResults(
            listOf(germany),
            query = "",
            selectedProtocols = setOf("VLESS"),
            selectedCountries = setOf("FR"),
            onlyAvailable = false,
            onlyFavorites = false
        )

        assertTrue(filtered.isEmpty())
        assertTrue(rankExportableProxies(filtered).isEmpty())
    }

    @Test
    fun `site upload JSON contains only verified supported links in configs array`() {
        val fast = verified("fast", 40)
        val socks = verified("socks", 80, protocol = "SOCKS5", raw = "socks://proxy.example:1080")
        val unverified = verified("pending", 20).copy(checkStatus = "TCP доступен · HTTPS-туннель не подтверждён")

        val payload = org.json.JSONObject(buildSiteUploadJson(listOf(socks, unverified, fast)))
        val configs = payload.getJSONArray("configs")

        assertEquals("Scaner Proxy X Android", payload.getString("source"))
        assertEquals(2, configs.length())
        assertEquals("vless://fast@example.com:443", configs.getJSONObject(0).getString("uri"))
        assertEquals("socks://proxy.example:1080", configs.getJSONObject(1).getString("uri"))
    }

    @Test
    fun `export ranks lower latency ahead when quality scores match`() {
        val slower = verified("slower", 180)
        val faster = verified("faster", 120)

        val result = rankExportableProxies(listOf(slower, faster))

        assertEquals(listOf("faster", "slower"), result.map { it.id })
        assertTrue(result.all { it.isExportReady })
    }
}
