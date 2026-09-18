package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResendEmailProtocolTest {
    @Test fun `validates plain and named sender addresses`() {
        assertTrue(ResendEmailProtocol.isValidEmailAddress("alerts@example.com"))
        assertTrue(ResendEmailProtocol.isValidSender("Flockle Grid Outage Monitor <alerts@example.com>"))
        assertFalse(ResendEmailProtocol.isValidSender("alerts at example.com"))
    }

    @Test fun `creates stable recipient-specific idempotency keys`() {
        val message = AlertMessage("event-1", AlertKind.OUTAGE, "Outage", "Power lost")
        val first = ResendEmailProtocol.idempotencyKey(message, "One@Example.com")

        assertEquals(first, ResendEmailProtocol.idempotencyKey(message, "one@example.com"))
        assertTrue(first != ResendEmailProtocol.idempotencyKey(message, "two@example.com"))
        assertTrue(first.length <= 256)
    }

    @Test fun `classifies transient and permanent service failures`() {
        assertTrue(ResendEmailProtocol.deliveryResult(status = 429, apiKey = "re_secret")
            is DeliveryResult.RetryableFailure)
        assertTrue(ResendEmailProtocol.deliveryResult(status = 503, apiKey = "re_secret")
            is DeliveryResult.RetryableFailure)
        assertTrue(ResendEmailProtocol.deliveryResult(status = 401, apiKey = "re_secret")
            is DeliveryResult.PermanentFailure)
    }

    @Test fun `sanitizes secrets from provider errors`() {
        val result = ResendEmailProtocol.deliveryResult(
            status = 400,
            errorMessage = "bad key re_secret_value_1234567890",
            apiKey = "re_secret_value_1234567890"
        ) as DeliveryResult.PermanentFailure

        assertFalse(result.reason.contains("re_secret_value_1234567890"))
    }
}
