package com.happ.proxyfinder

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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

private val Bg = Color(0xFF070A12)
private val Surface = Color(0xFF101827)
private val SurfaceRaised = Color(0xFF151F31)
private val Blue = Color(0xFF53C8FF)
private val BlueDeep = Color(0xFF1E86FF)
private val Cyan = Color(0xFF78F0FF)
private val Green = Color(0xFF52E6A6)
private val Amber = Color(0xFFFFC76A)
private val Red = Color(0xFFFF7189)
private val TextPrimary = Color(0xFFF4F8FF)
private val TextMuted = Color(0xFF8C9AB1)
private val Line = Color(0xFF26354B)

class MainActivity : ComponentActivity() {
    private lateinit var repository: ProxyRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 10, 18)
        window.navigationBarColor = android.graphics.Color.rgb(7, 10, 18)
        repository = ProxyRepository(this)
        setContent {
            val scheme = darkColorScheme(
                primary = Blue, onPrimary = Color(0xFF03101A), secondary = Cyan,
                background = Bg, onBackground = TextPrimary, surface = Surface,
                onSurface = TextPrimary, surfaceVariant = SurfaceRaised, onSurfaceVariant = TextMuted,
                outline = Line, error = Red
            )
            MaterialTheme(colorScheme = scheme, typography = Typography()) {
                Surface(color = Bg) {
                    ProxyFinderScreen(
                        repo = repository,
                        onShare = { item ->
                            val config = namedConfigForImport(item)
                            val choices = arrayOf("Добавить в HAPP", "Добавить в Hiddify", "Другие приложения / поделиться", "Копировать конфигурацию")
                            AlertDialog.Builder(this)
                                .setTitle("Куда добавить конфигурацию?")
                                .setItems(choices) { _, which ->
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
                                            // HAPP supports its happ://add deep link for import links.
                                            val deepLink = Uri.Builder().scheme("happ").authority("add").appendPath(config).build()
                                            val intent = Intent(Intent.ACTION_VIEW, deepLink)
                                            if (intent.resolveActivity(packageManager) != null) {
                                                startActivity(intent)
                                            } else {
                                                Toast.makeText(this, "HAPP не найден. Скопируйте ссылку или выберите другое приложение.", Toast.LENGTH_LONG).show()
                                                shareChooser()
                                            }
                                        }
                                        1 -> {
                                            // Hiddify's documented import format accepts a single proxy URI.
                                            val deepLink = Uri.Builder().scheme("hiddify").authority("import").appendPath(config).build()
                                            val intent = Intent(Intent.ACTION_VIEW, deepLink)
                                            if (intent.resolveActivity(packageManager) != null) {
                                                startActivity(intent)
                                            } else {
                                                Toast.makeText(this, "Hiddify не найден. Скопируйте ссылку или выберите другое приложение.", Toast.LENGTH_LONG).show()
                                                shareChooser()
                                            }
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
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HAPP configuration", value))
                            Toast.makeText(this, "Конфигурация скопирована", Toast.LENGTH_SHORT).show()
                        },
                        onBulkShare = { items ->
                            val payload = "#subscription-ping-onopen-enabled: 1\n" + items.distinctBy { it.rawConfig }.joinToString("\n") { namedConfigForImport(it) }
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, payload)
                                putExtra(Intent.EXTRA_SUBJECT, "HAPP Proxy Finder · ${items.size} конфигураций")
                            }
                            startActivity(Intent.createChooser(send, "Передать ${items.size} конфигураций в HAPP"))
                        },
                        onCopyAllJson = { items ->
                            // HAPP accepts a subscription-style text list of URI configurations.
                            // A JSON array of strings is not a valid Xray/HAPP JSON configuration.
                            val unique = items.distinctBy { it.rawConfig.trim() }
                                .filter { it.rawConfig.isNotBlank() }
                            val payload = "#subscription-ping-onopen-enabled: 1\n" +
                                unique.joinToString("\n") { namedConfigForImport(it) }
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HAPP configurations", payload))
                            Toast.makeText(this, "Список для HAPP скопирован: ${unique.size} конфигураций", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
    }
}

private fun namedConfigForImport(item: ProxyItem): String {
    val displayName = (if (item.country.isNotBlank() && item.countryCode.length == 2) {
        countryFlag(item.countryCode) + " " + item.country
    } else {
        item.title.ifBlank { item.protocol }
    }).take(30)
    val description = android.util.Base64.encodeToString(
        item.protocol.toByteArray(Charsets.UTF_8),
        android.util.Base64.NO_WRAP
    )
    return item.rawConfig.trim().substringBefore('#') +
        "#" + Uri.encode(displayName) + "?serverDescription=" + description
}

@Composable
private fun ProxyFinderScreen(repo: ProxyRepository, onShare: (ProxyItem) -> Unit, onCopy: (String) -> Unit, onBulkShare: (List<ProxyItem>) -> Unit, onCopyAllJson: (List<ProxyItem>) -> Unit) {
    var allItems by remember { mutableStateOf(repo.loadSnapshot()) }
    var loading by remember { mutableStateOf(false) }
    var checkingCount by remember { mutableIntStateOf(0) }
    var geoLoading by remember { mutableStateOf(false) }
    var sourceStatus by remember { mutableStateOf(if (allItems.isEmpty()) "Готов к сканированию" else "Сохранённые результаты загружены") }
    var sourceProgress by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf("Все протоколы") }
    var selectedCountry by remember { mutableStateOf("Все страны") }
    var onlyAvailable by remember { mutableStateOf(false) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var backgroundMonitor by remember {
        mutableStateOf(context.getSharedPreferences("proxy_finder", 0).getBoolean("background_monitor_enabled", false))
    }
    var scanFilter by remember { mutableStateOf("Все типы") }
    var scanScope by remember { mutableStateOf("Весь мир") }
    var targetRange by remember { mutableStateOf("192.168.1.0/24") }
    var targetPorts by remember { mutableStateOf("1080, 3128, 8080, 8081, 8888, 9050") }
    var localResults by remember { mutableStateOf(emptyList<OpenEndpoint>()) }
    var errorMessage by remember { mutableStateOf("") }
    var autoRefresh by remember { mutableStateOf(true) }
    var autoCheckRunning by remember { mutableStateOf(false) }
    var refreshCounter by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        var cursor = 0
        while (true) {
            delay(1000)
            refreshCounter = (refreshCounter + 1) % 60
            if (autoRefresh && !loading && !autoCheckRunning && allItems.isNotEmpty()) {
                val candidates = allItems.filter { it.isChecked }.ifEmpty { allItems.take(48) }
                val candidate = candidates[cursor % candidates.size]
                cursor = (cursor + 1) % candidates.size
                autoCheckRunning = true
                scope.launch {
                    try {
                        val checked = repo.check(candidate)
                        val latest = allItems.map { if (it.id == checked.id) checked.copy(favorite = it.favorite) else it }
                        allItems = latest
                        repo.saveSnapshot(latest)
                        repo.saveFavorites(latest.filter { it.favorite }.map { it.id }.toSet())
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
        repo.saveSnapshot(items)
        repo.saveFavorites(items.filter { it.favorite }.map { it.id }.toSet())
    }

    fun scan() {
        if (loading) return
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
                val fetched = repo.fetch { name ->
                    withContext(Dispatchers.Main) { sourceProgress = name }
                }
                val matchesFilter: (ProxyItem) -> Boolean = { item ->
                    scanFilter == "Все типы" || item.protocol.equals(scanFilter, ignoreCase = true)
                }
                val matchedItems = fetched.items.filter(matchesFilter)
                val oldFavorites = allItems.filter { it.favorite && matchesFilter(it) }.associateBy { it.id }
                val merged = (matchedItems + oldFavorites.values).distinctBy { it.rawConfig }
                    .map { item -> oldFavorites[item.id]?.let { item.copy(favorite = true) } ?: item }
                    .take(2500)
                persist(merged)
                sourceStatus = "Получено ${matchedItems.size} конфигураций по фильтру «$scanFilter»"
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
                        "По фильтру «$scanFilter» конфигураций не найдено. Выбери «Все типы» или повтори позже."
                    }
                } else {
                    val batch = merged.filter { !it.isChecked }.take(48)
                    if (batch.isNotEmpty()) {
                        sourceStatus = "Проверяем доступность узлов"
                        checkingCount = batch.size
                        val updates = repo.checkBatch(batch) { checked ->
                            withContext(Dispatchers.Main) {
                                allItems = allItems.map { if (it.id == checked.id) checked else it }
                                checkingCount = (checkingCount - 1).coerceAtLeast(0)
                            }
                        }
                        val updatedIds = updates.associateBy { it.id }
                        persist(allItems.map { updatedIds[it.id] ?: it })
                    }
                    sourceStatus = "Сканирование завершено"
                    sourceProgress = "Проверено: ${allItems.count { it.isChecked }} · подтверждённые прокси: ${allItems.count { it.isTunnelVerified }}"
                    if (allItems.any { it.country.isBlank() }) {
                        geoLoading = true
                        val countryItems = repo.resolveCountries(allItems) { resolved ->
                            withContext(Dispatchers.Main) {
                                allItems = allItems.map {
                                if (it.host == resolved.host) it.copy(country = resolved.country, countryCode = resolved.countryCode) else it
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

    val protocols = listOf("Все протоколы", "VLESS", "VMESS", "TROJAN", "Shadowsocks", "Hysteria2", "SOCKS5")
    val countries = listOf("Все страны") + allItems.map { it.country }.filter { it.isNotBlank() }.distinct().sorted()
    val visible = allItems.filter { item ->
        val haystack = "${item.host} ${item.port} ${item.protocol} ${item.source} ${item.title} ${item.country}"
        (query.isBlank() || haystack.contains(query, true)) &&
            (selectedProtocol == "Все протоколы" || item.protocol.equals(selectedProtocol, true)) &&
            (selectedCountry == "Все страны" || item.country == selectedCountry) &&
            (!onlyAvailable || item.isTcpReachable) && (!onlyFavorites || item.favorite)
    }.sortedWith(compareByDescending<ProxyItem> { it.qualityScore }.thenBy { it.latencyMs ?: Int.MAX_VALUE }.thenBy { it.host })

    Column(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item(key = "topbar") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Blue, BlueDeep))),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_happ_logo), contentDescription = "HAPP Proxy Finder", modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("HAPP PROXY", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                        Text("ПОИСК  /  ОТКРЫТЫЕ ПРОКСИ", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.7.sp)
                    }
                    Icon(Icons.Default.Refresh, contentDescription = "Сканирование запускается кнопкой ниже", tint = TextMuted.copy(alpha = 0.65f), modifier = Modifier.size(22.dp))
                }
            }
            item(key = "hero") {
                HeroPanel(loading = loading, checkingCount = checkingCount, onScan = { scan() }, enabled = !loading)
            }
            item(key = "exportJson") {
                val exportItems = allItems.distinctBy { it.rawConfig.trim() }.filter { it.rawConfig.isNotBlank() }
                OutlinedButton(
                    onClick = { onCopyAllJson(exportItems) },
                    enabled = exportItems.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, tint = Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text("Копировать список конфигураций для HAPP", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("${exportItems.size} уникальных ссылок · только реальные результаты", color = TextMuted, fontSize = 10.sp)
                    }
                    Icon(Icons.Default.ContentCopy, null, tint = Cyan, modifier = Modifier.size(16.dp))
                }
                Text(
                    "Копирует текстовый список ссылок VLESS/VMess/Trojan/SS для импорта в HAPP. Это список подписки, а не JSON-конфигурация Xray.",
                    color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            item(key = "backgroundMonitor") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Фоновая проверка", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Проверка каждые 5 минут: generate_204 для HTTP/SOCKS5, TCP для остальных протоколов", color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
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
                            colors = SwitchDefaults.colors(checkedThumbColor = Blue, checkedTrackColor = Blue.copy(alpha = 0.35f))
                        )
                    }
                    Text("Работает при заблокированном экране, но не при полностью выключенном телефоне. Android может ограничивать фоновые задачи.", color = Amber, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            item(key = "scanMode") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Surface).padding(14.dp),
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
                                focusedBorderColor = Blue, focusedLabelColor = Blue,
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
                                focusedBorderColor = Blue, focusedLabelColor = Blue,
                                unfocusedBorderColor = Line, focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Text("Сканируй только свои или разрешённые диапазоны. Открытый порт ещё не означает, что это прокси.", color = Amber, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Text("ТИП ПРОКСИ", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    val scanFilters = listOf("Все типы", "VLESS", "VMESS", "TROJAN", "Shadowsocks", "Hysteria2", "SOCKS5")
                    scanFilters.chunked(3).forEach { rowFilters ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                            rowFilters.forEach { filter ->
                                val selected = scanFilter == filter
                                Surface(
                                    modifier = Modifier.weight(1f).clickable(enabled = !loading) {
                                        scanFilter = filter
                                        errorMessage = ""
                                    },
                                    color = if (selected) Color(0xFF123B5A) else SurfaceRaised,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (selected) Blue else Line)
                                ) {
                                    Text(filter, modifier = Modifier.padding(horizontal = 5.dp, vertical = 11.dp),
                                        color = if (selected) Blue else TextPrimary, fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                                }
                            }
                            repeat(3 - rowFilters.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Button(
                        onClick = { scan() }, enabled = !loading,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color(0xFF03101A))
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (loading) "Сканирование…" else "Сканировать", fontWeight = FontWeight.Bold)
                    }
                }
            }
            item(key = "localResults") {
                if (scanScope == "Локальная сеть") {
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
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF102A42), Surface)))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF163A55)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Refresh, null, tint = Blue, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Автообновление · 1 сек", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(if (autoRefresh) "Проверяется следующий узел по очереди" else "Автопроверка приостановлена", color = TextMuted, fontSize = 10.sp)
                    }
                    Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Blue, checkedTrackColor = Blue.copy(alpha = 0.35f)))
                }
            }
            item(key = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("Найдено", allItems.size.toString(), "конфигураций", Icons.Default.Public, Modifier.weight(1f), Blue)
                    MetricCard("Доступен", allItems.count { it.isTcpReachable }.toString(), "порт отвечает", Icons.Default.CheckCircle, Modifier.weight(1f), Green)
                    MetricCard("Средний ping", averageLatency(allItems), "проверенные узлы", Icons.Default.Speed, Modifier.weight(1f), Cyan)
                }
            }
            item(key = "status") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface).padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (loading || geoLoading) Blue else if (errorMessage.isNotBlank()) Amber else Green))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(sourceStatus, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (sourceProgress.isNotBlank()) sourceProgress else "Автопроверка: 1 сек · цикл ${refreshCounter + 1}/60", color = TextMuted, fontSize = 10.sp)
                    }
                    if (loading || geoLoading) {
                        CircularProgressIndicator(Modifier.size(19.dp), color = Blue, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(17.dp))
                    }
                }
            }
            item(key = "error") {
                AnimatedVisibility(visible = errorMessage.isNotBlank(), enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2020)).padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Close, null, tint = Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage, color = Color(0xFFFFD7A0), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
            item(key = "search") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceRaised).padding(start = 13.dp, end = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = Blue, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 14.sp),
                        cursorBrush = Brush.verticalGradient(listOf(Blue, Cyan)),
                        modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                        decorationBox = { inner -> Box {
                            if (query.isEmpty()) Text("IP, домен, протокол или страна", color = TextMuted, fontSize = 13.sp)
                            inner()
                        } }
                    )
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить", tint = TextMuted) }
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Default.Tune, "Фильтры", tint = if (showFilters || selectedProtocol != "Все протоколы" || selectedCountry != "Все страны") Blue else TextMuted)
                    }
                }
            }
            item(key = "filters") {
                AnimatedVisibility(visible = showFilters, enter = fadeIn(tween(180)) + slideInVertically { -it / 3 }, exit = fadeOut(tween(140)) + slideOutVertically { -it / 3 }) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ФИЛЬТРЫ", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text("Протокол", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        FlowChips(items = protocols, selected = selectedProtocol, onSelect = { selectedProtocol = it })
                        Text("Страна", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        FlowChips(items = countries, selected = selectedCountry, onSelect = { selectedCountry = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = onlyAvailable, onClick = { onlyAvailable = !onlyAvailable }, label = { Text("Соединение доступно") }, leadingIcon = if (onlyAvailable) ({ Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp)) }) else null)
                            FilterChip(selected = onlyFavorites, onClick = { onlyFavorites = !onlyFavorites }, label = { Text("Избранное") }, leadingIcon = if (onlyFavorites) ({ Icon(Icons.Default.Favorite, null, Modifier.size(16.dp)) }) else null)
                        }
                    }
                }
            }
            item(key = "bulkImport") {
                if (allItems.isNotEmpty()) {
                    Button(
                        onClick = { onBulkShare(allItems) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF174A38), contentColor = Color(0xFFD9FFF0)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                            Text("Передать все в HAPP", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${allItems.size} конфигураций · страны и флаги сохранены", fontSize = 10.sp, color = Color(0xFFACDEC9))
                        }
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(17.dp))
                    }
                }
            }
            item(key = "resultHeader") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Результаты", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("Сначала самые быстрые по задержке", color = TextMuted, fontSize = 11.sp)
                    }
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF102B43)).padding(horizontal = 11.dp, vertical = 7.dp)) {
                        Text("${visible.size} узлов", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(loading = loading, onScan = { scan() })
                }
            } else {
                items(visible, key = { it.id }, contentType = { "proxy" }) { item ->
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
            item(key = "disclaimer") {
                Text(
                    "Для HTTP и SOCKS5 измеряется задержка запроса к gstatic generate_204. Для VLESS, VMess и Shadowsocks без соответствующего прокси-ядра проверяется только TCP-порт. Бесплатные публичные узлы могут быть ненадёжными.",
                    color = TextMuted.copy(alpha = 0.75f), fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }



}

@Composable
private fun HeroPanel(loading: Boolean, checkingCount: Int, onScan: () -> Unit, enabled: Boolean) {
    val infinite = rememberInfiniteTransition(label = "radar")
    val pulse by infinite.animateFloat(initialValue = 0.86f, targetValue = 1.12f, animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val rotation by infinite.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(tween(8500, easing = androidx.compose.animation.core.LinearEasing)), label = "rotation")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF102C48), Color(0xFF0D1829), Color(0xFF111626))))
            .padding(start = 18.dp, top = 18.dp, bottom = 17.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (loading) Blue else Green))
                Text(if (loading) "СКАНЕР АКТИВЕН" else "ГОТОВ К РАБОТЕ", color = if (loading) Blue else Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            }
            Text("Найди свой\nбыстрый узел.", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 27.sp)
            Text("Публичные конфигурации для HAPP", color = Color(0xFFB5C8DD), fontSize = 11.sp)
            Text("Выбери режим ниже и нажми «Сканировать».", color = Color(0xFFB5C8DD), fontSize = 11.sp, lineHeight = 15.sp)
        }
        Box(Modifier.size(124.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(112.dp).scale(pulse).clip(CircleShape).background(Blue.copy(alpha = 0.045f)))
            Box(Modifier.size(94.dp).clip(CircleShape).background(Color(0xFF122A42)), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 1.dp.toPx()
                    drawCircle(color = Blue.copy(alpha = 0.2f), radius = size.minDimension * 0.48f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawCircle(color = Blue.copy(alpha = 0.18f), radius = size.minDimension * 0.31f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawLine(color = Blue.copy(alpha = 0.13f), start = androidx.compose.ui.geometry.Offset(size.width / 2, 0f), end = androidx.compose.ui.geometry.Offset(size.width / 2, size.height), strokeWidth = stroke)
                    drawLine(color = Blue.copy(alpha = 0.13f), start = androidx.compose.ui.geometry.Offset(0f, size.height / 2), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2), strokeWidth = stroke)
                    rotate(rotation) {
                        drawArc(brush = Brush.sweepGradient(listOf(Color.Transparent, Blue.copy(alpha = 0.75f), Color.Transparent)), startAngle = 0f, sweepAngle = 110f, useCenter = true)
                    }
                    drawCircle(color = Cyan, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height * 0.36f))
                    drawCircle(color = Green, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.31f, size.height * 0.62f))
                }
                androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_happ_logo), contentDescription = "HAPP Proxy Finder", modifier = Modifier.size(62.dp))
            }
        }
    }
}

