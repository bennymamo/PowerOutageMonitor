package com.flossypickle.poweroutagemonitor.audible

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Schedules a best-effort wake-up for the next user-configured repeat. */
internal class AudibleAlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(atEpochMs: Long) {
        val operation = operation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, operation)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, atEpochMs, operation)
        }
    }

    fun cancel() = alarmManager.cancel(operation())

    private fun operation() = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AudibleAlarmReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val ACTION_TICK =
            "com.flossypickle.poweroutagemonitor.AUDIBLE_ALARM_TICK"
        const val ACTION_DISMISS =
            "com.flossypickle.poweroutagemonitor.DISMISS_AUDIBLE_ALARM"
        private const val REQUEST_CODE = 4102
    }
}
