package com.flossypickle.poweroutagemonitor.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

/** Reconciles a persisted pending state even if Android recreated the process. */
internal class DeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!MonitorStore(context).settings().monitoringEnabled) return
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        PowerSnapshot.from(batteryIntent)?.let { MonitoringCoordinator(context).process(it) }
    }
}
