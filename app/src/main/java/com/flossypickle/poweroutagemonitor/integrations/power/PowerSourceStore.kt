package com.flossypickle.poweroutagemonitor.integrations.power

import android.content.Context
import android.os.Build
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowModbusProtocol

/** Device-protected source configuration and the latest normalized source reading. */
internal class PowerSourceStore(context: Context) {
    private val appContext = context.applicationContext
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val preferences = storageContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    enum class Source { ANDROID_CHARGER, ECOFLOW_MODBUS, ECOFLOW_ACCOUNT }

    data class EcoFlowConfig(
        val host: String = "",
        val port: Int = EcoFlowModbusProtocol.DEFAULT_PORT,
        val unitId: Int = EcoFlowModbusProtocol.DEFAULT_UNIT_ID
    ) {
        val isValid: Boolean get() = host.isNotBlank() && host.length <= 253 &&
            port in 1..65_535 && unitId in 0..247

        internal fun key(): String = "${host.trim().lowercase()}|$port|$unitId"
    }

    data class Status(
        val source: Source,
        val availability: GridAvailability,
        val observedAtEpochMs: Long,
        val detail: String?,
        val recoveryPending: Boolean = false,
        val dataPossiblyStalled: Boolean = false
    )

    fun selectedSource(): Source = runCatching {
        Source.valueOf(preferences.getString(KEY_SELECTED_SOURCE, null) ?: "")
    }.getOrDefault(Source.ANDROID_CHARGER)

    fun powerOceanRequiresChargerConfirmation(): Boolean = preferences.getBoolean(KEY_POWEROCEAN_CHARGER_CONFIRMATION, false)

    fun setPowerOceanChargerConfirmation(required: Boolean) {
        check(preferences.edit().putBoolean(KEY_POWEROCEAN_CHARGER_CONFIRMATION, required).commit()) {
            "Unable to save charger confirmation setting"
        }
    }

    fun powerOceanRequestsLiveReporting(): Boolean = preferences.getBoolean(KEY_POWEROCEAN_LIVE_REPORTING, false)

