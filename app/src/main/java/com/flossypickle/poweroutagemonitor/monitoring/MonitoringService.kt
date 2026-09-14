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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.flossypickle.poweroutagemonitor.MainActivity
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.R
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmCoordinator
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmReceiver
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmScheduler
import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignal
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignalPolicy
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowModbusPowerSignalProvider
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore

/** Event-driven foreground service. It performs no polling while power state is stable. */
internal class MonitoringService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var coordinator: MonitoringCoordinator
    private lateinit var audibleAlarm: AudibleAlarmCoordinator
    private var activeSource = PowerSourceStore.Source.ANDROID_CHARGER
    private var ecoFlowProvider: EcoFlowModbusPowerSignalProvider? = null
    private var latestBatterySnapshot: PowerSnapshot? = null
    private var ecoFlowCpuLock: PowerManager.WakeLock? = null
    private var ecoFlowWifiLock: WifiManager.WifiLock? = null
    private var lastEcoFlowAvailability: GridAvailability? = null
    private var ecoFlowHadUnknown = false
    private var lastEcoFlowStatusPersistedAt = 0L
    private var lastEcoFlowUiRefreshAt = 0L
    private val deadlineCheck = Runnable { reconcileSelectedPower() }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> PowerSnapshot.from(intent)?.let(::onBatterySnapshot)
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> reconcileSelectedPower()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        coordinator = MonitoringCoordinator(this)
        audibleAlarm = AudibleAlarmCoordinator(this)
        activeSource = PowerSourceStore(this).selectedSource()
        val settings = MonitorStore(this).settings()
        OperationalHistoryStore(this).recordMonitoringStarted(settings.historyLimit)
        createNotificationChannel()
        startAsForeground(buildNotification(MonitorStore(this).state(), MonitorStore(this).lastSnapshot()))
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MonitorStore(this).settings().monitoringEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_AUDIBLE_TICK -> {
                val store = MonitorStore(this)
                audibleAlarm.reconcile(
                    state = store.state(),
                    snapshot = store.lastSnapshot(),
                    scheduledTick = true
                )
                refreshNotification()
            }
            ACTION_REFRESH_NOTIFICATION -> refreshNotification()
            ACTION_RELOAD_POWER_SOURCE -> reloadPowerSource()
            else -> reconcileSelectedPower()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(deadlineCheck)
        ecoFlowProvider?.stop()
        ecoFlowProvider = null
        lastEcoFlowAvailability = null
        ecoFlowHadUnknown = false
        releaseEcoFlowLocks()
        runCatching { unregisterReceiver(batteryReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        val settings = MonitorStore(this).settings()
        OperationalHistoryStore(this).recordMonitoringStopped(
            maxRecords = settings.historyLimit,
            userDisabled = !settings.monitoringEnabled
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryReceiver, filter)
        }
    }

    private fun currentBatterySnapshot(): PowerSnapshot {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return PowerSnapshot.from(intent)
            ?.also { latestBatterySnapshot = it }
            ?: latestBatterySnapshot
            ?: MonitorStore(this).lastSnapshot()
            ?: PowerSnapshot(plugged = -1, batteryPercent = null, batteryStatus = 1,
                batteryTemperatureTenthsCelsius = null)
    }

    private fun reconcileSelectedPower() {
        val selected = PowerSourceStore(this).selectedSource()
        if (selected != activeSource ||
            selected == PowerSourceStore.Source.ECOFLOW_MODBUS && ecoFlowProvider == null
        ) {
            reloadPowerSource()
            return
        }
        when (selected) {
            PowerSourceStore.Source.ANDROID_CHARGER -> processAndroid(currentBatterySnapshot())
            PowerSourceStore.Source.ECOFLOW_MODBUS -> ecoFlowProvider?.refresh()
        }
    }

    private fun reloadPowerSource() {
        ecoFlowProvider?.stop()
        ecoFlowProvider = null
        lastEcoFlowAvailability = null
        ecoFlowHadUnknown = false
        lastEcoFlowStatusPersistedAt = 0L
        lastEcoFlowUiRefreshAt = 0L
        releaseEcoFlowLocks()
        activeSource = PowerSourceStore(this).selectedSource()
        if (activeSource == PowerSourceStore.Source.ECOFLOW_MODBUS) {
            val config = PowerSourceStore(this).ecoFlowConfig()
            if (!config.isValid) {
                PowerSourceStore(this).select(PowerSourceStore.Source.ANDROID_CHARGER)
                activeSource = PowerSourceStore.Source.ANDROID_CHARGER
                processAndroid(currentBatterySnapshot())
                return
            }
            ecoFlowProvider = EcoFlowModbusPowerSignalProvider(
                host = config.host,
                port = config.port,
                unitId = config.unitId
            ).also { provider ->
                acquireEcoFlowLocks()
                provider.start { signal -> handler.post { processEcoFlow(signal) } }
            }
        } else {
            processAndroid(currentBatterySnapshot())
        }
    }

    private fun onBatterySnapshot(snapshot: PowerSnapshot) {
        latestBatterySnapshot = snapshot
        if (activeSource == PowerSourceStore.Source.ANDROID_CHARGER) {
            processAndroid(snapshot)
        } else {
            val now = System.currentTimeMillis()
            val status = PowerSourceStore(this).lastStatus()?.takeIf {
                it.source == PowerSourceStore.Source.ECOFLOW_MODBUS &&
                    now - it.observedAtEpochMs in 0..ECOFLOW_STALE_AFTER_MS
            }
            val gridPowered = when (status?.availability) {
                GridAvailability.AVAILABLE -> true
                GridAvailability.UNAVAILABLE -> false
                GridAvailability.UNKNOWN, null -> null
            }
            // Device-battery changes must still reach the coordinator so the low-battery
            // alert and audible-alarm cutoff work while EcoFlow owns the grid decision.
            process(snapshot, gridPowered, now)
        }
    }

    private fun processAndroid(snapshot: PowerSnapshot) {
        val now = System.currentTimeMillis()
        PowerSourceStore(this).recordSignal(PowerSignal(
            availability = when (snapshot.externallyPowered) {
                true -> GridAvailability.AVAILABLE
                false -> GridAvailability.UNAVAILABLE
                null -> GridAvailability.UNKNOWN
            },
            observedAtEpochMs = now,
            providerId = ANDROID_PROVIDER_ID,
            detail = "Android external-power signal"
        ))
        process(snapshot, snapshot.externallyPowered, now)
    }

    private fun processEcoFlow(signal: PowerSignal) {
        val availabilityChanged = signal.availability != lastEcoFlowAvailability
        val persistStatus = availabilityChanged ||
            signal.observedAtEpochMs - lastEcoFlowStatusPersistedAt >= STATUS_PERSIST_INTERVAL_MS
        PowerSourceStore(this).recordSignal(signal, persistStatus)
        if (persistStatus) lastEcoFlowStatusPersistedAt = signal.observedAtEpochMs
        val evaluated = PowerSignalPolicy.evaluate(
            signal = signal,
            nowEpochMs = System.currentTimeMillis(),
            staleAfterMs = ECOFLOW_STALE_AFTER_MS
        )
        val powered = when (evaluated.availability) {
            GridAvailability.AVAILABLE -> true
            GridAvailability.UNAVAILABLE -> false
            GridAvailability.UNKNOWN -> null
        }
        val monitorStore = MonitorStore(this)
        val state = monitorStore.state()
        val settings = monitorStore.settings()
        val deadlineReached = OutageEngine.deadlineEpochMs(
            state, settings.outageDelayMs, settings.restoreDelayMs
        )?.let { signal.observedAtEpochMs >= it } == true
        when {
            powered == null && !ecoFlowHadUnknown -> {
                ecoFlowHadUnknown = true
                process(currentBatterySnapshot(), null, signal.observedAtEpochMs)
            }
            powered == null -> Unit
            ecoFlowHadUnknown -> {
                ecoFlowHadUnknown = false
                process(currentBatterySnapshot(), null, signal.observedAtEpochMs)
                process(currentBatterySnapshot(), powered, signal.observedAtEpochMs)
            }
            availabilityChanged || deadlineReached ->
                process(currentBatterySnapshot(), powered, signal.observedAtEpochMs)
        }
        if (signal.observedAtEpochMs - lastEcoFlowUiRefreshAt >= UI_REFRESH_INTERVAL_MS) {
            lastEcoFlowUiRefreshAt = signal.observedAtEpochMs
            sendBroadcast(Intent(MonitoringCoordinator.ACTION_MONITOR_STATE_CHANGED).setPackage(packageName))
            refreshNotification()
        }
        lastEcoFlowAvailability = signal.availability
    }

    private fun process(snapshot: PowerSnapshot, gridPowered: Boolean?, observedAtEpochMs: Long) {
        val state = coordinator.process(snapshot, observedAtEpochMs, gridPowered)
        scheduleInProcessDeadline(state)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state, snapshot))
    }

    @Suppress("DEPRECATION")
    private fun acquireEcoFlowLocks() {
        runCatching {
            ecoFlowCpuLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ecoflow-monitor")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
            ecoFlowWifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:ecoflow-wifi")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.onFailure {
            releaseEcoFlowLocks()
            android.util.Log.e("MonitoringService", "Unable to keep EcoFlow connection awake", it)
        }
    }

    private fun releaseEcoFlowLocks() {
        runCatching { ecoFlowWifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { ecoFlowCpuLock?.takeIf { it.isHeld }?.release() }
        ecoFlowWifiLock = null
        ecoFlowCpuLock = null
    }

    private fun refreshNotification() {
        val store = MonitorStore(this)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(store.state(), store.lastSnapshot()))
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
        val sourceStore = PowerSourceStore(this)
        val ecoFlowUnknown = sourceStore.selectedSource() == PowerSourceStore.Source.ECOFLOW_MODBUS &&
            sourceStore.lastStatus()?.let {
                it.source != PowerSourceStore.Source.ECOFLOW_MODBUS ||
                    it.availability == GridAvailability.UNKNOWN ||
                    System.currentTimeMillis() - it.observedAtEpochMs !in 0..ECOFLOW_STALE_AFTER_MS
            } != false
        val title = if (ecoFlowUnknown) {
            "Monitoring · EcoFlow reading unavailable"
        } else when (state.phase) {
            OutageEngine.Phase.WAITING -> "Monitoring · waiting for power"
            OutageEngine.Phase.POWERED -> "Monitoring · grid power online"
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
        builder
            .setSmallIcon(R.drawable.ic_monitoring_notification)
            .setContentTitle(title)
            .setContentText("${MonitorStore(this).settings().deviceName}$battery")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (audibleAlarm.isActive(state, snapshot)) {
            val dismiss = PendingIntent.getBroadcast(
                this,
                4103,
                Intent(this, AudibleAlarmReceiver::class.java)
                    .setAction(AudibleAlarmScheduler.ACTION_DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_monitoring_notification),
                    "Dismiss alarm",
                    dismiss
                ).build()
            )
        }
        return builder.build()
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

        fun handleAudibleTick(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java).setAction(ACTION_AUDIBLE_TICK)
            )
        }

        fun refreshNotification(context: Context) {
            if (!MonitorStore(context).settings().monitoringEnabled) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java)
                    .setAction(ACTION_REFRESH_NOTIFICATION)
            )
        }

        fun reloadPowerSource(context: Context) {
            if (!MonitorStore(context).settings().monitoringEnabled) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java)
                    .setAction(ACTION_RELOAD_POWER_SOURCE)
            )
        }

        fun handleDeadline(context: Context) {
            if (!MonitorStore(context).settings().monitoringEnabled) return
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
        }

        private const val ACTION_AUDIBLE_TICK =
            "com.flossypickle.poweroutagemonitor.SERVICE_AUDIBLE_TICK"
        private const val ACTION_REFRESH_NOTIFICATION =
            "com.flossypickle.poweroutagemonitor.REFRESH_MONITOR_NOTIFICATION"
        private const val ACTION_RELOAD_POWER_SOURCE =
            "com.flossypickle.poweroutagemonitor.RELOAD_POWER_SOURCE"
        private const val ANDROID_PROVIDER_ID = "android_charger"
        private const val ECOFLOW_STALE_AFTER_MS = 15_000L
        private const val UI_REFRESH_INTERVAL_MS = 15_000L
        private const val STATUS_PERSIST_INTERVAL_MS = 5 * 60_000L
    }
}
