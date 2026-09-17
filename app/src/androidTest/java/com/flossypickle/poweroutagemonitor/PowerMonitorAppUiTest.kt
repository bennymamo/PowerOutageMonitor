package com.flossypickle.poweroutagemonitor

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.diagnostics.SystemHealthSnapshot
import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertStore
import com.flossypickle.poweroutagemonitor.integrations.power.*
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.*
import com.flossypickle.poweroutagemonitor.ui.PowerMonitorApp
import com.flossypickle.poweroutagemonitor.ui.theme.PowerOutageMonitorTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Runs real app navigation with isolated preferences: no account login, messages or monitoring changes. */
class PowerMonitorAppUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var captureNumber = 0
    private val qaContext by lazy { QaContext(compose.activity) }

    private class QaContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("ui_qa_$name", mode)
        override fun getFilesDir(): File = File(super.getFilesDir(), "ui_qa").apply { mkdirs() }
    }

    private fun launch(assisted: Boolean = false, dark: Boolean = true) {
        qaContext.getSharedPreferences("power_sources", 0).edit().clear().commit()
        if (assisted) PowerSourceStore(qaContext).setPowerOceanAssistedSettings(PowerOceanAssistedSettings(enabled = true))
        val now = System.currentTimeMillis()
        val settings = MonitorStore.Settings(true, false, 30_000, 30_000, true, "Sample grid monitor")
        val check = PowerSourceCheck(now - 120_000, now - 60_000, true,
            observations = listOf(SourceReportedValue("Reported grid code", "0", "Connected in the tested profile.", now - 60_000, true),
                SourceReportedValue("Meter 1 reading", "12.5", "Reported meter activity; zero alone is not an outage.", now - 60_000, true)),
            cycleState = PowerSourceCheck.CycleState.WAITING, finishedAtEpochMs = now - 59_000,
            nextCheckAtEpochMs = now + 3_480_000, deviceUpdates = 5, powerUpdates = 3, valuesChanged = true)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides qaContext) {
                PowerOutageMonitorTheme(darkTheme = dark) {
                    PowerMonitorApp(snapshot = PowerSnapshot(2, 88, 2, 250), monitorState = OutageEngine.State(), settings = settings,
                        history = listOf(EventHistoryStore.Record("outage", now - 600_000, now - 570_000, now - 480_000, 90, 89, 240, 250)),
                        operationalHistory = listOf(OperationalHistoryStore.Record(OperationalHistoryStore.KIND_MONITORING_RECOVERED, now - 300_000, "Sample interruption detail")),
                        audibleSettings = AudibleAlarmStore.Settings(), audibleAlarmActive = false, exactAlarmAccessGranted = false,
                        lastObservationEpochMs = now, deliveryWarning = null, alertChannels = "Not configured", hasEnabledAlertChannel = false,
                        hasSentTestAlert = false, systemHealth = SystemHealthSnapshot(internetAvailable = true),
                        selectedPowerSource = if (assisted) PowerSourceStore.Source.ECOFLOW_ACCOUNT else PowerSourceStore.Source.ANDROID_CHARGER,
                        powerSourceStatus = if (assisted) PowerSourceStore.Status(PowerSourceStore.Source.ECOFLOW_ACCOUNT, GridAvailability.AVAILABLE, now,
                            "Sample grid reading", check = check) else null,
                        scheduledAlertSettings = ScheduledAlertStore.Settings(), scheduledAlertState = ScheduledAlertStore.State(), deliverySummaries = emptyMap(),
                        onMonitoringEnabledChange = {}, onSettingsChange = { _, _, _, _ -> }, onCompleteSetup = { _, _, _, _ -> },
                        onRetryFailedDeliveries = {}, onClearDeliveryRecords = {}, onHistoryLimitChange = {}, onThemeModeChange = {},
                        onHelpLevelChange = {}, onBatteryLowAlertChange = { _, _ -> }, onScheduledAlertSettingsChange = {}, onAudibleSettingsChange = {},
                        onDismissAudibleAlarm = {}, onTestAudibleAlarm = {}, onPowerSourceChanged = {}, onClearHistory = {},
                        onBackupRestore = { _, _, _ -> null }, onSendTestAlert = { false }, onAlertConfigurationChanged = {})
                }
            }
        }
    }
    private fun nav(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()
    private fun click(label: String) = compose.onAllNodes(hasText(label) and hasClickAction())[0].performScrollTo().performClick()
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun title(label: String) = compose.onAllNodes(hasText(label) and !hasClickAction())[0].assertIsDisplayed()
    private fun capture(label: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Old emulator surfaces draw asynchronously after Compose becomes idle.
        Thread.sleep(120)
        saveScreen(label)
        val scrolls = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        if (scrolls.size == 1) {
            compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle(); Thread.sleep(120); saveScreen("$label-bottom")
            compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
            compose.waitForIdle()
        }
    }
    private fun saveScreen(label: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val folder = File(compose.activity.getExternalFilesDir("ui-check"), "all-screens").apply { mkdirs() }
        File(folder, "%03d-%s.png".format(++captureNumber, label.replace(Regex("[^a-zA-Z0-9-]"), "-"))).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun dashboardShortcutsReturnDirectlyButSettingsNavigationKeepsParents() {
        launch(assisted = true)
        click("Grid source"); title("Power sources"); back(); title("Grid outage monitor")
        click("Connections & alerts"); click("Alert channels"); title("Alert channels"); back(); title("Grid outage monitor")
        click("EcoFlow checks"); title("PowerOcean account")
        click("Jump to a step"); click("5. Verify and monitor"); back(); title("Grid outage monitor")
        nav("Settings"); click("Monitoring"); click("Outage timing"); title("Outage timing")
        back(); title("Monitoring"); back(); title("Settings"); back(); title("Grid outage monitor")
        nav("Settings"); click("Power sources"); title("Power sources"); back(); title("Settings"); back(); title("Grid outage monitor")
    }

    @Test fun allSettingsGroupsSubpagesProviderStepsAndHistoryAreReachable() {
        launch()
        capture("status-dark")
        val groups = linkedMapOf(
            "Monitoring" to listOf("Outage timing", "Restoration", "Battery alerts", "Scheduled updates", "Reliability"),
            "Alerts & sound" to listOf("Alert channels", "Audible alarm"),
            "Data & recovery" to listOf("History", "Create encrypted backup", "Automatic backups", "Restore backup"),
            "Appearance & device" to listOf("Device", "Appearance", "Help & guidance"),
            "Help & app" to listOf("Setup & testing", "Safety & privacy", "About"))
        for ((group, pages) in groups) {
            nav("Settings"); click(group); capture(group)
            for (page in pages) {
                click(page); title(if (page == "Create encrypted backup") "Create backup" else page); capture(page)
                when (page) {
                    "Scheduled updates" -> for (label in listOf("Source unavailable", "Monitor heartbeat", "Long outage updates")) {
                        click(label); capture(label); click(label)
                    }
                    "Audible alarm" -> for (label in listOf("Repeats and timing", "Battery and volume")) { click(label); capture(label); click(label) }
                    "Create encrypted backup" -> { click("Included data"); capture("backup-included-data") }
                    "Automatic backups" -> for (label in listOf("Frequency & copies", "Included data", "Backup password")) { click(label); capture("automatic-$label"); click(label) }
                }
                back(); title(group)
            }
        }
        nav("Settings"); click("Power sources"); capture("power-sources")
        click("Set up PowerOcean account"); visitSteps(listOf("Before you begin", "Account login", "Your equipment", "Save and connect", "Verify and monitor"), "powerocean")
        nav("Settings"); click("Power sources"); click("Other EcoFlow connections"); capture("other-connections")
        click("Set up local connection"); visitSteps(listOf("Prepare your network", "Inverter address", "Save, test and select"), "local")
        nav("Settings"); click("Power sources"); click("Other EcoFlow connections"); click("Set up EcoFlow Cloud")
        visitSteps(listOf("Prepare developer access", "Save API keys", "Find and test devices"), "developer-preview")
        nav("Settings"); click("Alerts & sound"); click("Alert channels"); click("Configure Telegram")
        visitSteps(listOf("Create your bot", "Connect your bot", "Choose recipients", "Save and test"), "telegram")
        nav("Settings"); click("Alerts & sound"); click("Alert channels"); click("Configure device SMS")
        visitSteps(listOf("Check your phone", "Choose recipients", "Save and test"), "sms")
        nav("Settings"); click("Alerts & sound"); click("Alert channels"); click("Configure email"); capture("email-providers")
        click("Configure Gmail"); visitSteps(listOf("Prepare Google account", "Sender account", "Choose recipients", "Save and test"), "gmail")
        nav("Settings"); click("Alerts & sound"); click("Alert channels"); click("Configure email"); click("Configure Resend")
        visitSteps(listOf("Prepare Resend", "Sender and recipients", "Save and test"), "resend")
        nav("Settings"); click("Help & app"); click("Setup & testing"); click("Open diagnostics"); title("Diagnostics"); capture("diagnostics")
        for (label in listOf("Delivery readiness", "Background reliability", "Local audible alarm", "Recovery backups", "Device")) { click(label); capture("diagnostics-$label"); click(label) }
        nav("Settings"); click("Help & app"); click("Setup & testing"); click("Open setup checklist"); title("Setup checklist"); capture("checklist")
        nav("Settings"); click("Help & app"); click("Setup & testing"); click("Open test mode"); title("Test mode"); capture("test-mode")
        nav("History"); capture("history-all")
        nav("Grid"); capture("history-grid"); compose.onNodeWithText("Details").performClick(); capture("history-grid-details")
        nav("App & monitor"); capture("history-app"); compose.onNodeWithText("Unrecorded monitoring interruption").assertExists()
        compose.onNodeWithText("Details").performClick(); compose.onNodeWithText("Sample interruption detail").assertExists(); capture("history-app-details")
        nav("Status"); title("Grid outage monitor")
    }
    private fun visitSteps(steps: List<String>, prefix: String) {
        for ((index, label) in steps.withIndex()) {
            if (compose.onAllNodes(hasText("${index + 1}. $label") and hasClickAction()).fetchSemanticsNodes().isEmpty()) click("Jump to a step")
            click("${index + 1}. $label"); capture("$prefix-step-${index + 1}")
        }
    }

    @Test fun lightDashboardShowsCycleStatsWithoutStartingAnyCheck() {
        launch(assisted = true, dark = false); capture("status-light")
        click("EcoFlow readings"); compose.onNodeWithText("5 total · 3 power reports").assertExists()
        compose.onNodeWithText("Power values changing").assertExists(); capture("ecoflow-expanded-light")
        click("How to read this check"); capture("check-explanation-light")
    }
}
