package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.*
import com.flossypickle.poweroutagemonitor.integrations.power.*
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanCheckContinuity
import org.junit.Assert.*
import org.junit.Test

class PowerOceanCheckContinuityTest {
    private val check = PowerSourceCheck(100_000, 105_000, true, cycleState = PowerSourceCheck.CycleState.WAITING,
        finishedAtEpochMs = 120_000, nextCheckAtEpochMs = 400_000)
    private val verified = PowerSignal(GridAvailability.AVAILABLE, 120_000, "account",
        evidenceReceivedAtEpochMs = 105_000, check = check)
    @Test fun expirationUsesCurrentIntervalPlusOneMinuteFromDeviceReceipt() {
        for (seconds in listOf(5, 60, 300, 3600, 86400)) {
            val expiry = 105_000L + (seconds + 60) * 1000L
            assertEquals(GridAvailability.AVAILABLE, PowerOceanCheckContinuity.availability(verified, check, expiry, 120_000, seconds))
            assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(verified, check, expiry + 1, 120_000, seconds))
        }
        val active = PowerSourceCheck(400_000, null, false, cycleState = PowerSourceCheck.CycleState.COLLECTING)
        assertEquals(GridAvailability.AVAILABLE, PowerOceanCheckContinuity.availability(verified, active, 465_000, 120_000, 300))
        assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(verified, active, 465_001, 120_000, 300))
        assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(verified, active, 580_001, 120_000, 3600))
    }
    @Test fun fiveMinuteWaitAndNextCollectionDoNotCreateSourceFailureOrGridRecovery() {
        var scheduled = ScheduledAlertStore.State()
        var state = OutageEngine.State(OutageEngine.Phase.POWERED)
        for (now in listOf(120_000L, 215_000L, 399_000L, 450_000L)) {
            val current = if (now < 400_000) check else PowerSourceCheck(400_000, null, false,
                cycleState = PowerSourceCheck.CycleState.COLLECTING)
            val availability = PowerOceanCheckContinuity.availability(verified, current, now, 120_000, 300)
            assertEquals(GridAvailability.AVAILABLE, availability)
            val primary = verified.copy(availability = availability, observedAtEpochMs = now, check = current)
            val result = ChargerFirstPolicy.evaluate(false, primary, 90_000, false, true, now)
            assertEquals(GridAvailability.AVAILABLE, result.availability)
            state = OutageEngine.update(state, true, now, 90, 30_000, 30_000)
            val update = ScheduledAlertPolicy.update(scheduled, ScheduledAlertStore.Settings(), true, state, now)
            scheduled = update.state
            assertTrue(update.notices.isEmpty())
        }
        assertEquals(OutageEngine.Phase.POWERED, state.phase)
        assertEquals(105_000L, verified.evidenceReceivedAtEpochMs)
        assertEquals(GridAvailability.AVAILABLE, ChargerFirstPolicy.evaluate(true,
            verified.copy(observedAtEpochMs = 450_000), 90_000, false, true, 450_000).availability)
    }
    @Test fun aNewUnplugMustBeVerifiedDespitePriorOnlineResult() {
        val now = 450_000L
        val current = PowerSourceCheck(now, null, false, cycleState = PowerSourceCheck.CycleState.CONNECTING)
        val carried = verified.copy(observedAtEpochMs = now, check = current)
        assertEquals(GridAvailability.UNKNOWN, ChargerFirstPolicy.evaluate(false, carried, now,
            false, false, now, verificationWindowMs = 180_000).availability)
    }
    @Test fun failedInconclusiveAndPausedChecksDoNotRetainOldState() {
        for (state in listOf(PowerSourceCheck.CycleState.FAILED, PowerSourceCheck.CycleState.WAITING, PowerSourceCheck.CycleState.PAUSED)) {
            val failed = PowerSourceCheck(400_000, null, false, cycleState = state, finishedAtEpochMs = 420_000)
            assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(verified, failed, 450_000, 120_000, 300))
        }
    }
    @Test fun manualOnlyOldMissingFutureAndOverdueEvidenceBecomesUnknown() {
        assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(null, check, 450_000, 120_000, 300))
        assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(verified.copy(evidenceReceivedAtEpochMs = 500_000), check, 450_000, 120_000, 300))
        assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(verified, check.copy(nextCheckAtEpochMs = null), 450_000, 120_000, null))
        assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(verified, check, 580_001, 120_000, 300))
        val late = PowerSourceCheck(700_000, null, false, cycleState = PowerSourceCheck.CycleState.CONNECTING)
        assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(verified, late, 700_001, 120_000, 300))
    }
    @Test fun knownOutageRemainsOpenDuringScheduledWait() {
        val lost = verified.copy(availability = GridAvailability.UNAVAILABLE)
        assertEquals(GridAvailability.UNAVAILABLE, PowerOceanCheckContinuity.availability(lost, check, 450_000, 120_000, 300))
    }
    @Test fun chargerReturnUpdateRequiresConfirmedOnlineStateAndActualTransition() {
        val now = 450_000L
        val current = verified.copy(observedAtEpochMs = now)
        assertTrue(ChargerReconnectPolicy.shouldNotify(false, true, current, 90_000, OutageEngine.Phase.POWERED, now, true))
        assertFalse(ChargerReconnectPolicy.shouldNotify(false, true, current, 90_000, OutageEngine.Phase.POWERED, now, false))
        assertFalse(ChargerReconnectPolicy.shouldNotify(true, true, current, 90_000, OutageEngine.Phase.POWERED, now, true))
        assertFalse(ChargerReconnectPolicy.shouldNotify(null, true, current, 90_000, OutageEngine.Phase.POWERED, now, true))
        assertFalse(ChargerReconnectPolicy.shouldNotify(false, true, current, 110_000, OutageEngine.Phase.POWERED, now, true))
        assertFalse(ChargerReconnectPolicy.shouldNotify(false, true, current.copy(availability = GridAvailability.UNAVAILABLE), 90_000, OutageEngine.Phase.OUTAGE, now, true))
        assertFalse(ChargerReconnectPolicy.shouldNotify(false, true, current.copy(recoveryPending = true), 90_000, OutageEngine.Phase.POWERED, now, true))
    }
    @Test fun finalPartialPacketKeepsOnlyQualifiedEvidenceFromThisCheck() {
        val collecting = check.copy(cycleState = PowerSourceCheck.CycleState.COLLECTING)
        val good = verified.copy(check = collecting)
        val partial = good.copy(check = collecting.copy(gridEvidenceAvailable = false, deviceUpdates = 8))
        val completed = PowerOceanCheckContinuity.completedEvidence(partial, good, 100_000, false)!!
        assertEquals(GridAvailability.AVAILABLE, completed.availability)
        assertEquals(105_000L, completed.evidenceReceivedAtEpochMs)
        assertTrue(completed.check!!.gridEvidenceAvailable)
        assertEquals(8, completed.check!!.deviceUpdates)
        assertNull(PowerOceanCheckContinuity.completedEvidence(partial, good, 100_000, true))
        assertNull(PowerOceanCheckContinuity.completedEvidence(partial, good.copy(check = collecting.copy(requestedAtEpochMs = 90_000)), 100_000, false))
        assertNull(PowerOceanCheckContinuity.completedEvidence(partial, good.copy(evidenceReceivedAtEpochMs = 99_000), 100_000, false))
    }
    @Test fun newQualifiedOutageWinsOverEarlierOnlineEvidence() {
        val lost = verified.copy(availability = GridAvailability.UNAVAILABLE, evidenceReceivedAtEpochMs = 110_000)
        assertEquals(GridAvailability.UNAVAILABLE,
            PowerOceanCheckContinuity.completedEvidence(lost, verified, 100_000, false)!!.availability)
        val unsupported = lost.copy(check = check.copy(gridEvidenceAvailable = false,
            observations = listOf(SourceReportedValue("Reported grid code", "2", "Unsupported", 112_000, true))))
        assertNull(PowerOceanCheckContinuity.completedEvidence(unsupported, verified, 100_000, false))
    }

    @Test fun requestBeginningAfterConnectionStillCompletesTheSameBoundedCycle() {
        val readingCheck = check.copy(requestedAtEpochMs = 110_000, liveReportAtEpochMs = 115_000,
            cycleState = PowerSourceCheck.CycleState.COLLECTING)
        val report = verified.copy(observedAtEpochMs = 120_000, evidenceReceivedAtEpochMs = 115_000, check = readingCheck)
        val completed = PowerOceanCheckContinuity.completedEvidence(report, report, 100_000, false)!!
        assertEquals(GridAvailability.AVAILABLE, completed.availability)
        assertEquals(115_000L, completed.evidenceReceivedAtEpochMs)
        val partial = report.copy(check = readingCheck.copy(gridEvidenceAvailable = false))
        assertNotNull(PowerOceanCheckContinuity.completedEvidence(partial, report, 100_000, false))
        assertNull(PowerOceanCheckContinuity.completedEvidence(partial, verified, 100_000, false))
        assertNull(PowerOceanCheckContinuity.completedEvidence(report, report, 125_000, false))
    }

}
