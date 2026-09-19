package com.flossypickle.poweroutagemonitor.configuration

import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmEngine
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertQueueEngine
import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudClient
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

internal enum class BackupCategory(val title: String) {
    SETTINGS("App settings"),
    ALERTS("Alert channels and keys"),
    POWER_SOURCES("Power sources"),
    HISTORY("History"),
    ACTIVE_STATE("Live state and pending alerts")
}

internal data class BackupDocument(
    val createdAtEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val categories: Set<BackupCategory>,
    val settings: SettingsData? = null,
    val alerts: AlertsData? = null,
    val powerSources: PowerSourcesData? = null,
    val history: HistoryData? = null,
    val activeState: ActiveStateData? = null
) {
    data class SettingsData(
        val monitor: MonitorStore.Settings,
        val scheduled: ScheduledAlertStore.Settings,
        val audible: AudibleAlarmStore.Settings,
        val backupSchedule: BackupScheduleStore.Settings,
        val backupSchedulePassword: String?
    )

    data class AlertsData(
        val telegram: TelegramConfigStore.Config,
        val telegramToken: String?,
        val gmail: GmailSmtpConfigStore.Config,
        val gmailAppPassword: String?,
        val resend: ResendEmailConfigStore.Config,
        val resendApiKey: String?,
        val sms: SmsConfigStore.Config,
        val telegramRemote: TelegramRemoteStore.Settings = TelegramRemoteStore.Settings()
    )

    data class PowerSourcesData(
        val selectedSource: PowerSourceStore.Source,
        val modbus: PowerSourceStore.EcoFlowConfig,
        val cloudCredentials: EcoFlowCloudClient.Credentials?,
        val cloudSerialNumber: String?,
        val cloudDeviceName: String?,
        val powerOceanAccount: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection? = null,
        val powerOceanRequireChargerConfirmation: Boolean = false,
        val powerOceanProfileVerified: Boolean = false,
        val powerOceanRequestLiveReporting: Boolean = false,
        val powerOceanAssisted: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings(),
        val powerOceanUsePreviousTest: Boolean = false
    ) {
        init { require(!powerOceanUsePreviousTest || (powerOceanProfileVerified && powerOceanAccount?.isValid == true && powerOceanAccount.model == "86")) { "Previous-test choice requires a valid verified Single Phase account" } }
    }

    data class HistoryData(
        val powerEvents: List<EventHistoryStore.Record>,
        val operationalEvents: List<OperationalHistoryStore.Record>
    )

    data class ActiveStateData(
        val monitoringWasEnabled: Boolean,
        val monitorState: OutageEngine.State,
        val lastSnapshot: PowerSnapshot?,
        val lastObservationEpochMs: Long,
        val scheduledState: ScheduledAlertStore.State,
        val audibleRuntime: AudibleAlarmEngine.Runtime,
        val deliveryQueue: List<AlertQueueEngine.Item>,
        val pendingEvents: List<AlertMessage>
    )
}

internal object BackupDocumentCodec {
    private const val FORMAT = "fp-grid-monitor-backup"
    private const val VERSION = 1
    private const val MAX_ITEMS = 1_000

    fun encode(document: BackupDocument): ByteArray {
        val p = Properties()
        p["format"] = FORMAT
        p["version"] = VERSION.toString()
        p["createdAt"] = document.createdAtEpochMs.toString()
        p["appVersionName"] = document.appVersionName
        p["appVersionCode"] = document.appVersionCode.toString()
        p["categories"] = document.categories.joinToString(",", transform = BackupCategory::name)
        document.settings?.let { writeSettings(p, it) }
        document.alerts?.let { writeAlerts(p, it) }
        document.powerSources?.let { writePowerSources(p, it) }
        document.history?.let { writeHistory(p, it) }
        document.activeState?.let { writeActiveState(p, it) }
        return StringWriter().use { writer ->
            p.store(writer, "Flockle Grid Outage Monitor encrypted backup payload")
            writer.toString().toByteArray(Charsets.UTF_8)
        }
    }

