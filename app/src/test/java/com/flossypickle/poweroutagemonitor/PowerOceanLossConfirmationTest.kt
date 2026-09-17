package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanGridCorrelation
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanLossConfirmation
import org.junit.Assert.*
import org.junit.Test

class PowerOceanLossConfirmationTest {
    @Test fun enabledConfirmationRequiresAllThreeLossObservations() {
        val offGrid = snapshot(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, 0.0)
        assertEquals(GridAvailability.UNAVAILABLE, PowerOceanLossConfirmation.evaluate(offGrid, false, true).availability)
        assertEquals(GridAvailability.UNKNOWN, PowerOceanLossConfirmation.evaluate(offGrid, true, true).availability)
        assertEquals(GridAvailability.UNKNOWN, PowerOceanLossConfirmation.evaluate(offGrid, null, true).availability)
        assertEquals(GridAvailability.UNKNOWN, PowerOceanLossConfirmation.evaluate(offGrid.copy(currentMeterValue = null), false, true).availability)
        assertEquals(GridAvailability.UNKNOWN, PowerOceanLossConfirmation.evaluate(offGrid.copy(currentMeterValue = 12.0), false, true).availability)
    }

    @Test fun disabledConfirmationDoesNotRequireChargerLoss() {
        assertEquals(GridAvailability.UNAVAILABLE, PowerOceanLossConfirmation.evaluate(
            snapshot(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, 0.0), true, false).availability)
    }

    @Test fun chargerRemainingOffNeverBlocksProbableReturnOrFullRecovery() {
        for (state in listOf(PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY, PowerOceanGridCorrelation.State.INVERTER_CONNECTED)) {
            assertEquals(GridAvailability.AVAILABLE, PowerOceanLossConfirmation.evaluate(snapshot(state, -800.0), false, true).availability)
            assertEquals(GridAvailability.AVAILABLE, PowerOceanLossConfirmation.evaluate(snapshot(state, null), null, true).availability)
        }
    }

    @Test fun cloudFailureCannotBecomeAnOutageEvenWhenChargerIsOff() {
        assertEquals(GridAvailability.UNKNOWN, PowerOceanLossConfirmation.evaluate(
            snapshot(PowerOceanGridCorrelation.State.UNKNOWN, 0.0), false, true).availability)
    }

    private fun snapshot(state: PowerOceanGridCorrelation.State, meter: Double?) =
        PowerOceanGridCorrelation.Snapshot(state, meter == 0.0, null, meter)
    @Test fun changingCurrentFeedRefreshesConnectedEvidenceWithoutChangingGridCodeTime() {
        val grid = snapshot(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, 12.0)
            .copy(evidenceReceivedAtUtcMillis = 500)
        val live = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanLiveCheck.Status(
            1000, 3000, 0, powerUpdates = 3, valuesChanged = true)
        assertEquals(3000L, PowerOceanLossConfirmation.evidenceReceivedAt(grid, live, 3000, 3))
        assertEquals(500L, grid.evidenceReceivedAtUtcMillis)
        assertEquals(500L, PowerOceanLossConfirmation.evidenceReceivedAt(grid, live.copy(powerUpdates = 2), 3000, 3))
        assertEquals(500L, PowerOceanLossConfirmation.evidenceReceivedAt(grid, live.copy(valuesChanged = false), 3000, 3))
        assertEquals(500L, PowerOceanLossConfirmation.evidenceReceivedAt(grid.copy(currentMeterValue = null), live, 3000, 3))
        assertEquals(500L, PowerOceanLossConfirmation.evidenceReceivedAt(grid, live, 100_000, 3))
    }
    @Test fun changingHomeLoadCannotInventGridRecoveryFromAnOffGridCode() {
        val grid = snapshot(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, 0.0)
            .copy(evidenceReceivedAtUtcMillis = 1500)
        val live = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanLiveCheck.Status(
            1000, 3000, 0, powerUpdates = 3, valuesChanged = true)
        assertEquals(1500L, PowerOceanLossConfirmation.evidenceReceivedAt(grid, live, 3000, 3))
    }

    @Test fun cachedConnectedReplyNeedsChangingLivePowerAndNonzeroMeterActivity() {
        val grid = snapshot(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, 5.0)
            .copy(evidenceReceivedAtUtcMillis = 1500)
        val live = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanLiveCheck.Status(
            1000, 3000, 0, powerUpdates = 3, valuesChanged = true)
        assertEquals(3000L, PowerOceanLossConfirmation.evidenceReceivedAt(grid, live, 3000, 3, false))
        assertNull(PowerOceanLossConfirmation.evidenceReceivedAt(grid.copy(currentMeterValue = 0.0), live, 3000, 3, false))
        assertNull(PowerOceanLossConfirmation.evidenceReceivedAt(grid, live.copy(powerUpdates = 1), 3000, 3, false))
        assertNull(PowerOceanLossConfirmation.evidenceReceivedAt(grid, null, 3000, 3, false))
    }

}
