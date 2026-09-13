package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertQueueEngine
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertQueueEngineTest {
    @Test
    fun `duplicate event provider and destination is enqueued once`() {
        val item = item()
        val once = AlertQueueEngine.enqueue(emptyList(), item)
        val twice = AlertQueueEngine.enqueue(once, item.copy(id = "another-id"))

        assertEquals(1, twice.size)
        assertEquals("delivery-1", twice.single().id)
    }

    @Test
    fun `network failure remains pending then sends exactly once`() {
        val claimed = AlertQueueEngine.markInFlight(item(), 1_000L)
        val retrying = AlertQueueEngine.complete(
            claimed,
            DeliveryResult.RetryableFailure("No network"),
            2_000L
        )

        assertEquals(AlertQueueEngine.Status.RETRYING, retrying.status)
        assertTrue(AlertQueueEngine.due(listOf(retrying), retrying.nextAttemptAtEpochMs - 1).isEmpty())
        assertEquals(listOf(retrying), AlertQueueEngine.due(listOf(retrying), retrying.nextAttemptAtEpochMs))

        val secondClaim = AlertQueueEngine.markInFlight(retrying, retrying.nextAttemptAtEpochMs)
        val sent = AlertQueueEngine.complete(
            secondClaim,
            DeliveryResult.Sent("provider-message-7"),
            secondClaim.lastAttemptAtEpochMs!!
        )

        assertEquals(AlertQueueEngine.Status.SENT, sent.status)
        assertEquals(2, sent.attemptCount)
        assertEquals("provider-message-7", sent.providerMessageId)
        assertTrue(AlertQueueEngine.due(listOf(sent), Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `expired in-flight lease becomes due after process death`() {
        val claimed = AlertQueueEngine.markInFlight(item(), 10_000L)

        assertFalse(AlertQueueEngine.due(listOf(claimed), claimed.leaseUntilEpochMs!! - 1).isNotEmpty())
        assertEquals(listOf(claimed), AlertQueueEngine.due(listOf(claimed), claimed.leaseUntilEpochMs!!))
    }

    @Test
    fun `permanent provider error never retries`() {
        val claimed = AlertQueueEngine.markInFlight(item(), 1_000L)
        val failed = AlertQueueEngine.complete(
            claimed,
            DeliveryResult.PermanentFailure("Invalid destination"),
            2_000L
        )

        assertEquals(AlertQueueEngine.Status.FAILED, failed.status)
        assertEquals("Invalid destination", failed.lastError)
        assertTrue(AlertQueueEngine.due(listOf(failed), Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `retry delay grows and is capped at six hours`() {
        assertEquals(60_000L, AlertQueueEngine.retryDelayMs(1))
        assertEquals(5 * 60_000L, AlertQueueEngine.retryDelayMs(2))
        assertEquals(6 * 60 * 60_000L, AlertQueueEngine.retryDelayMs(100))
    }

    @Test
    fun `user retry resets a permanent failure for an immediate clean attempt`() {
        val failed = AlertQueueEngine.complete(
            AlertQueueEngine.markInFlight(item(), 1_000L),
            DeliveryResult.PermanentFailure("invalid token"),
            2_000L
        )

        val retried = AlertQueueEngine.retryFailed(failed, 9_000L)

        assertEquals(AlertQueueEngine.Status.PENDING, retried.status)
        assertEquals(0, retried.attemptCount)
        assertEquals(9_000L, retried.nextAttemptAtEpochMs)
        assertEquals(null, retried.lastError)
        assertEquals(listOf(retried), AlertQueueEngine.due(listOf(retried), 9_000L))
    }

    @Test
    fun `restoration waits for its outage message at the same destination`() {
        val outage = item()
        val restored = item(
            id = "delivery-restored",
            kind = AlertKind.RESTORED,
            createdAtEpochMs = 2_000L
        )

        assertEquals(listOf(outage), AlertQueueEngine.due(listOf(outage, restored), 3_000L))
        assertTrue(AlertQueueEngine.hasUnfinishedPredecessor(listOf(outage, restored), restored))
    }

    @Test
    fun `terminal outage unblocks restoration even when outage delivery failed`() {
        val failedOutage = AlertQueueEngine.complete(
            AlertQueueEngine.markInFlight(item(), 1_000L),
            DeliveryResult.PermanentFailure("Invalid destination"),
            2_000L
        )
        val restored = item(
            id = "delivery-restored",
            kind = AlertKind.RESTORED,
            createdAtEpochMs = 3_000L
        )

        assertEquals(
            listOf(restored),
            AlertQueueEngine.due(listOf(failedOutage, restored), 3_000L)
        )
        assertEquals(
            restored,
            AlertQueueEngine.nextUnfinishedForEvent(listOf(failedOutage, restored), failedOutage)
        )
    }

    @Test
    fun `battery warning is ordered between outage and restoration`() {
        val sentOutage = AlertQueueEngine.complete(
            AlertQueueEngine.markInFlight(item(), 1_000L),
            DeliveryResult.Sent(),
            2_000L
        )
        val batteryLow = item(
            id = "delivery-battery",
            kind = AlertKind.BATTERY_LOW,
            createdAtEpochMs = 3_000L
        )
        val restored = item(
            id = "delivery-restored",
            kind = AlertKind.RESTORED,
            createdAtEpochMs = 4_000L
        )

        assertEquals(
            listOf(batteryLow),
            AlertQueueEngine.due(listOf(sentOutage, batteryLow, restored), 5_000L)
        )
        assertEquals(
            listOf(batteryLow),
            AlertQueueEngine.sequenceHeads(listOf(sentOutage, batteryLow, restored))
        )
    }

    private fun item(
        id: String = "delivery-1",
        kind: AlertKind = AlertKind.OUTAGE,
        createdAtEpochMs: Long = 0L
    ) = AlertQueueEngine.Item(
        id = id,
        providerId = "telegram",
        destinationId = "garage-chat",
        message = AlertMessage(
            eventId = "event-1",
            kind = kind,
            title = "Power outage detected",
            body = "Garage monitor is on battery"
        ),
        createdAtEpochMs = createdAtEpochMs
    )
}