    fun decode(bytes: ByteArray): BackupDocument {
        require(bytes.size <= PasswordBackupCipher.MAX_BACKUP_BYTES) { "Backup contents are too large." }
        val p = Properties().apply {
            StringReader(bytes.toString(Charsets.UTF_8)).use(::load)
        }
        require(p.required("format") == FORMAT) { "This is not an Flockle Grid Outage Monitor backup." }
        require(p.int("version") == VERSION) { "This backup version is not supported." }
        val categories = p.required("categories").split(',').filter(String::isNotBlank)
            .mapTo(linkedSetOf()) { value ->
                BackupCategory.entries.firstOrNull { it.name == value }
                    ?: throw IllegalArgumentException("Backup contains an unknown data category.")
            }
        require(categories.isNotEmpty()) { "The backup does not contain any selected data." }
        return BackupDocument(
            createdAtEpochMs = p.long("createdAt").coerceAtLeast(0),
            appVersionName = p.required("appVersionName").also {
                require(it.length <= 100) { "Invalid app version in backup." }
            },
            appVersionCode = p.long("appVersionCode").coerceAtLeast(0),
            categories = categories,
            settings = if (BackupCategory.SETTINGS in categories) readSettings(p) else null,
            alerts = if (BackupCategory.ALERTS in categories) readAlerts(p) else null,
            powerSources = if (BackupCategory.POWER_SOURCES in categories) readPowerSources(p) else null,
            history = if (BackupCategory.HISTORY in categories) readHistory(p) else null,
            activeState = if (BackupCategory.ACTIVE_STATE in categories) readActiveState(p) else null
        )
    }

    private fun writeSettings(p: Properties, data: BackupDocument.SettingsData) {
        val m = data.monitor
        p["settings.setupCompleted"] = m.setupCompleted.toString()
        p["settings.deviceName"] = m.deviceName
        p["settings.outageDelay"] = m.outageDelayMs.toString()
        p["settings.restoreDelay"] = m.restoreDelayMs.toString()
        p["settings.sendRestore"] = m.sendRestoreNotification.toString()
        p["settings.historyLimit"] = m.historyLimit.toString()
        p["settings.theme"] = m.themeMode.name
        p["settings.help"] = m.helpLevel.name
        p["settings.batteryAlert"] = m.batteryLowAlertEnabled.toString()
        p["settings.batteryThreshold"] = m.batteryLowAlertThreshold.toString()
        val s = data.scheduled
        p["settings.sourceAlert"] = s.sourceUnavailableEnabled.toString()
        p["settings.sourceDelay"] = s.sourceUnavailableDelayMs.toString()
        p["settings.heartbeat"] = s.heartbeatEnabled.toString()
        p["settings.heartbeatInterval"] = s.heartbeatIntervalMs.toString()
        p["settings.outageUpdates"] = s.outageUpdatesEnabled.toString()
        p["settings.outageUpdateInterval"] = s.outageUpdateIntervalMs.toString()
        val a = data.audible
        p["settings.audible"] = a.enabled.toString()
        p["settings.audibleRepeat"] = a.repeatIntervalMs.toString()
        p["settings.audibleStopBattery"] = a.stopBatteryPercent.toString()
        p["settings.audibleMaximumVolume"] = a.useMaximumVolume.toString()
        p["settings.audibleSchedule"] = a.scheduleMode.name
        p.putOptional("settings.audibleSound", a.soundUri)
        val b = data.backupSchedule
        p["settings.backupEnabled"] = b.enabled.toString()
        p.putOptional("settings.backupFolder", b.folderUri)
        p.putOptional("settings.backupFolderLabel", b.folderLabel)
        p["settings.backupInterval"] = b.intervalHours.toString()
        p["settings.backupRetained"] = b.retainedCopies.toString()
        p["settings.backupCategories"] = b.categories.joinToString(",", transform = BackupCategory::name)
        p.putOptional("settings.backupPassword", data.backupSchedulePassword)
    }

