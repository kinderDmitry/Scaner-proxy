package com.happ.proxyfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

/** Serves a live HAPP subscription containing only genuinely verified proxy tunnels. */
class SubscriptionServerService : Service() {
    private var server: SubscriptionHttpServer? = null
    private lateinit var repository: ProxyRepository

    override fun onCreate() {
        super.onCreate()
        repository = ProxyRepository(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Локальный сервер подписки", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification("Сервер подписки запускается"))
        try {
            if (server == null) {
                val httpServer = SubscriptionHttpServer(repository) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty()
                }
                httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = httpServer
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
            val address = localIpv4Address()
            val status = if (address == null) "Сервер работает · IP сети не определён"
                else "Подписка доступна: http://$address:$PORT/sub"
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        } catch (error: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Ошибка запуска сервера: ${error.message?.take(80) ?: "неизвестная ошибка"}")
            )
            stopSelf()
        }
        return START_STICKY
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
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
                        val name = item.title.ifBlank { item.protocol }.take(30)
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
        const val PREFS = "proxy_finder"
        const val KEY_RUNNING = "subscription_server_running"
        private const val KEY_TOKEN = "local_subscription_token"
        private const val CHANNEL = "proxy_subscription_server"
        private const val NOTIFICATION_ID = 5110
        private const val PORT = 8765

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

        fun subscriptionUrl(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var token = prefs.getString(KEY_TOKEN, null)
            if (token.isNullOrBlank()) {
                token = UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(KEY_TOKEN, token).apply()
            }
            return localIpv4Address()?.let { "http://$it:$PORT/sub?token=$token" }
        }
    }
}
