package com.flossypickle.poweroutagemonitor.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertQueueEngine
import com.flossypickle.poweroutagemonitor.storage.AlertQueueStore
import java.text.DateFormat
import java.util.Date

internal data class DiagnosticsReport(
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val monitoringEnabled: Boolean,
    val serviceRunning: Boolean,
    val phase: OutageEngine.Phase,
    val externalPower: Boolean?,
    val batteryPercent: Int?,
    val lastObservation: String,
    val internetAvailable: Boolean,
    val batteryOptimizationExcluded: Boolean,
    val backgroundRestricted: Boolean,
    val notificationsAllowed: Boolean,
    val bootStartupConfigured: Boolean = true,
    val configuredPowerProviders: String = "Android external power",
    val configuredAlertProviders: String = "None",
    val queuedDeliveries: Int = 0,
    val retryingDeliveries: Int = 0,
    val sentDeliveries: Int = 0,
    val failedDeliveries: Int = 0,
    val lastDeliveryError: String? = null
) {
    fun asPlainText(): String = buildString {
        appendLine("Power Outage Monitor diagnostics")
        appendLine("App version: $appVersion")
        appendLine("Android: $androidVersion")
        appendLine("Device: $device")
        appendLine("Monitoring enabled: ${yesNo(monitoringEnabled)}")
        appendLine("Monitoring service running: ${yesNo(serviceRunning)}")
        appendLine("State: ${phase.name}")
        appendLine("External power: ${externalPower?.let(::yesNo) ?: "Unknown"}")
        appendLine("Battery: ${batteryPercent?.let { "$it%" } ?: "Unknown"}")
        appendLine("Last observation: $lastObservation")
        appendLine("Internet available: ${yesNo(internetAvailable)}")
        appendLine("Battery optimization excluded: ${yesNo(batteryOptimizationExcluded)}")
        appendLine("Background restricted: ${yesNo(backgroundRestricted)}")
        appendLine("Notifications allowed: ${yesNo(notificationsAllowed)}")
        appendLine("Boot startup configured: ${yesNo(bootStartupConfigured)}")
        appendLine("Power providers: $configuredPowerProviders")
        appendLine("Alert providers: $configuredAlertProviders")
        appendLine("Queued deliveries: $queuedDeliveries")
        appendLine("Retrying deliveries: $retryingDeliveries")
        appendLine("Sent deliveries: $sentDeliveries")
        appendLine("Failed deliveries: $failedDeliveries")
        lastDeliveryError?.let { appendLine("Last delivery error: $it") }
    }

    private fun yesNo(value: Boolean) = if (value) "Yes" else "No"
}

internal class DiagnosticsCollector(private val context: Context) {
    fun collect(
        settings: MonitorStore.Settings,
        state: OutageEngine.State,
        snapshot: PowerSnapshot?,
        lastObservationEpochMs: Long
    ): DiagnosticsReport {
        val telegram = TelegramConfigStore(context).config()
        val deliveries = AlertQueueStore(context).read()
        val health = SystemHealthSnapshot.capture(context)
        return DiagnosticsReport(
        appVersion = appVersionName(),
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        monitoringEnabled = settings.monitoringEnabled,
        serviceRunning = MonitoringService.isRunning,
        phase = state.phase,
        externalPower = snapshot?.externallyPowered,
        batteryPercent = snapshot?.batteryPercent,
        lastObservation = if (lastObservationEpochMs > 0) {
            DateFormat.getDateTimeInstance().format(Date(lastObservationEpochMs))
        } else "Never",
        internetAvailable = health.internetAvailable,
        batteryOptimizationExcluded = health.batteryOptimizationExcluded,
        backgroundRestricted = health.backgroundRestricted,
        notificationsAllowed = health.notificationsAllowed,
        configuredAlertProviders = when {
            telegram.enabled -> "Telegram enabled (${telegram.destinations.size} destination(s))"
            telegram.hasToken -> "Telegram saved, disabled"
            else -> "None"
        },
        queuedDeliveries = deliveries.count {
            it.status == AlertQueueEngine.Status.PENDING || it.status == AlertQueueEngine.Status.IN_FLIGHT
        },
        retryingDeliveries = deliveries.count { it.status == AlertQueueEngine.Status.RETRYING },
        sentDeliveries = deliveries.count { it.status == AlertQueueEngine.Status.SENT },
        failedDeliveries = deliveries.count { it.status == AlertQueueEngine.Status.FAILED },
        lastDeliveryError = deliveries.asReversed().firstNotNullOfOrNull { it.lastError }
        )
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "Unknown"
    }.getOrDefault("Unknown")
}
