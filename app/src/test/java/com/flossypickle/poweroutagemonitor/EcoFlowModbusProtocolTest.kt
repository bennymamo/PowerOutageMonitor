package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowGridReading
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowGridSignalMapper
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowModbusClient
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowModbusProtocol
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun `client performs one read-only TCP transaction`() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val serverResult = executor.submit {
                server.accept().use { socket ->
                    val request = socket.getInputStream().readFully(12)
                    assertEquals(3, request[7].toInt() and 0xff)
                    assertEquals(40_530, unsignedShort(request[8], request[9]))
                    assertEquals(66, unsignedShort(request[10], request[11]))
                    val response = ByteArray(7 + 2 + 132)
                    response[0] = request[0]
                    response[1] = request[1]
                    response[4] = 0
                    response[5] = 135.toByte() // unit byte + response PDU
                    response[6] = 1
                    response[7] = 3
                    response[8] = 132.toByte()
                    response[9] = 0x12
                    response[10] = 0x34
                    socket.getOutputStream().apply { write(response); flush() }
                }
            }

            val registers = EcoFlowModbusClient(1_000, 1_000)
                .readGridRegisters("127.0.0.1", server.localPort, 1)

            serverResult.get(2, TimeUnit.SECONDS)
            executor.shutdownNow()
            assertEquals(0x1234, registers[0])
            assertEquals(66, registers.size)
        }
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
    fun `decodes mode voltage and frequency from one coherent register block`() {
        val registers = IntArray(EcoFlowModbusProtocol.GRID_BLOCK_REGISTER_COUNT)
        registers[0] = 1 // system_modes low word: islanded
        writeFloat(registers, 40_580, 0f)
        writeFloat(registers, 40_594, 0f)

        val reading = EcoFlowGridSignalMapper.decode(registers)

        assertEquals(true, reading.islanded)
        assertEquals(0f, reading.gridVoltageL1, 0.001f)
        assertEquals(0f, reading.frequencyHz, 0.001f)
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

    private fun writeFloat(registers: IntArray, address: Int, value: Float) {
        val bits = value.toRawBits()
        val offset = address - EcoFlowModbusProtocol.GRID_BLOCK_START
        registers[offset] = bits and 0xffff
        registers[offset + 1] = bits ushr 16
    }

    private fun InputStream.readFully(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < result.size) {
            val read = read(result, offset, result.size - offset)
            check(read >= 0)
            offset += read
        }
        return result
    }

    private fun unsignedShort(high: Byte, low: Byte) =
        ((high.toInt() and 0xff) shl 8) or (low.toInt() and 0xff)
}
