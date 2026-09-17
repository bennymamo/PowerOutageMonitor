package com.flossypickle.poweroutagemonitor.integrations.power

/** Normalized evidence from Android or a future inverter/grid integration. */
internal data class PowerSignal(
    val availability: GridAvailability,
    val observedAtEpochMs: Long,
    val providerId: String,
    val detail: String? = null,
    val recoveryPending: Boolean = false,
    // Receipt of the qualifying device report, distinct from a provider health update.
    val evidenceReceivedAtEpochMs: Long? = null,
    // Null means this update did not assess consecutive power readings.
    val dataPossiblyStalled: Boolean? = null
)

internal enum class GridAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

internal enum class PowerSignalHealth {
    FRESH,
    MISSING,
    PROVIDER_UNKNOWN,
    STALE,
    INVALID_TIMESTAMP
}

internal data class EvaluatedPowerSignal(
    val availability: GridAvailability,
    val health: PowerSignalHealth,
    val signal: PowerSignal?
)

/**
 * Safety boundary shared by every present and future grid source.
 *
 * A provider timeout, an old cloud value, or a malformed clock must become
 * UNKNOWN. Only fresh evidence is allowed to enter the outage state machine.
 */
internal object PowerSignalPolicy {
    const val DEFAULT_FUTURE_TOLERANCE_MS = 30_000L

    fun evaluate(
        signal: PowerSignal?,
        nowEpochMs: Long,
        staleAfterMs: Long,
        futureToleranceMs: Long = DEFAULT_FUTURE_TOLERANCE_MS
    ): EvaluatedPowerSignal {
        require(staleAfterMs >= 0)
        require(futureToleranceMs >= 0)

        val health = when {
            signal == null -> PowerSignalHealth.MISSING
            signal.availability == GridAvailability.UNKNOWN -> PowerSignalHealth.PROVIDER_UNKNOWN
            signal.observedAtEpochMs <= 0L ||
                signal.observedAtEpochMs > nowEpochMs + futureToleranceMs ->
                PowerSignalHealth.INVALID_TIMESTAMP
            nowEpochMs - signal.observedAtEpochMs > staleAfterMs -> PowerSignalHealth.STALE
            else -> PowerSignalHealth.FRESH
        }
        return EvaluatedPowerSignal(
            availability = if (health == PowerSignalHealth.FRESH) {
                signal!!.availability
            } else {
                GridAvailability.UNKNOWN
            },
            health = health,
            signal = signal
        )
    }
}

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
