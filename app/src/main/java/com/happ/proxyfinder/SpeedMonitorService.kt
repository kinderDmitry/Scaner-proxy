package com.happ.proxyfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs while the screen is locked; no app can run while the device is powered off. */
class SpeedMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var repo: ProxyRepository
    override fun onCreate() {
        super.onCreate()
        repo = ProxyRepository(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Фоновая проверка прокси", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Фоновая проверка включена"))
        if (job?.isActive != true) job = scope.launch {
            while (true) {
                try {
                    val batch = repo.loadSnapshot().sortedBy { it.checkedAt ?: 0L }.take(48)
                    if (batch.isNotEmpty()) {
                        val checked = repo.checkBatch(batch) { }
                        val checkedById = checked.associateBy { it.id }
                        val latest = repo.loadSnapshot()
                        repo.saveSnapshot(latest.map { checkedById[it.id] ?: it })
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Обновлено: ${checked.size} прокси · следующий цикл через 5 минут"))
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Нет сохранённых прокси — сначала запусти поиск"))
                    }
                } catch (_: Exception) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Ошибка проверки · повтор через 5 минут"))
                }
                delay(300_000L)
            }
        }
        return START_STICKY
    }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("HAPP Proxy Finder")
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).build()
    override fun onDestroy() { job?.cancel(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_START = "com.happ.proxyfinder.START_MONITOR"
        const val ACTION_STOP = "com.happ.proxyfinder.STOP_MONITOR"
        private const val CHANNEL = "proxy_speed_monitor"
        private const val NOTIFICATION_ID = 5107
    }
}
