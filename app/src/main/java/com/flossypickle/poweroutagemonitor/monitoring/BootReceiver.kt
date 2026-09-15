package com.flossypickle.poweroutagemonitor.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryCoordinator

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val cause = when (intent?.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> OperationalHistoryStore.RestartCause.APP_UPDATE
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED -> OperationalHistoryStore.RestartCause.DEVICE_REBOOT
            else -> null
        }
        cause?.let { OperationalHistoryStore(context).expectAppRestart(it) }
        runCatching { AlertDeliveryCoordinator(context).materializePending() }
            .onFailure { Log.e(TAG, "Unable to resume pending alerts after ${intent?.action}", it) }
        if (!MonitorStore(context).settings().monitoringEnabled) return
        runCatching { MonitoringService.start(context, cause) }
            .onFailure { Log.e(TAG, "Unable to resume monitoring after ${intent?.action}", it) }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
