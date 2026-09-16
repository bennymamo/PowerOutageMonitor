package com.flossypickle.poweroutagemonitor.integrations.power

import android.content.Context
import android.os.Build
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowModbusProtocol

/** Device-protected source configuration and the latest normalized source reading. */
internal class PowerSourceStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val preferences = storageContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    enum class Source { ANDROID_CHARGER, ECOFLOW_MODBUS }

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
        val detail: String?
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
        check(preferences.edit().putString(KEY_SELECTED_SOURCE, source.name).commit()) {
            "Unable to save power source"
        }
        return true
    }

    fun recordSignal(signal: PowerSignal, persist: Boolean = true) {
        val source = when (signal.providerId) {
            ECOFLOW_PROVIDER_ID -> Source.ECOFLOW_MODBUS
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
            detail = preferences.getString(KEY_STATUS_DETAIL, null)
        )
    }

    private fun statusFrom(source: Source, signal: PowerSignal) = Status(
        source = source,
        availability = signal.availability,
        observedAtEpochMs = signal.observedAtEpochMs,
        detail = signal.detail?.take(MAX_DETAIL_LENGTH)
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

    companion object {
        const val ECOFLOW_PROVIDER_ID = "ecoflow_modbus"
        private const val KEY_POWEROCEAN_CHARGER_CONFIRMATION = "powerocean_charger_confirmation"
        private const val FILE_NAME = "power_sources"
        private const val KEY_SELECTED_SOURCE = "selected_source"
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
