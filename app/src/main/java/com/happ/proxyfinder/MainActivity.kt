package com.happ.proxyfinder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF020304)
private val Surface = Color(0xFF080B0F)
private val SurfaceRaised = Color(0xFF10161D)
private val AccentCyan = Color(0xFF00D7FF)
private val AccentCyanDeep = Color(0xFF007C9D)
private val AccentCyanSoft = Color(0xFFB8F5FF)
private val Green = Color(0xFF45E6A5)
private val Amber = Color(0xFFFFC66D)
private val Red = Color(0xFFFF647C)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextMuted = Color(0xFFAAB5C1)
private val Line = Color(0xFF1A3540)

internal val SupportedProxyProtocols = listOf("VLESS", "SOCKS5")

internal fun isSupportedByBothApps(protocol: String): Boolean =
    protocol.trim().uppercase(java.util.Locale.ROOT) in SupportedProxyProtocols

internal fun matchesCountrySelection(item: ProxyItem, selectedCountries: Set<String>): Boolean =
    selectedCountries.isEmpty() || item.country in selectedCountries ||
        item.countryCode.uppercase(java.util.Locale.ROOT) in selectedCountries

internal fun filterProxyResults(
    items: Iterable<ProxyItem>,
    query: String,
    selectedProtocols: Set<String>,
    selectedCountries: Set<String>,
    onlyAvailable: Boolean,
    onlyFavorites: Boolean
): List<ProxyItem> {
    val normalizedQuery = query.trim()
    return items.asSequence().filter { item ->
        val haystack = "${item.host} ${item.port} ${item.protocol} ${item.source} ${item.title} ${item.country} ${item.countryCode} ${item.city}"
        (normalizedQuery.isBlank() || haystack.contains(normalizedQuery, ignoreCase = true)) &&
            (selectedProtocols.isEmpty() || selectedProtocols.any { item.protocol.equals(it, true) }) &&
            matchesCountrySelection(item, selectedCountries) &&
            (!onlyAvailable || item.isTunnelVerified) &&
            (!onlyFavorites || item.favorite)
    }.sortedWith(
        compareByDescending<ProxyItem> { it.qualityScore }
            .thenBy { it.latencyMs ?: Int.MAX_VALUE }
            .thenBy { it.host }
    ).toList()
}

internal fun rankExportableProxies(items: Iterable<ProxyItem>): List<ProxyItem> =
    items.asSequence()
        .filter { it.isExportReady && isSupportedByBothApps(it.protocol) && it.rawConfig.isNotBlank() }
        .distinctBy { it.rawConfig.trim() }
        .sortedWith(
            compareByDescending<ProxyItem> { it.qualityScore }
                .thenBy { it.latencyMs ?: Int.MAX_VALUE }
                .thenByDescending { it.checkedAt ?: 0L }
        )
        .toList()

private fun Modifier.premiumPanel(shape: RoundedCornerShape = RoundedCornerShape(18.dp)): Modifier =
    this.drawBehind {
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(AccentCyan.copy(alpha = 0.16f), AccentCyan.copy(alpha = 0.045f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.08f),
                radius = size.maxDimension * 0.92f
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx())
        )
    }
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF111820), Surface, Bg)))
        .border(BorderStroke(1.dp, Brush.linearGradient(listOf(AccentCyan.copy(alpha = 0.48f), Line, AccentCyan.copy(alpha = 0.16f)))), shape)

