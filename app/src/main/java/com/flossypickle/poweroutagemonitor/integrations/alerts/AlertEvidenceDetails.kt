package com.flossypickle.poweroutagemonitor.integrations.alerts

import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.integrations.power.*
import java.text.DateFormat
import java.util.Date

/** Capture explanatory evidence with the event; never substitute later delivery-time readings. */
internal object AlertEvidenceDetails {
    fun append(message: AlertMessage, source: PowerSourceStore.Source, status: PowerSourceStore.Status?,
        snapshot: PowerSnapshot, now: Long = System.currentTimeMillis()): AlertMessage {
        val details = buildString {
            append("\n\nCharger: ${when (snapshot.externallyPowered) { true -> "powered"; false -> "no power"; else -> "unknown" }}.")
            if (source == PowerSourceStore.Source.ANDROID_CHARGER) {
                append(" Charger detection cannot distinguish grid loss from an unplugged cable or failed charger.")
            } else {
                if (message.kind == AlertKind.OUTAGE) {
                    val verified = status?.outageVerifiedByEcoFlow == true
                    append(if (verified) " EcoFlow verified grid loss using grid/meter evidence and live device reports."
                        else " Charger loss confirmed this outage. The EcoFlow observations below did not satisfy all freshness/corroboration checks at confirmation.")
                }
                status?.check?.let { check ->
                    append("\nEcoFlow evidence: ${SourceEvidencePresentation.qualification(check, now)}")
                    append(" · ${check.powerUpdates} power reports · ${if (check.valuesChanged) "changing" else "not changing"}.")
                    if (check.active) append("\nCheck ongoing: ${SourceEvidencePresentation.progress(check, now)}.")
                    check.observations.filter { it.label in setOf("Reported grid code", "Meter 1 reading") }.forEach {
                        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it.receivedAtEpochMs))
                        append("\n${it.label}: ${SourceEvidencePresentation.value(it)} · received $time · ${SourceEvidencePresentation.age(it.receivedAtEpochMs, now)} · ${SourceEvidencePresentation.origin(it, check)}")
                    }
                    check.ecoFlowBattery?.let {
                        append("\nEcoFlow home battery: ${it.percent}% · ${SourceEvidencePresentation.age(it.receivedAtEpochMs, now)} · ${if (it.fromDevicePush) "device push" else "sampled reply"}.")
                        append(" Receipt time is not a verified measurement time.")
                    } ?: append("\nEcoFlow home battery: not reported.")
                }
            }
        }
        return message.copy(body = message.body + details)
    }
}
