package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.configuration.BackupCategory
import com.flossypickle.poweroutagemonitor.configuration.BackupDocument

@Composable
internal fun SetupWizardScreen(
    settings: MonitorStore.Settings,
    snapshot: PowerSnapshot?,
    onComplete: (String, Long, Long, MonitorStore.HelpLevel) -> Unit,
    onRestore: (BackupDocument, Set<BackupCategory>, Boolean) -> String?
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var showRestore by rememberSaveable { mutableStateOf(false) }
    var deviceName by rememberSaveable { mutableStateOf(settings.deviceName) }
    var outageDelay by rememberSaveable { mutableLongStateOf(settings.outageDelayMs) }
    var restoreDelay by rememberSaveable { mutableLongStateOf(settings.restoreDelayMs) }
    var helpLevel by rememberSaveable { mutableStateOf(settings.helpLevel) }
    var sawConnected by rememberSaveable { mutableStateOf(false) }
    var sawDisconnected by rememberSaveable { mutableStateOf(false) }
    var sawReconnected by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(snapshot?.externallyPowered) {
        when {
            snapshot?.externallyPowered == true && sawDisconnected -> sawReconnected = true
            snapshot?.externallyPowered == true -> sawConnected = true
            snapshot?.externallyPowered == false && sawConnected -> sawDisconnected = true
        }
    }
    val powerTestComplete = sawConnected && sawDisconnected && sawReconnected

    BackHandler(enabled = showRestore || step > 0) {
        if (showRestore) showRestore = false else step--
    }
    if (showRestore) {
        SetupRestoreScreen(
            settings = settings,
            onBack = { showRestore = false },
            onRestore = onRestore
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("FLOSSY PICKLE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("SETUP ${step + 1} OF ${SETUP_STEPS.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                letterSpacing = 1.5.sp)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                when (step) {
                    0 -> WelcomeStep(helpLevel, onHelpLevelChange = { helpLevel = it })
                    1 -> SafetyStep(helpLevel)
                    2 -> DeviceStep(
                        deviceName = deviceName,
                        outageDelay = outageDelay,
                        restoreDelay = restoreDelay,
                        onDeviceNameChange = { if (it.length <= 50) deviceName = it },
                        onOutageDelayChange = { outageDelay = it },
                        onRestoreDelayChange = { restoreDelay = it }
                    )
                    3 -> PowerDetectionStep(
                        snapshot = snapshot,
                        sawConnected = sawConnected,
                        sawDisconnected = sawDisconnected,
                        sawReconnected = sawReconnected
                    )
                    else -> ReadyStep(helpLevel, deviceName, outageDelay, restoreDelay,
                        powerTestComplete)
                }
            }
            if (step == 0) {
                OutlinedButton(
                    onClick = { showRestore = true },
                    modifier = Modifier
                ) { Text("Restore an existing backup") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }) {
                        Text("Back")
                    }
                }
                Button(
                    onClick = {
                        if (step < SETUP_STEPS.lastIndex) step++
                        else onComplete(deviceName, outageDelay, restoreDelay, helpLevel)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = step != 2 || deviceName.isNotBlank()
                ) {
                    Text(when {
                        step == SETUP_STEPS.lastIndex -> "Start monitoring"
                        step == POWER_TEST_STEP && !powerTestComplete -> "Skip for now"
                        else -> "Continue"
                    })
                }
            }
            }
        }
    }
}

@Composable
private fun SetupRestoreScreen(
    settings: MonitorStore.Settings,
    onBack: () -> Unit,
    onRestore: (BackupDocument, Set<BackupCategory>, Boolean) -> String?
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            TextButton(onClick = onBack) { Text("‹ Setup") }
            Text("Restore your monitor", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Have a .fpgrid backup? Unlock it here before setting up this phone again. " +
                    "Select App settings to recover your completed setup. Monitoring stays off " +
                    "unless you explicitly choose to resume it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DataBackupSettingsContent(
                settings = settings,
                onRestore = onRestore,
                panel = BackupPanel.RESTORE
            )
        }
    }
}

