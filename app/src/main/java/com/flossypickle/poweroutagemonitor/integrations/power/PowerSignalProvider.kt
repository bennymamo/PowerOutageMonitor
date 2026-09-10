package com.flossypickle.poweroutagemonitor.integrations.power

/** Normalized evidence from Android or a future inverter/grid integration. */
internal data class PowerSignal(
    val availability: GridAvailability,
    val observedAtEpochMs: Long,
    val providerId: String,
    val detail: String? = null
)

internal enum class GridAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

/**
 * Implementations may be event-driven (Android, WebSocket, MQTT) or perform a
 * one-shot refresh (REST/SNMP). They never decide whether an outage is confirmed.
 */
internal interface PowerSignalProvider {
    val id: String
    val displayName: String
    fun start(onSignal: (PowerSignal) -> Unit)
    fun stop()
}
