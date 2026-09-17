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
    @Test fun supportedRangesIncludeManualAndHours() {
        assertTrue(PowerOceanAssistedSettings.valid(0)); assertTrue(PowerOceanAssistedSettings.valid(5)); assertTrue(PowerOceanAssistedSettings.valid(86_400))
        assertFalse(PowerOceanAssistedSettings.valid(1)); assertFalse(PowerOceanAssistedSettings.valid(-1)); assertFalse(PowerOceanAssistedSettings.valid(86_401))
    }
}
