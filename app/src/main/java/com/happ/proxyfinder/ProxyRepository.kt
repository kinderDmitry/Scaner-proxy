package com.happ.proxyfinder

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import android.util.Base64
import java.util.Locale

/** Fetches public configuration feeds. Feed contents are untrusted input and are never executed. */
class ProxyRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("proxy_finder", Context.MODE_PRIVATE)
    private val store = ProxyStore(context)


    // Verified public feeds. Large all.txt bundles are intentionally avoided on mobile;
    // protocol-specific and quality-ranked bundles provide a better signal-to-bandwidth ratio.
    private val sources = listOf(
        Source("Morpheus · Best", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt", protocol = "mixed"),
        Source("Morpheus · Lite", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/lite.txt", protocol = "mixed"),
        Source("Morpheus · Mini", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/mini.txt", protocol = "mixed"),
        Source("Morpheus · Trojan", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/trojan.txt", protocol = "trojan"),
        Source("Morpheus · Hysteria2", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/hysteria2.txt", protocol = "hysteria2"),
        Source("Morpheus · TUIC", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/tuic.txt", protocol = "tuic"),
        Source("3inker · Проверенные RU", "https://raw.githubusercontent.com/3inker/v2ray-subscription/main/subs/all_ru.txt"),
        Source("3inker · Проверенные международные", "https://raw.githubusercontent.com/3inker/v2ray-subscription/main/subs/all_not_ru.txt"),
        Source("3inker · AetrisVPN blacklist feed", "https://raw.githubusercontent.com/flaafix/AetrisVPN-black-list/refs/heads/main/configs.txt"),
        Source("3inker · AetrisVPN whitelist feed", "https://raw.githubusercontent.com/flaafix/AetrisVPN-white-list-lite/refs/heads/main/AetrisVPN.txt"),
        Source("3inker · FlareFeed", "https://gitverse.ru/api/repos/Nokls/FlareFeed/raw/branch/main/public/fastest.txt"),
        Source("3inker · VlessForU", "https://sub.vlessfo.ru/vlessforu/working_configs.txt"),
        Source("OpenProxyList · V2Ray", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/V2RAY_RAW.txt"),
        Source("Pawdroid · Free servers", "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub"),
        Source("Multi Proxy Config Fetcher · Public channels", "https://raw.githubusercontent.com/4n0nymou3/multi-proxy-config-fetcher/main/configs/proxy_configs.txt"),
        Source("Delta-Kronecker · TCP pass 013", "https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/refs/heads/main/config/tcp-pass/batch_013.txt"),
        Source("Delta-Kronecker · TCP pass 014", "https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/refs/heads/main/config/tcp-pass/batch_014.txt"),
        Source("Coldwater · Sub7", "https://raw.githubusercontent.com/coldwater-10/V2ray-Config/main/Sub7.txt"),
        Source("Sevcator · Mini", "https://raw.githubusercontent.com/sevcator/5ubscrpt10n/main/mini/m1n1-5ub-69.txt"),
        Source("SylphNet · Aggregated", "https://raw.githubusercontent.com/ProblemTheCode/SylphNet-public/main/sub/sub_plain.txt"),
        Source("TheSpeedX · SOCKS5", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt", "socks5"),
        Source("TheSpeedX · SOCKS4", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks4.txt", "socks4"),
        Source("TheSpeedX · HTTP", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt", "http"),
        Source("TheSpeedX · HTTPS", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/https.txt", "https"),
        Source("monosans · SOCKS5", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt", "socks5"),
        Source("monosans · SOCKS4", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks4.txt", "socks4"),
        Source("monosans · HTTP", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt", "http"),
        Source("monosans · HTTPS", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/https.txt", "https")
    )

    suspend fun fetch(protocolFilter: String = "Все типы", onSource: suspend (String) -> Unit): FetchResult = withContext(Dispatchers.IO) {
        val filter = protocolFilter.uppercase(Locale.ROOT)
        val selectedSources = when (filter) {
            "SOCKS5" -> sources.filter { it.protocol.equals("socks5", true) }
            "SOCKS4" -> sources.filter { it.protocol.equals("socks4", true) }
            "HTTP" -> sources.filter { it.protocol.equals("http", true) }
            "HTTPS" -> sources.filter { it.protocol.equals("https", true) }
            "HYSTERIA2" -> sources.filter { it.protocol.equals("hysteria2", true) || it.protocol == "mixed" }
            "TUIC" -> sources.filter { it.protocol.equals("tuic", true) || it.protocol == "mixed" }
            "TROJAN" -> sources.filter { it.protocol.equals("trojan", true) || it.protocol == "mixed" || it.name.contains("3inker", true) }
            "VLESS" -> sources.filter { it.protocol.equals("vless", true) || it.protocol == "mixed" || it.name.contains("Vless", true) || it.name.contains("3inker", true) }
            "VMESS" -> sources.filter { it.protocol.equals("vmess", true) || it.protocol == "mixed" || it.name.contains("3inker", true) || it.name.contains("Pawdroid", true) || it.name.contains("SylphNet", true) }
            "SHADOWSOCKS" -> sources.filter { it.protocol == "mixed" || it.name.contains("3inker", true) || it.name.contains("Pawdroid", true) }
            else -> sources
        }
        val gate = Semaphore(if (protocolFilter == "Все типы") 4 else 3)
        val results = coroutineScope {
            selectedSources.map { source -> async {
                gate.withPermit {
                    onSource(source.name)
                    runCatching { SourceFetchResult(source.name, fetchSource(source), null) }
                        .getOrElse { error ->
                            SourceFetchResult(source.name, emptyList(), error.message?.take(100) ?: error.javaClass.simpleName)
                        }
                }
            } }.awaitAll()
        }
        val sourceCounts = results.associate { it.name to it.items.size }
        val sourceErrors = results.filter { it.error != null }.associate { it.name to it.error!! }
        val merged = linkedMapOf<String, ProxyItem>()
        results.flatMap { it.items }.asSequence()
            .filter { protocolFilter == "Все типы" || it.protocol.equals(protocolFilter, ignoreCase = true) }
            .forEach { item ->
            val key = item.rawConfig.trim()
            if (key.isNotEmpty() && !merged.containsKey(key)) merged[key] = item
        }
        val favorites = loadFavorites()
        FetchResult(merged.values.map { it.copy(favorite = favorites.contains(it.id)) }, sourceCounts, sourceErrors)
    }

    private fun fetchSource(source: Source): List<ProxyItem> {
        val connection = URL(source.url).openConnection() as? java.net.HttpURLConnection
            ?: throw IllegalStateException("Источник не использует HTTP")
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "PROXY-HUNTER-X/2.0 (Android)")
            connection.setRequestProperty("Accept", "text/plain,application/octet-stream,*/*")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("HTTP $status")
            val text = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SOURCE_BYTES) throw java.io.IOException("Превышен лимит источника")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            if (text.isBlank()) throw java.io.IOException("Пустой ответ")
            val decoded = decodeSubscriptionIfNeeded(text)
            val seen = HashSet<String>()
            return decoded.lineSequence().mapNotNull { raw ->
                val line = raw.trim().substringBefore(" | ").trim()
                if (line.isBlank()) null else {
                    val candidate = if (source.protocol != null && !line.contains("://")) "${source.protocol}://$line" else line
                    ConfigParser.parseConfig(candidate, source.name)
                }
            }.filter { seen.add(it.rawConfig) }.toList()
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeSubscriptionIfNeeded(text: String): String {
        val schemePattern = Regex("(?im)^\\s*(vless|vmess|trojan|ss|socks|socks4|socks5|http|https|hysteria2|hy2|tuic)://")
        if (schemePattern.containsMatchIn(text)) return text
        val compact = text.trim().replace("\\s".toRegex(), "")
        if (compact.length < 16 || !compact.matches(Regex("[A-Za-z0-9+/=_-]+"))) return text
        return runCatching {
            val padded = compact.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                .takeIf { schemePattern.containsMatchIn(it) } ?: text
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
        val subnetAddress = listOf(24, 16, 8, 0)
            .joinToString(".") { shift -> ((networkStart shr shift) and 255).toString() }
        // Common proxy ports across Clash, sing-box, Squid, Privoxy, SOCKS and custom local gateways.
        // A reachable TCP port is only a candidate; protocol handshakes below decide whether it is a proxy.
        val ports = listOf(
            1080, 1081, 1085, 10808, 10809, 10880,
            3128, 3129, 3130, 4145, 4444,
            5000, 5555, 6666, 7000, 7890, 7891, 7892, 7893,
            8000, 8001, 8008, 8080, 8081, 8088, 8090, 8118, 8888, 8889,
            9000, 9050, 9080, 9090, 9150, 10080, 10888, 1180, 20170, 4145, 8388
        ).distinct()
        val tasks = addresses.flatMap { ip -> ports.map { port -> ip to port } }
        onProgress("Wi-Fi $subnetAddress/$prefix · ${addresses.size} устройств × ${ports.size} портов")
        val found = java.util.concurrent.ConcurrentLinkedQueue<OpenEndpoint>()
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = Semaphore(72)
        coroutineScope {
            tasks.map { (ip, port) -> async {
                gate.withPermit {
                    probeLocalEndpoint(ip, port, wifiNetwork)?.let(found::add)
                    val count = completed.incrementAndGet()
                    if (count % 100 == 0 || count == tasks.size) {
                        onProgress("Локальная сеть: проверено $count/${tasks.size} адресов и портов · найдено ${found.size}")
                    }
                }
            } }.awaitAll()
        }
        val sorted = found.sortedWith(compareBy<OpenEndpoint> { it.protocol == "TCP" }.thenBy { it.latencyMs }.thenBy { it.host }.thenBy { it.port })
        val summary = if (sorted.isEmpty()) {
            "Wi-Fi $subnetAddress/$prefix · проверено ${tasks.size} комбинаций. На устройствах локальной сети открытые прокси-порты не обнаружены. Это не поиск прокси в интернете: если нужен список внешних узлов, переключи режим на открытые источники."
        } else {
            "Wi-Fi $subnetAddress/$prefix · проверено ${tasks.size} комбинаций · найдено ${sorted.size} открытых портов. Только SOCKS5/HTTP CONNECT подтверждают тип прокси; TCP сам по себе не подтверждает прокси."
        }
        EndpointScanResult(sorted, summary)
    }

    private fun probeLocalEndpoint(host: String, port: Int, network: android.net.Network): OpenEndpoint? {
        val started = System.nanoTime()
        // Bind probes to Wi-Fi so a VPN's default route cannot swallow LAN traffic.
        val socket = network.socketFactory.createSocket()
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
            // 0xFF means the server rejected every offered authentication method, not a usable SOCKS5 endpoint.
            if (version == 5 && method in 0..254) {
                return OpenEndpoint(host, port, tcpMs, "SOCKS5", if (method == 0) "SOCKS5 обнаружен · без авторизации" else "SOCKS5 обнаружен · может требоваться авторизация")
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
        val httpStatus = runCatching {
            network.socketFactory.createSocket().use { http ->
                http.connect(java.net.InetSocketAddress(host, port), 450)
                http.soTimeout = 350
                http.getOutputStream().write("CONNECT www.gstatic.com:443 HTTP/1.1\r\nHost: www.gstatic.com:443\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                http.getOutputStream().flush()
                val reader = http.getInputStream().bufferedReader(Charsets.US_ASCII)
                val statusLine = reader.readLine().orEmpty()
                Regex("^HTTP/1\\.[01] ([1-5][0-9]{2})(?:\\s.*)?$").find(statusLine)?.groupValues?.get(1)
            }
        }.getOrNull()
        return when (httpStatus) {
            "200" -> OpenEndpoint(host, port, tcpMs, "HTTP", "HTTP CONNECT работает")
            "407" -> OpenEndpoint(host, port, tcpMs, "HTTP", "HTTP-прокси обнаружен · требуется авторизация")
            null -> OpenEndpoint(host, port, tcpMs, "TCP", "Открытый TCP-порт · прокси не подтверждён")
            else -> OpenEndpoint(host, port, tcpMs, "TCP", "Открытый TCP-порт · HTTP CONNECT не подтверждён (HTTP $httpStatus)")
        }
    }

    /** End-to-end protocol probe. TCP reachability is never treated as a working tunnel. */
    suspend fun check(item: ProxyItem): ProxyItem = withContext(Dispatchers.IO) {
        val checkedAt = System.currentTimeMillis()
        if (item.port !in 1..65535) return@withContext item.copy(checkStatus = "Неверный порт", latencyMs = null, checkedAt = checkedAt)
        val started = System.nanoTime()
        try {
            val status = probeHttp204(item)
            val elapsed = ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
            if (status == 204) item.copy(checkStatus = "Проверен через generate_204 · HTTP 204", latencyMs = elapsed, checkedAt = checkedAt)
            else item.copy(checkStatus = "Прокси ответил HTTP $status · проверка не пройдена", latencyMs = null, checkedAt = checkedAt)
        } catch (error: Exception) {
            val tcp = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress(item.host, item.port), 1800) }
                true
            }.getOrDefault(false)
            val reason = error.message?.take(90)?.ifBlank { null } ?: error.javaClass.simpleName
            if (tcp) item.copy(checkStatus = "TCP доступен · туннель не подтверждён: $reason", latencyMs = null, checkedAt = checkedAt)
            else item.copy(checkStatus = "Проверка не пройдена: $reason", latencyMs = null, checkedAt = checkedAt)
        }
    }

    private fun probeHttp204(item: ProxyItem): Int {
        val targetHost = "www.gstatic.com"
        val targetPort = 443
        val credentials = runCatching { URI(item.rawConfig).rawUserInfo }.getOrNull()
        val user = credentials?.substringBefore(':')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val pass = credentials?.substringAfter(':', "")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = 5500
        socket.connect(InetSocketAddress(item.host, item.port), 4500)
        var tunnel: java.net.Socket = socket
        try {
            when (item.protocol.uppercase(Locale.ROOT)) {
                "SOCKS5" -> {
                    val out = socket.getOutputStream()
                    val input = socket.getInputStream()
                    out.write(if (user != null) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); out.flush()
                    if (input.read() != 5) throw java.io.IOException("Некорректный SOCKS5 handshake")
                    when (input.read()) {
                        0 -> Unit
                        2 -> {
                            if (user == null) throw java.io.IOException("SOCKS5 требует логин и пароль")
                            val u = user.toByteArray(Charsets.UTF_8); val p = (pass ?: "").toByteArray(Charsets.UTF_8)
                            if (u.size > 255 || p.size > 255) throw java.io.IOException("Слишком длинные данные авторизации")
                            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
                            if (input.read() != 1 || input.read() != 0) throw java.io.IOException("SOCKS5: ошибка авторизации")
                        }
                        255 -> throw java.io.IOException("SOCKS5 отклонил методы авторизации")
                        else -> throw java.io.IOException("SOCKS5 выбрал неизвестный метод")
                    }
                    val host = targetHost.toByteArray(Charsets.US_ASCII)
                    out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte())); out.flush()
                    if (input.read() != 5) throw java.io.IOException("SOCKS5 CONNECT не принят")
                    val reply = input.read()
                    if (reply != 0) throw java.io.IOException("SOCKS5 CONNECT отказал: $reply")
                    if (input.read() < 0) throw java.io.IOException("Оборван SOCKS5 ответ")
                    when (input.read()) {
                        1 -> readExact(input, 4)
                        3 -> { val n = input.read(); if (n < 0) throw java.io.IOException("Оборван SOCKS5 адрес"); readExact(input, n) }
                        4 -> readExact(input, 16)
                        else -> throw java.io.IOException("Неизвестный SOCKS5 адрес")
                    }
                    if (input.read() < 0 || input.read() < 0) throw java.io.IOException("Оборван SOCKS5 порт")
                }
                "SOCKS4" -> {
                    val out = socket.getOutputStream(); val input = socket.getInputStream()
                    val host = targetHost.toByteArray(Charsets.US_ASCII)
                    out.write(byteArrayOf(4, 1, (targetPort shr 8).toByte(), targetPort.toByte(), 0, 0, 0, 1, 0) + host + byteArrayOf(0)); out.flush()
                    val response = readExact(input, 8)
                    if (response.size != 8 || (response[1].toInt() and 0xff) != 90) throw java.io.IOException("SOCKS4a CONNECT отказал")
                }
                "HTTP", "HTTPS" -> {
                    if (item.protocol.equals("HTTPS", true)) {
                        val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(socket, item.host, item.port, true) as javax.net.ssl.SSLSocket
                        ssl.soTimeout = 5500
                        val proxyTlsParameters = ssl.sslParameters
                        proxyTlsParameters.endpointIdentificationAlgorithm = "HTTPS"
                        ssl.sslParameters = proxyTlsParameters
                        ssl.startHandshake()
                        tunnel = ssl
                    }
                    val auth = if (user != null) {
                        val raw = "$user:${pass ?: ""}".toByteArray(Charsets.UTF_8)
                        "Proxy-Authorization: Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP) + "\r\n"
                    } else ""
                    val request = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n${auth}Proxy-Connection: keep-alive\r\n\r\n"
                    tunnel.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); tunnel.getOutputStream().flush()
                    val headers = readHttpHeaders(tunnel.getInputStream())
                    val status = Regex("^HTTP/1\\.[01] ([0-9]{3})").find(headers.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                        ?: throw java.io.IOException("Некорректный ответ HTTP proxy")
                    if (status != 200) throw java.io.IOException("HTTP CONNECT отказал: $status")
                }
                else -> throw java.io.IOException("Для ${item.protocol} требуется встроенное sing-box ядро")
            }
            val tls = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(tunnel, targetHost, targetPort, true) as javax.net.ssl.SSLSocket
            tls.soTimeout = 5500
            val parameters = tls.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = parameters
            tls.startHandshake()
            tls.getOutputStream().write("GET /generate_204 HTTP/1.1\r\nHost: $targetHost\r\nConnection: close\r\nCache-Control: no-cache\r\nUser-Agent: HAPP-Proxy-Finder/2.0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            tls.getOutputStream().flush()
            val statusLine = tls.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
            tls.close()
            return Regex("^HTTP/1\\.[01] 204(?:\\s|$)").containsMatchIn(statusLine).let { if (it) 204 else Regex("^HTTP/1\\.[01] ([0-9]{3})").find(statusLine)?.groupValues?.get(1)?.toIntOrNull() ?: throw java.io.IOException("Некорректный ответ целевого сервера") }
        } finally {
            runCatching { tunnel.close() }
            if (tunnel !== socket) runCatching { socket.close() }
        }
    }

    private fun readExact(input: java.io.InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw java.io.IOException("Прокси оборвал handshake")
            offset += read
        }
        return result
    }

    private fun readHttpHeaders(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        var matched = 0
        while (out.size() < 16_384) {
            val value = input.read()
            if (value < 0) break
            out.write(value)
            matched = when {
                matched == 0 && value == 13 -> 1
                matched == 1 && value == 10 -> 2
                matched == 2 && value == 13 -> 3
                matched == 3 && value == 10 -> 4
                value == 13 -> 1
                else -> 0
            }
            if (matched == 4) return out.toString(Charsets.US_ASCII.name())
        }
        throw java.io.IOException("HTTP proxy не вернул полные заголовки")
    }

    suspend fun checkBatch(items: List<ProxyItem>, onResult: suspend (ProxyItem) -> Unit): List<ProxyItem> = coroutineScope {
        val gate = Semaphore(8)
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
        val candidates = items.filter {
            it.country.isBlank() && (isIpv4(it.host) || isResolvableHostname(it.host)) && !cached.containsKey(it.host)
        }.distinctBy { it.host }.take(MAX_GEO_LOOKUPS)
        val updated = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        val gate = Semaphore(8)
        coroutineScope {
            candidates.map { item -> async {
                gate.withPermit {
                    val foundCountry = runCatching {
                        val url = URL("https://ipwho.is/${item.host}?fields=success,country,country_code")
                        val conn = url.openConnection().apply {
                            connectTimeout = 1800; readTimeout = 1800
                            setRequestProperty("User-Agent", "HAPP-Proxy-Finder/1.2")
                        }
                        val json = JSONObject(conn.getInputStream().bufferedReader().use { it.readText().take(16_384) })
                        if (json.optBoolean("success")) {
                            val country = json.optString("country").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                            val code = json.optString("country_code").takeIf { it.isNotBlank() && it != "null" }
                                ?.uppercase(Locale.ROOT).orEmpty()
                            if (country.isNotBlank()) country to code else null
                        } else null
                    }.getOrNull() ?: runCatching {
                        // Secondary provider helps when the primary geo API rate-limits or rejects a host.
                        val conn = URL("https://ipapi.co/${item.host}/json/").openConnection().apply {
                            connectTimeout = 2800; readTimeout = 2800
                            setRequestProperty("User-Agent", "HAPP-Proxy-Finder/1.2")
                        }
                        val json = JSONObject(conn.getInputStream().bufferedReader().use { it.readText().take(16_384) })
                        val country = json.optString("country_name").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                        val code = json.optString("country_code").takeIf { it.isNotBlank() && it != "null" }
                            ?.uppercase(Locale.ROOT).orEmpty()
                        if (country.isNotBlank()) country to code else null
                    }.getOrNull()
                    if (foundCountry != null) {
                        updated[item.host] = foundCountry
                        onResolved(item.copy(country = foundCountry.first, countryCode = foundCountry.second))
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
        items.zip(resolved).forEach { (original, resolvedItem) ->
            if (original.country.isBlank() && resolvedItem.country.isNotBlank()) onResolved(resolvedItem)
        }
        resolved
    }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        if (octets[0] == 0 || octets[0] == 10 || octets[0] == 127 || octets[0] >= 224) return false
        if (octets[0] == 169 && octets[1] == 254) return false
        if (octets[0] == 172 && octets[1] in 16..31) return false
        if (octets[0] == 192 && octets[1] == 168) return false
        if (octets[0] == 100 && octets[1] in 64..127) return false
        return true
    }

    private fun isResolvableHostname(host: String): Boolean {
        if (host.length !in 4..253 || !host.contains('.') || !host.any { it.isLetter() }) return false
        if (host.endsWith(".local", ignoreCase = true)) return false
        return host.split('.').all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    fun loadSubscriptionWorkerUrl(): String = prefs.getString("subscription_worker_url", "") ?: ""
    fun loadSubscriptionUrl(): String = prefs.getString("subscription_url", "") ?: ""

    fun saveSubscriptionSettings(workerUrl: String, token: String) {
        prefs.edit().putString("subscription_worker_url", workerUrl.trim().trimEnd('/')).apply()
        if (token.isNotBlank()) {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, subscriptionKey())
            val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString("subscription_token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("subscription_token_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
        }
    }

    fun loadSubscriptionToken(): String {
        val ivText = prefs.getString("subscription_token_iv", null) ?: return ""
        val dataText = prefs.getString("subscription_token_data", null) ?: return ""
        return runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val data = Base64.decode(dataText, Base64.NO_WRAP)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, subscriptionKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun subscriptionKey(): java.security.Key {
        val alias = "happ_proxy_subscription_token_v1"
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? java.security.Key)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    suspend fun publishSubscription(workerUrl: String, token: String, items: List<ProxyItem>): String = withContext(Dispatchers.IO) {
        val base = workerUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "Укажи HTTPS-адрес Cloudflare Worker" }
        require(token.isNotBlank()) { "Не указан токен публикации" }
        val configs = items.asSequence().filter { it.isExportReady }.map { it.rawConfig.trim() }
            .filter { it.isNotBlank() }.distinct().take(1500).toList()
        require(configs.isNotEmpty()) { "Нет подтверждённых конфигураций для публикации" }
        val id = prefs.getString("subscription_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("subscription_id", it).apply()
        }
        val connection = URL("$base/api/subscriptions/$id").openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("configs", JSONArray(configs)).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(32768) }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("Сервер подписки вернул HTTP $code: $response")
            val published = JSONObject(response).optString("url")
            require(published.startsWith("https://")) { "Сервер не вернул корректную ссылку подписки" }
            prefs.edit().putString("subscription_url", published).apply()
            published
        } finally {
            connection.disconnect()
        }
    }

    fun loadFavorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun saveFavorites(ids: Set<String>) { prefs.edit().putStringSet("favorites", ids).apply() }

    /** Upserts records into SQLite; scanning another protocol never deletes previous results. */
    fun saveSnapshot(items: List<ProxyItem>) {
        store.upsert(items)
    }

    fun loadSnapshot(limit: Int = 1000): List<ProxyItem> {
        migrateLegacySnapshot()
        return store.readLatest(limit)
    }

    /** Full verified-only export query; unverified rows never enter the export flow. */
    fun loadVerifiedSnapshot(): List<ProxyItem> {
        migrateLegacySnapshot()
        return store.readVerified()
    }

    private fun migrateLegacySnapshot() {
        if (store.count() > 0) return
        val legacy = runCatching {
            val arr = JSONArray(prefs.getString("snapshot", "[]"))
            (0 until arr.length()).mapNotNull { i ->
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
        if (legacy.isNotEmpty()) store.upsert(legacy)
        prefs.edit().remove("snapshot").apply()
    }

    private data class Source(val name: String, val url: String, val protocol: String? = null)

    companion object {
        private const val SPEED_TEST_URL = "https://www.gstatic.com/generate_204"
        private const val MAX_SOURCE_BYTES = 8_000_000
        private const val MAX_GEO_LOOKUPS = 120
    }
}



/** Unbounded-by-arbitrary-row-count local result store. SQLite persists incrementally and deduplicates by raw config. */
private class ProxyStore(context: Context) : SQLiteOpenHelper(context, "proxy_results.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE proxy_results (
                id TEXT PRIMARY KEY NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                protocol TEXT NOT NULL,
                source TEXT NOT NULL,
                raw_config TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL,
                latency INTEGER,
                favorite INTEGER NOT NULL,
                checked_at INTEGER,
                title TEXT NOT NULL,
                country TEXT NOT NULL,
                country_code TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_proxy_protocol ON proxy_results(protocol)")
        db.execSQL("CREATE INDEX idx_proxy_host ON proxy_results(host)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM proxy_results", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun upsert(items: List<ProxyItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("id", item.id); put("host", item.host); put("port", item.port); put("protocol", item.protocol)
                    put("source", item.source); put("raw_config", item.rawConfig); put("status", item.checkStatus)
                    if (item.latencyMs == null) putNull("latency") else put("latency", item.latencyMs)
                    put("favorite", if (item.favorite) 1 else 0)
                    if (item.checkedAt == null) putNull("checked_at") else put("checked_at", item.checkedAt)
                    put("title", item.title); put("country", item.country); put("country_code", item.countryCode)
                }
                db.insertWithOnConflict("proxy_results", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readQuery(sql: String, args: Array<String>? = null): List<ProxyItem> = readableDatabase.rawQuery(sql, args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)) ?: ""
                fun nullableLong(name: String): Long? {
                    val index = cursor.getColumnIndexOrThrow(name)
                    return if (cursor.isNull(index)) null else cursor.getLong(index)
                }
                val latency = nullableLong("latency")?.toInt()
                add(ProxyItem(
                    id = string("id"), host = string("host"), port = cursor.getInt(cursor.getColumnIndexOrThrow("port")),
                    protocol = string("protocol"), source = string("source"), rawConfig = string("raw_config"),
                    checkStatus = string("status"), latencyMs = latency,
                    favorite = cursor.getInt(cursor.getColumnIndexOrThrow("favorite")) != 0,
                    checkedAt = nullableLong("checked_at"), title = string("title"),
                    country = string("country"), countryCode = string("country_code")
                ))
            }
        }
    }

    fun readLatest(limit: Int): List<ProxyItem> = readQuery(
        "SELECT id,host,port,protocol,source,raw_config,status,latency,favorite,checked_at,title,country,country_code FROM proxy_results ORDER BY rowid DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 5000).toString())
    )

    fun readVerified(): List<ProxyItem> = readQuery(
        "SELECT id,host,port,protocol,source,raw_config,status,latency,favorite,checked_at,title,country,country_code FROM proxy_results WHERE status LIKE 'Проверен через generate_204%' ORDER BY latency ASC, rowid DESC"
    )
}

internal object ConfigParser {
    fun parseConfig(rawLine: String, sourceName: String = "Источник"): ProxyItem? {
        val line = rawLine.trim().removePrefix("\uFEFF")
        if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) return null
        val match = Regex("^(vless|vmess|trojan|ss|socks|socks4|socks5|http|https|hysteria2|hy2|tuic)://", RegexOption.IGNORE_CASE).find(line) ?: return null
        val scheme = match.groupValues[1].lowercase(Locale.ROOT)
        val protocol = when (scheme) {
            "hy2", "hysteria2" -> "Hysteria2"
            "tuic" -> "TUIC"
            "ss" -> "Shadowsocks"
            "socks", "socks5" -> "SOCKS5"
            "socks4" -> "SOCKS4"
            "http" -> "HTTP"
            "https" -> "HTTPS"
            else -> scheme.uppercase(Locale.ROOT)
        }
        val endpoint = runCatching {
            when (scheme) {
                "vmess" -> {
                    val payload = line.substringAfter("vmess://").substringBefore('#')
                    val json = JSONObject(String(Base64.decode(padBase64(payload), Base64.DEFAULT), Charsets.UTF_8))
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
                if (code in isoCodes) return countryName(code) to code
            }
        }

        bracketCountryRegex.find(title)?.let { match ->
            val code = (match.groupValues[1].ifBlank { match.groupValues[2] }).uppercase(Locale.ROOT)
            if (code in isoCodes) return countryName(code) to code
        }

        val normalized = normalizeCountryText(title)
        val paddedTitle = " $normalized "
        for ((name, code) in countryNameEntries) {
            if (paddedTitle.contains(" $name ")) return countryName(code) to code
        }

        // Feeds commonly use codes like US-NYC, DE | Frankfurt or lowercase us-nyc.
        bareCountryCodeRegex.findAll(title).forEach { match ->
            val code = match.groupValues[1].uppercase(Locale.ROOT)
            if (code in isoCodes) return countryName(code) to code
        }
        return "" to ""
    }

    private fun normalizeCountryText(value: String): String = value.lowercase(Locale.ROOT)
        .replace('_', ' ').replace('-', ' ')
        .replace(countryPunctuationRegex, " ")
        .replace(countryWhitespaceRegex, " ").trim()

    private val isoCodes = Locale.getISOCountries().toSet()
    private val bracketCountryRegex = Regex("\\[([A-Za-z]{2})\\]|\\(([A-Za-z]{2})\\)")
    private val bareCountryCodeRegex = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val countryPunctuationRegex = Regex("[^\\p{L}\\p{N}\\s]")
    private val countryWhitespaceRegex = Regex("\\s+")
    private val countryAliases = mapOf(
        "usa" to "US", "u s a" to "US", "united states" to "US",
        "united states of america" to "US", "сша" to "US",
        "deutschland" to "DE", "germany" to "DE", "германия" to "DE",
        "россия" to "RU", "russia" to "RU", "russian federation" to "RU", "рф" to "RU",
        "singapore" to "SG", "сингапур" to "SG",
        "united kingdom" to "GB", "uk" to "GB", "great britain" to "GB", "britain" to "GB",
        "england" to "GB", "великобритания" to "GB", "англия" to "GB",
        "holland" to "NL", "netherlands" to "NL", "нидерланды" to "NL", "голландия" to "NL",
        "france" to "FR", "франция" to "FR", "japan" to "JP", "япония" to "JP",
        "canada" to "CA", "канада" to "CA", "finland" to "FI", "финляндия" to "FI",
        "sweden" to "SE", "швеция" to "SE", "switzerland" to "CH", "швейцария" to "CH",
        "hong kong" to "HK", "гонконг" to "HK", "taiwan" to "TW", "тайвань" to "TW",
        "poland" to "PL", "польша" to "PL", "turkey" to "TR", "türkiye" to "TR", "турция" to "TR",
        "india" to "IN", "индия" to "IN", "indonesia" to "ID", "индонезия" to "ID",
        "brazil" to "BR", "бразилия" to "BR", "australia" to "AU", "австралия" to "AU",
        "ukraine" to "UA", "украина" to "UA", "czechia" to "CZ", "czech republic" to "CZ", "чехия" to "CZ",
        "latvia" to "LV", "латвия" to "LV", "estonia" to "EE", "эстония" to "EE",
        "lithuania" to "LT", "литва" to "LT", "austria" to "AT", "австрия" to "AT",
        "romania" to "RO", "румыния" to "RO", "south korea" to "KR", "южная корея" to "KR",
        "israel" to "IL", "израиль" to "IL", "italy" to "IT", "италия" to "IT",
        "spain" to "ES", "испания" to "ES", "norway" to "NO", "норвегия" to "NO",
        "belgium" to "BE", "бельгия" to "BE", "ireland" to "IE", "ирландия" to "IE",
        "new zealand" to "NZ", "новая зеландия" to "NZ"
    )
    private val countryNameEntries: List<Pair<String, String>> by lazy {
        val localeNames = isoCodes.flatMap { code ->
            listOf(
                Locale("", code).getDisplayCountry(Locale.ENGLISH) to code,
                Locale("", code).getDisplayCountry(Locale("ru")) to code
            )
        }
        (localeNames + countryAliases.map { it.key to it.value })
            .mapNotNull { (name, code) ->
                val normalized = normalizeCountryText(name)
                if (normalized.length > 2) normalized to code else null
            }
            .distinctBy { it.first }
            .sortedByDescending { it.first.length }
    }

    private fun countryName(code: String): String {
        val name = Locale("", code).getDisplayCountry(Locale("ru"))
        return name.takeIf { it.isNotBlank() } ?: code
    }

    private fun parseShadowsocksEndpoint(payload: String): Pair<String, Int>? {
        val decodedPart = if (!payload.substringBefore('@').contains(':') && !payload.contains('@')) {
            runCatching { String(Base64.decode(padBase64(payload.substringBefore('#')), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: payload
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

data class SourceFetchResult(val name: String, val items: List<ProxyItem>, val error: String?)
data class FetchResult(val items: List<ProxyItem>, val sourceCounts: Map<String, Int>, val sourceErrors: Map<String, String> = emptyMap())
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
    val supportsNativeTunnelCheck: Boolean get() = protocol.uppercase(Locale.ROOT) in setOf("SOCKS4", "SOCKS5", "HTTP", "HTTPS")
    /** Retry unverified native-protocol endpoints after 10 minutes; TCP reachability is not completion. */
    val needsNativeTunnelCheck: Boolean get() = supportsNativeTunnelCheck && !isTunnelVerified &&
        (checkedAt == null || System.currentTimeMillis() - checkedAt >= 10 * 60 * 1000L)
    /** Only a successful end-to-end HTTP 204 proxy tunnel may be exported to HAPP. */
    val isExportReady: Boolean get() = isTunnelVerified && rawConfig.isNotBlank()

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
