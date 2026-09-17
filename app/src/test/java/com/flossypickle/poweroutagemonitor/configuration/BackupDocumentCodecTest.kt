package com.flossypickle.poweroutagemonitor.configuration

import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmEngine
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudClient
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupDocumentCodecTest {
    @Test fun remoteChoicesRoundTripAndOldBackupsCannotEnableControl() {
        val original = completeDocument()
        val config = com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore.Settings(
            true, setOf("1234"), false, 2, 30, false, 1_700_000_010_000)
        val configured = original.copy(alerts = original.alerts!!.copy(telegramRemote = config))
        val text = BackupDocumentCodec.encode(configured).toString(Charsets.UTF_8)
        assertEquals(configured, BackupDocumentCodec.decode(text.toByteArray()))
        val old = text.lineSequence().filterNot { it.startsWith("alerts.remote.") }.joinToString("\n")
        assertEquals(false, BackupDocumentCodec.decode(old.toByteArray()).alerts!!.telegramRemote.enabled)
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocumentCodec.decode(text.replace("alerts.remote.pollSeconds=2", "alerts.remote.pollSeconds=1").toByteArray())
        }
    }
    @Test
    fun allCategoriesAndSecretsRoundTrip() {
        val original = completeDocument()

        val restored = BackupDocumentCodec.decode(BackupDocumentCodec.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun appVersionCanBeEditedButInvalidDocumentIsRejected() {
        val text = BackupDocumentCodec.encode(completeDocument()).toString(Charsets.UTF_8)
        val edited = text.replace("appVersionName=1.0-test", "appVersionName=9.9-test")
        val parsed = BackupDocumentCodec.decode(edited.toByteArray())
        assertEquals("9.9-test", parsed.appVersionName)

        val invalid = edited.replace("settings.historyLimit=200", "settings.historyLimit=99999")
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocumentCodec.decode(invalid.toByteArray())
        }
    }

    @Test
    fun olderBackupsDefaultNewMonitoringChoicesToDisabled() {
        val text = BackupDocumentCodec.encode(completeDocument()).toString(Charsets.UTF_8)
        val old = text.lineSequence().filterNot { it.startsWith("power.account.warnUnchanged=") || it.startsWith("power.account.ignoreUnchanged=") || it.startsWith("power.account.previousTest=") || it.startsWith("power.account.profileVerified=") || it.startsWith("power.account.liveReporting=") || it.startsWith("power.account.chargerFirst=") || it.startsWith("power.account.normalSeconds=") || it.startsWith("power.account.outageSeconds=") }.joinToString("\n")
        val restored = BackupDocumentCodec.decode(old.toByteArray()).powerSources!!
        assertEquals(false, restored.powerOceanProfileVerified)
        assertEquals(false, restored.powerOceanUsePreviousTest)
        assertEquals(false, restored.powerOceanRequestLiveReporting)
        assertEquals(com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings(), restored.powerOceanAssisted)
    }

    @Test
    fun manualSchedulesRoundTripAndInvalidIntervalsAreRejected() {
        val document = completeDocument()
        val manual = document.copy(powerSources = document.powerSources!!.copy(
            powerOceanAssisted = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings(true, 0, 0)))
        assertEquals(manual, BackupDocumentCodec.decode(BackupDocumentCodec.encode(manual)))
        val text = BackupDocumentCodec.encode(manual).toString(Charsets.UTF_8)
        listOf("-1", "4", "86401", "not-a-number").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupDocumentCodec.decode(text.replace("power.account.normalSeconds=0", "power.account.normalSeconds=$bad").toByteArray())
            }
        }
    }

    @Test fun previousTestOverrideCannotRestoreAnUnverifiedProfile() {
        val text = BackupDocumentCodec.encode(completeDocument()).toString(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocumentCodec.decode(text.replace("power.account.profileVerified=true", "power.account.profileVerified=false").toByteArray())
        }
    }

    @Test fun checkLimitsRoundTripAndOlderArchivesUseSafeDefaults() {
        val original = completeDocument()
        val configured = original.copy(powerSources = original.powerSources!!.copy(
            powerOceanAssisted = original.powerSources.powerOceanAssisted.copy(checkWindowSeconds = 180, extraPowerUpdates = 4)))
        assertEquals(configured, BackupDocumentCodec.decode(BackupDocumentCodec.encode(configured)))
        val text = BackupDocumentCodec.encode(configured).toString(Charsets.UTF_8)
        val old = text.lineSequence().filterNot { it.startsWith("power.account.windowSeconds=") || it.startsWith("power.account.extraUpdates=") }.joinToString("\n")
        val settings = BackupDocumentCodec.decode(old.toByteArray()).powerSources!!.powerOceanAssisted
        assertEquals(120, settings.checkWindowSeconds); assertEquals(2, settings.extraPowerUpdates)
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocumentCodec.decode(text.replace("power.account.windowSeconds=180", "power.account.windowSeconds=301").toByteArray())
        }
    }

    private fun completeDocument() = BackupDocument(
        createdAtEpochMs = 1_700_000_000_000,
        appVersionName = "1.0-test",
        appVersionCode = 42,
        categories = BackupCategory.entries.toSet(),
        settings = BackupDocument.SettingsData(
            monitor = MonitorStore.Settings(
                setupCompleted = true,
                monitoringEnabled = false,
                outageDelayMs = 60_000,
                restoreDelayMs = 30_000,
                sendRestoreNotification = true,
                deviceName = "Kitchen monitor"
            ),
            scheduled = ScheduledAlertStore.Settings(),
            audible = AudibleAlarmStore.Settings(soundUri = "content://tones/alarm"),
            backupSchedule = BackupScheduleStore.Settings(
                enabled = true,
                folderUri = "content://provider/tree/backups",
                folderLabel = "Backups",
                intervalHours = 24,
                retainedCopies = 7,
                categories = BackupCategory.entries.toSet(),
                hasPassword = true
            ),
            backupSchedulePassword = "automatic-backup-password"
        ),
        alerts = BackupDocument.AlertsData(
            telegram = TelegramConfigStore.Config(
                enabled = true,
                botDisplayName = "Home bot",
                destinations = listOf(TelegramConfigStore.ChatDestination("1234", "Family")),
                hasToken = true
            ),
            telegramToken = "123456789:telegram-secret",
            gmail = GmailSmtpConfigStore.Config(true, "me@example.com", listOf("you@example.com"), true),
            gmailAppPassword = "gmail app password",
            resend = ResendEmailConfigStore.Config(true, "alerts@example.com", listOf("you@example.com"), true),
            resendApiKey = "re_test_secret",
            sms = SmsConfigStore.Config(true, listOf("+35699999999"))
        ),
        powerSources = BackupDocument.PowerSourcesData(
            selectedSource = PowerSourceStore.Source.ECOFLOW_MODBUS,
            modbus = PowerSourceStore.EcoFlowConfig("192.168.1.50", 502, 1),
            cloudCredentials = EcoFlowCloudClient.Credentials("access-key", "secret-key"),
            cloudSerialNumber = "SN123",
            cloudDeviceName = "PowerOcean",
            powerOceanAccount = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection(
                "owner@example.com", "account-password", "EXAMPLE-SERIAL", refreshSeconds = 20),
            powerOceanRequireChargerConfirmation = true,
            powerOceanProfileVerified = true,
            powerOceanUsePreviousTest = true,
            powerOceanRequestLiveReporting = true,
            powerOceanAssisted = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings(true, 3600, 60, false, true)
        ),
        history = BackupDocument.HistoryData(
            powerEvents = listOf(EventHistoryStore.Record("outage", 100, 200, 300, 90, 89)),
            operationalEvents = listOf(OperationalHistoryStore.Record("app_opened", 50, "Dashboard opened"))
        ),
        activeState = BackupDocument.ActiveStateData(
            monitoringWasEnabled = true,
            monitorState = OutageEngine.State(),
            lastSnapshot = null,
            lastObservationEpochMs = 400,
            scheduledState = ScheduledAlertStore.State(),
            audibleRuntime = AudibleAlarmEngine.Runtime(),
            deliveryQueue = emptyList(),
            pendingEvents = listOf(AlertMessage("event-1", AlertKind.OUTAGE, "Outage", "Grid failed"))
        )
    )
}
