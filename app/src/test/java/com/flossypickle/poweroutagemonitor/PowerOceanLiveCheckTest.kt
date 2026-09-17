package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import org.junit.Assert.*
import org.junit.Test

class PowerOceanLiveCheckTest {
    private fun power(watts: Double, counter: Int = 1) = PowerOceanPushDecoder.Report(33, mapOf("sysLoadPwr" to watts, "sysGridPwr" to 0.0, "requestId" to counter))
    @Test fun cachedRetainedAndPreRequestReportsCannotVerifyTheCheck() {
        val check = PowerOceanLiveCheck(); check.begin(1000)
        check.observe(power(100.0), 1100, false, false)
        check.observe(power(100.0), 1200, true, true)
        check.observe(power(100.0), 999, false, true)
        assertFalse(check.status().hasCurrentReport(1300))
        check.observe(power(100.0), 1400, false, true)
        assertTrue(check.status().hasCurrentReport(1400))
        check.begin(2000); assertFalse(check.status().hasCurrentReport(2000))
    }
    @Test fun repeatedPacketsWithinOneCheckAreNotRepeatedScheduledChecks() {
        val check = PowerOceanLiveCheck(); check.begin(1000)
        (1..10).forEach { check.observe(power(100.0), 1000L + it, false, true) }
        assertFalse(check.status().possiblyStalled); assertEquals(0, check.status().unchangedChecks)
    }
    @Test fun identicalPowerAcrossThreeChecksWarnsDespiteChangingRequestMetadata() {
        val check = PowerOceanLiveCheck()
        (1..3).forEach { check.begin(it * 60_000L); check.observe(power(100.0, it), it * 60_000L + 1000, false, true) }
        assertTrue(check.status().possiblyStalled); assertEquals(2, check.status().unchangedChecks)
    }
    @Test fun changedLiveWattsClearTheWarningWithoutNeedingGridStatusToChange() {
        val check = PowerOceanLiveCheck()
        (1..3).forEach { check.begin(it * 60_000L); check.observe(power(100.0), it * 60_000L + 1000, false, true) }
        check.observe(power(101.0), 182000, false, true)
        assertFalse(check.status().possiblyStalled); assertEquals(0, check.status().unchangedChecks)
    }
    @Test fun staleFutureAndUnsupportedReportsDoNotProveCurrentData() {
        val check = PowerOceanLiveCheck(); check.begin(1000)
        check.observe(PowerOceanPushDecoder.Report(50, mapOf("bpPwr" to 100)), 2000, false, true)
        assertFalse(check.status().hasCurrentReport(2000))
        check.observe(power(100.0), 3000, false, true)
        assertFalse(check.status().hasCurrentReport(2999)); assertFalse(check.status().hasCurrentReport(93001))
    }
    @Test fun knownGridStateWithoutNewLiveReportsIsUnknownForThatCheck() {
        val grid = PowerOceanGridCorrelation.Snapshot(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, false, null)
        val result = PowerOceanLossConfirmation.evaluate(grid, true, false, liveDataVerified = false)
        assertEquals(GridAvailability.UNKNOWN, result.availability)
        assertEquals(PowerOceanLossConfirmation.Reason.WAITING_FOR_LIVE_DATA, result.reason)
    }
}