    private fun readSettings(p: Properties): BackupDocument.SettingsData {
        val historyLimit = p.int("settings.historyLimit")
        require(historyLimit in MonitorStore.HISTORY_LIMIT_RANGE) { "Invalid history size in backup." }
        val batteryThreshold = p.int("settings.batteryThreshold")
        require(batteryThreshold in MonitorStore.BATTERY_LOW_THRESHOLD_RANGE) {
            "Invalid battery warning in backup."
        }
        val monitor = MonitorStore.Settings(
            setupCompleted = p.boolean("settings.setupCompleted"),
            monitoringEnabled = false,
            outageDelayMs = p.long("settings.outageDelay").validDelay(),
            restoreDelayMs = p.long("settings.restoreDelay").validDelay(),
            sendRestoreNotification = p.boolean("settings.sendRestore"),
            deviceName = p.required("settings.deviceName").also {
                require(it.length <= 50) { "Invalid device name in backup." }
            },
            historyLimit = historyLimit,
            themeMode = p.enumValue("settings.theme"),
            helpLevel = p.enumValue("settings.help"),
            batteryLowAlertEnabled = p.boolean("settings.batteryAlert"),
            batteryLowAlertThreshold = batteryThreshold
        )
        val scheduled = ScheduledAlertStore.Settings(
            sourceUnavailableEnabled = p.boolean("settings.sourceAlert"),
            sourceUnavailableDelayMs = p.long("settings.sourceDelay").validScheduledInterval(),
            heartbeatEnabled = p.boolean("settings.heartbeat"),
            heartbeatIntervalMs = p.long("settings.heartbeatInterval").validScheduledInterval(),
            outageUpdatesEnabled = p.boolean("settings.outageUpdates"),
            outageUpdateIntervalMs = p.long("settings.outageUpdateInterval").validScheduledInterval()
        )
        val audible = AudibleAlarmStore.Settings(
            enabled = p.boolean("settings.audible"),
            repeatIntervalMs = p.long("settings.audibleRepeat").also {
                require(it in AudibleAlarmStore.MIN_REPEAT_INTERVAL_MS..30L * 24 * 60 * 60_000L) {
                    "Invalid audible alarm interval in backup."
                }
            },
            stopBatteryPercent = p.int("settings.audibleStopBattery").also {
                require(it in 1..99) { "Invalid audible alarm battery limit in backup." }
            },
            useMaximumVolume = p.boolean("settings.audibleMaximumVolume"),
            scheduleMode = p.enumValue("settings.audibleSchedule"),
            soundUri = p.optional("settings.audibleSound")
        )
        val backupCategories = p.required("settings.backupCategories")
            .split(',')
            .filter(String::isNotBlank)
            .mapTo(linkedSetOf()) { name ->
                BackupCategory.entries.firstOrNull { it.name == name }
                    ?: throw IllegalArgumentException("Invalid automatic backup category.")
            }
        require(backupCategories.isNotEmpty()) { "Automatic backup has no data categories." }
        val backupInterval = p.long("settings.backupInterval")
        require(backupInterval in BackupScheduleStore.ALLOWED_INTERVAL_HOURS) {
            "Invalid automatic backup interval."
        }
        val retained = p.int("settings.backupRetained")
        require(retained in BackupScheduleStore.ALLOWED_RETAINED_COPIES) {
            "Invalid automatic backup retention."
        }
        val backupPassword = p.optional("settings.backupPassword")
        require(backupPassword == null || backupPassword.length >= PasswordBackupCipher.MIN_PASSWORD_LENGTH) {
            "Invalid automatic backup password."
        }
        val backupSchedule = BackupScheduleStore.Settings(
            enabled = p.boolean("settings.backupEnabled"),
            folderUri = p.optional("settings.backupFolder"),
            folderLabel = p.optional("settings.backupFolderLabel"),
            intervalHours = backupInterval,
            retainedCopies = retained,
            categories = backupCategories,
            hasPassword = backupPassword != null
        )
        return BackupDocument.SettingsData(
            monitor,
            scheduled,
            audible,
            backupSchedule,
            backupPassword
        )
    }

