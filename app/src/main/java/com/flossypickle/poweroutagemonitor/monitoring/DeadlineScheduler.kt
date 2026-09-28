package com.flossypickle.poweroutagemonitor.monitoring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

/** Use granted exact access for confirmation deadlines, with an idle-aware fallback. */
internal class DeadlineScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DeadlineReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(state: OutageEngine.State, settings: MonitorStore.Settings) {
        val deadline = OutageEngine.deadlineEpochMs(
            state,
            settings.outageDelayMs,
            settings.restoreDelayMs
        )
        scheduleAt(deadline)
    }

    fun scheduleAt(deadline: Long?) {
        alarmManager.cancel(pendingIntent)
        deadline ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pendingIntent)
                else alarmManager.setExact(AlarmManager.RTC_WAKEUP, deadline, pendingIntent)
                return
            } catch (_: SecurityException) { /* Access may have been revoked since the check. */ }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, deadline, pendingIntent)
        }
    }

    fun cancel() = alarmManager.cancel(pendingIntent)

    companion object {
        private const val REQUEST_CODE = 4107
    }
}