    fun setPowerOceanLiveReporting(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_POWEROCEAN_LIVE_REPORTING, enabled).commit())
    }

    fun powerOceanProfileVerified(connection: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection?): Boolean =
        connection != null && connection.isValid && connection.model == "86" &&
            preferences.getString(KEY_POWEROCEAN_PROFILE, null) == powerOceanKey(connection)

    fun setPowerOceanProfileVerified(connection: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection, verified: Boolean) {
        require(connection.isValid && connection.model == "86")
        val editor = preferences.edit().remove(KEY_POWEROCEAN_TEST_TIME).remove(KEY_POWEROCEAN_PREVIOUS_TEST)
        if (verified) editor.putString(KEY_POWEROCEAN_PROFILE, powerOceanKey(connection)) else editor.remove(KEY_POWEROCEAN_PROFILE)
        check(editor.commit()) { "Unable to save PowerOcean profile" }
    }

    fun powerOceanUsesPreviousTest(connection: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection?): Boolean =
        powerOceanProfileVerified(connection) && preferences.getString(KEY_POWEROCEAN_PREVIOUS_TEST, null) == connection?.let(::powerOceanKey)

    fun setPowerOceanUsePreviousTest(connection: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection, enabled: Boolean) {
        if (enabled) require(powerOceanProfileVerified(connection)) { "Confirm the tested grid profile first" }
        val editor = preferences.edit()
        if (enabled) editor.putString(KEY_POWEROCEAN_PREVIOUS_TEST, powerOceanKey(connection)) else editor.remove(KEY_POWEROCEAN_PREVIOUS_TEST)
        check(editor.commit()) { "Unable to save previous-test choice" }
    }

    fun recordPowerOceanLiveTest(connection: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection) {
        if (powerOceanProfileVerified(connection)) preferences.edit().putLong(KEY_POWEROCEAN_TEST_TIME, System.currentTimeMillis()).apply()
    }

    fun powerOceanActivationStage(
        connection: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection?,
        now: Long = System.currentTimeMillis()
    ) = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanActivationPolicy.evaluate(
        connection?.isValid == true, connection?.model == "86", powerOceanProfileVerified(connection),
        preferences.getLong(KEY_POWEROCEAN_TEST_TIME, 0), now, powerOceanUsesPreviousTest(connection))

    fun powerOceanReadyToActivate(): Boolean = powerOceanActivationStage(
        com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountStore(appContext).connection()
    ) == com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanActivationPolicy.Stage.READY

    private fun powerOceanKey(connection: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAccountClient.Connection): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("single-phase-v1|${connection.serial}|${connection.model}|${connection.region}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun powerOceanAssistedSettings() = com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings(
        preferences.getBoolean("account_charger_first", false), preferences.getInt("account_normal_seconds", 3600),
        preferences.getInt("account_outage_seconds", 60), preferences.getBoolean("account_warn_unchanged", true),
        preferences.getBoolean("account_ignore_unchanged", false))

    fun setPowerOceanAssistedSettings(settings: com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings) {
        val changedMode = powerOceanAssistedSettings().enabled != settings.enabled
        val edit = preferences.edit().putBoolean("account_charger_first", settings.enabled)
            .putInt("account_normal_seconds", settings.normalSeconds).putInt("account_outage_seconds", settings.outageSeconds)
            .putBoolean("account_warn_unchanged", settings.warnOnUnchanged).putBoolean("account_ignore_unchanged", settings.ignoreUnchanged)
        if (changedMode) edit.remove("account_charger_loss_started").remove("account_charger_loss_recovered")
        check(edit.commit()) { "Unable to save charger-first settings" }
    }

    fun powerOceanAssistancePaused() = preferences.getBoolean("account_assistance_paused", false)
    fun setPowerOceanAssistancePaused(paused: Boolean) {
        check(preferences.edit().putBoolean("account_assistance_paused", paused).commit())
    }

    fun assistedChargerLossStartedAt() = preferences.getLong("account_charger_loss_started", 0)
    fun assistedChargerLossRecovered() = preferences.getBoolean("account_charger_loss_recovered", false)
    fun recordAssistedChargerState(lossStartedAt: Long, recovered: Boolean) {
        if (lossStartedAt == assistedChargerLossStartedAt() && recovered == assistedChargerLossRecovered()) return
        check(preferences.edit().putLong("account_charger_loss_started", lossStartedAt)
            .putBoolean("account_charger_loss_recovered", recovered).commit())
    }

    fun ecoFlowConfig() = EcoFlowConfig(
        host = preferences.getString(KEY_ECOFLOW_HOST, "").orEmpty(),
        port = preferences.getInt(KEY_ECOFLOW_PORT, EcoFlowModbusProtocol.DEFAULT_PORT),
        unitId = preferences.getInt(KEY_ECOFLOW_UNIT, EcoFlowModbusProtocol.DEFAULT_UNIT_ID)
    )

    fun saveEcoFlowConfig(config: EcoFlowConfig) {
        require(config.isValid)
        require(selectedSource() != Source.ECOFLOW_MODBUS) {
            "Switch to Android charger before changing active EcoFlow connection settings"
        }
        preferences.edit()
            .putString(KEY_ECOFLOW_HOST, config.host.trim())
            .putInt(KEY_ECOFLOW_PORT, config.port)
            .putInt(KEY_ECOFLOW_UNIT, config.unitId)
            .remove(KEY_ECOFLOW_TESTED_CONFIG)
            .commit()
    }

    /** Restores connection details but requires a fresh test before EcoFlow can be selected. */
    fun restoreEcoFlowConfig(config: EcoFlowConfig) {
        require(config.host.isEmpty() || config.isValid)
        preferences.edit()
            .putString(KEY_SELECTED_SOURCE, Source.ANDROID_CHARGER.name)
            .putString(KEY_ECOFLOW_HOST, config.host.trim())
            .putInt(KEY_ECOFLOW_PORT, config.port)
            .putInt(KEY_ECOFLOW_UNIT, config.unitId)
            .remove(KEY_ECOFLOW_TESTED_CONFIG)
            .remove(KEY_POWEROCEAN_PROFILE)
            .remove(KEY_POWEROCEAN_PREVIOUS_TEST)
            .remove(KEY_POWEROCEAN_TEST_TIME)
            .remove("account_charger_loss_started")
            .remove("account_assistance_paused").remove("account_data_warning_episode").remove("account_data_warning_sent")
            .remove("status_data_stalled")
            .remove("account_charger_loss_recovered")
            .remove(KEY_STATUS_SOURCE)
            .remove(KEY_STATUS_AVAILABILITY)
            .remove(KEY_STATUS_OBSERVED_AT)
            .remove(KEY_STATUS_DETAIL)
            .commit()
        PowerSourceRuntime.status = null
    }

    fun recordEcoFlowTest(signal: PowerSignal) {
        require(signal.providerId == ECOFLOW_PROVIDER_ID)
        val editor = preferences.edit()
        if (signal.availability != GridAvailability.UNKNOWN) {
            editor.putString(KEY_ECOFLOW_TESTED_CONFIG, ecoFlowConfig().key())
        } else {
            editor.remove(KEY_ECOFLOW_TESTED_CONFIG)
        }
        writeStatus(editor, Source.ECOFLOW_MODBUS, signal)
        editor.commit()
        PowerSourceRuntime.status = statusFrom(Source.ECOFLOW_MODBUS, signal)
    }

    fun ecoFlowReadyToActivate(): Boolean {
        val config = ecoFlowConfig()
        return config.isValid &&
            preferences.getString(KEY_ECOFLOW_TESTED_CONFIG, null) == config.key()
    }

    fun select(source: Source): Boolean {
        if (source == Source.ECOFLOW_MODBUS && !ecoFlowReadyToActivate()) return false
        if (source == Source.ECOFLOW_ACCOUNT && !powerOceanReadyToActivate()) return false
        check(preferences.edit().putString(KEY_SELECTED_SOURCE, source.name).commit()) {
            "Unable to save power source"
        }
        return true
    }

    fun recordSignal(signal: PowerSignal, persist: Boolean = true) {
        val source = when (signal.providerId) {
            ECOFLOW_PROVIDER_ID -> Source.ECOFLOW_MODBUS
            POWEROCEAN_PROVIDER_ID -> Source.ECOFLOW_ACCOUNT
            else -> Source.ANDROID_CHARGER
        }
        PowerSourceRuntime.status = statusFrom(source, signal)
        if (persist) {
            writeStatus(preferences.edit(), source, signal).apply()
        }
    }

    fun lastStatus(): Status? {
        PowerSourceRuntime.status?.let { return it }
        val sourceName = preferences.getString(KEY_STATUS_SOURCE, null) ?: return null
        val source = runCatching { Source.valueOf(sourceName) }.getOrNull() ?: return null
        val availability = runCatching {
            GridAvailability.valueOf(preferences.getString(KEY_STATUS_AVAILABILITY, null) ?: "")
        }.getOrDefault(GridAvailability.UNKNOWN)
        return Status(
            source = source,
            availability = availability,
            observedAtEpochMs = preferences.getLong(KEY_STATUS_OBSERVED_AT, 0),
            detail = preferences.getString(KEY_STATUS_DETAIL, null),
            recoveryPending = preferences.getBoolean(KEY_STATUS_RECOVERY_PENDING, false),
            dataPossiblyStalled = preferences.getBoolean("status_data_stalled", false)
        )
    }

    private fun statusFrom(source: Source, signal: PowerSignal) = Status(
        source = source,
        availability = signal.availability,
        observedAtEpochMs = signal.observedAtEpochMs,
        detail = signal.detail?.take(MAX_DETAIL_LENGTH),
        recoveryPending = signal.recoveryPending,
        dataPossiblyStalled = signal.dataPossiblyStalled ?: lastStatus()?.takeIf { it.source == source }?.dataPossiblyStalled ?: false
    )

    private fun writeStatus(
        editor: android.content.SharedPreferences.Editor,
        source: Source,
        signal: PowerSignal
    ) = editor
        .putString(KEY_STATUS_SOURCE, source.name)
        .putString(KEY_STATUS_AVAILABILITY, signal.availability.name)
        .putLong(KEY_STATUS_OBSERVED_AT, signal.observedAtEpochMs)
        .putString(KEY_STATUS_DETAIL, signal.detail?.take(MAX_DETAIL_LENGTH))
        .putBoolean(KEY_STATUS_RECOVERY_PENDING, signal.recoveryPending)
        .putBoolean("status_data_stalled", PowerSourceRuntime.status?.dataPossiblyStalled ?: false)

    companion object {
        const val ECOFLOW_PROVIDER_ID = "ecoflow_modbus"
        const val POWEROCEAN_PROVIDER_ID = "ecoflow_powerocean_account"
        private const val KEY_POWEROCEAN_LIVE_REPORTING = "powerocean_live_reporting"
        private const val FILE_NAME = "power_sources"
        private const val KEY_SELECTED_SOURCE = "selected_source"
        private const val KEY_POWEROCEAN_CHARGER_CONFIRMATION = "powerocean_charger_confirmation"
        private const val KEY_POWEROCEAN_PROFILE = "powerocean_verified_profile"
        private const val KEY_POWEROCEAN_PREVIOUS_TEST = "powerocean_previous_successful_test"
        private const val KEY_POWEROCEAN_TEST_TIME = "powerocean_live_test_time"
        private const val KEY_STATUS_RECOVERY_PENDING = "status_recovery_pending"
        private const val KEY_ECOFLOW_HOST = "ecoflow_host"
        private const val KEY_ECOFLOW_PORT = "ecoflow_port"
        private const val KEY_ECOFLOW_UNIT = "ecoflow_unit"
        private const val KEY_ECOFLOW_TESTED_CONFIG = "ecoflow_tested_config"
        private const val KEY_STATUS_SOURCE = "status_source"
        private const val KEY_STATUS_AVAILABILITY = "status_availability"
        private const val KEY_STATUS_OBSERVED_AT = "status_observed_at"
        private const val KEY_STATUS_DETAIL = "status_detail"
        private const val MAX_DETAIL_LENGTH = 200
    }
}

private object PowerSourceRuntime {
    @Volatile
    var status: PowerSourceStore.Status? = null
}
