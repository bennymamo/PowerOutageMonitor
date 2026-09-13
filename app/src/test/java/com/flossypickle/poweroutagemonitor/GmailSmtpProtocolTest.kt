package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailSmtpProtocolTest {
    @Test fun `accepts grouped Google app passwords`() {
        assertTrue(GmailSmtpProtocol.isValidAppPassword("abcd efgh ijkl mnop"))
        assertEquals("abcdefghijklmnop",
            GmailSmtpProtocol.normalizedAppPassword("abcd efgh ijkl mnop"))
        assertFalse(GmailSmtpProtocol.isValidAppPassword("ordinary-password"))
    }

    @Test fun `encodes UTF-8 using standard base64`() {
        assertEquals("R1JJRCBPSw==", GmailSmtpProtocol.encodeBase64("GRID OK"))
        assertEquals("8J+UlQ==", GmailSmtpProtocol.encodeBase64("🔕"))
    }

    @Test fun `builds one-recipient MIME message without exposing body text`() {
        val data = GmailSmtpProtocol.messageData(
            "sender@gmail.com",
            "recipient@example.com",
            AlertMessage("event-1", AlertKind.OUTAGE, "Grid outage", "Power is offline")
        )
        assertTrue(data.contains("From: FP Grid Monitor <sender@gmail.com>"))
        assertTrue(data.contains("To: <recipient@example.com>"))
        assertTrue(data.contains("Content-Transfer-Encoding: base64"))
        assertFalse(data.contains("Power is offline"))
    }

    @Test fun `retries temporary SMTP replies and stops on permanent replies`() {
        assertTrue(GmailSmtpProtocol.failureResult(421, "421 try later")
            is DeliveryResult.RetryableFailure)
        assertTrue(GmailSmtpProtocol.failureResult(535, "535 bad credentials")
            is DeliveryResult.PermanentFailure)
    }

    @Test fun `redacts password-like values from SMTP errors`() {
        val result = GmailSmtpProtocol.failureResult(535, "535 abcdefghijklmnop rejected")
            as DeliveryResult.PermanentFailure
        assertFalse(result.reason.contains("abcdefghijklmnop"))
    }
}
