package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Optional, experimental owner-account interface. Only login and device reads are supported. */
internal class PowerOceanAccountClient(
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
) {
    data class Connection(val email: String, val password: String, val serial: String,
        val model: String = "86", val region: String = "eu", val refreshSeconds: Int = 30) {
        val isValid get() = email.length in 3..254 && email.contains('@') && email.none(Char::isWhitespace) &&
            password.length in 1..300 && serial.matches(Regex("[A-Za-z0-9_-]{4,100}")) &&
            model in setOf("83", "85", "86", "87") && region in setOf("eu", "us") && refreshSeconds in 10..60
        override fun toString() = "PowerOcean account connection (private details redacted)"
    }

    class Session internal constructor(internal val token: String, internal val connection: Connection,
        internal val userId: String, internal val loginHost: String) {
        override fun toString() = "PowerOcean session (redacted)"
    }

    fun login(connection: Connection): EcoFlowCloudClient.Result<Session> {
        if (!connection.isValid) return EcoFlowCloudClient.Result.Failure("Check your email, password, inverter serial and model.", false)
        val body = JSONObject().put("email", connection.email)
            .put("password", Base64.encodeToString(connection.password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("scene", "IOT_APP").put("userType", "ECOFLOW").toString()
        val host = if (connection.region == "eu") "api-e.ecoflow.com" else "api-a.ecoflow.com"
        return request(URL("https://$host/auth/login"), connection, body = body) { root ->
            val token = root.getJSONObject("data").getString("token")
            require(token.length in 8..8192 && token.none { it.isWhitespace() || it.isISOControl() })
            val userId = root.getJSONObject("data").optJSONObject("user")?.optString("userId").orEmpty()
            require(userId.isEmpty() || userId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            Session(token, connection, userId, host)
        }
    }

    fun read(session: Session): EcoFlowCloudClient.Result<JSONObject> {
        val connection = session.connection
        val host = if (connection.region == "eu") "api-e.ecoflow.com" else "api-a.ecoflow.com"
        val url = URL("https://$host/provider-service/user/device/detail?sn=${URLEncoder.encode(connection.serial, "UTF-8")}")
        return request(url, connection, session = session) { root -> root.getJSONObject("data") }
    }

    data class PushCredentials(val host: String, val port: Int, val path: String, val account: String, val password: String) {
        override fun toString() = "PowerOcean push credentials (redacted)"
    }

    fun pushCredentials(session: Session): EcoFlowCloudClient.Result<PushCredentials> = request(
        URL("https://${session.loginHost}/iot-auth/enterprise-development/user/certification"), session.connection, session = session
    ) { root ->
        require(session.userId.isNotEmpty())
        val encoded = root.getString("data")
        require(encoded.length <= 16_384)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(session.token.toByteArray(Charsets.UTF_8))
        val cipher = javax.crypto.Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec("ojsajkqjwk1w2dfg".toByteArray(Charsets.UTF_8)))
        val plaintext = cipher.doFinal(Base64.decode(encoded, Base64.DEFAULT))
        try {
            val padding = plaintext.lastOrNull()?.toInt()?.and(255) ?: 0
            val length = if (padding in 1..16 && plaintext.size >= padding && plaintext.takeLast(padding).all { it.toInt().and(255) == padding }) plaintext.size - padding else plaintext.size
            val data = JSONObject(String(plaintext, 0, length, Charsets.UTF_8))
            val host = data.optString("url").lowercase(java.util.Locale.ROOT)
            require(host.matches(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.ecoflow\\.com")))
            val wss = data.optString("protocol").lowercase() in setOf("wss", "websockets")
            val port = if (wss) data.optString("port").toIntOrNull() ?: 8084 else 8084
            val path = data.optString("path").ifEmpty { "/mqtt" }
            require(port in 1..65535 && path.matches(Regex("/[A-Za-z0-9/_-]{1,100}")))
            val account = data.getString("certificateAccount")
            val password = data.getString("certificatePassword")
            require(account.matches(Regex("[A-Za-z0-9_-]{1,200}")) && password.length in 8..300)
            PushCredentials(host, port, path, account, password)
        } finally { plaintext.fill(0); key.fill(0) }
    }

    private fun <T> request(url: URL, account: Connection, body: String? = null, session: Session? = null,
        transform: (JSONObject) -> T): EcoFlowCloudClient.Result<T> {
        var connection: HttpsURLConnection? = null
        return try {
            connection = openConnection(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("lang", "en_US")
            if (session != null) {
                connection.setRequestProperty("authorization", "Bearer ${session.token}")
                connection.setRequestProperty("product-type", account.model)
            }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(result.length + count <= 1_048_576)
                    result.append(buffer, 0, count)
                }
                result.toString()
            }.orEmpty()
            val root = runCatching { JSONObject(text) }.getOrNull()
            if (status in 200..299 && root?.optString("code") == "0") {
                EcoFlowCloudClient.Result.Success(transform(root))
            } else {
                val privateValues = listOf(account.email, account.password, account.serial,
                    Base64.encodeToString(account.password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), session?.token.orEmpty(), session?.userId.orEmpty())
                val message = EcoFlowCloudError.describe(root?.optString("code"), root?.optString("message"), status, privateValues)
                EcoFlowCloudClient.Result.Failure(message + if (status == 401 || status == 403) " Reconnect your account." else "",
                    status == 429 || status >= 500)
            }
        } catch (_: IOException) {
            EcoFlowCloudClient.Result.Failure("PowerOcean cloud could not be reached. Check the phone and inverter internet connection.", true)
        } catch (_: Exception) {
            EcoFlowCloudClient.Result.Failure("PowerOcean returned incomplete or unsupported account data. No grid state was inferred.", false)
        } finally { connection?.disconnect() }
    }
}