class MainActivity : ComponentActivity() {
    private lateinit var repository: ProxyRepository
    private var pendingHiddifyExport: List<ProxyItem> = emptyList()
    private val jsonFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    buildHiddifySingBoxConfig(rankExportableProxies(pendingHiddifyExport))
                }
                val count = withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.toByteArray(Charsets.UTF_8))
                        org.json.JSONObject(payload).getJSONArray("outbounds").length()
                    } ?: error("Не удалось открыть файл")
                }
                Toast.makeText(this@MainActivity, "Hiddify JSON сохранён: $count узлов с учётом фильтров", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка сохранения: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptSiteUpload(items: List<ProxyItem>) {
        val exportable = rankExportableProxies(items)
        if (exportable.isEmpty()) {
            Toast.makeText(this, "Нет проверенных конфигураций для публикации", Toast.LENGTH_LONG).show()
            return
        }
        val preferences = getSharedPreferences("proxy_finder", MODE_PRIVATE)
        val endpoint = EditText(this).apply {
            hint = "https://your-worker.workers.dev"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(preferences.getString("site_upload_url", ""))
        }
        val token = EditText(this).apply {
            hint = "Ключ загрузки"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
            addView(endpoint)
            addView(token)
        }
        AlertDialog.Builder(this)
            .setTitle("Выгрузка на сайт")
            .setMessage("Будут отправлены только рабочие конфигурации из текущих фильтров: ${exportable.size}.")
            .setView(layout)
            .setPositiveButton("Отправить") { _, _ ->
                val baseUrl = endpoint.text.toString().trim().trimEnd('/')
                val secret = token.text.toString().trim()
                if (!baseUrl.startsWith("https://") || secret.isBlank()) {
                    Toast.makeText(this, "Укажи HTTPS-адрес API и ключ загрузки", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                preferences.edit().putString("site_upload_url", baseUrl).apply()
                lifecycleScope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            val payload = buildSiteUploadJson(exportable)
                            val connection = java.net.URL("$baseUrl/upload").openConnection() as java.net.HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.connectTimeout = 15000
                            connection.readTimeout = 20000
                            connection.doOutput = true
                            connection.setRequestProperty("Authorization", "Bearer $secret")
                            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                            val status = connection.responseCode
                            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                                ?.bufferedReader()?.use { it.readText() }.orEmpty()
                            connection.disconnect()
                            if (status !in 200..299) error("HTTP $status: ${body.take(180)}")
                            body
                        }
                        Toast.makeText(this@MainActivity, "Каталог отправлен на сайт: ${exportable.size} конфигураций", Toast.LENGTH_LONG).show()
                    } catch (error: Exception) {
                        Toast.makeText(this@MainActivity, "Ошибка выгрузки: ${error.message?.take(150)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(2, 3, 4)
        window.navigationBarColor = android.graphics.Color.rgb(2, 3, 4)
        repository = ProxyRepository(this)
        if (getSharedPreferences("proxy_finder", MODE_PRIVATE).getBoolean("background_monitor_enabled", false)) {
            ProxyMonitorScheduler.enable(this)
            val monitorIntent = Intent(this, SpeedMonitorService::class.java).apply { action = SpeedMonitorService.ACTION_START }
            androidx.core.content.ContextCompat.startForegroundService(this, monitorIntent)
        }
        setContent {
            val scheme = darkColorScheme(
                primary = AccentCyan, onPrimary = Color(0xFF06131C), secondary = AccentCyanSoft,
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
                            val isSocks4 = item.protocol.equals("SOCKS4", ignoreCase = true)
                            val verified = item.isExportReady
                            val choices = when {
                                !verified -> arrayOf("Копировать конфигурацию")
                                isSocks4 -> arrayOf("Другие приложения / поделиться", "Копировать конфигурацию")
                                else -> arrayOf("Добавить в HAPP", "Добавить в Hiddify", "Другие приложения / поделиться", "Копировать конфигурацию")
                            }
                            AlertDialog.Builder(this)
                                .setTitle(when { !verified -> "Туннель не подтверждён"; isSocks4 -> "SOCKS4 не поддерживается HAPP"; else -> "Куда добавить конфигурацию?" })
                                .setItems(choices) { _, selected ->
                                    val which = when { !verified -> 3; isSocks4 -> selected + 2; else -> selected }
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
                            val unique = items.distinctBy { it.rawConfig.trim() }
                                .filter { it.isExportReady && it.rawConfig.isNotBlank() && !it.protocol.equals("SOCKS4", true) }
                            if (unique.isNotEmpty()) {
                                val payload = buildHappSubscription(unique)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, payload)
                                    putExtra(Intent.EXTRA_SUBJECT, "HAPP Proxy Finder · ${unique.size} конфигураций")
                                }
                                startActivity(Intent.createChooser(send, "Передать ${unique.size} прокси"))
                            } else Toast.makeText(this, "Нет проверенных туннелей для передачи в HAPP. Непроверенные узлы исключены.", Toast.LENGTH_LONG).show()
                        },
                        onCopyHappJson = { items ->
                            try {
                                val payload = buildHappVerifiedJson(items)
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HAPP verified JSON", payload))
                                val count = org.json.JSONArray(payload).length()
                                Toast.makeText(this, "HAPP JSON скопирован: $count проверенных узлов", Toast.LENGTH_LONG).show()
                            } catch (error: Exception) {
                                Toast.makeText(this, error.message ?: "Нет проверенных узлов для HAPP", Toast.LENGTH_LONG).show()
                            }
                        },
                        onCopyAllJson = { items ->
                            pendingHiddifyExport = items
                            jsonFileLauncher.launch("hiddify-singbox-filtered-${System.currentTimeMillis()}.json")
                        },
                        onUploadToSite = { items -> promptSiteUpload(items) }
                    )
                }
            }
        }
    }
}


internal fun buildSiteUploadJson(items: Iterable<ProxyItem>): String {
    val configs = rankExportableProxies(items)
    require(configs.isNotEmpty()) { "Нет проверенных конфигураций для выгрузки" }
    val array = org.json.JSONArray()
    configs.forEach { array.put(it.rawConfig.trim()) }
    return org.json.JSONObject()
        .put("configs", array)
        .put("source", "Scaner Proxy X Android")
        .toString()
}

/**
 * Builds a native Happ text subscription. Happ performs protocol-aware ping checks
 * on open and applies its own latency sort, placing unavailable nodes at the bottom.
 * Existing successful tunnel measurements provide a useful initial order before that check.
 */
internal fun buildHappSubscription(items: List<ProxyItem>): String {
    val ordered = items.asSequence()
        .filter { it.isExportReady && it.rawConfig.isNotBlank() && !it.protocol.equals("SOCKS4", ignoreCase = true) }
        .distinctBy { it.rawConfig.trim() }
        .sortedWith(
            compareBy<ProxyItem> {
                when {
                    it.isTunnelVerified -> 0
                    !it.isChecked -> 1
                    else -> 2
                }
            }.thenBy { if (it.isTunnelVerified) it.latencyMs ?: Int.MAX_VALUE else Int.MAX_VALUE }
                .thenBy { it.host.lowercase(java.util.Locale.ROOT) }
        )
        .map { namedConfigForImport(it) }
        .toList()

    val directives = listOf(
        "#subscription-ping-onopen-enabled: 1",
        "#subscriptions-sort-type: ping",
        "#ping-type: proxy",
        "#check-url-via-proxy: https://cp.cloudflare.com/generate_204"
    )
    return (directives + ordered).joinToString("\n")
}

internal fun normalizeProxySchemeForHapp(config: String): String {
    val value = config.removePrefix("\uFEFF").trim()
    if (value.isEmpty()) return value
    val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(value) ?: return value
    val originalScheme = match.groupValues[1]
    val scheme = originalScheme.lowercase(java.util.Locale.ROOT)
    val canonicalScheme = if (scheme == "socks5") "socks" else scheme
    // The regex consumes the complete `scheme://` prefix; restore the delimiter.
    return if (canonicalScheme == originalScheme) value
    else canonicalScheme + "://" + value.substring(match.range.last + 1)
}

