package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanFailureRetryPolicy
import org.junit.Assert.*
import org.junit.Test

class PowerOceanFailureRetryPolicyTest {
    @Test fun `powered failures accumulate and warn at configured threshold`() {
        var streak = 0
        repeat(4) {
            streak = PowerOceanFailureRetryPolicy.nextStreak(streak, true, true)
            assertTrue(PowerOceanFailureRetryPolicy.deferPoweredWarning(true, true, streak, 5))
        }
        streak = PowerOceanFailureRetryPolicy.nextStreak(streak, true, true)
        assertEquals(5, streak)
        assertFalse(PowerOceanFailureRetryPolicy.deferPoweredWarning(true, true, streak, 5))
    }

    @Test fun `success and charger loss reset routine powered failure streak`() {
        assertEquals(0, PowerOceanFailureRetryPolicy.nextStreak(4, true, false))
        assertEquals(0, PowerOceanFailureRetryPolicy.nextStreak(4, false, true))
        assertEquals(0, PowerOceanFailureRetryPolicy.nextStreak(4, null, true))
        assertFalse(PowerOceanFailureRetryPolicy.deferPoweredWarning(false, true, 1, 5))
    }

    @Test fun `broker session refresh occurs at configured failure multiples`() {
        assertFalse(PowerOceanFailureRetryPolicy.refreshSession(1, 2, true))
        assertTrue(PowerOceanFailureRetryPolicy.refreshSession(2, 2, true))
        assertTrue(PowerOceanFailureRetryPolicy.refreshSession(4, 2, true))
        assertFalse(PowerOceanFailureRetryPolicy.refreshSession(2, 0, true))
        assertFalse(PowerOceanFailureRetryPolicy.refreshSession(2, 2, false))
    }
}
