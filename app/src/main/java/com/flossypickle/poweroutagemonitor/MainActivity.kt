package com.flossypickle.poweroutagemonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.content.ContextCompat
import com.flossypickle.poweroutagemonitor.monitoring.DeadlineScheduler
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmCoordinator
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmPlayer
import com.flossypickle.poweroutagemonitor.diagnostics.SystemHealthMonitor
import com.flossypickle.poweroutagemonitor.diagnostics.SystemHealthSnapshot
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryCoordinator
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryWorker
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryScheduler
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliverySummary
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertQueueEngine
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertProviderRegistry
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringCoordinator
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.AlertQueueStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore
import com.flossypickle.poweroutagemonitor.ui.PowerMonitorApp
import com.flossypickle.poweroutagemonitor.ui.theme.PowerOutageMonitorTheme

class MainActivity : ComponentActivity() {
    private var snapshot = androidx.compose.runtime.mutableStateOf<PowerSnapshot?>(null)
    private var monitorState = androidx.compose.runtime.mutableStateOf(OutageEngine.State())
    private var settings = androidx.compose.runtime.mutableStateOf<MonitorStore.Settings?>(null)
    private var history = androidx.compose.runtime.mutableStateOf(emptyList<EventHistoryStore.Record>())
    private var operationalHistory = androidx.compose.runtime.mutableStateOf(
        emptyList<OperationalHistoryStore.Record>()
    )
    private var audibleSettings = androidx.compose.runtime.mutableStateOf(AudibleAlarmStore.Settings())
    private var audibleAlarmActive = androidx.compose.runtime.mutableStateOf(false)
    private var lastObservationEpochMs = androidx.compose.runtime.mutableLongStateOf(0)
    private var deliveryWarning = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var alertChannels = androidx.compose.runtime.mutableStateOf("None configured")
    private var systemHealth = androidx.compose.runtime.mutableStateOf(SystemHealthSnapshot())
    private var deliverySummaries = androidx.compose.runtime.mutableStateOf(
        emptyMap<String, AlertDeliverySummary.Event>()
    )
    private var receiverRegistered = false
    private lateinit var systemHealthMonitor: SystemHealthMonitor

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startMonitoringService() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> PowerSnapshot.from(intent)?.let { snapshot.value = it }
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> refreshCurrentPowerSnapshot()
                MonitoringCoordinator.ACTION_MONITOR_STATE_CHANGED -> refreshStoredState()
                AlertDeliveryWorker.ACTION_ALERT_DELIVERY_CHANGED,
                AudibleAlarmCoordinator.ACTION_AUDIBLE_ALARM_CHANGED -> refreshStoredState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        OperationalHistoryStore(this).recordAppOpened(MonitorStore(this).settings().historyLimit)
        refreshStoredState()
        systemHealthMonitor = SystemHealthMonitor(this) { systemHealth.value = it }
        setContent {
            val currentSettings = settings.value ?: MonitorStore(this).settings()
            val darkTheme = when (currentSettings.themeMode) {
                MonitorStore.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                MonitorStore.ThemeMode.DARK -> true
                MonitorStore.ThemeMode.LIGHT -> false
            }
            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(transparent)
                } else {
                    SystemBarStyle.light(transparent, transparent)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }
            PowerOutageMonitorTheme(darkTheme = darkTheme) {
                PowerMonitorApp(
                    snapshot = snapshot.value,
                    monitorState = monitorState.value,
                    settings = currentSettings,
                    history = history.value,
                    operationalHistory = operationalHistory.value,
                    audibleSettings = audibleSettings.value,
                    audibleAlarmActive = audibleAlarmActive.value,
                    lastObservationEpochMs = lastObservationEpochMs.longValue,
                    deliveryWarning = deliveryWarning.value,
                    alertChannels = alertChannels.value,
                    systemHealth = systemHealth.value,
                    deliverySummaries = deliverySummaries.value,
                    onMonitoringEnabledChange = ::setMonitoringEnabled,
                    onSettingsChange = ::updateSettings,
                    onCompleteSetup = ::completeSetup,
                    onRetryFailedDeliveries = ::retryFailedDeliveries,
                    onClearDeliveryRecords = ::clearDeliveryRecords,
                    onHistoryLimitChange = ::updateHistoryLimit,
                    onThemeModeChange = ::updateThemeMode,
                    onAudibleSettingsChange = ::updateAudibleSettings,
                    onDismissAudibleAlarm = ::dismissAudibleAlarm,
                    onTestAudibleAlarm = ::testAudibleAlarm,
                    onClearHistory = ::clearHistory,
                    onSendTestAlert = ::sendTestAlert,
                    onAlertConfigurationChanged = ::refreshStoredState
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerAppReceiver()
        systemHealthMonitor.start()
        refreshStoredState()
        AlertDeliveryCoordinator(this).materializePending()
        if (MonitorStore(this).settings().monitoringEnabled) startMonitoringService()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        systemHealthMonitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            OperationalHistoryStore(this).recordAppClosed(
                MonitorStore(this).settings().historyLimit
            )
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        systemHealthMonitor.refresh()
    }

    private fun registerAppReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(MonitoringCoordinator.ACTION_MONITOR_STATE_CHANGED)
            addAction(AlertDeliveryWorker.ACTION_ALERT_DELIVERY_CHANGED)
            addAction(AudibleAlarmCoordinator.ACTION_AUDIBLE_ALARM_CHANGED)
        }
        val sticky = ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        PowerSnapshot.from(sticky)?.let { snapshot.value = it }
    }

    private fun refreshCurrentPowerSnapshot() {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        PowerSnapshot.from(sticky)?.let { snapshot.value = it }
    }

    private fun setMonitoringEnabled(enabled: Boolean) {
        MonitorStore(this).setMonitoringEnabled(enabled)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startMonitoringService()
            }
        } else {
            DeadlineScheduler(this).cancel()
            AudibleAlarmCoordinator(this).stop()
            stopService(Intent(this, MonitoringService::class.java))
        }
        refreshStoredState()
    }

    private fun startMonitoringService() {
        if (MonitorStore(this).settings().monitoringEnabled) MonitoringService.start(this)
    }

    private fun updateSettings(
        outageDelayMs: Long,
        restoreDelayMs: Long,
        sendRestoreNotification: Boolean,
        deviceName: String
    ) {
        MonitorStore(this).updateSettings(
            outageDelayMs,
            restoreDelayMs,
            sendRestoreNotification,
            deviceName
        )
        refreshStoredState()
    }

    private fun completeSetup(deviceName: String, outageDelayMs: Long, restoreDelayMs: Long) {
        val store = MonitorStore(this)
        store.updateSettings(
            outageDelayMs = outageDelayMs,
            restoreDelayMs = restoreDelayMs,
            sendRestoreNotification = true,
            deviceName = deviceName
        )
        store.setSetupCompleted()
        refreshStoredState()
        setMonitoringEnabled(true)
    }

    private fun retryFailedDeliveries() {
        AlertQueueStore(this).retryFailed(System.currentTimeMillis()).forEach { item ->
            AlertDeliveryScheduler(this).scheduleNow(item.id)
        }
        refreshStoredState()
    }

    private fun clearDeliveryRecords() {
        AlertQueueStore(this).clearTerminal()
        refreshStoredState()
    }

    private fun updateHistoryLimit(limit: Int) {
        MonitorStore(this).setHistoryLimit(limit)
        EventHistoryStore(this).trimTo(limit)
        OperationalHistoryStore(this).trimTo(limit)
        refreshStoredState()
    }

    private fun updateThemeMode(mode: MonitorStore.ThemeMode) {
        MonitorStore(this).setThemeMode(mode)
        refreshStoredState()
    }

    private fun updateAudibleSettings(value: AudibleAlarmStore.Settings) {
        AudibleAlarmStore(this).updateSettings(value)
        val store = MonitorStore(this)
        AudibleAlarmCoordinator(this).reconcile(store.state(), store.lastSnapshot())
        MonitoringService.refreshNotification(this)
        refreshStoredState()
    }

    private fun dismissAudibleAlarm() {
        AudibleAlarmCoordinator(this).dismissCurrent()
        MonitoringService.refreshNotification(this)
        refreshStoredState()
    }

    private fun testAudibleAlarm() {
        AudibleAlarmPlayer(this).play(audibleSettings.value.useMaximumVolume)
    }

    private fun clearHistory() {
        EventHistoryStore(this).clear()
        OperationalHistoryStore(this).clear()
        refreshStoredState()
    }

    private fun sendTestAlert(message: AlertMessage): Boolean =
        AlertDeliveryCoordinator(this).enqueueTest(message)

    private fun refreshStoredState() {
        val store = MonitorStore(this)
        monitorState.value = store.state()
        settings.value = store.settings()
        lastObservationEpochMs.longValue = store.lastObservationEpochMs()
        history.value = EventHistoryStore(this).read()
        operationalHistory.value = OperationalHistoryStore(this).read()
        audibleSettings.value = AudibleAlarmStore(this).settings()
        audibleAlarmActive.value = AudibleAlarmCoordinator(this).isActive(
            monitorState.value,
            store.lastSnapshot()
        )
        val deliveries = AlertQueueStore(this).read()
        deliverySummaries.value = AlertDeliverySummary.byEvent(deliveries)
        alertChannels.value = AlertProviderRegistry(this).statusSummary()
        deliveryWarning.value = when {
            deliveries.any { it.status == AlertQueueEngine.Status.FAILED } ->
                "An alert failed. Open Diagnostics for the reason."
            deliveries.any { it.status == AlertQueueEngine.Status.RETRYING } ->
                "An alert is waiting to retry when delivery is possible."
            else -> null
        }
    }
}
