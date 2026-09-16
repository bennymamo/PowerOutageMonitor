package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.*
import org.json.JSONArray
import org.json.JSONObject

/** Decodes account reports, including JSON strings and serial-keyed battery collections. */
internal object PowerOceanAccountTelemetry {
    fun snapshot(data: JSONObject, receivedAt: Long): SourceTelemetrySnapshot {
        var visited = 0
        fun decode(value: Any?, depth: Int, parent: String = ""): Any? {
            require(depth <= 12 && ++visited <= 20_000)
            return when (value) {
                null, JSONObject.NULL -> null
                is JSONObject -> {
                    val keys = value.keys().asSequence().sorted().toList()
                    if (parent == "parallel" || parent.endsWith("BP_STA_REPORT")) {
                        // Device identifiers can be dictionary keys, including Base64 serials.
                        keys.map { decode(value.opt(it), depth + 1) }
                    } else keys.associateWith { decode(value.opt(it), depth + 1, it) }
                }
                is JSONArray -> List(value.length()) { decode(value.opt(it), depth + 1) }
                is String -> if (value.trimStart().startsWith("{") || value.trimStart().startsWith("[")) {
                    val parsed = runCatching { if (value.trimStart().startsWith("{")) JSONObject(value) else JSONArray(value) }.getOrNull()
                    if (parsed == null) null else decode(parsed, depth + 1, parent)
                } else value
                else -> value
            }
        }
        val flat = SourceTelemetryFlattener.flatten(decode(data, 0))
        val names = mapOf(
            "sysGridPwr" to ("Grid power" to "W"), "sysLoadPwr" to ("Home load" to "W"),
            "mpptPwr" to ("Solar power" to "W"), "bpPwr" to ("Battery power" to "W"),
            "bpSoc" to ("Battery charge" to "%"), "meterAVoltage" to ("Meter phase A voltage" to "V"),
            "meterBVoltage" to ("Meter phase B voltage" to "V"), "meterCVoltage" to ("Meter phase C voltage" to "V"),
            "meterACurrent" to ("Meter phase A current" to "A"), "meterBCurrent" to ("Meter phase B current" to "A"),
            "meterCCurrent" to ("Meter phase C current" to "A"), "pcsMeterPower" to ("Meter power" to "W"),
            "pcsAcFreq" to ("Inverter AC frequency" to "Hz"), "sysGridSta" to ("Reported grid state · unverified code" to ""),
            "gridIsEnergized" to ("Reported grid energized · unverified" to ""),
            "emsSystemState" to ("Energy management state · raw code" to ""),
            "workingMode" to ("Working mode · raw code" to ""), "sysWorkSta" to ("System state · raw code" to ""),
            "updateTime" to ("Report update time · timezone unverified" to "")
        )
        val grouped = linkedMapOf<String, MutableList<SourceTelemetryReading>>()
        val sections = linkedMapOf("grid" to "Grid and meter observations", "solar" to "Solar",
            "batteries" to "Individual batteries", "system" to "System and diagnostics",
            "overview" to "Cloud overview · may update slowly", "technical" to "Additional reported fields")
        flat.values.forEach { (key, value) ->
            val leaf = key.substringAfterLast('.')
            val friendly = names[leaf] ?: if (Regex(".*pcs[ABC]Phase\\.(vol|amp|actPwr|reactPwr|apparentPwr)").matches(key)) {
                val phase = Regex("pcs([ABC])Phase").find(key)!!.groupValues[1]
                when (leaf) { "vol" -> "Phase $phase voltage" to "V"; "amp" -> "Phase $phase current" to "A"
                    "actPwr" -> "Phase $phase active power" to "W"; "reactPwr" -> "Phase $phase reactive power" to "var"
                    else -> "Phase $phase apparent power" to "VA" }
            } else null
            val section = when {
                key.contains("BP_STA_REPORT") -> "batteries"
                !key.contains('.') -> "overview"
                leaf.contains("meter", true) || leaf.contains("grid", true) || key.contains("Phase") || leaf == "pcsAcFreq" -> "grid"
                leaf.contains("mppt", true) || leaf.startsWith("pv") -> "solar"
                leaf.contains("err", true) || leaf.contains("state", true) || leaf.contains("sta", true) -> "system"
                else -> "technical"
            }
            val label = if (section == "batteries") {
                val index = Regex("BP_STA_REPORT\\[([0-9]+)]").find(key)?.groupValues?.get(1)?.toIntOrNull()
                "Battery ${(index ?: 0) + 1} · ${friendly?.first ?: leaf}"
            } else friendly?.first ?: leaf
            grouped.getOrPut(section) { mutableListOf() }.add(SourceTelemetryReading(key, label, value, friendly?.second.orEmpty()))
        }
        val overview = grouped["overview"].orEmpty()
        return SourceTelemetrySnapshot("PowerOcean account · experimental", "PowerOcean device readings", receivedAt,
            summary = overview.filter { it.key in setOf("sysGridPwr", "sysLoadPwr", "mpptPwr", "bpPwr", "bpSoc") },
            sections = sections.mapNotNull { (id, title) -> grouped[id]?.let { SourceTelemetrySection(id, title, it, id == "grid") } },
            omittedValues = flat.omittedValues,
            acquisitionNote = "Owner-account cloud request. Some overview values may be cached. Report timestamps are shown as supplied; timezone and per-field freshness need verification. Raw state codes are not interpreted as outages. Individual batteries are numbered for this snapshot; numbering may change if batteries change."
        )
    }
}
