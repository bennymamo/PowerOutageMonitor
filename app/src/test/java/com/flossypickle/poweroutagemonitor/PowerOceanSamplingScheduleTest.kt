package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import org.junit.Assert.*
import org.junit.Test

class PowerOceanSamplingScheduleTest {
    private fun s(seconds: Int?, incident: Boolean = false, manual: Long = 0) = PowerOceanReadSchedule(seconds, incident, manual, true)
    @Test fun defaultNormalRequestsAreHourly() {
        val c = PowerOceanSamplingSchedule(); val normal = s(PowerOceanAssistedSettings().normalSeconds)
        assertTrue(c.due(normal, 0)); assertFalse(c.due(normal, 3_599_999)); assertTrue(c.due(normal, 3_600_000))
    }
    @Test fun chargerLossStartsMinuteRequestsImmediately() {
        val c = PowerOceanSamplingSchedule(); c.due(s(3600), 0)
        assertTrue(c.due(s(60, true), 1000)); assertFalse(c.due(s(60, true), 60_999)); assertTrue(c.due(s(60, true), 61_000))
    }
    @Test fun manualNormalDoesNotPreventAutomaticIncidentChecks() {
        val c = PowerOceanSamplingSchedule()
        assertFalse(c.due(s(null), 0)); assertFalse(c.due(s(null), 100_000)); assertTrue(c.due(s(60, true), 100_001))
    }
    @Test fun manualActionIsOneCheckRatherThanARepeatedTrigger() {
        val c = PowerOceanSamplingSchedule(); assertTrue(c.due(s(null, manual = 1), 0))
        assertFalse(c.due(s(null, manual = 1), 1000)); assertTrue(c.due(s(null, manual = 2), 2000))
    }
    @Test fun shorterIntervalAppliesWithoutReconnecting() {
        val c = PowerOceanSamplingSchedule(); c.due(s(3600), 0)
        assertTrue(c.due(s(5), 5000)); assertFalse(c.due(s(5), 5001)); assertTrue(c.due(s(5), 10_000))
    }
    @Test fun assistedChecksActivateLiveReportsEvenWithLegacyToggleOff() {
        assertTrue(s(3600).needsLiveActivation(false, readDue = true, periodicDue = false))
        assertTrue(s(null, manual = 1).needsLiveActivation(false, readDue = true, periodicDue = false))
    }
    @Test fun assistedIdleAndManualOnlyDoNotRunTheTwentySecondLoop() {
        assertFalse(s(3600).needsLiveActivation(true, readDue = false, periodicDue = true))
        assertFalse(s(null).needsLiveActivation(true, readDue = false, periodicDue = true))
    }
    @Test fun continuousModeRetainsItsExplicitLiveReportingChoice() {
        val continuous = s(60).copy(liveOnEachRead = false)
        assertFalse(continuous.needsLiveActivation(false, readDue = true, periodicDue = true))
        assertTrue(continuous.needsLiveActivation(true, readDue = false, periodicDue = true))
    }
    @Test fun pauseStopsScheduledManualAndLegacyLiveRequestsWithoutLosingResume() {
        val c = PowerOceanSamplingSchedule(); c.due(s(3600), 0)
        val paused = s(3600, manual = 1).copy(paused = true)
        assertFalse(c.due(paused, 100)); assertFalse(c.due(paused, 4_000_000))
        assertFalse(paused.needsLiveActivation(true, true, true))
        assertFalse(paused.copy(liveOnEachRead = false).needsLiveActivation(true, true, true))
        assertTrue(c.due(paused.copy(paused = false), 4_000_001))
        assertFalse(c.due(paused.copy(paused = false), 4_000_002))
    }
    @Test fun ecoFlowLossFoundDuringACheckDoesNotCauseAnImmediateDuplicateRequest() {
        val c = PowerOceanSamplingSchedule(); assertTrue(c.due(s(3600), 0))
        val incident = s(60, true).copy(incidentDetectedDuringCheck = true)
        assertFalse(c.due(incident, 1000)); assertFalse(c.due(incident, 59_999))
        assertTrue(c.due(incident, 60_000)); assertFalse(c.due(incident, 60_001))
    }
    @Test fun completedLongCycleLeavesShortClosedGapWithoutSkippingAnotherWholeInterval() {
        val c = PowerOceanSamplingSchedule(); assertTrue(c.due(s(60), 1000))
        c.finishCheck(121_000)
        assertEquals(126_000L, c.nextDueAt); assertFalse(c.due(s(60), 121_001))
        assertTrue(c.due(s(60), 126_000))
    }
    @Test fun incidentFoundDuringLongCollectionAdoptsMinuteScheduleWithoutImmediateReconnect() {
        val c = PowerOceanSamplingSchedule(); c.due(s(3600), 1000)
        val incident = s(60, true).copy(incidentDetectedDuringCheck = true)
        c.finishCheck(121_000, incident)
        assertEquals(126_000L, c.nextDueAt); assertFalse(c.due(incident, 121_001))
        assertTrue(c.due(incident, 126_000))
    }
    @Test fun twoMinuteCheckOverrunDoesNotBecomeFourMinuteGap() {
        val c = PowerOceanSamplingSchedule(); assertTrue(c.due(s(120), 0))
        c.finishCheck(121_000)
        assertEquals(126_000L, c.nextDueAt)
        assertFalse(c.due(s(120), 125_999)); assertTrue(c.due(s(120), 126_000))
        assertFalse(c.due(s(120), 126_001))
        assertTrue(126_000L < 3_000L + (120 + 60) * 1000)
    }
    @Test fun restartingWithDisconnectedChargerChecksImmediatelyEvenOnHourlyIncidentSchedule() {
        val c = PowerOceanSamplingSchedule()
        assertTrue(c.due(s(3600, true), 0))
        c.finishCheck(20_000)
        assertEquals(3_600_000L, c.nextDueAt)
        assertFalse(c.due(s(3600, true), 20_001))
    }
    @Test fun manualOnlyFinishAndPauseHaveNoScheduledNextTime() {
        val c = PowerOceanSamplingSchedule(); c.due(s(null, manual = 1), 1000); c.finishCheck(121_000)
        assertNull(c.nextDueAt)
        c.due(s(3600).copy(paused = true), 122_000); assertNull(c.nextDueAt)
    }
    @Test fun supportedRangesIncludeManualAndHours() {
        assertTrue(PowerOceanAssistedSettings.valid(0)); assertTrue(PowerOceanAssistedSettings.valid(5)); assertTrue(PowerOceanAssistedSettings.valid(86_400))
        assertFalse(PowerOceanAssistedSettings.valid(1)); assertFalse(PowerOceanAssistedSettings.valid(-1)); assertFalse(PowerOceanAssistedSettings.valid(86_401))
    }
    @Test fun temporaryReportingIsRenewedOnlyInsideTheBoundedCollectionWindow() {
        val schedule = s(3600)
        assertTrue(schedule.needsLiveActivation(false, false, true, boundedWindow = true))
        assertFalse(schedule.needsLiveActivation(false, false, false, boundedWindow = true))
        assertFalse(schedule.needsLiveActivation(false, false, true, boundedWindow = false))
        assertFalse(schedule.copy(paused = true).needsLiveActivation(true, true, true, boundedWindow = true))
    }

}
