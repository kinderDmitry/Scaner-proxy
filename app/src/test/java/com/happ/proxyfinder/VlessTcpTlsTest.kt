package com.happ.proxyfinder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class VlessTcpTlsTest {
    private val uuid = "a4b5c6d7-e8f9-4012-8abc-1234567890ab"

    @Test
    fun parsesVlessTcpTlsWithExplicitServerName() {
        val parsed = VlessTcpTls.parse(
            "vless://$uuid@203.0.113.10:443?type=tcp&security=tls&sni=edge.example.com#Test"
        )
        assertNotNull(parsed)
        assertEquals(UUID.fromString(uuid), parsed!!.id)
        assertEquals("edge.example.com", parsed.serverName)
        assertEquals("203.0.113.10", parsed.host)
        assertEquals(443, parsed.port)
    }

    @Test
    fun defaultsServerNameToEndpointHostAndTcpTransport() {
        val parsed = VlessTcpTls.parse("vless://$uuid@example.com:8443?security=tls")
        assertNotNull(parsed)
        assertEquals("example.com", parsed!!.serverName)
        assertEquals(8443, parsed.port)
    }

    @Test
    fun rejectsUnsupportedVlessTransportsAndSecurityModes() {
        val unsupported = listOf(
            "vless://$uuid@example.com:443?security=reality",
            "vless://$uuid@example.com:443?security=tls&type=ws",
            "vless://$uuid@example.com:443?security=tls&type=grpc",
            "vless://$uuid@example.com:443?security=tls&type=xhttp",
            "vless://$uuid@example.com:443?security=tls&flow=xtls-rprx-vision",
            "vless://$uuid@example.com:443?security=tls&fp=chrome",
            "vless://$uuid@example.com:443?security=tls&allowInsecure=1"
        )
        unsupported.forEach { assertNull("Must reject $it", VlessTcpTls.parse(it)) }
    }

    @Test
    fun rejectsMalformedIdentityEndpointAndAmbiguousParameters() {
        val invalid = listOf(
            "vless://not-a-uuid@example.com:443?security=tls",
            "vless://$uuid@example.com:0?security=tls",
            "vless://$uuid@:443?security=tls",
            "vless://$uuid@example.com:443?security=tls&type=tcp&type=ws",
            "vless://$uuid@example.com:443?security=none"
        )
        invalid.forEach { assertNull("Must reject $it", VlessTcpTls.parse(it)) }
    }

    @Test
    fun buildsCorrectVlessTcpRequestForDomainTarget() {
        val expected = byteArrayOf(
            0,
            0xA4.toByte(), 0xB5.toByte(), 0xC6.toByte(), 0xD7.toByte(),
            0xE8.toByte(), 0xF9.toByte(), 0x40, 0x12,
            0x8A.toByte(), 0xBC.toByte(), 0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0xAB.toByte(),
            0, 1, 1, 0xBB.toByte(), 2, 14,
            'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(), '.'.code.toByte(),
            'g'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            't'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), '.'.code.toByte(),
            'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
        )
        assertArrayEquals(expected, VlessTcpTls.buildRequest(UUID.fromString(uuid), "www.gstatic.com", 443))
    }
}
