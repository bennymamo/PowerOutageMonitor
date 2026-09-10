package com.flossypickle.poweroutagemonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkMonitorColors = darkColorScheme(
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

private val LightMonitorColors = lightColorScheme(
    primary = Color(0xFF176B45),
    onPrimary = Color.White,
    background = Color(0xFFF4F7F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2ECE7),
    onBackground = Color(0xFF14211A),
    onSurface = Color(0xFF14211A),
    onSurfaceVariant = Color(0xFF52645A),
    outline = Color(0xFFB7C8BF)
)

@Composable
fun PowerOutageMonitorTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkMonitorColors else LightMonitorColors,
        typography = Typography,
        content = content
    )
}