    private fun writeAlerts(p: Properties, data: BackupDocument.AlertsData) {
        val remote = data.telegramRemote
        p["alerts.remote.enabled"] = remote.enabled.toString()
        p.writeStrings("alerts.remote.trusted", remote.trustedChatIds.sorted())
        p["alerts.remote.longPoll"] = remote.longPolling.toString()
        p["alerts.remote.pollSeconds"] = remote.pollSeconds.toString()
        p["alerts.remote.quietMinutes"] = remote.quietMinutes.toString()
        p["alerts.remote.quietUntil"] = remote.quietUntilEpochMs.toString()
        p["alerts.remote.checkWarnings"] = remote.checkWarnings.toString()
        p["alerts.telegram.enabled"] = data.telegram.enabled.toString()
        p.putOptional("alerts.telegram.name", data.telegram.botDisplayName)
        p.putOptional("alerts.telegram.token", data.telegramToken)
        p.writeList("alerts.telegram.destinations", data.telegram.destinations) { prefix, item ->
            p["$prefix.id"] = item.chatId
            p["$prefix.label"] = item.label
        }
        p["alerts.gmail.enabled"] = data.gmail.enabled.toString()
        p["alerts.gmail.account"] = data.gmail.account
        p.putOptional("alerts.gmail.password", data.gmailAppPassword)
        p.writeStrings("alerts.gmail.recipients", data.gmail.recipients)
        p["alerts.resend.enabled"] = data.resend.enabled.toString()
        p["alerts.resend.sender"] = data.resend.sender
        p.putOptional("alerts.resend.key", data.resendApiKey)
        p.writeStrings("alerts.resend.recipients", data.resend.recipients)
        p["alerts.sms.enabled"] = data.sms.enabled.toString()
        p.writeStrings("alerts.sms.recipients", data.sms.recipients)
    }

    private fun readAlerts(p: Properties) = BackupDocument.AlertsData(
        telegram = TelegramConfigStore.Config(
            enabled = p.boolean("alerts.telegram.enabled"),
            botDisplayName = p.optional("alerts.telegram.name"),
            destinations = p.readList("alerts.telegram.destinations") { prefix ->
                TelegramConfigStore.ChatDestination(p.required("$prefix.id"), p.required("$prefix.label"))
            },
            hasToken = p.optional("alerts.telegram.token") != null
        ),
        telegramRemote = if (p.containsKey("alerts.remote.enabled")) TelegramRemoteStore.Settings(
            p.boolean("alerts.remote.enabled"), p.readStrings("alerts.remote.trusted").toSet(),
            p.boolean("alerts.remote.longPoll"), p.int("alerts.remote.pollSeconds"),
            p.int("alerts.remote.quietMinutes"), p.boolean("alerts.remote.checkWarnings"),
            p.long("alerts.remote.quietUntil")) else TelegramRemoteStore.Settings(),
        telegramToken = p.optional("alerts.telegram.token"),
        gmail = GmailSmtpConfigStore.Config(
            enabled = p.boolean("alerts.gmail.enabled"),
            account = p.required("alerts.gmail.account"),
            recipients = p.readStrings("alerts.gmail.recipients"),
            hasAppPassword = p.optional("alerts.gmail.password") != null
        ),
        gmailAppPassword = p.optional("alerts.gmail.password"),
        resend = ResendEmailConfigStore.Config(
            enabled = p.boolean("alerts.resend.enabled"),
            sender = p.required("alerts.resend.sender"),
            recipients = p.readStrings("alerts.resend.recipients"),
            hasApiKey = p.optional("alerts.resend.key") != null
        ),
        resendApiKey = p.optional("alerts.resend.key"),
        sms = SmsConfigStore.Config(
            enabled = p.boolean("alerts.sms.enabled"),
            recipients = p.readStrings("alerts.sms.recipients")
        )
    )

