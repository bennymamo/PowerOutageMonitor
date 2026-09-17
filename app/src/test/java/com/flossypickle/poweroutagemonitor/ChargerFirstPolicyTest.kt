package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.*
import org.junit.Assert.*
import org.junit.Test

class ChargerFirstPolicyTest {
    private fun grid(a: GridAvailability, evidence: Long = 2000, pending: Boolean = false) =
        PowerSignal(a, 3000, "account", recoveryPending = pending, evidenceReceivedAtEpochMs = evidence)
    @Test fun cloudFailureNeverPreventsLocalChargerOutage() {
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, null, 1000, false, false, 3000).availability)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, grid(GridAvailability.UNKNOWN), 1000, true, false, 3000).availability)
    }
    @Test fun chargerConnectionRearmsAndResolvesEvenWithoutCloud() {
        val r = ChargerFirstPolicy.evaluate(true, null, 1000, true, true, 3000)
        assertEquals(GridAvailability.AVAILABLE, r.availability); assertFalse(r.recovered)
    }
    @Test fun oldConnectedReportCannotVetoChargerLossOrRestoreOutage() {
        for (confirmed in listOf(false, true)) assertEquals(GridAvailability.UNAVAILABLE,
            ChargerFirstPolicy.evaluate(false, grid(GridAvailability.AVAILABLE, 500), 1000, confirmed, false, 3000).availability)
    }
    @Test fun newRecoveryDoesNotPreventThePrimaryChargerAlert() {
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, grid(GridAvailability.AVAILABLE), 1000, false, false, 3000).availability)
    }
    @Test fun newGridRecoveryCompletesOutageWithoutChargerReturn() {
        val r = ChargerFirstPolicy.evaluate(false, grid(GridAvailability.AVAILABLE, pending = true), 1000, true, false, 3000)
        assertEquals(GridAvailability.AVAILABLE, r.availability); assertTrue(r.recovered); assertTrue(r.recoveryPending)
    }
    @Test fun persistedRecoveryDoesNotInventAnotherOutageWhenCloudGoesQuiet() {
        assertEquals(GridAvailability.UNKNOWN, ChargerFirstPolicy.evaluate(false, null, 1000, false, true, 3000).availability)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, grid(GridAvailability.UNAVAILABLE), 1000, false, true, 3000).availability)
    }
    @Test fun verifiedEcoFlowOutageOverridesAPoweredBackupCharger() {
        val r = ChargerFirstPolicy.evaluate(true, grid(GridAvailability.UNAVAILABLE), 0, false, false, 3000)
        assertEquals(GridAvailability.UNAVAILABLE, r.availability); assertEquals(2000L, r.ecoFlowOutageStartedAt)
    }
    @Test fun lostCloudContactCannotInventRecoveryFromStillPoweredUps() {
        val r = ChargerFirstPolicy.evaluate(true, null, 0, true, false, 3000, 2000)
        assertEquals(GridAvailability.UNKNOWN, r.availability); assertEquals(2000L, r.ecoFlowOutageStartedAt)
    }
    @Test fun onlyNewEcoFlowEvidenceCanClearItsOutageWithPoweredCharger() {
        assertEquals(GridAvailability.UNKNOWN, ChargerFirstPolicy.evaluate(true, grid(GridAvailability.AVAILABLE, 1000), 0, true, false, 3000, 2000).availability)
        val r = ChargerFirstPolicy.evaluate(true, grid(GridAvailability.AVAILABLE, 2500, true), 0, true, false, 3000, 2000)
        assertEquals(GridAvailability.AVAILABLE, r.availability); assertTrue(r.recoveryPending); assertEquals(2000L, r.ecoFlowOutageStartedAt)
        val stable = ChargerFirstPolicy.evaluate(true, grid(GridAvailability.AVAILABLE, 2600), 0, true, false, 3000, r.ecoFlowOutageStartedAt)
        assertEquals(GridAvailability.AVAILABLE, stable.availability); assertEquals(0L, stable.ecoFlowOutageStartedAt)
    }
    @Test fun persistedEcoFlowLossDoesNotHideIndependentLocalChargerLoss() {
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, null, 1000, true, false, 3000, 2000).availability)
    }
    @Test fun staleUnknownOrFutureEcoFlowLossCannotLatchAnOutage() {
        assertEquals(GridAvailability.AVAILABLE, ChargerFirstPolicy.evaluate(true, grid(GridAvailability.UNAVAILABLE), 0, false, false, 30000).availability)
        assertEquals(GridAvailability.AVAILABLE, ChargerFirstPolicy.evaluate(true, grid(GridAvailability.UNKNOWN), 0, false, false, 3000).availability)
        assertEquals(GridAvailability.AVAILABLE, ChargerFirstPolicy.evaluate(true, grid(GridAvailability.UNAVAILABLE, 4000), 0, false, false, 3000).availability)
    }
    @Test fun staleOrFutureEvidenceCannotRecover() {
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, grid(GridAvailability.AVAILABLE), 1000, true, false, 30_000).availability)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, grid(GridAvailability.AVAILABLE, 4000), 1000, true, false, 3000).availability)
    }
}
