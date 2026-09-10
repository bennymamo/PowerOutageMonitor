package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.diagnostics.SystemHealthSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemHealthSnapshotTest {
    @Test
    fun `healthy monitoring has no attention message`() {
        val health = SystemHealthSnapshot(
            internetAvailable = true,
            notificationsAllowed = true,
            backgroundRestricted = false
        )

        assertNull(health.monitoringAttention(monitoringEnabled = true))
    }

    @Test
    fun `disabled monitoring does not show system attention`() {
        val health = SystemHealthSnapshot(
            notificationsAllowed = false,
            backgroundRestricted = true
        )

        assertNull(health.monitoringAttention(monitoringEnabled = false))
    }

    @Test
    fun `background restriction takes priority over notification warning`() {
        val health = SystemHealthSnapshot(
            notificationsAllowed = false,
            backgroundRestricted = true
        )

        assertEquals(
            "Android is restricting background activity. Open Settings › Diagnostics to fix monitoring reliability.",
            health.monitoringAttention(monitoringEnabled = true)
        )
    }

    @Test
    fun `blocked notifications are visible while monitoring`() {
        val health = SystemHealthSnapshot(notificationsAllowed = false)

        assertEquals(
            "Monitoring is running, but Android is hiding its ongoing notification. Open Settings › Diagnostics to allow it.",
            health.monitoringAttention(monitoringEnabled = true)
        )
    }
}
