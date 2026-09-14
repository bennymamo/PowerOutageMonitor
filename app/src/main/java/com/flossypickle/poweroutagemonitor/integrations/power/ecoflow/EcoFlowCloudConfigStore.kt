package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import android.content.Context
import androidx.core.content.edit
import com.flossypickle.poweroutagemonitor.storage.SecureSecretStore

/** Stores cloud credentials separately from non-secret display configuration. */
internal class EcoFlowCloudConfigStore(context: Context) {
    data class Config(
        val hasCredentials: Boolean,
        val selectedSerialNumber: String?,
        val selectedDeviceName: String?
    )

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(context)

    fun config() = Config(
        hasCredentials = secrets.contains(SECRET_ACCESS_KEY) && secrets.contains(SECRET_SECRET_KEY),
        selectedSerialNumber = preferences.getString(KEY_SELECTED_SERIAL, null),
        selectedDeviceName = preferences.getString(KEY_SELECTED_NAME, null)
    )

    fun credentials(): EcoFlowCloudClient.Credentials? {
        val accessKey = secrets.get(SECRET_ACCESS_KEY) ?: return null
        val secretKey = secrets.get(SECRET_SECRET_KEY) ?: return null
        return EcoFlowCloudClient.Credentials(accessKey, secretKey).takeIf { it.isValid }
    }

    fun saveCredentials(credentials: EcoFlowCloudClient.Credentials) {
        require(credentials.isValid)
        secrets.put(SECRET_ACCESS_KEY, credentials.accessKey)
        secrets.put(SECRET_SECRET_KEY, credentials.secretKey)
        preferences.edit(commit = true) {
            remove(KEY_SELECTED_SERIAL)
            remove(KEY_SELECTED_NAME)
        }
    }

    fun selectDevice(device: EcoFlowCloudClient.Device) {
        preferences.edit(commit = true) {
            putString(KEY_SELECTED_SERIAL, device.serialNumber)
            putString(KEY_SELECTED_NAME, device.name)
        }
    }

    fun clear() {
        preferences.edit(commit = true) { clear() }
        secrets.remove(SECRET_ACCESS_KEY)
        secrets.remove(SECRET_SECRET_KEY)
    }

    companion object {
        private const val PREFERENCES_FILE = "ecoflow_cloud_config"
        private const val SECRET_ACCESS_KEY = "ecoflow_cloud_access_key"
        private const val SECRET_SECRET_KEY = "ecoflow_cloud_secret_key"
        private const val KEY_SELECTED_SERIAL = "selected_serial"
        private const val KEY_SELECTED_NAME = "selected_name"
    }
}
