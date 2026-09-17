package com.flossypickle.poweroutagemonitor.integrations.alerts

import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import java.text.DateFormat
import java.util.Date

/** Capture explanatory evidence with the event; never substitute later delivery-time readings. */
internal object AlertEvidenceDetails {
    fun append(message: AlertMessage, source: PowerSourceStore.Source, status: PowerSourceStore.Status?,
        snapshot: PowerSnapshot): AlertMessage {
        val details = buildString {
            append("\n\nCharger: ${when (snapshot.externallyPowered) { true -> "powered"; false -> "no power"; else -> "unknown" }}.")
            if (source == PowerSourceStore.Source.ANDROID_CHARGER) {
                append(" Charger detection cannot distinguish grid loss from an unplugged cable or failed charger.")
            } else {
                if (status?.detail?.startsWith("Charger power lost.") == true)
                    append(" Detection is based on charger loss; EcoFlow did not verify grid loss.")
                status?.check?.let { check ->
                    append("\nEcoFlow check: ${when { check.active -> "still checking"; check.gridEvidenceAvailable -> "current grid evidence received"; else -> "no current grid evidence" }}.")
                    check.observations.filter { it.label in setOf("Reported grid code", "Meter 1 reading") }.forEach {
                        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it.receivedAtEpochMs))
                        append("\n${it.label}: ${it.value} · received $time")
                    }
                }
            }
        }
        return message.copy(body = message.body + details)
    }
}
