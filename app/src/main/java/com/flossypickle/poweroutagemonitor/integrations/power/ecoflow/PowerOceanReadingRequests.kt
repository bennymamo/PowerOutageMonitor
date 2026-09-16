package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import java.io.ByteArrayOutputStream

/** The only SET payload supported here is the portal's temporary live-report request (96/97).
 * No configurable command IDs or equipment-control payloads are accepted.
 * Protocol adapted from shuette42 (MIT); see THIRD_PARTY_NOTICES.md.
 */
internal object PowerOceanReadingRequests {
    fun liveReporting(sequence: Int): ByteArray {
        require(sequence >= 0)
        val body = scalar(1, 1)
        val header = bytes(1, body) + scalar(2, 32) + scalar(3, 96) + scalar(4, 1) + scalar(5, 1) +
            scalar(8, 96) + scalar(9, 97) + scalar(10, body.size) + scalar(11, 1) +
            scalar(14, sequence) + scalar(16, 3) + scalar(17, 1)
        return bytes(1, header)
    }

    fun allReadings(sequence: Int): ByteArray {
        require(sequence >= 0)
        return bytes(1, scalar(2, 32) + scalar(3, 32) + scalar(14, sequence) + bytes(23, "app".toByteArray(Charsets.UTF_8)))
    }

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
