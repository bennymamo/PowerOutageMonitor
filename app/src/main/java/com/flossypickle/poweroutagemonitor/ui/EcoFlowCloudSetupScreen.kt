package com.flossypickle.poweroutagemonitor.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
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
import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetrySnapshot
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudClient
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudConfigStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudQuota
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowTelemetryMapper
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var dashboard by remember { mutableStateOf<SourceTelemetrySnapshot?>(null) }
    var dashboardOpen by remember { mutableStateOf(false) }
    var dashboardError by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val setupSteps = listOf("Prepare developer access", "Save API keys", "Find and test devices")
    var setupStep by rememberSaveable { mutableStateOf(if (config.hasCredentials) 2 else 0) }
    val setupScroll = rememberScrollState()
    LaunchedEffect(setupStep) { setupScroll.scrollTo(0) }
    var liveBrokerSummary by remember { mutableStateOf<String?>(null) }

    fun enteredCredentials(): EcoFlowCloudClient.Credentials? {
        // Device selection must belong to the saved key pair, including after restart.
        return store.credentials().takeIf { accessKey.isBlank() && secretKey.isBlank() }
    }

    fun runOperation(block: suspend () -> Unit) {
        if (loading) return
        loading = true
        scope.launch {
            feedback = null
            dashboardError = null
            try {
                block()
            } finally {
                loading = false
            }
        }
    }

    fun inspectDevice(device: EcoFlowCloudClient.Device, requestedFields: Boolean = false) {
        val openingDashboard = !dashboardOpen
        val credentials = enteredCredentials()
        if (credentials == null) {
            feedback = "Save valid EcoFlow credentials first."
            return
        }
        runOperation {
            dashboardError = null
            when (val result = withContext(Dispatchers.IO) {
                client.readPowerOceanQuota(credentials, device.serialNumber, requestedFields)
            }) {
                is EcoFlowCloudClient.Result.Success -> {
                    selectedQuota = result.value
                    dashboard = EcoFlowTelemetryMapper.snapshot(result.value, device.name, System.currentTimeMillis())
                    store.selectDevice(device)
                    config = store.config()
                    dashboardOpen = dashboardOpen || openingDashboard
                    feedback = "Device snapshot received. Device data age still needs verification."
                }
                is EcoFlowCloudClient.Result.Failure -> {
                    feedback = result.message
                    dashboardError = result.message
                }
            }
        }
    }

    BackHandler(enabled = dashboardOpen) { dashboardOpen = false }
    val snapshot = dashboard
    if (dashboardOpen && snapshot != null) {
        SourceDetailsScreen(
            snapshot = snapshot,
            padding = padding,
            refreshing = loading,
            refreshError = dashboardError,
            onRefresh = {
                config.selectedSerialNumber?.let { serial ->
                    inspectDevice(EcoFlowCloudClient.Device(serial, config.selectedDeviceName ?: "EcoFlow device", online = false), selectedQuota?.requestedFields == true)
                }
            },
            onBack = { dashboardOpen = false }
        )
        return
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(setupScroll)
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

        SetupFlowHeader(setupSteps, setupStep, helpLevel.isGuided, loading) { setupStep = it }
        SetupFlowSection(0, setupStep, helpLevel.isGuided, "Prepare developer access") {
        PowerSourceSectionTitle("What this version does")
        SettingsCard {
            Text("Read-only connection preview", fontWeight = FontWeight.Medium)
            Text(
                "Find your devices and open a dedicated dashboard for grid, solar, battery, home load and other readings your equipment reports. Expand sections or search to see additional fields.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                "Cloud monitoring cannot be activated yet. This documented connection is a device-data preview. Monitoring needs separately verified, current grid observations; phase voltage alone can remain present during whole-house backup.",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        NetworkBackupGuidance()

        PowerSourceSectionTitle("Developer access")
        SettingsCard {
            if (helpLevel.isGuided) {
                Text("1. Tap Open EcoFlow Developer below.")
                Text("2. Tap the person icon at the top-right. Choose Log in if you already have an EcoFlow account, or Create EcoFlow Account if you do not.")
                Text("3. Choose Become a Developer and complete the developer registration if EcoFlow asks for it.")
                Text("4. EcoFlow may show Under review. This is normal: wait for its approval, which EcoFlow says can take up to 5 working days. You cannot create keys while the review is pending.")
                Text("5. After approval, open Security Information Management in the developer console and choose Create AccessKey.")
                Text("6. Copy the AccessKey and SecretKey from the same key pair. Keep them private; do not send them in chat or issue reports.")
                Text("7. Return here, paste both keys, and tap Save credentials.")
                Text("8. Tap Find my EcoFlow devices. Then inspect the PowerOcean entry.")
            } else {
                Text("After EcoFlow developer approval, use Security Information Management → Create AccessKey, then save and test the key pair here.")
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, DEVELOPER_URL.toUri()))
                    }.onFailure { feedback = "No browser is available to open EcoFlow Developer." }
                },
                modifier = Modifier
            ) { Text("Open EcoFlow Developer") }
        }
        }
        SetupFlowSection(1, setupStep, helpLevel.isGuided, "Save API keys") {
        SettingsCard {
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
                "These are API credentials from the developer console, not your Wi-Fi password and not your normal EcoFlow account password. Flockle Grid Outage Monitor never needs your EcoFlow account password for this connection.",
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
                        dashboard = null
                        dashboardError = null
                        liveBrokerSummary = null
                        feedback = "EcoFlow credentials saved securely."
                    }
                },
                modifier = Modifier,
                enabled = !loading && accessKey.isNotBlank() && secretKey.isNotBlank()
            ) { Text("Save credentials") }
        }
        }
        SetupFlowSection(2, setupStep, helpLevel.isGuided, "Find and test devices") {
        SettingsCard {
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
                            is EcoFlowCloudClient.Result.Failure -> {
                                feedback = result.message
                                dashboardError = result.message
                            }
                        }
                    }
                },
                modifier = Modifier,
                enabled = !loading && config.hasCredentials && accessKey.isBlank() && secretKey.isBlank()
            ) {
                if (loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Text("Find my EcoFlow devices")
            }
        }

        config.selectedSerialNumber?.let { serial ->
            SettingsCard {
                Text(config.selectedDeviceName ?: "Selected EcoFlow device", fontWeight = FontWeight.Medium)
                Text("Read its data without changing inverter settings or your outage detector.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                CompactActions {
                    Button(
                        onClick = { inspectDevice(EcoFlowCloudClient.Device(serial, config.selectedDeviceName ?: "EcoFlow device", online = false)) },
                        enabled = !loading && config.hasCredentials,
                        modifier = Modifier
                    ) { Text("Open device dashboard") }
                    OutlinedButton(onClick = { inspectDevice(EcoFlowCloudClient.Device(serial,
                        config.selectedDeviceName ?: "EcoFlow device", online = false), requestedFields = true) },
                        enabled = !loading && config.hasCredentials, modifier = Modifier) {
                        Text("Request PowerOcean readings")
                    }
                }
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
                    CompactActions {
                        Button(
                            onClick = {
                                inspectDevice(device)
                            },
                            modifier = Modifier,
                            enabled = !loading
                        ) { Text("Inspect read-only data") }
                        OutlinedButton(onClick = { inspectDevice(device, requestedFields = true) },
                            modifier = Modifier, enabled = !loading) {
                            Text("Request PowerOcean readings")
                        }
                    }
                    Text("Try this documented read-only request if the all-readings inspection is denied. It requests grid phases, solar, battery and home power; it does not change inverter settings.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        selectedQuota?.let { quota ->
            PowerSourceSectionTitle("Latest inspection")
            SettingsCard {
                SettingText("Displayable readings", quota.reportedValues.size.toString())
                OutlinedButton(onClick = { dashboardOpen = true }, modifier = Modifier) {
                    Text("View latest device snapshot")
                }
            }
        }

        feedback?.let {
            Text(it, color = if (dashboardError == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium)
        }

        }
        SetupFlowFooter(setupSteps, setupStep, helpLevel.isGuided, loading, { setupStep = it }, onBack, finishEnabled = config.hasCredentials)
        ExpandableSettingsSection("Connection help and advanced checks", "Device permissions, live-data access and credential removal") {
        PowerSourceSectionTitle("If something does not work")
        SettingsCard {
            Text("Live-data access", fontWeight = FontWeight.Medium)
            Text("EcoFlow also documents MQTT, a connection that can push readings to an app. This read-only check asks EcoFlow for secure connection details; it does not connect to the live feed or change equipment settings. Connection passwords are kept out of the screen and are not saved.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            liveBrokerSummary?.let { SettingText("Secure endpoint available", it) }
            OutlinedButton(onClick = {
                val credentials = enteredCredentials()
                if (credentials == null) {
                    feedback = "Save valid EcoFlow credentials first."
                    return@OutlinedButton
                }
                runOperation {
                    when (val result = withContext(Dispatchers.IO) { client.readMqttConnectionInfo(credentials) }) {
                        is EcoFlowCloudClient.Result.Success -> {
                            liveBrokerSummary = "${result.value.host}:${result.value.port}"
                            feedback = "EcoFlow issued secure live-data connection details. No live feed connection or measurement was made by this check."
                        }
                        is EcoFlowCloudClient.Result.Failure -> {
                            liveBrokerSummary = null
                            feedback = result.message
                            dashboardError = result.message
                        }
                    }
                }
            }, enabled = !loading && config.hasCredentials, modifier = Modifier) {
                Text("Check live-data access")
            }
        }
        SettingsCard {
            Text("Device listed, but readings denied", fontWeight = FontWeight.Medium)
            Text("This is an EcoFlow developer API restriction, not an Android permission. Try Request PowerOcean readings. If both requests are denied, contact EcoFlow through Support in the developer console. Ask for read-only PowerOcean quota/API access for your account and equipment; include the error code and device model. Share serial details only through EcoFlow's private support process, never API keys.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text("No devices found", fontWeight = FontWeight.Medium)
            Text(
                "Check that the developer account is the owner of the PowerOcean. EcoFlow's documented device-list request does not return devices that were only shared with the account.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text("Credentials rejected", fontWeight = FontWeight.Medium)
            Text(
                "Copy the AccessKey and SecretKey again from the same key pair. Do not add spaces before or after either key.",
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
                        modifier = Modifier,
                        enabled = !loading
                    ) { Text("Remove EcoFlow Cloud credentials") }
                } else {
                    Text("This removes the encrypted keys and selected device from this phone.")
                    CompactActions {
                        Button(
                            onClick = {
                                store.clear()
                                config = store.config()
                                devices = emptyList()
                                selectedQuota = null
                                dashboard = null
                                dashboardError = null
                                liveBrokerSummary = null
                                confirmClear = false
                                feedback = "EcoFlow Cloud credentials removed."
                            },
                            modifier = Modifier,
                            enabled = !loading
                        ) { Text("Remove credentials") }
                        OutlinedButton(
                            onClick = { confirmClear = false },
                            modifier = Modifier
                        ) { Text("Cancel") }
                    }
                }
            }
        }
        }
    }
}

private fun maskSerial(serial: String): String = when {
    serial.length <= 6 -> "••••"
    else -> serial.take(4) + "••••" + serial.takeLast(2)
}

private const val DEVELOPER_URL = "https://developer-eu.ecoflow.com/"
