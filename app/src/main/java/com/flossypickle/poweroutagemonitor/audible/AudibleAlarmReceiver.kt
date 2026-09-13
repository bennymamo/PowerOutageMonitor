package com.flossypickle.poweroutagemonitor.audible

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

internal class AudibleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AudibleAlarmScheduler.ACTION_DISMISS -> {
                AudibleAlarmCoordinator(context).dismissCurrent()
                MonitoringService.refreshNotification(context)
            }
            AudibleAlarmScheduler.ACTION_TICK -> MonitoringService.handleAudibleTick(context)
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val store = MonitorStore(context)
                AudibleAlarmCoordinator(context).reconcile(store.state(), store.lastSnapshot())
                MonitoringService.refreshNotification(context)
            }
        }
    }
}
