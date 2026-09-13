package com.flossypickle.poweroutagemonitor.integrations.alerts.sms

import android.content.Context
import com.flossypickle.poweroutagemonitor.storage.EnabledAlertProvidersStore
import org.json.JSONArray

/** Device-SMS recipients; no phone or subscription identifiers are collected. */
internal class SmsConfigStore(context: Context) {
    data class Config(val enabled: Boolean, val recipients: List<String>)

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val enabledProviders = EnabledAlertProvidersStore(context)

    fun config() = Config(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        recipients = readRecipients()
    )

    fun save(enabled: Boolean, recipients: List<String>) {
        val cleanRecipients = recipients.map(SmsProtocol::normalizeNumber)
            .filter(SmsProtocol::isValidNumber)
            .distinct()
        val actuallyEnabled = enabled && cleanRecipients.isNotEmpty()
        check(preferences.edit()
            .putBoolean(KEY_ENABLED, actuallyEnabled)
            .putString(KEY_RECIPIENTS, JSONArray(cleanRecipients).toString())
            .commit()) { "Unable to persist SMS configuration" }
        enabledProviders.setEnabled(PROVIDER_ID, actuallyEnabled)
    }

    fun clear() {
        preferences.edit().clear().commit()
        enabledProviders.setEnabled(PROVIDER_ID, false)
    }

    private fun readRecipients(): List<String> = runCatching {
        val array = JSONArray(preferences.getString(KEY_RECIPIENTS, "[]"))
        buildList {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }.getOrDefault(emptyList())

    companion object {
        const val PROVIDER_ID = "device_sms"
        private const val PREFERENCES_FILE = "sms_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RECIPIENTS = "recipients"
    }
}
