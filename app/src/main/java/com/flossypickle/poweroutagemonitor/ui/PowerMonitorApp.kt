package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

private enum class AppScreen(val label: String) {
    STATUS("Status"),
    HISTORY("History"),
    SETTINGS("Settings"),
    DIAGNOSTICS("Diagnostics"),
    TEST_MODE("Test mode"),
    TELEGRAM("Telegram")
}

private val primaryScreens = listOf(AppScreen.STATUS, AppScreen.HISTORY, AppScreen.SETTINGS)

@Composable
internal fun PowerMonitorApp(
    snapshot: PowerSnapshot?,
    monitorState: OutageEngine.State,
    settings: MonitorStore.Settings,
    history: List<EventHistoryStore.Record>,
    lastObservationEpochMs: Long,
    onMonitoringEnabledChange: (Boolean) -> Unit,
    onSettingsChange: (Long, Long, Boolean, String) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.STATUS) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(52.dp)) {
                    primaryScreens.forEach { item ->
                        val selected = screen == item ||
                            item == AppScreen.SETTINGS && screen in listOf(
                                        AppScreen.DIAGNOSTICS,
                                        AppScreen.TEST_MODE,
                                        AppScreen.TELEGRAM
                            )
                        TextButton(onClick = { screen = item }, modifier = Modifier.weight(1f)) {
                            Text(
                                item.label,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        when (screen) {
            AppScreen.STATUS -> DashboardScreen(snapshot, monitorState, settings, lastObservationEpochMs, padding)
            AppScreen.HISTORY -> HistoryScreen(history, monitorState, padding)
            AppScreen.SETTINGS -> SettingsScreen(
                settings,
                padding,
                onMonitoringEnabledChange,
                onSettingsChange,
                onOpenDiagnostics = { screen = AppScreen.DIAGNOSTICS },
                onOpenTestMode = { screen = AppScreen.TEST_MODE },
                onOpenTelegram = { screen = AppScreen.TELEGRAM }
            )
            AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
                settings = settings,
                state = monitorState,
                snapshot = snapshot,
                lastObservationEpochMs = lastObservationEpochMs,
                padding = padding,
                onBack = { screen = AppScreen.SETTINGS }
            )
            AppScreen.TEST_MODE -> TestModeScreen(
                settings = settings,
                padding = padding,
                onBack = { screen = AppScreen.SETTINGS }
            )
            AppScreen.TELEGRAM -> TelegramSetupScreen(
                deviceName = settings.deviceName,
                padding = padding,
                onBack = { screen = AppScreen.SETTINGS }
            )
        }
    }
}
