package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanPushDecoder
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanReadingRequests
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PowerOceanPushDecoderTest {
    @Test fun packedMeterValuesPreservePositionsAndExplicitZeroWithoutInventingMeasurements() {
        val packed = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(230.5f).putFloat(0f).putFloat(Float.NaN).array()
        val report = PowerOceanPushDecoder.decode(frame(1, bytes(30, scalar(1, 2) + bytes(3, packed)))).single()
        assertEquals(230.5f, report.values["meterHeartBeat[0].meterData[0]"])
        assertEquals(0f, report.values["meterHeartBeat[0].meterData[1]"])
        assertFalse(report.values.containsKey("meterHeartBeat[0].meterData[2]"))
        assertEquals(2L, report.values["meterHeartBeat[0].meterType"])
        assertFalse(report.values.containsKey("gridIsEnergized"))
    }
    @Test fun explicitFalseIsPreservedButAbsentGridFlagIsNotInvented() {
        val falseReport = PowerOceanPushDecoder.decode(frame(8, scalar(752, 0))).single()
        assertEquals(false, falseReport.values["gridIsEnergized"])
        assertFalse(PowerOceanPushDecoder.decode(frame(8, scalar(7, 60))).single().values.containsKey("gridIsEnergized"))
    }
    @Test fun stateReportDoesNotInheritConfigurationGridDefaults() {
        assertFalse(PowerOceanPushDecoder.decode(frame(17, scalar(21, 2) + scalar(752, 0))).single().values.containsKey("gridIsEnergized"))
    }
    @Test fun truncatedAndEncryptedPacketsDoNotBecomeGridObservations() {
        assertTrue(PowerOceanPushDecoder.decode(byteArrayOf(10, 127, 8)).isEmpty())
        assertTrue(PowerOceanPushDecoder.decode(bytes(1, scalar(6, 1) + scalar(8, 96) + scalar(9, 8) + bytes(1, scalar(752, 0)))).isEmpty())
    }
    @Test fun sequenceMaskedReportDecodesExplicitGridFlag() {
        val body = scalar(752, 1)
        val masked = ByteArray(body.size) { (body[it].toInt() xor 37).toByte() }
        val packet = bytes(1, scalar(6, 1) + scalar(14, 37) + scalar(8, 96) + scalar(9, 8) + bytes(1, masked))
        assertEquals(true, PowerOceanPushDecoder.decode(packet).single().values["gridIsEnergized"])
    }
    @Test fun liveReportingRequestMatchesPortalFrameAndIsNotAStateReport() {
        val payload = PowerOceanReadingRequests.liveReporting(37)
        val expected = bytes(1, bytes(1, scalar(1, 1)) + scalar(2, 32) + scalar(3, 96) +
            scalar(4, 1) + scalar(5, 1) + scalar(8, 96) + scalar(9, 97) + scalar(10, 2) +
            scalar(11, 1) + scalar(14, 37) + scalar(16, 3) + scalar(17, 1))
        assertArrayEquals(expected, payload)
        assertTrue(PowerOceanPushDecoder.decode(payload).isEmpty())
    }
    private fun frame(command: Int, body: ByteArray) = bytes(1, scalar(8, 96) + scalar(9, command) + bytes(1, body))
    private fun scalar(field: Int, value: Int) = varint(field * 8) + varint(value)
    private fun bytes(field: Int, value: ByteArray) = varint(field * 8 + 2) + varint(value.size) + value
    private fun varint(value: Int): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream()
        while (remaining >= 128) { output.write((remaining and 127) or 128); remaining = remaining ushr 7 }
        output.write(remaining)
        return output.toByteArray()
    }
}
