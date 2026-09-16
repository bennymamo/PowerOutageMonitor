package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Shared setup advice for alert destinations and network-based power sources. */
@Composable
internal fun NetworkBackupGuidance() {
    SettingsCard {
        Text("Keep your network powered", fontWeight = FontWeight.SemiBold)
        Text(
            "Put your modem/router, every Wi-Fi access point this phone uses, and any HomePlug/powerline adapters or network switches along the connection on a UPS or battery backup. Otherwise an outage can cut the connection just when an alert is needed.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Text(
            "Telegram and email need internet; working mobile data can be an alternative. EcoFlow local readings need the home network, and EcoFlow Cloud also needs the inverter's internet connection. Device SMS needs a working SIM and mobile signal. Charger detection, local history and the audible alarm work offline.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Text(
            "Using the Android charger source? Keep that charger on the socket you want to monitor, outside the backup supply. A backed-up charger would hide grid loss. An EcoFlow source instead reads the inverter's grid state.",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp
        )
    }
}
