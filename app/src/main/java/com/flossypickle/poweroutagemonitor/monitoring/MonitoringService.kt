package com.flossypickle.poweroutagemonitor.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.flossypickle.poweroutagemonitor.MainActivity
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.R
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

/** Event-driven foreground service. It performs no polling while power state is stable. */
internal class MonitoringService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var coordinator: MonitoringCoordinator
    private val deadlineCheck = Runnable { reconcileCurrentPower() }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            PowerSnapshot.from(intent)?.let(::process)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        coordinator = MonitoringCoordinator(this)
        createNotificationChannel()
        startAsForeground(buildNotification(MonitorStore(this).state(), MonitorStore(this).lastSnapshot()))
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MonitorStore(this).settings().monitoringEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        reconcileCurrentPower()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(deadlineCheck)
        runCatching { unregisterReceiver(batteryReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryReceiver, filter)
        }
    }

    private fun reconcileCurrentPower() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        PowerSnapshot.from(intent)?.let(::process)
    }

    private fun process(snapshot: PowerSnapshot) {
        val state = coordinator.process(snapshot)
        scheduleInProcessDeadline(state)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state, snapshot))
    }

    private fun scheduleInProcessDeadline(state: OutageEngine.State) {
        handler.removeCallbacks(deadlineCheck)
        val settings = MonitorStore(this).settings()
        val deadline = OutageEngine.deadlineEpochMs(
            state,
            settings.outageDelayMs,
            settings.restoreDelayMs
        ) ?: return
        handler.postDelayed(deadlineCheck, (deadline - System.currentTimeMillis()).coerceAtLeast(0))
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: OutageEngine.State, snapshot: PowerSnapshot?): Notification {
        val title = when (state.phase) {
            OutageEngine.Phase.WAITING -> "Monitoring · waiting for power"
            OutageEngine.Phase.POWERED -> "Monitoring · external power connected"
            OutageEngine.Phase.PENDING_OUTAGE -> "Checking possible power loss"
            OutageEngine.Phase.OUTAGE -> "Power outage confirmed"
            OutageEngine.Phase.PENDING_RESTORE -> "Checking power restoration"
        }
        val battery = snapshot?.batteryPercent?.let { " · Battery $it%" }.orEmpty()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_monitoring_notification)
            .setContentTitle(title)
            .setContentText("${MonitorStore(this).settings().deviceName}$battery")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Power monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows that power monitoring is active"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "power_monitoring"
        private const val NOTIFICATION_ID = 1001
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java)
            )
        }
    }
}
