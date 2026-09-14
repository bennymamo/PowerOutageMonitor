package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

/** Read-only client for EcoFlow's documented Developer API. */
internal class EcoFlowCloudClient(
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = {
        (SecureRandom().nextInt(900_000) + 100_000).toString()
    }
) {
    data class Credentials(val accessKey: String, val secretKey: String) {
        val isValid: Boolean get() = accessKey.length in 8..200 && secretKey.length in 8..300 &&
            accessKey.none(Char::isWhitespace) && secretKey.none(Char::isWhitespace)
    }

    data class Device(val serialNumber: String, val name: String, val online: Boolean)

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data class Failure(val message: String, val retryable: Boolean) : Result<Nothing>
    }

    fun listDevices(credentials: Credentials): Result<List<Device>> = request(
        path = DEVICE_LIST_PATH,
        parameters = emptyMap(),
        credentials = credentials
    ) { root ->
        val data = root.getJSONArray("data")
        buildList {
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                val serial = item.optString("sn").trim()
                if (serial.isEmpty()) continue
                add(
                    Device(
                        serialNumber = serial,
                        name = item.optString("deviceName").ifBlank { "EcoFlow device" },
                        online = item.optInt("online", 0) == 1
                    )
                )
            }
        }
    }

    fun readPowerOceanQuota(
        credentials: Credentials,
        serialNumber: String
    ): Result<EcoFlowCloudQuota> {
        val cleanSerial = serialNumber.trim()
        if (cleanSerial.isEmpty() || cleanSerial.length > 100 || cleanSerial.any(Char::isWhitespace)) {
            return Result.Failure("Device serial number is not valid", retryable = false)
        }
        return request(
            path = ALL_QUOTA_PATH,
            parameters = mapOf("sn" to cleanSerial),
            credentials = credentials
        ) { root -> parsePowerOceanQuota(root.getJSONObject("data")) }
    }

    private fun <T> request(
        path: String,
        parameters: Map<String, String>,
        credentials: Credentials,
        transform: (JSONObject) -> T
    ): Result<T> {
        if (!credentials.isValid) {
            return Result.Failure("EcoFlow access key or secret key format is not valid", retryable = false)
        }
        val timestamp = clock()
        val requestNonce = nonce()
        val signature = EcoFlowCloudSigner.sign(
            parameters,
            credentials.accessKey,
            credentials.secretKey,
            requestNonce,
            timestamp
        )
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val url = "$API_HOST$path" + if (query.isEmpty()) "" else "?$query"
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpsURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("accessKey", credentials.accessKey)
            connection.setRequestProperty("nonce", requestNonce)
            connection.setRequestProperty("timestamp", timestamp.toString())
            connection.setRequestProperty("sign", signature)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val root = runCatching { JSONObject(body) }.getOrNull()
            if (status in 200..299 && root?.optString("code") == "0") {
                runCatching { Result.Success(transform(root)) }
                    .getOrElse { Result.Failure("EcoFlow returned incomplete PowerOcean data", false) }
            } else {
                val safeMessage = root?.optString("message")
                    ?.take(MAX_ERROR_LENGTH)
                    ?.takeIf(String::isNotBlank)
                    ?: "EcoFlow returned HTTP $status"
                Result.Failure(safeMessage, retryable = status == 429 || status >= 500 || status <= 0)
            }
        } catch (_: IOException) {
            Result.Failure("EcoFlow Cloud could not be reached", retryable = true)
        } catch (_: Exception) {
            Result.Failure("EcoFlow Cloud response could not be read", retryable = false)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parsePowerOceanQuota(data: JSONObject): EcoFlowCloudQuota = EcoFlowCloudQuota(
        phaseVoltages = listOfNotNull(
            data.number("pcsAPhase.vol", "pcsAPhase", "vol"),
            data.number("pcsBPhase.vol", "pcsBPhase", "vol"),
            data.number("pcsCPhase.vol", "pcsCPhase", "vol")
        ),
        gridPowerWatts = data.number("sysGridPwr"),
        loadPowerWatts = data.number("sysLoadPwr"),
        solarPowerWatts = data.number("mpptPwr"),
        batteryPowerWatts = data.number("bpPwr"),
        batteryPercent = data.number("bpSoc")
    )

    private fun JSONObject.number(flatKey: String, objectKey: String? = null, childKey: String? = null): Double? {
        if (has(flatKey) && !isNull(flatKey)) return opt(flatKey).toDoubleOrNull()
        if (objectKey != null && childKey != null) {
            val nested = optJSONObject(objectKey) ?: return null
            if (nested.has(childKey) && !nested.isNull(childKey)) return nested.opt(childKey).toDoubleOrNull()
        }
        return null
    }

    private fun Any?.toDoubleOrNull(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }?.takeIf(Double::isFinite)

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val API_HOST = "https://api.ecoflow.com"
        private const val DEVICE_LIST_PATH = "/iot-open/sign/device/list"
        private const val ALL_QUOTA_PATH = "/iot-open/sign/device/quota/all"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_ERROR_LENGTH = 240
    }
}
