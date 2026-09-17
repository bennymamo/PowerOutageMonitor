package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemotePolicy
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.*
import org.junit.Assert.*
import org.junit.Test

class TelegramRemotePolicyTest {
    private val message = TelegramRemotePolicy.Message(12, "123", "123", true, false, false, 100_000, "/monitor_off")
    private fun authorize(value: TelegramRemotePolicy.Message = message, trusted: Set<String> = setOf("123"),
        enabledAt: Long = 90_000, now: Long = 110_000, offset: Long = 12) =
        TelegramRemotePolicy.authorize(value, trusted, enabledAt, now, offset)
    @Test fun trustedPrivatePersonCanControl() { assertEquals("monitor_off", authorize()?.name) }
    @Test fun unknownChatCannotControl() { assertNull(authorize(trusted = setOf("999"))) }
    @Test fun groupCannotControlEvenWhenAllowlisted() { assertNull(authorize(message.copy(privateChat = false))) }
    @Test fun impersonatedSenderCannotControl() { assertNull(authorize(message.copy(senderId = "999"))) }
    @Test fun forwardedCommandCannotControl() { assertNull(authorize(message.copy(forwarded = true))) }
    @Test fun botCannotControl() { assertNull(authorize(message.copy(senderIsBot = true))) }
    @Test fun alreadyHandledCommandCannotReplay() { assertNull(authorize(offset = 13)) }
    @Test fun commandsBeforeOptInCannotControl() { assertNull(authorize(enabledAt = 101_000)) }
    @Test fun staleAndFutureCommandsCannotControl() {
        assertNull(authorize(now = 400_001)); assertNull(authorize(now = 69_999))
    }
    @Test fun onlyKnownShortCommandsAccepted() {
        assertNull(authorize(message.copy(text = "monitor_off")))
        assertNull(authorize(message.copy(text = "/change_password")))
        assertNull(authorize(message.copy(text = "/quiet " + "1".repeat(201))))
    }
    @Test fun quietArgumentAndBotSuffixParsed() {
        assertEquals(TelegramRemotePolicy.Command("quiet", "30"), authorize(message.copy(text = "/quiet@mybot 30")))
    }
    @Test fun remoteDefaultsOffAndPrivateIdsRequired() {
        assertFalse(TelegramRemoteStore.Settings().enabled)
        assertTrue(TelegramRemoteStore.Settings().longPolling)
        assertThrows(IllegalArgumentException::class.java) { TelegramRemoteStore.Settings(trustedChatIds = setOf("-123")) }
        assertThrows(IllegalArgumentException::class.java) { TelegramRemoteStore.Settings(pollSeconds = 1) }
    }
    @Test fun quietSkipIsTerminalAndDoesNotBlockRestoration() {
        val outage = AlertQueueEngine.Item("1", "telegram", "123", AlertMessage("e", AlertKind.OUTAGE, "Outage", "body"), createdAtEpochMs = 100)
        val skipped = AlertQueueEngine.complete(outage, DeliveryResult.Skipped("Quiet"), 110)
        val restored = outage.copy(id = "2", message = outage.message.copy(kind = AlertKind.RESTORED))
        assertEquals(AlertQueueEngine.Status.SKIPPED, skipped.status)
        assertNull(AlertQueueEngine.nextRunnableAt(skipped))
        assertEquals(listOf(restored), AlertQueueEngine.due(listOf(skipped, restored), 200))
        assertEquals("1 skipped", AlertDeliverySummary.byEvent(listOf(skipped)).getValue("e").label())
    }
}
