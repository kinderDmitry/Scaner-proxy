package com.happ.proxyfinder

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Durable fallback for background monitoring. Android may stop a foreground dataSync service
 * after its platform time budget; WorkManager resumes checks when network and OS constraints allow.
 */
class ProxyMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val repo = ProxyRepository(applicationContext)
            val prefs = applicationContext.getSharedPreferences("proxy_finder", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            // Refresh only public, published VLESS/SOCKS5 feeds once per hour, not on every cycle.
            val lastRefresh = prefs.getLong(KEY_LAST_PUBLIC_REFRESH, 0L)
            if (now - lastRefresh >= PUBLIC_REFRESH_INTERVAL_MS) {
                val discovered = ArrayList<ProxyItem>(300)
                repo.fetchStreaming(setOf("VLESS", "SOCKS5"), onSource = {}) { item ->
                    if (discovered.size < MAX_DISCOVERED_PER_REFRESH) discovered.add(item)
                }
                if (discovered.isNotEmpty()) repo.saveSnapshot(discovered)
                prefs.edit().putLong(KEY_LAST_PUBLIC_REFRESH, now).apply()
            }

            val due = repo.loadSnapshot(5000)
                .filter { item ->
                    item.supportsNativeTunnelCheck &&
                        (item.needsNativeTunnelCheck ||
                            item.isTunnelVerified && now - (item.checkedAt ?: 0L) >= RECHECK_INTERVAL_MS)
                }
                .sortedWith(
                    compareByDescending<ProxyItem> { it.qualityScore }
                        .thenBy { it.latencyMs ?: Int.MAX_VALUE }
                        .thenBy { it.checkedAt ?: 0L }
                )
                .take(MAX_CHECKS_PER_RUN)

            if (due.isNotEmpty()) {
                val checked = repo.checkBatch(due) { }
                val byId = checked.associateBy { it.id }
                val latest = repo.loadSnapshot(5000)
                repo.saveSnapshot(latest.map { byId[it.id] ?: it })
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_LAST_PUBLIC_REFRESH = "last_public_feed_refresh"
        private const val PUBLIC_REFRESH_INTERVAL_MS = 60 * 60 * 1000L
        private const val RECHECK_INTERVAL_MS = 15 * 60 * 1000L
        private const val MAX_DISCOVERED_PER_REFRESH = 300
        private const val MAX_CHECKS_PER_RUN = 48
    }
}

object ProxyMonitorScheduler {
    private const val UNIQUE_WORK_NAME = "proxy-background-monitor"

    fun enable(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ProxyMonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
