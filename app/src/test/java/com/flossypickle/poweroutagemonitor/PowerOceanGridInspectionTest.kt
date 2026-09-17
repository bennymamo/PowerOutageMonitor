package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanGridCorrelation
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanGridInspection
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanPushDecoder
import org.junit.Assert.*
import org.junit.Test

class PowerOceanGridInspectionTest {
    @Test fun missingRetainedAndUnrelatedReportsCannotRefreshGridEvidence() {
        val inspection = PowerOceanGridInspection()
        inspection.observe(report(0), 100, false)
        inspection.observe(PowerOceanPushDecoder.Report(8, mapOf("bpSoc" to 50L)), 200, false)
        inspection.observe(report(1), 300, true)
        inspection.observe(PowerOceanPushDecoder.Report(17, mapOf("sysGridSta" to 1L)), 400, false)
        assertEquals(0L, inspection.snapshot().lastCode)
        assertEquals(100L, inspection.snapshot().lastReceivedUtcMillis)
        assertEquals(1, inspection.snapshot().changes.size)
    }

    @Test fun utilityLossAndRecoveryKeepTheirReceiptTimesWithoutAssumingCodeMeanings() {
        val inspection = PowerOceanGridInspection()
        inspection.observe(report(0), 100, false)
        inspection.observe(report(0), 200, false)
        inspection.observe(report(1), 300, false)
        inspection.observe(report(0), 900, false)
        inspection.observe(report(1), 800, false)
        assertEquals(listOf(0L, 1L, 0L), inspection.snapshot().changes.map { it.code })
        assertEquals(listOf(100L, 300L, 900L), inspection.snapshot().changes.map { it.receivedUtcMillis })
        assertEquals(900L, inspection.snapshot().lastReceivedUtcMillis)
    }

    @Test fun comparisonIsBoundedAndSnapshotsDoNotChangeAfterLaterReports() {
        val inspection = PowerOceanGridInspection()
        inspection.observe(report(0), 100, false)
        val original = inspection.snapshot()
        repeat(20) { inspection.observe(report((it % 2).toLong()), 200L + it, false) }
        assertEquals(8, inspection.snapshot().changes.size)
        assertEquals(listOf(0L), original.changes.map { it.code })
        assertEquals(100L, original.lastReceivedUtcMillis)
    }

    @Test fun requestReplyGridValueIsDisplayableButItsOriginRemainsExplicit() {
        val inspection = PowerOceanGridInspection(PowerOceanGridCorrelation.Profile())
        inspection.observe(report(0), 100, false, false)
        val snapshot = inspection.snapshot(100)
        assertEquals(0L, snapshot.lastCode); assertFalse(snapshot.lastCodeFromDevicePush)
        assertEquals(PowerOceanGridCorrelation.State.UNKNOWN, snapshot.correlation!!.state)
    }
    @Test fun meterDisplayKeepsActualReceiptAndOriginWithoutInventingMissingZero() {
        val inspection = PowerOceanGridInspection()
        inspection.observe(PowerOceanPushDecoder.Report(1, mapOf("meterHeartBeat[0].meterData[0]" to -50.0)), 100, false, false)
        inspection.observe(PowerOceanPushDecoder.Report(1, emptyMap()), 200, false, true)
        inspection.observe(PowerOceanPushDecoder.Report(1, mapOf("meterHeartBeat[0].meterData[0]" to 0.0)), 300, true, true)
        assertEquals(-50.0, inspection.snapshot().meterValue!!, 0.0)
        assertEquals(100L, inspection.snapshot().meterReceivedUtcMillis); assertFalse(inspection.snapshot().meterFromDevicePush)
    }
    @Test fun laterLiveMeterValueReplacesReplyButOlderAndInvalidReportsCannot() {
        val inspection = PowerOceanGridInspection()
        fun meter(value: Double) = PowerOceanPushDecoder.Report(1, mapOf("meterHeartBeat[0].meterData[0]" to value))
        inspection.observe(meter(10.0), 100, false, false)
        inspection.observe(meter(20.0), 200, false, true)
        inspection.observe(meter(30.0), 150, false, false)
        inspection.observe(meter(Double.NaN), 300, false, true)
        assertEquals(20.0, inspection.snapshot().meterValue!!, 0.0)
        assertEquals(200L, inspection.snapshot().meterReceivedUtcMillis); assertTrue(inspection.snapshot().meterFromDevicePush)
    }
    private fun report(code: Long) = PowerOceanPushDecoder.Report(8, mapOf("sysGridSta" to code))
}
