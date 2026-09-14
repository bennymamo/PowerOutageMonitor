package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

internal class ScheduledAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!MonitorStore(context).settings().monitoringEnabled) return
        MonitoringService.handleScheduledAlert(context)
    }
}
