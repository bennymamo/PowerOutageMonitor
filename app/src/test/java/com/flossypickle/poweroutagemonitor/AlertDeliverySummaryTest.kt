package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliverySummary
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertQueueEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertDeliverySummaryTest {
    @Test
    fun `summarizes every destination for an event without exposing destination ids`() {
        val deliveries = listOf(
            item("one", AlertQueueEngine.Status.SENT),
            item("two", AlertQueueEngine.Status.RETRYING),
            item("three", AlertQueueEngine.Status.FAILED)
        )

        val summary = AlertDeliverySummary.byEvent(deliveries).getValue("power-event-10")

        assertEquals(1, summary.sent)
        assertEquals(1, summary.retrying)
        assertEquals(1, summary.failed)
        assertEquals("1 sent · 1 retrying · 1 failed", summary.label())
    }

    private fun item(id: String, status: AlertQueueEngine.Status) = AlertQueueEngine.Item(
        id = id,
        providerId = "telegram",
        destinationId = "secret-chat-id-$id",
        message = AlertMessage("power-event-10", AlertKind.OUTAGE, "title", "body"),
        status = status,
        createdAtEpochMs = 10L
    )
}
