package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal, read-only Modbus TCP codec for EcoFlow PowerOcean telemetry. */
internal object EcoFlowModbusProtocol {
    const val READ_HOLDING_REGISTERS = 0x03
    const val DEFAULT_PORT = 502
    const val DEFAULT_UNIT_ID = 1
    const val GRID_BLOCK_START = 40_530
    const val GRID_BLOCK_REGISTER_COUNT = 66

    private const val MBAP_HEADER_SIZE = 7
    private const val MAX_READ_REGISTERS = 125

    fun readHoldingRegistersRequest(
        transactionId: Int,
        address: Int,
        count: Int,
        unitId: Int = DEFAULT_UNIT_ID
    ): ByteArray {
        require(transactionId in 0..0xffff)
        require(address in 0..0xffff)
        require(count in 1..MAX_READ_REGISTERS)
        require(address + count - 1 <= 0xffff)
        require(unitId in 0..0xff)

        return ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(transactionId.toShort())
            putShort(0) // Modbus protocol identifier
            putShort(6) // Unit identifier plus five-byte request PDU
            put(unitId.toByte())
            put(READ_HOLDING_REGISTERS.toByte())
            putShort(address.toShort())
            putShort(count.toShort())
        }.array()
    }

    fun responseLengthFromHeader(
        header: ByteArray,
        expectedTransactionId: Int,
        expectedUnitId: Int
    ): Int {
        require(header.size == MBAP_HEADER_SIZE) { "Invalid Modbus TCP header length" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val transactionId = buffer.short.toInt() and 0xffff
        val protocolId = buffer.short.toInt() and 0xffff
        val remainingLength = buffer.short.toInt() and 0xffff
        val unitId = buffer.get().toInt() and 0xff
        require(transactionId == expectedTransactionId) { "Unexpected Modbus transaction" }
        require(protocolId == 0) { "Unexpected Modbus protocol" }
        require(unitId == expectedUnitId) { "Unexpected Modbus unit" }
        require(remainingLength in 3..254) { "Invalid Modbus response length" }
        return remainingLength - 1 // The unit byte was already read in the MBAP header.
    }

    fun parseReadHoldingRegistersResponse(
        pdu: ByteArray,
        expectedCount: Int
    ): IntArray {
        require(expectedCount in 1..MAX_READ_REGISTERS)
        require(pdu.size >= 2) { "Truncated Modbus response" }
        val function = pdu[0].toInt() and 0xff
        if (function == (READ_HOLDING_REGISTERS or 0x80)) {
            val exceptionCode = pdu.getOrNull(1)?.toInt()?.and(0xff)
            throw IllegalArgumentException("Modbus exception ${exceptionCode ?: "unknown"}")
        }
        require(function == READ_HOLDING_REGISTERS) { "Unexpected Modbus function" }
        val byteCount = pdu[1].toInt() and 0xff
        require(byteCount == expectedCount * 2) { "Unexpected Modbus register count" }
        require(pdu.size == byteCount + 2) { "Truncated Modbus register data" }
        val buffer = ByteBuffer.wrap(pdu, 2, byteCount).order(ByteOrder.BIG_ENDIAN)
        return IntArray(expectedCount) { buffer.short.toInt() and 0xffff }
    }

    fun decodeLowWordFirstUInt32(lowWord: Int, highWord: Int): Long {
        require(lowWord in 0..0xffff && highWord in 0..0xffff)
        return (highWord.toLong() shl 16) or lowWord.toLong()
    }

    fun decodeLowWordFirstFloat(lowWord: Int, highWord: Int): Float {
        val bits = decodeLowWordFirstUInt32(lowWord, highWord).toInt()
        return Float.fromBits(bits)
    }
}
