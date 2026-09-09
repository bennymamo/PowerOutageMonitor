package com.flossypickle.poweroutagemonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MonitorColors = darkColorScheme(
    primary = Color(0xFFA8E6BD),
    onPrimary = Color(0xFF102A1C),
    background = Color(0xFF0C1419),
    surface = Color(0xFF152128),
    surfaceVariant = Color(0xFF203038),
    onBackground = Color(0xFFF1F5F3),
    onSurface = Color(0xFFF1F5F3),
    onSurfaceVariant = Color(0xFF9CACB1),
    outline = Color(0xFF31434B)
)

@Composable
fun PowerOutageMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MonitorColors, typography = Typography, content = content)
}
