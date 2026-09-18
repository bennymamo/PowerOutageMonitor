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
                title = "Keep Power Monitor Running",
                summary = "Detected device maker: $name. Android and the device maker can restrict apps that run for long periods.",
                steps = listOf(
                    "Allow the ongoing Power monitoring notification.",
                    "Open this app's system settings and avoid a Restricted battery mode.",
                    "If this device has Auto-start or Background activity controls, allow Flockle Grid Outage Monitor.",
                    "After changing device settings, reboot once and confirm Monitoring service is running in Diagnostics."
                )
            )
        }
    }
}
