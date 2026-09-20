package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import org.junit.Assert.*
import org.junit.Test

class PowerOceanSampledOutageTest {
    private val profile = PowerOceanGridCorrelation.Profile()
    private fun grid(code: Long) = PowerOceanPushDecoder.Report(8, mapOf("sysGridSta" to code))
    private fun meter(value: Double) = PowerOceanPushDecoder.Report(1, mapOf(profile.meterKey to value))
    private fun power(value: Double) = PowerOceanPushDecoder.Report(33, mapOf("sysLoadPwr" to value))

    @Test fun connectedThenSampledOutageThenChangingMeterReturnWithCodeStillOffGrid() {
        val inspection = PowerOceanGridInspection(profile)
        val live = PowerOceanLiveCheck()
        fun observe(report: PowerOceanPushDecoder.Report, at: Long, push: Boolean) {
            inspection.observe(report, at, false, push, true)
            live.observe(report, at, false, push)
            inspection.corroborateSampledOffGrid(live.status(), 3, at)
        }
        live.begin(1_000)
        observe(grid(0), 1_100, false)
        observe(meter(-3_800.0), 1_200, false)
        observe(power(700.0), 2_000, true)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, inspection.snapshot(2_000).correlation!!.state)

        live.begin(121_000)
        observe(grid(1), 121_100, false)
        observe(meter(0.0), 121_200, false)
        observe(power(710.0), 122_000, true)
        observe(power(720.0), 124_000, true)
        observe(power(730.0), 126_000, true)
        val loss = inspection.snapshot(126_000).correlation!!
        assertEquals(GridAvailability.UNAVAILABLE, PowerOceanLossConfirmation.evaluate(loss, false, true).availability)
        assertTrue(PowerOceanLossConfirmation.evidenceReceivedAt(loss, live.status(), 126_000, 3, false)!! > 121_000)

        live.begin(241_000)
        observe(grid(1), 241_100, false)
        observe(meter(-3_308.0), 241_200, false)
        observe(power(740.0), 242_000, true)
        observe(meter(-3_300.0), 245_000, false)
        val restored = inspection.snapshot(245_000).correlation!!
        assertEquals(GridAvailability.AVAILABLE, PowerOceanLossConfirmation.evaluate(restored, false, true).availability)
        // Balanced net flow after restoration must not replay the same off-grid episode.
        observe(meter(0.0), 246_000, false)
        observe(power(750.0), 247_000, true)
        observe(power(760.0), 249_000, true)
        assertEquals(GridAvailability.AVAILABLE, PowerOceanLossConfirmation.evaluate(inspection.snapshot(249_000).correlation!!, false, true).availability)
    }

    @Test fun replyNeedsCurrentZeroMeterAndEnoughChangingNonRetainedDeviceReports() {
        for (scenario in listOf("valid", "retained", "oldCode", "oldMeter", "nonzero", "unchanged", "tooFew", "expired", "unsupported")) {
            val inspection = PowerOceanGridInspection(profile)
            val live = PowerOceanLiveCheck().apply { begin(10_000) }
            inspection.observe(grid(if (scenario == "unsupported") 99 else 1), if (scenario == "oldCode") 9_000 else 10_100,
                scenario == "retained", false, true)
            inspection.observe(meter(if (scenario == "nonzero") 10.0 else 0.0), if (scenario == "oldMeter") 9_000 else 10_200, false, false, true)
            repeat(if (scenario == "tooFew") 2 else 3) { index ->
                val report = power(if (scenario == "unchanged") 700.0 else 700.0 + index)
                val at = 11_000L + index * 2_000
                inspection.observe(report, at, false, true, true)
                live.observe(report, at, false, true)
            }
            val now = if (scenario == "expired") 120_000L else 15_000L
            inspection.corroborateSampledOffGrid(live.status(), 3, now)
            val result = PowerOceanLossConfirmation.evaluate(inspection.snapshot(now).correlation!!, false, true)
            assertEquals(scenario, if (scenario == "valid") GridAvailability.UNAVAILABLE else GridAvailability.UNKNOWN, result.availability)
        }
    }

    @Test fun zeroMeterReplyMayArriveBeforeGridReplyAndLaterLiveConnectedCodeWins() {
        val inspection = PowerOceanGridInspection(profile)
        val live = PowerOceanLiveCheck().apply { begin(1_000) }
        inspection.observe(meter(0.0), 1_100, false, false, true)
        inspection.observe(grid(1), 1_200, false, false, true)
        repeat(3) { index ->
            val at = 2_000L + index * 2_000
            val report = power(700.0 + index)
            inspection.observe(report, at, false, true, true)
            live.observe(report, at, false, true)
        }
        inspection.corroborateSampledOffGrid(live.status(), 3, 6_000)
        assertEquals(GridAvailability.UNAVAILABLE, PowerOceanLossConfirmation.evaluate(
            inspection.snapshot(6_000).correlation!!, false, true).availability)
        inspection.observe(grid(0), 7_000, false, true, true)
        inspection.corroborateSampledOffGrid(live.status(), 3, 7_000)
        assertEquals(PowerOceanGridCorrelation.State.INVERTER_CONNECTED, inspection.snapshot(7_000).correlation!!.state)
    }
}
