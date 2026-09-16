package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudGridSignalMapper
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudQuota
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudSigner
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowPowerOceanRequest
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudError
import org.junit.Assert.assertEquals
import org.junit.Test

class EcoFlowCloudProtocolTest {
    @Test
    fun `PowerOcean request signs quota array indices as documented`() {
        val parameters = EcoFlowPowerOceanRequest.signingParameters("example-serial")
        assertEquals("pcsAPhase", parameters["params.quotas[0]"])
        assertEquals("sysGridPwr", parameters["params.quotas[8]"])
        assertEquals(10, parameters.size)
        assertEquals(
            "params.quotas[0]=pcsAPhase&params.quotas[1]=pcsBPhase&params.quotas[2]=pcsCPhase&" +
                "params.quotas[3]=mpptHeartBeat&params.quotas[4]=mpptPwr&params.quotas[5]=bpSoc&" +
                "params.quotas[6]=bpPwr&params.quotas[7]=sysLoadPwr&params.quotas[8]=sysGridPwr&" +
                "sn=example-serial&accessKey=example-key&nonce=123456&timestamp=1000",
            EcoFlowCloudSigner.canonicalRequest(parameters, "example-key", "123456", 1000)
        )
    }

    @Test
    fun `provider errors preserve diagnostic code but redact private values before truncation`() {
        assertEquals("EcoFlow code 1006: denied [redacted] [redacted] [redacted] [redacted]",
            EcoFlowCloudError.describe("1006", "denied example-access example-secret example-serial Bearer other-token", 200,
                listOf("example-access", "example-secret", "example-serial")))
    }

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
