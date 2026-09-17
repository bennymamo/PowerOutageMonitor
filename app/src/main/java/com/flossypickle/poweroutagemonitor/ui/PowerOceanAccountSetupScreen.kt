package com.flossypickle.poweroutagemonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
internal fun PowerOceanAccountSetupScreen(helpLevel: MonitorStore.HelpLevel, padding: PaddingValues, onPowerSourceChanged: () -> Unit, onBack: () -> Unit) {
    val directReturn = LocalDashboardReturn.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { PowerOceanAccountStore(context) }
    val sourceStore = remember(context) { com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore(context) }
    var assistedSettings by remember { mutableStateOf(sourceStore.powerOceanAssistedSettings()) }
    var requireChargerConfirmation by remember { mutableStateOf(sourceStore.powerOceanRequiresChargerConfirmation()) }
    var chargerPowered by remember { mutableStateOf<Boolean?>(null) }
    var liveCheckStatus by remember { mutableStateOf<PowerOceanLiveCheck.Status?>(null) }
    var lossConfirmation by remember { mutableStateOf<PowerOceanLossConfirmation.Result?>(null) }
    val client = remember { PowerOceanAccountClient() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(store.connection()) }
    var email by remember { mutableStateOf(saved?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf(saved?.serial.orEmpty()) }
    var model by remember { mutableStateOf(saved?.model ?: "86") }
    var region by remember { mutableStateOf(saved?.region ?: "eu") }
    var interval by remember { mutableStateOf((saved?.refreshSeconds ?: 60).toString()) }
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
    var requestLiveReporting by remember { mutableStateOf(sourceStore.powerOceanRequestsLiveReporting()) }
    var pushError by remember { mutableStateOf<String?>(null) }
    var pushFeedback by remember { mutableStateOf<String?>(null) }
    var inspectionSeconds by remember { mutableStateOf(45) }
    var usePreviousTest by remember(saved) { mutableStateOf(sourceStore.powerOceanUsesPreviousTest(saved)) }
    var useTestedGridCorrelation by remember(saved) { mutableStateOf(sourceStore.powerOceanProfileVerified(saved)) }
    var confirmGridCorrelation by remember { mutableStateOf(false) }
    var confirmPreviousTest by remember { mutableStateOf(false) }
    val setupSteps = listOf("Before you begin", "Account login", "Your equipment", "Save and connect", "Verify and monitor")
    var setupStep by rememberSaveable { mutableStateOf(if (saved != null) 4 else 0) }
    var selectedSource by remember { mutableStateOf(sourceStore.selectedSource()) }
    val accountActive = selectedSource == com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore.Source.ECOFLOW_ACCOUNT
    val setupScroll = rememberScrollState()
    LaunchedEffect(setupStep) { setupScroll.scrollTo(0) }
    var gridInspection by remember { mutableStateOf<com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanGridInspection.Snapshot?>(null) }

    fun receivedTime(epochMs: Long) = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
    val inspectionDuration = if (inspectionSeconds >= 60) "${inspectionSeconds / 60} minutes" else "$inspectionSeconds seconds"

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
    var activationClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(saved, dashboardOpen) {
        while (true) { activationClock = System.currentTimeMillis(); delay(1000) }
    }
    val activationStage = sourceStore.powerOceanActivationStage(saved, activationClock)
    BackHandler(!dashboardOpen && helpLevel.isGuided && setupStep > 0 && !loading) { if (directReturn != null) directReturn() else setupStep-- }

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
                    autoRefresh = false
                }
            }
        } finally { loading = false }
    }

    LaunchedEffect(dashboardOpen, autoRefresh, session) {
        val current = session
        if (dashboardOpen && autoRefresh && current != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(current.connection.refreshSeconds.coerceAtLeast(60) * 1000L)
                    read(current, false)
                    if (!autoRefresh) break
                }
            }
        }
    }
    BackHandler(dashboardOpen) { if (directReturn != null) directReturn() else closeDashboard() }
    val currentSnapshot = snapshot
    if (dashboardOpen && currentSnapshot != null) {
        SourceDetailsScreen(currentSnapshot, padding, loading, error,
            onRefresh = { if (!probeRunning) {
                if (pushStatus != null) closeDashboard() else session?.let { current -> scope.launch { read(current, false) } }
            } },
            onBack = { if (directReturn != null) directReturn() else closeDashboard() },
            refreshLabel = if (pushStatus == null) "Refresh device readings" else "Set up another live inspection",
            dashboardControls = {
                SettingsCard {
                    if (pushStatus == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Auto-refresh while viewing")
                        Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it }, enabled = !probeRunning && pushStatus == null)
                    }
                    Text("Requests every ${session?.connection?.refreshSeconds?.coerceAtLeast(60) ?: 60} seconds. Pauses when this screen is closed or the app is in the background.", style = MaterialTheme.typography.bodySmall)
                    Text("Successful reads: $successfulReads · fields changed since previous read: $changedFields", style = MaterialTheme.typography.bodySmall)
                    Text("Changing fields prove activity, not that every field is fresh.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Live feed inspection", fontWeight = FontWeight.Medium)
                        Text("Manual inspection · $inspectionDuration. The display keeps the final snapshot when inspection ends. Use the button below to set up another inspection.", style = MaterialTheme.typography.bodySmall)
                    }
                    pushStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (probeRunning) OutlinedButton({ probeJob?.cancel() }) { Text("Stop push inspection") }
                }
                if (pushStatus != null) SettingsCard {
                    Text("Grid comparison", fontWeight = FontWeight.Medium)
                    liveCheckStatus?.let { check ->
                        Text(if (!check.hasCurrentReport(activationClock)) "Waiting for new live device reports; cached replies do not verify this check."
                            else if (check.possiblyStalled) "Power readings are identical across successive checks. The feed may be stalled, or the load steady."
                            else "New live device reports received for this check.", style = MaterialTheme.typography.bodySmall)
                    }
                    val evidence = gridInspection
                    Text(evidence?.lastCode?.let { "Last reported grid code: $it" } ?: "Waiting for an explicit grid-code report.")
                    evidence?.lastReceivedUtcMillis?.let {
                        Text("Last code received: ${receivedTime(it)} (phone time)", style = MaterialTheme.typography.bodySmall)
                    }
                    evidence?.changes?.forEach {
                        Text("${receivedTime(it.receivedUtcMillis)} · code ${it.code}", style = MaterialTheme.typography.bodySmall)
                    }
                    evidence?.correlation?.let { comparison ->
                        val label = when (comparison.state) {
                            PowerOceanGridCorrelation.State.UNKNOWN -> "Not enough current grid evidence"
                            PowerOceanGridCorrelation.State.INVERTER_CONNECTED -> "EcoFlow reports a grid connection"
                            PowerOceanGridCorrelation.State.INVERTER_OFF_GRID -> if (comparison.currentMeterValue == 0.0) "EcoFlow off-grid · meter reports zero flow" else "EcoFlow off-grid · waiting for meter evidence"
                            PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY -> "Grid appears back · waiting for EcoFlow to reconnect"
                        }
                        Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        ExpandableSettingsSection("How grid comparison works", "Tested grid code, meter activity and changing device data") {
                            Text("This setup test compares the reported grid code with meter 1. Zero flow alone does not prove an outage. After confirmed loss, two changing non-zero meter readings support grid return while the inverter reconnects. Retained messages and repeated old pre-outage values cannot establish return.", style = MaterialTheme.typography.bodySmall)
                            Text("An unchanged grid code is usable while new device values arrive. Phone receipt times and changing values support apparent freshness; EcoFlow does not supply a verified measurement time. This inspection does not send outage alerts.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (requireChargerConfirmation) {
                            Text("Charger: ${when (chargerPowered) { true -> "connected"; false -> "disconnected"; null -> "unknown" }}", style = MaterialTheme.typography.bodySmall)
                            Text(when (lossConfirmation?.reason) {
                                PowerOceanLossConfirmation.Reason.CHARGER_CORROBORATED -> "Outage evidence agrees: EcoFlow off-grid, zero meter flow and charger disconnected."
                                PowerOceanLossConfirmation.Reason.WAITING_FOR_CHARGER -> "Waiting for charger-loss confirmation."
                                PowerOceanLossConfirmation.Reason.RETURN_PENDING -> "Recovery follows the grid evidence; charger reconnection is not required."
                                else -> "Charger confirmation applies to outage detection only."
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Test loss and restoration before enabling a new installation. Codes and meter behavior can differ by model.", style = MaterialTheme.typography.bodySmall)
                    Text("Backup can keep AC voltage present during an outage. Grid reconnection can also be reported several minutes after the supply returns. Allow time for recovery.", style = MaterialTheme.typography.bodySmall)
                }
            })
        return
    }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(setupScroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text(if (LocalDashboardReturn.current != null) "‹ Status" else "‹ Power sources") }
        Text("PowerOcean account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        SetupGuidanceCaption(helpLevel)
        SetupFlowHeader(setupSteps, setupStep, helpLevel.isGuided, loading) { setupStep = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        SetupFlowSection(0, setupStep, helpLevel.isGuided, "Before you begin") {
        SettingsCard {
            Text("Optional · experimental", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Text("Read PowerOcean with your normal EcoFlow login. No developer keys or installer access needed. This optional connection is unofficial; EcoFlow has not confirmed permitted usage.")
            ExpandableSettingsSection("Connection details", "Read-only equipment access and tested grid behavior") {
                Text("Reads inverter and battery data without changing charging, reserve or output settings. Scheduled checks open briefly and close between cycles. Low traffic does not guarantee EcoFlow account approval.", style = MaterialTheme.typography.bodySmall)
                Text("Verify grid behavior on your installation, or confirm your previous successful test. Choose the charger source before changing this account. New changing device reports are needed for EcoFlow monitoring decisions.", style = MaterialTheme.typography.bodySmall)
            }
        }
        ExpandableSettingsSection("Keep internet working during an outage", "Router, Wi-Fi and network adapters need backup power") { NetworkBackupGuidance() }
        if (helpLevel.isGuided) ExpandableSettingsSection("Find your login and inverter serial", "Step-by-step help") {
            Text("Before you connect", fontWeight = FontWeight.Medium)
            Text("1. Use the email and password you use to log into the EcoFlow app. This is different from developer AccessKey and SecretKey.")
            Text("2. In EcoFlow, open your PowerOcean system and its device information. Copy the inverter's serial number (S/N), not a battery's serial number.")
            Text("3. Choose your actual model and account region below. Single Phase and Europe are selected initially.")
            Text("4. Tap Save account, then Connect and read device. Keep your password private; enter it here, never in chat.")
            Text("5. In Verify and monitor, run a live-feed test with EcoFlow's app and web portal closed. Check whether power values change.")
        }

        }
        SetupFlowSection(1, setupStep, helpLevel.isGuided, "Account and equipment") {
        PowerSourceSectionTitle("Account")
        SettingsCard {
            OutlinedTextField(email, { email = it }, label = { Text("EcoFlow account email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, enabled = !loading && !accountActive, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(if (saved == null) "EcoFlow password" else "New password · blank keeps saved password") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !loading && !accountActive, modifier = Modifier.fillMaxWidth())
            Text("Email, password and serial are encrypted on this device. Password-encrypted backups include them when Power sources is selected. Login tokens stay in memory.", style = MaterialTheme.typography.bodySmall)
        }
        }
        SetupFlowSection(2, setupStep, helpLevel.isGuided, "Your equipment") {
        PowerSourceSectionTitle("Equipment")
        SettingsCard {
            OutlinedTextField(serial, { serial = it }, label = { Text("Inverter serial number") }, singleLine = true, enabled = !loading && !accountActive, modifier = Modifier.fillMaxWidth())
            listOf("86" to "PowerOcean Single Phase", "83" to "PowerOcean", "85" to "PowerOcean DC Fit", "87" to "PowerOcean Plus").forEach { (id, title) ->
                Row { RadioButton(model == id, { model = id }, enabled = !loading && !accountActive); TextButton({ model = id }, enabled = !loading && !accountActive) { Text(title) } }
            }
        }
        PowerSourceSectionTitle("Connection and refresh")
        SettingsCard {
            Row { RadioButton(region == "eu", { region = "eu" }, enabled = !loading && !accountActive); TextButton({ region = "eu" }, enabled = !loading && !accountActive) { Text("Europe") }
                RadioButton(region == "us", { region = "us" }, enabled = !loading && !accountActive); TextButton({ region = "us" }, enabled = !loading && !accountActive) { Text("United States") } }

        }

        }
        SetupFlowSection(3, setupStep, helpLevel.isGuided, "Save and connect") {
        CompactActions {
            Button(onClick = {
                runCatching { store.save(editedConnection()); sourceStore.setPowerOceanLiveReporting(requestLiveReporting) }.onSuccess {
                    generation++; saved = store.connection(); password = ""; session = null; snapshot = null; pushStatus = null
                    successfulReads = 0; changedFields = 0; error = null; feedback = "Account saved securely. Tap Connect and read device."
                }.onFailure { error = "Check all account fields. Refresh must be between 60 and 3,600 seconds." }
            }, enabled = !loading && !accountActive && editedConnection().isValid, modifier = Modifier) { Text("Save account") }
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
                            is EcoFlowCloudClient.Result.Success -> { session = result.value; read(result.value, false) }
                            is EcoFlowCloudClient.Result.Failure -> { error = result.message; feedback = null }
                        }
                    } finally { loading = false }
                }
            }, enabled = saved != null && !unsaved && !loading, modifier = Modifier) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                Text(if (loading) "Connecting…" else "Connect and read device")
            }
        }
        if (unsaved && saved != null) Text("Save your changes before connecting.", style = MaterialTheme.typography.bodySmall)
        if (snapshot != null) OutlinedButton({ dashboardOpen = true }, enabled = !loading) { Text("Open device readings") }

        }
        SetupFlowSection(4, setupStep, helpLevel.isGuided, "Verify and monitor") {
        PowerOceanSamplingSettings(assistedSettings) {
            sourceStore.setPowerOceanAssistedSettings(it)
            assistedSettings = it
            com.flossypickle.poweroutagemonitor.monitoring.MonitoringService.refreshScheduledAlerts(context)
        }
        if (!assistedSettings.enabled) {
        SettingsCard {
            OutlinedTextField(interval, { interval = it }, label = { Text("Refresh interval · 60–3,600 seconds") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !loading && !accountActive, modifier = Modifier.fillMaxWidth())
            Text("This controls how often we ask. EcoFlow may update individual readings less often. Auto-refresh is opt-in on the device dashboard.", style = MaterialTheme.typography.bodySmall)
            Text("Reading requests are at least one minute apart. Higher intervals delay meter confirmation and can let evidence expire to Unknown. Save changed intervals in Save and connect.", style = MaterialTheme.typography.bodySmall)
        }
        SettingsCard {
            Text("Additional confirmation", fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Require charger confirmation for outages", modifier = Modifier.weight(1f))
                Switch(requireChargerConfirmation, {
                    sourceStore.setPowerOceanChargerConfirmation(it)
                    requireChargerConfirmation = it
                    onPowerSourceChanged()
                }, enabled = !loading)
            }
            Text("Off by default. When enabled, the phone must also lose charger power before EcoFlow and meter evidence can confirm an outage. Grid recovery does not wait for the charger to reconnect. This setting is included in Power sources backups.", style = MaterialTheme.typography.bodySmall)
        }
        }
        SettingsCard {
            Text("Background monitoring", fontWeight = FontWeight.SemiBold)
            Text(if (accountActive) "${if (assistedSettings.enabled) "Charger + EcoFlow assistance" else "PowerOcean"} is selected. The dashboard master switch controls monitoring." else "Select this source only after testing grid loss and restoration on your installation.")
            if (!accountActive) {
                val nextStep = when {
                    loading -> "Wait for the current connection or inspection to finish."
                    unsaved -> "Save account changes in Save and connect first."
                    else -> when (activationStage) {
                        PowerOceanActivationPolicy.Stage.ACCOUNT_REQUIRED -> "Save a valid account in Save and connect first."
                        PowerOceanActivationPolicy.Stage.UNSUPPORTED_MODEL -> "Background grid monitoring currently supports only a tested Single Phase installation."
                        PowerOceanActivationPolicy.Stage.PROFILE_REQUIRED -> "Next: confirm your previous tested profile below, or use Live-feed test for a first-time check."
                        PowerOceanActivationPolicy.Stage.LIVE_TEST_REQUIRED -> "Next: run a 45-second live check below, or use your previous successful test if this installation was already tested."
                        PowerOceanActivationPolicy.Stage.READY -> if (usePreviousTest) "Ready to activate using your previous successful test. No repeat grid cut or setup inspection is needed." else "Ready to activate. Use the button within 90 seconds of the latest live grid evidence."
                    }
                }
                Text(nextStep, color = if (!unsaved && !loading && activationStage == PowerOceanActivationPolicy.Stage.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (sourceStore.powerOceanProfileVerified(saved)) {
                    SettingSwitch("Use my previous successful test", "Skip the setup live check for this same tested installation. EcoFlow decisions still need current live reports.", usePreviousTest, {
                        saved?.let { account -> sourceStore.setPowerOceanUsePreviousTest(account, it) }
                        usePreviousTest = it
                    })
                } else if (saved?.model == "86" && !unsaved) {
                    OutlinedButton({ confirmPreviousTest = true; confirmGridCorrelation = true }, enabled = !loading) { Text("I already tested this installation") }
                }
                if (!usePreviousTest && activationStage == PowerOceanActivationPolicy.Stage.LIVE_TEST_REQUIRED && session == null) {
                    Text("Use Connect and inspect live feed below. It connects your saved account if needed.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (accountActive) OutlinedButton({
                sourceStore.select(com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore.Source.ANDROID_CHARGER)
                selectedSource = sourceStore.selectedSource(); onPowerSourceChanged()
            }, enabled = !loading) { Text("Switch to charger monitoring") }
            else Button({
                if (sourceStore.select(com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore.Source.ECOFLOW_ACCOUNT)) {
                    selectedSource = sourceStore.selectedSource(); onPowerSourceChanged()
                    feedback = "PowerOcean selected. Use the Status master switch to start or stop monitoring."
                } else error = "Check the activation requirements above. Use your previous successful test or complete a new live check."
            }, enabled = !loading && !unsaved && activationStage == PowerOceanActivationPolicy.Stage.READY, modifier = Modifier) { Text(if (assistedSettings.enabled) "Use charger + EcoFlow assistance" else "Use PowerOcean for monitoring") }
            Text("Unofficial access is opt-in. Low traffic is not a guarantee against account restrictions. Missing or stale evidence stays Unknown.", style = MaterialTheme.typography.bodySmall)
        }
        ExpandableSettingsSection("Live-feed test", "Inspect saved account and fresh grid evidence", initiallyExpanded = !accountActive && !assistedSettings.enabled && activationStage != PowerOceanActivationPolicy.Stage.READY) {
        PowerSourceSectionTitle("Live push inspection")
        SettingsCard {
            Text("Try the faster account feed", fontWeight = FontWeight.Medium)
            Text("Inspect the saved account over secure MQTT. The button connects if needed and reuses this session for later inspections.", style = MaterialTheme.typography.bodySmall)
            Text("Inspection time", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(inspectionSeconds == 45, { inspectionSeconds = 45 }, label = { Text("45 seconds") }, enabled = !loading && !accountActive)
                FilterChip(inspectionSeconds == 300, { inspectionSeconds = 300 }, label = { Text("5 minutes") }, enabled = !loading && !accountActive)
            }
            FilterChip(inspectionSeconds == 900, { inspectionSeconds = 900 }, label = { Text("15 minutes · grid test") }, enabled = !loading && !accountActive)
            Text("45 seconds checks the live connection; no grid cut is needed. Unchanged values are normal at a steady load. Longer inspections are optional for a controlled outage test.", style = MaterialTheme.typography.bodySmall)
            if (assistedSettings.enabled) {
                Text("Live reporting is automatic", fontWeight = FontWeight.Medium)
                Text("Each charger-first check activates the live feed. This inspection also requests live reporting; EcoFlow's app can stay closed.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Setup inspections request temporary live reporting every 20 seconds until the test ends. Scheduled monitoring requests it once per check and closes the connection afterward. No power-control commands are sent.", style = MaterialTheme.typography.bodySmall)
            Text("Stops when you leave the dashboard or put the app in the background. If no readings arrive, the result explains what remains to investigate.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Use my tested grid/meter comparison", modifier = Modifier.weight(1f))
                Switch(useTestedGridCorrelation, {
                    if (it) { confirmPreviousTest = false; confirmGridCorrelation = true } else { useTestedGridCorrelation = false; usePreviousTest = false; saved?.let { account -> sourceStore.setPowerOceanProfileVerified(account, false) } }
                }, enabled = !loading && !accountActive && !unsaved && saved?.model == "86")
            }
            Text("Optional Single Phase inspection profile: code 0 means connected, code 1 means off-grid, and AC meter 1 loses power with the utility grid. Enable only after physically checking these facts on your installation. After verification and a fresh live-feed test, you can explicitly select this source for monitoring.", style = MaterialTheme.typography.bodySmall)
            pushFeedback?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            pushError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = {
                val account = saved ?: return@Button
                val requestedGeneration = generation
                autoRefresh = false; probeRunning = true; loading = true; error = null; feedback = null
                pushError = null; pushFeedback = "Requesting secure push access…"; gridInspection = null; lossConfirmation = null
                probeJob = scope.launch {
                    try {
                        val current = session?.takeIf { it.connection == account } ?: run {
                            pushFeedback = "Connecting saved account for the live test…"
                            when (val login = withContext(Dispatchers.IO) { client.login(account) }) {
                                is EcoFlowCloudClient.Result.Failure -> {
                                    if (requestedGeneration == generation) { pushError = login.message; pushFeedback = null }
                                    return@launch
                                }
                                is EcoFlowCloudClient.Result.Success -> login.value
                            }
                        }
                        if (requestedGeneration != generation) return@launch
                        session = current
                        pushFeedback = "Requesting secure push access…"
                        when (val result = withContext(Dispatchers.IO) { client.pushCredentials(current) }) {
                            is EcoFlowCloudClient.Result.Failure -> if (requestedGeneration == generation) { pushError = result.message; pushFeedback = null }
                            is EcoFlowCloudClient.Result.Success -> {
                                pushFeedback = "Inspecting push feed…"
                                if (requestedGeneration != generation) return@launch
                                val inspectionError = PowerOceanPushProbe(context.applicationContext).inspect(current, result.value, true, inspectionSeconds,
                                    correlationProfile = if (useTestedGridCorrelation) PowerOceanGridCorrelation.Profile() else null,
                                    requireChargerConfirmation = requireChargerConfirmation && !assistedSettings.enabled, readIntervalSeconds = current.connection.refreshSeconds.coerceAtLeast(60)) { update ->
                                    if (requestedGeneration == generation) {
                                        snapshot = update.snapshot; dashboardOpen = true
                                        pushStatus = "Push packets: ${update.packets} · unsupported: ${update.unsupported} · retained: ${update.retained}"
                                        gridInspection = update.gridInspection
                                        chargerPowered = update.chargerExternallyPowered
                                        lossConfirmation = update.confirmation; liveCheckStatus = update.liveCheck
                                        if (update.confirmation?.availability != com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability.UNKNOWN && update.confirmation != null) {
                                            sourceStore.recordPowerOceanLiveTest(current.connection)
                                        }
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
            }, enabled = saved != null && !unsaved && !loading && !accountActive, modifier = Modifier) {
                if (probeRunning) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                Text(if (probeRunning) "Opening and inspecting live feed…" else if (session == null) "Connect and inspect live feed" else "Inspect live push feed")
            }
        }
        if (saved != null) {
            OutlinedButton({ confirmClear = true }, enabled = !loading && !accountActive, modifier = Modifier) { Text("Remove saved account") }
        }
        }

        }
        SetupFlowFooter(setupSteps, setupStep, helpLevel.isGuided, loading, { setupStep = it }, onBack, finishEnabled = saved != null && !unsaved)

    }
    if (confirmGridCorrelation) AlertDialog(onDismissRequest = { confirmGridCorrelation = false },
        title = { Text("Have you tested this installation?") },
        text = { Text("Confirm an earlier test of this same installation; you do not need to repeat the grid cut. Use this comparison only if that test showed code 0 before the cut, code 1 while off-grid, code 0 after reconnection, and AC meter 1 losing power with the grid. A UPS-powered meter or different codes need a different profile. Zero meter flow alone cannot confirm an outage.") },
        confirmButton = { TextButton({ useTestedGridCorrelation = true; saved?.let { account -> sourceStore.setPowerOceanProfileVerified(account, true); sourceStore.setPowerOceanUsePreviousTest(account, confirmPreviousTest) }; usePreviousTest = confirmPreviousTest; confirmGridCorrelation = false; confirmPreviousTest = false }) { Text("I verified this") } },
        dismissButton = { TextButton({ confirmGridCorrelation = false }) { Text("Cancel") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Remove PowerOcean account?") },
        text = { Text("Removes the saved connection from this phone. Existing encrypted backups may still contain it.") },
        confirmButton = { TextButton({ store.clear(); generation++; saved = null; session = null; snapshot = null; email = ""; password = ""; serial = ""; feedback = null; error = null; confirmClear = false }) { Text("Remove") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
}
