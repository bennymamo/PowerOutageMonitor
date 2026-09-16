package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetryReading
import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetrySection
import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetrySnapshot
import java.util.Locale

/** Field names are from EcoFlow's PowerOcean GetAllQuotaResponse documentation. */
internal object EcoFlowTelemetryMapper {
    private data class Field(val label: String, val unit: String, val group: String)
    private val totals = mapOf(
        "sysGridPwr" to Field("Grid power", "W", "grid"),
        "sysLoadPwr" to Field("Home load", "W", "home"),
        "mpptPwr" to Field("Solar power", "W", "solar"),
        "bpPwr" to Field("Battery power", "W", "battery"),
        "bpSoc" to Field("Battery reserve", "%", "battery"),
        "evPwr" to Field("EV charger power", "W", "ev"),
        "chargingStatus" to Field("EV charger status", "", "ev"),
        "errorCode" to Field("Reported error code", "", "health")
    )
    private val groupTitles = linkedMapOf(
        "grid" to "⚡ Grid & phases", "solar" to "☀ Solar & strings",
        "battery" to "▰ Battery", "home" to "⌂ Home load",
        "ev" to "EV charging", "heating" to "Heating & hot water",
        "health" to "Device health", "technical" to "Additional reported fields"
    )

    fun snapshot(quota: EcoFlowCloudQuota, deviceName: String, receivedAtEpochMs: Long): SourceTelemetrySnapshot {
        val groups = linkedMapOf<String, MutableList<SourceTelemetryReading>>()
        val summary = mutableListOf<SourceTelemetryReading>()
        quota.reportedValues.forEach { (key, value) ->
            val field = describe(key)
            val reading = SourceTelemetryReading(
                key, field?.label ?: key, format(value, field?.unit.orEmpty()), field?.unit.orEmpty()
            )
            groups.getOrPut(field?.group ?: "technical") { mutableListOf() }.add(reading)
            if (key in listOf("sysGridPwr", "sysLoadPwr", "mpptPwr", "bpPwr", "bpSoc")) summary.add(reading)
        }
        val summaryOrder = listOf("sysGridPwr", "sysLoadPwr", "mpptPwr", "bpPwr", "bpSoc")
        return SourceTelemetrySnapshot(
            sourceName = "EcoFlow Cloud",
            deviceName = deviceName,
            receivedAtEpochMs = receivedAtEpochMs,
            summary = summary.sortedBy { summaryOrder.indexOf(it.key) },
            sections = groupTitles.mapNotNull { (id, title) ->
                groups[id]?.let { SourceTelemetrySection(id, title, it, initiallyExpanded = id == "grid") }
            },
            omittedValues = quota.omittedValues,
            acquisitionNote = if (quota.requestedFields) "Requested PowerOcean grid, solar, battery and home readings. Other equipment fields are not included in this request."
                else "All displayable fields returned by the device request."
        )
    }

    private fun describe(key: String): Field? {
        totals[key.trim()]?.let { return it }
        val phase = Regex("pcs([ABC])Phase\\.(vol|amp|actPwr|reactPwr|apparentPwr)").matchEntire(key)
        if (phase != null) {
            val name = "Phase ${phase.groupValues[1]}"
            return when (phase.groupValues[2]) {
                "vol" -> Field("$name voltage", "V", "grid")
                "amp" -> Field("$name current", "A", "grid")
                "actPwr" -> Field("$name active power", "W", "grid")
                "reactPwr" -> Field("$name reactive power", "var", "grid")
                else -> Field("$name apparent power", "VA", "grid")
            }
        }
        val pv = Regex("mpptHeartBeat(?:\\[([0-9]+)])?\\.mpptPv\\[([0-9]+)]\\.(vol|amp|pwr)").matchEntire(key)
        if (pv != null) {
            val tracker = (pv.groupValues[1].toIntOrNull() ?: 0) + 1
            val index = pv.groupValues[2].toIntOrNull()?.takeIf { it < Int.MAX_VALUE } ?: return null
            val string = index + 1
            val name = "Tracker $tracker · string $string"
            return when (pv.groupValues[3]) {
                "vol" -> Field("$name voltage", "V", "solar")
                "amp" -> Field("$name current", "A", "solar")
                else -> Field("$name power", "W", "solar")
            }
        }
        return when (key) {
            "sectorA.tempCurr" -> Field("Zone A temperature", "°C", "heating")
            "sectorB.tempCurr" -> Field("Zone B temperature", "°C", "heating")
            "sectorDhw.tempCurr" -> Field("Hot water temperature", "°C", "heating")
            "hpMaster.tempInlet" -> Field("Heating inlet", "°C", "heating")
            "hpMaster.tempOutlet" -> Field("Heating outlet", "°C", "heating")
            "hpMaster.tempAmbient" -> Field("Ambient temperature", "°C", "heating")
            else -> when {
                key.startsWith("emsErrCode.errCode[") -> Field("Reported error ${key.substringAfter('[').substringBefore(']')}", "", "health")
                Regex("hrEnergyStream\\[[0-9]+]\\.temp").matches(key) -> Field("Heating rod water temperature", "°C", "heating")
                Regex("hrEnergyStream\\[[0-9]+]\\.hrPwr").matches(key) -> Field("Heating rod power", "W", "heating")
                else -> null
            }
        }
    }

    private fun format(value: String, unit: String): String {
        if (unit.isEmpty()) return value
        val number = value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return value
        if (unit == "%" && number !in 0.0..100.0 || unit == "°C" && number !in -100.0..200.0) {
            return "Not valid ($value)"
        }
        return String.format(Locale.ROOT, if (unit in setOf("W", "VA", "var", "%")) "%.0f" else "%.1f", number)
    }
}
