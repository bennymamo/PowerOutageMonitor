package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import org.json.JSONObject
import org.json.JSONArray
import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetryFlattener
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
    },
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
) {
    data class Credentials(val accessKey: String, val secretKey: String) {
        val isValid: Boolean get() = accessKey.length in 8..200 && secretKey.length in 8..300 &&
            accessKey.none(Char::isWhitespace) && secretKey.none(Char::isWhitespace)

        override fun toString(): String = "EcoFlow credentials (redacted)"
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
        serialNumber: String,
        requestedFields: Boolean = false
    ): Result<EcoFlowCloudQuota> {
        val cleanSerial = serialNumber.trim()
        if (cleanSerial.isEmpty() || cleanSerial.length > 100 || cleanSerial.any(Char::isWhitespace)) {
            return Result.Failure("Device serial number is not valid", retryable = false)
        }
        val body = if (requestedFields) JSONObject().put("sn", cleanSerial)
            .put("params", JSONObject().put("quotas", JSONArray(EcoFlowPowerOceanRequest.fields))).toString() else null
        return request(
            path = if (requestedFields) REQUEST_QUOTA_PATH else ALL_QUOTA_PATH,
            parameters = if (requestedFields) EcoFlowPowerOceanRequest.signingParameters(cleanSerial) else mapOf("sn" to cleanSerial),
            credentials = credentials,
            readOnlyPostBody = body
        ) { root -> parsePowerOceanQuota(root.getJSONObject("data")).copy(requestedFields = requestedFields) }
    }

    private fun <T> request(
        path: String,
        parameters: Map<String, String>,
        credentials: Credentials,
        readOnlyPostBody: String? = null,
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
        require(readOnlyPostBody == null || path == REQUEST_QUOTA_PATH) { "Unsupported read-only request" }
        val url = "$API_HOST$path" + if (query.isEmpty() || readOnlyPostBody != null) "" else "?$query"
        var connection: HttpsURLConnection? = null
        return try {
            connection = openConnection(URL(url))
            connection.requestMethod = if (readOnlyPostBody == null) "GET" else "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("accessKey", credentials.accessKey)
            connection.setRequestProperty("nonce", requestNonce)
            connection.setRequestProperty("timestamp", timestamp.toString())
            connection.setRequestProperty("sign", signature)
            if (readOnlyPostBody != null) {
                val bytes = readOnlyPostBody.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use {
                val buffer = CharArray(4_096)
                val text = StringBuilder()
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= MAX_RESPONSE_CHARS) { "Oversized telemetry response" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }.orEmpty()
            val root = runCatching { JSONObject(body) }.getOrNull()
            if (status in 200..299 && root?.optString("code") == "0") {
                runCatching { Result.Success(transform(root)) }
                    .getOrElse { Result.Failure("EcoFlow returned incomplete PowerOcean data", false) }
            } else {
                val safeMessage = EcoFlowCloudError.describe(root?.optString("code"), root?.optString("message"), status,
                    listOf(credentials.accessKey, credentials.secretKey, parameters["sn"].orEmpty()))
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

    private fun parsePowerOceanQuota(data: JSONObject): EcoFlowCloudQuota {
        val reported = SourceTelemetryFlattener.flatten(jsonValue(data, 0))
        return EcoFlowCloudQuota(
            phaseVoltages = listOfNotNull(
                data.number("pcsAPhase.vol", "pcsAPhase", "vol"),
                data.number("pcsBPhase.vol", "pcsBPhase", "vol"),
                data.number("pcsCPhase.vol", "pcsCPhase", "vol")
        ),
            gridPowerWatts = data.number("sysGridPwr"),
            loadPowerWatts = data.number("sysLoadPwr"),
            solarPowerWatts = data.number("mpptPwr"),
            batteryPowerWatts = data.number("bpPwr"),
            batteryPercent = data.number("bpSoc"),
            reportedValues = reported.values,
            omittedValues = reported.omittedValues
        )
    }

    private fun jsonValue(value: Any?, depth: Int): Any? {
        require(depth <= 12) { "Device telemetry is nested too deeply" }
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.opt(it), depth + 1) }
            is JSONArray -> List(value.length()) { jsonValue(value.opt(it), depth + 1) }
            else -> value
        }
    }

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
        private const val REQUEST_QUOTA_PATH = "/iot-open/sign/device/quota"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_CHARS = 1_048_576
    }
}
