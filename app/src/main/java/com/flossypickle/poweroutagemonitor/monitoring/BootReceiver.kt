package com.flossypickle.poweroutagemonitor.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryCoordinator

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching { AlertDeliveryCoordinator(context).materializePending() }
            .onFailure { Log.e(TAG, "Unable to resume pending alerts after ${intent?.action}", it) }
        if (!MonitorStore(context).settings().monitoringEnabled) return
        runCatching { MonitoringService.start(context) }
            .onFailure { Log.e(TAG, "Unable to resume monitoring after ${intent?.action}", it) }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
