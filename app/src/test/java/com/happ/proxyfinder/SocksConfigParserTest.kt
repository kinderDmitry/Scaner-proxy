package com.happ.proxyfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class SocksConfigParserTest {
    @Test
    fun parsesPlainHappSocksLink() {
        val item = ConfigParser.parseConfig("socks://user:pass@127.0.0.1:1080")
        assertNotNull(item)
        assertEquals("SOCKS5", item!!.protocol)
        assertEquals("127.0.0.1", item.host)
        assertEquals(1080, item.port)
    }

    @Test
    fun parsesSocks5AliasAndPort() {
        val item = ConfigParser.parseConfig("socks5://user:pass@example.com:443#Test")
        assertNotNull(item)
        assertEquals("SOCKS5", item!!.protocol)
        assertEquals("example.com", item.host)
        assertEquals(443, item.port)
    }

    @Test
    fun parsesHappPartialBase64Credentials() {
        val credentials = Base64.getEncoder().encodeToString("user:pass".toByteArray())
        val item = ConfigParser.parseConfig("socks://${credentials}@192.0.2.10:1080#Node")
        assertNotNull(item)
        assertEquals("192.0.2.10", item!!.host)
        assertEquals(1080, item.port)
    }

    @Test
    fun parsesHappFullyBase64EncodedAuthority() {
        val authority = Base64.getEncoder().encodeToString("user:pass@198.51.100.20:2080".toByteArray())
        val item = ConfigParser.parseConfig("socks://${authority}")
        assertNotNull(item)
        assertEquals("198.51.100.20", item!!.host)
        assertEquals(2080, item.port)
    }

    @Test
    fun rejectsMalformedSocksConfiguration() {
        assertNull(ConfigParser.parseConfig("socks://not-a-valid-base64-or-host"))
        assertNull(ConfigParser.parseConfig("socks://user:pass@example.com:99999"))
        assertNull(ConfigParser.parseConfig("socks://example.com"))
    }
}
