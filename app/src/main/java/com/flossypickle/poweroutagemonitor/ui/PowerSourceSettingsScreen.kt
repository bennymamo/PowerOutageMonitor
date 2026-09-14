package com.flossypickle.poweroutagemonitor.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignal
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowGridSignalMapper
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowModbusClient
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PowerSourceSettingsContent(
    selectedSource: PowerSourceStore.Source,
    sourceStatus: PowerSourceStore.Status?,
    helpLevel: MonitorStore.HelpLevel,
    onPowerSourceChanged: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { PowerSourceStore(context) }
    val client = remember { EcoFlowModbusClient() }
    val scope = rememberCoroutineScope()
    var savedConfig by remember(selectedSource, sourceStatus) { mutableStateOf(store.ecoFlowConfig()) }
    var host by remember(savedConfig) { mutableStateOf(savedConfig.host) }
    var portText by remember(savedConfig) { mutableStateOf(savedConfig.port.toString()) }
    var unitText by remember(savedConfig) { mutableStateOf(savedConfig.unitId.toString()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var localNetworkAllowed by remember {
        mutableStateOf(hasLocalNetworkAccess(context))
    }
    val requestLocalNetworkAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        localNetworkAllowed = granted
        feedback = if (granted) {
            "Local network access allowed. You can test the EcoFlow connection."
        } else {
            "Local network access was not allowed. EcoFlow cannot be reached on Android 17."
        }
    }
    val editingAllowed = selectedSource != PowerSourceStore.Source.ECOFLOW_MODBUS
    val ready = store.ecoFlowReadyToActivate()

    SetupGuidanceCaption(helpLevel)
    Text(
        "Choose which evidence decides whether the electricity grid is online. Device battery readings remain available with either source.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    PowerSourceSectionTitle("Android charger")
    SettingsCard {
        SourceHeading(
            title = "Phone or tablet charger",
            status = if (selectedSource == PowerSourceStore.Source.ANDROID_CHARGER) "ACTIVE" else "AVAILABLE"
        )
        Text(
            "Uses Android's external-power signal. Choose this when the charger is connected to a socket that loses power with the grid.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        if (selectedSource != PowerSourceStore.Source.ANDROID_CHARGER) {
            OutlinedButton(
                onClick = {
                    store.select(PowerSourceStore.Source.ANDROID_CHARGER)
                    feedback = "Android charger is now the active grid source."
                    onPowerSourceChanged()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Use Android charger") }
        }
    }

    PowerSourceSectionTitle("EcoFlow PowerOcean")
    SettingsCard {
        SourceHeading(
            title = "Local inverter connection",
            status = if (selectedSource == PowerSourceStore.Source.ECOFLOW_MODBUS) "ACTIVE" else "OPTIONAL"
        )
        Text(
            "Reads the inverter's grid mode, grid-side voltage and frequency over your home network. It does not send control commands.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        if (helpLevel.isGuided) {
            Text("Before setup:", fontWeight = FontWeight.Medium)
            Text("1. Ask EcoFlow support, your installer, or another certified EcoFlow partner to enable Modbus control on the inverter.")
            Text("2. In your router, expand likely embedded-device entries such as ESP, lwIP, wlan or Unknown and note each private IPv4 address.")
            Text("3. Reserve that address in the router so it does not change.")
            Text("4. Keep the router and local network equipment on backup power.")
            Text("5. Save the address, run the read-only test, then activate EcoFlow.")
            Text(
                "A network scanner finding no open TCP port 502 usually means Modbus is disabled. It can also mean the inverter is on another subnet or Wi-Fi client isolation is enabled.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        } else {
            Text(
                "Requires inverter Modbus TCP access, a stable local IPv4 address, TCP port 502, unit 1, and backed-up LAN equipment.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        if (!editingAllowed) {
            Text(
                "EcoFlow is active. Switch to Android charger before changing its connection settings.",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        if (Build.VERSION.SDK_INT >= 37 && !localNetworkAllowed) {
            Text(
                if (helpLevel.isGuided) {
                    "Android 17 needs your permission before FP Grid Monitor can contact devices on your home network. This is used only for the EcoFlow address you enter."
                } else {
                    "ACCESS_LOCAL_NETWORK is required for the Modbus TCP socket on Android 17+."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = { requestLocalNetworkAccess.launch(LOCAL_NETWORK_PERMISSION) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Allow local network access") }
        }
        OutlinedTextField(
            value = host,
            onValueChange = { if (it.length <= 15) host = it.filterNot(Char::isWhitespace) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Inverter local IPv4 address") },
            supportingText = { Text("Example: 192.168.1.50") },
            singleLine = true,
            enabled = editingAllowed && !testing,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = portText,
                onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) portText = it },
                modifier = Modifier.weight(1f),
                label = { Text("TCP port") },
                singleLine = true,
                enabled = editingAllowed && !testing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = unitText,
                onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) unitText = it },
                modifier = Modifier.weight(1f),
                label = { Text("Unit") },
                singleLine = true,
                enabled = editingAllowed && !testing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        if (editingAllowed) {
            OutlinedButton(
                onClick = {
                    val parsed = parsedConfig(host, portText, unitText)
                    if (parsed == null) {
                        feedback = "Enter a private local IPv4 address, a port from 1 to 65535, and a unit from 0 to 247."
                    } else {
                        store.saveEcoFlowConfig(parsed)
                        savedConfig = parsed
                        feedback = "Connection settings saved. Run the read-only test next."
                        onPowerSourceChanged()
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save connection") }
            Button(
                onClick = {
                    if (!hasLocalNetworkAccess(context)) {
                        requestLocalNetworkAccess.launch(LOCAL_NETWORK_PERMISSION)
                        return@Button
                    }
                    val config = store.ecoFlowConfig()
                    if (!config.isValid || config.host != host.trim() ||
                        config.port.toString() != portText || config.unitId.toString() != unitText
                    ) {
                        feedback = "Save these connection settings before testing them."
                        return@Button
                    }
                    testing = true
                    feedback = null
                    scope.launch {
                        val signal = withContext(Dispatchers.IO) {
                            runCatching {
                                EcoFlowGridSignalMapper.toSignal(
                                    EcoFlowGridSignalMapper.decode(
                                        client.readGridRegisters(config.host, config.port, config.unitId)
                                    ),
                                    System.currentTimeMillis()
                                )
                            }.getOrElse { error ->
                                PowerSignal(
                                    GridAvailability.UNKNOWN,
                                    System.currentTimeMillis(),
                                    PowerSourceStore.ECOFLOW_PROVIDER_ID,
                                    connectionErrorMessage(error)
                                )
                            }
                        }
                        store.recordEcoFlowTest(signal)
                        testing = false
                        feedback = when (signal.availability) {
                            GridAvailability.AVAILABLE -> "Connected. EcoFlow reports grid power available."
                            GridAvailability.UNAVAILABLE -> "Connected. EcoFlow reports islanded operation with grid power unavailable."
                            GridAvailability.UNKNOWN -> "No trustworthy reading: ${signal.detail.orEmpty()}"
                        }
                        onPowerSourceChanged()
                    }
                },
                enabled = !testing && savedConfig.isValid && localNetworkAllowed,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else Text("Test read-only connection")
            }
        }
        if (sourceStatus?.source == PowerSourceStore.Source.ECOFLOW_MODBUS) {
            SettingText("Last EcoFlow reading", sourceStatus.detail ?: sourceStatus.availability.name)
        }
        if (selectedSource != PowerSourceStore.Source.ECOFLOW_MODBUS) {
            Button(
                onClick = {
                    if (store.select(PowerSourceStore.Source.ECOFLOW_MODBUS)) {
                        feedback = "EcoFlow PowerOcean is now the active grid source."
                        onPowerSourceChanged()
                    } else {
                        feedback = "Run a successful connection test before activating EcoFlow."
                    }
                },
                enabled = ready && !testing && localNetworkAllowed,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Use EcoFlow PowerOcean") }
        }
    }

    feedback?.let {
        Text(
            it,
            color = if (it.startsWith("Connected") || it.contains("now the active") || it.contains("saved")) {
                MaterialTheme.colorScheme.primary
            } else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium
        )
    }

    PowerSourceSectionTitle("Reliability")
    SettingsCard {
        Text(
            "EcoFlow readings stay inside your local network and are checked every 5 seconds. A timeout, old value, invalid number, or disagreement between inverter mode and voltage becomes Unknown. Unknown readings cannot confirm an outage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Text(
            "Before relying on EcoFlow, perform one controlled grid-loss test with the inverter and confirm both outage and restoration in this app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SourceHeading(title: String, status: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PowerSourceSectionTitle(value: String) {
    Text(
        value.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

private fun parsedConfig(host: String, portText: String, unitText: String): PowerSourceStore.EcoFlowConfig? {
    val trimmedHost = host.trim()
    if (!isPrivateIpv4(trimmedHost)) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
    val unit = unitText.toIntOrNull()?.takeIf { it in 0..247 } ?: return null
    return PowerSourceStore.EcoFlowConfig(trimmedHost, port, unit)
}

internal fun isPrivateIpv4(value: String): Boolean {
    val parts = value.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 ||
        parts[0] == 192 && parts[1] == 168 ||
        parts[0] == 172 && parts[1] in 16..31
}

private fun hasLocalNetworkAccess(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
        context,
        LOCAL_NETWORK_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private fun connectionErrorMessage(error: Throwable): String = when (error) {
    is java.net.SocketTimeoutException -> "The inverter did not reply before the timeout."
    is java.net.ConnectException -> "Connection refused. Check the address and ask EcoFlow support or a certified partner to enable Modbus TCP."
    is java.net.NoRouteToHostException -> "The inverter is not reachable on this network."
    else -> error.message?.take(140) ?: error.javaClass.simpleName
}
