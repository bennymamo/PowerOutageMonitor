package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignal
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Implements EcoFlow's documented HMAC-SHA256 request signing. */
internal object EcoFlowCloudSigner {
    fun canonicalRequest(
        parameters: Map<String, String>,
        accessKey: String,
        nonce: String,
        timestampEpochMs: Long
    ): String = buildList {
        parameters.toSortedMap().forEach { (key, value) -> add("$key=$value") }
        add("accessKey=$accessKey")
        add("nonce=$nonce")
        add("timestamp=$timestampEpochMs")
    }.joinToString("&")

    fun sign(
        parameters: Map<String, String>,
        accessKey: String,
        secretKey: String,
        nonce: String,
        timestampEpochMs: Long
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(
            canonicalRequest(parameters, accessKey, nonce, timestampEpochMs)
                .toByteArray(Charsets.UTF_8)
        ).joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }
}

internal data class EcoFlowCloudQuota(
    val phaseVoltages: List<Double>,
    val gridPowerWatts: Double?,
    val loadPowerWatts: Double?,
    val solarPowerWatts: Double?,
    val batteryPowerWatts: Double?,
    val batteryPercent: Double?,
    val reportedValues: Map<String, String> = emptyMap(),
    val omittedValues: Int = 0,
    val requestedFields: Boolean = false
)

/** Matches the array expansion in EcoFlow's official Java signing example. */
internal object EcoFlowPowerOceanRequest {
    val fields = listOf("pcsAPhase", "pcsBPhase", "pcsCPhase", "mpptHeartBeat", "mpptPwr",
        "bpSoc", "bpPwr", "sysLoadPwr", "sysGridPwr")

    fun signingParameters(serial: String): Map<String, String> = buildMap {
        put("sn", serial)
        fields.forEachIndexed { index, field -> put("params.quotas[$index]", field) }
    }
}

internal object EcoFlowCloudError {
    fun describe(code: String?, message: String?, status: Int, privateValues: List<String>): String {
        var safe = message.orEmpty()
        privateValues.filter(String::isNotEmpty).sortedByDescending(String::length).forEach {
            safe = safe.replace(it, "[redacted]", ignoreCase = true)
        }
        safe = safe.replace(Regex("(?i)(bearer\\s+|(?:access[_-]?key|secret[_-]?key|password)\\s*[=:]\\s*)[^\\s,;]+"), "[redacted]")
            .replace(Regex("[\\p{Cntrl}]"), " ").take(240)
        val safeCode = code?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) && privateValues.none { private -> private.equals(it, true) } }
        val prefix = if (safeCode == null) "EcoFlow HTTP $status" else "EcoFlow code $safeCode"
        return if (safe.isBlank()) prefix else "$prefix: $safe"
    }
}

/**
 * Conservative PowerOcean cloud mapping.
 *
 * Grid flow alone is not proof: a healthy grid can exchange zero watts. Phase
 * voltage is the only value currently allowed to decide availability.
 */
internal object EcoFlowCloudGridSignalMapper {
    const val PROVIDER_ID = "ecoflow_cloud"
    private const val GRID_PRESENT_MINIMUM_VOLTS = 50.0
    private const val MAX_PLAUSIBLE_PHASE_VOLTS = 300.0

    fun toSignal(quota: EcoFlowCloudQuota, observedAtEpochMs: Long): PowerSignal {
        val validVoltages = quota.phaseVoltages.filter { it.isFinite() && it in 0.0..MAX_PLAUSIBLE_PHASE_VOLTS }
        val availability = when {
            validVoltages.isEmpty() -> GridAvailability.UNKNOWN
            validVoltages.any { it > GRID_PRESENT_MINIMUM_VOLTS } -> GridAvailability.AVAILABLE
            validVoltages.size == quota.phaseVoltages.size -> GridAvailability.UNAVAILABLE
            else -> GridAvailability.UNKNOWN
        }
        val phases = validVoltages.joinToString(" / ") { String.format(Locale.ROOT, "%.1f V", it) }
        val flow = quota.gridPowerWatts?.let {
            " · grid ${String.format(Locale.ROOT, "%.0f W", it)}"
        }.orEmpty()
        return PowerSignal(
            availability = availability,
            observedAtEpochMs = observedAtEpochMs,
            providerId = PROVIDER_ID,
            detail = if (phases.isEmpty()) "No documented phase-voltage reading" else "$phases$flow"
        )
    }
}
