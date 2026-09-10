package com.flossypickle.poweroutagemonitor.storage

import android.content.Context
import android.os.Build

/** Credential-free provider flags used only to retain alerts raised before first unlock. */
internal class EnabledAlertProvidersStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val preferences = storageContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun hasAny(): Boolean = preferences.getStringSet(KEY_ENABLED, emptySet()).orEmpty().isNotEmpty()

    fun setEnabled(providerId: String, enabled: Boolean) {
        val providers = preferences.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toMutableSet()
        if (enabled) providers += providerId else providers -= providerId
        check(preferences.edit().putStringSet(KEY_ENABLED, providers).commit()) {
            "Unable to persist enabled alert providers"
        }
    }

    companion object {
        private const val FILE_NAME = "enabled_alert_providers"
        private const val KEY_ENABLED = "provider_ids"
    }
}
