package com.happ.proxyfinder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF070707)
private val Surface = Color(0xFF101010)
private val SurfaceRaised = Color(0xFF19130F)
private val NeonOrange = Color(0xFFFF7A18)
private val NeonOrangeDeep = Color(0xFFFF4D00)
private val OrangeSoft = Color(0xFFFFB36B)
private val Green = Color(0xFF52E6A6)
private val Amber = Color(0xFFFFC76A)
private val Red = Color(0xFFFF7189)
private val TextPrimary = Color(0xFFFFF7F0)
private val TextMuted = Color(0xFFAA9684)
private val Line = Color(0xFF3B281B)

private fun Modifier.neonPanel(shape: RoundedCornerShape = RoundedCornerShape(18.dp)): Modifier =
    this.clip(shape)
        .background(Brush.linearGradient(listOf(SurfaceRaised, Surface, Bg)))
        .border(BorderStroke(1.dp, Brush.linearGradient(listOf(NeonOrange.copy(alpha = 0.52f), Line, NeonOrange.copy(alpha = 0.12f)))), shape)

class MainActivity : ComponentActivity() {
    private lateinit var repository: ProxyRepository
    private var pendingJsonExport: String = "{}"
    private val jsonFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pendingJsonExport) } ?: error("Не удалось открыть файл")
            Toast.makeText(this, "JSON-файл сохранён", Toast.LENGTH_LONG).show()
        }.onFailure { Toast.makeText(this, "Ошибка сохранения: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 7, 7)
        window.navigationBarColor = android.graphics.Color.rgb(7, 7, 7)
        repository = ProxyRepository(this)
        setContent {
            val scheme = darkColorScheme(
                primary = NeonOrange, onPrimary = Color(0xFF1A0B02), secondary = OrangeSoft,
                background = Bg, onBackground = TextPrimary, surface = Surface,
                onSurface = TextPrimary, surfaceVariant = SurfaceRaised, onSurfaceVariant = TextMuted,
                outline = Line, error = Red
            )
            MaterialTheme(colorScheme = scheme, typography = Typography()) {
                Surface(color = Bg) {
                    ProxyFinderScreen(
                        repo = repository,
                        onShare = { item ->
                            val config = normalizeProxySchemeForHapp(item.rawConfig)
                            val isSocks4 = item.protocol.equals("SOCKS4", ignoreCase = true)
                            val choices = if (isSocks4) arrayOf("Другие приложения / поделиться", "Копировать конфигурацию")
                                else arrayOf("Добавить в HAPP", "Добавить в Hiddify", "Другие приложения / поделиться", "Копировать конфигурацию")
                            AlertDialog.Builder(this)
                                .setTitle(if (isSocks4) "SOCKS4 не поддерживается HAPP" else "Куда добавить конфигурацию?")
                                .setItems(choices) { _, selected ->
                                    val which = if (isSocks4) selected + 2 else selected
                                    fun shareChooser() {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, config)
                                            putExtra(Intent.EXTRA_SUBJECT, item.title)
                                        }
                                        startActivity(Intent.createChooser(send, "Выбрать приложение"))
                                    }
                                    when (which) {
                                        0 -> {
                                            val deepLink = Uri.Builder().scheme("happ").authority("add").appendPath(config).build()
                                            val intent = Intent(Intent.ACTION_VIEW, deepLink)
                                            if (intent.resolveActivity(packageManager) != null) startActivity(intent) else {
                                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SOCKS5 configuration", config))
                                                Toast.makeText(this, "HAPP не найден. Конфигурация скопирована.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        1 -> {
                                            val deepLink = Uri.Builder().scheme("hiddify").authority("import").appendPath(config).build()
                                            val intent = Intent(Intent.ACTION_VIEW, deepLink)
                                            if (intent.resolveActivity(packageManager) != null) startActivity(intent) else shareChooser()
                                        }
                                        2 -> shareChooser()
                                        3 -> {
                                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Proxy configuration", config))
                                            Toast.makeText(this, "Конфигурация скопирована", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        },
                        onCopy = { value ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HAPP configuration", normalizeProxySchemeForHapp(value)))
                            Toast.makeText(this, "Конфигурация скопирована", Toast.LENGTH_SHORT).show()
                        },
                        onBulkShare = { items ->
                            val unique = items.distinctBy { it.rawConfig.trim() }.filter { it.rawConfig.isNotBlank() }
                            if (unique.isNotEmpty()) {
                                val payload = "#subscription-ping-onopen-enabled: 1\n" + unique.filterNot { it.protocol.equals("SOCKS4", true) }.joinToString("\n") { normalizeProxySchemeForHapp(it.rawConfig) }
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, payload)
                                    putExtra(Intent.EXTRA_SUBJECT, "HAPP Proxy Finder · ${unique.size} конфигураций")
                                }
                                startActivity(Intent.createChooser(send, "Передать ${unique.size} прокси"))
                            } else Toast.makeText(this, "Нет конфигураций для передачи.", Toast.LENGTH_LONG).show()
                        },
                        onCopyAllJson = { items ->
                            val unique = items.distinctBy { it.rawConfig.trim() }.filter { it.rawConfig.isNotBlank() }
                            pendingJsonExport = org.json.JSONObject().put("format", "happ-proxy-finder").put("version", 1)
                                .put("exportedAt", System.currentTimeMillis())
                                .put("proxies", org.json.JSONArray().apply {
                                    unique.forEach { item -> put(org.json.JSONObject().apply {
                                        put("id", item.id); put("protocol", item.protocol); put("host", item.host); put("port", item.port)
                                        put("name", item.title); put("country", item.country); put("city", item.city)
                                        put("source", item.source); put("status", item.checkStatus); put("verified", item.isTunnelVerified)
                                        put("latencyMs", item.latencyMs ?: org.json.JSONObject.NULL); put("config", normalizeProxySchemeForHapp(item.rawConfig))
                                    }) }
                                }).toString(2)
                            jsonFileLauncher.launch("proxy-finder-${System.currentTimeMillis()}.json")
                        }
                    )
                }
            }
        }
    }
}

internal fun normalizeProxySchemeForHapp(config: String): String {
    val value = config.trim().removePrefix("\uFEFF")
    if (value.isEmpty()) return value
    // HAPP uses socks:// for SOCKS5. Preserve endpoint and authentication verbatim.
    return value.replace(Regex("^socks5://", RegexOption.IGNORE_CASE), "socks://")
}

/** Returns a clean, HAPP-compatible configuration string for single-item copy actions. */
internal fun namedConfigForImport(item: ProxyItem): String = normalizeProxySchemeForHapp(item.rawConfig)

@Composable
private fun ProxyFinderScreen(repo: ProxyRepository, onShare: (ProxyItem) -> Unit, onCopy: (String) -> Unit, onBulkShare: (List<ProxyItem>) -> Unit, onCopyAllJson: (List<ProxyItem>) -> Unit) {
    var allItems by remember { mutableStateOf(emptyList<ProxyItem>()) }
    var displayLimit by remember { mutableIntStateOf(100) }
    var loading by remember { mutableStateOf(false) }
    var checkingCount by remember { mutableIntStateOf(0) }
    var geoLoading by remember { mutableStateOf(false) }
    var sourceStatus by remember { mutableStateOf(if (allItems.isEmpty()) "Готов к сканированию" else "Сохранённые результаты загружены") }
    var sourceProgress by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selectedProtocols by remember { mutableStateOf(setOf<String>()) }
    var selectedCountry by remember { mutableStateOf("Все страны") }
    var onlyAvailable by remember { mutableStateOf(false) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var backgroundMonitor by remember {
        mutableStateOf(context.getSharedPreferences("proxy_finder", 0).getBoolean("background_monitor_enabled", false))
    }
    var scanFiltersSelected by remember { mutableStateOf(setOf<String>()) }
    var scanScope by remember { mutableStateOf("Весь мир") }
    var targetRange by remember { mutableStateOf("192.168.1.0/24") }
    var targetPorts by remember { mutableStateOf("1080, 3128, 8080, 8081, 8888, 9050") }
    var localResults by remember { mutableStateOf(emptyList<OpenEndpoint>()) }
    var errorMessage by remember { mutableStateOf("") }
    var autoRefresh by remember { mutableStateOf(context.getSharedPreferences("proxy_finder", 0).getBoolean("auto_refresh_enabled", false)) }
    var autoCheckRunning by remember { mutableStateOf(false) }
    var refreshCounter by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(repo) {
        val restored = withContext(Dispatchers.IO) { repo.loadSnapshot(5000) }
        allItems = restored
        sourceStatus = if (restored.isEmpty()) "Готов к сканированию" else "Загружено ${restored.size} последних узлов · база сохранена"
    }

    LaunchedEffect(Unit) {
        var cursor = 0
        while (true) {
            delay(15_000)
            if (autoRefresh && !loading && !autoCheckRunning && allItems.isNotEmpty()) {
                val candidates = allItems.asSequence().filter { it.needsNativeTunnelCheck }.take(48).toList().ifEmpty { allItems.filter { it.isTunnelVerified }.take(48) }
                if (candidates.isEmpty()) continue
                val candidate = candidates[cursor % candidates.size]
                cursor = (cursor + 1) % candidates.size
                autoCheckRunning = true
                scope.launch {
                    try {
                        val checked = repo.check(candidate)
                        val latest = allItems.map { if (it.id == checked.id) checked.copy(favorite = it.favorite) else it }
                        allItems = latest
                        withContext(Dispatchers.IO) {
                            repo.saveSnapshot(listOf(checked.copy(favorite = candidate.favorite)))
                            repo.saveFavorites(latest.filter { it.favorite }.map { it.id }.toSet())
                        }
                    } catch (_: Exception) {
                        // A transient probe failure must not clear previously saved results.
                    } finally {
                        autoCheckRunning = false
                    }
                }
            }
        }
    }

    fun persist(items: List<ProxyItem>) {
        allItems = items
        val favorites = items.filter { it.favorite }.map { it.id }.toSet()
        scope.launch(Dispatchers.IO) {
            repo.saveSnapshot(items)
            repo.saveFavorites(favorites)
        }
    }

    suspend fun persistAndSave(items: List<ProxyItem>) {
        allItems = items
        val favorites = items.filter { it.favorite }.map { it.id }.toSet()
        withContext(Dispatchers.IO) {
            repo.saveSnapshot(items)
            repo.saveFavorites(favorites)
        }
    }

    fun checkNextBatch() {
        if (loading || autoCheckRunning) return
        val batch = allItems.filter { it.needsNativeTunnelCheck }.take(48)
        if (batch.isEmpty()) {
            Toast.makeText(context, "Нет новых SOCKS4/SOCKS5/HTTP/HTTPS узлов для полной проверки. Для Shadowsocks, VLESS, VMess, Trojan, Hysteria2 и TUIC требуется sing-box core.", Toast.LENGTH_LONG).show()
            return
        }
        autoCheckRunning = true
        checkingCount = batch.size
        sourceStatus = "Проверяем туннели · пакет из ${batch.size}"
        sourceProgress = "Протокольная проверка с реальным HTTPS-запросом"
        scope.launch {
            try {
                val updates = repo.checkBatch(batch) { checked ->
                    allItems = allItems.map { if (it.id == checked.id) checked.copy(favorite = it.favorite) else it }
                    checkingCount = (checkingCount - 1).coerceAtLeast(0)
                }
                val byId = updates.associateBy { it.id }
                persistAndSave(allItems.map { byId[it.id]?.copy(favorite = it.favorite) ?: it })
                val verifiedNow = updates.count { it.isTunnelVerified }
                sourceStatus = "Пакет проверен: ${updates.size} · рабочих туннелей: $verifiedNow"
                sourceProgress = "Можно запустить следующий пакет. Неподдерживаемые протоколы не выдаются за проверенные."
            } catch (error: Exception) {
                errorMessage = "Проверка пакета прервана: ${error.message?.take(100) ?: "ошибка сети"}"
            } finally {
                autoCheckRunning = false
                checkingCount = 0
            }
        }
    }

    fun scan() {
        if (loading) return
        val scanAllProtocols = scanFiltersSelected.isEmpty() || scanFiltersSelected.size == 10
        selectedProtocols = if (scanAllProtocols) emptySet() else scanFiltersSelected
        loading = true
        errorMessage = ""
        if (scanScope == "Диапазон IP") {
            sourceStatus = "Сканируем разрешённый диапазон IP"
            sourceProgress = "Подготовка адресов и портов"
            localResults = emptyList()
            scope.launch {
                try {
                    val result = repo.scanAuthorizedRange(targetRange, targetPorts) { progress ->
                        withContext(Dispatchers.Main) { sourceProgress = progress }
                    }
                    localResults = result.endpoints
                    sourceStatus = "Диапазон IP · найдено ${result.endpoints.size}"
                    sourceProgress = result.message
                    if (result.endpoints.isEmpty()) errorMessage = result.message
                } catch (_: Exception) {
                    sourceStatus = "Сканирование диапазона прервано"
                    errorMessage = "Не удалось проверить диапазон. Проверь адрес, порты и разрешение на сканирование."
                } finally {
                    loading = false
                    checkingCount = 0
                }
            }
            return
        }
        sourceStatus = "Подключаемся к открытым источникам"
        sourceProgress = "Подготовка"
        scope.launch {
            try {
                val requested = if (scanAllProtocols) listOf("Все типы") else scanFiltersSelected.toList()
                val results = requested.map { filter -> repo.fetch(filter) { name -> withContext(Dispatchers.Main) { sourceProgress = "$filter · $name" } } }
                val combinedCounts = results.flatMap { it.sourceCounts.entries }
                    .groupBy { it.key }
                    .mapValues { (_, entries) -> entries.sumOf { it.value } }
                val combinedErrors = results.flatMap { it.sourceErrors.entries }
                    .associate { it.key to it.value }
                val fetched = FetchResult(
                    results.flatMap { it.items }.distinctBy { it.rawConfig.trim() },
                    combinedCounts,
                    combinedErrors
                )
                val matchedItems = fetched.items
                val currentItems = allItems
                val merged = withContext(Dispatchers.Default) {
                    val previousByConfig = currentItems.associateBy { it.rawConfig.trim() }
                    (currentItems + fetched.items).distinctBy { it.rawConfig.trim() }
                        .map { fresh ->
                            previousByConfig[fresh.rawConfig.trim()]?.let { previous ->
                                fresh.copy(
                                    checkStatus = previous.checkStatus,
                                    latencyMs = previous.latencyMs,
                                    checkedAt = previous.checkedAt,
                                    favorite = previous.favorite || fresh.favorite
                                )
                            } ?: fresh
                        }
                }
                persist(merged)
                sourceStatus = "Получено по выбранным протоколам: ${matchedItems.size} · всего в базе: ${merged.size}"
                sourceProgress = buildString {
                    append("Источники: ${fetched.sourceCounts.count { it.value > 0 }}/${fetched.sourceCounts.size} с конфигурациями")
                    if (fetched.sourceErrors.isNotEmpty()) {
                        append(" · ошибок: ${fetched.sourceErrors.size}")
                        append(" · ")
                        append(fetched.sourceErrors.entries.take(2).joinToString("; ") { "${it.key}: ${it.value}" })
                    }
                }
                if (matchedItems.isEmpty()) {
                    errorMessage = if (fetched.items.isEmpty()) {
                        "Источники не ответили или не содержат поддерживаемых конфигураций. Проверь подключение и повтори попытку."
                    } else {
                        "По выбранным протоколам новых конфигураций нет. Ранее найденные узлы сохранены; измени фильтр и повтори поиск."
                    }
                } else {
                    val batch = merged.filter { it.needsNativeTunnelCheck }.take(48)
                    if (batch.isNotEmpty()) {
                        sourceStatus = "Проверяем поддерживаемые прокси-туннели"
                        checkingCount = batch.size
                        val updates = repo.checkBatch(batch) { checked ->
                            withContext(Dispatchers.Main) {
                                allItems = allItems.map { if (it.id == checked.id) checked else it }
                                checkingCount = (checkingCount - 1).coerceAtLeast(0)
                            }
                        }
                        val updatedIds = updates.associateBy { it.id }
                        persistAndSave(allItems.map { updatedIds[it.id] ?: it })
                    } else {
                        persistAndSave(allItems)
                    }
                    sourceStatus = "Сканирование завершено"
                    sourceProgress = "Проверено: ${allItems.count { it.isChecked }} · подтверждённые туннели: ${allItems.count { it.isTunnelVerified }} · осталось: ${allItems.count { it.needsNativeTunnelCheck }}"
                    if (allItems.any { it.city.isBlank() } && scanFiltersSelected.isEmpty()) {
                        geoLoading = true
                        val countryItems = repo.resolveCountries(allItems.take(48).filter { it.city.isBlank() }) { resolved ->
                            withContext(Dispatchers.Main) {
                                allItems = allItems.map {
                                if (it.host == resolved.host) it.copy(country = resolved.country, countryCode = resolved.countryCode, city = resolved.city) else it
                            }
                            }
                        }
                        persist(countryItems)
                        geoLoading = false
                    }
                }
            } catch (_: Exception) {
                sourceStatus = "Сканирование прервано"
                errorMessage = "Не удалось завершить сканирование. Данные, уже сохранённые на устройстве, не удалены."
            } finally {
                loading = false
                checkingCount = 0
            }
        }
    }

    val protocols = listOf("VLESS", "VMESS", "TROJAN", "Shadowsocks", "Hysteria2", "TUIC", "SOCKS4", "SOCKS5", "HTTP", "HTTPS")
    val countries = remember(allItems) { listOf("Все страны") + allItems.asSequence().map { it.country }.filter { it.isNotBlank() }.distinct().sorted().toList() }
    val visible = remember(allItems, query, selectedProtocols, selectedCountry, onlyAvailable, onlyFavorites) { allItems.filter { item ->
        val haystack = "${item.host} ${item.port} ${item.protocol} ${item.source} ${item.title} ${item.country}"
        (query.isBlank() || haystack.contains(query, true)) &&
            (selectedProtocols.isEmpty() || selectedProtocols.any { item.protocol.equals(it, true) }) &&
            (selectedCountry == "Все страны" || item.country == selectedCountry) &&
            (!onlyAvailable || item.isTunnelVerified) && (!onlyFavorites || item.favorite)
    }.sortedWith(compareByDescending<ProxyItem> { it.qualityScore }.thenBy { it.latencyMs ?: Int.MAX_VALUE }.thenBy { it.host }) }
    val displayedItems = remember(visible, displayLimit) { visible.take(displayLimit) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item(key = "topbar") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 1.dp)
                ) {
                    Box(
                        Modifier.size(50.dp).clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(NeonOrange, NeonOrangeDeep)))
                            .border(1.dp, OrangeSoft.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.ic_happ_logo),
                            contentDescription = "HAPP Proxy Finder",
                            modifier = Modifier.size(45.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("HAPP PROXY", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        Text("ПОИСК И ПРОВЕРКА ПРОКСИ", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.65.sp)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(NeonOrange.copy(alpha = 0.10f))
                            .border(1.dp, NeonOrange.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Radar, null, tint = NeonOrange, modifier = Modifier.size(14.dp))
                            Text("СКАНЕР", color = NeonOrange, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                        }
                    }
                }
            }
            item(key = "hero") {
                HeroPanel(loading = loading, checkingCount = checkingCount, onScan = { scan() }, enabled = !loading)
            }
            item(key = "backgroundMonitor") {
                Column(
                    Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(17.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Фоновая проверка", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("HTTP/SOCKS5: проверка туннеля; остальные протоколы: пока только TCP-проверка", color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        Switch(
                            checked = backgroundMonitor,
                            onCheckedChange = { enabled ->
                                backgroundMonitor = enabled
                                context.getSharedPreferences("proxy_finder", 0).edit()
                                    .putBoolean("background_monitor_enabled", enabled).apply()
                                val serviceIntent = Intent(context, SpeedMonitorService::class.java).apply {
                                    action = if (enabled) SpeedMonitorService.ACTION_START else SpeedMonitorService.ACTION_STOP
                                }
                                if (enabled) androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                                else context.startService(serviceIntent)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonOrange, checkedTrackColor = NeonOrange.copy(alpha = 0.35f))
                        )
                    }
                    Text("Работает при заблокированном экране, но не при полностью выключенном телефоне. Android может ограничивать фоновые задачи.", color = Amber, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            item(key = "scanMode") {
                Column(
                    Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(18.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("ГДЕ ИСКАТЬ", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    FlowChips(items = listOf("Весь мир", "Диапазон IP"), selected = scanScope, onSelect = {
                        scanScope = it
                        errorMessage = ""
                    })
                    Text(
                        if (scanScope == "Весь мир") "Поиск опубликованных конфигураций из источников разных стран." else "Сканер IP/портов для сети или диапазона, который ты владеешь или имеешь разрешение проверять. Ограничение: максимум /24 и 8 портов за запуск.",
                        color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp
                    )
                    if (scanScope == "Диапазон IP") {
                        OutlinedTextField(
                            value = targetRange,
                            onValueChange = { targetRange = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("IPv4 / CIDR (например 192.168.1.0/24)") },
                            singleLine = true,
                            enabled = !loading,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonOrange, focusedLabelColor = NeonOrange,
                                unfocusedBorderColor = Line, focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        OutlinedTextField(
                            value = targetPorts,
                            onValueChange = { targetPorts = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("TCP-порты через запятую (до 8)") },
                            singleLine = true,
                            enabled = !loading,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonOrange, focusedLabelColor = NeonOrange,
                                unfocusedBorderColor = Line, focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Text("Сканируй только свои или разрешённые диапазоны. Открытый порт ещё не означает, что это прокси.", color = Amber, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Text("ВНЕШНИЙ ФИЛЬТР · ПРОТОКОЛЫ ИСТОЧНИКОВ", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(if (scanFiltersSelected.isEmpty() || scanFiltersSelected.size == 10) "Все типы включены" else "Выбрано: ${scanFiltersSelected.joinToString(", ")}", color = NeonOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Можно выбрать несколько протоколов одновременно. Сброс выбора включает все типы.", color = TextMuted, fontSize = 10.sp)
                    val scanFilters = listOf("VLESS", "VMESS", "TROJAN", "Shadowsocks", "Hysteria2", "TUIC", "SOCKS4", "SOCKS5", "HTTP", "HTTPS")
                    MultiSelectChips(
                        items = scanFilters,
                        selected = scanFiltersSelected,
                        onChange = { scanFiltersSelected = it; errorMessage = "" },
                        allLabel = "Все типы",
                        enabled = !loading
                    )
                }
            }
            item(key = "localResults") {
                if (scanScope == "Диапазон IP") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("РЕЗУЛЬТАТЫ СКАНИРОВАНИЯ IP", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        if (!loading && localResults.isEmpty() && errorMessage.isBlank()) {
                            Text("На выбранных адресах и портах ничего не обнаружено. Проверь диапазон и порты; сканируй только сети, на проверку которых у тебя есть разрешение.", color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                        localResults.forEach { endpoint -> LocalEndpointCard(endpoint) }
                    }
                }
            }
            item(key = "autoRefresh") {
                Row(
                    Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(17.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF35200F)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Refresh, null, tint = NeonOrange, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Автообновление · 15 сек", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(if (autoRefresh) "Проверяется следующий узел по очереди" else "Автопроверка приостановлена", color = TextMuted, fontSize = 10.sp)
                    }
                    Switch(checked = autoRefresh, onCheckedChange = {
                        autoRefresh = it
                        context.getSharedPreferences("proxy_finder", 0).edit().putBoolean("auto_refresh_enabled", it).apply()
                    },
                        colors = SwitchDefaults.colors(checkedThumbColor = NeonOrange, checkedTrackColor = NeonOrange.copy(alpha = 0.35f)))
                }
            }
            item(key = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("Найдено", allItems.size.toString(), "конфигураций", Icons.Default.Public, Modifier.weight(1f), NeonOrange)
                    MetricCard("Проверен", allItems.count { it.isTunnelVerified }.toString(), "туннель работает", Icons.Default.CheckCircle, Modifier.weight(1f), Green)
                    MetricCard("Средний ping", averageLatency(allItems), "проверенные узлы", Icons.Default.Speed, Modifier.weight(1f), OrangeSoft)
                }
            }
            item(key = "status") {
                Row(
                    Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(15.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (loading || geoLoading) NeonOrange else if (errorMessage.isNotBlank()) Amber else Green))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(sourceStatus, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (sourceProgress.isNotBlank()) sourceProgress else "Автопроверка отключена или работает с интервалом 15 секунд", color = TextMuted, fontSize = 10.sp)
                    }
                    if (loading || geoLoading) {
                        CircularProgressIndicator(Modifier.size(19.dp), color = NeonOrange, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(17.dp))
                    }
                }
            }
            item(key = "error") {
                AnimatedVisibility(visible = errorMessage.isNotBlank(), enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF281412)).border(1.dp, Red.copy(alpha = 0.28f), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Close, null, tint = Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage, color = Color(0xFFFFD7A0), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
            item(key = "search") {
                Row(
                    Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(16.dp)).padding(start = 13.dp, end = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = NeonOrange, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 14.sp),
                        cursorBrush = Brush.verticalGradient(listOf(NeonOrange, OrangeSoft)),
                        modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                        decorationBox = { inner -> Box {
                            if (query.isEmpty()) Text("IP, домен, протокол или страна", color = TextMuted, fontSize = 13.sp)
                            inner()
                        } }
                    )
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить", tint = TextMuted) }
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Default.Tune, "Фильтры", tint = if (showFilters || selectedProtocols.isNotEmpty() || selectedCountry != "Все страны") NeonOrange else TextMuted)
                    }
                }
            }
            item(key = "filters") {
                AnimatedVisibility(visible = showFilters, enter = fadeIn(tween(180)) + slideInVertically { -it / 3 }, exit = fadeOut(tween(140)) + slideOutVertically { -it / 3 }) {
                    Column(Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(17.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ФИЛЬТРЫ", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text("Протокол", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        MultiSelectChips(items = protocols, selected = selectedProtocols, onChange = { selectedProtocols = it }, allLabel = "Все протоколы")
                        Text("Страна", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        FlowChips(items = countries, selected = selectedCountry, onSelect = { selectedCountry = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = onlyAvailable, onClick = { onlyAvailable = !onlyAvailable }, label = { Text("Туннель проверен") }, leadingIcon = if (onlyAvailable) ({ Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp)) }) else null)
                            FilterChip(selected = onlyFavorites, onClick = { onlyFavorites = !onlyFavorites }, label = { Text("Избранное") }, leadingIcon = if (onlyFavorites) ({ Icon(Icons.Default.Favorite, null, Modifier.size(16.dp)) }) else null)
                        }
                    }
                }
            }
            item(key = "checkBatch") {
                val pendingNative = allItems.count { it.needsNativeTunnelCheck }
                Button(
                    onClick = { checkNextBatch() },
                    enabled = pendingNative > 0 && !loading && !autoCheckRunning,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange.copy(alpha = 0.15f), contentColor = TextPrimary)
                ) {
                    if (autoCheckRunning) CircularProgressIndicator(Modifier.size(17.dp), color = NeonOrange, strokeWidth = 2.dp)
                    else Icon(Icons.Default.NetworkCheck, null, tint = NeonOrange, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(if (autoCheckRunning) "Проверка туннелей…" else "Проверить следующие 48", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("$pendingNative SOCKS4/SOCKS5/HTTP/HTTPS требуют проверки или повторной проверки", fontSize = 10.sp, color = TextMuted)
                    }
                }
                Text("Проверка делает CONNECT/SOCKS handshake и реальный HTTPS-запрос. Другие протоколы будут проверяться после подключения sing-box core.", color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
            }
            item(key = "bulkImport") {
                if (allItems.isNotEmpty()) {
                    val exportItems = allItems.distinctBy { it.rawConfig.trim() }.filter { it.rawConfig.isNotBlank() }
                                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(
                        onClick = { onBulkShare(exportItems) },
                        enabled = exportItems.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7A18), contentColor = Color(0xFF1A0B02)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                            Text("Поделиться конфигурациями", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${exportItems.size} узлов · включая непроверенные", fontSize = 10.sp, color = Color(0xFF5C2C0B))
                        }
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(17.dp))
                    }
                    Text("Экспортируются все найденные уникальные конфигурации, включая непроверенные. Проверка и её статус сохраняются отдельно.", color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 3.dp))
                    OutlinedButton(onClick = { onCopyAllJson(exportItems) }, enabled = exportItems.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.ContentCopy, null, tint = NeonOrange)
                        Spacer(Modifier.width(8.dp))
                        Text("Сохранить JSON-файл", color = NeonOrange)
                    }
                    }
                }
            }
            item(key = "resultHeader") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Результаты", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("Сначала самые быстрые по задержке", color = TextMuted, fontSize = 11.sp)
                    }
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2D180B)).padding(horizontal = 11.dp, vertical = 7.dp)) {
                        Text("${visible.size} узлов", color = NeonOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(loading = loading, onScan = { scan() })
                }
            } else {
                items(displayedItems, key = { it.id }, contentType = { "proxy" }) { item ->
                    ProxyCard(
                        item = item,
                        onFavorite = {
                            val updated = allItems.map { if (it.id == item.id) it.copy(favorite = !it.favorite) else it }
                            persist(updated)
                        },
                        onCheck = {
                            scope.launch {
                                val checked = repo.check(item)
                                persist(allItems.map { if (it.id == item.id) checked else it })
                            }
                        },
                        onShare = { onShare(item) }, onCopy = { onCopy(namedConfigForImport(item)) }
                    )
                }
            }
            if (displayedItems.size < visible.size) {
                item(key = "loadMoreCards") {
                    OutlinedButton(onClick = { displayLimit = (displayLimit + 100).coerceAtMost(visible.size) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Показать ещё ${minOf(100, visible.size - displayedItems.size)} · осталось ${visible.size - displayedItems.size}", color = NeonOrange)
                    }
                }
            }
            item(key = "disclaimer") {
                Text(
                    "При передаче в HAPP доступны все найденные конфигурации, в том числе непроверенные. У SOCKS4/SOCKS5/HTTP/HTTPS проверяется туннель; у VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC без sing-box core подтверждается только TCP-доступность.",
                    color = TextMuted.copy(alpha = 0.75f), fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }



}

@Composable
private fun HeroPanel(loading: Boolean, checkingCount: Int, onScan: () -> Unit, enabled: Boolean) {
    val transition = rememberInfiniteTransition(label = "hero-neon")
    val glow by transition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(1450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar-glow"
    )

    Column(
        Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(24.dp)).padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (loading) NeonOrange else Green))
                    Text(
                        if (loading) "СКАНЕР АКТИВЕН" else "ГОТОВ К СКАНИРОВАНИЮ",
                        color = if (loading) NeonOrange else Green,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.35.sp
                    )
                }
                Text("Найди свой\nбыстрый узел.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 28.sp)
                Text("Публичные прокси-конфигурации для HAPP", color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(108.dp).clip(CircleShape).background(NeonOrange.copy(alpha = 0.035f + glow * 0.045f)))
                androidx.compose.foundation.Canvas(Modifier.size(100.dp)) {
                    val stroke = 1.dp.toPx()
                    val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                    drawCircle(color = NeonOrange.copy(alpha = 0.18f + glow * 0.15f), radius = size.minDimension * 0.48f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 1.4f))
                    drawCircle(color = NeonOrange.copy(alpha = 0.12f), radius = size.minDimension * 0.31f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawLine(color = NeonOrange.copy(alpha = 0.12f), start = androidx.compose.ui.geometry.Offset(size.width / 2, 0f), end = androidx.compose.ui.geometry.Offset(size.width / 2, size.height), strokeWidth = stroke)
                    drawLine(color = NeonOrange.copy(alpha = 0.12f), start = androidx.compose.ui.geometry.Offset(0f, size.height / 2), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2), strokeWidth = stroke)
                    rotate(28f) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(Color.Transparent, NeonOrange.copy(alpha = glow), Color.Transparent)),
                            startAngle = 0f,
                            sweepAngle = 112f,
                            useCenter = true
                        )
                    }
                    drawCircle(color = OrangeSoft, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height * 0.34f))
                    drawCircle(color = Green, radius = 2.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.31f, size.height * 0.63f))
                    drawCircle(color = NeonOrange.copy(alpha = 0.8f), radius = 2.dp.toPx(), center = center)
                }
                androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_happ_logo), contentDescription = null, modifier = Modifier.size(51.dp))
            }
        }
        Button(
            onClick = onScan,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NeonOrange,
                contentColor = Color(0xFF1A0B02),
                disabledContainerColor = SurfaceRaised,
                disabledContentColor = TextMuted
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF1A0B02), strokeWidth = 2.dp)
            else Icon(Icons.Default.Radar, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Text(if (loading) "Сканирование выполняется…" else "ЗАПУСТИТЬ СКАНИРОВАНИЕ", fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, tint = NeonOrange, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("Режим и внешние протоколы настраиваются ниже", color = TextMuted, fontSize = 10.sp)
            if (loading && checkingCount > 0) {
                Spacer(Modifier.weight(1f))
                Text("$checkingCount", color = NeonOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LocalEndpointCard(endpoint: OpenEndpoint) {
    Column(
        Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(15.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NetworkCheck, null, tint = if (endpoint.protocol == "TCP") Amber else Green, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(endpoint.host + ":" + endpoint.port, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Источник: диапазон IP", color = TextMuted, fontSize = 10.sp)
            }
            Text("TCP " + endpoint.latencyMs + " мс", color = OrangeSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(endpoint.protocol, if (endpoint.protocol == "TCP") Color(0xFF3A2B1B) else Color(0xFF2D1B0E), if (endpoint.protocol == "TCP") Amber else NeonOrange)
            Text(endpoint.status, color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, caption: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, accent: Color) {
    Column(
        modifier.neonPanel(RoundedCornerShape(17.dp)).padding(horizontal = 11.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
            Text(title, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedContent(targetState = value, transitionSpec = { (fadeIn(tween(180)) + slideInVertically { it / 3 }).togetherWith(fadeOut(tween(120)) + slideOutVertically { -it / 3 }) }, label = "metric") { current ->
            Text(current, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(caption, color = TextMuted.copy(alpha = 0.8f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MultiSelectChips(items: List<String>, selected: Set<String>, onChange: (Set<String>) -> Unit, allLabel: String, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = selected.isEmpty(), enabled = enabled, onClick = { onChange(emptySet()) }, label = { Text(allLabel) }, colors = FilterChipDefaults.filterChipColors(containerColor = SurfaceRaised, labelColor = TextMuted, selectedContainerColor = NeonOrange.copy(alpha = 0.15f), selectedLabelColor = NeonOrange))
            FilterChip(selected = selected.size == items.size, enabled = enabled, onClick = { onChange(if (selected.size == items.size) emptySet() else items.toSet()) }, label = { Text("Выбрать все") }, colors = FilterChipDefaults.filterChipColors(containerColor = SurfaceRaised, labelColor = TextMuted, selectedContainerColor = NeonOrange.copy(alpha = 0.15f), selectedLabelColor = NeonOrange))
        }
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    val active = item in selected
                    FilterChip(selected = active, enabled = enabled, onClick = { onChange(if (active) selected - item else selected + item) }, label = { Text(item, maxLines = 1) }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(containerColor = SurfaceRaised, labelColor = TextMuted, selectedContainerColor = NeonOrange.copy(alpha = 0.15f), selectedLabelColor = NeonOrange))
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FlowChips(items: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rowItems.forEach { item ->
                    val active = item == selected
                    val bg by animateColorAsState(if (active) NeonOrange.copy(alpha = 0.14f) else SurfaceRaised, label = "chip-bg")
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onSelect(item) },
                        color = bg, shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (active) NeonOrange.copy(alpha = 0.65f) else Line)
                    ) {
                        Text(item, color = if (active) NeonOrange else TextMuted, fontSize = 10.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 7.dp, vertical = 9.dp))
                    }
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ProxyCard(item: ProxyItem, onFavorite: () -> Unit, onCheck: () -> Unit, onShare: () -> Unit, onCopy: () -> Unit) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val borderColor = when {
        item.isTcpReachable -> Green.copy(alpha = 0.3f)
        item.checkStatus == "TCP недоступен" -> Red.copy(alpha = 0.2f)
        else -> Line
    }
    Column(
        Modifier.fillMaxWidth().neonPanel(RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(borderColor.copy(alpha = 0.08f), Color.Transparent))).padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF25170D)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Public, null, tint = if (item.isTunnelVerified) Green else NeonOrange, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title.ifBlank { item.protocol }, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.host}:${item.port}", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "В избранное", tint = if (item.favorite) Red else TextMuted, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(item.protocol.uppercase(), NeonOrange.copy(alpha = 0.12f), NeonOrange)
                Tag("${countryFlag(item.countryCode)} ${item.country.ifBlank { "Страна не определена" }}", Green.copy(alpha = 0.10f), Green)
                Spacer(Modifier.weight(1f))
                val statusColor = if (item.isTunnelVerified) Green else if (item.checkStatus == "TCP недоступен") Red else Amber
                Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                Text(when { item.isTunnelVerified -> "Прокси проверен"; item.checkStatus.startsWith("TCP доступен") -> "TCP доступен"; item.checkStatus == "TCP недоступен" -> "Недоступен"; else -> "Не проверен" }, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallMetric(
                    if (item.isTunnelVerified) "PING" else if (item.checkStatus.startsWith("TCP доступен")) "TCP RTT" else "PING",
                    item.latencyMs?.let { if (item.isTunnelVerified) "$it мс" else if (item.checkStatus.startsWith("TCP доступен")) "$it мс" else "—" } ?: "—",
                    Icons.Default.Speed, Modifier.weight(1f)
                )
                SmallMetric("ОЦЕНКА", if (item.qualityScore > 5) "${item.qualityScore}/100" else "—", Icons.Default.CheckCircle, Modifier.weight(1f))
                SmallMetric("СТРАНА", if (item.countryCode.isNotBlank()) countryFlag(item.countryCode) + " " + item.countryCode.uppercase() else "🏳️", Icons.Default.LocationOn, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onShare, modifier = Modifier.weight(1.1f), shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 9.dp)) {
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Передать", fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(onClick = onCheck, enabled = item.supportsNativeTunnelCheck, modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Line), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 9.dp)) {
                    Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(15.dp), tint = if (item.supportsNativeTunnelCheck) NeonOrange else Amber); Spacer(Modifier.width(5.dp)); Text(if (item.supportsNativeTunnelCheck) "Проверить" else "Нужен core", fontSize = 10.sp, maxLines = 1, color = TextPrimary)
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(39.dp).clip(RoundedCornerShape(11.dp)).background(NeonOrange.copy(alpha = 0.10f)).border(1.dp, NeonOrange.copy(alpha = 0.32f), RoundedCornerShape(11.dp))) {
                    Icon(Icons.Default.ContentCopy, "Копировать ссылку", tint = NeonOrange, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "Скрыть детали" else "Детали проверки", color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(if (expanded) "−" else "+", color = NeonOrange, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(tween(160)) + slideInVertically { -it / 4 }, exit = fadeOut(tween(100))) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    DetailLine("Статус", item.checkStatus)
                    DetailLine("Источник", item.source)
                    DetailLine("Последняя проверка", item.checkedAt?.let { java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "Ещё не проверялся")
                    DetailLine("Оценка", when {
                        item.isTunnelVerified -> "Подтверждён ответ HTTP 204 через прокси; это задержка, не замер пропускной способности"
                        item.checkStatus.startsWith("TCP доступен") -> "Открытый TCP-порт; работа туннеля и скорость не проверены"
                        else -> "Пока недостаточно данных"
                    })
                    DetailLine("Конфигурация", item.rawConfig.take(100) + if (item.rawConfig.length > 100) "…" else "")
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, background: Color, foreground: Color) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(background).padding(horizontal = 7.dp, vertical = 5.dp)) {
        Text(text, color = foreground, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallMetric(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(SurfaceRaised).padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = TextMuted, modifier = Modifier.size(11.dp))
            Text(title, color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(value, color = TextPrimary.copy(alpha = 0.88f), fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun EmptyState(loading: Boolean, onScan: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp).neonPanel(RoundedCornerShape(22.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF2A190E)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Search, null, tint = NeonOrange, modifier = Modifier.size(29.dp))
        }
        Text(if (loading) "Ищем конфигурации" else "Пока ничего не найдено", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(if (loading) "Загружаем источники и проверяем узлы. Это может занять немного времени." else "Запусти сканирование или измени параметры фильтра.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
        if (!loading) OutlinedButton(onClick = onScan, shape = RoundedCornerShape(12.dp)) { Text("Повторить поиск", color = NeonOrange) }
    }
}

private fun averageLatency(items: List<ProxyItem>): String {
    val values = items.filter { it.isTunnelVerified }.mapNotNull { it.latencyMs }
    return if (values.isEmpty()) "—" else "${values.average().toInt()} мс"
}

private fun countryFlag(countryCode: String): String {
    val code = countryCode.trim().uppercase(java.util.Locale.ROOT)
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return "🏳️"
    val first = code[0].code - 'A'.code + 0x1F1E6
    val second = code[1].code - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
