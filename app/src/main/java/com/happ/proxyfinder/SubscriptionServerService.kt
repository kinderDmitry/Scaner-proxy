package com.happ.proxyfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

/** Serves the live HAPP subscription on-device and can expose it through a public Cloudflare Quick Tunnel. */
private fun subscriptionCountryFlag(code: String): String {
    if (code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return "🌐"
    val upper = code.uppercase(java.util.Locale.ROOT)
    return String(Character.toChars(0x1F1E6 + (upper[0] - 'A'))) +
        String(Character.toChars(0x1F1E6 + (upper[1] - 'A')))
}

class SubscriptionServerService : Service() {
    private var server: SubscriptionHttpServer? = null
    private var tunnelProcess: Process? = null
    private var tunnelReader: Thread? = null
    private lateinit var repository: ProxyRepository
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        repository = ProxyRepository(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Сервер подписки", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_INTERNET -> {
                stopTunnel()
                if (server == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification("Локальный сервер работает · внешний доступ выключен")
                    )
                }
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, notification("Запускаем сервер подписки"))
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProxyFinder:SubscriptionServer").apply { acquire() }
        }

        try {
            ensureHttpServer()
            if (intent?.action == ACTION_START_INTERNET) startTunnel()
            val localStatus = if (intent?.action == ACTION_START_INTERNET) {
                "Сервер запущен · подключаем интернет-доступ"
            } else {
                "Сервер работает · порт $PORT"
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(localStatus))
        } catch (error: Exception) {
            stopTunnel()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putBoolean(KEY_INTERNET_ENABLED, false)
                .putString(KEY_TUNNEL_STATUS, "Ошибка запуска: ${error.message?.take(100) ?: "неизвестная ошибка"}")
                .apply()
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Ошибка запуска сервера: ${error.message?.take(80) ?: "неизвестная ошибка"}")
            )
            stopSelf()
        }
        return START_STICKY
    }

    private fun ensureHttpServer() {
        if (server == null) {
            val httpServer = SubscriptionHttpServer(repository) {
                getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty()
            }
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = httpServer
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
    }

    /**
     * Quick Tunnel provides a public HTTPS URL without inbound port forwarding.
     * The subscription itself remains served by this Android process.
     */
    private fun startTunnel() {
        if (tunnelProcess?.isAlive == true) return

        val binary = File(applicationInfo.nativeLibraryDir, CLOUDFLARED_BINARY)
        if (!binary.isFile || !binary.canExecute()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_INTERNET_ENABLED, false)
                .putString(KEY_PUBLIC_URL, "")
                .putString(KEY_TUNNEL_STATUS, "Cloudflared не найден. Доступ из интернета доступен в ARM64-сборке.")
                .apply()
            return
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_INTERNET_ENABLED, true)
            .putString(KEY_PUBLIC_URL, "")
            .putString(KEY_TUNNEL_STATUS, "Устанавливаем защищённое соединение с Cloudflare…")
            .apply()

        val process = ProcessBuilder(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--url", "http://127.0.0.1:$PORT",
            "--edge-ip-version", "4"
        ).redirectErrorStream(true).start()
        tunnelProcess = process

        tunnelReader = Thread({
            var lastError = ""
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val publicHost = PUBLIC_HOST_REGEX.find(line)?.value
                        if (publicHost != null) {
                            val token = getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getString(KEY_TOKEN, "").orEmpty()
                            if (token.isNotBlank()) {
                                val url = "$publicHost/sub?token=$token"
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putString(KEY_PUBLIC_URL, url)
                                    .putString(KEY_TUNNEL_STATUS, "Публичный адрес подключён")
                                    .putBoolean(KEY_INTERNET_ENABLED, true)
                                    .apply()
                                getSystemService(NotificationManager::class.java).notify(
                                    NOTIFICATION_ID, notification("Публичная подписка доступна из интернета")
                                )
                            }
                        }
                        if (line.contains("ERR", true) || line.contains("error", true) ||
                            line.contains("failed", true) || line.contains("fatal", true)
                        ) {
                            lastError = line.take(180)
                            if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PUBLIC_URL, "").isNullOrBlank()) {
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putString(KEY_TUNNEL_STATUS, "Cloudflare: $lastError")
                                    .apply()
                            }
                        }
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                lastError = error.message?.take(180) ?: "ошибка чтения процесса"
            }

            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            if (tunnelProcess === process) {
                tunnelProcess = null
                val publicUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PUBLIC_URL, "").orEmpty()
                val status = if (publicUrl.isNotBlank()) {
                    "Внешний туннель остановлен"
                } else {
                    lastError.ifBlank { "Cloudflare завершил подключение (код $exitCode)" }
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_INTERNET_ENABLED, false)
                    .putString(KEY_PUBLIC_URL, "")
                    .putString(KEY_TUNNEL_STATUS, status)
                    .apply()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
            }
        }, "ProxyFinder-Cloudflare-Tunnel").apply { isDaemon = true; start() }
    }

    private fun stopTunnel() {
        val process = tunnelProcess
        tunnelProcess = null
        runCatching { process?.destroy() }
        runCatching { if (process?.isAlive == true) process.destroyForcibly() }
        tunnelReader = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_INTERNET_ENABLED, false)
            .putString(KEY_PUBLIC_URL, "")
            .putString(KEY_TUNNEL_STATUS, "Внешний доступ выключен")
            .apply()
    }

    private fun stopServer() {
        stopTunnel()
        runCatching { server?.stop() }
        server = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_INTERNET_ENABLED, false)
            .apply()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("HAPP Proxy Finder · сервер")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private class SubscriptionHttpServer(
        private val repository: ProxyRepository,
        private val tokenProvider: () -> String
    ) : NanoHTTPD(PORT) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.GET && session.method != Method.HEAD) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "GET only")
            }

            if (session.uri == "/sub" || session.uri == "/" || session.uri == "/subscription.txt") {
                val expectedToken = tokenProvider()
                if (expectedToken.isBlank() || session.parameters["token"]?.firstOrNull() != expectedToken) {
                    return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "unauthorized")
                }
            }

            return when (session.uri) {
                "/health" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
                "/", "/sub", "/subscription.txt" -> {
                    val verified = runBlocking { repository.loadVerifiedSnapshot() }
                        .asSequence()
                        .filter { it.isExportReady && it.rawConfig.isNotBlank() }
                        .distinctBy { it.rawConfig.trim() }
                        .take(1500)
                        .toList()
                    val body = verified.joinToString("\n") { item ->
                        val location = listOf(item.city, item.country).filter { it.isNotBlank() }.joinToString(", ")
                        val name = when {
                            location.isNotBlank() && item.countryCode.length == 2 -> subscriptionCountryFlag(item.countryCode) + " " + location
                            location.isNotBlank() -> location
                            else -> item.title.ifBlank { item.protocol }
                        }.take(48)
                        item.rawConfig.trim().substringBefore('#') + "#" +
                            java.net.URLEncoder.encode(name, Charsets.UTF_8.name())
                    }
                    val response = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body)
                    response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    response.addHeader("Pragma", "no-cache")
                    response.addHeader("X-Verified-Count", verified.size.toString())
                    if (session.method == Method.HEAD) {
                        newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                    } else response
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        }
    }

    companion object {
        const val ACTION_START = "com.happ.proxyfinder.START_SUBSCRIPTION_SERVER"
        const val ACTION_STOP = "com.happ.proxyfinder.STOP_SUBSCRIPTION_SERVER"
        const val ACTION_START_INTERNET = "com.happ.proxyfinder.START_INTERNET_TUNNEL"
        const val ACTION_STOP_INTERNET = "com.happ.proxyfinder.STOP_INTERNET_TUNNEL"
        const val PREFS = "proxy_finder"
        const val KEY_RUNNING = "subscription_server_running"
        const val KEY_INTERNET_ENABLED = "subscription_server_internet_enabled"
        const val KEY_PUBLIC_URL = "subscription_server_public_url"
        const val KEY_TUNNEL_STATUS = "subscription_server_tunnel_status"
        private const val KEY_TOKEN = "local_subscription_token"
        private const val CHANNEL = "proxy_subscription_server"
        private const val NOTIFICATION_ID = 5110
        private const val PORT = 8765
        private const val CLOUDFLARED_BINARY = "libcloudflared.so"
        private val PUBLIC_HOST_REGEX = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com", RegexOption.IGNORE_CASE)

        fun localIpv4Address(): String? = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan", true)) 0 else 1 }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull { it != "127.0.0.1" && !it.startsWith("169.254.") }
        } catch (_: Exception) { null }

        fun subscriptionToken(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var token = prefs.getString(KEY_TOKEN, null)
            if (token.isNullOrBlank()) {
                token = UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(KEY_TOKEN, token).apply()
            }
            return token
        }

        fun subscriptionUrl(context: Context, host: String = "127.0.0.1"): String =
            "http://$host:$PORT/sub?token=${subscriptionToken(context)}"
    }
}
