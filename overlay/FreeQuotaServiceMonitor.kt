// DEST: core/FreeQuotaServiceMonitor.kt
package com.v2ray.ang.core

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.v2ray.ang.ui.main.FreeConfigRepository
import com.v2ray.ang.ui.main.FreeQuotaSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Enforces free-profile limits from the VPN core process. This keeps enforcement
 * independent from the Compose activity lifecycle while the VPN service is alive.
 */
internal object FreeQuotaServiceMonitor {
    private const val FREE_SUBSCRIPTION_ID = "v2plas_free_remote_group"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var monitorJob: Job? = null

    @Synchronized
    fun start(subscriptionId: String?) {
        stopMonitorOnly()
        if (subscriptionId != FREE_SUBSCRIPTION_ID) {
            FreeQuotaSessionStore.finish()
            return
        }

        val settings = FreeConfigRepository.readCurrentSettings()
        if (!settings.freeEnabled) {
            requestServiceStop()
            return
        }

        val session = FreeQuotaSessionStore.startOrResume(Process.myUid())
        if (session.invalid && settings.volumeLimitBytes > 0L) {
            requestServiceStop()
            return
        }
        if (settings.timeLimitMillis <= 0L && settings.volumeLimitBytes <= 0L) return

        monitorJob = scope.launch {
            while (isActive && CoreServiceManager.isRunning()) {
                delay(500L)
                val now = SystemClock.elapsedRealtime()
                val rx = TrafficStats.getUidRxBytes(Process.myUid())
                val tx = TrafficStats.getUidTxBytes(Process.myUid())
                val elapsed = (now - session.startElapsed).coerceAtLeast(0L)
                val countersInvalid = settings.volumeLimitBytes > 0L &&
                    (rx < 0L || tx < 0L || rx < session.baseRx || tx < session.baseTx)
                val rxUsed = if (countersInvalid || rx < 0L) 0L else (rx - session.baseRx).coerceAtLeast(0L)
                val txUsed = if (countersInvalid || tx < 0L) 0L else (tx - session.baseTx).coerceAtLeast(0L)
                val used = if (rxUsed > Long.MAX_VALUE - txUsed) Long.MAX_VALUE else rxUsed + txUsed
                val timeReached = settings.timeLimitMillis > 0L && elapsed >= settings.timeLimitMillis
                val volumeReached = settings.volumeLimitBytes > 0L && used >= settings.volumeLimitBytes

                if (timeReached || countersInvalid || volumeReached) {
                    requestServiceStop()
                    break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        stopMonitorOnly()
        FreeQuotaSessionStore.finish()
    }

    private fun stopMonitorOnly() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun requestServiceStop() {
        // The manager and ServiceControl live in the daemon process. A missing
        // SoftReference can happen during service teardown; retry briefly rather
        // than silently leaving a limited tunnel running.
        scope.launch {
            repeat(10) {
                if (!CoreServiceManager.isRunning()) return@launch
                val control = CoreServiceManager.serviceControl?.get()
                if (control != null) {
                    control.stopService()
                    return@launch
                }
                delay(300L)
            }
        }
    }
}
