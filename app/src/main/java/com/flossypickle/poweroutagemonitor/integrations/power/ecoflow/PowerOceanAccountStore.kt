package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import android.content.Context
import com.flossypickle.poweroutagemonitor.storage.SecureSecretStore
import org.json.JSONObject

/** Email, password and serial all stay in the existing Keystore-encrypted secret store. */
internal class PowerOceanAccountStore(context: Context) {
    private val secrets = SecureSecretStore(context)
    fun connection(): PowerOceanAccountClient.Connection? = secrets.get(KEY)?.let(::decode)
    fun save(connection: PowerOceanAccountClient.Connection) {
        require(connection.isValid)
        secrets.put(KEY, encode(connection))
    }
    fun clear() = secrets.remove(KEY)

    companion object {
        private const val KEY = "powerocean_owner_account_v1"
        fun encode(value: PowerOceanAccountClient.Connection): String = JSONObject()
            .put("email", value.email).put("password", value.password).put("serial", value.serial)
            .put("model", value.model).put("region", value.region).put("refreshSeconds", value.refreshSeconds).toString()
        fun decode(text: String): PowerOceanAccountClient.Connection? = runCatching {
            val data = JSONObject(text)
            PowerOceanAccountClient.Connection(data.getString("email"), data.getString("password"),
                data.getString("serial"), data.getString("model"), data.getString("region"),
                data.optInt("refreshSeconds", 30)).takeIf { it.isValid }
        }.getOrNull()
    }
}
