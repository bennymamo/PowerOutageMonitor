package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceCheck
import org.junit.Assert.*
import org.junit.Test

class PowerOceanCheckCyclePolicyTest {
    private fun power(watts: Double) = PowerOceanPushDecoder.Report(33, mapOf("sysLoadPwr" to watts))
    @Test fun firstPlusTwoChangingPowerReportsCanFinishWithUsableEvidence() {
        val check = PowerOceanLiveCheck(); val policy = PowerOceanCheckCyclePolicy(); check.begin(1000)
        check.observe(power(100.0), 2000, false, true)
        check.observe(power(101.0), 3000, false, true)
        assertFalse(policy.enough(check.status(), true))
        check.observe(power(100.0), 4000, false, true)
        assertTrue(policy.enough(check.status(), true))
        assertFalse(policy.enough(check.status(), false))
    }
    @Test fun steadyLoadWaitsForTheWindowRatherThanPretendingFreshnessWasVerified() {
        val check = PowerOceanLiveCheck(); check.begin(1000)
        (2..6).forEach { check.observe(power(100.0), it * 1000L, false, true) }
        assertFalse(PowerOceanCheckCyclePolicy().enough(check.status(), true))
        assertEquals(5, check.status().powerUpdates)
    }
    @Test fun onePacketWithMultipleCommandsCountsAsOneUpdateAndStillCountsPower() {
        val check = PowerOceanLiveCheck(); check.begin(1000)
        check.observe(PowerOceanPushDecoder.Report(8, mapOf("sysGridSta" to 0L)), 2000, false, true)
        check.observe(power(100.0), 2000, false, true)
        check.observe(power(100.0), 2000, false, true)
        assertEquals(1, check.status().deviceUpdates); assertEquals(1, check.status().powerUpdates)
        check.observe(power(101.0), 3000, false, false)
        check.observe(power(102.0), 4000, true, true)
        assertEquals(1, check.status().deviceUpdates); assertFalse(check.status().valuesChanged)
    }
    @Test fun newCycleClearsCountersAndDisplayValuesButKeepsCrossCheckComparison() {
        val check = PowerOceanLiveCheck(); check.begin(1000); check.observe(power(100.0), 2000, false, true)
        check.begin(3000); assertEquals(0, check.status().deviceUpdates); assertTrue(check.status().powerValues.isEmpty())
        check.observe(power(100.0), 4000, false, true)
        assertEquals(1, check.status().unchangedChecks); assertFalse(check.status().valuesChanged)
    }
    @Test fun configurableCountAndWindowAreBounded() {
        assertEquals(300, PowerOceanCheckCyclePolicy(300, 10).maximumSeconds)
        assertThrows(IllegalArgumentException::class.java) { PowerOceanCheckCyclePolicy(29) }
        assertThrows(IllegalArgumentException::class.java) { PowerOceanCheckCyclePolicy(301) }
        assertThrows(IllegalArgumentException::class.java) { PowerOceanCheckCyclePolicy(extraUpdates = 0) }
    }
    @Test fun activeCheckStaysActiveBeyondOldFortyFiveSecondTimeoutAndFinishReflectsDataHealth() {
        val collecting = PowerSourceCheck(1000, 2000, true, cycleState = PowerSourceCheck.CycleState.COLLECTING, deviceUpdates = 1)
        assertEquals(PowerSourceCheck.Phase.CHECKING, collecting.phase(60_000))
        assertEquals(PowerSourceCheck.DataHealth.UNCHANGED, collecting.dataHealth)
        val done = collecting.copy(cycleState = PowerSourceCheck.CycleState.WAITING, finishedAtEpochMs = 120_000, valuesChanged = true)
        assertEquals(PowerSourceCheck.Phase.GRID_VERIFIED, done.phase(120_000))
        assertEquals(PowerSourceCheck.DataHealth.CHANGING, done.dataHealth)
        assertEquals(PowerSourceCheck.Phase.TIMED_OUT, done.copy(liveReportAtEpochMs = null).phase(120_000))
    }
}