    private fun writePowerSources(p: Properties, data: BackupDocument.PowerSourcesData) {
        p["power.selected"] = data.selectedSource.name
        p["power.modbus.host"] = data.modbus.host
        p["power.modbus.port"] = data.modbus.port.toString()
        p["power.modbus.unit"] = data.modbus.unitId.toString()
        p.putOptional("power.cloud.access", data.cloudCredentials?.accessKey)
        p.putOptional("power.cloud.secret", data.cloudCredentials?.secretKey)
        p.putOptional("power.cloud.serial", data.cloudSerialNumber)
        p.putOptional("power.cloud.name", data.cloudDeviceName)
        p["power.account.present"] = (data.powerOceanAccount != null).toString()
        p["power.account.chargerConfirmation"] = data.powerOceanRequireChargerConfirmation.toString()
        p["power.account.profileVerified"] = data.powerOceanProfileVerified.toString()
        p["power.account.previousTest"] = data.powerOceanUsePreviousTest.toString()
        p["power.account.liveReporting"] = data.powerOceanRequestLiveReporting.toString()
        p["power.account.chargerFirst"] = data.powerOceanAssisted.enabled.toString()
        p["power.account.normalSeconds"] = data.powerOceanAssisted.normalSeconds.toString()
        p["power.account.outageSeconds"] = data.powerOceanAssisted.outageSeconds.toString()
        p["power.account.windowSeconds"] = data.powerOceanAssisted.checkWindowSeconds.toString()
        p["power.account.extraUpdates"] = data.powerOceanAssisted.extraPowerUpdates.toString()
        p["power.account.warnUnchanged"] = data.powerOceanAssisted.warnOnUnchanged.toString()
        p["power.account.ignoreUnchanged"] = data.powerOceanAssisted.ignoreUnchanged.toString()
        p["power.account.notifyUnknown"] = data.powerOceanAssisted.notifyOnUnknown.toString()
        p["power.account.notifyChargerReturn"] = data.powerOceanAssisted.notifyOnChargerReturn.toString()
        p["power.account.poweredFailureThreshold"] = data.powerOceanAssisted.poweredFailureThreshold.toString()
        p["power.account.sessionRefreshFailureThreshold"] = data.powerOceanAssisted.sessionRefreshFailureThreshold.toString()
        data.powerOceanAccount?.let {
            p["power.account.email"] = it.email
            p["power.account.password"] = it.password
            p["power.account.serial"] = it.serial
            p["power.account.model"] = it.model
            p["power.account.region"] = it.region
            p["power.account.refresh"] = it.refreshSeconds.toString()
        }
    }

    private fun readPowerSources(p: Properties): BackupDocument.PowerSourcesData {
        val modbus = PowerSourceStore.EcoFlowConfig(
            host = p.required("power.modbus.host"),
            port = p.int("power.modbus.port"),
            unitId = p.int("power.modbus.unit")
        )
        require(modbus.host.isEmpty() || modbus.isValid) { "Invalid local EcoFlow settings in backup." }
        val access = p.optional("power.cloud.access")
        val secret = p.optional("power.cloud.secret")
        require((access == null) == (secret == null)) { "Incomplete EcoFlow Cloud keys in backup." }
        val credentials = if (access != null && secret != null) {
            EcoFlowCloudClient.Credentials(access, secret).also {
                require(it.isValid) { "Invalid EcoFlow Cloud keys in backup." }
            }
        } else null
        return BackupDocument.PowerSourcesData(
            selectedSource = p.enumValue("power.selected"),
            modbus = modbus,
            cloudCredentials = credentials,
            cloudSerialNumber = p.optional("power.cloud.serial"),
            cloudDeviceName = p.optional("power.cloud.name"),
            powerOceanAccount = if (p.containsKey("power.account.present") && p.boolean("power.account.present")) {
                com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection(
                    p.required("power.account.email"), p.required("power.account.password"), p.required("power.account.serial"),
                    p.required("power.account.model"), p.required("power.account.region"), p.int("power.account.refresh")
                ).also { require(it.isValid) { "Invalid PowerOcean account data in backup." } }
            } else null,
            powerOceanRequireChargerConfirmation = p.containsKey("power.account.chargerConfirmation") && p.boolean("power.account.chargerConfirmation"),
            powerOceanUsePreviousTest = p.containsKey("power.account.previousTest") && p.boolean("power.account.previousTest"),
            powerOceanProfileVerified = p.containsKey("power.account.profileVerified") && p.boolean("power.account.profileVerified"),
            powerOceanRequestLiveReporting = p.containsKey("power.account.liveReporting") && p.boolean("power.account.liveReporting"),
            powerOceanAssisted = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings(
                p.containsKey("power.account.chargerFirst") && p.boolean("power.account.chargerFirst"),
                if (p.containsKey("power.account.normalSeconds")) p.int("power.account.normalSeconds") else 3600,
                if (p.containsKey("power.account.outageSeconds")) p.int("power.account.outageSeconds") else 60,
                !p.containsKey("power.account.warnUnchanged") || p.boolean("power.account.warnUnchanged"),
                p.containsKey("power.account.ignoreUnchanged") && p.boolean("power.account.ignoreUnchanged"),
                if (p.containsKey("power.account.windowSeconds")) p.int("power.account.windowSeconds") else 120,
                if (p.containsKey("power.account.extraUpdates")) p.int("power.account.extraUpdates") else 2,
                !p.containsKey("power.account.notifyUnknown") || p.boolean("power.account.notifyUnknown"),
                !p.containsKey("power.account.notifyChargerReturn") || p.boolean("power.account.notifyChargerReturn"),
                if (p.containsKey("power.account.poweredFailureThreshold")) p.int("power.account.poweredFailureThreshold") else 5,
                if (p.containsKey("power.account.sessionRefreshFailureThreshold")) p.int("power.account.sessionRefreshFailureThreshold") else 2)
        )
    }

