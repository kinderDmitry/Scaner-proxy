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
}
