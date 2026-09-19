package com.flossypickle.poweroutagemonitor.integrations.alerts.telegram

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.flossypickle.poweroutagemonitor.monitoring.*
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmCoordinator
import com.flossypickle.poweroutagemonitor.integrations.alerts.*
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import java.text.DateFormat
import java.util.Date

/** All remotely permitted actions live here; credentials and electrical controls are excluded. */
internal class TelegramRemoteActions(private val context: Context) {
    fun execute(command: TelegramRemotePolicy.Command): String {
        val monitor = MonitorStore(context); val source = PowerSourceStore(context); val remote = TelegramRemoteStore(context)
        val result = when (command.name) {
            "status" -> status()
            "help" -> TelegramRemotePolicy.commands.joinToString("\n") { "/${it.first}: ${it.second}" } +
                "\nQuiet affects automatic Telegram alerts only. Remote control stays on when monitoring is off."
            "stop_sound" -> { AudibleAlarmCoordinator(context).dismissCurrent(); MonitoringService.refreshNotification(context)
                "Current audible alarm acknowledged and stopped. A new outage can sound again." }
            "quiet" -> {
                val minutes = if (command.argument.isBlank()) remote.settings().quietMinutes else command.argument.toIntOrNull()
                if (minutes == null || minutes !in 1..1440) "Use /quiet or /quiet 60 (1–1440 minutes)."
                else { remote.quiet(System.currentTimeMillis() + minutes * 60_000L)
                    "Automatic Telegram alerts quiet until ${time(remote.settings().quietUntilEpochMs)}. Commands, other channels and sound remain active." }
            }
            "unquiet" -> { remote.quiet(0); "Automatic Telegram alerts resumed. Quiet-period alerts are recorded as skipped and are not replayed." }
            "monitor_on", "monitor_off" -> {
                val enabled = command.name == "monitor_on"
                monitor.setMonitoringEnabled(enabled)
                if (enabled) { monitor.setRestoredDeliveriesPaused(false); AlertDeliveryCoordinator(context).materializePending() }
                else { DeadlineScheduler(context).cancel(); ScheduledAlertCoordinator(context).stop(); AudibleAlarmCoordinator(context).stop() }
                MonitoringService.syncHosting(context)
                "Monitoring ${if (enabled) "active" else "inactive"}. Telegram remote control remains available."
            }
            "check_ecoflow" -> when {
                !monitor.settings().monitoringEnabled -> "Monitoring is inactive. Send /monitor_on first."
                source.selectedSource() != PowerSourceStore.Source.ECOFLOW_ACCOUNT -> "Select and configure EcoFlow account monitoring in the app first."
                source.powerOceanAssistancePaused() -> "EcoFlow is paused. Send /ecoflow_on first."
                else -> { MonitoringService.requestPowerOceanCheck(context)
                    "EcoFlow check requested. Send /status shortly to see the last check, current readings and result. A completed failed/inconclusive check can send a warning." }
            }
            "charger_on" -> {
                if (source.selectedSource() == PowerSourceStore.Source.ECOFLOW_ACCOUNT) source.setPowerOceanAssistedSettings(source.powerOceanAssistedSettings().copy(enabled = true))
                else source.select(PowerSourceStore.Source.ANDROID_CHARGER)
                MonitoringService.reloadPowerSource(context); "Charger watching enabled. ${if (monitor.settings().monitoringEnabled) "Monitoring is active." else "Monitoring is inactive; use /monitor_on to start."}"
            }
            "charger_off" -> {
                if (!source.powerOceanReadyToActivate() || source.powerOceanAssistancePaused())
                    "Charger watching was kept on. Configure and resume a verified EcoFlow source first; /monitor_off disables all power monitoring."
                else { source.setPowerOceanAssistedSettings(source.powerOceanAssistedSettings().copy(enabled = false))
                    source.select(PowerSourceStore.Source.ECOFLOW_ACCOUNT); MonitoringService.reloadPowerSource(context)
                    "Charger watching disabled. EcoFlow is the power source. Monitoring ${if (monitor.settings().monitoringEnabled) "active" else "inactive"}." }
            }
            "ecoflow_on" -> {
                if (!source.powerOceanReadyToActivate()) "EcoFlow must be configured and its grid profile confirmed in the app first."
                else { if (source.selectedSource() != PowerSourceStore.Source.ECOFLOW_ACCOUNT) {
                    source.setPowerOceanAssistedSettings(source.powerOceanAssistedSettings().copy(enabled = true))
                    source.select(PowerSourceStore.Source.ECOFLOW_ACCOUNT) }
                    source.setPowerOceanAssistancePaused(false); MonitoringService.reloadPowerSource(context)
                    "EcoFlow resumed using its configured check schedule. Monitoring ${if (monitor.settings().monitoringEnabled) "active" else "inactive"}." }
            }
            "ecoflow_off" -> { source.setPowerOceanAssistedSettings(source.powerOceanAssistedSettings().copy(enabled = true))
                source.setPowerOceanAssistancePaused(true); MonitoringService.reloadPowerSource(context)
                "EcoFlow paused. Charger watching remains enabled; /monitor_off disables all monitoring." }
            else -> "Unknown command. Send /help."
        }
        if (command.name !in setOf("status", "help")) OperationalHistoryStore(context).recordRemoteCommand(command.name, monitor.settings().historyLimit)
        context.sendBroadcast(Intent(MonitoringCoordinator.ACTION_MONITOR_STATE_CHANGED).setPackage(context.packageName))
        return result
    }
    fun status(): String {
        val monitor = MonitorStore(context); val settings = monitor.settings(); val source = PowerSourceStore(context)
        val last = source.lastStatus(); val check = last?.check
        val snapshot = PowerSnapshot.from(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) ?: monitor.lastSnapshot()
        return buildString {
            appendLine(settings.deviceName)
            appendLine("Monitoring ${if (settings.monitoringEnabled) "active" else "inactive"}")
            appendLine("Grid: ${if (!settings.monitoringEnabled) "not being monitored" else if (source.selectedSource() != PowerSourceStore.Source.ANDROID_CHARGER && last?.availability == com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability.UNKNOWN) "unknown: no current evidence" else monitor.state().phase.name.lowercase().replace('_', ' ')}")
            appendLine("Charger: ${when(snapshot?.externallyPowered){true -> "powered";false -> "no power";else -> "unknown"}} · Battery ${snapshot?.batteryPercent ?: "?"}%")
            appendLine("Source: ${source.selectedSource().name.lowercase().replace('_', ' ')}")
            if (source.selectedSource() == PowerSourceStore.Source.ECOFLOW_ACCOUNT) {
                val assisted = source.powerOceanAssistedSettings()
                val failureStreak = source.powerOceanPoweredFailureStreak()
                appendLine("Charger watching: ${if (assisted.enabled) "on" else "off"} · EcoFlow: ${if(source.powerOceanAssistancePaused()) "paused" else "on"}")
                appendLine("Last check: ${time(check?.requestedAtEpochMs)} · ${check?.cycleState?.name?.lowercase() ?: "not checked"}")
                appendLine("Last device update: ${time(check?.liveReportAtEpochMs)}")
                appendLine("Updates: ${check?.deviceUpdates ?: 0} · Values: ${check?.dataHealth?.name?.lowercase()?.replace('_',' ') ?: "unknown"}")
                check?.observations?.filter { it.label in setOf("Reported grid code", "Meter 1 reading") }?.forEach { appendLine("${it.label}: ${it.value} · ${time(it.receivedAtEpochMs)}") }
                appendLine("Next check: ${when { !settings.monitoringEnabled -> "monitoring inactive"; source.powerOceanAssistancePaused() -> "paused"; check?.active == true -> "after this check"; else -> time(check?.nextCheckAtEpochMs) }}")
                if (snapshot?.externallyPowered == true && failureStreak > 0) {
                    appendLine("EcoFlow retries: $failureStreak/${assisted.poweredFailureThreshold} failures · ${retryInterval(assisted.outageSeconds)}")
                }
                appendLine("${last?.detail.orEmpty()}")
            }
            appendLine("Sound: ${if(AudibleAlarmCoordinator(context).isActive(monitor.state(), snapshot)) "playing: /stop_sound" else "not playing"}")
            val remote = TelegramRemoteStore(context)
            append("Telegram alerts: ${if(remote.isQuiet()) "quiet until ${time(remote.settings().quietUntilEpochMs)}" else "normal"}")
        }
    }
    private fun time(value: Long?) = value?.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)) } ?: "not scheduled / unavailable"
    private fun retryInterval(seconds: Int) = when {
        seconds == 0 -> "manual checks only"
        seconds % 3600 == 0 -> "every ${seconds / 3600}h"
        seconds % 60 == 0 -> "every ${seconds / 60}m"
        else -> "every ${seconds}s"
    }
}
