package com.happ.proxyfinder

import org.json.JSONObject
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
    fun happSubscriptionEnablesNativePingSortingAndPlacesVerifiedFastNodesFirst() {
        val fast = item("Проверен через HTTPS-туннель · HTTP 204", 35).copy(
            id = "fast", host = "fast.example", rawConfig = "vless://fast"
        )
        val slow = item("Проверен через HTTPS-туннель · HTTP 204", 280).copy(
            id = "slow", host = "slow.example", rawConfig = "vless://slow"
        )
        val unchecked = item("Не проверен", null).copy(
            id = "unchecked", host = "unknown.example", rawConfig = "vless://unchecked", checkedAt = null
        )
        val failed = item("Проверка не пройдена: timeout", null).copy(
            id = "failed", host = "dead.example", rawConfig = "vless://failed", checkedAt = 2L
        )
        val socks4 = item("Проверен через HTTPS-туннель · HTTP 204", 5).copy(
            id = "socks4", protocol = "SOCKS4", rawConfig = "socks4://legacy"
        )

        val subscription = buildHappSubscription(listOf(slow, failed, unchecked, socks4, fast))

        assertTrue(subscription.startsWith(
            "#subscription-ping-onopen-enabled: 1\n" +
                "#subscriptions-sort-type: ping\n" +
                "#ping-type: proxy\n" +
                "#check-url-via-proxy: https://cp.cloudflare.com/generate_204\n"
        ))
        assertTrue(subscription.indexOf("vless://fast") < subscription.indexOf("vless://slow"))
        assertFalse(subscription.contains("vless://unchecked"))
        assertFalse(subscription.contains("vless://failed"))
        assertFalse(subscription.contains("socks4://"))
    }


    @Test
    fun happJsonIsAnArrayOfStandaloneXrayProfilesForVerifiedSupportedNodesOnly() {
        val socks = item("Проверен через HTTPS-туннель · HTTP 204", 42).copy(
            id = "socks-node", host = "proxy.example", port = 1080,
            rawConfig = "socks5://alice:secret@proxy.example:1080"
        )
        val vless = item("Проверен через HTTPS-туннель · HTTP 204", 95).copy(
            id = "vless-node", host = "vless.example", port = 443, protocol = "VLESS",
            rawConfig = "vless://00000000-0000-0000-0000-000000000000@vless.example:443?security=tls&type=tcp&sni=cdn.example"
        )
        val unchecked = item("Не проверен", null).copy(id = "unchecked")
        val unsupported = item("Проверен через HTTPS-туннель · HTTP 204", 20).copy(
            id = "http", protocol = "HTTP", rawConfig = "http://proxy.example:8080"
        )

        val profiles = org.json.JSONArray(buildHappVerifiedJson(listOf(socks, unchecked, unsupported, vless)))
        assertEquals(2, profiles.length())

        val socksProfile = profiles.getJSONObject(0)
        assertEquals("🇺🇳 Геолокация не определена", socksProfile.getString("remarks"))
        val socksOutbound = socksProfile.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", socksOutbound.getString("protocol"))
        val socksServer = socksOutbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("proxy.example", socksServer.getString("address"))
        assertEquals("alice", socksServer.getJSONArray("users").getJSONObject(0).getString("user"))
        assertEquals("secret", socksServer.getJSONArray("users").getJSONObject(0).getString("pass"))
        assertEquals("socks-in", socksProfile.getJSONArray("inbounds").getJSONObject(0).getString("tag"))

        val vlessOutbound = profiles.getJSONObject(1).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", vlessOutbound.getString("protocol"))
        assertEquals("00000000-0000-0000-0000-000000000000",
            vlessOutbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
                .getJSONArray("users").getJSONObject(0).getString("id"))
        assertEquals("cdn.example", vlessOutbound.getJSONObject("streamSettings")
            .getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test
    fun happJsonRejectsUnverifiedAndUnsupportedNodes() {
        val tcpOnly = item("TCP доступен · туннель не подтверждён", null)
        val http = item("Проверен через HTTPS-туннель · HTTP 204", 50).copy(
            protocol = "HTTP", rawConfig = "http://proxy.example:8080"
        )
        try {
            buildHappVerifiedJson(listOf(tcpOnly, http))
            throw AssertionError("Expected empty HAPP JSON set to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Нет проверенных SOCKS5/VLESS"))
        }
    }

    @Test
    fun hiddifySingBoxExportContainsOnlyVerifiedTypedOutbounds() {
        val socks = item("Проверен через HTTPS-туннель · HTTP 204", 42).copy(
            id = "socks-node", host = "proxy.example", port = 1080, rawConfig = "socks5://alice:secret@proxy.example:1080"
        )
        val vless = item("Проверен через HTTPS-туннель · HTTP 204", 95).copy(
            id = "vless-node", host = "vless.example", port = 443, protocol = "VLESS",
            rawConfig = "vless://00000000-0000-0000-0000-000000000000@vless.example:443?security=tls&type=tcp&sni=cdn.example"
        )
        val unchecked = item("Не проверен", null).copy(id = "unchecked", rawConfig = "socks5://unchecked")
        val config = JSONObject(buildHiddifySingBoxConfig(listOf(socks, unchecked, vless)))
        val outbounds = config.getJSONArray("outbounds")
        assertEquals(2, outbounds.length())
        assertEquals("socks", outbounds.getJSONObject(0).getString("type"))
        assertEquals("alice", outbounds.getJSONObject(0).getString("username"))
        assertEquals("secret", outbounds.getJSONObject(0).getString("password"))
        assertEquals("vless", outbounds.getJSONObject(1).getString("type"))
        assertEquals("00000000-0000-0000-0000-000000000000", outbounds.getJSONObject(1).getString("uuid"))
        assertEquals("proxy-socks-node", config.getJSONObject("route").getString("final"))
    }

    @Test
    fun hiddifyExportRejectsUnsupportedOrUnverifiedNodes() {
        val unsupported = item("Проверен через HTTPS-туннель · HTTP 204", 50).copy(
            protocol = "Shadowsocks", rawConfig = "ss://invalid"
        )
        val unverified = item("TCP доступен · туннель не подтверждён", null)
        assertEquals(null, singBoxOutboundForVerified(unsupported))
        assertEquals(null, singBoxOutboundForVerified(unverified))
        try {
            buildHiddifySingBoxConfig(listOf(unsupported, unverified))
            throw AssertionError("Expected empty verified set to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Нет проверенных туннелей"))
        }
    }

    @Test
    fun happExportNormalizesSocks5SchemeWithoutChangingCredentialsOrEndpoint() {
        assertEquals(
            "socks://user:secret@proxy.example:1080#node",
            normalizeProxySchemeForHapp("  socks5://user:secret@proxy.example:1080#node  ")
        )
        assertEquals(
            "socks://user:secret@proxy.example:1080",
            normalizeProxySchemeForHapp("SOCKS5://user:secret@proxy.example:1080")
        )
    }

    @Test
    fun happExportLeavesOtherProxySchemesUntouched() {
        val configs = listOf(
            "socks4://127.0.0.1:1080",
            "http://127.0.0.1:8080",
            "vless://id@example.com:443"
        )
        configs.forEach { assertEquals(it, normalizeProxySchemeForHapp(it)) }
    }

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
    fun parserRecognizesCountryNamesInServerTitles() {
        val cases = listOf(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#Germany-Frankfurt" to "DE",
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#USA-NewYork" to "US",
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#Singapore-01" to "SG",
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#Россия-Москва" to "RU"
        )
        cases.forEach { (config, expectedCode) ->
            val parsed = ConfigParser.parseConfig(config)
            assertEquals("Country should be detected for $config", expectedCode, parsed?.countryCode)
            assertTrue("Country name should not be blank for $config", parsed?.country?.isNotBlank() == true)
        }
    }

    @Test
    fun parserRecognizesBareUppercaseIsoCountryCode() {
        val parsed = ConfigParser.parseConfig(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#DE-Frankfurt-01"
        )
        assertEquals("DE", parsed?.countryCode)
    }


    @Test
    fun parserRecognizesAllAdvertisedUriSchemes() {
        val cases = listOf(
            "socks4://127.0.0.1:1080" to "SOCKS4",
            "socks5://127.0.0.1:1080" to "SOCKS5",
            "http://127.0.0.1:8080" to "HTTP",
            "https://127.0.0.1:8443" to "HTTPS",
            "hysteria2://00000000-0000-0000-0000-000000000000@example.com:443" to "Hysteria2",
            "hy2://00000000-0000-0000-0000-000000000000@example.com:443" to "Hysteria2",
            "tuic://00000000-0000-0000-0000-000000000000@example.com:443" to "TUIC"
        )
        cases.forEach { (uri, expected) ->
            assertEquals("Wrong protocol for $uri", expected, ConfigParser.parseConfig(uri)?.protocol)
        }
    }

    @Test
    fun parserAcceptsRawPublicProxyFeedEndpointsAfterSchemeNormalization() {
        val cases = listOf(
            "socks5://198.51.100.20:1080" to "SOCKS5",
            "socks4://198.51.100.21:1080" to "SOCKS4",
            "http://198.51.100.22:8080" to "HTTP",
            "https://198.51.100.23:8443" to "HTTPS"
        )
        cases.forEach { (uri, expected) ->
            val parsed = ConfigParser.parseConfig(uri, "public proxy list")
            assertEquals(expected, parsed?.protocol)
            assertEquals(uri, parsed?.rawConfig)
            assertEquals("public proxy list", parsed?.source?.removePrefix("Сеть · "))
        }
    }

    @Test
    fun nativeTunnelCheckCapabilityIsExplicit() {
        assertTrue(item("Не проверен", null).supportsNativeTunnelCheck)
        val vless = ProxyItem("v", "example.com", 443, "VLESS", "test", "vless://id@example.com:443")
        val ss = ProxyItem("s", "example.com", 443, "Shadowsocks", "test", "ss://encoded")
        assertTrue(vless.supportsNativeTunnelCheck)
        assertFalse(ss.supportsNativeTunnelCheck)
    }

    @Test
    fun tcpOnlyResultsRemainEligibleForRetry() {
        val tcpOnly = item("TCP доступен · туннель не подтверждён: timeout", null)
        assertTrue(tcpOnly.supportsNativeTunnelCheck)
        assertTrue(tcpOnly.needsNativeTunnelCheck)
    }

    @Test
    fun onlyVerifiedUniqueConfigurationsBelongInWorkingExport() {
        val verified = item("Проверен через generate_204 · HTTP 204", 85)
        val pending = item("Не проверен", null).copy(id = "pending", rawConfig = "socks5://pending")
        val failed = item("Проверка generate_204 не пройдена", null).copy(id = "failed", rawConfig = "socks5://failed")
        val duplicates = listOf(verified, verified.copy(id = "duplicate"), pending, failed)
        val exported = verifiedExportItems(duplicates)
        assertEquals(1, exported.size)
        assertTrue(exported.single().isExportReady)
    }

    @Test
    fun onlySuccessfulTunnelProbeIsEligibleForHappExport() {
        val verified = item("Проверен через generate_204 · HTTP 204", 85)
        val tcpOnly = item("TCP доступен · скорость туннеля не измерена: нужен core VLESS", 24)
        val failed = item("Проверка generate_204 не пройдена", null)
        assertTrue(verified.isExportReady)
        assertFalse(tcpOnly.isExportReady)
        assertFalse(failed.isExportReady)
    }

    @Test
    fun successfulFallbackHttps204IsVerifiedAndScored() {
        val proxy = item("Проверен через HTTPS-туннель · HTTP 204", 85)
        assertTrue(proxy.isTunnelVerified)
        assertTrue(proxy.isExportReady)
        assertEquals(90, proxy.qualityScore)
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

    @Test
    fun ipv4RangeParserEnforcesMaximumRangeAndValidOctets() {
        assertEquals(254, Ipv4RangeParser.parse("192.168.1.0/24")?.size)
        assertEquals(listOf("192.168.1.7"), Ipv4RangeParser.parse("192.168.1.7/32"))
        assertEquals(2, Ipv4RangeParser.parse("10.0.0.0/31")?.size)
        assertEquals(null, Ipv4RangeParser.parse("10.0.0.0/23"))
        assertEquals(null, Ipv4RangeParser.parse("10.0.0.0/33"))
        assertEquals(null, Ipv4RangeParser.parse("256.0.0.1/32"))
        assertEquals(null, Ipv4RangeParser.parse("10.0.0/24"))
        assertEquals(null, Ipv4RangeParser.parse("not-an-ip"))
    }

    @Test
    fun ipv4RangeParserAcceptsInclusiveStartEndSyntax() {
        assertEquals(
            listOf("192.168.1.10", "192.168.1.11", "192.168.1.12"),
            Ipv4RangeParser.parse("192.168.1.10-192.168.1.12")
        )
        assertEquals(listOf("10.0.0.1"), Ipv4RangeParser.parse("10.0.0.1-10.0.0.1"))
    }

    @Test
    fun ipv4RangeParserRejectsReversedOversizedAndNonRoutableRanges() {
        assertEquals(null, Ipv4RangeParser.parse("192.168.1.20-192.168.1.10"))
        assertEquals(null, Ipv4RangeParser.parse("192.168.0.1-192.168.1.1"))
        assertEquals(null, Ipv4RangeParser.parse("127.0.0.1-127.0.0.2"))
        assertEquals(null, Ipv4RangeParser.parse("224.0.0.1-224.0.0.2"))
        assertEquals(null, Ipv4RangeParser.parse("192.168.1.1-999.1.1.1"))
    }

    @Test
    fun ipv4RangeParserMergesOverlappingRangesAndEnforcesAggregateLimit() {
        assertEquals(
            listOf("192.168.1.10", "192.168.1.11", "192.168.1.12", "192.168.1.13"),
            Ipv4RangeParser.parseMany("192.168.1.10-192.168.1.12\n192.168.1.12-192.168.1.13")
        )
        assertEquals(null, Ipv4RangeParser.parseMany("192.168.1.1-192.168.2.1"))
    }

    @Test
    fun parserRejectsCommentsMalformedEndpointsAndUnsupportedSchemes() {
        assertEquals(null, ConfigParser.parseConfig(""))
        assertEquals(null, ConfigParser.parseConfig("# subscription comment"))
        assertEquals(null, ConfigParser.parseConfig("// comment"))
        assertEquals(null, ConfigParser.parseConfig("socks5://proxy.example:0"))
        assertEquals(null, ConfigParser.parseConfig("socks5://proxy.example:65536"))
        assertEquals(null, ConfigParser.parseConfig("socks5://:1080"))
        assertEquals(null, ConfigParser.parseConfig("ftp://proxy.example:21"))
        assertEquals(null, ConfigParser.parseConfig("socks5://proxy.example"))
    }

    @Test
    fun parserSupportsIpv6ProxyAuthorities() {
        val parsed = ConfigParser.parseConfig("socks5://user:pass@[2001:db8::1]:1080")
        assertEquals("2001:db8::1", parsed?.host)
        assertEquals(1080, parsed?.port)
        assertEquals("SOCKS5", parsed?.protocol)
    }

    @Test
    fun happNormalizerRemovesBomAndWhitespaceButDoesNotRewriteOtherSchemes() {
        assertEquals("socks://127.0.0.1:1080", normalizeProxySchemeForHapp("\uFEFF  SOCKS5://127.0.0.1:1080  "))
        assertEquals("socks4://127.0.0.1:1080", normalizeProxySchemeForHapp("  socks4://127.0.0.1:1080  "))
        assertEquals("", normalizeProxySchemeForHapp("  "))
    }

    @Test
    fun qualityScoreUsesOnlyVerifiedTunnelLatencyAndStableThresholds() {
        fun verified(latency: Int) = item("Проверен через generate_204 · HTTP 204", latency).qualityScore
        assertEquals(90, verified(99))
        assertEquals(80, verified(100))
        assertEquals(80, verified(249))
        assertEquals(65, verified(250))
        assertEquals(65, verified(499))
        assertEquals(45, verified(500))
        assertEquals(45, verified(899))
        assertEquals(25, verified(900))
        assertEquals(0, item("TCP доступен · tunnel not verified", 20).qualityScore)
    }


    @Test
    fun happSocksParserAcceptsPlainPartialBase64AndFullBase64Formats() {
        val plain = ConfigParser.parseConfig("socks://user:pass@192.0.2.10:1080")
        assertEquals("SOCKS5", plain?.protocol)
        assertEquals("192.0.2.10", plain?.host)
        assertEquals(1080, plain?.port)

        val partial = ConfigParser.parseConfig("socks://dXNlcjpwYXNz@192.0.2.11:1080#Partial")
        assertEquals("192.0.2.11", partial?.host)
        assertEquals(1080, partial?.port)

        val encodedAuthority = java.util.Base64.getEncoder().encodeToString(
            "user:pass@192.0.2.12:1080".toByteArray(Charsets.UTF_8)
        )
        val full = ConfigParser.parseConfig("socks://$encodedAuthority")
        assertEquals("192.0.2.12", full?.host)
        assertEquals(1080, full?.port)
    }

    @Test
    fun geoMergePreservesEveryProxyAndExistingMetadata() {
        fun proxy(id: String, host: String, country: String = "", code: String = "", city: String = "") =
            ProxyItem(id, host, 1080, "SOCKS5", "test", "socks://$host:1080", country = country, countryCode = code, city = city)
        val original = listOf(
            proxy("a", "192.0.2.1", country = "Россия", code = "RU"),
            proxy("b", "192.0.2.2"),
            proxy("c", "192.0.2.3")
        )
        val resolved = listOf(
            proxy("a", "192.0.2.1", country = "Россия", code = "RU", city = "Москва"),
            proxy("c", "192.0.2.3", country = "Германия", code = "DE", city = "Берлин")
        )
        val merged = mergeResolvedGeoResults(original, resolved)
        assertEquals(3, merged.size)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("RU", merged[0].countryCode)
        assertEquals("Москва", merged[0].city)
        assertEquals("", merged[1].country)
        assertEquals("Берлин", merged[2].city)
    }


    @Test
    fun proxyCredentialsDecodePlainPartialAndFullBase64WithoutChangingPlusSigns() {
        assertEquals("user" to "pass", parseProxyCredentials("socks://user:pass@192.0.2.10:1080"))
        assertEquals("user" to "pass", parseProxyCredentials("socks://dXNlcjpwYXNz@192.0.2.11:1080#Partial"))

        val full = java.util.Base64.getEncoder().encodeToString(
            "user:pass@192.0.2.12:1080".toByteArray(Charsets.UTF_8)
        )
        assertEquals("user" to "pass", parseProxyCredentials("socks://$full"))

        assertEquals("user+tag" to "p+ass", parseProxyCredentials("socks://user+tag:p+ass@192.0.2.13:1080"))
    }


    @Test
    fun happSocksExportAddsEmptyCredentialsAndCountryFlagToTheName() {
        val germany = ProxyItem(
            id = "de", host = "203.0.113.10", port = 1080, protocol = "SOCKS5",
            source = "test", rawConfig = "socks5://203.0.113.10:1080",
            country = "Германия", countryCode = "DE"
        )
        assertEquals(
            "socks://:@203.0.113.10:1080#%F0%9F%87%A9%F0%9F%87%AA%20%D0%93%D0%B5%D1%80%D0%BC%D0%B0%D0%BD%D0%B8%D1%8F",
            namedConfigForImport(germany)
        )
    }

    @Test
    fun happSocksExportPreservesRealCredentialsAndReplacesOldCountryLabel() {
        val germany = ProxyItem(
            id = "de-auth", host = "proxy.example", port = 1080, protocol = "SOCKS5",
            source = "test", rawConfig = "socks5://alice:secret@proxy.example:1080#old-name",
            country = "Germany", countryCode = "DE"
        )
        assertEquals(
            "socks://alice:secret@proxy.example:1080#%F0%9F%87%A9%F0%9F%87%AA%20Germany",
            namedConfigForImport(germany)
        )
    }

    @Test
    fun happExportDoesNotRewriteSocks4OrOtherProtocols() {
        val socks4 = ProxyItem("s4", "192.0.2.1", 1080, "SOCKS4", "test", "socks4://192.0.2.1:1080", country = "Germany", countryCode = "DE")
        val vless = ProxyItem("v", "example.com", 443, "VLESS", "test", "vless://id@example.com:443", country = "Germany", countryCode = "DE")
        assertEquals("socks4://192.0.2.1:1080", namedConfigForImport(socks4))
        assertEquals("vless://id@example.com:443", namedConfigForImport(vless))
    }


    @Test
    fun parsesVmessUrlSafeBase64WithoutPaddingAndKeepsOriginalLinkForHapp() {
        val json = """{"v":"2","ps":"DE Frankfurt","add":"vmess.example.net","port":443,"id":"00000000-0000-0000-0000-000000000000","aid":0,"scy":"auto","net":"ws","type":"none","host":"cdn.example.net","path":"/vmess","tls":"tls","sni":"cdn.example.net"}"""
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        val link = "vmess://$encoded#DE-Frankfurt"
        val parsed = ConfigParser.parseConfig(link)
        assertEquals("VMESS", parsed?.protocol)
        assertEquals("vmess.example.net", parsed?.host)
        assertEquals(443, parsed?.port)
        assertEquals(link, parsed?.rawConfig)
        assertTrue(namedConfigForImport(parsed!!).startsWith("vmess://$encoded#"))
    }

    @Test
    fun parsesShadowsocksSip002UrlSafeCredentialsAndPluginPath() {
        val credentials = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("chacha20-ietf-poly1305:secret".toByteArray(Charsets.UTF_8))
        val link = "ss://$credentials@ss.example.net:8388/?plugin=v2ray-plugin%3Btls%3Bhost%3Dcdn.example.net#DE-Berlin"
        val parsed = ConfigParser.parseConfig(link)
        assertEquals("Shadowsocks", parsed?.protocol)
        assertEquals("ss.example.net", parsed?.host)
        assertEquals(8388, parsed?.port)
        assertEquals(link, parsed?.rawConfig)
    }

    @Test
    fun parsesLegacyShadowsocksBase64AuthorityWithIpv6() {
        val authority = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:secret@[2001:db8::10]:8388".toByteArray(Charsets.UTF_8))
        val link = "ss://$authority#DE-Frankfurt"
        val parsed = ConfigParser.parseConfig(link)
        assertEquals("Shadowsocks", parsed?.protocol)
        assertEquals("2001:db8::10", parsed?.host)
        assertEquals(8388, parsed?.port)
    }


    @Test
    fun parserHandlesUppercaseVmessAndShadowsocksSchemes() {
        val vmessJson = """{"add":"vmess.example.net","port":443,"id":"00000000-0000-0000-0000-000000000000","aid":0,"net":"tcp","type":"none","tls":""}"""
        val vmessPayload = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(vmessJson.toByteArray(Charsets.UTF_8))
        val vmess = ConfigParser.parseConfig("VMESS://$vmessPayload")
        assertEquals("VMESS", vmess?.protocol)
        assertEquals("vmess.example.net", vmess?.host)
        assertEquals(443, vmess?.port)

        val ssCredentials = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:secret".toByteArray(Charsets.UTF_8))
        val shadowsocks = ConfigParser.parseConfig("SS://$ssCredentials@ss.example.net:8388")
        assertEquals("Shadowsocks", shadowsocks?.protocol)
        assertEquals("ss.example.net", shadowsocks?.host)
        assertEquals(8388, shadowsocks?.port)
    }

    @Test
    fun happExportCanonicalizesVmessAndShadowsocksSchemeCaseOnly() {
        assertEquals("vmess://payload#node", normalizeProxySchemeForHapp("VMESS://payload#node"))
        assertEquals("ss://payload", normalizeProxySchemeForHapp("SS://payload"))
        assertEquals("socks://user:pass@host:1080", normalizeProxySchemeForHapp("SOCKS5://user:pass@host:1080"))
    }

    @Test
    fun ipv4RangeParserAcceptsMultipleCidrsAndDeduplicatesOverlapsWithinAggregateLimit() {
        val addresses = Ipv4RangeParser.parseMany("192.168.1.0/30\n192.168.1.2/31\n10.0.0.7")
        assertEquals(listOf("192.168.1.1", "192.168.1.2", "192.168.1.3", "10.0.0.7"), addresses)
    }

    @Test
    fun ipv4RangeParserRejectsTooManyRangesAndAggregateHosts() {
        assertEquals(null, Ipv4RangeParser.parseMany("192.168.1.0/24\n192.168.2.0/24\n192.168.3.0/24\n192.168.4.0/24\n192.168.5.0/24"))
        assertEquals(null, Ipv4RangeParser.parseMany("10.0.0.0/24\n10.0.1.0/24\n10.0.2.0/24\n10.0.3.0/24\n10.0.4.0/24"))
        assertEquals(null, Ipv4RangeParser.parseMany("10.0.0.0/24\nnot-an-ip"))
    }

}
