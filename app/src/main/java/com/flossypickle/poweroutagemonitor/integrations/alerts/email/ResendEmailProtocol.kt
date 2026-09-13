package com.flossypickle.poweroutagemonitor.integrations.alerts.email

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import java.util.UUID

/** Pure validation, retry classification and duplicate protection for Resend email. */
internal object ResendEmailProtocol {
    private val emailPattern = Regex(
        "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
        RegexOption.IGNORE_CASE
    )
    private val namedSenderPattern = Regex("^.{1,80}\\s<([^<>]+)>$")

    fun isValidApiKey(value: String): Boolean =
        value.length in 20..200 && value.startsWith("re_") && value.none(Char::isWhitespace)

    fun isValidEmailAddress(value: String): Boolean =
        value.length <= 254 && emailPattern.matches(value.trim())

    fun isValidSender(value: String): Boolean {
        val trimmed = value.trim()
        return isValidEmailAddress(trimmed) ||
            namedSenderPattern.matchEntire(trimmed)?.groupValues?.getOrNull(1)
                ?.let(::isValidEmailAddress) == true
    }

    fun idempotencyKey(message: AlertMessage, recipient: String): String {
        val identity = "${message.eventId}|${message.kind}|${recipient.lowercase()}"
        return "fp-grid/${UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))}"
    }

    fun deliveryResult(
        status: Int,
        providerMessageId: String? = null,
        errorName: String = "",
        errorMessage: String? = null,
        apiKey: String
    ): DeliveryResult {
        if (status in 200..299) {
            return DeliveryResult.Sent(providerMessageId?.takeIf(String::isNotBlank))
        }
        val message = errorMessage
            ?.replace(apiKey, "••••")
            ?.take(300)
            ?.takeIf(String::isNotBlank)
            ?: "Email service returned HTTP $status"
        val retryable = status in listOf(408, 425, 429) || status >= 500 ||
            status <= 0 || status == 409 && errorName == "concurrent_idempotent_requests"
        return if (retryable) {
            DeliveryResult.RetryableFailure(message)
        } else {
            DeliveryResult.PermanentFailure(message)
        }
    }
}
