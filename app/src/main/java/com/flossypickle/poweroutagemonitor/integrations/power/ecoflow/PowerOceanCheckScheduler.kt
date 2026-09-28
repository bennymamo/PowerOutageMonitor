package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

/** Wakes the foreground monitor for the next account check without holding idle locks. */
internal class PowerOceanCheckScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val pendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, PowerOceanCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(deadlineEpochMs: Long?) {
        alarmManager.cancel(pendingIntent)
        deadlineEpochMs ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
                else alarmManager.setExact(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
                return
            } catch (_: SecurityException) { /* Fall back when access was revoked. */ }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
        }
    }

    companion object {
        private const val REQUEST_CODE = 4113
    }
}

internal class PowerOceanCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (MonitorStore(context).settings().monitoringEnabled &&
            PowerSourceStore(context).selectedSource() == PowerSourceStore.Source.ECOFLOW_ACCOUNT
        ) {
            MonitoringService.handleDeadline(context)
        }
    }
}
