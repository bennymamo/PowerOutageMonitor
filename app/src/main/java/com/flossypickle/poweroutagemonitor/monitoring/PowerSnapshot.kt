package com.flossypickle.poweroutagemonitor.monitoring

import android.content.Intent
import android.os.BatteryManager

internal data class PowerSnapshot(
    val plugged: Int,
    val batteryPercent: Int?,
    val batteryStatus: Int,
    val batteryTemperatureTenthsCelsius: Int?
) {
    val externallyPowered: Boolean? get() = when {
        plugged < 0 -> null
        plugged == 0 -> false
        else -> true
    }

    companion object {
        fun from(intent: Intent?): PowerSnapshot? {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return PowerSnapshot(
                plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
                batteryPercent = if (scale > 0 && level in 0..scale) {
                    (level.toLong() * 100 / scale).toInt()
                } else null,
                batteryStatus = intent.getIntExtra(
                    BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN
                ),
                batteryTemperatureTenthsCelsius = temperature.takeUnless { it == Int.MIN_VALUE }
            )
        }
    }
}
