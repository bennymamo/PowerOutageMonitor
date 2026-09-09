package com.flossypickle.poweroutagemonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flossypickle.poweroutagemonitor.ui.theme.PowerOutageMonitorTheme

class MainActivity : ComponentActivity() {
    private var battery by mutableStateOf(BatteryReading())
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                battery = BatteryReading.from(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            PowerOutageMonitorTheme {
                Dashboard(battery)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // This protected system broadcast also supplies the current sticky snapshot.
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { battery = BatteryReading.from(it) }
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }
}

internal data class BatteryReading(
    val plugged: Int = -1,
    val percent: Int? = null,
    val status: Int = BatteryManager.BATTERY_STATUS_UNKNOWN
) {
    val powerText: String get() = when {
        plugged < 0 -> "Power status unavailable"
        plugged == 0 -> "Running on battery"
        else -> "External power connected"
    }
    val sourceText: String get() = when (plugged) {
        0 -> "None"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
        -1 -> "Unknown"
        else -> "Other external source"
    }
    val chargingText: String get() = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }
    companion object {
        fun from(intent: Intent): BatteryReading {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return BatteryReading(
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
                if (scale > 0 && level in 0..scale) (level.toLong() * 100 / scale).toInt() else null,
                intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            )
        }
    }
}

@Composable
private fun Dashboard(battery: BatteryReading) {
    val colors = MaterialTheme.colorScheme
    val accent = when {
        battery.plugged > 0 -> colors.primary
        battery.plugged == 0 -> Color(0xFFF0C580)
        else -> colors.onSurfaceVariant
    }
    Scaffold(containerColor = colors.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FLOSSY PICKLE", color = colors.primary, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("Power monitor", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("A little peace of mind.", color = colors.onSurfaceVariant)
                }
                Card(shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).background(accent, CircleShape))
                            Text(when {
                                battery.plugged > 0 -> "EXTERNAL POWER"
                                battery.plugged == 0 -> "ON BATTERY"
                                else -> "AWAITING READING"
                            }, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp)
                        }
                        Box(Modifier.size(208.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                                val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                                drawArc(colors.surfaceVariant, 135f, 270f, false, style = stroke)
                                battery.percent?.let {
                                    drawArc(accent, 135f, 270f * it / 100f, false, style = stroke)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(battery.percent?.let { "$it%" } ?: "—",
                                    fontSize = 48.sp, fontWeight = FontWeight.Light)
                                Text("BATTERY", color = colors.onSurfaceVariant, fontSize = 11.sp,
                                    letterSpacing = 2.sp)
                            }
                        }
                        Text(battery.powerText, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium)
                        Text(if (battery.plugged > 0) "Your device has external power."
                            else if (battery.plugged == 0) "Your device is using its battery."
                            else "Waiting for Android’s power reading.",
                            color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReadingTile("POWER SOURCE", battery.sourceText, Modifier.weight(1f))
                    ReadingTile("BATTERY STATE", battery.chargingText, Modifier.weight(1f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Monitoring", fontWeight = FontWeight.Medium)
                        Text("Preview", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Text("Live readings while this screen is open. Background monitoring and alerts are coming next.",
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = colors.outline)
                Text("TRY IT OUT", color = colors.primary, fontSize = 11.sp,
                    letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("Unplug your charger, then reconnect it. The power indicator above will follow the change.",
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ReadingTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, fontSize = 10.sp, letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
