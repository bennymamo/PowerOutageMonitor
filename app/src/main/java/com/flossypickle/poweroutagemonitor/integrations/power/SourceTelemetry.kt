package com.flossypickle.poweroutagemonitor.integrations.power

/** Display-only device observations. These never drive the outage engine. */
internal data class SourceTelemetryReading(
    val key: String,
    val label: String,
    val value: String,
    val unit: String = ""
)

internal data class SourceTelemetrySection(
    val id: String,
    val title: String,
    val readings: List<SourceTelemetryReading>,
    val initiallyExpanded: Boolean = false
)

internal data class SourceTelemetrySnapshot(
    val sourceName: String,
    val deviceName: String,
    val receivedAtEpochMs: Long,
    val summary: List<SourceTelemetryReading>,
    val sections: List<SourceTelemetrySection>,
    val deviceReportedAtEpochMs: Long? = null,
    val omittedValues: Int = 0,
    val acquisitionNote: String? = null
)

/** Handles arbitrary nested provider data without leaking credentials or identifiers. */
internal object SourceTelemetryFlattener {
    data class Result(val values: Map<String, String>, val omittedValues: Int)

    fun flatten(data: Any?): Result {
        val values = sortedMapOf<String, String>()
        var omitted = 0
        fun visit(value: Any?, path: String, depth: Int) {
            if (sensitivePath(path) || depth > 12 || path.length > 240) {
                omitted++
                return
            }
            when (value) {
                null -> Unit
                is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.forEach { (key, child) ->
                    val name = key as? String ?: return@forEach
                    visit(child, if (path.isEmpty()) name else "$path.$name", depth + 1)
                }
                is List<*> -> value.forEachIndexed { index, child -> visit(child, "$path[$index]", depth + 1) }
                else -> {
                    if (path.isEmpty() || values.size >= 1_024) {
                        omitted++
                        return
                    }
                    val text = when (value) {
                        is Double -> value.takeIf(Double::isFinite)?.toString()
                        is Float -> value.takeIf(Float::isFinite)?.toString()
                        is Number, is Boolean, is String -> value.toString()
                        else -> null
                    } ?: return
                    if (text.length > 160 || sensitiveText(text)) {
                        omitted++
                        return
                    }
                    val existing = values[path]
                    values[path] = if (existing != null && existing != text) {
                        "Conflicting values — unavailable"
                    } else text
                }
            }
        }
        visit(data, "", 0)
        return Result(values, omitted)
    }

    private fun sensitivePath(path: String): Boolean = path.split('.', '[', ']')
        .map { it.lowercase().replace("_", "").replace("-", "") }
        .any { part ->
            part in setOf("sn", "devsn", "bpsn", "evsn", "hrsn", "modulesn", "serial", "serialnumber", "mac", "macaddr", "macaddress", "ip", "ipaddress",
                "ssid", "email", "username", "userid", "location", "address", "systemname", "latitude", "longitude", "tid", "eagleeyetraceid") ||
                listOf("password", "secret", "token", "accesskey", "authorization", "credential")
                    .any(part::contains)
        }

    private fun sensitiveText(text: String): Boolean =
        text.contains(Regex("(?i)(bearer\\s+|access[_-]?key[=:]|secret[_-]?key[=:]|password[=:])"))
}