    private fun writeHistory(p: Properties, data: BackupDocument.HistoryData) {
        p.writeList("history.power", data.powerEvents) { prefix, item ->
            p["$prefix.kind"] = item.kind
            p["$prefix.lost"] = item.powerLostAtEpochMs.toString()
            p.putOptionalLong("$prefix.confirmed", item.confirmedAtEpochMs)
            p["$prefix.restored"] = item.restoredAtEpochMs.toString()
            p.putOptionalInt("$prefix.startBattery", item.startingBatteryPercent)
            p.putOptionalInt("$prefix.endBattery", item.endingBatteryPercent)
            p.putOptionalInt("$prefix.startTemperature", item.startingBatteryTemperatureTenthsCelsius)
            p.putOptionalInt("$prefix.endTemperature", item.endingBatteryTemperatureTenthsCelsius)
        }
        p.writeList("history.operational", data.operationalEvents) { prefix, item ->
            p["$prefix.kind"] = item.kind
            p["$prefix.at"] = item.timestampEpochMs.toString()
            p["$prefix.detail"] = item.detail
        }
    }

    private fun readHistory(p: Properties) = BackupDocument.HistoryData(
        powerEvents = p.readList("history.power") { prefix ->
            EventHistoryStore.Record(
                kind = p.required("$prefix.kind"),
                powerLostAtEpochMs = p.long("$prefix.lost"),
                confirmedAtEpochMs = p.optionalLong("$prefix.confirmed"),
                restoredAtEpochMs = p.long("$prefix.restored"),
                startingBatteryPercent = p.optionalInt("$prefix.startBattery"),
                endingBatteryPercent = p.optionalInt("$prefix.endBattery"),
                startingBatteryTemperatureTenthsCelsius = p.optionalInt("$prefix.startTemperature"),
                endingBatteryTemperatureTenthsCelsius = p.optionalInt("$prefix.endTemperature")
            )
        },
        operationalEvents = p.readList("history.operational") { prefix ->
            OperationalHistoryStore.Record(
                kind = p.required("$prefix.kind"),
                timestampEpochMs = p.long("$prefix.at"),
                detail = p.required("$prefix.detail")
            )
        }
    )

