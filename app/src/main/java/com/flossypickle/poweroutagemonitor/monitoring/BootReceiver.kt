package com.flossypickle.poweroutagemonitor.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!MonitorStore(context).settings().monitoringEnabled) return
        runCatching { MonitoringService.start(context) }
            .onFailure { Log.e(TAG, "Unable to resume monitoring after ${intent?.action}", it) }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
