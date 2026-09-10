package com.flossypickle.poweroutagemonitor.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
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
    val configuredAlertProviders: String = "None"
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
        append("Alert providers: $configuredAlertProviders")
    }

    private fun yesNo(value: Boolean) = if (value) "Yes" else "No"
}

internal class DiagnosticsCollector(private val context: Context) {
    fun collect(
        settings: MonitorStore.Settings,
        state: OutageEngine.State,
        snapshot: PowerSnapshot?,
        lastObservationEpochMs: Long
    ): DiagnosticsReport = DiagnosticsReport(
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
        internetAvailable = hasInternet(),
        batteryOptimizationExcluded = ignoresBatteryOptimization(),
        backgroundRestricted = isBackgroundRestricted(),
        notificationsAllowed = notificationsAllowed()
    )

    private fun hasInternet(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun ignoresBatteryOptimization(): Boolean = if (Build.VERSION.SDK_INT >= 23) {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    } else true

    private fun isBackgroundRestricted(): Boolean = if (Build.VERSION.SDK_INT >= 28) {
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    } else false

    private fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

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
