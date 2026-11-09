package com.happ.proxyfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Test
import java.util.Base64

class ProxyConfigParsingTest {
    @Test
    fun rejectsMalformedHostsForGenericAndStructuredProtocols() {
        assertNull(ConfigParser.parseConfig("trojan://secret@bad host:443"))
        assertNull(ConfigParser.parseConfig("vless://00000000-0000-0000-0000-000000000000@999.1.1.1:443"))
        assertNull(ConfigParser.parseConfig("""{"type":"socks","server":"bad_host.example","server_port":1080}"""))
    }

    @Test
    fun acceptsValidIpv6AndInternationalizedEndpointHosts() {
        val ipv6 = ConfigParser.parseConfig("trojan://secret@[2001:4860:4860::8888]:443")
        val idn = ConfigParser.parseConfig("trojan://secret@xn--e1afmkfd.xn--p1ai:443")
        assertNotNull(ipv6)
        assertNotNull(idn)
        assertEquals("2001:4860:4860::8888", ipv6!!.host)
        assertEquals("xn--e1afmkfd.xn--p1ai", idn!!.host)
    }

    @Test
    fun streamingSourceReaderEnforcesMaximumRawBytes() {
        val exactLimit = BoundedInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        assertEquals(listOf<Byte>(1, 2, 3), exactLimit.readBytes().toList())

        val oversized = BoundedInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3)
        try {
            oversized.readBytes()
            fail("Expected the byte limit to reject an oversized source")
        } catch (_: IOException) {
            // Expected: untrusted feeds cannot exceed the configured raw-byte ceiling.
        }
    }

    @Test
    fun parsesHappSocksUriWithEmptyCredentialsAndEncodedLabel() {
        val item = ConfigParser.parseConfig(
            "socks://:@46.146.220.180:1080#%F0%9F%8F%B3%EF%B8%8F%20unknown"
        )
        assertNotNull(item)
        assertEquals("SOCKS5", item!!.protocol)
        assertEquals("46.146.220.180", item.host)
        assertEquals(1080, item.port)
    }

    @Test
    fun parsesIpv6SocksEndpoint() {
        val item = ConfigParser.parseConfig("socks5://user:pass@[2001:4860:4860::8888]:1080")
        assertNotNull(item)
        assertEquals("2001:4860:4860::8888", item!!.host)
        assertEquals(1080, item.port)
    }

    @Test
    fun parsesVmessBase64Json() {
        val json = """{"add":"vmess.example.net","port":"8443","id":"00000000-0000-0000-0000-000000000000"}"""
        val payload = Base64.getEncoder().withoutPadding().encodeToString(json.toByteArray())
        val item = ConfigParser.parseConfig("vmess://$payload")
        assertNotNull(item)
        assertEquals("VMESS", item!!.protocol)
        assertEquals("vmess.example.net", item.host)
        assertEquals(8443, item.port)
    }

    @Test
    fun parsesShadowsocksRLegacyLink() {
        val legacy = "ssr.example.net:443:origin:aes-128-cfb:plain:YWJj"
        val payload = Base64.getEncoder().withoutPadding().encodeToString(legacy.toByteArray())
        val item = ConfigParser.parseConfig("ssr://$payload")
        assertNotNull(item)
        assertEquals("ShadowsocksR", item!!.protocol)
        assertEquals("ssr.example.net", item.host)
        assertEquals(443, item.port)
    }

    @Test
    fun appliesDefaultPortToHysteria2AndAnyTls() {
        val hysteria = ConfigParser.parseConfig("hy2://secret@hysteria.example.net")
        val anyTls = ConfigParser.parseConfig("anytls://secret@anytls.example.net")
        assertNotNull(hysteria)
        assertNotNull(anyTls)
        assertEquals(443, hysteria!!.port)
        assertEquals(443, anyTls!!.port)
    }

    @Test
    fun parsesSingleStructuredClashOrSingBoxNode() {
        val item = ConfigParser.parseConfig(
            """{"type":"socks","tag":"🇩🇪 Frankfurt","server":"proxy.example.net","server_port":1080}"""
        )
        assertNotNull(item)
        assertEquals("SOCKS5", item!!.protocol)
        assertEquals("proxy.example.net", item.host)
        assertEquals(1080, item.port)
        assertEquals("DE", item.countryCode)
    }

    @Test
    fun parsesMultiNodeSingBoxJsonProfile() {
        val profile = """{"outbounds":[{"type":"socks","tag":"local","server":"one.example.net","server_port":1080},{"type":"vless","tag":"🇩🇪 Germany","server":"two.example.net","server_port":443}]}"""
        val items = ConfigParser.parseConfigs(profile)
        assertEquals(2, items.size)
        assertEquals("SOCKS5", items[0].protocol)
        assertEquals("VLESS", items[1].protocol)
        assertEquals("two.example.net", items[1].host)
    }

    @Test
    fun parsesClashYamlProxyList() {
        val profile = """
            mixed-port: 7890
            proxies:
              - name: Germany node
                type: socks5
                server: de.example.net
                port: 1080
              - name: US node
                type: http
                server: us.example.net
                port: 8080
        """.trimIndent()
        val items = ConfigParser.parseConfigs(profile)
        assertEquals(2, items.size)
        assertEquals("SOCKS5", items[0].protocol)
        assertEquals("HTTP", items[1].protocol)
        assertEquals("us.example.net", items[1].host)
    }

    @Test
    fun parsesWireGuardAndAmneziaWgIniEndpoint() {
        val profile = """
            [Interface]
            PrivateKey = hidden
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = hidden
            Endpoint = [2001:4860:4860::8888]:51820
        """.trimIndent()
        val item = ConfigParser.parseConfigs(profile).single()
        assertEquals("WireGuard", item.protocol)
        assertEquals("2001:4860:4860::8888", item.host)
        assertEquals(51820, item.port)
    }

    @Test
    fun rejectsMalformedAndNonProxyInput() {
        assertNull(ConfigParser.parseConfig("not a proxy"))
        assertNull(ConfigParser.parseConfig("socks5://host.example.net:99999"))
    }
}
