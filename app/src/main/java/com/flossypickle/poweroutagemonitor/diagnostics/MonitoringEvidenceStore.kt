package com.flossypickle.poweroutagemonitor.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertQueueEngine
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceCheck
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

/** Bounded, credential-free timing evidence, included in the explicit diagnostics export.
 * Android validation describes the phone network, not reachability of the inverter. */
internal class MonitoringEvidenceStore(context: Context) {
    private val appContext = context.applicationContext
    private val storage = if (Build.VERSION.SDK_INT >= 24) appContext.createDeviceProtectedStorageContext() else appContext
    private val preferences = storage.getSharedPreferences("monitoring_evidence", Context.MODE_PRIVATE)

    enum class Event { CHARGER_CHANGED, NETWORK_CHANGED, CHECK_STARTED, CHECK_FINISHED, ALERT_STARTED, ALERT_FINISHED,
        REMOTE_RECEIVED, REMOTE_IGNORED, REMOTE_REPLY_SENT, REMOTE_REPLY_FAILED, REMOTE_POLL_FAILED }

    fun record(event: Event, check: PowerSourceCheck? = null, delivery: AlertQueueEngine.Status? = null,
        command: String? = null, requestAgeMs: Long? = null) {
        // Diagnostics must never interrupt monitoring or delivery if storage is unavailable.
        runCatching {
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val monitor = MonitorStore(appContext)
            val line = buildString {
                append("utcMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} event=$event")
                append(" charger=${monitor.lastSnapshot()?.externallyPowered} phase=${monitor.state().phase}")
                append(" source=${PowerSourceStore(appContext).selectedSource()}")
                append(" phoneTransport=${when {
                    network == null -> "none"
                    network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "other"
                }}")
                append(" phoneValidated=${network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}")
                check?.let {
                    append(" check=${it.cycleState} requestedMs=${it.requestedAtEpochMs}")
                    append(" liveReportMs=${it.liveReportAtEpochMs} finishedMs=${it.finishedAtEpochMs}")
                    append(" gridEvidence=${it.gridEvidenceAvailable} updates=${it.deviceUpdates}")
                    append(" powerUpdates=${it.powerUpdates} powerChanged=${it.valuesChanged}")
                    it.observations.filter { observation -> observation.label in setOf("Reported grid code", "Meter 1 reading") }
                        .forEach { observation ->
                            observation.value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { value ->
                                val key = if (observation.label == "Reported grid code") "gridCode" else "meterPower"
                                append(" $key=$value ${key}ReceivedMs=${observation.receivedAtEpochMs} ${key}Push=${observation.fromDevicePush}")
                            }
                        }
                }
                delivery?.let { append(" delivery=$it") }
                command?.takeIf { it in com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemotePolicy.commands.map { entry -> entry.first } }
                    ?.let { append(" command=$it") }
                requestAgeMs?.let { append(" requestAgeMs=$it") }
            }
            synchronized(lock) {
                val retained = (read() + line).takeLast(500)
                kotlin.check(preferences.edit().putString("records", retained.joinToString("\n")).commit())
            }
            android.util.Log.i("MonitoringEvidence", line)
        }
    }

    fun read(): List<String> = synchronized(lock) {
        preferences.getString("records", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
    }

    companion object { private val lock = Any() }
}
