package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.*
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanCheckContinuity
import org.junit.Assert.*
import org.junit.Test

class DashboardSourceReadingPolicyTest {
    private val source = PowerSourceStore.Source.ECOFLOW_ACCOUNT
    private val check = PowerSourceCheck(400_000, null, false,
        cycleState = PowerSourceCheck.CycleState.CONNECTING, lastConfirmedOnlineAtEpochMs = 105_000)
    private val status = PowerSourceStore.Status(source, GridAvailability.AVAILABLE, 400_000,
        null, check = check, evidenceReceivedAtEpochMs = 105_000)
    @Test fun slowConnectionDoesNotDiscardValidEvidenceAtScreenRefreshBoundary() {
        assertTrue(DashboardSourceReadingPolicy.isCurrent(status, source, 420_000, 300, 120_000))
        assertFalse(DashboardSourceReadingPolicy.isCurrent(status, source, 465_001, 300, 120_000))
        assertFalse(DashboardSourceReadingPolicy.isCurrent(status, source, 580_001, 3600, 120_000))
    }
    @Test fun failedPausedMissingOrOtherSourceCannotExtendScreenReading() {
        for (cycle in listOf(PowerSourceCheck.CycleState.FAILED, PowerSourceCheck.CycleState.PAUSED, PowerSourceCheck.CycleState.WAITING)) {
            assertFalse(DashboardSourceReadingPolicy.isCurrent(status.copy(check = check.copy(cycleState = cycle)), source, 420_000, 300, 120_000))
        }
        assertFalse(DashboardSourceReadingPolicy.isCurrent(status.copy(evidenceReceivedAtEpochMs = null), source, 420_000, 300, 120_000))
        assertFalse(DashboardSourceReadingPolicy.isCurrent(status, PowerSourceStore.Source.ECOFLOW_MODBUS, 420_000, 300, 120_000))
        assertFalse(DashboardSourceReadingPolicy.isCurrent(status.copy(availability = GridAvailability.UNKNOWN), source, 420_000, 300, 120_000))
    }
    @Test fun chargerLossCanDisplayLastOnlineWhileStillRequiringNewConfirmation() {
        val awaiting = status.copy(availability = GridAvailability.UNKNOWN)
        assertTrue(DashboardSourceReadingPolicy.previousOnlineDuringCheck(awaiting, 420_000, 300, 120_000))
        val primary = PowerSignal(GridAvailability.AVAILABLE, 420_000, "account", evidenceReceivedAtEpochMs = 105_000, check = check)
        assertEquals(GridAvailability.UNKNOWN, ChargerFirstPolicy.evaluate(false, primary, 400_000,
            false, false, 420_000, verificationWindowMs = 180_000).availability)
    }
    @Test fun previousOnlineDisplayEndsOnFailureExpiryOrContradictingEvidence() {
        assertFalse(DashboardSourceReadingPolicy.previousOnlineDuringCheck(status, 465_001, 300, 120_000))
        assertFalse(DashboardSourceReadingPolicy.previousOnlineDuringCheck(status.copy(check = check.copy(cycleState = PowerSourceCheck.CycleState.FAILED)), 420_000, 300, 120_000))
        assertFalse(DashboardSourceReadingPolicy.previousOnlineDuringCheck(status.copy(availability = GridAvailability.UNAVAILABLE), 420_000, 300, 120_000))
        assertFalse(DashboardSourceReadingPolicy.previousOnlineDuringCheck(status.copy(evidenceReceivedAtEpochMs = null), 420_000, 300, 120_000))
        assertFalse(DashboardSourceReadingPolicy.previousOnlineDuringCheck(status.copy(check = check.copy(gridEvidenceAvailable = true)), 420_000, 300, 120_000))
    }
    @Test fun switchingToOutageScheduleDoesNotRetroactivelyExpireGoodNormalReading() {
        val old = PowerSignal(GridAvailability.AVAILABLE, 120_000, "account", evidenceReceivedAtEpochMs = 105_000,
            check = check.copy(requestedAtEpochMs = 100_000))
        val started = check.copy(requestedAtEpochMs = 1_000_000, evidenceValidUntilEpochMs = 3_765_000)
        val interval = PowerOceanCheckContinuity.intervalDuringCheck(old, started, 3600, 300)
        assertEquals(3600, interval)
        assertEquals(GridAvailability.AVAILABLE, PowerOceanCheckContinuity.availability(old, started, 1_010_000, 120_000, interval))
        val awaiting = status.copy(availability = GridAvailability.UNKNOWN, observedAtEpochMs = 1_000_000, check = started)
        assertTrue(DashboardSourceReadingPolicy.previousOnlineDuringCheck(awaiting, 1_010_000, 300, 120_000))
        assertEquals(300, PowerOceanCheckContinuity.intervalDuringCheck(old, old.check, 3600, 300))
    }
    @Test fun incompleteReportWithinSameCheckDoesNotDiscardEarlierQualifiedReport() {
        val collecting = check.copy(requestedAtEpochMs = 100_000, cycleState = PowerSourceCheck.CycleState.COLLECTING)
        val signal = PowerSignal(GridAvailability.AVAILABLE, 105_000, "account", evidenceReceivedAtEpochMs = 105_000, check = collecting)
        assertEquals(GridAvailability.AVAILABLE, PowerOceanCheckContinuity.availability(signal, collecting, 110_000, 120_000, 300))
        assertEquals(GridAvailability.UNKNOWN, PowerOceanCheckContinuity.availability(signal, collecting.copy(requestedAtEpochMs = 90_000), 110_000, 120_000, 300))
    }
    @Test fun standaloneKnownOutageKeepsItsEvidenceDeadlineDuringSlowCheck() {
        val outage = status.copy(availability = GridAvailability.UNAVAILABLE,
            check = check.copy(lastConfirmedOnlineAtEpochMs = null, evidenceValidUntilEpochMs = 3_765_000))
        assertTrue(DashboardSourceReadingPolicy.isCurrent(outage, source, 420_000, null, 120_000))
        assertFalse(DashboardSourceReadingPolicy.previousOnlineDuringCheck(outage, 420_000, null, 120_000))
        assertFalse(DashboardSourceReadingPolicy.isCurrent(outage, source, 580_001, null, 120_000))
    }
    @Test fun firstCheckWarningGraceIsBoundedAndNeverAppliesToCompletedChecks() {
        assertEquals(580_000L, check.verificationDeadline(420_000, 120_000))
        assertNull(check.verificationDeadline(580_000, 120_000))
        assertNull(check.verificationDeadline(399_999, 120_000))
        for (cycle in listOf(PowerSourceCheck.CycleState.WAITING, PowerSourceCheck.CycleState.FAILED, PowerSourceCheck.CycleState.PAUSED)) {
            assertNull(check.copy(cycleState = cycle).verificationDeadline(420_000, 120_000))
        }
    }

}
