package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowGridReading
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowGridSignalMapper
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowModbusProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EcoFlowModbusProtocolTest {
    @Test
    fun `builds read-only PowerOcean register request`() {
        val request = EcoFlowModbusProtocol.readHoldingRegistersRequest(
            transactionId = 0x1234,
            address = 40_530,
            count = 66,
            unitId = 1
        )

        assertArrayEquals(
            byteArrayOf(
                0x12, 0x34, 0, 0, 0, 6, 1, 3,
                0x9e.toByte(), 0x52, 0, 0x42
            ),
            request
        )
    }

    @Test
    fun `validates response identity before reading payload`() {
        val header = byteArrayOf(0x12, 0x34, 0, 0, 0, 7, 1)

        assertEquals(6, EcoFlowModbusProtocol.responseLengthFromHeader(header, 0x1234, 1))
        assertThrows(IllegalArgumentException::class.java) {
            EcoFlowModbusProtocol.responseLengthFromHeader(header, 0x4321, 1)
        }
    }

    @Test
    fun `parses register words in network order`() {
        val response = byteArrayOf(3, 4, 0x12, 0x34, 0xab.toByte(), 0xcd.toByte())

        assertArrayEquals(
            intArrayOf(0x1234, 0xabcd),
            EcoFlowModbusProtocol.parseReadHoldingRegistersResponse(response, 2)
        )
    }

    @Test
    fun `rejects Modbus exception response`() {
        assertThrows(IllegalArgumentException::class.java) {
            EcoFlowModbusProtocol.parseReadHoldingRegistersResponse(
                byteArrayOf(0x83.toByte(), 2),
                2
            )
        }
    }

    @Test
    fun `decodes PowerOcean low-word-first values`() {
        assertEquals(65_536L, EcoFlowModbusProtocol.decodeLowWordFirstUInt32(0, 1))
        assertEquals(
            123.5f,
            EcoFlowModbusProtocol.decodeLowWordFirstFloat(0, 0x42f7),
            0.001f
        )
    }

    @Test
    fun `grid-connected mode and mains voltage means available`() {
        val signal = EcoFlowGridSignalMapper.toSignal(
            EcoFlowGridReading(systemModes = 0, gridVoltageL1 = 230.4f, frequencyHz = 50f),
            observedAtEpochMs = 123L
        )

        assertEquals(GridAvailability.AVAILABLE, signal.availability)
    }

    @Test
    fun `islanded mode and absent voltage means unavailable`() {
        val signal = EcoFlowGridSignalMapper.toSignal(
            EcoFlowGridReading(systemModes = 1, gridVoltageL1 = 0f, frequencyHz = 0f),
            observedAtEpochMs = 123L
        )

        assertEquals(GridAvailability.UNAVAILABLE, signal.availability)
    }

    @Test
    fun `contradictory EcoFlow readings are unknown`() {
        val islandedWithMains = EcoFlowGridSignalMapper.toSignal(
            EcoFlowGridReading(systemModes = 1, gridVoltageL1 = 230f, frequencyHz = 50f), 123L
        )
        val gridModeWithoutMains = EcoFlowGridSignalMapper.toSignal(
            EcoFlowGridReading(systemModes = 0, gridVoltageL1 = 0f, frequencyHz = 0f), 123L
        )

        assertEquals(GridAvailability.UNKNOWN, islandedWithMains.availability)
        assertEquals(GridAvailability.UNKNOWN, gridModeWithoutMains.availability)
    }

    @Test
    fun `invalid telemetry is unknown`() {
        val signal = EcoFlowGridSignalMapper.toSignal(
            EcoFlowGridReading(systemModes = 0, gridVoltageL1 = Float.NaN, frequencyHz = 50f),
            123L
        )

        assertEquals(GridAvailability.UNKNOWN, signal.availability)
    }
}
