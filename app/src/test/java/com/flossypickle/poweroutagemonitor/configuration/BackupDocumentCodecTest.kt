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
        val old = text.lineSequence().filterNot { it.startsWith("power.account.profileVerified=") || it.startsWith("power.account.liveReporting=") }.joinToString("\n")
        val restored = BackupDocumentCodec.decode(old.toByteArray()).powerSources!!
        assertEquals(false, restored.powerOceanProfileVerified)
        assertEquals(false, restored.powerOceanRequestLiveReporting)
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
            powerOceanRequestLiveReporting = true
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
