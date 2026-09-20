package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.*
import org.junit.Assert.*
import org.junit.Test

class PendingAlertRetentionTest {
    private fun message(id: String, kind: AlertKind, incident: String = id) =
        AlertMessage(id, kind, "", "", incident)

    @Test fun `heartbeat flooding preserves transitions and newest heartbeat`() {
        val outage = message("incident", AlertKind.OUTAGE)
        val restored = message("incident", AlertKind.RESTORED)
        val input = listOf(outage) + List(600) { message("heartbeat-$it", AlertKind.HEARTBEAT) } + restored
        assertEquals(listOf(outage, input[input.lastIndex - 1], restored), PendingAlertRetention.retain(input))
    }

    @Test fun `updates coalesce per incident rather than across incidents`() {
        val old = message("update-1", AlertKind.OUTAGE_UPDATE, "a")
        val other = message("update-2", AlertKind.OUTAGE_UPDATE, "b")
        val newest = message("update-3", AlertKind.OUTAGE_UPDATE, "a")
        assertEquals(listOf(other, newest), PendingAlertRetention.retain(listOf(old, other, newest)))
    }

    @Test fun `essential overflow is retained and discarded tests produce a visible notice`() {
        val incidents = List(501) { message("incident-$it", AlertKind.OUTAGE) }
        assertEquals(incidents, PendingAlertRetention.retain(incidents))
        val retained = PendingAlertRetention.retain(incidents + message("test", AlertKind.TEST))
        assertTrue(retained.containsAll(incidents))
        assertFalse(retained.any { it.kind == AlertKind.TEST })
        assertTrue(retained.any { it.kind == AlertKind.SOURCE_DATA_WARNING && it.body.contains("omitted") })
    }
}
