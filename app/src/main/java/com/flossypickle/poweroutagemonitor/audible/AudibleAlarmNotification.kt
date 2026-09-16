package com.flossypickle.poweroutagemonitor.audible

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import com.flossypickle.poweroutagemonitor.MainActivity
import com.flossypickle.poweroutagemonitor.R

/** Identifies the repeating local alarm and exposes its existing dismissal receiver. */
internal class AudibleAlarmNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show(outageStartedAt: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audible outage alarm", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "Identifies an active outage alarm and lets you stop its sound"
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val explanation = "FP Grid Monitor is sounding a repeating outage alarm. " +
            "Stop sound silences this outage and cancels its repeats. " +
            "Grid monitoring and message alerts continue."
        @Suppress("DEPRECATION")
        val notification = builder
            .setSmallIcon(R.drawable.ic_audible_alarm)
            .setContentTitle("Outage alarm active")
            .setContentText("FP Grid Monitor is sounding a repeating alarm.")
            .setStyle(Notification.BigTextStyle().bigText(explanation))
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stop_sound),
                "Stop sound", stopSoundIntent(context)
            ).build())
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(outageStartedAt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        // Sound belongs to AudibleAlarmPlayer, never to this notification/channel.
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    companion object {
        private const val CHANNEL_ID = "audible_outage_alarm"
        private const val NOTIFICATION_ID = 1003

        fun stopSoundIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 4103,
            Intent(context, AudibleAlarmReceiver::class.java)
                .setAction(AudibleAlarmScheduler.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
