package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Opens one short-lived, read-only Modbus TCP transaction per telemetry refresh. */
internal class EcoFlowModbusClient(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 3_000
) {
    fun readGridRegisters(
        host: String,
        port: Int = EcoFlowModbusProtocol.DEFAULT_PORT,
        unitId: Int = EcoFlowModbusProtocol.DEFAULT_UNIT_ID
    ): IntArray {
        require(host.isNotBlank()) { "Inverter address is required" }
        require(port in 1..65_535)
        require(unitId in 0..247)
        val transactionId = nextTransactionId.getAndIncrement() and 0xffff
        val request = EcoFlowModbusProtocol.readHoldingRegistersRequest(
            transactionId = transactionId,
            address = EcoFlowModbusProtocol.GRID_BLOCK_START,
            count = EcoFlowModbusProtocol.GRID_BLOCK_REGISTER_COUNT,
            unitId = unitId
        )
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.getOutputStream().apply {
                write(request)
                flush()
            }
            val header = socket.getInputStream().readExactly(7)
            val pduLength = EcoFlowModbusProtocol.responseLengthFromHeader(
                header, transactionId, unitId
            )
            val pdu = socket.getInputStream().readExactly(pduLength)
            EcoFlowModbusProtocol.parseReadHoldingRegistersResponse(
                pdu, EcoFlowModbusProtocol.GRID_BLOCK_REGISTER_COUNT
            )
        }
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            if (read < 0) throw EOFException("Modbus connection closed during response")
            offset += read
        }
        return bytes
    }

    companion object {
        private val nextTransactionId = AtomicInteger(1)
    }
}
