package com.happ.proxyfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections\nimport java.util.UUID

/**
 * Hosts a live HAPP-compatible subscription from the verified local database.
 * The response is rebuilt for every request, so clients always receive the latest verified set.
 */
class SubscriptionServerService : Service() {
    private var server: SubscriptionHttpServer? = null
    private lateinit var repository: ProxyRepository

    override fun onCreate() {
        super.onCreate()
        repository = ProxyRepository(applicationContext)\n        serverTokenProvider = { getSharedPreferences(PREFS, MODE_PRIVATE).getString("local_subscription_token", "").orEmpty() }
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
                val httpServer = SubscriptionHttpServer(repository)
                httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = httpServer
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
            val address = localIpv4Address()
            val status = if (address == null) "Сервер работает · IP сети не определён" else "Подписка: http://$address:$PORT/sub"
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        } catch (error: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Ошибка запуска сервера: ${error.message?.take(80) ?: "неизвестная ошибка"}"))
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

    private class SubscriptionHttpServer(private val repository: ProxyRepository) : NanoHTTPD(PORT) {\n        private fun repositoryToken(): String = repositoryServerToken(repository)
        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.GET && session.method != Method.HEAD) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "GET only")
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
                        item.rawConfig.trim().substringBefore('#') + "#" + java.net.URLEncoder.encode(name, "UTF-8")
                    }
                    val response = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body)
                    response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    response.addHeader("Pragma", "no-cache")
                    response.addHeader("X-Verified-Count", verified.size.toString())
                    if (session.method == Method.HEAD) newFixedLengthResponse(Response.Status.OK, "text/plain", "") else response
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        }

        companion object { const val PORT = 8765 }
    }

    companion object {
        const val ACTION_START = "com.happ.proxyfinder.START_SUBSCRIPTION_SERVER"
        const val ACTION_STOP = "com.happ.proxyfinder.STOP_SUBSCRIPTION_SERVER"
        const val PREFS = "proxy_finder"
        const val KEY_RUNNING = "subscription_server_running"
        private const val CHANNEL = "proxy_subscription_server"
        private const val NOTIFICATION_ID = 5110
        private const val PORT = 8765

        fun localIpv4Address(): String? = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it != "127.0.0.1" && !it.startsWith("169.254.") }
        } catch (_: Exception) { null }

        fun subscriptionUrl(context: android.content.Context): String? {\n            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)\n            var token = prefs.getString("local_subscription_token", null)\n            if (token.isNullOrBlank()) {\n                token = UUID.randomUUID().toString().replace("-", "")\n                prefs.edit().putString("local_subscription_token", token).apply()\n            }\n            return localIpv4Address()?.let { "http://$it:$PORT/sub?token=$token" }\n        }\n\n        private fun repositoryServerToken(repository: ProxyRepository): String {\n            // The token is read from the same private preferences used by the server service.\n            return serverTokenProvider?.invoke() ?: ""\n        }\n        @Volatile private var serverTokenProvider: (() -> String)? = null
    }
}
