package com.happ.proxyfinder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BuildSiteUploadJsonTest {
    private fun verified(
        id: String,
        protocol: String = "VLESS",
        rawConfig: String = "vless://11111111-1111-4111-8111-111111111111@proxy.example:443#node"
    ) = ProxyItem(
        id = id,
        host = "proxy.example",
        port = 443,
        protocol = protocol,
        source = "unit-test",
        rawConfig = rawConfig,
        checkStatus = "Проверен через generate_204",
        latencyMs = 42,
        checkedAt = 1_800_000_000_000L
    )

    @Test
    fun exportsSiteConfigsWithUriAndResolvedLocationMetadata() {
        val payload = buildSiteUploadJson(listOf(
            verified("vless-1").copy(country = "Германия", countryCode = "DE", city = "Франкфурт"),
            verified("socks-1", "SOCKS5", "socks5://proxy.example:1080")
        ))

        val json = JSONObject(payload)
        val configs = json.getJSONArray("configs")
        assertEquals("Scaner Proxy X Android", json.getString("source"))
        assertEquals(2, configs.length())
        val first = configs.getJSONObject(0)
        assertEquals("vless://11111111-1111-4111-8111-111111111111@proxy.example:443#node", first.getString("uri"))
        assertEquals("Германия", first.getString("country"))
        assertEquals("DE", first.getString("countryCode"))
        assertEquals("Франкфурт", first.getString("city"))
        assertEquals("socks5://proxy.example:1080", configs.getJSONObject(1).getString("uri"))
    }

    @Test
    fun excludesUnverifiedUnsupportedAndDuplicateConfigs() {
        val duplicate = verified("duplicate")
        val unverified = verified("unverified").copy(checkStatus = "TCP доступен · прокси не подтверждён")
        val unsupported = verified("unsupported", "VMESS", "vmess://not-supported")

        val json = JSONObject(buildSiteUploadJson(listOf(duplicate, duplicate.copy(id = "same"), unverified, unsupported)))
        assertEquals(1, json.getJSONArray("configs").length())
        assertEquals(duplicate.rawConfig, json.getJSONArray("configs").getString(0))
    }

    @Test
    fun rejectsCatalogLargerThanWebsiteLimitInsteadOfSilentlyTruncating() {
        val items = (1..1001).map { index ->
            verified("node-$index", rawConfig = "socks5://proxy$index.example:1080")
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            buildSiteUploadJson(items)
        }
        assertEquals(true, error.message?.contains("не более 1000"))
    }

    @Test
    fun rejectsEmptyVerifiedCatalogInsteadOfCreatingAnEmptyUpload() {
        assertThrows(IllegalArgumentException::class.java) {
            buildSiteUploadJson(emptyList())
        }
    }
}
