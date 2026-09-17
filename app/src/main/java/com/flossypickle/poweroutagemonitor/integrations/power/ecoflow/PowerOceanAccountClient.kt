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
    private class UnsupportedResponse(message: String) : Exception(message)
    private fun requireResponse(condition: Boolean, message: String) {
        if (!condition) throw UnsupportedResponse(message)
    }
    private fun <T> responseStep(message: String, action: () -> T): T = try { action() }
        catch (known: UnsupportedResponse) { throw known }
        catch (_: Exception) { throw UnsupportedResponse(message) }
    data class Connection(val email: String, val password: String, val serial: String,
        val model: String = "86", val region: String = "eu", val refreshSeconds: Int = 60) {
        val isValid get() = email.length in 3..254 && email.contains('@') && email.none(Char::isWhitespace) &&
            password.length in 1..300 && serial.matches(Regex("[A-Za-z0-9_-]{4,100}")) &&
            model in setOf("83", "85", "86", "87") && region in setOf("eu", "us") && refreshSeconds in 10..3600
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

    data class PushCredentials(val host: String, val port: Int, val path: String, val account: String, val password: String, val transport: String = "wss") {
        override fun toString() = "PowerOcean push credentials (redacted)"
    }

    fun pushCredentials(session: Session): EcoFlowCloudClient.Result<PushCredentials> {
        // PowerOcean live activation uses the portal's secure WebSocket connection.
        // The mobile TLS route can return quotas without waking the live energy stream.
        val portalResult = portalPushCredentials(session)
        if (portalResult is EcoFlowCloudClient.Result.Success ||
            portalResult is EcoFlowCloudClient.Result.Failure && portalResult.retryable) return portalResult
        val appResult = request(URL("https://${session.loginHost}/iot-auth/app/certification"), session.connection, session = session) { root ->
            requireResponse(session.userId.isNotEmpty(), "Live-feed setup failed: account login did not include the user ID required for MQTT.")
            val details = responseStep("Mobile-app live access did not return connection details in the expected format.") { root.getJSONObject("data") }
            parsePushDetails(details, appTransport = true)
        }
        return if (appResult is EcoFlowCloudClient.Result.Failure) EcoFlowCloudClient.Result.Failure(
            "Portal route: ${(portalResult as EcoFlowCloudClient.Result.Failure).message} Mobile-app route: ${appResult.message}", appResult.retryable
        ) else appResult
    }

    private fun portalPushCredentials(session: Session): EcoFlowCloudClient.Result<PushCredentials> = request(
        URL("https://${session.loginHost}/iot-auth/enterprise-development/user/certification"), session.connection, session = session
    ) { root ->
        requireResponse(session.userId.isNotEmpty(), "Live-feed setup failed: account login did not include the user ID required for MQTT.")
        val encoded = root.opt("data")
        requireResponse(encoded is String && encoded.length in 1..16_384, "Live-feed setup failed: EcoFlow did not return encrypted connection details in the expected format.")
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(session.token.toByteArray(Charsets.UTF_8))
        val plaintext = responseStep("Live-feed setup failed: this device could not decrypt EcoFlow's connection details.") {
            try {
                val cipher = javax.crypto.Cipher.getInstance("AES/CFB128/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.IvParameterSpec("ojsajkqjwk1w2dfg".toByteArray(Charsets.UTF_8)))
                cipher.doFinal(Base64.decode(encoded as String, Base64.DEFAULT))
            } finally { key.fill(0) }
        }
        try {
            val padding = plaintext.lastOrNull()?.toInt()?.and(255) ?: 0
            val length = if (padding in 1..16 && plaintext.size >= padding && plaintext.takeLast(padding).all { it.toInt().and(255) == padding }) plaintext.size - padding else plaintext.size
            val data = responseStep("Live-feed setup failed: decrypted connection details were not valid JSON.") {
                JSONObject(String(plaintext, 0, length, Charsets.UTF_8))
            }
            parsePushDetails(data, appTransport = false)
        } finally { plaintext.fill(0); key.fill(0) }
    }

    private fun parsePushDetails(data: JSONObject, appTransport: Boolean): PushCredentials {
            val host = data.optString("url").lowercase(java.util.Locale.ROOT)
            requireResponse(host.matches(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.ecoflow\\.com")), "Live-feed setup failed: the reported broker address was missing or unsupported.")
            val protocol = data.optString("protocol").lowercase(java.util.Locale.ROOT)
            val tls = appTransport && protocol == "mqtts"
            requireResponse(!appTransport || protocol in setOf("mqtts", "wss", "websockets"), "Mobile-app live access did not provide a supported secure transport.")
            val wss = protocol in setOf("wss", "websockets")
            val port = if (tls || wss) data.optString("port").toIntOrNull() ?: if (tls) 8883 else 8084 else 8084
            val path = if (tls) "" else data.optString("path").ifEmpty { "/mqtt" }
            requireResponse(port in 1..65535 && (tls || path.matches(Regex("/[A-Za-z0-9/_-]{1,100}"))), "Live-feed setup failed: the reported secure port or path was unsupported.")
            val account = data.optString("certificateAccount")
            val password = data.optString("certificatePassword")
            requireResponse(account.isNotEmpty(), "Live-feed setup failed: EcoFlow's connection details did not include a broker account.")
            requireResponse(account.length <= 512 && account.none(Char::isISOControl), "Live-feed setup failed: the broker account exceeded the supported length or contained control characters.")
            requireResponse(password.length in 1..4096, "Live-feed setup failed: the broker password was missing or had an unsupported length.")
            return PushCredentials(host, port, path, account, password, if (tls) "ssl" else "wss")
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
                    status >= 500)
            }
        } catch (known: UnsupportedResponse) {
            EcoFlowCloudClient.Result.Failure(known.message ?: "Unsupported live-feed connection response.", false)
        } catch (_: IOException) {
            EcoFlowCloudClient.Result.Failure("PowerOcean cloud could not be reached. Check the phone and inverter internet connection.", true)
        } catch (_: Exception) {
            EcoFlowCloudClient.Result.Failure("PowerOcean returned incomplete or unsupported account data. No grid state was inferred.", false)
        } finally { connection?.disconnect() }
    }
}
