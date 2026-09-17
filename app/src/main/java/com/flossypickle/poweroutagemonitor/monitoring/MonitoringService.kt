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
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmNotification
import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignal
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignalPolicy
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountPowerSignalProvider
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignalProvider
import com.flossypickle.poweroutagemonitor.integrations.power.ChargerConfirmationPolicy
import com.flossypickle.poweroutagemonitor.integrations.power.ChargerFirstPolicy
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowModbusPowerSignalProvider
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore

/** Event-driven foreground service. It performs no polling while power state is stable. */
internal class MonitoringService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var coordinator: MonitoringCoordinator
    private lateinit var audibleAlarm: AudibleAlarmCoordinator
    private var activeSource = PowerSourceStore.Source.ANDROID_CHARGER
    private var ecoFlowProvider: PowerSignalProvider? = null
    private var sourceGeneration = 0
    private var latestPrimarySignal: PowerSignal? = null
    private var latestBatterySnapshot: PowerSnapshot? = null
    private var ecoFlowCpuLock: PowerManager.WakeLock? = null
    private var ecoFlowWifiLock: WifiManager.WifiLock? = null
    private var lastEcoFlowAvailability: GridAvailability? = null
    private var ecoFlowHadUnknown = false
    private var lastEcoFlowStatusPersistedAt = 0L
    private var lastEcoFlowUiRefreshAt = 0L
    private val deadlineCheck = Runnable { reconcileSelectedPower() }
    private var historyStartRecorded = false
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
        createNotificationChannel()
        startAsForeground(buildNotification(MonitorStore(this).state(), MonitorStore(this).lastSnapshot()))
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!historyStartRecorded) {
            val cause = when (intent?.action) {
                ACTION_RESUME_AFTER_UPDATE -> OperationalHistoryStore.RestartCause.APP_UPDATE
                ACTION_RESUME_AFTER_BOOT -> OperationalHistoryStore.RestartCause.DEVICE_REBOOT
                else -> null
            }
            val historyLimit = MonitorStore(this).settings().historyLimit
            OperationalHistoryStore(this).recordMonitoringStarted(historyLimit, cause)
            historyStartRecorded = true
        }
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
            ACTION_REQUEST_POWEROCEAN_CHECK -> {
                if (activeSource == PowerSourceStore.Source.ECOFLOW_ACCOUNT) {
                    if ((ecoFlowProvider as? PowerOceanAccountPowerSignalProvider)?.requestCheck() != true) {
                        reloadPowerSource()
                        (ecoFlowProvider as? PowerOceanAccountPowerSignalProvider)?.requestCheck()
                    }
                }
            }
            ACTION_REFRESH_SCHEDULED_ALERTS -> reconcileSelectedPower()
            else -> reconcileSelectedPower()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(deadlineCheck)
        sourceGeneration++
        latestPrimarySignal = null
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
        if (historyStartRecorded) {
            OperationalHistoryStore(this).recordMonitoringStopped(
                maxRecords = settings.historyLimit,
                userDisabled = !settings.monitoringEnabled
            )
        }
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
            selected != PowerSourceStore.Source.ANDROID_CHARGER && ecoFlowProvider == null
        ) {
            reloadPowerSource()
            return
        }
        when (selected) {
            PowerSourceStore.Source.ANDROID_CHARGER -> processAndroid(currentBatterySnapshot())
            PowerSourceStore.Source.ECOFLOW_MODBUS -> (ecoFlowProvider as? EcoFlowModbusPowerSignalProvider)?.refresh()
            PowerSourceStore.Source.ECOFLOW_ACCOUNT -> onBatterySnapshot(currentBatterySnapshot())
        }
    }

    private fun reloadPowerSource() {
        val generation = ++sourceGeneration
        latestPrimarySignal = null
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
                provider.start { signal -> handler.post { if (sourceGeneration == generation && isRunning) processEcoFlow(signal) } }
            }
        } else if (activeSource == PowerSourceStore.Source.ECOFLOW_ACCOUNT) {
            process(currentBatterySnapshot(), null, System.currentTimeMillis())
            ecoFlowProvider = PowerOceanAccountPowerSignalProvider(this).also { provider ->
                provider.updateCharger(currentBatterySnapshot().externallyPowered)
                acquireEcoFlowLocks()
                provider.start { signal -> handler.post {
                    if (sourceGeneration == generation && isRunning) processEcoFlow(signal)
                } }
            }
        } else {
            processAndroid(currentBatterySnapshot())
        }
    }

    private fun onBatterySnapshot(snapshot: PowerSnapshot) {
        latestBatterySnapshot = snapshot
        (ecoFlowProvider as? PowerOceanAccountPowerSignalProvider)?.updateCharger(snapshot.externallyPowered)
        if (activeSource == PowerSourceStore.Source.ECOFLOW_ACCOUNT && PowerSourceStore(this).powerOceanAssistedSettings().enabled) {
            processEcoFlow(latestPrimarySignal ?: PowerSignal(GridAvailability.UNKNOWN, System.currentTimeMillis(), PowerSourceStore.POWEROCEAN_PROVIDER_ID))
            return
        }
        if (activeSource == PowerSourceStore.Source.ANDROID_CHARGER) {
            processAndroid(snapshot)
        } else {
            val now = System.currentTimeMillis()
            val status = PowerSourceStore(this).lastStatus()?.takeIf {
                it.source == activeSource &&
                    now - it.observedAtEpochMs in 0..ECOFLOW_STALE_AFTER_MS
            }
            val gridPowered = when (status?.availability) {
                GridAvailability.AVAILABLE -> true
                GridAvailability.UNAVAILABLE -> false
                GridAvailability.UNKNOWN, null -> null
            }
            if (activeSource == PowerSourceStore.Source.ECOFLOW_MODBUS) {
                latestPrimarySignal?.takeIf { now - it.observedAtEpochMs in 0..ECOFLOW_STALE_AFTER_MS }?.let {
                    processEcoFlow(it)
                    return
                }
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

    private fun assistedSignal(primary: PowerSignal): PowerSignal {
        val store = PowerSourceStore(this)
        val now = System.currentTimeMillis()
        val charger = currentBatterySnapshot().externallyPowered
        val lossAt = if (charger == false) store.assistedChargerLossStartedAt().takeIf { it > 0 } ?: now else 0
        val phase = MonitorStore(this).state().phase
        val assistance = store.powerOceanAssistedSettings()
        val verify = !store.powerOceanAssistancePaused() && assistance.outageSeconds > 0 &&
            !(assistance.ignoreUnchanged && primary.dataPossiblyStalled == true)
        val result = ChargerFirstPolicy.evaluate(charger, primary, lossAt,
            phase in setOf(OutageEngine.Phase.OUTAGE, OutageEngine.Phase.PENDING_RESTORE),
            store.assistedChargerLossRecovered(), now, store.assistedEcoFlowOutageStartedAt(),
            verificationWindowMs = if (verify) assistance.checkWindowSeconds * 1000L + 60_000 else 0)
        store.recordAssistedChargerState(lossAt, result.recovered, result.ecoFlowOutageStartedAt)
        return primary.copy(availability = result.availability, observedAtEpochMs = now,
            detail = result.detail, recoveryPending = result.recoveryPending)
    }

    private fun processEcoFlow(primary: PowerSignal) {
        latestPrimarySignal = primary
        val sourceStore = PowerSourceStore(this)
        val assisted = sourceStore.powerOceanAssistedSettings()
        if (activeSource == PowerSourceStore.Source.ECOFLOW_ACCOUNT) {
            com.flossypickle.poweroutagemonitor.integrations.alerts.SourceDataWarningCoordinator(this)
                .process(primary.dataPossiblyStalled, assisted.warnOnUnchanged)
        }
        val signal = if (activeSource == PowerSourceStore.Source.ECOFLOW_ACCOUNT && assisted.enabled) {
            assistedSignal(com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistancePolicy.apply(
                primary, sourceStore.powerOceanAssistancePaused(), assisted.ignoreUnchanged))
        } else ChargerConfirmationPolicy.apply(primary, currentBatterySnapshot().externallyPowered,
            PowerSourceStore(this).powerOceanRequiresChargerConfirmation())
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
        var fullStateProcessRan = false
        when {
            powered == null && !ecoFlowHadUnknown -> {
                ecoFlowHadUnknown = true
                process(currentBatterySnapshot(), null, signal.observedAtEpochMs)
                fullStateProcessRan = true
            }
            powered == null -> Unit
            ecoFlowHadUnknown -> {
                ecoFlowHadUnknown = false
                process(currentBatterySnapshot(), null, signal.observedAtEpochMs)
                process(currentBatterySnapshot(), powered, signal.observedAtEpochMs)
                fullStateProcessRan = true
            }
            availabilityChanged || deadlineReached -> {
                process(currentBatterySnapshot(), powered, signal.observedAtEpochMs)
                fullStateProcessRan = true
            }
        }
        if (!fullStateProcessRan) {
            coordinator.processScheduledOnly(
                currentBatterySnapshot(),
                powered,
                signal.observedAtEpochMs
            )
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
        val ecoFlowUnknown = sourceStore.selectedSource() != PowerSourceStore.Source.ANDROID_CHARGER &&
            sourceStore.lastStatus()?.let {
                it.source != sourceStore.selectedSource() ||
                    it.availability == GridAvailability.UNKNOWN ||
                    System.currentTimeMillis() - it.observedAtEpochMs !in 0..ECOFLOW_STALE_AFTER_MS
            } != false
        val title = if (ecoFlowUnknown) {
            "Monitoring · EcoFlow reading unavailable"
        } else when (state.phase) {
            OutageEngine.Phase.WAITING -> "Monitoring · waiting for power"
            OutageEngine.Phase.POWERED -> if (sourceStore.lastStatus()?.recoveryPending == true) "Grid appears back · EcoFlow reconnecting" else "Monitoring · grid power online"
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
            val dismiss = AudibleAlarmNotification.stopSoundIntent(this)
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop_sound),
                    "Stop sound",
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

        fun start(
            context: Context,
            restartCause: OperationalHistoryStore.RestartCause? = null
        ) {
            val action = when (restartCause) {
                OperationalHistoryStore.RestartCause.APP_UPDATE -> ACTION_RESUME_AFTER_UPDATE
                OperationalHistoryStore.RestartCause.DEVICE_REBOOT -> ACTION_RESUME_AFTER_BOOT
                null -> null
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java).apply { this.action = action }
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

        fun requestPowerOceanCheck(context: Context) {
            if (!MonitorStore(context).settings().monitoringEnabled) return
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java)
                .setAction(ACTION_REQUEST_POWEROCEAN_CHECK))
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

        fun handleScheduledAlert(context: Context) {
            if (!MonitorStore(context).settings().monitoringEnabled) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java)
                    .setAction(ACTION_REFRESH_SCHEDULED_ALERTS)
            )
        }

        fun refreshScheduledAlerts(context: Context) = handleScheduledAlert(context)

        private const val ACTION_AUDIBLE_TICK =
            "com.flossypickle.poweroutagemonitor.SERVICE_AUDIBLE_TICK"
        private const val ACTION_REFRESH_NOTIFICATION =
            "com.flossypickle.poweroutagemonitor.REFRESH_MONITOR_NOTIFICATION"
        private const val ACTION_REQUEST_POWEROCEAN_CHECK =
            "com.flossypickle.poweroutagemonitor.REQUEST_POWEROCEAN_CHECK"
        private const val ACTION_RELOAD_POWER_SOURCE =
            "com.flossypickle.poweroutagemonitor.RELOAD_POWER_SOURCE"
        private const val ACTION_REFRESH_SCHEDULED_ALERTS =
            "com.flossypickle.poweroutagemonitor.REFRESH_SCHEDULED_ALERTS"
        private const val ACTION_RESUME_AFTER_UPDATE =
            "com.flossypickle.poweroutagemonitor.RESUME_AFTER_UPDATE"
        private const val ACTION_RESUME_AFTER_BOOT =
            "com.flossypickle.poweroutagemonitor.RESUME_AFTER_BOOT"
        private const val ANDROID_PROVIDER_ID = "android_charger"
        private const val ECOFLOW_STALE_AFTER_MS = 15_000L
        private const val UI_REFRESH_INTERVAL_MS = 15_000L
        private const val STATUS_PERSIST_INTERVAL_MS = 5 * 60_000L
    }
}
