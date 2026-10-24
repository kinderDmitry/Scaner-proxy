package com.happ.proxyfinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Proxy
import java.net.URL
import java.net.URI
import java.util.Base64
import java.util.Locale

/** Fetches public configuration feeds. Feed contents are untrusted input and are never executed. */
class ProxyRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("proxy_finder", Context.MODE_PRIVATE)

    private val sources = listOf(
        Source("Morpheus · Лучшие", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt"),
        Source("Morpheus · Компактный", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/mini.txt"),
        Source("OpenProxyList · V2Ray", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/V2RAY_RAW.txt"),
        Source("Pawdroid · Подписка", "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub"),
        Source("V2RayAggregator · Объединённый список", "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt")
    )

    suspend fun fetch(onSource: suspend (String) -> Unit): FetchResult = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            sources.map { source -> async {
                onSource(source.name)
                val parsed = runCatching { fetchSource(source) }.getOrDefault(emptyList())
                source.name to parsed
            } }.awaitAll()
        }
        val sourceCounts = results.associate { it.first to it.second.size }
        val merged = linkedMapOf<String, ProxyItem>()
        results.flatMap { it.second }.forEach { item ->
            val key = item.rawConfig.trim()
            if (key.isNotEmpty() && !merged.containsKey(key)) merged[key] = item
        }
        val favorites = loadFavorites()
        FetchResult(merged.values.map { it.copy(favorite = favorites.contains(it.id)) }, sourceCounts)
    }

    private fun fetchSource(source: Source): List<ProxyItem> {
        val connection = URL(source.url).openConnection().apply {
            connectTimeout = 6500
            readTimeout = 8500
            setRequestProperty("User-Agent", "HAPP-Proxy-Finder/1.0 (Android)")
            setRequestProperty("Accept", "text/plain,*/*")
        }
        val text = connection.getInputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_SOURCE_BYTES) break
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
        val decoded = decodeSubscriptionIfNeeded(text)
        val seen = HashSet<String>()
        return decoded.lineSequence().take(MAX_LINES).mapNotNull { raw ->
            val line = raw.trim().substringBefore(" | ").trim()
            ConfigParser.parseConfig(line, source.name)
        }.filter { seen.add(it.rawConfig) }.toList()
    }

    private fun decodeSubscriptionIfNeeded(text: String): String {
        if (text.contains(Regex("(?im)^\\s*(vless|vmess|trojan|ss|socks|hysteria2|hy2)://"))) return text
        val compact = text.trim().replace("\\s".toRegex(), "")
        if (compact.length < 16 || !compact.matches(Regex("[A-Za-z0-9+/=_-]+"))) return text
        return runCatching {
            val padded = compact.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8).takeIf { it.contains("://") } ?: text
        }.getOrDefault(text)
    }


    /**
     * Probes a small, explicitly supplied IPv4 range only. This is not an internet-wide scanner:
     * the range is capped at /24 (256 addresses), port count is capped, and concurrency is bounded.
     * Use only on networks/hosts the user owns or is authorized to assess.
     */
    suspend fun scanAuthorizedRange(
        rangeInput: String,
        portsInput: String,
        onProgress: suspend (String) -> Unit
    ): EndpointScanResult = withContext(Dispatchers.IO) {
        val addresses = Ipv4RangeParser.parse(rangeInput)
            ?: return@withContext EndpointScanResult(emptyList(), "Укажи корректный IPv4-адрес или CIDR не шире /24")
        val ports = portsInput.split(',', ' ', ';', '\n', '\t')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }.distinct().take(8)
        if (ports.isEmpty()) return@withContext EndpointScanResult(emptyList(), "Укажи от 1 до 8 TCP-портов")
        val tasks = addresses.flatMap { ip -> ports.map { port -> ip to port } }
        val found = java.util.concurrent.ConcurrentLinkedQueue<OpenEndpoint>()
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = Semaphore(16)
        coroutineScope {
            tasks.map { (ip, port) -> async {
                gate.withPermit {
                    val started = System.nanoTime()
                    val open = runCatching {
                        Socket().use { socket ->
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(ip, port), 900)
                        }
                        true
                    }.getOrDefault(false)
                    val count = completed.incrementAndGet()
                    if (open) {
                        found.add(OpenEndpoint(ip, port, ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)))
                    }
                    if (count % 12 == 0 || count == tasks.size) onProgress("Проверено $count из ${tasks.size} адресов/портов · открыто ${found.size}")
                }
            } }.awaitAll()
        }
        EndpointScanResult(found.sortedWith(compareBy<OpenEndpoint> { it.latencyMs }.thenBy { it.host }.thenBy { it.port }),
            "Завершено: ${tasks.size} проверок · открыто ${found.size} TCP-портов. Это не подтверждает наличие прокси или рабочий туннель.")
    }


    /**
     * Scans only the currently connected Wi-Fi subnet. It never scans public IP ranges.
     * Open TCP ports are reported separately from protocol-confirmed SOCKS/HTTP proxies.
     */
    suspend fun scanLocalNetwork(onProgress: suspend (String) -> Unit): EndpointScanResult = withContext(Dispatchers.IO) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        // activeNetwork may be a VPN tunnel. Select the actual Wi-Fi network from allNetworks first.
        val wifiNetwork = connectivity.allNetworks.firstOrNull { candidate ->
            connectivity.getNetworkCapabilities(candidate)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: run {
            val active = connectivity.activeNetwork
            if (active != null && connectivity.getNetworkCapabilities(active)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) active else null
        } ?: return@withContext EndpointScanResult(
            emptyList(),
            "Wi-Fi сеть не найдена. Подключись к Wi-Fi; если VPN скрывает сеть, временно отключи VPN и повтори поиск."
        )
        val linkProperties = connectivity.getLinkProperties(wifiNetwork)
            ?: return@withContext EndpointScanResult(emptyList(), "Android не предоставил параметры Wi-Fi сети")
        val link = linkProperties.linkAddresses.firstOrNull {
            it.address is java.net.Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
        } ?: return@withContext EndpointScanResult(emptyList(), "У Wi-Fi нет локального IPv4-адреса")
        val ownIp = link.address.hostAddress ?: return@withContext EndpointScanResult(emptyList(), "Не удалось прочитать IP устройства")
        val octets = ownIp.split('.').mapNotNull { it.toLongOrNull() }
        if (octets.size != 4) return@withContext EndpointScanResult(emptyList(), "Локальный IPv4-адрес некорректен")
        // Keep the scan bounded to the /24 containing this device, even on larger home/office subnets.
        val prefix = maxOf(link.prefixLength, 24)
        if (prefix > 30) return@withContext EndpointScanResult(emptyList(), "Слишком маленькая подсеть Wi-Fi: /${link.prefixLength}")
        val ownLong = octets.fold(0L) { acc, value -> (acc shl 8) or value }
        val hostBits = 32 - prefix
        val mask = (0xFFFF_FFFFL shl hostBits) and 0xFFFF_FFFFL
        val networkStart = ownLong and mask
        val broadcast = networkStart or (mask xor 0xFFFF_FFFFL)
        val addresses = (networkStart + 1 until broadcast)
            .filter { it != ownLong }
            .map { value -> listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 255).toString() } }
        val ports = listOf(1080, 1081, 10808, 10809, 3128, 3129, 8080, 8081, 8118, 8888, 7890, 7891, 7892, 8388, 9050, 9150, 4145, 8000)
        val tasks = addresses.flatMap { ip -> ports.map { port -> ip to port } }
        val found = java.util.concurrent.ConcurrentLinkedQueue<OpenEndpoint>()
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = Semaphore(72)
        coroutineScope {
            tasks.map { (ip, port) -> async {
                gate.withPermit {
                    probeLocalEndpoint(ip, port)?.let(found::add)
                    val count = completed.incrementAndGet()
                    if (count % 100 == 0 || count == tasks.size) {
                        onProgress("Локальная сеть: проверено $count/${tasks.size} адресов и портов · найдено ${found.size}")
                    }
                }
            } }.awaitAll()
        }
        val sorted = found.sortedWith(compareBy<OpenEndpoint> { it.protocol == "TCP" }.thenBy { it.latencyMs }.thenBy { it.host }.thenBy { it.port })
        EndpointScanResult(sorted, "Локальная сеть · найдено ${sorted.size} открытых портов; протокол подтверждён только для SOCKS5/HTTP CONNECT")
    }

    private fun probeLocalEndpoint(host: String, port: Int): OpenEndpoint? {
        val started = System.nanoTime()
        val socket = java.net.Socket()
        val tcpMs = try {
            socket.tcpNoDelay = true
            socket.connect(java.net.InetSocketAddress(host, port), 450)
            ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            runCatching { socket.close() }
            return null
        }
        try {
            socket.soTimeout = 350
            socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            socket.getOutputStream().flush()
            val input = socket.getInputStream()
            val version = input.read()
            val method = input.read()
            if (version == 5 && method in 0..255) {
                return OpenEndpoint(host, port, tcpMs, "SOCKS5", if (method == 0) "SOCKS5 обнаружен · без авторизации" else "SOCKS5 обнаружен · может требоваться авторизация")
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
        val httpStatus = runCatching {
            java.net.Socket().use { http ->
                http.connect(java.net.InetSocketAddress(host, port), 450)
                http.soTimeout = 350
                http.getOutputStream().write("CONNECT www.gstatic.com:443 HTTP/1.1\r\nHost: www.gstatic.com:443\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                http.getOutputStream().flush()
                val reader = http.getInputStream().bufferedReader(Charsets.US_ASCII)
                val statusLine = reader.readLine().orEmpty()
                Regex("^HTTP/1\\.[01] (200|407)(?:\\s.*)?$").find(statusLine)?.groupValues?.get(1)
            }
        }.getOrNull()
        return when (httpStatus) {
            "200" -> OpenEndpoint(host, port, tcpMs, "HTTP", "HTTP CONNECT работает")
            "407" -> OpenEndpoint(host, port, tcpMs, "HTTP", "HTTP-прокси обнаружен · требуется авторизация")
            else -> OpenEndpoint(host, port, tcpMs, "TCP", "Открытый TCP-порт · прокси не подтверждён")
        }
    }

    suspend fun check(item: ProxyItem): ProxyItem = withContext(Dispatchers.IO) {
        val checkedAt = System.currentTimeMillis()
        if (item.port !in 1..65535) return@withContext item.copy(checkStatus = "Неверный порт", latencyMs = null, checkedAt = checkedAt)
        val proxyType = when (item.protocol.uppercase(Locale.ROOT)) {
            "SOCKS5" -> Proxy.Type.SOCKS
            "HTTP", "HTTPS" -> Proxy.Type.HTTP
            else -> null
        }
        if (proxyType == null) {
            val started = System.nanoTime()
            return@withContext try {
                Socket().use { socket -> socket.tcpNoDelay = true; socket.connect(InetSocketAddress(item.host, item.port), 2600) }
                val tcpMs = ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
                item.copy(checkStatus = "TCP доступен · скорость туннеля не измерена: нужен core ${item.protocol}", latencyMs = tcpMs, checkedAt = checkedAt)
            } catch (_: Exception) {
                item.copy(checkStatus = "TCP недоступен", latencyMs = null, checkedAt = checkedAt)
            }
        }
        try {
            val proxy = Proxy(proxyType, InetSocketAddress(item.host, item.port))
            val connection = URL(SPEED_TEST_URL).openConnection(proxy).apply {
                connectTimeout = 4500
                readTimeout = 4500
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "HAPP-Proxy-Finder/1.2")
            } as java.net.HttpURLConnection
            val started = System.nanoTime()
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
            connection.disconnect()
            if (status == 204) {
                item.copy(checkStatus = "Проверен через generate_204 · HTTP 204", latencyMs = elapsed, checkedAt = checkedAt)
            } else {
                // A response other than 204 is not a successful latency probe; never present it as a working proxy.
                item.copy(checkStatus = "Проверка generate_204: неожиданный HTTP $status", latencyMs = null, checkedAt = checkedAt)
            }
        } catch (_: Exception) {
            item.copy(checkStatus = "Проверка generate_204 не пройдена", latencyMs = null, checkedAt = checkedAt)
        }
    }

    suspend fun checkBatch(items: List<ProxyItem>, onResult: suspend (ProxyItem) -> Unit): List<ProxyItem> = coroutineScope {
        val gate = Semaphore(12)
        items.map { item -> async {
            gate.withPermit { check(item).also { onResult(it) } }
        } }.awaitAll()
    }

    suspend fun resolveCountries(items: List<ProxyItem>, onResolved: suspend (ProxyItem) -> Unit): List<ProxyItem> = withContext(Dispatchers.IO) {
        val cached = runCatching {
            val json = JSONObject(prefs.getString("geo_cache", "{}"))
            buildMap<String, Pair<String, String>> {
                json.keys().forEach { host ->
                    val value = json.optJSONObject(host) ?: return@forEach
                    val country = value.optString("country")
                    if (country.isNotBlank()) put(host, country to value.optString("code"))
                }
            }
        }.getOrDefault(emptyMap())
        val candidates = items.filter { it.country.isBlank() && isIpv4(it.host) && !cached.containsKey(it.host) }
            .distinctBy { it.host }.take(MAX_GEO_LOOKUPS)
        val updated = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        val gate = Semaphore(3)
        coroutineScope {
            candidates.map { item -> async {
                gate.withPermit {
                    runCatching {
                        val url = URL("https://ipwho.is/${item.host}?fields=success,country,country_code")
                        val conn = url.openConnection().apply { connectTimeout = 2200; readTimeout = 2200; setRequestProperty("User-Agent", "HAPP-Proxy-Finder/1.0") }
                        val json = JSONObject(conn.getInputStream().bufferedReader().use { it.readText().take(16_384) })
                        if (json.optBoolean("success")) {
                            val country = json.optString("country").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                            val code = json.optString("country_code").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                            if (country.isNotBlank()) updated[item.host] = country to code
                        }
                    }
                }
            } }.awaitAll()
        }
        val allGeo = cached + updated
        val cacheJson = JSONObject()
        allGeo.forEach { (host, value) -> cacheJson.put(host, JSONObject().put("country", value.first).put("code", value.second)) }
        prefs.edit().putString("geo_cache", cacheJson.toString()).apply()
        val resolved = items.map { item ->
            val country = allGeo[item.host]
            if (country == null || item.country.isNotBlank()) item else item.copy(country = country.first, countryCode = country.second)
        }
        resolved.filter { it.country.isNotBlank() }.forEach { onResolved(it) }
        resolved
    }

    private fun isIpv4(host: String): Boolean {
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        if (octets[0] == 0 || octets[0] == 10 || octets[0] == 127 || octets[0] >= 224) return false
        if (octets[0] == 169 && octets[1] == 254) return false
        if (octets[0] == 172 && octets[1] in 16..31) return false
        if (octets[0] == 192 && octets[1] == 168) return false
        if (octets[0] == 100 && octets[1] in 64..127) return false
        return true
    }

    fun loadFavorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun saveFavorites(ids: Set<String>) { prefs.edit().putStringSet("favorites", ids).apply() }

    fun saveSnapshot(items: List<ProxyItem>) {
        val arr = JSONArray()
        items.take(MAX_SNAPSHOT).forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id); put("host", item.host); put("port", item.port); put("protocol", item.protocol)
                put("source", item.source); put("rawConfig", item.rawConfig); put("status", item.checkStatus)
                put("latency", item.latencyMs ?: -1); put("favorite", item.favorite); put("checked", item.checkedAt ?: 0L)
                put("title", item.title); put("country", item.country); put("countryCode", item.countryCode)
            })
        }
        prefs.edit().putString("snapshot", arr.toString()).apply()
    }

    fun loadSnapshot(): List<ProxyItem> = runCatching {
        val arr = JSONArray(prefs.getString("snapshot", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ProxyItem(
                id = o.getString("id"), host = o.getString("host"), port = o.getInt("port"), protocol = o.getString("protocol"),
                source = o.getString("source"), rawConfig = o.getString("rawConfig"), checkStatus = o.optString("status", "Не проверен"),
                latencyMs = o.optInt("latency", -1).takeIf { it >= 0 }, favorite = o.optBoolean("favorite"),
                checkedAt = o.optLong("checked").takeIf { it > 0 }, title = o.optString("title"),
                country = o.optString("country"), countryCode = o.optString("countryCode")
            )
        }
    }.getOrDefault(emptyList())

    private data class Source(val name: String, val url: String)

    companion object {
        private const val SPEED_TEST_URL = "https://www.gstatic.com/generate_204"
        private const val MAX_SOURCE_BYTES = 1_800_000
        private const val MAX_LINES = 20_000
        private const val MAX_SNAPSHOT = 2_500
        private const val MAX_GEO_LOOKUPS = 24
    }
}


