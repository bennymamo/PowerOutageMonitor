package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanGridCorrelation
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanPushDecoder
import org.junit.Assert.*
import org.junit.Test

class PowerOceanGridCorrelationTest {
    private val profile = PowerOceanGridCorrelation.Profile()

    @Test fun oldNonZeroMeterReadingAfterOffGridTransitionCannotDeclareRestoration() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(0), 1_000, false, true)
        comparison.observe(meter(12f), 2_000, false, true)
        comparison.observe(grid(1), 3_000, false, true)
        comparison.observe(meter(12f), 4_000, false, true)
        comparison.observe(meter(13f), 7_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, comparison.snapshot(8_000).state)
        assertFalse(comparison.snapshot(8_000).zeroFlowSeen)
    }

    @Test fun returnNeedsZeroThenChangingMeterActivityAndFullRecoveryNeedsConnectedCode() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(1), 1_000, false, true)
        comparison.observe(meter(0f), 2_000, false, true)
        comparison.observe(meter(-800f), 3_000, false, true)
        comparison.observe(meter(-800f), 6_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, comparison.snapshot(6_000).state)
        comparison.observe(meter(-790f), 7_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY, comparison.snapshot(7_000).state)
        assertEquals(7_000L, comparison.snapshot(7_000).returnEvidenceAtUtcMillis)
        comparison.observe(grid(0), 8_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, comparison.snapshot(8_000).state)
        assertNull(comparison.snapshot(8_000).returnEvidenceAtUtcMillis)
    }

    @Test fun zeroFlowDuringNormalGridConnectionDoesNotBecomeAnOutage() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(0), 1_000, false, true)
        comparison.observe(meter(0f), 2_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, comparison.snapshot(2_000).state)
        assertFalse(comparison.snapshot(2_000).zeroFlowSeen)
    }

    @Test fun retainedCachedMissingAndOutOfOrderMeterValuesCannotSupplyReturnEvidence() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(1), 1_000, false, true)
        comparison.observe(meter(0f), 2_000, false, true)
        comparison.observe(meter(-800f), 3_000, true, true)
        comparison.observe(meter(-790f), 6_000, false, false)
        comparison.observe(PowerOceanPushDecoder.Report(1, mapOf("pcsAPhase.vol" to 230f)), 7_000, false, true)
        comparison.observe(meter(-780f), 1_500, false, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, comparison.snapshot(7_000).state)
        assertNull(comparison.snapshot(7_000).returnEvidenceAtUtcMillis)
    }

    @Test fun exportAlsoSupportsReturnButStaleEvidenceAndUnknownCodesDoNot() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(1), 1_000, false, true)
        comparison.observe(meter(0f), 2_000, false, true)
        comparison.observe(meter(20f), 3_000, false, true)
        comparison.observe(meter(25f), 6_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY, comparison.snapshot(6_000).state)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, comparison.snapshot(100_000).state)
        comparison.observe(grid(99), 101_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, comparison.snapshot(101_000).state)
    }

    @Test fun newOutageOrExpiredCodeStartsANewMeterSequence() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(1), 1_000, false, true)
        comparison.observe(meter(0f), 2_000, false, true)
        comparison.observe(meter(-800f), 3_000, false, true)
        comparison.observe(grid(1), 100_000, false, true)
        comparison.observe(meter(-790f), 101_000, false, true)
        assertFalse(comparison.snapshot(101_000).zeroFlowSeen)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, comparison.snapshot(101_000).state)
    }

    @Test fun replyMeterSnapshotsNeedLiveActivityAndCannotReplayPreZeroValuesAsReturn() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(0), 1_000, false, true)
        comparison.observe(meter(10f), 2_000, false, false)
        comparison.observe(meter(12f), 3_000, false, false)
        comparison.observe(grid(1), 4_000, false, true)
        comparison.observe(meter(0f), 5_000, false, false)
        comparison.observe(meter(10f), 6_000, false, false)
        comparison.observe(meter(12f), 9_000, false, false)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, comparison.snapshot(9_000).state)
        comparison.observe(meter(-800f), 10_000, false, false)
        comparison.observe(meter(-790f), 13_000, false, false)
        assertEquals(PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY, comparison.snapshot(13_000).state)
        comparison.observe(meter(-780f), 100_000, false, false)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, comparison.snapshot(100_000).state)
    }

    @Test fun changingLiveFlowCanMaintainHealthButSnapshotRepliesCannotRefreshGridCode() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(1), 1_000, false, true)
        comparison.observe(meter(0f), 2_000, false, false)
        comparison.observe(PowerOceanPushDecoder.Report(33, mapOf("sysLoadPwr" to 700f)), 80_000, false, true)
        comparison.observe(grid(0), 81_000, false, false)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, comparison.snapshot(100_000).state)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, comparison.snapshot(180_000).state)
    }

    private fun grid(code: Long) = PowerOceanPushDecoder.Report(8, mapOf("sysGridSta" to code))
    private fun meter(value: Float) = PowerOceanPushDecoder.Report(1, mapOf(profile.meterKey to value))
}
