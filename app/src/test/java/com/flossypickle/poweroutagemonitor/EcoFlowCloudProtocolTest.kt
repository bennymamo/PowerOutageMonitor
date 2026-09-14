package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudGridSignalMapper
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudQuota
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudSigner
import org.junit.Assert.assertEquals
import org.junit.Test

class EcoFlowCloudProtocolTest {
    @Test
    fun `signer matches EcoFlow's published test vector`() {
        val sign = EcoFlowCloudSigner.sign(
            parameters = mapOf(
                "sn" to "123456789",
                "params.cmdSet" to "11",
                "params.id" to "24",
                "params.eps" to "0"
            ),
            accessKey = "Fp4SvIprYSDPXtYJidEtUAd1o",
            secretKey = "WIbFEKre0s6sLnh4ei7SPUeYnptHG6V",
            nonce = "345164",
            timestampEpochMs = 1_671_171_709_428L
        )

        assertEquals(
            "07c13b65e037faf3b153d51613638fa80003c4c38d2407379a7f52851af1473e",
            sign
        )
    }

    @Test
    fun `phase voltage proves grid available even when grid flow is zero`() {
        val signal = EcoFlowCloudGridSignalMapper.toSignal(
            quota(phaseVoltages = listOf(229.4), gridPowerWatts = 0.0),
            observedAtEpochMs = 1_000L
        )

        assertEquals(GridAvailability.AVAILABLE, signal.availability)
    }

    @Test
    fun `all reported phases below threshold prove grid unavailable`() {
        val signal = EcoFlowCloudGridSignalMapper.toSignal(
            quota(phaseVoltages = listOf(0.0, 0.0, 0.0)),
            observedAtEpochMs = 1_000L
        )

        assertEquals(GridAvailability.UNAVAILABLE, signal.availability)
    }

    @Test
    fun `grid flow without phase voltage is never treated as outage evidence`() {
        val signal = EcoFlowCloudGridSignalMapper.toSignal(
            quota(phaseVoltages = emptyList(), gridPowerWatts = 0.0),
            observedAtEpochMs = 1_000L
        )

        assertEquals(GridAvailability.UNKNOWN, signal.availability)
    }

    private fun quota(
        phaseVoltages: List<Double>,
        gridPowerWatts: Double? = null
    ) = EcoFlowCloudQuota(
        phaseVoltages = phaseVoltages,
        gridPowerWatts = gridPowerWatts,
        loadPowerWatts = null,
        solarPowerWatts = null,
        batteryPowerWatts = null,
        batteryPercent = null
    )
}
