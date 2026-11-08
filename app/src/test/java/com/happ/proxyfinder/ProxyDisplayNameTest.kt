package com.happ.proxyfinder

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyDisplayNameTest {
    private fun item(country: String, code: String, city: String) = ProxyItem(
        id = "test",
        host = "203.0.113.10",
        port = 443,
        protocol = "VLESS",
        source = "test-feed",
        rawConfig = "vless://example",
        title = "legacy-title-with-ip",
        country = country,
        countryCode = code,
        city = city
    )

    @Test
    fun displayNameContainsOnlyFlagCountryAndCity() {
        assertEquals("🇩🇪 Германия · Франкфурт", proxyDisplayName(item("Германия", "DE", "Франкфурт")))
    }

    @Test
    fun displayNameOmitsCityWhenGeoProviderDoesNotReturnIt() {
        assertEquals("🇩🇪 Германия", proxyDisplayName(item("Германия", "DE", "")))
    }

    @Test
    fun displayNameDoesNotLeakLegacyTitleWhenLocationIsUnknown() {
        assertEquals("🏳️ Геолокация не определена", proxyDisplayName(item("", "", "")))
    }
    @Test
    fun happImportLabelUsesFlagCountryAndCityForVless() {
        val config = namedConfigForImport(
            item("Германия", "DE", "Франкфурт").copy(
                rawConfig = "vless://uuid@example.com:443?security=tls#old-feed-name"
            )
        )
        assertEquals("🇩🇪 Германия · Франкфурт", java.net.URI(config).fragment)
    }

    @Test
    fun happSocksImportLabelIncludesCity() {
        val config = namedConfigForImport(
            item("Германия", "DE", "Франкфурт").copy(
                protocol = "SOCKS5",
                rawConfig = "socks5://example.com:1080"
            )
        )
        assertEquals("🇩🇪 Германия · Франкфурт", java.net.URI(config).fragment)
        assertEquals("socks://:@example.com:1080", config.substringBefore("#"))
    }

}
