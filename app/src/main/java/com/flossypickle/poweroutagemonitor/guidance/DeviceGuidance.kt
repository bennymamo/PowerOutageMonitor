package com.flossypickle.poweroutagemonitor.guidance

import java.util.Locale

internal data class DeviceGuidance(
    val title: String,
    val summary: String,
    val steps: List<String>
) {
    companion object {
        fun forManufacturer(manufacturer: String): DeviceGuidance {
            val name = manufacturer.trim().ifEmpty { "Android" }
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            return DeviceGuidance(
                title = "$name background guidance",
                summary = "Android and the device maker can restrict apps that run for long periods. Check these items on the monitor device.",
                steps = listOf(
                    "Allow the ongoing Power monitoring notification.",
                    "Open Android battery optimization settings and avoid a Restricted battery mode for this app.",
                    "If the device has an Auto-start or Background activity control, allow this app.",
                    "After changing device settings, reboot once and confirm Monitoring service is running in Diagnostics."
                )
            )
        }
    }
}