    private fun writeActiveState(p: Properties, data: BackupDocument.ActiveStateData) {
        p["active.enabled"] = data.monitoringWasEnabled.toString()
        val state = data.monitorState
        p["active.phase"] = state.phase.name
        p["active.phaseSince"] = state.phaseSinceEpochMs.toString()
        p.putOptionalLong("active.outageStarted", state.outageStartedEpochMs)
        p.putOptionalInt("active.outageBattery", state.outageStartBatteryPercent)
        p.putOptionalLong("active.confirmed", state.confirmedAtEpochMs)
        p.putOptionalInt("active.outageTemperature", state.outageStartBatteryTemperatureTenthsCelsius)
        p["active.observedAt"] = data.lastObservationEpochMs.toString()
        p["active.hasSnapshot"] = (data.lastSnapshot != null).toString()
        data.lastSnapshot?.let {
            p["active.snapshot.plugged"] = it.plugged.toString()
            p.putOptionalInt("active.snapshot.battery", it.batteryPercent)
            p["active.snapshot.status"] = it.batteryStatus.toString()
            p.putOptionalInt("active.snapshot.temperature", it.batteryTemperatureTenthsCelsius)
        }
        val scheduled = data.scheduledState
        p.putOptionalLong("active.sourceSince", scheduled.sourceUnavailableSinceEpochMs)
        p["active.sourceAlerted"] = scheduled.sourceUnavailableAlerted.toString()
        p.putOptionalLong("active.lastHeartbeat", scheduled.lastHeartbeatEpochMs)
        p.putOptionalLong("active.trackedOutage", scheduled.trackedOutageStartedEpochMs)
        p.putOptionalLong("active.lastOutageUpdate", scheduled.lastOutageUpdateEpochMs)
        val audible = data.audibleRuntime
        p.putOptionalLong("active.audibleOutage", audible.activeOutageId)
        p.putOptionalLong("active.audibleDismissed", audible.dismissedOutageId)
        p.putOptionalLong("active.audiblePlayed", audible.lastPlayedAtEpochMs)
        p.writeList("active.queue", data.deliveryQueue) { prefix, item ->
            p["$prefix.id"] = item.id
            p["$prefix.provider"] = item.providerId
            p["$prefix.destination"] = item.destinationId
            p.writeMessage("$prefix.message", item.message)
            p["$prefix.status"] = item.status.name
            p["$prefix.created"] = item.createdAtEpochMs.toString()
            p["$prefix.attempts"] = item.attemptCount.toString()
            p.putOptionalLong("$prefix.lastAttempt", item.lastAttemptAtEpochMs)
            p["$prefix.nextAttempt"] = item.nextAttemptAtEpochMs.toString()
            p.putOptionalLong("$prefix.lease", item.leaseUntilEpochMs)
            p.putOptional("$prefix.error", item.lastError)
            p.putOptional("$prefix.providerMessage", item.providerMessageId)
        }
        p.writeList("active.pending", data.pendingEvents) { prefix, item ->
            p.writeMessage(prefix, item)
        }
    }

    private fun readActiveState(p: Properties): BackupDocument.ActiveStateData =
        BackupDocument.ActiveStateData(
            monitoringWasEnabled = p.boolean("active.enabled"),
            monitorState = OutageEngine.State(
                phase = p.enumValue("active.phase"),
                phaseSinceEpochMs = p.long("active.phaseSince"),
                outageStartedEpochMs = p.optionalLong("active.outageStarted"),
                outageStartBatteryPercent = p.optionalInt("active.outageBattery"),
                confirmedAtEpochMs = p.optionalLong("active.confirmed"),
                outageStartBatteryTemperatureTenthsCelsius = p.optionalInt("active.outageTemperature")
            ),
            lastSnapshot = if (p.boolean("active.hasSnapshot")) {
                PowerSnapshot(
                    plugged = p.int("active.snapshot.plugged"),
                    batteryPercent = p.optionalInt("active.snapshot.battery"),
                    batteryStatus = p.int("active.snapshot.status"),
                    batteryTemperatureTenthsCelsius = p.optionalInt("active.snapshot.temperature")
                )
            } else null,
            lastObservationEpochMs = p.long("active.observedAt"),
            scheduledState = ScheduledAlertStore.State(
                sourceUnavailableSinceEpochMs = p.optionalLong("active.sourceSince"),
                sourceUnavailableAlerted = p.boolean("active.sourceAlerted"),
                lastHeartbeatEpochMs = p.optionalLong("active.lastHeartbeat"),
                trackedOutageStartedEpochMs = p.optionalLong("active.trackedOutage"),
                lastOutageUpdateEpochMs = p.optionalLong("active.lastOutageUpdate")
            ),
            audibleRuntime = AudibleAlarmEngine.Runtime(
                activeOutageId = p.optionalLong("active.audibleOutage"),
                dismissedOutageId = p.optionalLong("active.audibleDismissed"),
                lastPlayedAtEpochMs = p.optionalLong("active.audiblePlayed")
            ),
            deliveryQueue = p.readList("active.queue") { prefix ->
                AlertQueueEngine.Item(
                    id = p.required("$prefix.id"),
                    providerId = p.required("$prefix.provider"),
                    destinationId = p.required("$prefix.destination"),
                    message = p.readMessage("$prefix.message"),
                    status = p.enumValue("$prefix.status"),
                    createdAtEpochMs = p.long("$prefix.created"),
                    attemptCount = p.int("$prefix.attempts"),
                    lastAttemptAtEpochMs = p.optionalLong("$prefix.lastAttempt"),
                    nextAttemptAtEpochMs = p.long("$prefix.nextAttempt"),
                    leaseUntilEpochMs = p.optionalLong("$prefix.lease"),
                    lastError = p.optional("$prefix.error"),
                    providerMessageId = p.optional("$prefix.providerMessage")
                )
            },
            pendingEvents = p.readList("active.pending") { prefix -> p.readMessage(prefix) }
        )