/** Returns a clean, HAPP-compatible configuration string for single-item copy actions. */
private fun encodeUriComponent(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** Stable node label: location only, without host, port, protocol or source metadata. */
internal fun proxyDisplayName(item: ProxyItem): String {
    val code = item.countryCode.trim().uppercase(java.util.Locale.ROOT)
    val flag = countryFlag(code)
    val country = item.country.trim()
    if (country.isBlank()) return "❗ Геолокация не определена"
    return buildString {
        if (!isSupportedByBothApps(item.protocol)) append("❗ ")
        append(flag)
        append(" ")
        append(country)
        if (item.city.isNotBlank()) {
            append(" · ")
            append(item.city.trim())
        }
    }
}

internal fun namedConfigForImport(item: ProxyItem): String {
    val normalized = normalizeProxySchemeForHapp(item.rawConfig)
    val displayLabel = proxyDisplayName(item)
    if (!item.protocol.equals("SOCKS5", ignoreCase = true) || !normalized.startsWith("socks://", ignoreCase = true)) {
        // Preserve protocols and configurations that have no existing node label.
        // When a feed already supplies a URI fragment, replace only that label with
        // the location-only name (flag, country and city).
        return if (normalized.contains("://") && displayLabel.isNotBlank()) {
            normalized.substringBefore("#") + "#" + encodeUriComponent(displayLabel)
        } else normalized
    }

    // Build the endpoint from parsed fields so full-Base64 SOCKS imports are normalized too.
    val credentials = parseProxyCredentials(item.rawConfig)
    val userInfo = credentials?.let { "${encodeUriComponent(it.first)}:${encodeUriComponent(it.second)}" } ?: ":"
    val host = if (item.host.contains(':')) "[${item.host}]" else item.host
    val endpoint = "socks://$userInfo@$host:${item.port}"

    val code = item.countryCode.trim().uppercase(java.util.Locale.ROOT)
    val label = if (code.matches(Regex("[A-Z]{2}")) && code in java.util.Locale.getISOCountries()) {
        val countryName = item.country.ifBlank {
            java.util.Locale("", code).getDisplayCountry(java.util.Locale.getDefault()).ifBlank { code }
        }
        buildString {
            append(countryFlag(code))
            append(" ")
            append(countryName)
            if (item.city.isNotBlank()) {
                append(" · ")
                append(item.city.trim())
            }
        }
    } else {
        proxyDisplayName(item)
    }
    return if (label.isBlank()) endpoint else "$endpoint#${encodeUriComponent(label)}"
}

@Composable
private fun ProxyFinderScreen(repo: ProxyRepository, onShare: (ProxyItem) -> Unit, onCopy: (String) -> Unit, onBulkShare: (List<ProxyItem>) -> Unit, onCopyHappJson: (List<ProxyItem>) -> Unit, onCopyAllJson: (List<ProxyItem>) -> Unit, onUploadToSite: (List<ProxyItem>) -> Unit) {
    var allItems by remember { mutableStateOf(emptyList<ProxyItem>()) }
    var totalStoredCount by remember { mutableIntStateOf(0) }
    var totalVerifiedCount by remember { mutableIntStateOf(0) }
    var displayLimit by remember { mutableIntStateOf(100) }
    var showDisplayLimitMenu by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(emptyList<ProxyItem>()) }
    var loading by remember { mutableStateOf(false) }
    var checkingCount by remember { mutableIntStateOf(0) }
    var geoLoading by remember { mutableStateOf(false) }
    var sourceStatus by remember { mutableStateOf(if (allItems.isEmpty()) "Готов к сканированию" else "Сохранённые результаты загружены") }
    var sourceProgress by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selectedProtocols by remember { mutableStateOf(setOf<String>()) }
    var selectedCountries by remember { mutableStateOf(emptySet<String>()) }
    var onlyAvailable by remember { mutableStateOf(false) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var backgroundMonitor by remember {
        mutableStateOf(context.getSharedPreferences("proxy_finder", 0).getBoolean("background_monitor_enabled", false))
    }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("proxy_finder", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "background_monitor_enabled") {
                backgroundMonitor = preferences.getBoolean("background_monitor_enabled", false)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
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
        val stats = withContext(Dispatchers.IO) { repo.snapshotStats() }
        allItems = restored
        totalStoredCount = stats.first
        totalVerifiedCount = stats.second
        sourceStatus = if (stats.first == 0) "Готов к сканированию" else "Загружено ${restored.size} последних узлов · всего в базе ${stats.first}"

        // GeoIP was previously implemented but never called: enrich restored records too.
        if (restored.any { it.country.isBlank() || it.city.isBlank() }) {
            geoLoading = true
            sourceStatus = "Определяем страну и город сохранённых узлов"
            try {
                val resolved = repo.resolveCountries(restored) { geoItem ->
                    withContext(Dispatchers.Main) {
                        allItems = mergeResolvedGeoResults(allItems, listOf(geoItem))
                        sourceProgress = "Геолокация: ${allItems.count { it.country.isNotBlank() }} узлов определено"
                    }
                    withContext(Dispatchers.IO) { repo.saveSnapshot(listOf(geoItem)) }
                }
                allItems = resolved
                withContext(Dispatchers.IO) { repo.saveSnapshot(resolved) }
                sourceStatus = "Геолокация сохранённых узлов обновлена"
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                sourceStatus = "Геолокация частично недоступна"
                sourceProgress = error.message?.take(120) ?: "Провайдеры GeoIP не ответили"
            } finally {
                geoLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        var cursor = 0
        while (true) {
            delay(15_000)
            if (autoRefresh && !loading && !autoCheckRunning && allItems.isNotEmpty()) {
                val candidates = allItems.filter { it.needsNativeTunnelCheck }.ifEmpty { allItems.filter { it.isTunnelVerified } }
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
            val stats = repo.snapshotStats()
            withContext(Dispatchers.Main) {
                totalStoredCount = stats.first
                totalVerifiedCount = stats.second
            }
        }
    }

    suspend fun persistAndSave(items: List<ProxyItem>) {
        allItems = items
        val favorites = items.filter { it.favorite }.map { it.id }.toSet()
        withContext(Dispatchers.IO) {
            repo.saveSnapshot(items)
            repo.saveFavorites(favorites)
            val stats = repo.snapshotStats()
            withContext(Dispatchers.Main) {
                totalStoredCount = stats.first
                totalVerifiedCount = stats.second
            }
        }
    }

    fun checkNextBatch() {
        if (loading || autoCheckRunning) return
        val batch = allItems.filter { it.needsNativeTunnelCheck }
        if (batch.isEmpty()) {
            Toast.makeText(context, "Нет новых SOCKS4/SOCKS5/HTTP/HTTPS/VLESS TCP+TLS узлов для полной проверки. Для VMess, Trojan, Shadowsocks, Hysteria2 и TUIC пока нужен отдельный протокольный модуль.", Toast.LENGTH_LONG).show()
            return
        }
        autoCheckRunning = true
        checkingCount = batch.size
        sourceStatus = "Проверяем все ожидающие туннели · ${batch.size}"
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
                sourceStatus = "Проверено: ${updates.size} · рабочих туннелей: $verifiedNow"
                sourceProgress = "Обработаны все ожидавшие проверки узлы из текущего списка; неподдерживаемые протоколы не выдаются за рабочие."
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
        val scanAllProtocols = scanFiltersSelected.isEmpty() || scanFiltersSelected.size == SupportedProxyProtocols.size
        if (scanScope == "Весь мир") selectedProtocols = if (scanAllProtocols) emptySet() else scanFiltersSelected
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
        if (scanScope == "Локальная сеть") {
            sourceStatus = "Сканируем локальную Wi-Fi сеть"
            sourceProgress = "Определяем активную Wi-Fi подсеть"
            localResults = emptyList()
            scope.launch {
                try {
                    val result = repo.scanLocalNetwork { progress ->
                        withContext(Dispatchers.Main) { sourceProgress = progress }
                    }
                    localResults = result.endpoints
                    sourceStatus = "Локальная сеть · найдено ${result.endpoints.size}"
                    sourceProgress = result.message
                    if (result.endpoints.isEmpty()) errorMessage = result.message
                } catch (_: Exception) {
                    sourceStatus = "Локальное сканирование прервано"
                    errorMessage = "Не удалось проверить локальную Wi-Fi сеть. Проверь подключение и повтори попытку."
                } finally {
                    loading = false
                    checkingCount = 0
                }
            }
            return
        }
        sourceStatus = "Подключаемся к открытым источникам"
        sourceProgress = "Потоковый поиск · HTTPS-туннель проверяется сразу при обнаружении · 12 параллельных проверок"
        scope.launch {
            val savedFavorites = withContext(Dispatchers.IO) { repo.loadFavorites() }
            val channel = Channel<ProxyItem>(capacity = 128)
            val geoChannel = Channel<ProxyItem>(capacity = 256)
            val geoWorker = scope.launch(Dispatchers.IO) {
                val pendingGeo = ArrayList<ProxyItem>(20)
                suspend fun resolveGeoBatch(batch: List<ProxyItem>) {
                    repo.resolveCountries(batch) { geoItem ->
                        withContext(Dispatchers.Main) {
                            allItems = mergeResolvedGeoResults(allItems, listOf(geoItem))
                            sourceProgress = "Геолокация: ${allItems.count { it.country.isNotBlank() }} узлов определено"
                        }
                        repo.saveSnapshot(listOf(geoItem))
                    }
                }
                for (candidate in geoChannel) {
                    pendingGeo.add(candidate)
                    if (pendingGeo.size >= 20) {
                        resolveGeoBatch(pendingGeo.toList())
                        pendingGeo.clear()
                    }
                }
                if (pendingGeo.isNotEmpty()) resolveGeoBatch(pendingGeo.toList())
            }
            var discoveredCount = 0
            var checkedCount = 0
            var verifiedCount = 0
            val workers = List(12) {
                scope.launch {
                    for (candidate in channel) {
                        try {
                            val checked = repo.check(candidate)
                            withContext(Dispatchers.IO) { repo.saveSnapshot(listOf(checked)) }
                            val previous = allItems.firstOrNull { it.rawConfig.trim() == checked.rawConfig.trim() }
                            val merged = checked.copy(favorite = previous?.favorite ?: (checked.id in savedFavorites))
                            allItems = (allItems.filterNot { it.rawConfig.trim() == checked.rawConfig.trim() } + merged).takeLast(5000)
                            geoChannel.send(merged)
                            checkedCount++
                            if (checked.isTunnelVerified) verifiedCount++
                            if (checkedCount % 20 == 0) {
                                sourceProgress = "Найдено $discoveredCount · проверено $checkedCount · рабочих $verifiedCount · очередь $checkingCount"
                            }
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            errorMessage = "Ошибка сохранения/проверки узла: ${error.message?.take(90) ?: "неизвестная ошибка"}"
                        } finally {
                            checkingCount = (checkingCount - 1).coerceAtLeast(0)
                        }
                    }
                }
            }
            try {
                val requested = if (scanAllProtocols) SupportedProxyProtocols.toSet() else scanFiltersSelected.filter { isSupportedByBothApps(it) }.toSet()
                val fetched = repo.fetchStreaming(
                    requested,
                    onSource = { name ->
                        withContext(Dispatchers.Main) {
                            sourceProgress = if (scanAllProtocols) "Все поддерживаемые типы · $name" else "Выбранные протоколы · $name"
                        }
                    },
                    onItem = { item ->
                        if (isSupportedByBothApps(item.protocol) &&
                            (scanAllProtocols || scanFiltersSelected.isEmpty() || scanFiltersSelected.any { it.equals(item.protocol, true) })) {
                        withContext(Dispatchers.Main) {
                            discoveredCount++
                            checkingCount++
                            if (discoveredCount == 1 || discoveredCount % 20 == 0) {
                                sourceProgress = "Обнаружено $discoveredCount · передано на проверку · в очереди $checkingCount"
                            }
                        }
                        channel.send(item)
                        }
                    }
                )
                channel.close()
                workers.joinAll()
                geoChannel.close()
                geoWorker.join()
                val latest = withContext(Dispatchers.IO) { repo.loadSnapshot(5000) }
                // Resolve IP geolocation after discovery; update cards and persist each result immediately.
                geoLoading = true
                sourceStatus = "Поиск завершён · определяем страну и город узлов"
                try {
                    val resolved = repo.resolveCountries(latest) { geoItem ->
                        withContext(Dispatchers.Main) {
                            allItems = mergeResolvedGeoResults(allItems, listOf(geoItem))
                            sourceProgress = "Определено местоположение: ${allItems.count { it.country.isNotBlank() }}"
                        }
                        withContext(Dispatchers.IO) { repo.saveSnapshot(listOf(geoItem)) }
                    }
                    allItems = resolved
                    withContext(Dispatchers.IO) { repo.saveSnapshot(resolved) }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    sourceProgress = "GeoIP временно недоступен: ${error.message?.take(90) ?: "нет ответа"}"
                } finally {
                    geoLoading = false
                }
                val stats = withContext(Dispatchers.IO) { repo.snapshotStats() }
                totalStoredCount = stats.first
                totalVerifiedCount = stats.second
                sourceStatus = "Поиск, проверка и геолокация завершены"
                sourceProgress = "Найдено $discoveredCount · проверено $checkedCount · рабочих $verifiedCount · всего в базе $totalStoredCount"
                if (fetched.sourceErrors.isNotEmpty()) {
                    sourceProgress += " · ошибок источников: ${fetched.sourceErrors.size}"
                }
                if (discoveredCount == 0) {
                    errorMessage = if (fetched.sourceErrors.isNotEmpty()) {
                        "Не удалось получить конфигурации из части источников. Проверь интернет и повтори поиск."
                    } else {
                        "Источники не содержат новых уникальных конфигураций выбранных протоколов."
                    }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                channel.close()
                geoChannel.close()
                geoWorker.cancel()
                workers.forEach { it.cancel() }
                sourceStatus = "Потоковое сканирование прервано"
                errorMessage = "Сканирование остановлено: ${error.message?.take(100) ?: "ошибка обработки"}. Уже проверенные записи сохранены в SQLite."
            } finally {
                channel.close()
                geoChannel.close()
                geoWorker.cancel()
                workers.forEach { it.cancel() }
                loading = false
                checkingCount = 0
            }
        }
    }

    val protocols = remember(allItems) { allItems.map { it.protocol }.distinct().sorted() }
    val countries = remember(allItems) { allItems.asSequence().map { it.country }.filter { it.isNotBlank() }.distinct().sorted().toList() }
    LaunchedEffect(allItems, query, selectedProtocols, selectedCountries, onlyAvailable, onlyFavorites) {
        delay(120)
        visible = withContext(Dispatchers.Default) {
            filterProxyResults(allItems, query, selectedProtocols, selectedCountries, onlyAvailable, onlyFavorites)
        }
    }
    val displayedItems = remember(visible, displayLimit) { visible.take(displayLimit) }
    // Export uses the complete active filtered result set, not just the rendered page.
    val exportItems = remember(visible) { rankExportableProxies(visible) }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050709), Bg, Color(0xFF010203))))) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item(key = "topbar") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 1.dp)
                ) {
                    Box(
                        Modifier.size(50.dp).clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(AccentCyan, AccentCyanDeep)))
                            .border(1.dp, AccentCyanSoft.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
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
                        Text("SCANER PROXY X", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.25.sp)
                        Text("NETWORK INTELLIGENCE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(AccentCyan.copy(alpha = 0.10f))
                            .border(1.dp, AccentCyan.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Radar, null, tint = AccentCyan, modifier = Modifier.size(14.dp))
                            Text("LIVE SCANNER", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                        }
                    }
                }
            }
            item(key = "hero") {
                HeroPanel(loading = loading, checkingCount = checkingCount, onScan = { scan() }, enabled = !loading)
            }
            item(key = "backgroundMonitor") {
                Column(
                    Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(17.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Фоновая проверка", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("SOCKS4/5, HTTP(S), VLESS TCP+TLS: проверка HTTPS-туннеля; остальные: только TCP-доступность", color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        Switch(
                            checked = backgroundMonitor,
                            onCheckedChange = { enabled ->
                                backgroundMonitor = enabled
                                context.getSharedPreferences("proxy_finder", 0).edit()
                                    .putBoolean("background_monitor_enabled", enabled).apply()
                                if (enabled) ProxyMonitorScheduler.enable(context)
                                else ProxyMonitorScheduler.disable(context)
                                val serviceIntent = Intent(context, SpeedMonitorService::class.java).apply {
                                    action = if (enabled) SpeedMonitorService.ACTION_START else SpeedMonitorService.ACTION_STOP
                                }
                                if (enabled) androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                                else context.startService(serviceIntent)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = AccentCyan.copy(alpha = 0.35f))
                        )
                    }
                    Text("Частая проверка работает через сервис, а WorkManager подстраховывает мониторинг после остановки сервиса и повторяет цикл примерно каждые 15 минут, когда Android разрешает фоновую работу и есть сеть.", color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
            item(key = "scanMode") {
                Column(
                    Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(18.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Режим поиска", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    FlowChips(items = listOf("Весь мир", "Локальная сеть", "Диапазон IP"), selected = scanScope, onSelect = {
                        if (!loading) {
                            scanScope = it
                            localResults = emptyList()
                            errorMessage = ""
                        }
                    })
                    Text(
                        when (scanScope) {
                            "Весь мир" -> "Поиск опубликованных конфигураций из открытых источников разных стран."
                            "Локальная сеть" -> "Поиск открытых SOCKS5 и HTTP CONNECT портов в текущей Wi-Fi сети. Проверяются только локальные адреса."
                            else -> "Проверка заданных IPv4/CIDR и диапазонов вида 192.168.1.1-192.168.1.100. До 4 диапазонов по 256 IP, суммарно до 1024 уникальных адресов."
                        },
                        color = TextMuted, fontSize = 12.sp, lineHeight = 15.sp
                    )
                    if (scanScope == "Диапазон IP") {
                        OutlinedTextField(
                            value = targetRange,
                            onValueChange = { targetRange = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("IPv4 / CIDR / начало-конец · по одному на строку") },
                            minLines = 2,
                            maxLines = 4,
                            enabled = !loading,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan, focusedLabelColor = AccentCyan,
                                unfocusedBorderColor = Line, focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        OutlinedTextField(
                            value = targetPorts,
                            onValueChange = { targetPorts = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("TCP-порты через запятую (до 8)") },
                            setSingleLine(true),
                            enabled = !loading,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan, focusedLabelColor = AccentCyan,
                                unfocusedBorderColor = Line, focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Text("Форматы: 192.168.1.10-192.168.1.80, 192.168.1.0/24 или одиночный IP. До 4 диапазонов, максимум 256 адресов в каждом и 1024 уникальных IP суммарно; до 8 TCP-портов. Пересечения объединяются. Только свои или письменно разрешённые сети. Открытый порт не доказывает наличие прокси.", color = Amber, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                    if (scanScope == "Весь мир") {
                        val scanFilters = SupportedProxyProtocols
                        Text("Протоколы для поиска", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(if (scanFiltersSelected.isEmpty() || scanFiltersSelected.size == scanFilters.size) "Все поддерживаемые типы включены" else "Выбрано: ${scanFiltersSelected.joinToString(", ")}", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Доступны только общие типы HAPP и Hiddify: VLESS и SOCKS5.", color = TextMuted, fontSize = 11.sp)
                        MultiSelectChips(
                            items = scanFilters,
                            selected = scanFiltersSelected,
                            onChange = { scanFiltersSelected = it; errorMessage = "" },
                            allLabel = "Все поддерживаемые",
                            enabled = !loading
                        )
                    }
                }
            }
            item(key = "localResultsHeader") {
                if (scanScope != "Весь мир") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(if (scanScope == "Локальная сеть") "Результаты локальной сети" else "Результаты проверки диапазонов", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (!loading && localResults.isEmpty() && errorMessage.isBlank()) {
                            Text("Открытые порты не обнаружены. Проверь подключение, диапазон и список портов. Проверяй только сети, на сканирование которых у тебя есть разрешение.", color = TextMuted, fontSize = 13.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
            if (scanScope != "Весь мир") {
                items(localResults, key = { "endpoint-${it.host}-${it.port}" }, contentType = { "endpoint" }) { endpoint ->
                    LocalEndpointCard(endpoint)
                }
            }
            item(key = "autoRefresh") {
                Row(
                    Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(17.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(SurfaceRaised), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Refresh, null, tint = AccentCyan, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Автообновление · 15 сек", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(if (autoRefresh) "Проверяется следующий узел по очереди" else "Автопроверка приостановлена", color = TextMuted, fontSize = 11.sp)
                    }
                    Switch(checked = autoRefresh, onCheckedChange = {
                        autoRefresh = it
                        context.getSharedPreferences("proxy_finder", 0).edit().putBoolean("auto_refresh_enabled", it).apply()
                    },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = AccentCyan.copy(alpha = 0.35f)))
                }
            }
            item(key = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("Найдено", totalStoredCount.toString(), "в SQLite", Icons.Default.Public, Modifier.weight(1f), AccentCyan)
                    MetricCard("Рабочих", totalVerifiedCount.toString(), "подтверждённый туннель", Icons.Default.CheckCircle, Modifier.weight(1f), Green)
                    MetricCard("Средний пинг", averageLatency(allItems), "проверенные узлы", Icons.Default.Speed, Modifier.weight(1f), AccentCyanSoft)
                }
            }
            item(key = "status") {
                Row(
                    Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(15.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (loading || geoLoading) AccentCyan else if (errorMessage.isNotBlank()) Amber else Green))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(sourceStatus, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (sourceProgress.isNotBlank()) sourceProgress else "Автопроверка отключена или работает с интервалом 15 секунд", color = TextMuted, fontSize = 11.sp)
                    }
                    if (loading || geoLoading) {
                        CircularProgressIndicator(Modifier.size(19.dp), color = AccentCyan, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(17.dp))
                    }
                }
            }
            item(key = "error") {
                AnimatedVisibility(visible = errorMessage.isNotBlank(), enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF17090D)).border(1.dp, Red.copy(alpha = 0.28f), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Close, null, tint = Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage, color = Color(0xFFFFDDE2), fontSize = 13.sp, lineHeight = 17.sp)
                    }
                }
            }
            item(key = "search") {
                Row(
                    Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(16.dp)).padding(start = 13.dp, end = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = AccentCyan, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 15.sp),
                        cursorBrush = Brush.verticalGradient(listOf(AccentCyan, AccentCyanSoft)),
                        modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                        decorationBox = { inner -> Box {
                            if (query.isEmpty()) Text("IP, домен, протокол или страна", color = TextMuted, fontSize = 14.sp)
                            inner()
                        } }
                    )
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить", tint = TextMuted) }
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Default.Tune, "Фильтры", tint = if (showFilters || selectedProtocols.isNotEmpty() || selectedCountries.isNotEmpty()) AccentCyan else TextMuted)
                    }
                }
            }
            item(key = "filters") {
                AnimatedVisibility(visible = showFilters, enter = fadeIn(tween(180)) + slideInVertically { -it / 3 }, exit = fadeOut(tween(140)) + slideOutVertically { -it / 3 }) {
                    Column(Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(17.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Фильтры результатов", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text("Протокол", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        MultiSelectChips(items = protocols, selected = selectedProtocols, onChange = { selectedProtocols = it }, allLabel = "Все поддерживаемые")
                        Text("Страны (можно несколько)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        MultiSelectChips(items = countries, selected = selectedCountries, onChange = { selectedCountries = it }, allLabel = "Все страны")
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
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan.copy(alpha = 0.15f), contentColor = TextPrimary)
                ) {
                    if (autoCheckRunning) CircularProgressIndicator(Modifier.size(17.dp), color = AccentCyan, strokeWidth = 2.dp)
                    else Icon(Icons.Default.NetworkCheck, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(if (autoCheckRunning) "Проверка туннелей…" else "Проверить все ожидающие", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("$pendingNative SOCKS4/SOCKS5/HTTP/HTTPS/VLESS TCP+TLS требуют проверки или повторной проверки", fontSize = 11.sp, color = TextMuted)
                    }
                }
                Text("Проверка выполняет протокольный handshake и реальный HTTPS-запрос для SOCKS/HTTP и VLESS TCP+TLS. Другие транспорты пока отмечаются как неподдерживаемые.", color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
            }
            item(key = "bulkImport") {
                if (allItems.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(
                        onClick = { onBulkShare(exportItems) },
                        enabled = exportItems.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF06131C)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                            Text("Поделиться конфигурациями", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${exportItems.size} узлов · Happ проверит и отсортирует по пингу", fontSize = 11.sp, color = Color(0xFF06151B))
                        }
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(17.dp))
                    }
                    Text("Для Happ включены проверка при открытии и сортировка по задержке: быстрые узлы выше, недоступные — внизу. Перед первой проверки сохраняется предварительный порядок по результатам сканера.", color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 3.dp))
                    OutlinedButton(
                        onClick = { onCopyHappJson(exportItems) },
                        enabled = exportItems.any { it.isExportReady && (it.protocol.equals("SOCKS5", true) || it.protocol.equals("VLESS", true)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = Green)
                        Spacer(Modifier.width(8.dp))
                        Text("Скопировать проверенный JSON для HAPP", color = Green)
                    }
                    OutlinedButton(onClick = { onCopyAllJson(exportItems) }, enabled = exportItems.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.ContentCopy, null, tint = AccentCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Сохранить sing-box JSON для Hiddify", color = AccentCyan)
                    }
                    OutlinedButton(onClick = { onUploadToSite(exportItems) }, enabled = exportItems.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.CloudUpload, null, tint = Green)
                        Spacer(Modifier.width(8.dp))
                        Text("Выгрузить рабочие конфигурации на сайт", color = Green)
                    }
                    }
                }
            }
            item(key = "resultHeader") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Результаты", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Сначала самые быстрые по пингу", color = TextMuted, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(SurfaceRaised).padding(horizontal = 11.dp, vertical = 7.dp)) {
                            Text("${visible.size} узлов", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Box {
                            TextButton(onClick = { showDisplayLimitMenu = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                                Text("Показывать: ${if (displayLimit >= 5000) "Все" else displayLimit}", color = AccentCyanSoft, fontSize = 11.sp)
                            }
                            DropdownMenu(expanded = showDisplayLimitMenu, onDismissRequest = { showDisplayLimitMenu = false }) {
                                listOf(25, 50, 100, 200, 500, 1000, 5000).forEach { limit ->
                                    DropdownMenuItem(
                                        text = { Text(if (limit == 5000) "Все (до 5000)" else "$limit") },
                                        onClick = {
                                            displayLimit = limit
                                            showDisplayLimitMenu = false
                                        }
                                    )
                                }
                            }
                        }
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
                        Text("Показать ещё ${minOf(100, visible.size - displayedItems.size)} · осталось ${visible.size - displayedItems.size}", color = AccentCyan)
                    }
                }
            }
            item(key = "disclaimer") {
                Text(
                    "В HAPP JSON включаются только подтверждённые HTTPS-туннели SOCKS5 и VLESS TCP+TLS. Полный HTTPS-туннель проверяется у SOCKS4/SOCKS5/HTTP/HTTPS и VLESS TCP+TLS. Для VMess, Trojan, Shadowsocks, Hysteria2, TUIC и неподдерживаемых транспортов VLESS рабочий туннель не подтверждается.",
                    color = TextMuted.copy(alpha = 0.75f), fontSize = 11.sp, lineHeight = 14.sp,
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
        Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(24.dp)).padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (loading) AccentCyan else Green))
                    Text(
                        if (loading) "СКАНЕР АКТИВЕН" else "ГОТОВ К СКАНИРОВАНИЮ",
                        color = if (loading) AccentCyan else Green,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.35.sp
                    )
                }
                Text("Найди свой\nбыстрый узел.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 28.sp)
                Text("Публичные прокси-конфигурации для HAPP", color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(108.dp).clip(CircleShape).background(AccentCyan.copy(alpha = 0.035f + glow * 0.045f)))
                androidx.compose.foundation.Canvas(Modifier.size(100.dp)) {
                    val stroke = 1.dp.toPx()
                    val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                    drawCircle(color = AccentCyan.copy(alpha = 0.18f + glow * 0.15f), radius = size.minDimension * 0.48f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 1.4f))
                    drawCircle(color = AccentCyan.copy(alpha = 0.08f), radius = size.minDimension * 0.31f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawLine(color = AccentCyan.copy(alpha = 0.08f), start = androidx.compose.ui.geometry.Offset(size.width / 2, 0f), end = androidx.compose.ui.geometry.Offset(size.width / 2, size.height), strokeWidth = stroke)
                    drawLine(color = AccentCyan.copy(alpha = 0.08f), start = androidx.compose.ui.geometry.Offset(0f, size.height / 2), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2), strokeWidth = stroke)
                    rotate(28f) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(Color.Transparent, AccentCyan.copy(alpha = glow), Color.Transparent)),
                            startAngle = 0f,
                            sweepAngle = 112f,
                            useCenter = true
                        )
                    }
                    drawCircle(color = AccentCyanSoft, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height * 0.34f))
                    drawCircle(color = Green, radius = 2.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.31f, size.height * 0.63f))
                    drawCircle(color = AccentCyan.copy(alpha = 0.8f), radius = 2.dp.toPx(), center = center)
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
                containerColor = AccentCyan,
                contentColor = Color(0xFF06131C),
                disabledContainerColor = SurfaceRaised,
                disabledContentColor = TextMuted
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF06131C), strokeWidth = 2.dp)
            else Icon(Icons.Default.Radar, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Text(if (loading) "Сканирование выполняется…" else "Запустить поиск", fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, tint = AccentCyan, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("Режим и внешние протоколы настраиваются ниже", color = TextMuted, fontSize = 11.sp)
            if (loading && checkingCount > 0) {
                Spacer(Modifier.weight(1f))
                Text("$checkingCount", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LocalEndpointCard(endpoint: OpenEndpoint) {
    Column(
        Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(15.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NetworkCheck, null, tint = if (endpoint.protocol == "TCP") Amber else Green, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(endpoint.host + ":" + endpoint.port, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Источник: диапазон IP", color = TextMuted, fontSize = 11.sp)
            }
            Text("TCP " + endpoint.latencyMs + " мс", color = AccentCyanSoft, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(endpoint.protocol, if (endpoint.protocol == "TCP") SurfaceRaised else Color(0xFF102A34), if (endpoint.protocol == "TCP") Amber else AccentCyan)
            Text(endpoint.status, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, caption: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, accent: Color) {
    Column(
        modifier.premiumPanel(RoundedCornerShape(17.dp)).padding(horizontal = 11.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
            Text(title, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedContent(targetState = value, transitionSpec = { (fadeIn(tween(180)) + slideInVertically { it / 3 }).togetherWith(fadeOut(tween(120)) + slideOutVertically { -it / 3 }) }, label = "metric") { current ->
            Text(current, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(caption, color = TextMuted.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MultiSelectChips(items: List<String>, selected: Set<String>, onChange: (Set<String>) -> Unit, allLabel: String, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = selected.isEmpty(), enabled = enabled, onClick = { onChange(emptySet()) }, label = { Text(allLabel) }, colors = FilterChipDefaults.filterChipColors(containerColor = SurfaceRaised, labelColor = TextMuted, selectedContainerColor = AccentCyan.copy(alpha = 0.15f), selectedLabelColor = AccentCyan))
            FilterChip(selected = selected.size == items.size, enabled = enabled, onClick = { onChange(if (selected.size == items.size) emptySet() else items.toSet()) }, label = { Text("Выбрать все") }, colors = FilterChipDefaults.filterChipColors(containerColor = SurfaceRaised, labelColor = TextMuted, selectedContainerColor = AccentCyan.copy(alpha = 0.15f), selectedLabelColor = AccentCyan))
        }
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    val active = item in selected
                    FilterChip(selected = active, enabled = enabled, onClick = { onChange(if (active) selected - item else selected + item) }, label = { Text(item, maxLines = 1) }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(containerColor = SurfaceRaised, labelColor = TextMuted, selectedContainerColor = AccentCyan.copy(alpha = 0.15f), selectedLabelColor = AccentCyan))
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
                    val bg by animateColorAsState(if (active) AccentCyan.copy(alpha = 0.14f) else SurfaceRaised, label = "chip-bg")
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onSelect(item) },
                        color = bg, shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (active) AccentCyan.copy(alpha = 0.65f) else Line)
                    ) {
                        Text(item, color = if (active) AccentCyan else TextMuted, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
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
        item.isTunnelVerified -> Green.copy(alpha = 0.3f)
        item.checkStatus.startsWith("TCP доступен") -> Amber.copy(alpha = 0.3f)
        item.checkStatus == "TCP недоступен" -> Red.copy(alpha = 0.2f)
        else -> Line
    }
    Column(
        Modifier.fillMaxWidth().premiumPanel(RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(borderColor.copy(alpha = 0.08f), Color.Transparent))).padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(SurfaceRaised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Public, null, tint = if (item.isTunnelVerified) Green else if (item.checkStatus.startsWith("TCP доступен")) Amber else AccentCyan, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(proxyDisplayName(item), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.host}:${item.port}", color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onFavorite, modifier = Modifier.size(40.dp)) {
                    Icon(if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "В избранное", tint = if (item.favorite) Red else TextMuted, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(item.protocol.uppercase(), AccentCyan.copy(alpha = 0.08f), AccentCyan)
                Spacer(Modifier.weight(1f))
                val statusColor = if (item.isTunnelVerified) Green else if (item.checkStatus == "TCP недоступен") Red else Amber
                Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                Text(when { item.isTunnelVerified -> "Прокси проверен"; item.checkStatus.startsWith("TCP доступен") -> "TCP доступен"; item.checkStatus == "TCP недоступен" -> "Недоступен"; else -> "Не проверен" }, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallMetric(
                    "Пинг",
                    item.latencyMs?.let { if (item.isTunnelVerified) "$it мс" else if (item.checkStatus.startsWith("TCP доступен")) "$it мс" else "—" } ?: "—",
                    Icons.Default.Speed, Modifier.weight(1f)
                )
                SmallMetric("Оценка", if (item.qualityScore > 5) "${item.qualityScore}/100" else "—", Icons.Default.CheckCircle, Modifier.weight(1f))
                SmallMetric("Страна", if (item.countryCode.isNotBlank()) countryFlag(item.countryCode) + " " + item.countryCode.uppercase() else "🏳️", Icons.Default.LocationOn, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onShare, modifier = Modifier.weight(1.1f), shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 9.dp)) {
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Передать", fontSize = 12.sp, maxLines = 1)
                }
                OutlinedButton(onClick = onCheck, enabled = item.supportsNativeTunnelCheck, modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Line), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 9.dp)) {
                    Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(15.dp), tint = if (item.supportsNativeTunnelCheck) AccentCyan else Amber); Spacer(Modifier.width(5.dp)); Text(if (item.supportsNativeTunnelCheck) "Проверить" else "Нужен core", fontSize = 11.sp, maxLines = 1, color = TextPrimary)
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(AccentCyan.copy(alpha = 0.10f)).border(1.dp, AccentCyan.copy(alpha = 0.32f), RoundedCornerShape(11.dp))) {
                    Icon(Icons.Default.ContentCopy, "Копировать ссылку", tint = AccentCyan, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "Скрыть детали" else "Детали проверки", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (expanded) "−" else "+", color = AccentCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
        Text(text, color = foreground, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallMetric(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(SurfaceRaised).padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = TextMuted, modifier = Modifier.size(11.dp))
            Text(title, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(value, color = TextPrimary.copy(alpha = 0.88f), fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun EmptyState(loading: Boolean, onScan: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp).premiumPanel(RoundedCornerShape(22.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(SurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Search, null, tint = AccentCyan, modifier = Modifier.size(29.dp))
        }
        Text(if (loading) "Ищем конфигурации" else "Пока ничего не найдено", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(if (loading) "Загружаем источники и проверяем узлы. Это может занять немного времени." else "Запусти сканирование или измени параметры фильтра.", color = TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
        if (!loading) OutlinedButton(onClick = onScan, shape = RoundedCornerShape(12.dp)) { Text("Повторить поиск", color = AccentCyan) }
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