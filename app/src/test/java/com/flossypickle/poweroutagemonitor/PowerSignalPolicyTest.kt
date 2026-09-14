package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignal
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignalHealth
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignalPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerSignalPolicyTest {
    @Test
    fun `fresh provider evidence keeps its availability`() {
        val result = PowerSignalPolicy.evaluate(
            signal = signal(GridAvailability.UNAVAILABLE, observedAtEpochMs = 9_000L),
            nowEpochMs = 10_000L,
            staleAfterMs = 2_000L
        )

        assertEquals(GridAvailability.UNAVAILABLE, result.availability)
        assertEquals(PowerSignalHealth.FRESH, result.health)
    }

    @Test
    fun `missing evidence is unknown`() {
        val result = PowerSignalPolicy.evaluate(null, 10_000L, 2_000L)

        assertEquals(GridAvailability.UNKNOWN, result.availability)
        assertEquals(PowerSignalHealth.MISSING, result.health)
    }

    @Test
    fun `provider unknown cannot become an outage`() {
        val result = PowerSignalPolicy.evaluate(
            signal = signal(GridAvailability.UNKNOWN, observedAtEpochMs = 9_000L),
            nowEpochMs = 10_000L,
            staleAfterMs = 2_000L
        )

        assertEquals(GridAvailability.UNKNOWN, result.availability)
        assertEquals(PowerSignalHealth.PROVIDER_UNKNOWN, result.health)
    }

    @Test
    fun `stale unavailable evidence becomes unknown`() {
        val result = PowerSignalPolicy.evaluate(
            signal = signal(GridAvailability.UNAVAILABLE, observedAtEpochMs = 7_999L),
            nowEpochMs = 10_000L,
            staleAfterMs = 2_000L
        )

        assertEquals(GridAvailability.UNKNOWN, result.availability)
        assertEquals(PowerSignalHealth.STALE, result.health)
    }

    @Test
    fun `small device clock skew is accepted`() {
        val result = PowerSignalPolicy.evaluate(
            signal = signal(GridAvailability.AVAILABLE, observedAtEpochMs = 11_000L),
            nowEpochMs = 10_000L,
            staleAfterMs = 2_000L,
            futureToleranceMs = 1_000L
        )

        assertEquals(GridAvailability.AVAILABLE, result.availability)
        assertEquals(PowerSignalHealth.FRESH, result.health)
    }

    @Test
    fun `invalid or far future timestamp becomes unknown`() {
        val invalid = PowerSignalPolicy.evaluate(
            signal = signal(GridAvailability.UNAVAILABLE, observedAtEpochMs = 0L),
            nowEpochMs = 10_000L,
            staleAfterMs = 2_000L
        )
        val future = PowerSignalPolicy.evaluate(
            signal = signal(GridAvailability.UNAVAILABLE, observedAtEpochMs = 12_001L),
            nowEpochMs = 10_000L,
            staleAfterMs = 2_000L,
            futureToleranceMs = 2_000L
        )

        assertEquals(GridAvailability.UNKNOWN, invalid.availability)
        assertEquals(PowerSignalHealth.INVALID_TIMESTAMP, invalid.health)
        assertEquals(GridAvailability.UNKNOWN, future.availability)
        assertEquals(PowerSignalHealth.INVALID_TIMESTAMP, future.health)
    }

    private fun signal(availability: GridAvailability, observedAtEpochMs: Long) = PowerSignal(
        availability = availability,
        observedAtEpochMs = observedAtEpochMs,
        providerId = "test"
    )
}
