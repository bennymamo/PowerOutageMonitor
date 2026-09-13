package com.flossypickle.poweroutagemonitor.integrations.alerts.email

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class ResendEmailClient {
    fun send(apiKey: String, sender: String, recipient: String, message: AlertMessage): DeliveryResult {
        if (!ResendEmailProtocol.isValidApiKey(apiKey)) {
            return DeliveryResult.PermanentFailure("Resend API key format is not valid")
        }
        if (!ResendEmailProtocol.isValidSender(sender)) {
            return DeliveryResult.PermanentFailure("Sender email address is not valid")
        }
        if (!ResendEmailProtocol.isValidEmailAddress(recipient)) {
            return DeliveryResult.PermanentFailure("Recipient email address is not valid")
        }
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "Idempotency-Key",
                ResendEmailProtocol.idempotencyKey(message, recipient)
            )
            val payload = JSONObject().apply {
                put("from", sender)
                put("to", JSONArray(listOf(recipient)))
                put("subject", message.title.take(SUBJECT_LIMIT))
                put("text", message.body)
            }
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            ResendEmailProtocol.deliveryResult(
                status = status,
                providerMessageId = response?.optString("id"),
                errorName = response?.optString("name").orEmpty(),
                errorMessage = response?.optString("message"),
                apiKey = apiKey
            )
        } catch (_: IOException) {
            DeliveryResult.RetryableFailure("Email service could not be reached")
        } catch (_: Exception) {
            DeliveryResult.PermanentFailure("Email request could not be completed")
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.resend.com/emails"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val SUBJECT_LIMIT = 998
    }
}
