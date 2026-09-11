package com.flossypickle.poweroutagemonitor.audible

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService

internal class AudibleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AudibleAlarmScheduler.ACTION_DISMISS -> {
                AudibleAlarmCoordinator(context).dismissCurrent()
                MonitoringService.refreshNotification(context)
            }
            AudibleAlarmScheduler.ACTION_TICK -> MonitoringService.handleAudibleTick(context)
        }
    }
}
