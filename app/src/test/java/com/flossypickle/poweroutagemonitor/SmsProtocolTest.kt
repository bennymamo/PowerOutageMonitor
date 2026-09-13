package com.flossypickle.poweroutagemonitor

import android.app.Activity
import android.telephony.SmsManager
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertProviderRegistry
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsProtocolTest {
    @Test fun `normalizes readable international phone numbers`() {
        assertEquals("+35699123456", SmsProtocol.normalizeNumber("+356 9912-3456"))
        assertTrue(SmsProtocol.isValidNumber("+356 9912 3456"))
        assertFalse(SmsProtocol.isValidNumber("call-me"))
    }

    @Test fun `formats title and body with a defensive size limit`() {
        val message = AlertMessage("id", AlertKind.OUTAGE, "OUTAGE", "Power lost")
        assertEquals("OUTAGE\n\nPower lost", SmsProtocol.messageText(message))
        assertTrue(SmsProtocol.messageText(message.copy(body = "x".repeat(2_000))).length <= 1_600)
    }

    @Test fun `maps Android sent results to queue outcomes`() {
        assertTrue(SmsProtocol.resultForPart(Activity.RESULT_OK) is DeliveryResult.Sent)
        assertTrue(SmsProtocol.resultForPart(SmsManager.RESULT_ERROR_NO_SERVICE)
            is DeliveryResult.RetryableFailure)
        assertTrue(SmsProtocol.resultForPart(SmsManager.RESULT_ERROR_NULL_PDU)
            is DeliveryResult.PermanentFailure)
        assertTrue(SmsProtocol.resultForPart(
            SmsManager.RESULT_ERROR_GENERIC_FAILURE,
            noDefaultSubscription = true
        ) is DeliveryResult.PermanentFailure)
    }

    @Test fun `one permanent multipart failure takes precedence`() {
        val result = SmsProtocol.combinedResult(listOf(
            DeliveryResult.Sent(),
            DeliveryResult.RetryableFailure("later"),
            DeliveryResult.PermanentFailure("bad number")
        ))
        assertTrue(result is DeliveryResult.PermanentFailure)
    }

    @Test fun `SMS delivery does not wait for internet`() {
        assertFalse(AlertProviderRegistry.requiresInternet(SmsConfigStore.PROVIDER_ID))
        assertTrue(AlertProviderRegistry.requiresInternet("telegram"))
    }
}
