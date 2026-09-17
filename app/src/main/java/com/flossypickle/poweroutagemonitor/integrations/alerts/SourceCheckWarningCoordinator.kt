package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import android.os.Build
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceCheck
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import java.util.UUID

/** Report failed completed checks once per episode, never intentional idle time. */
internal class SourceCheckWarningCoordinator(private val context: Context) {
    private val storage = if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else context
    private val prefs = storage.getSharedPreferences("source_check_warning", Context.MODE_PRIVATE)
    fun process(check: PowerSourceCheck?) {
        if (PowerSourceStore(context).powerOceanAssistancePaused()) return
        if (check == null || check.active || check.finishedAtEpochMs == null ||
            check.cycleState == PowerSourceCheck.CycleState.PAUSED) return
        val finished = check.finishedAtEpochMs
        if (prefs.getLong("last_finished", 0) >= finished) return
        if (!TelegramRemoteStore(context).settings().checkWarnings) {
            prefs.edit().putLong("last_finished", finished).remove("episode").remove("sent").apply(); return
        }
        val failed = check.cycleState == PowerSourceCheck.CycleState.FAILED || !check.gridEvidenceAvailable
        val old = prefs.getString("episode", null)
        val sent = prefs.getBoolean("sent", false)
        if (failed && old != null && sent || !failed && old == null) {
            check(prefs.edit().putLong("last_finished", finished).commit()); return
        }
        if (!failed && !sent) {
            check(prefs.edit().putLong("last_finished", finished).remove("episode").remove("sent").commit()); return
        }
        val episode = old ?: UUID.randomUUID().toString().also {
            // Keep the event identity before queueing, including across a process crash.
            check(prefs.edit().putString("episode", it).putBoolean("sent", false).commit())
        }
        val chargerFirst = PowerSourceStore(context).powerOceanAssistedSettings().enabled
        val name = MonitorStore(context).settings().deviceName
        val message = AlertMessage("source-check-$episode", if (failed) AlertKind.SOURCE_UNAVAILABLE else AlertKind.SOURCE_RESTORED,
            if (failed) "EcoFlow check could not confirm grid state" else "EcoFlow grid checks recovered",
            if (failed) "$name: the completed EcoFlow check failed or lacked current grid evidence. " +
                "${if (chargerFirst) "Charger monitoring continues and may report an outage without EcoFlow confirmation." else "Grid state may be unknown until a valid reading arrives."} " +
                "This warning alone does not confirm an outage. Open Status or use /status if Telegram control is enabled."
            else "$name: a completed EcoFlow check received current grid evidence again.")
        val alerts = AlertDeliveryCoordinator(context)
        if (alerts.persistForEnabledProviders(message)) {
            val edit = prefs.edit()
            if (failed) edit.putBoolean("sent", true) else edit.remove("episode").remove("sent")
            edit.putLong("last_finished", finished)
            check(edit.commit()); alerts.materializePending()
        } else check(prefs.edit().putLong("last_finished", finished).commit())
    }
}
