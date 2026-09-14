package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Own alarm slot for persisted heartbeat, source-health and long-outage deadlines. */
internal class ScheduledAlertScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val pendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScheduledAlertReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(deadlineEpochMs: Long?) {
        alarmManager.cancel(pendingIntent)
        deadlineEpochMs ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
        }
    }

    fun cancel() = alarmManager.cancel(pendingIntent)

    companion object {
        private const val REQUEST_CODE = 4111
    }
}
