package com.happ.proxyfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ProxyConfigParsingTest {
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
    fun rejectsMalformedAndNonProxyInput() {
        assertNull(ConfigParser.parseConfig("not a proxy"))
        assertNull(ConfigParser.parseConfig("socks5://host.example.net:99999"))
    }
}
