package com.happ.proxyfinder

import java.io.IOException
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Implements only the VLESS TCP + TLS profile.
 *
 * REALITY, WebSocket, gRPC, XHTTP, Vision/flow and custom TLS fingerprints are
 * deliberately rejected instead of being silently treated as plain TLS.
 */
internal object VlessTcpTls {
    private const val CONNECT_TIMEOUT_MS = 4_500
    private const val READ_TIMEOUT_MS = 5_500

    internal data class Config(
        val id: UUID,
        val host: String,
        val port: Int,
        val serverName: String
    )

    fun parse(rawConfig: String): Config? = runCatching {
        val uri = URI(rawConfig.trim())
        require(uri.scheme.equals("vless", ignoreCase = true))
        require(uri.rawFragment == null || uri.rawFragment.length <= 256)

        val rawId = uri.rawUserInfo ?: return null
        val idText = URLDecoder.decode(rawId, "UTF-8")
        require(UUID_PATTERN.matches(idText))
        val id = UUID.fromString(idText)

        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() }
            ?: return null
        val port = uri.port
        require(port in 1..65535)
        require(isValidHost(host))

        val parameters = parseQuery(uri.rawQuery ?: return null) ?: return null
        val allowed = setOf(
            "type", "security", "sni", "fp", "alpn", "flow",
            "encryption", "headertype", "allowinsecure", "insecure"
        )
        require(parameters.keys.all { it in allowed })
        require(parameters["security"].equals("tls", ignoreCase = true))
        require((parameters["type"] ?: "tcp").equals("tcp", ignoreCase = true))
        require((parameters["encryption"] ?: "none").equals("none", ignoreCase = true))
        require(parameters["flow"].isNullOrBlank())
        require(parameters["fp"].isNullOrBlank() || parameters["fp"].equals("none", ignoreCase = true))
        require(parameters["alpn"].isNullOrBlank())
        require(parameters["headertype"].isNullOrBlank() || parameters["headertype"].equals("none", ignoreCase = true))
        require(isFalse(parameters["allowinsecure"]))
        require(isFalse(parameters["insecure"]))

        val serverName = parameters["sni"]?.takeIf { it.isNotBlank() } ?: host
        require(isValidHost(serverName))
        Config(id, host, port, normalizeHost(serverName))
    }.getOrNull()

    /**
     * Opens a verified TLS connection to the VLESS endpoint, performs the VLESS
     * request/response exchange, and returns the live stream to the target.
     * The caller owns and must close the returned socket.
     */
    @Throws(IOException::class)
    fun openTunnel(
        rawConfig: String,
        endpointHost: String,
        endpointPort: Int,
        targetHost: String,
        targetPort: Int
    ): SSLSocket {
        val config = parse(rawConfig) ?: throw IOException("Неподдерживаемая конфигурация VLESS: требуется TCP + TLS без дополнительных транспортов")
        if (!config.host.equals(endpointHost, ignoreCase = true) || config.port != endpointPort) {
            throw IOException("Адрес VLESS-конфигурации не совпадает с записью прокси")
        }

        val tcp = Socket()
        var tls: SSLSocket? = null
        try {
            tcp.tcpNoDelay = true
            tcp.soTimeout = READ_TIMEOUT_MS
            tcp.connect(java.net.InetSocketAddress(endpointHost, endpointPort), CONNECT_TIMEOUT_MS)

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            tls = factory.createSocket(tcp, config.serverName, endpointPort, true) as SSLSocket
            tls.soTimeout = READ_TIMEOUT_MS
            val parameters = tls.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            if (!isIpLiteral(config.serverName)) {
                parameters.serverNames = listOf(SNIHostName(config.serverName))
            }
            tls.sslParameters = parameters
            tls.startHandshake()

            val request = buildRequest(config.id, targetHost, targetPort)
            tls.outputStream.write(request)
            tls.outputStream.flush()

            val input = tls.inputStream
            val responseVersion = input.read()
            if (responseVersion != 0) throw IOException("VLESS вернул неподдерживаемую версию ответа")
            val addonLength = input.read()
            if (addonLength < 0) throw IOException("VLESS оборвал заголовок ответа")
            readExact(input, addonLength)
            return tls
        } catch (error: Exception) {
            runCatching { tls?.close() }
            runCatching { tcp.close() }
            if (error is IOException) throw error
            throw IOException("VLESS TCP+TLS handshake не пройден: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    internal fun buildRequest(id: UUID, targetHost: String, targetPort: Int): ByteArray {
        require(targetPort in 1..65535)
        val address = encodeAddress(targetHost)
        val uuidBytes = ByteBuffer.allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()
        return byteArrayOf(
            0, // VLESS version
            *uuidBytes,
            0, // addons length
            1, // command: TCP
            (targetPort ushr 8).toByte(),
            targetPort.toByte()
        ) + address
    }

    private fun encodeAddress(host: String): ByteArray {
        val value = host.trim()
        require(value.isNotEmpty())
        return when {
            value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) -> {
                val octets = value.split('.').map { it.toIntOrNull() ?: -1 }
                require(octets.all { it in 0..255 })
                byteArrayOf(1) + octets.map { it.toByte() }.toByteArray()
            }
            value.contains(':') && value.matches(Regex("[0-9A-Fa-f:.]+")) -> {
                val address = InetAddress.getByName(value)
                require(address is Inet6Address)
                byteArrayOf(3) + address.address
            }
            else -> {
                val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toByteArray(Charsets.US_ASCII)
                require(ascii.isNotEmpty() && ascii.size <= 255)
                byteArrayOf(2, ascii.size.toByte()) + ascii
            }
        }
    }

    private fun parseQuery(rawQuery: String): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isBlank()) continue
            val separator = part.indexOf('=')
            val key = URLDecoder.decode(if (separator >= 0) part.substring(0, separator) else part, "UTF-8")
                .lowercase(Locale.ROOT)
            val value = URLDecoder.decode(if (separator >= 0) part.substring(separator + 1) else "", "UTF-8")
            if (key.isBlank() || result.containsKey(key)) return null
            result[key] = value
        }
        return result
    }

    private fun isFalse(value: String?): Boolean =
        value.isNullOrBlank() || value.equals("false", true) || value == "0"

    private fun isValidHost(value: String): Boolean {
        if (value.isBlank() || value.length > 253 || value.any { it.isWhitespace() || it.isISOControl() }) return false
        if (value.matches(Regex("[0-9.]+"))) return isIpLiteral(value)
        if (isIpLiteral(value)) return true
        return runCatching {
            val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES)
            ascii.length <= 253 && ascii.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first().isLetterOrDigit() && label.last().isLetterOrDigit()
            }
        }.getOrDefault(false)
    }

    private fun normalizeHost(value: String): String =
        if (isIpLiteral(value)) value else IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)

    private fun isIpLiteral(value: String): Boolean {
        if (value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
            return value.split('.').all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
        }
        if (!value.contains(':') || !value.matches(Regex("[0-9A-Fa-f:.]+"))) return false
        return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
    }

    private fun readExact(input: java.io.InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw IOException("VLESS оборвал дополнительные данные ответа")
            offset += read
        }
        return result
    }

    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
