package com.flossypickle.poweroutagemonitor.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudClient
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudConfigStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudGridSignalMapper
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudQuota
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun EcoFlowCloudSetupScreen(
    helpLevel: MonitorStore.HelpLevel,
    padding: PaddingValues,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { EcoFlowCloudConfigStore(context) }
    val client = remember { EcoFlowCloudClient() }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(store.config()) }
    var accessKey by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf(emptyList<EcoFlowCloudClient.Device>()) }
    var selectedQuota by remember { mutableStateOf<EcoFlowCloudQuota?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun enteredCredentials(): EcoFlowCloudClient.Credentials? {
        val enteredAccess = accessKey.trim()
        val enteredSecret = secretKey.trim()
        return if (enteredAccess.isEmpty() && enteredSecret.isEmpty()) {
            store.credentials()
        } else {
            EcoFlowCloudClient.Credentials(enteredAccess, enteredSecret).takeIf { it.isValid }
        }
    }

    fun runOperation(block: suspend () -> Unit) {
        if (loading) return
        scope.launch {
            loading = true
            feedback = null
            try {
                block()
            } finally {
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Power sources") }
        Text(
            "EcoFlow Cloud",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        SetupGuidanceCaption(helpLevel)
        Text(
            "Connects directly to EcoFlow's documented developer service. It needs internet, but it does not need Modbus, port 502 or installer access.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        PowerSourceSectionTitle("What this version does")
        SettingsCard {
            Text("Read-only connection preview", fontWeight = FontWeight.Medium)
            Text(
                "It can find devices on your EcoFlow account and inspect documented PowerOcean phase voltage, grid flow, home load, solar and battery readings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                "Cloud monitoring cannot be activated yet. First we must prove that the selected PowerOcean sends fresh phase voltage while the EcoFlow app and web portal are closed. This prevents cached data from causing a false outage.",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        PowerSourceSectionTitle("Developer access")
        SettingsCard {
            if (helpLevel.isGuided) {
                Text("1. Tap Open EcoFlow Developer below.")
                Text("2. Tap the person icon at the top-right. Choose Log in if you already have an EcoFlow account, or Create EcoFlow Account if you do not.")
                Text("3. Choose Become a Developer and complete the developer registration if EcoFlow asks for it.")
                Text("4. In the developer console, create an application for your personal home-monitoring use.")
                Text("5. Open that application's credentials and copy its Access Key and Secret Key.")
                Text("6. Return here, paste both keys, and tap Save credentials.")
                Text("7. Tap Find my EcoFlow devices. Then inspect the PowerOcean entry.")
            } else {
                Text("Create Developer API credentials, then save and test them here.")
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, DEVELOPER_URL.toUri()))
                    }.onFailure { feedback = "No browser is available to open EcoFlow Developer." }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open EcoFlow Developer") }
            OutlinedTextField(
                value = accessKey,
                onValueChange = { if (it.length <= 200) accessKey = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (config.hasCredentials) "New Access Key (leave blank to keep saved)" else "Access Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !loading
            )
            OutlinedTextField(
                value = secretKey,
                onValueChange = { if (it.length <= 300) secretKey = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (config.hasCredentials) "New Secret Key (leave blank to keep saved)" else "Secret Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !loading
            )
            if (config.hasCredentials) {
                Text(
                    "Credentials are stored with Android Keystore encryption.",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
            }
            Text(
                "These are API credentials from the developer console, not your Wi-Fi password and not your normal EcoFlow account password. FP Grid Monitor never needs your EcoFlow account password for this connection.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = {
                    val credentials = EcoFlowCloudClient.Credentials(accessKey.trim(), secretKey.trim())
                    if (!credentials.isValid) {
                        feedback = "Enter both the Access Key and Secret Key from EcoFlow Developer."
                    } else {
                        store.saveCredentials(credentials)
                        config = store.config()
                        accessKey = ""
                        secretKey = ""
                        devices = emptyList()
                        selectedQuota = null
                        feedback = "EcoFlow credentials saved securely."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && accessKey.isNotBlank() && secretKey.isNotBlank()
            ) { Text("Save credentials") }
            Button(
                onClick = {
                    val credentials = enteredCredentials()
                    if (credentials == null) {
                        feedback = "Save valid EcoFlow credentials first."
                        return@Button
                    }
                    runOperation {
                        when (val result = withContext(Dispatchers.IO) { client.listDevices(credentials) }) {
                            is EcoFlowCloudClient.Result.Success -> {
                                devices = result.value
                                feedback = if (devices.isEmpty()) {
                                    "Connected, but EcoFlow returned no owned devices. Shared devices are not included by this API."
                                } else "Connected. Found ${devices.size} EcoFlow device${if (devices.size == 1) "" else "s"}."
                            }
                            is EcoFlowCloudClient.Result.Failure -> feedback = result.message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && (config.hasCredentials || accessKey.isNotBlank())
            ) {
                if (loading) CircularProgressIndicator(strokeWidth = 2.dp)
                else Text("Find my EcoFlow devices")
            }
        }

        if (devices.isNotEmpty()) {
            PowerSourceSectionTitle("Devices")
            devices.forEach { device ->
                SettingsCard {
                    Text(device.name, fontWeight = FontWeight.Medium)
                    SettingText("Cloud status", if (device.online) "Online" else "Offline")
                    Text(
                        "Serial ${maskSerial(device.serialNumber)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            val credentials = enteredCredentials()
                            if (credentials == null) {
                                feedback = "Saved EcoFlow credentials could not be read."
                                return@Button
                            }
                            runOperation {
                                when (val result = withContext(Dispatchers.IO) {
                                    client.readPowerOceanQuota(credentials, device.serialNumber)
                                }) {
                                    is EcoFlowCloudClient.Result.Success -> {
                                        selectedQuota = result.value
                                        store.selectDevice(device)
                                        config = store.config()
                                        val signal = EcoFlowCloudGridSignalMapper.toSignal(
                                            result.value,
                                            System.currentTimeMillis()
                                        )
                                        feedback = when (signal.availability) {
                                            GridAvailability.AVAILABLE -> "Fresh request completed: phase voltage currently indicates grid available."
                                            GridAvailability.UNAVAILABLE -> "Fresh request completed: all reported phase voltages are below 50 V."
                                            GridAvailability.UNKNOWN -> "Connected, but this response has no usable phase voltage."
                                        }
                                    }
                                    is EcoFlowCloudClient.Result.Failure -> feedback = result.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    ) { Text("Inspect read-only data") }
                }
            }
        }

        selectedQuota?.let { quota ->
            PowerSourceSectionTitle("Latest inspection")
            SettingsCard {
                SettingText(
                    "Phase voltage",
                    quota.phaseVoltages.joinToString(" / ") { formatValue(it, "V") }.ifEmpty { "Not reported" }
                )
                SettingText("Grid flow", formatOptional(quota.gridPowerWatts, "W"))
                SettingText("Home load", formatOptional(quota.loadPowerWatts, "W"))
                SettingText("Solar", formatOptional(quota.solarPowerWatts, "W"))
                SettingText("Battery power", formatOptional(quota.batteryPowerWatts, "W"))
                SettingText("EcoFlow battery", formatOptional(quota.batteryPercent, "%"))
            }
        }

        feedback?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }

        PowerSourceSectionTitle("If something does not work")
        SettingsCard {
            Text("No devices found", fontWeight = FontWeight.Medium)
            Text(
                "Check that the developer account is the owner of the PowerOcean. EcoFlow's documented device-list request does not return devices that were only shared with the account.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text("Credentials rejected", fontWeight = FontWeight.Medium)
            Text(
                "Copy the Access Key and Secret Key again from the same application. Do not add spaces before or after either key.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text("Connected, but no phase voltage", fontWeight = FontWeight.Medium)
            Text(
                "The account works, but EcoFlow did not expose the readings needed for safe outage detection on that device. The app will keep the result Unknown.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        if (config.hasCredentials) {
            PowerSourceSectionTitle("Remove access")
            SettingsCard {
                if (!confirmClear) {
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Remove EcoFlow Cloud credentials") }
                } else {
                    Text("This removes the encrypted keys and selected device from this phone.")
                    Button(
                        onClick = {
                            store.clear()
                            config = store.config()
                            devices = emptyList()
                            selectedQuota = null
                            confirmClear = false
                            feedback = "EcoFlow Cloud credentials removed."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Remove credentials") }
                    OutlinedButton(
                        onClick = { confirmClear = false },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

private fun maskSerial(serial: String): String = when {
    serial.length <= 6 -> "••••"
    else -> serial.take(4) + "••••" + serial.takeLast(2)
}

private fun formatOptional(value: Double?, unit: String): String =
    value?.let { formatValue(it, unit) } ?: "Not reported"

private fun formatValue(value: Double, unit: String): String =
    String.format(Locale.ROOT, "%.1f %s", value, unit)

private const val DEVELOPER_URL = "https://developer-eu.ecoflow.com/"
