package com.flossypickle.poweroutagemonitor.integrations.alerts.sms

import android.app.Activity
import android.telephony.SmsManager
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult

/** Pure phone-number, message and Android sent-result rules for device SMS. */
internal object SmsProtocol {
    fun normalizeNumber(value: String): String = buildString {
        value.trim().forEachIndexed { index, character ->
            when {
                character.isDigit() -> append(character)
                character == '+' && index == 0 -> append(character)
            }
        }
    }

    fun isValidNumber(value: String): Boolean {
        val normalized = normalizeNumber(value)
        return normalized.matches(Regex("\\+?[0-9]{5,15}"))
    }

    fun messageText(message: AlertMessage): String = "${message.title}\n\n${message.body}".take(1_600)

    fun resultForPart(resultCode: Int, noDefaultSubscription: Boolean = false): DeliveryResult {
        if (resultCode == Activity.RESULT_OK) return DeliveryResult.Sent()
        if (noDefaultSubscription) {
            return DeliveryResult.PermanentFailure("Choose a default SMS SIM in Android settings")
        }
        return when (resultCode) {
            SmsManager.RESULT_ERROR_RADIO_OFF ->
                DeliveryResult.RetryableFailure("Mobile radio is off")
            SmsManager.RESULT_ERROR_NO_SERVICE ->
                DeliveryResult.RetryableFailure("Mobile service is unavailable")
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED ->
                DeliveryResult.RetryableFailure("The carrier SMS limit was reached")
            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                DeliveryResult.RetryableFailure("The mobile network rejected the SMS temporarily")
            SmsManager.RESULT_ERROR_NULL_PDU ->
                DeliveryResult.PermanentFailure("Android could not create the SMS")
            else -> DeliveryResult.PermanentFailure("SMS failed with Android code $resultCode")
        }
    }

    fun combinedResult(results: List<DeliveryResult>): DeliveryResult {
        if (results.all { it is DeliveryResult.Sent }) return DeliveryResult.Sent()
        return results.firstOrNull { it is DeliveryResult.PermanentFailure }
            ?: results.firstOrNull { it is DeliveryResult.RetryableFailure }
            ?: DeliveryResult.RetryableFailure("SMS result was not received")
    }
}