@Composable
private fun LocalEndpointCard(endpoint: OpenEndpoint) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NetworkCheck, null, tint = if (endpoint.protocol == "TCP") Amber else Green, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(endpoint.host + ":" + endpoint.port, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Источник: Локальная сеть", color = TextMuted, fontSize = 10.sp)
            }
            Text("TCP " + endpoint.latencyMs + " мс", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(endpoint.protocol, if (endpoint.protocol == "TCP") Color(0xFF3A2B1B) else Color(0xFF133853), if (endpoint.protocol == "TCP") Amber else Blue)
            Text(endpoint.status, color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, caption: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, accent: Color) {
    Column(
        modifier.clip(RoundedCornerShape(17.dp)).background(Surface).padding(horizontal = 11.dp, vertical = 12.dp),
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
private fun FlowChips(items: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rowItems.forEach { item ->
                    val active = item == selected
                    val bg by animateColorAsState(if (active) Color(0xFF123B5A) else SurfaceRaised, label = "chip-bg")
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onSelect(item) },
                        color = bg, shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (active) Blue.copy(alpha = 0.65f) else Line)
                    ) {
                        Text(item, color = if (active) Blue else TextMuted, fontSize = 10.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(Surface)
            .then(Modifier).padding(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(borderColor.copy(alpha = 0.08f), Color.Transparent))).padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF17273B)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Public, null, tint = if (item.isTcpReachable) Green else Blue, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title.ifBlank { item.protocol }, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.host}:${item.port}", color = Color(0xFFB3C5DB), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "В избранное", tint = if (item.favorite) Red else TextMuted, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(item.protocol.uppercase(), Color(0xFF133853), Blue)
                Tag("${countryFlag(item.countryCode)} ${item.country.ifBlank { "Страна не определена" }}", Color(0xFF1A2D2A), Green)
                Spacer(Modifier.weight(1f))
                val statusColor = if (item.isTcpReachable) Green else if (item.checkStatus == "TCP недоступен") Red else Amber
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
                OutlinedButton(onClick = onCheck, modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Line), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 9.dp)) {
                    Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(15.dp), tint = Blue); Spacer(Modifier.width(5.dp)); Text("Проверить", fontSize = 11.sp, maxLines = 1, color = TextPrimary)
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(39.dp).clip(RoundedCornerShape(11.dp)).background(SurfaceRaised)) {
                    Icon(Icons.Default.ContentCopy, "Копировать ссылку", tint = Blue, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "Скрыть детали" else "Детали проверки", color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(if (expanded) "−" else "+", color = Blue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
        Modifier.fillMaxWidth().padding(vertical = 30.dp).clip(RoundedCornerShape(22.dp)).background(Surface).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF142A41)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Search, null, tint = Blue, modifier = Modifier.size(29.dp))
        }
        Text(if (loading) "Ищем конфигурации" else "Пока ничего не найдено", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(if (loading) "Загружаем источники и проверяем узлы. Это может занять немного времени." else "Запусти сканирование или измени параметры фильтра.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
        if (!loading) OutlinedButton(onClick = onScan, shape = RoundedCornerShape(12.dp)) { Text("Повторить поиск", color = Blue) }
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
