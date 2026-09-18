package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import android.os.Build
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceCheck
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import java.util.UUID

/** Report failed completed checks once per episode, never intentional idle time. */
internal class SourceCheckWarningCoordinator(private val context: Context,
    private val persistMessage: (AlertMessage) -> Boolean = { AlertDeliveryCoordinator(context).persistForEnabledProviders(it) },
    private val materializeMessages: () -> Unit = { AlertDeliveryCoordinator(context).materializePending() }) {
    private val storage = if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else context
    private val prefs = storage.getSharedPreferences("source_check_warning", Context.MODE_PRIVATE)
    fun process(check: PowerSourceCheck?, chargerPowered: Boolean?) {
        if (PowerSourceStore(context).powerOceanAssistancePaused()) return
        if (check == null || check.active || check.finishedAtEpochMs == null ||
            check.cycleState == PowerSourceCheck.CycleState.PAUSED) return
        val finished = check.finishedAtEpochMs
        if (prefs.getLong("last_finished", 0) >= finished) return
        val failed = check.cycleState == PowerSourceCheck.CycleState.FAILED || !check.gridEvidenceAvailable
        val chargerOff = failed && chargerPowered == false
        val assistance = PowerSourceStore(context).powerOceanAssistedSettings()
        if (failed && (chargerOff && !assistance.notifyOnUnknown ||
                !chargerOff && !TelegramRemoteStore(context).settings().checkWarnings)) {
            check(prefs.edit().putLong("last_finished", finished).commit()); return
        }
        val old = prefs.getString("episode", null)
        val sent = prefs.getBoolean("sent", false)
        // Losing charger power escalates a previously reported routine source warning once.
        val escalating = chargerOff && sent && !prefs.getBoolean("charger_off_sent", false)
        if (failed && old != null && sent && !escalating || !failed && old == null) {
            check(prefs.edit().putLong("last_finished", finished).commit()); return
        }
        if (!failed && !sent) {
            check(prefs.edit().putLong("last_finished", finished).remove("episode").remove("sent").remove("charger_off_sent").commit()); return
        }
        val episode = old?.takeUnless { escalating } ?: UUID.randomUUID().toString().also {
            // Keep the event identity before queueing, including across a process crash.
            check(prefs.edit().putString("episode", it).putBoolean("sent", false).putBoolean("charger_off_sent", false).commit())
        }
        val chargerFirst = assistance.enabled
        val name = MonitorStore(context).settings().deviceName
        val message = AlertMessage("source-check-$episode", if (failed) AlertKind.SOURCE_UNAVAILABLE else AlertKind.SOURCE_RESTORED,
            if (chargerOff) "Grid status is unknown, and the charger has no power" else if (failed) "EcoFlow check could not confirm grid state" else "EcoFlow grid checks recovered",
            if (chargerOff) "$name: grid status is unknown, and the charger has no power. Please confirm manually. " +
                "The completed EcoFlow check failed or lacked trustworthy grid evidence. A grid outage is possible. " +
                "EcoFlow has not confirmed an outage. " +
                (if (chargerFirst) "Charger-based outage alerts remain active." else "Grid state remains unknown until valid evidence returns.")
            else if (failed) "$name: the completed EcoFlow check failed or lacked current grid evidence. " +
                "${if (chargerFirst) "Charger monitoring continues and may report an outage without EcoFlow confirmation." else "Grid state may be unknown until a valid reading arrives."} " +
                "This warning alone does not confirm an outage. Open Status or use /status if Telegram control is enabled."
            else "$name: a completed EcoFlow check received current grid evidence again.")
        if (persistMessage(message)) {
            val edit = prefs.edit()
            if (failed) edit.putBoolean("sent", true).putBoolean("charger_off_sent", chargerOff)
            else edit.remove("episode").remove("sent").remove("charger_off_sent")
            edit.putLong("last_finished", finished)
            check(edit.commit()); materializeMessages()
        } else check(prefs.edit().putLong("last_finished", finished).commit())
    }
}
