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
    fun `export ranks lower latency ahead when quality scores match`() {
        val slower = verified("slower", 180)
        val faster = verified("faster", 120)

        val result = rankExportableProxies(listOf(slower, faster))

        assertEquals(listOf("faster", "slower"), result.map { it.id })
        assertTrue(result.all { it.isExportReady })
    }
}
