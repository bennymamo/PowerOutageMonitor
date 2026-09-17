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

    @Test fun liveActivityAfterAnExpiredGapCannotReviveAnOldGridCode() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(0), 1_000, false, true)
        comparison.observe(PowerOceanPushDecoder.Report(33, mapOf("sysLoadPwr" to 700f)), 100_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, comparison.snapshot(100_000).state)
        comparison.observe(grid(0), 101_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, comparison.snapshot(101_000).state)
    }

    @Test fun zeroNetFlowAfterReturnDoesNotInventASecondOutage() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(1), 1_000, false, true)
        comparison.observe(meter(0f), 2_000, false, true)
        comparison.observe(meter(-800f), 3_000, false, true)
        comparison.observe(meter(-790f), 6_000, false, true)
        comparison.observe(meter(0f), 7_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY, comparison.snapshot(7_000).state)
    }

    @Test fun healthTicksAndSnapshotRepliesDoNotMakeOldConnectedEvidenceNew() {
        val comparison = PowerOceanGridCorrelation(profile)
        comparison.observe(grid(0), 1_000, false, true)
        comparison.observe(PowerOceanPushDecoder.Report(33, mapOf("sysLoadPwr" to 700f)), 80_000, false, true)
        comparison.observe(grid(0), 81_000, false, false)
        assertEquals(1_000L, comparison.snapshot(82_000).evidenceReceivedAtUtcMillis)
        comparison.observe(grid(0), 83_000, true, true)
        assertEquals(1_000L, comparison.snapshot(84_000).evidenceReceivedAtUtcMillis)
        comparison.observe(grid(0), 85_000, false, true)
        assertEquals(85_000L, comparison.snapshot(86_000).evidenceReceivedAtUtcMillis)
        assertNull(comparison.snapshot(180_000).evidenceReceivedAtUtcMillis)
    }

    @Test fun connectedSnapshotNeedsANewerDeviceUpdateBeforeItCanBeUsed() {
        val c = PowerOceanGridCorrelation(profile)
        c.observe(grid(0), 1_000, false, false, allowSnapshotBaseline = true)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(1_001).state)
        c.observe(power(700f), 2_000, false, true, allowSnapshotBaseline = true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, c.snapshot(2_000).state)
        assertEquals(1_000L, c.snapshot(2_000).evidenceReceivedAtUtcMillis)
    }

    @Test fun unchangedGridCodeIsCarriedWhileNewDeviceReadingsContinue() {
        val c = PowerOceanGridCorrelation(profile)
        c.observe(grid(0), 1_000, false, false, true)
        c.observe(power(700f), 2_000, false, true, true)
        c.observe(power(720f), 80_000, false, true, true)
        c.observe(power(740f), 150_000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, c.snapshot(151_000).state)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(241_000).state)
    }

    @Test fun cachedConnectedReplyCannotOverwriteADeviceReportedOutage() {
        val c = PowerOceanGridCorrelation(profile)
        c.observe(grid(1), 1_000, false, true, true)
        c.observe(meter(0f), 2_000, false, true, true)
        c.observe(grid(0), 3_000, false, false, true)
        c.observe(power(720f), 4_000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, c.snapshot(4_000).state)
    }

    @Test fun incidentOrManualInspectionCannotBootstrapFromASnapshot() {
        val c = PowerOceanGridCorrelation(profile)
        c.observe(grid(0), 1_000, false, false, true)
        c.observe(power(700f), 2_000, false, true, allowSnapshotBaseline = false)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(2_000).state)
        c.observe(grid(0), 3_000, false, false)
        c.observe(power(720f), 4_000, false, true)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(4_000).state)
    }

    @Test fun retainedExpiredUnsupportedAndOffGridSnapshotsCannotBootstrap() {
        for ((code, retained, pushAt) in listOf(Triple(0L, true, 2_000L), Triple(0L, false, 100_000L),
            Triple(99L, false, 2_000L), Triple(1L, false, 2_000L))) {
            val c = PowerOceanGridCorrelation(profile)
            c.observe(grid(code), 1_000, retained, false, true)
            c.observe(power(700f), pushAt, false, true, true)
            assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(pushAt).state)
        }
    }

    @Test fun actualGridChangeOverridesTheInitialConnectedSnapshot() {
        val c = PowerOceanGridCorrelation(profile)
        c.observe(grid(0), 1_000, false, false, true)
        c.observe(grid(1), 2_000, false, true, true)
        c.observe(meter(0f), 3_000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, c.snapshot(3_000).state)
    }

    @Test fun sampledConnectionNeedsNewDeviceDataAfterAnIntentionalGapAndKeepsOriginalCodeTime() {
        val c = PowerOceanGridCorrelation(profile)
        c.observe(grid(0), 1000, false, false, true); c.observe(power(700f), 2000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(3_600_000).state)
        c.observe(grid(0), 3_600_001, false, false, true)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(3_600_001).state)
        c.observe(power(740f), 3_601_000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, c.snapshot(3_601_000).state)
        assertEquals(1000L, c.snapshot(3_601_000).evidenceReceivedAtUtcMillis)
    }
    @Test fun knownOffGridEpisodeKeepsZeroAcrossClosedConnectionButCannotRecoverFromCachedCodeAlone() {
        val c = PowerOceanGridCorrelation(profile)
        c.observe(grid(1), 1000, false, true, true); c.observe(meter(0f), 2000, false, true, true)
        c.observe(grid(0), 122_000, false, false, true)
        c.observe(grid(1), 123_000, false, true, true)
        assertTrue(c.snapshot(123_000).zeroFlowSeen)
        c.observe(meter(-800f), 124_000, false, true, true)
        c.observe(meter(-790f), 127_000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY, c.snapshot(127_000).state)
        c.observe(grid(0), 128_000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, c.snapshot(128_000).state)
    }
    @Test fun replyMeterArrivingBeforeDeviceFeedIsUsedOnlyAfterDeviceDataArrives() {
        val c = PowerOceanGridCorrelation(profile)
        c.observe(grid(0), 1000, false, false, true); c.observe(meter(12f), 1100, false, false, true)
        assertNull(c.snapshot(1100).currentMeterValue)
        c.observe(power(700f), 2000, false, true, true)
        assertEquals(12.0, c.snapshot(2000).currentMeterValue!!, 0.0)
    }
    @Test fun restartedKnownOutageStillNeedsLiveMeterReturnEvidence() {
        val c = PowerOceanGridCorrelation(profile); c.resumeOffGridEpisode(1000)
        c.observe(grid(0), 5000, false, false, true)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(5000).state)
        c.observe(power(700f), 6000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, c.snapshot(6000).state)
        c.observe(meter(-800f), 7000, false, true, true); c.observe(meter(-790f), 10_000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY, c.snapshot(10_000).state)
    }
    @Test fun changingMeterActivityAcrossBoundedChecksCanEstablishReturnAfterLongIntentionalGap() {
        val c = PowerOceanGridCorrelation(profile); c.resumeOffGridEpisode(1000)
        c.observe(meter(-800f), 2000, false, false, true)
        c.observe(power(700f), 3000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_OFF_GRID, c.snapshot(3000).state)
        c.observe(meter(-790f), 183_000, false, false, true)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, c.snapshot(183_000).state)
        c.observe(power(740f), 184_000, false, true, true)
        assertEquals(PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY, c.snapshot(184_000).state)
        assertEquals(183_000L, c.snapshot(184_000).evidenceReceivedAtUtcMillis)
    }
    private fun power(watts: Float) = PowerOceanPushDecoder.Report(33, mapOf("sysLoadPwr" to watts))

    private fun grid(code: Long) = PowerOceanPushDecoder.Report(8, mapOf("sysGridSta" to code))
    private fun meter(value: Float) = PowerOceanPushDecoder.Report(1, mapOf(profile.meterKey to value))
}
