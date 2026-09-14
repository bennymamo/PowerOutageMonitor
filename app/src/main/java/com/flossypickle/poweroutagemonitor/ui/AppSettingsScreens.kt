package com.flossypickle.poweroutagemonitor.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Appearance, guidance, reliability, local-data and app-information settings. */
@Composable
internal fun AppearanceSettingsContent(
    settings: MonitorStore.Settings,
    onThemeModeChange: (MonitorStore.ThemeMode) -> Unit
) {
    SettingsCard {
        Text("Theme", fontWeight = FontWeight.Medium)
        Text(
            "System follows the phone's light or dark appearance.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        THEME_OPTIONS.forEach { (mode, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { onThemeModeChange(mode) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = settings.themeMode == mode,
                    onClick = { onThemeModeChange(mode) }
                )
                Text(label)
            }
        }
    }
}

@Composable
internal fun HelpSettingsContent(
    settings: MonitorStore.Settings,
    onHelpLevelChange: (MonitorStore.HelpLevel) -> Unit
) {
    SettingsCard {
        Text("Setup instructions", fontWeight = FontWeight.Medium)
        Text(
            "This changes how much help setup pages show. It does not change monitoring or alert behavior.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        HELP_LEVEL_OPTIONS.forEach { (level, label, explanation) ->
            Row(
                Modifier.fillMaxWidth().clickable { onHelpLevelChange(level) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                RadioButton(
                    selected = settings.helpLevel == level,
                    onClick = { onHelpLevelChange(level) }
                )
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(label, fontWeight = FontWeight.Medium)
                    Text(
                        explanation,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReliabilitySettingsContent(onOpenDiagnostics: () -> Unit) {
    SettingsCard {
        SettingText("Restart after reboot", "Enabled with monitoring")
        SettingText(
            "Before first unlock",
            if (Build.VERSION.SDK_INT >= 24) "Supported" else "Unavailable"
        )
        SettingText("Outage state", "Saved after every reading")
        Text(
            "Some manufacturers can still stop background apps. Diagnostics shows current health and the system settings to check.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Open reliability diagnostics")
        }
    }
}

@Composable
internal fun HistorySettingsContent(
    settings: MonitorStore.Settings,
    onHistoryLimitChange: (Int) -> Unit,
    onClearHistory: () -> Unit
) {
    var confirmClearHistory by remember { mutableStateOf(false) }

    SettingsCard {
        Text("Keep recent power events", fontWeight = FontWeight.Medium)
        Text(
            "Older entries are removed automatically. Alert delivery records are managed separately in Diagnostics.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        HISTORY_LIMITS.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { onHistoryLimitChange(value) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = settings.historyLimit == value,
                    onClick = { onHistoryLimitChange(value) }
                )
                Text(label)
            }
        }
        if (!confirmClearHistory) {
            OutlinedButton(
                onClick = { confirmClearHistory = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear power history") }
        } else {
            Text(
                "This permanently removes local grid and app-operation history.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onClearHistory()
                    confirmClearHistory = false
                }) { Text("Clear") }
                OutlinedButton(onClick = { confirmClearHistory = false }) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
internal fun SafetyPrivacySettingsContent() {
    SettingsCard {
        Text(
            "Do not leave an old, swollen, hot or damaged lithium battery charging unattended.",
            fontWeight = FontWeight.Medium
        )
        Text(
            "The app has no analytics, advertising or trackers. Current monitoring stays on this device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
internal fun AboutSettingsContent() {
    val context = LocalContext.current
    var headingTaps by remember { mutableStateOf(0) }
    var versionTaps by remember { mutableStateOf(0) }
    var foundGrid by remember { mutableStateOf(false) }
    var foundEngineer by remember { mutableStateOf(false) }
    var foundStars by remember { mutableStateOf(false) }
    var starsVisible by remember { mutableStateOf(false) }
    var authorRevealed by remember { mutableStateOf(false) }

    LaunchedEffect(starsVisible) {
        if (starsVisible) {
            delay(6_000)
            starsVisible = false
        }
    }

    SettingsCard {
        Text(
            "FP Grid Monitor",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable {
                headingTaps++
                if (headingTaps >= 7) {
                    headingTaps = 0
                    foundStars = true
                    starsVisible = true
                }
            }
        )
        GridLogo(
            engineerVisible = foundEngineer,
            onLongPress = { foundEngineer = true }
        )
        Row(
            Modifier.fillMaxWidth().clickable {
                versionTaps++
                if (versionTaps >= 5) {
                    versionTaps = 0
                    foundGrid = true
                }
            },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("App version", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(appVersionName(context), fontWeight = FontWeight.Medium)
        }
        SettingText("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        SettingText("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        SettingText("Package", context.packageName)
        if (starsVisible) StarField()
        if (foundGrid) {
            GridWaveform(
                canRevealAuthor = foundGrid && foundEngineer && foundStars,
                onLongPress = { authorRevealed = true }
            )
        }
        if (foundEngineer) {
            Text(
                "🥒  Field inspection complete. All conductors look suitably crunchy.",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
        }
        if (authorRevealed) {
            Text(
                "Written with love, for free, by Bernard Mamo",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GridLogo(engineerVisible: Boolean, onLongPress: () -> Unit) {
    val line = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    Canvas(
        Modifier.fillMaxWidth().height(92.dp)
            .semantics { contentDescription = "FP Grid Monitor electricity-grid logo" }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
    ) {
        val center = size.width / 2f
        val top = size.height * .14f
        val bottom = size.height * .88f
        val halfBase = size.width.coerceAtMost(240f) * .28f
        val path = Path().apply {
            moveTo(center, top)
            lineTo(center - halfBase, bottom)
            moveTo(center, top)
            lineTo(center + halfBase, bottom)
            moveTo(center - halfBase * .52f, size.height * .48f)
            lineTo(center + halfBase * .52f, size.height * .48f)
            moveTo(center - halfBase * .72f, size.height * .68f)
            lineTo(center + halfBase * .72f, size.height * .68f)
            moveTo(center - halfBase * .38f, size.height * .48f)
            lineTo(center + halfBase * .65f, bottom)
            moveTo(center + halfBase * .38f, size.height * .48f)
            lineTo(center - halfBase * .65f, bottom)
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
        drawLine(line, Offset(0f, size.height * .34f), Offset(size.width, size.height * .34f), 3f)
        if (engineerVisible) {
            drawRoundRect(
                color = Color(0xFF73C96B),
                topLeft = Offset(center + halfBase + 18f, size.height * .57f),
                size = androidx.compose.ui.geometry.Size(30f, 52f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(15f, 15f)
            )
            drawLine(
                secondary,
                Offset(center + halfBase + 18f, size.height * .62f),
                Offset(center + halfBase + 48f, size.height * .62f),
                8f
            )
        }
    }
}

@Composable
private fun GridWaveform(canRevealAuthor: Boolean, onLongPress: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "grid-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val color = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().pointerInput(canRevealAuthor) {
            detectTapGestures(onLongPress = { if (canRevealAuthor) onLongPress() })
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
            val path = Path()
            for (x in 0..size.width.toInt()) {
                val angle = x / size.width * (4 * PI).toFloat() + phase
                val y = size.height / 2f + sin(angle.toDouble()).toFloat() * size.height * .32f
                if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
        }
        Text("50 Hz · The grid is humming", color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StarField() {
    Box(
        Modifier.fillMaxWidth().height(90.dp).background(
            Color(0xFF050B12),
            androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "✦       ·    ✧          ⚡       ·       ✦\n     ·          ✦       ·        ✧",
            color = Color(0xFFFFD580),
            fontSize = 18.sp
        )
    }
}

@Suppress("DEPRECATION")
private fun appVersionName(context: Context): String = try {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName ?: "Unknown"
} catch (_: PackageManager.NameNotFoundException) {
    "Unknown"
}

private val HISTORY_LIMITS = listOf(
    50 to "Last 50 events",
    100 to "Last 100 events",
    200 to "Last 200 events (recommended)"
)

private val THEME_OPTIONS = listOf(
    MonitorStore.ThemeMode.SYSTEM to "System default",
    MonitorStore.ThemeMode.DARK to "Dark",
    MonitorStore.ThemeMode.LIGHT to "Light"
)

private val HELP_LEVEL_OPTIONS = listOf(
    Triple(
        MonitorStore.HelpLevel.GUIDED,
        "Guided (recommended)",
        "Show numbered walkthroughs, plain explanations and direct setup links."
    ),
    Triple(
        MonitorStore.HelpLevel.EXPERIENCED,
        "Experienced",
        "Show concise technical notes and fewer setup hints."
    )
)
