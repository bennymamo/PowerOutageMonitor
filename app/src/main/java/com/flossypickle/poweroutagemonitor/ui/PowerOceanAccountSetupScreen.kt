package com.flossypickle.poweroutagemonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetrySnapshot
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

@Composable
internal fun PowerOceanAccountSetupScreen(helpLevel: MonitorStore.HelpLevel, padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { PowerOceanAccountStore(context) }
    val client = remember { PowerOceanAccountClient() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(store.connection()) }
    var email by remember { mutableStateOf(saved?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf(saved?.serial.orEmpty()) }
    var model by remember { mutableStateOf(saved?.model ?: "86") }
    var region by remember { mutableStateOf(saved?.region ?: "eu") }
    var interval by remember { mutableStateOf((saved?.refreshSeconds ?: 30).toString()) }
    var session by remember { mutableStateOf<PowerOceanAccountClient.Session?>(null) }
    var snapshot by remember { mutableStateOf<SourceTelemetrySnapshot?>(null) }
    var dashboardOpen by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var successfulReads by remember { mutableStateOf(0) }
    var changedFields by remember { mutableStateOf(0) }
    var probeJob by remember { mutableStateOf<Job?>(null) }
    var probeRunning by remember { mutableStateOf(false) }
    var pushStatus by remember { mutableStateOf<String?>(null) }
    var requestLiveReporting by remember { mutableStateOf(false) }
    var pushError by remember { mutableStateOf<String?>(null) }
    var pushFeedback by remember { mutableStateOf<String?>(null) }
    var inspectionSeconds by remember { mutableStateOf(45) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) probeJob?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); probeJob?.cancel() }
    }

    fun closeDashboard() { generation++; dashboardOpen = false; autoRefresh = false; probeJob?.cancel() }

    fun editedConnection() = PowerOceanAccountClient.Connection(email.trim(), password.ifEmpty { saved?.password.orEmpty() },
        serial.trim(), model, region, interval.toIntOrNull() ?: 0)
    val unsaved = editedConnection() != saved

    suspend fun read(current: PowerOceanAccountClient.Session, openAfter: Boolean) {
        if (loading) return
        val requestedGeneration = generation
        loading = true
        error = null
        pushStatus = null
        try {
            when (val result = withContext(Dispatchers.IO) { client.read(current) }) {
                is EcoFlowCloudClient.Result.Success -> {
                    val next = withContext(Dispatchers.Default) {
                        runCatching { PowerOceanAccountTelemetry.snapshot(result.value, System.currentTimeMillis()) }.getOrNull()
                    }
                    if (requestedGeneration != generation) return
                    if (next == null || next.sections.isEmpty()) {
                        error = "No supported device readings were returned. Check the inverter serial, model and region."
                        autoRefresh = false
                    } else {
                        val previousValues = snapshot?.sections?.flatMap { it.readings }?.associate { it.key to it.value }.orEmpty()
                        changedFields = next.sections.flatMap { it.readings }.count { previousValues[it.key]?.let { old -> old != it.value } == true }
                        successfulReads++
                        snapshot = next
                        if (openAfter) dashboardOpen = true
                        feedback = "Device readings received. Grid-outage evidence still needs a real-system check."
                    }
                }
                is EcoFlowCloudClient.Result.Failure -> {
                    if (requestedGeneration != generation) return
                    error = result.message
                    if (!result.retryable) autoRefresh = false
                }
            }
        } finally { loading = false }
    }

    LaunchedEffect(dashboardOpen, autoRefresh, session) {
        val current = session
        if (dashboardOpen && autoRefresh && current != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(current.connection.refreshSeconds * 1000L)
                    read(current, false)
                    if (!autoRefresh) break
                }
            }
        }
    }
    BackHandler(dashboardOpen) { closeDashboard() }
    val currentSnapshot = snapshot
    if (dashboardOpen && currentSnapshot != null) {
        SourceDetailsScreen(currentSnapshot, padding, loading, error,
            onRefresh = { if (!probeRunning) {
                if (pushStatus != null) closeDashboard() else session?.let { current -> scope.launch { read(current, false) } }
            } },
            onBack = { closeDashboard() },
            refreshLabel = if (pushStatus == null) "Refresh device readings" else "Set up another live inspection",
            dashboardControls = {
                SettingsCard {
                    if (pushStatus == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Auto-refresh while viewing")
                        Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it }, enabled = !probeRunning && pushStatus == null)
                    }
                    Text("Requests every ${session?.connection?.refreshSeconds ?: 30} seconds. Pauses when this screen is closed or the app is in the background.", style = MaterialTheme.typography.bodySmall)
                    Text("Successful reads: $successfulReads · fields changed since previous read: $changedFields", style = MaterialTheme.typography.bodySmall)
                    Text("Changing fields prove activity, not that every field is fresh.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Live feed inspection", fontWeight = FontWeight.Medium)
                        Text("Manual inspection · ${if (inspectionSeconds == 300) "5 minutes" else "45 seconds"}. The display keeps the final snapshot when inspection ends. Use the button below to set up another inspection.", style = MaterialTheme.typography.bodySmall)
                    }
                    pushStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (probeRunning) OutlinedButton({ probeJob?.cancel() }) { Text("Stop push inspection") }
                }
            })
        return
    }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("‹ Power sources") }
        Text("PowerOcean account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        SetupGuidanceCaption(helpLevel)
        SettingsCard {
            Text("Optional · experimental", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Text("Uses your normal EcoFlow login to read PowerOcean's cloud service. No developer keys, Modbus or installer are needed. EcoFlow may change this unofficial interface.")
            Text("This reads your inverter and batteries while your home remains powered by backup. It does not change inverter settings. Outage monitoring will be enabled only after we verify a trustworthy grid signal.", style = MaterialTheme.typography.bodySmall)
        }
        NetworkBackupGuidance()
        if (helpLevel.isGuided) SettingsCard {
            Text("Before you connect", fontWeight = FontWeight.Medium)
            Text("1. Use the email and password you use to log into the EcoFlow app. This is different from developer AccessKey and SecretKey.")
            Text("2. In EcoFlow, open your PowerOcean system and its device information. Copy the inverter's serial number (S/N), not a battery's serial number.")
            Text("3. Choose your actual model and account region below. Single Phase and Europe are selected initially.")
            Text("4. Tap Save account, then Connect and read device. Keep your password private; enter it here, never in chat.")
            Text("5. Once readings appear, close EcoFlow's app and web portal. Use auto-refresh here to check whether the readings keep changing.")
        }
        PowerSourceSectionTitle("Account")
        SettingsCard {
            OutlinedTextField(email, { email = it }, label = { Text("EcoFlow account email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(if (saved == null) "EcoFlow password" else "New password · blank keeps saved password") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth())
            Text("Email, password and serial are encrypted on this device. Password-encrypted backups include them when Power sources is selected. Login tokens stay in memory.", style = MaterialTheme.typography.bodySmall)
        }
        PowerSourceSectionTitle("Equipment")
        SettingsCard {
            OutlinedTextField(serial, { serial = it }, label = { Text("Inverter serial number") }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth())
            listOf("86" to "PowerOcean Single Phase", "83" to "PowerOcean", "85" to "PowerOcean DC Fit", "87" to "PowerOcean Plus").forEach { (id, title) ->
                Row { RadioButton(model == id, { model = id }, enabled = !loading); TextButton({ model = id }, enabled = !loading) { Text(title) } }
            }
        }
        PowerSourceSectionTitle("Connection and refresh")
        SettingsCard {
            Row { RadioButton(region == "eu", { region = "eu" }, enabled = !loading); TextButton({ region = "eu" }, enabled = !loading) { Text("Europe") }
                RadioButton(region == "us", { region = "us" }, enabled = !loading); TextButton({ region = "us" }, enabled = !loading) { Text("United States") } }
            OutlinedTextField(interval, { interval = it }, label = { Text("Refresh interval · 10–60 seconds") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth())
            Text("This controls how often we ask. EcoFlow may update individual readings less often. Auto-refresh is opt-in on the device dashboard.", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Button(onClick = {
            runCatching { store.save(editedConnection()) }.onSuccess {
                generation++; saved = store.connection(); password = ""; session = null; snapshot = null; pushStatus = null
                successfulReads = 0; changedFields = 0; error = null; feedback = "Account saved securely. Tap Connect and read device."
            }.onFailure { error = "Check all account fields. Refresh must be between 10 and 60 seconds." }
        }, enabled = !loading && editedConnection().isValid, modifier = Modifier.fillMaxWidth()) { Text("Save account") }
        Button(onClick = {
            val account = saved ?: return@Button
            val requestedGeneration = generation
            loading = true; error = null; pushStatus = null; feedback = "Connecting to EcoFlow…"
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { client.login(account) }
                    if (requestedGeneration != generation) return@launch
                    loading = false
                    when (result) {
                        is EcoFlowCloudClient.Result.Success -> { session = result.value; read(result.value, true) }
                        is EcoFlowCloudClient.Result.Failure -> { error = result.message; feedback = null }
                    }
                } finally { loading = false }
            }
        }, enabled = saved != null && !unsaved && !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            Text(if (loading) "Connecting…" else "Connect and read device")
        }
        if (unsaved && saved != null) Text("Save your changes before connecting.", style = MaterialTheme.typography.bodySmall)
        PowerSourceSectionTitle("Live push inspection")
        SettingsCard {
            Text("Try the faster account feed", fontWeight = FontWeight.Medium)
            Text("Connect your saved account first, then run a secure MQTT inspection.", style = MaterialTheme.typography.bodySmall)
            Text("Inspection time", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(inspectionSeconds == 45, { inspectionSeconds = 45 }, label = { Text("45 seconds") }, enabled = !loading)
                FilterChip(inspectionSeconds == 300, { inspectionSeconds = 300 }, label = { Text("5 minutes") }, enabled = !loading)
            }
            Text("Use 45 seconds to check access, or 5 minutes to compare grid-loss and recovery readings. These are manual inspections, not background outage monitoring.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Request live reporting", modifier = Modifier.weight(1f))
                Switch(requestLiveReporting, { requestLiveReporting = it }, enabled = !loading)
            }
            Text("Enable if readings only update when EcoFlow's app is open. Sends the portal's temporary live-report request every 20 seconds during inspection. Stops requesting when inspection ends. No charging, reserve or output controls are sent. This unofficial protocol still needs checking on your model.", style = MaterialTheme.typography.bodySmall)
            Text("Stops when you leave the dashboard or put the app in the background. If no readings arrive, the result explains what remains to investigate.", style = MaterialTheme.typography.bodySmall)
            pushFeedback?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            pushError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = {
                val current = session ?: return@Button
                val requestedGeneration = generation
                autoRefresh = false; probeRunning = true; loading = true; error = null; feedback = null
                pushError = null; pushFeedback = "Requesting secure push access…"
                probeJob = scope.launch {
                    try {
                        when (val result = withContext(Dispatchers.IO) { client.pushCredentials(current) }) {
                            is EcoFlowCloudClient.Result.Failure -> if (requestedGeneration == generation) { pushError = result.message; pushFeedback = null }
                            is EcoFlowCloudClient.Result.Success -> {
                                pushFeedback = "Inspecting push feed…"
                                if (requestedGeneration != generation) return@launch
                                val inspectionError = PowerOceanPushProbe().inspect(current, result.value, requestLiveReporting, inspectionSeconds) { update ->
                                    if (requestedGeneration == generation) {
                                        snapshot = update.snapshot; dashboardOpen = true
                                        pushStatus = "Push packets: ${update.packets} · unsupported: ${update.unsupported} · retained: ${update.retained}"
                                    }
                                }
                                if (requestedGeneration == generation) {
                                    error = inspectionError
                                    pushError = inspectionError
                                    pushFeedback = if (error == null) "Push inspection finished. Displaying its last readings." else null
                                    pushStatus = pushStatus?.plus(" · inspection finished")
                                }
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        if (requestedGeneration == generation) {
                            pushFeedback = "Push inspection stopped. Displaying its last readings."
                            pushStatus = pushStatus?.plus(" · inspection stopped")
                        }
                        throw cancelled
                    } finally { probeRunning = false; loading = false }
                }
            }, enabled = session != null && !unsaved && !loading, modifier = Modifier.fillMaxWidth()) {
                if (probeRunning) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                Text(if (probeRunning) "Opening and inspecting live feed…" else "Inspect live push feed")
            }
        }
        if (saved != null) {
            OutlinedButton({ confirmClear = true }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Remove saved account") }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Remove PowerOcean account?") },
        text = { Text("Removes the saved connection from this phone. Existing encrypted backups may still contain it.") },
        confirmButton = { TextButton({ store.clear(); generation++; saved = null; session = null; snapshot = null; email = ""; password = ""; serial = ""; feedback = null; error = null; confirmClear = false }) { Text("Remove") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
}