@Composable
private fun PowerDetectionStep(
    snapshot: PowerSnapshot?,
    sawConnected: Boolean,
    sawDisconnected: Boolean,
    sawReconnected: Boolean
) {
    val instruction = when {
        !sawConnected -> "Connect the permanent charger."
        !sawDisconnected -> "Now unplug the charger."
        !sawReconnected -> "Reconnect the charger."
        else -> "Power detection is working on this device."
    }
    WizardHeading(
        "Test power detection",
        "This checks Android's real external-power signal. It does not create an outage or send an alert."
    )
    WizardCard {
        Text(
            if (snapshot?.externallyPowered == true) "EXTERNAL POWER CONNECTED"
            else if (snapshot?.externallyPowered == false) "RUNNING ON BATTERY"
            else "WAITING FOR ANDROID",
            color = if (snapshot?.externallyPowered == true) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(instruction, style = MaterialTheme.typography.titleMedium)
        snapshot?.batteryPercent?.let { percent ->
            Text("Battery $percent%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    WizardCard {
        TestCheck("Charger connected", sawConnected)
        TestCheck("Charger disconnected", sawDisconnected)
        TestCheck("Charger reconnected", sawReconnected)
    }
    if (!sawReconnected) {
        Text(
            "If you cannot unplug this device now, choose Skip for now. You can repeat the same check from the Status screen later.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun TestCheck(label: String, complete: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Text(
            if (complete) "DONE" else "WAITING",
            color = if (complete) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun WelcomeStep(
    helpLevel: MonitorStore.HelpLevel,
    onHelpLevelChange: (MonitorStore.HelpLevel) -> Unit
) {
    WizardHeading("Turn a spare phone into a power monitor",
        "Keep this device connected to its permanent charger. The app watches Android's external-power signal and confirms an outage only after your chosen delay.")
    WizardCard {
        Text("How it works", fontWeight = FontWeight.SemiBold)
        Text("1. Connect the charger once to arm monitoring.")
        Text("2. A sustained charger disconnection confirms an outage.")
        Text("3. Enabled alert channels notify your chosen recipients.")
        Text("Charging can stop at 100% while external power is still connected. The app treats those as different signals.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
    WizardCard {
        Text("How much setup help would you like?", fontWeight = FontWeight.SemiBold)
        SetupHelpOption(
            selected = helpLevel == MonitorStore.HelpLevel.GUIDED,
            title = "Guided (recommended)",
            explanation = "Show step-by-step walkthroughs and explain where to find things online.",
            onClick = { onHelpLevelChange(MonitorStore.HelpLevel.GUIDED) }
        )
        SetupHelpOption(
            selected = helpLevel == MonitorStore.HelpLevel.EXPERIENCED,
            title = "Experienced",
            explanation = "Use shorter technical instructions and fewer hints.",
            onClick = { onHelpLevelChange(MonitorStore.HelpLevel.EXPERIENCED) }
        )
        Text(
            "You can change this later under Settings › Help & guidance.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SafetyStep(helpLevel: MonitorStore.HelpLevel) {
    WizardHeading("Check the phone before leaving it plugged in",
        "A spare phone may run unattended for long periods, so its battery condition matters.")
    WizardCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Text("Battery safety", fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer)
        Text("Do not use a phone with a swollen, damaged, unusually hot or failing battery. Keep it on a hard, ventilated surface and use a sound charger and cable.",
            color = MaterialTheme.colorScheme.onErrorContainer)
    }
    WizardCard {
        Text("Monitoring uses a quiet ongoing notification so Android and the user can see that the service is alive.")
        if (helpLevel == MonitorStore.HelpLevel.GUIDED) {
            Text("Some manufacturers also require battery-optimization or auto-start changes. Diagnostics explains each check and provides the relevant system shortcut after setup.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } else {
            Text("Check battery optimization and vendor auto-start controls in Diagnostics.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
    NetworkBackupGuidance()
}

@Composable
private fun SetupHelpOption(
    selected: Boolean,
    title: String,
    explanation: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                explanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DeviceStep(
    deviceName: String,
    outageDelay: Long,
    restoreDelay: Long,
    onDeviceNameChange: (String) -> Unit,
    onOutageDelayChange: (Long) -> Unit,
    onRestoreDelayChange: (Long) -> Unit
) {
    WizardHeading("Name this monitor and choose stable delays",
        "The defaults favor reliability and filter brief charger or grid interruptions.")
    WizardCard {
        OutlinedTextField(
            value = deviceName,
            onValueChange = onDeviceNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device name") },
            singleLine = true
        )
        Text("Confirm outage after", fontWeight = FontWeight.Medium)
        WizardDelayOptions(OUTAGE_SETUP_DELAYS, outageDelay, onOutageDelayChange)
        Text("Confirm restoration after", fontWeight = FontWeight.Medium)
        WizardDelayOptions(RESTORE_SETUP_DELAYS, restoreDelay, onRestoreDelayChange)
    }
}

@Composable
private fun ReadyStep(
    helpLevel: MonitorStore.HelpLevel,
    deviceName: String,
    outageDelay: Long,
    restoreDelay: Long,
    powerTestComplete: Boolean
) {
    WizardHeading("Ready to start monitoring",
        "Android may ask for notification permission. Allow it so monitoring remains visible and reliable in the background.")
    WizardCard {
        SummaryRow("Device", deviceName.trim())
        SummaryRow("Outage delay", setupDelayLabel(outageDelay))
        SummaryRow("Restore delay", setupDelayLabel(restoreDelay))
        SummaryRow("Restoration alerts", "On")
        SummaryRow("Power detection", if (powerTestComplete) "Verified" else "Test later")
    }
    WizardCard {
        Text("After setup", fontWeight = FontWeight.SemiBold)
        Text(if (helpLevel.isGuided) {
            "The setup checklist opens next. Connect the permanent charger, choose an alert destination, send a test message, and check Android's background settings. Monitoring can detect power now, but it cannot send an alert until a destination is working."
        } else {
            "Connect the permanent charger and confirm Status shows external power. Configure Telegram, Gmail or device SMS under Settings › Alert channels, then send a test message."
        },
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Every option can be changed later in Settings.",
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WizardHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WizardCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}

@Composable
private fun WizardDelayOptions(
    options: List<Pair<Long, String>>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    options.forEach { (value, label) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected == value, onClick = { onSelect(value) })
            Text(label)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun setupDelayLabel(value: Long): String =
    (OUTAGE_SETUP_DELAYS + RESTORE_SETUP_DELAYS).firstOrNull { it.first == value }?.second
        ?: "${value / 1_000} seconds"

private const val POWER_TEST_STEP = 3
private val SETUP_STEPS = listOf("Welcome", "Safety", "Device", "Power test", "Ready")
private val OUTAGE_SETUP_DELAYS = listOf(
    30_000L to "30 seconds",
    60_000L to "1 minute (recommended)",
    120_000L to "2 minutes"
)
private val RESTORE_SETUP_DELAYS = listOf(
    10_000L to "10 seconds",
    30_000L to "30 seconds (recommended)",
    60_000L to "1 minute"
)
