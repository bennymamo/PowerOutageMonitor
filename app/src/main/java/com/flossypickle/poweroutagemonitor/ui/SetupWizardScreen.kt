package com.flossypickle.poweroutagemonitor.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

@Composable
internal fun SetupWizardScreen(
    settings: MonitorStore.Settings,
    onComplete: (String, Long, Long) -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var deviceName by rememberSaveable { mutableStateOf(settings.deviceName) }
    var outageDelay by rememberSaveable { mutableLongStateOf(settings.outageDelayMs) }
    var restoreDelay by rememberSaveable { mutableLongStateOf(settings.restoreDelayMs) }

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
                    0 -> WelcomeStep()
                    1 -> SafetyStep()
                    2 -> DeviceStep(
                        deviceName = deviceName,
                        outageDelay = outageDelay,
                        restoreDelay = restoreDelay,
                        onDeviceNameChange = { if (it.length <= 50) deviceName = it },
                        onOutageDelayChange = { outageDelay = it },
                        onRestoreDelayChange = { restoreDelay = it }
                    )
                    else -> ReadyStep(deviceName, outageDelay, restoreDelay)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) {
                        Text("Back")
                    }
                }
                Button(
                    onClick = {
                        if (step < SETUP_STEPS.lastIndex) step++
                        else onComplete(deviceName, outageDelay, restoreDelay)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = step != 2 || deviceName.isNotBlank()
                ) { Text(if (step == SETUP_STEPS.lastIndex) "Start monitoring" else "Continue") }
            }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
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
}

@Composable
private fun SafetyStep() {
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
        Text("Some manufacturers also require battery-optimization or auto-start changes. Diagnostics provides the relevant system shortcut after setup.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
private fun ReadyStep(deviceName: String, outageDelay: Long, restoreDelay: Long) {
    WizardHeading("Ready to start monitoring",
        "Android may ask for notification permission. Allow it so monitoring remains visible and reliable in the background.")
    WizardCard {
        SummaryRow("Device", deviceName.trim())
        SummaryRow("Outage delay", setupDelayLabel(outageDelay))
        SummaryRow("Restore delay", setupDelayLabel(restoreDelay))
        SummaryRow("Restoration alerts", "On")
    }
    WizardCard {
        Text("After setup", fontWeight = FontWeight.SemiBold)
        Text("Connect the permanent charger and confirm Status shows external power. Configure Telegram under Settings › Alert channels, then send a test message.",
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
    Card(shape = RoundedCornerShape(22.dp),
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

private val SETUP_STEPS = listOf("Welcome", "Safety", "Device", "Ready")
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
