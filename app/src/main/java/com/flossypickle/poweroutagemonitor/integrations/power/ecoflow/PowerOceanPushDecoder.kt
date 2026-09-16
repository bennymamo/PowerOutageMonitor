package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded protobuf-wire decoder for identified PowerOcean reports; absent fields stay absent. */
internal object PowerOceanPushDecoder {
    private data class Field(val number: Int, val wire: Int, val scalar: Long? = null, val bytes: ByteArray? = null)
    data class Report(val command: Int, val values: Map<String, Any>)

    fun decode(payload: ByteArray): List<Report> = runCatching {
        require(payload.size <= 262_144)
        fields(payload).filter { it.number == 1 && it.wire == 2 }.mapNotNull { envelope ->
            val header = fields(requireNotNull(envelope.bytes))
            val encoding = header.firstOrNull { it.number == 6 }?.scalar ?: 0L
            if (encoding !in 0..1) return@mapNotNull null
            if (header.firstOrNull { it.number == 8 }?.scalar != 96L) return@mapNotNull null
            val command = header.firstOrNull { it.number == 9 }?.scalar?.toInt() ?: return@mapNotNull null
            val payloadBytes = header.firstOrNull { it.number == 1 }?.bytes ?: return@mapNotNull null
            val unmasked = if (encoding == 1L) {
                val sequence = header.firstOrNull { it.number == 14 }?.scalar ?: return@mapNotNull null
                val mask = (sequence and 255).toInt()
                ByteArray(payloadBytes.size) { (payloadBytes[it].toInt() xor mask).toByte() }
            } else payloadBytes
            val body = fields(unmasked)
            val result = linkedMapOf<String, Any>()
            fun scalar(number: Int, key: String, float: Boolean = false, bool: Boolean = false) {
                body.firstOrNull { it.number == number }?.let { field ->
                    val value = if (float && field.wire == 5) Float.fromBits(field.scalar!!.toInt()).takeIf(Float::isFinite)
                        else if (!float && field.wire == 0) if (bool) field.scalar?.takeIf { it in 0..1 }?.let { it == 1L } else field.scalar
                        else null
                    if (value != null) result[key] = value
                }
            }
            when (command) {
                33 -> {
                    scalar(1, "sysLoadPwr", true); scalar(2, "sysGridPwr", true); scalar(3, "mpptPwr", true)
                    scalar(4, "bpPwr", true); scalar(5, "bpSoc")
                }
                1 -> {
                    scalar(10, "pcsActPwr", true); scalar(11, "pcsAcFreq", true)
                    scalar(45, "pcsMeterPower", true); scalar(59, "emsBpPower", true)
                    scalar(57, "emsActiveOffGridCmd"); scalar(58, "emsBpAliveNum")
                    listOf(12 to "pcsAPhase", 13 to "pcsBPhase", 14 to "pcsCPhase").forEach { (number, name) ->
                        body.firstOrNull { it.number == number && it.wire == 2 }?.bytes?.let { nested ->
                            fields(nested).forEach { field ->
                                val leaf = mapOf(1 to "vol", 2 to "amp", 3 to "actPwr", 4 to "reactPwr", 5 to "apparentPwr")[field.number]
                                if (leaf != null && field.wire == 5) Float.fromBits(field.scalar!!.toInt()).takeIf(Float::isFinite)?.let { result["$name.$leaf"] = it }
                            }
                        }
                    }
                    body.filter { it.number == 31 && it.wire == 2 }.forEachIndexed { tracker, item ->
                        fields(item.bytes!!).filter { it.number == 1 && it.wire == 2 }.forEachIndexed { string, pv ->
                            fields(pv.bytes!!).forEach { field ->
                                val leaf = mapOf(1 to "vol", 2 to "amp", 3 to "pwr")[field.number]
                                if (leaf != null && field.wire == 5) Float.fromBits(field.scalar!!.toInt()).takeIf(Float::isFinite)?.let { result["mpptHeartBeat[$tracker].mpptPv[$string].$leaf"] = it }
                            }
                        }
                    }
                }
                8 -> {
                    scalar(752, "gridIsEnergized", bool = true); scalar(2, "sysGridSta")
                    scalar(1, "sysWorkSta"); scalar(3, "emsWorkMode"); scalar(7, "bpSoc")
                    scalar(9, "bpOnlineSum"); scalar(21, "pcsRunSta")
                }
                17 -> { scalar(21, "pcsRunSta"); scalar(22, "pcsAcErrCode"); scalar(23, "pcsDcErrCode") }
                50 -> {
                    val totals = body.filter { it.number == 1 && it.wire == 2 }.map { fields(it.bytes!!) }
                        .filter { row -> row.firstOrNull { it.number == 6 }?.bytes?.isNotEmpty() != true }
                    if (totals.size == 1) totals.single().forEach { field ->
                        val key = mapOf(1 to "sysLoadPwr", 2 to "sysGridPwr", 3 to "mpptPwr", 4 to "bpPwr", 5 to "bpSoc")[field.number]
                        if (key != null && field.number == 5 && field.wire == 0) result[key] = field.scalar!!
                        else if (key != null && field.wire == 5) Float.fromBits(field.scalar!!.toInt()).takeIf(Float::isFinite)?.let { result[key] = it }
                    }
                }
                7 -> {
                    body.filter { it.number == 1 && it.wire == 2 }.forEachIndexed { index, item ->
                        fields(item.bytes!!).forEach { field ->
                            val name = mapOf(1 to "bpPwr", 2 to "bpSoc", 3 to "bpSoh", 9 to "bpVol", 10 to "bpAmp",
                                13 to "bpErrCode", 17 to "bpCycles", 25 to "bpEnvTemp", 30 to "bpMaxCellTemp",
                                31 to "bpMinCellTemp", 37 to "bpTimestamp", 54 to "bpRemainWatth")[field.number]
                            if (name != null) {
                                val value = if (field.number in setOf(2, 3, 13, 17, 37) && field.wire == 0) field.scalar
                                    else if (field.wire == 5) Float.fromBits(field.scalar!!.toInt()).takeIf(Float::isFinite) else null
                                if (value != null) result["BP_STA_REPORT[$index].$name"] = value
                            }
                        }
                    }
                }
            }
            if (result.isEmpty()) null else Report(command, result)
        }
    }.getOrDefault(emptyList())

    private fun fields(bytes: ByteArray): List<Field> {
        require(bytes.size <= 262_144)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(buffer.hasRemaining())
                val byte = buffer.get().toInt() and 255
                require(shift != 63 || byte <= 1)
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            error("Invalid varint")
        }
        val result = ArrayList<Field>()
        while (buffer.hasRemaining()) {
            require(result.size < 4096)
            val tag = varint()
            require(tag in 8..4_294_967_295L)
            val number = (tag ushr 3).toInt()
            require(number in 1..536_870_911)
            val wire = (tag and 7).toInt()
            result += when (wire) {
                0 -> Field(number, wire, scalar = varint())
                1 -> Field(number, wire, scalar = buffer.long)
                5 -> Field(number, wire, scalar = buffer.int.toLong())
                2 -> {
                    val size = varint()
                    require(size in 0..buffer.remaining().toLong())
                    Field(number, wire, bytes = ByteArray(size.toInt()).also { buffer.get(it) })
                }
                else -> error("Unsupported wire field")
            }
        }
        return result
    }
}
