package com.flossypickle.poweroutagemonitor

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

    private fun report(code: Long) = PowerOceanPushDecoder.Report(8, mapOf("sysGridSta" to code))
}
