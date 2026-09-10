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
import androidx.core.content.ContextCompat
import com.flossypickle.poweroutagemonitor.monitoring.DeadlineScheduler
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryCoordinator
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryWorker
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertQueueEngine
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringCoordinator
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.AlertQueueStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.ui.PowerMonitorApp
import com.flossypickle.poweroutagemonitor.ui.theme.PowerOutageMonitorTheme

class MainActivity : ComponentActivity() {
    private var snapshot = androidx.compose.runtime.mutableStateOf<PowerSnapshot?>(null)
    private var monitorState = androidx.compose.runtime.mutableStateOf(OutageEngine.State())
    private var settings = androidx.compose.runtime.mutableStateOf<MonitorStore.Settings?>(null)
    private var history = androidx.compose.runtime.mutableStateOf(emptyList<EventHistoryStore.Record>())
    private var lastObservationEpochMs = androidx.compose.runtime.mutableLongStateOf(0)
    private var deliveryWarning = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var receiverRegistered = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startMonitoringService() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> PowerSnapshot.from(intent)?.let { snapshot.value = it }
                MonitoringCoordinator.ACTION_MONITOR_STATE_CHANGED -> refreshStoredState()
                AlertDeliveryWorker.ACTION_ALERT_DELIVERY_CHANGED -> refreshStoredState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        refreshStoredState()
        setContent {
            PowerOutageMonitorTheme {
                PowerMonitorApp(
                    snapshot = snapshot.value,
                    monitorState = monitorState.value,
                    settings = settings.value ?: MonitorStore(this).settings(),
                    history = history.value,
                    lastObservationEpochMs = lastObservationEpochMs.longValue,
                    deliveryWarning = deliveryWarning.value,
                    onMonitoringEnabledChange = ::setMonitoringEnabled,
                    onSettingsChange = ::updateSettings,
                    onCompleteSetup = ::completeSetup
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerAppReceiver()
        refreshStoredState()
        AlertDeliveryCoordinator(this).materializePending()
        if (MonitorStore(this).settings().monitoringEnabled) startMonitoringService()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun registerAppReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(MonitoringCoordinator.ACTION_MONITOR_STATE_CHANGED)
            addAction(AlertDeliveryWorker.ACTION_ALERT_DELIVERY_CHANGED)
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

    private fun refreshStoredState() {
        val store = MonitorStore(this)
        monitorState.value = store.state()
        settings.value = store.settings()
        lastObservationEpochMs.longValue = store.lastObservationEpochMs()
        history.value = EventHistoryStore(this).read()
        val deliveries = AlertQueueStore(this).read()
        deliveryWarning.value = when {
            deliveries.any { it.status == AlertQueueEngine.Status.FAILED } ->
                "An alert failed. Open Diagnostics for the reason."
            deliveries.any { it.status == AlertQueueEngine.Status.RETRYING } ->
                "An alert is waiting to retry when delivery is possible."
            else -> null
        }
    }
}