    private fun Properties.writeMessage(prefix: String, item: AlertMessage) {
        this["$prefix.event"] = item.eventId
        this["$prefix.kind"] = item.kind.name
        this["$prefix.title"] = item.title
        this["$prefix.body"] = item.body
    }

    private fun Properties.readMessage(prefix: String) = AlertMessage(
        eventId = required("$prefix.event"),
        kind = enumValue<AlertKind>("$prefix.kind"),
        title = required("$prefix.title"),
        body = required("$prefix.body")
    )

    private fun Properties.required(key: String): String = getProperty(key)
        ?: throw IllegalArgumentException("Backup is missing $key.")

    private fun Properties.long(key: String): Long = required(key).toLongOrNull()
        ?: throw IllegalArgumentException("Backup has an invalid $key value.")

    private fun Properties.int(key: String): Int = required(key).toIntOrNull()
        ?: throw IllegalArgumentException("Backup has an invalid $key value.")

    private fun Properties.boolean(key: String): Boolean = when (required(key)) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Backup has an invalid $key value.")
    }

    private inline fun <reified T : Enum<T>> Properties.enumValue(key: String): T =
        enumValues<T>().firstOrNull { it.name == required(key) }
            ?: throw IllegalArgumentException("Backup has an invalid $key value.")

    private fun Properties.putOptional(key: String, value: String?) {
        this["$key.present"] = (value != null).toString()
        if (value != null) this[key] = value
    }

    private fun Properties.optional(key: String): String? =
        if (boolean("$key.present")) required(key) else null

    private fun Properties.putOptionalLong(key: String, value: Long?) =
        putOptional(key, value?.toString())

    private fun Properties.putOptionalInt(key: String, value: Int?) =
        putOptional(key, value?.toString())

    private fun Properties.optionalLong(key: String): Long? {
        val value = optional(key) ?: return null
        return value.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid $key in backup.")
    }

    private fun Properties.optionalInt(key: String): Int? {
        val value = optional(key) ?: return null
        return value.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid $key in backup.")
    }

    private fun <T> Properties.writeList(
        prefix: String,
        values: List<T>,
        write: (String, T) -> Unit
    ) {
        this["$prefix.count"] = values.size.toString()
        values.forEachIndexed { index, value -> write("$prefix.$index", value) }
    }

    private fun <T> Properties.readList(prefix: String, read: (String) -> T): List<T> {
        val count = int("$prefix.count")
        require(count in 0..MAX_ITEMS) { "Backup contains too many $prefix items." }
        return List(count) { read("$prefix.$it") }
    }

    private fun Properties.writeStrings(prefix: String, values: List<String>) =
        writeList(prefix, values) { key, value -> this[key] = value }

    private fun Properties.readStrings(prefix: String): List<String> =
        readList(prefix) { key -> required(key) }

    private fun Long.validDelay(): Long = also {
        require(it in 0L..30L * 24 * 60 * 60_000L) { "Invalid detection delay in backup." }
    }

    private fun Long.validScheduledInterval(): Long = also {
        require(it in ScheduledAlertStore.INTERVAL_RANGE_MS) {
            "Invalid scheduled interval in backup."
        }
    }
}