internal object ConfigParser {
    fun parseConfig(rawLine: String, sourceName: String = "Источник"): ProxyItem? {
        val line = rawLine.trim().removePrefix("\uFEFF")
        if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) return null
        val match = Regex("^(vless|vmess|trojan|ss|socks|socks5|hysteria2|hy2)://", RegexOption.IGNORE_CASE).find(line) ?: return null
        val scheme = match.groupValues[1].lowercase(Locale.ROOT)
        val protocol = when (scheme) {
            "hy2", "hysteria2" -> "Hysteria2"
            "ss" -> "Shadowsocks"
            "socks", "socks5" -> "SOCKS5"
            else -> scheme.uppercase(Locale.ROOT)
        }
        val endpoint = runCatching {
            when (scheme) {
                "vmess" -> {
                    val payload = line.substringAfter("vmess://").substringBefore('#')
                    val json = JSONObject(String(Base64.getDecoder().decode(padBase64(payload)), Charsets.UTF_8))
                    json.optString("add") to json.optString("port").toIntOrNull()
                }
                "ss" -> parseShadowsocksEndpoint(line.substringAfter("ss://"))
                else -> {
                    val uri = URI(line)
                    val host = uri.host ?: run {
                        val authority = line.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
                        authority.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                    }
                    val port = if (uri.port > 0) uri.port else {
                        val authority = line.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
                        authority.substringAfterLast(':').toIntOrNull() ?: -1
                    }
                    host to port
                }
            }
        }.getOrNull() ?: return null
        val host = endpoint.first.trim().removePrefix("[").removeSuffix("]")
        val port = endpoint.second ?: -1
        if (host.isBlank() || port !in 1..65535 || host.length > 253) return null
        val title = runCatching { line.substringAfter('#', "").substringBefore('?').let { java.net.URLDecoder.decode(it, "UTF-8") } }.getOrDefault("")
        val countryInfo = parseCountryFromTitle(title)
        return ProxyItem(
            id = stableId(line), host = host, port = port, protocol = protocol,
            source = "Сеть · " + sourceName, rawConfig = line, title = title.take(48),
            checkStatus = "Не проверен", country = countryInfo.first, countryCode = countryInfo.second
        )
    }

    private fun parseCountryFromTitle(title: String): Pair<String, String> {
        val points = title.codePoints().toArray()
        for (i in 0 until (points.size - 1).coerceAtLeast(0)) {
            val first = points[i]
            val second = points[i + 1]
            if (first in 0x1F1E6..0x1F1FF && second in 0x1F1E6..0x1F1FF) {
                val code = ('A'.code + first - 0x1F1E6).toChar().toString() + ('A'.code + second - 0x1F1E6).toChar()
                if (code in Locale.getISOCountries()) return countryName(code) to code
            }
        }
        val bracketCode = Regex("\\[([A-Za-z]{2})\\]").find(title)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
        if (bracketCode != null && bracketCode in Locale.getISOCountries()) {
            return countryName(bracketCode) to bracketCode
        }
        return "" to ""
    }

    private fun countryName(code: String): String {
        val name = Locale("", code).getDisplayCountry(Locale("ru"))
        return name.takeIf { it.isNotBlank() } ?: code
    }

    private fun parseShadowsocksEndpoint(payload: String): Pair<String, Int>? {
        val decodedPart = if (!payload.substringBefore('@').contains(':') && !payload.contains('@')) {
            runCatching { String(Base64.getDecoder().decode(padBase64(payload.substringBefore('#'))), Charsets.UTF_8) }.getOrNull() ?: payload
        } else payload
        val authority = decodedPart.substringAfterLast('@').substringBefore('?').substringBefore('#')
        val hostPort = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(1, close) to authority.substring(close + 2).toIntOrNull()
        } else authority.substringBeforeLast(':') to authority.substringAfterLast(':').toIntOrNull()
        return hostPort.first to (hostPort.second ?: return null)
    }

    private fun padBase64(value: String): String {
        val normalized = value.replace('-', '+').replace('_', '/')
        return normalized + "=".repeat((4 - normalized.length % 4) % 4)
    }

    private fun stableId(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
}


