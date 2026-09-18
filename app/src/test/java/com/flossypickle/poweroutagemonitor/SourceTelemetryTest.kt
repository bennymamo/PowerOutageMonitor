package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetryFlattener
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudQuota
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowTelemetryMapper
import org.junit.Assert.*
import org.junit.Test

class SourceTelemetryTest {
    @Test fun `nested arrays retain zero and false and unknown fields`() {
        val data = mapOf(
            "mpptHeartBeat" to listOf(mapOf("mpptPv" to listOf(mapOf("pwr" to 0)))),
            "custom" to mapOf("enabled" to false, "futureReading" to 123.4),
            "empty" to null, "invalid" to Double.NaN
        )
        val result = SourceTelemetryFlattener.flatten(data)
        assertEquals("0", result.values["mpptHeartBeat[0].mpptPv[0].pwr"])
        assertEquals("false", result.values["custom.enabled"])
        assertEquals("123.4", result.values["custom.futureReading"])
        assertFalse(result.values.containsKey("empty"))
        assertFalse(result.values.containsKey("invalid"))
    }

    @Test fun `flat and nested disagreement is explicitly unavailable`() {
        val result = SourceTelemetryFlattener.flatten(mapOf(
            "pcsAPhase.vol" to 230, "pcsAPhase" to mapOf("vol" to 0)
        ))
        assertEquals(1, result.values.size)
        assertEquals("Conflicting values: unavailable", result.values["pcsAPhase.vol"])
    }

    @Test fun `private fields and embedded credentials are excluded`() {
        val result = SourceTelemetryFlattener.flatten(mapOf(
            "sn" to "private-serial", "latitude" to 12.34,
            "service" to mapOf("access_key" to "private-key", "certificatePassword" to "private-password"),
            "status" to "Bearer private-token", "sysGridPwr" to 0
        ))
        assertEquals(mapOf("sysGridPwr" to "0"), result.values)
        assertEquals(5, result.omittedValues)
    }

    @Test fun `oversized provider data has bounded display`() {
        val result = SourceTelemetryFlattener.flatten((0..1_100).associate { "field$it" to it } +
            mapOf("oversized" to "x".repeat(161)))
        assertEquals(1_024, result.values.size)
        assertTrue(result.omittedValues > 0)
        assertFalse(result.values.containsKey("oversized"))
    }

    @Test fun `snapshot groups phases solar strings and unknown equipment without guessing freshness`() {
        val snapshot = EcoFlowTelemetryMapper.snapshot(quota(mapOf(
            "sysGridPwr" to "0", "bpSoc" to "60", "pcsAPhase.vol" to "229.4",
            "mpptHeartBeat[0].mpptPv[1].vol" to "350.5", "futureSensor" to "hello"
        )), "Test inverter", 123L)
        assertEquals(123L, snapshot.receivedAtEpochMs)
        assertNull(snapshot.deviceReportedAtEpochMs)
        assertEquals(listOf("sysGridPwr", "bpSoc"), snapshot.summary.map { it.key })
        assertEquals("0", snapshot.summary.first().value)
        val solar = snapshot.sections.single { it.id == "solar" }.readings.single()
        assertEquals("Tracker 1 · string 2 voltage", solar.label)
        assertEquals("V", solar.unit)
        assertEquals("hello", snapshot.sections.single { it.id == "technical" }.readings.single().value)
    }

    @Test fun `sentinel temperatures and impossible reserve are not shown as valid measurements`() {
        val readings = EcoFlowTelemetryMapper.snapshot(quota(mapOf(
            "hpMaster.tempAmbient" to "-3270", "bpSoc" to "255"
        )), "Test", 123).sections.flatMap { it.readings }
        assertTrue(readings.all { it.value.startsWith("Not valid") })
    }

    @Test fun `oversized solar index remains an unknown field instead of crashing dashboard`() {
        val key = "mpptHeartBeat[0].mpptPv[99999999999999999999].vol"
        val snapshot = EcoFlowTelemetryMapper.snapshot(quota(mapOf(key to "12")), "Test", 123)
        assertEquals("technical", snapshot.sections.single().id)
        assertEquals(key, snapshot.sections.single().readings.single().label)
    }

    private fun quota(values: Map<String, String>) = EcoFlowCloudQuota(
        emptyList(), null, null, null, null, null, reportedValues = values
    )
}
