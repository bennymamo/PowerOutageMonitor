package com.flossypickle.poweroutagemonitor.configuration

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryScheduler
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudClient
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudConfigStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountStore
import com.flossypickle.poweroutagemonitor.storage.AlertQueueStore
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore
import com.flossypickle.poweroutagemonitor.storage.PendingAlertEventStore

/** Captures and restores typed app data; the outer archive is always password encrypted. */
internal class BackupManager(context: Context) {
    private val appContext = context.applicationContext

    fun create(categories: Set<BackupCategory>, password: CharArray): ByteArray {
        require(categories.isNotEmpty()) { "Select at least one backup category." }
        val document = capture(categories)
        val plaintext = BackupDocumentCodec.encode(document)
        return try {
            PasswordBackupCipher.encrypt(plaintext, password)
        } finally {
            plaintext.fill(0)
        }
    }

    fun open(encrypted: ByteArray, password: CharArray): BackupDocument {
        val plaintext = PasswordBackupCipher.decrypt(encrypted, password)
        return try {
            BackupDocumentCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun editableText(document: BackupDocument): String =
        BackupDocumentCodec.encode(document).toString(Charsets.UTF_8)

    /** Validates edited plain text, normalizes it, and creates a new authenticated archive. */
    fun createEdited(text: String, password: CharArray): ByteArray {
        val edited = text.toByteArray(Charsets.UTF_8)
        require(edited.size <= PasswordBackupCipher.MAX_BACKUP_BYTES) {
            "Edited backup contents are too large."
        }
        return try {
            val validated = BackupDocumentCodec.decode(edited)
            val normalized = BackupDocumentCodec.encode(validated)
            try {
                PasswordBackupCipher.encrypt(normalized, password)
            } finally {
                normalized.fill(0)
            }
        } finally {
            edited.fill(0)
        }
    }

    fun restore(
        document: BackupDocument,
        categories: Set<BackupCategory>,
        resumeMonitoring: Boolean
    ) {
        require(categories.isNotEmpty() && document.categories.containsAll(categories))
        val currentMonitor = MonitorStore(appContext)
        require(!currentMonitor.settings().monitoringEnabled) {
            "Turn off monitoring before restoring a backup."
        }

        if (BackupCategory.SETTINGS in categories) {
            val data = requireNotNull(document.settings)
            val m = data.monitor
            currentMonitor.updateSettings(
                outageDelayMs = m.outageDelayMs,
                restoreDelayMs = m.restoreDelayMs,
                sendRestoreNotification = m.sendRestoreNotification,
                deviceName = m.deviceName
            )
            if (m.setupCompleted) currentMonitor.setSetupCompleted()
            currentMonitor.setHistoryLimit(m.historyLimit)
            currentMonitor.setThemeMode(m.themeMode)
            currentMonitor.setHelpLevel(m.helpLevel)
            currentMonitor.setBatteryLowAlert(
                m.batteryLowAlertEnabled,
                m.batteryLowAlertThreshold
            )
            ScheduledAlertStore(appContext).updateSettings(data.scheduled)
            AudibleAlarmStore(appContext).updateSettings(data.audible)
            BackupScheduleStore(appContext).restorePortable(
                data.backupSchedule,
                data.backupSchedulePassword
            )
            BackupScheduler(appContext).apply(BackupScheduleStore(appContext).settings())
        }

        if (BackupCategory.ALERTS in categories) restoreAlerts(requireNotNull(document.alerts), resumeMonitoring)
        if (BackupCategory.POWER_SOURCES in categories) {
            restorePowerSources(requireNotNull(document.powerSources))
        }
        if (BackupCategory.HISTORY in categories) {
            val data = requireNotNull(document.history)
            val limit = currentMonitor.settings().historyLimit
            EventHistoryStore(appContext).replaceAll(data.powerEvents, limit)
            OperationalHistoryStore(appContext).replaceAll(data.operationalEvents, limit)
        }
        if (BackupCategory.ACTIVE_STATE in categories) {
            val data = requireNotNull(document.activeState)
            val shouldResume = resumeMonitoring && data.monitoringWasEnabled
            AlertDeliveryScheduler(appContext).cancelAll()
            currentMonitor.restoreRuntime(
                state = data.monitorState,
                snapshot = data.lastSnapshot,
                observedAtEpochMs = data.lastObservationEpochMs,
                monitoringEnabled = shouldResume
            )
            ScheduledAlertStore(appContext).save(data.scheduledState)
            AudibleAlarmStore(appContext).saveRuntime(data.audibleRuntime)
            AlertQueueStore(appContext).replaceAll(data.deliveryQueue)
            PendingAlertEventStore(appContext).replaceAll(data.pendingEvents)
            currentMonitor.setRestoredDeliveriesPaused(!shouldResume)
        }
        OperationalHistoryStore(appContext).recordBackupRestored(
            currentMonitor.settings().historyLimit
        )
    }

    private fun capture(categories: Set<BackupCategory>): BackupDocument {
        val monitor = MonitorStore(appContext)
        val scheduled = ScheduledAlertStore(appContext)
        val audible = AudibleAlarmStore(appContext)
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return BackupDocument(
            createdAtEpochMs = System.currentTimeMillis(),
            appVersionName = packageInfo.versionName ?: "unknown",
            appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            categories = categories,
            settings = if (BackupCategory.SETTINGS in categories) {
                val backupSchedule = BackupScheduleStore(appContext)
                BackupDocument.SettingsData(
                    monitor.settings(),
                    scheduled.settings(),
                    audible.settings(),
                    backupSchedule.settings(),
                    backupSchedule.password()
                )
            } else null,
            alerts = if (BackupCategory.ALERTS in categories) captureAlerts() else null,
            powerSources = if (BackupCategory.POWER_SOURCES in categories) capturePowerSources() else null,
            history = if (BackupCategory.HISTORY in categories) {
                BackupDocument.HistoryData(
                    EventHistoryStore(appContext).read(),
                    OperationalHistoryStore(appContext).read()
                )
            } else null,
            activeState = if (BackupCategory.ACTIVE_STATE in categories) {
                BackupDocument.ActiveStateData(
                    monitoringWasEnabled = monitor.settings().monitoringEnabled ||
                        monitor.restoredDeliveriesPaused(),
                    monitorState = monitor.state(),
                    lastSnapshot = monitor.lastSnapshot(),
                    lastObservationEpochMs = monitor.lastObservationEpochMs(),
                    scheduledState = scheduled.state(),
                    audibleRuntime = audible.runtime(),
                    deliveryQueue = AlertQueueStore(appContext).read(),
                    pendingEvents = PendingAlertEventStore(appContext).read()
                )
            } else null
        )
    }

    private fun captureAlerts(): BackupDocument.AlertsData {
        val telegram = TelegramConfigStore(appContext)
        val gmail = GmailSmtpConfigStore(appContext)
        val resend = ResendEmailConfigStore(appContext)
        val sms = SmsConfigStore(appContext)
        return BackupDocument.AlertsData(
            telegram = telegram.config(),
            telegramToken = telegram.botToken(),
            gmail = gmail.config(),
            gmailAppPassword = gmail.appPassword(),
            resend = resend.config(),
            resendApiKey = resend.apiKey(),
            sms = sms.config(),
            telegramRemote = com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore(appContext).settings()
        )
    }

    private fun restoreAlerts(data: BackupDocument.AlertsData, resumeMonitoring: Boolean) {
        com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore(appContext).apply {
            save(data.telegramRemote.copy(enabled = data.telegramRemote.enabled && resumeMonitoring)); resetCheckpoint()
        }
        TelegramConfigStore(appContext).apply {
            clear()
            save(data.telegramToken, data.telegram.enabled, data.telegram.botDisplayName,
                data.telegram.destinations)
        }
        GmailSmtpConfigStore(appContext).apply {
            clear()
            save(data.gmailAppPassword, data.gmail.enabled, data.gmail.account, data.gmail.recipients)
        }
        ResendEmailConfigStore(appContext).apply {
            clear()
            save(data.resendApiKey, data.resend.enabled, data.resend.sender, data.resend.recipients)
        }
        SmsConfigStore(appContext).apply {
            clear()
            save(data.sms.enabled, data.sms.recipients)
        }
    }

    private fun capturePowerSources(): BackupDocument.PowerSourcesData {
        val local = PowerSourceStore(appContext)
        val cloud = EcoFlowCloudConfigStore(appContext)
        val cloudConfig = cloud.config()
        return BackupDocument.PowerSourcesData(
            selectedSource = local.selectedSource(),
            modbus = local.ecoFlowConfig(),
            cloudCredentials = cloud.credentials(),
            cloudSerialNumber = cloudConfig.selectedSerialNumber,
            cloudDeviceName = cloudConfig.selectedDeviceName,
            powerOceanAccount = PowerOceanAccountStore(appContext).connection(),
            powerOceanRequireChargerConfirmation = local.powerOceanRequiresChargerConfirmation(),
            powerOceanProfileVerified = local.powerOceanProfileVerified(PowerOceanAccountStore(appContext).connection()),
            powerOceanRequestLiveReporting = local.powerOceanRequestsLiveReporting(),
            powerOceanAssisted = local.powerOceanAssistedSettings(),
            powerOceanUsePreviousTest = local.powerOceanUsesPreviousTest(PowerOceanAccountStore(appContext).connection())
        )
    }

    private fun restorePowerSources(data: BackupDocument.PowerSourcesData) {
        // Validate the portable account before mutating any power-source settings.
        val account = data.powerOceanAccount?.also { require(it.isValid) { "Invalid PowerOcean account connection in backup." } }
        PowerOceanAccountStore(appContext).apply { clear(); account?.let(::save) }
        PowerSourceStore(appContext).restoreEcoFlowConfig(data.modbus)
        PowerSourceStore(appContext).apply {
            setPowerOceanChargerConfirmation(data.powerOceanRequireChargerConfirmation)
            setPowerOceanLiveReporting(data.powerOceanRequestLiveReporting)
            setPowerOceanAssistedSettings(data.powerOceanAssisted)
            if (account?.model == "86") {
                setPowerOceanProfileVerified(account, data.powerOceanProfileVerified)
                setPowerOceanUsePreviousTest(account, data.powerOceanUsePreviousTest)
            }
        }
        EcoFlowCloudConfigStore(appContext).apply {
            clear()
            data.cloudCredentials?.let(::saveCredentials)
            val serial = data.cloudSerialNumber
            if (serial != null) {
                selectDevice(
                    EcoFlowCloudClient.Device(
                        serialNumber = serial,
                        name = data.cloudDeviceName ?: "EcoFlow device",
                        online = false
                    )
                )
            }
        }
        // A restored local source always requires a fresh read-only hardware test.
        // The backup retains the previous selection only for the restore summary.
    }
}
