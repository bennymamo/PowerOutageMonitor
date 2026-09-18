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
    @Test fun currentConnectedEvidenceCancelsTheSuspectedChargerOutage() {
        assertEquals(GridAvailability.AVAILABLE, ChargerFirstPolicy.evaluate(false, grid(GridAvailability.AVAILABLE), 1000, false, false, 3000).availability)
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
    @Test fun chargerLossWaitsForAnImmediateCheckEvenWithZeroOutageDelay() {
        val r = ChargerFirstPolicy.evaluate(false, null, 1000, false, false, 1000, verificationWindowMs = 180_000)
        assertEquals(GridAvailability.UNKNOWN, r.availability)
        val engine = OutageEngine.update(OutageEngine.State(OutageEngine.Phase.POWERED),
            null, 1000, 80, 0, 0)
        assertEquals(OutageEngine.Phase.POWERED, engine.phase)
    }
    @Test fun collectingCheckKeepsTheChargerAlertUnconfirmed() {
        val check = PowerSourceCheck(1100, null, false, cycleState = PowerSourceCheck.CycleState.COLLECTING)
        val unknown = grid(GridAvailability.UNKNOWN).copy(check = check)
        assertEquals(GridAvailability.UNKNOWN, ChargerFirstPolicy.evaluate(false, unknown, 1000, false, false,
            30_000, verificationWindowMs = 180_000).availability)
    }
    @Test fun failedOrCompletedInconclusiveCheckReleasesTheOfflineFallback() {
        for (state in listOf(PowerSourceCheck.CycleState.FAILED, PowerSourceCheck.CycleState.WAITING)) {
            val check = PowerSourceCheck(1100, null, false, cycleState = state, finishedAtEpochMs = 2500)
            val r = ChargerFirstPolicy.evaluate(false, grid(GridAvailability.UNKNOWN).copy(check = check),
                1000, false, false, 3000, verificationWindowMs = 180_000)
            assertEquals(GridAvailability.UNAVAILABLE, r.availability)
        }
    }
    @Test fun oldCompletedCheckCannotSkipTheNewUnplugVerification() {
        val old = PowerSourceCheck(100, null, false, finishedAtEpochMs = 500)
        assertEquals(GridAvailability.UNKNOWN, ChargerFirstPolicy.evaluate(false,
            grid(GridAvailability.UNKNOWN).copy(check = old), 1000, false, false, 3000,
            verificationWindowMs = 180_000).availability)
    }
    @Test fun failedWorkerCannotDelayLocalDetectionForever() {
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, null,
            1000, false, false, 181_000, verificationWindowMs = 180_000).availability)
    }
    @Test fun verifiedEcoFlowLossDoesNotWaitForCollectionToFinish() {
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false,
            grid(GridAvailability.UNAVAILABLE), 1000, false, false, 3000,
            verificationWindowMs = 180_000).availability)
    }
    @Test fun unplugConnectedThenClosedConnectionDoesNotInventAnOutage() {
        var state = OutageEngine.State(OutageEngine.Phase.POWERED)
        val waiting = ChargerFirstPolicy.evaluate(false, null, 1000, false, false, 1000,
            verificationWindowMs = 180_000)
        state = OutageEngine.update(state, if (waiting.availability == GridAvailability.UNKNOWN) null else false,
            1000, 80, 0, 0)
        val connected = ChargerFirstPolicy.evaluate(false, grid(GridAvailability.AVAILABLE), 1000,
            false, false, 3000, verificationWindowMs = 180_000)
        assertTrue(connected.recovered)
        state = OutageEngine.update(state, connected.availability == GridAvailability.AVAILABLE, 3000, 80, 0, 0)
        assertEquals(OutageEngine.Phase.POWERED, state.phase)
        assertEquals(GridAvailability.UNKNOWN, ChargerFirstPolicy.evaluate(false, null, 1000,
            false, connected.recovered, 100_000, verificationWindowMs = 180_000).availability)
    }

    @Test fun restartWithOldChargerLossStillWaitsForItsFirstBoundedCheck() {
        val check = PowerSourceCheck(400_000, null, false, cycleState = PowerSourceCheck.CycleState.CONNECTING)
        val unknown = grid(GridAvailability.UNKNOWN).copy(observedAtEpochMs = 420_000, check = check)
        assertEquals(GridAvailability.UNKNOWN, ChargerFirstPolicy.evaluate(false, unknown, 1000,
            false, false, 420_000, verificationWindowMs = 180_000).availability)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false,
            unknown.copy(observedAtEpochMs = 580_000), 1000, false, false, 580_000,
            verificationWindowMs = 180_000).availability)
        val failed = unknown.copy(check = check.copy(cycleState = PowerSourceCheck.CycleState.FAILED,
            finishedAtEpochMs = 425_000), observedAtEpochMs = 425_000)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, failed, 1000,
            false, false, 425_000, verificationWindowMs = 180_000).availability)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, unknown, 1000,
            true, false, 420_000, verificationWindowMs = 180_000).availability)
    }

}