/** Validates a deliberately bounded IPv4 range for opt-in, authorized diagnostics. */
internal object Ipv4RangeParser {
    fun parse(input: String): List<String>? {
        val parts = input.trim().split('/')
        if (parts.size !in 1..2) return null
        val base = ipv4ToLong(parts[0]) ?: return null
        val prefix = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 32
        if (prefix !in 24..32) return null
        val hostBits = 32 - prefix
        val count = 1 shl hostBits
        val mask = (0xFFFF_FFFFL shl hostBits) and 0xFFFF_FFFFL
        val network = base and mask
        return (0 until count).map { offset -> longToIpv4(network + offset) }
    }

    private fun ipv4ToLong(value: String): Long? {
        val octets = value.split('.')
        if (octets.size != 4) return null
        val nums = octets.map { it.toIntOrNull() ?: return null }
        if (nums.any { it !in 0..255 }) return null
        return nums.fold(0L) { acc, n -> (acc shl 8) or n.toLong() }
    }

    private fun longToIpv4(value: Long): String = listOf(24, 16, 8, 0)
        .joinToString(".") { shift -> ((value shr shift) and 255).toString() }
}

data class FetchResult(val items: List<ProxyItem>, val sourceCounts: Map<String, Int>)
data class OpenEndpoint(val host: String, val port: Int, val latencyMs: Int, val protocol: String = "TCP", val status: String = "Открытый TCP-порт · прокси не подтверждён") {
    /** Port-number hint only; it is not protocol detection. */
    val portHint: String get() = when (port) {
        1080 -> "Часто используется для SOCKS-прокси · не подтверждено"
        3128 -> "Часто используется для HTTP-прокси · не подтверждено"
        4145 -> "Встречается у SOCKS-прокси · не подтверждено"
        8080, 8081, 8888 -> "HTTP / веб-сервис · назначение не подтверждено"
        8118 -> "Часто используется для HTTP-прокси · не подтверждено"
        8388 -> "Часто используется Shadowsocks · не подтверждено"
        443, 2053, 2083, 2087, 2096, 8443 -> "TLS/HTTPS или прокси-транспорт · не подтверждено"
        9050, 9150 -> "Часто используется SOCKS для Tor · не подтверждено"
        else -> "Открытый TCP-порт · протокол неизвестен"
    }
}
data class EndpointScanResult(val endpoints: List<OpenEndpoint>, val message: String)

data class ProxyItem(
    val id: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val source: String,
    val rawConfig: String,
    val checkStatus: String = "Не проверен",
    val latencyMs: Int? = null,
    val favorite: Boolean = false,
    val checkedAt: Long? = null,
    val title: String = "",
    val country: String = "",
    val countryCode: String = ""
) {
    /** True only when the endpoint passed the protocol-aware probe or its TCP port answered. */
    val isTcpReachable: Boolean get() =
        checkStatus.startsWith("TCP доступен") || isTunnelVerified

    /** A real HTTP 204 round-trip through HTTP/SOCKS; raw TCP checks do not qualify. */
    val isTunnelVerified: Boolean get() = checkStatus.startsWith("Проверен через generate_204")

    val isChecked: Boolean get() = checkedAt != null

    /** Quality is meaningful only when the proxy actually completed the generate_204 request. */
    val qualityScore: Int get() {
        if (!isTunnelVerified || latencyMs == null) return 0
        return when {
            latencyMs < 100 -> 90
            latencyMs < 250 -> 80
            latencyMs < 500 -> 65
            latencyMs < 900 -> 45
            else -> 25
        }
    }
}
