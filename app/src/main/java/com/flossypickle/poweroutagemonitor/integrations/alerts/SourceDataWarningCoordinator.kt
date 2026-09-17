package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import android.os.Build
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import java.util.UUID

/** A durable warning episode, independent of outage alerts and delivery destinations. */
internal class SourceDataWarningCoordinator(private val context: Context) {
    private val storage = if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else context
    private val preferences = storage.getSharedPreferences("power_sources", Context.MODE_PRIVATE)
    fun process(possiblyStalled: Boolean?, enabled: Boolean) = synchronized(lock) {
        if (possiblyStalled == null) return@synchronized
        if (!possiblyStalled) {
            if (preferences.contains("account_data_warning_episode")) {
                check(preferences.edit().remove("account_data_warning_episode").remove("account_data_warning_sent").commit())
            }
            return@synchronized
        }
        if (!enabled) return@synchronized
        val episode = preferences.getString("account_data_warning_episode", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("account_data_warning_episode", it).commit())
        }
        if (preferences.getBoolean("account_data_warning_sent", false)) return@synchronized
        val alerts = AlertDeliveryCoordinator(context)
        val message = AlertMessage("source-data-$episode", AlertKind.SOURCE_DATA_WARNING,
            "EcoFlow readings may be stuck",
            "${MonitorStore(context).settings().deviceName}: power readings were identical across three consecutive EcoFlow checks. " +
                "The feed may be stalled, or the load genuinely steady. This is a data warning, not an outage confirmation. " +
                "Charger monitoring continues in charger-first mode. Open Status to pause/resume assistance, or enable automatic exclusion in EcoFlow settings.")
        if (alerts.persistForEnabledProviders(message)) {
            check(preferences.edit().putBoolean("account_data_warning_sent", true).commit())
            alerts.materializePending()
        }
    }
    companion object { private val lock = Any() }
}
