package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargerConfirmationPolicyTest {
    private fun signal(value: GridAvailability) = PowerSignal(value, 1_000, "grid")

    @Test fun chargerOnlyCorroboratesLossWhenRequested() {
        assertEquals(GridAvailability.UNKNOWN, ChargerConfirmationPolicy.apply(signal(GridAvailability.UNAVAILABLE), true, true).availability)
        assertEquals(GridAvailability.UNKNOWN, ChargerConfirmationPolicy.apply(signal(GridAvailability.UNAVAILABLE), null, true).availability)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerConfirmationPolicy.apply(signal(GridAvailability.UNAVAILABLE), false, true).availability)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerConfirmationPolicy.apply(signal(GridAvailability.UNAVAILABLE), true, false).availability)
    }

    @Test fun disconnectedChargerNeverBlocksGridRecoveryOrSubstitutesForUnknown() {
        assertEquals(GridAvailability.AVAILABLE, ChargerConfirmationPolicy.apply(signal(GridAvailability.AVAILABLE), false, true).availability)
        assertEquals(GridAvailability.UNKNOWN, ChargerConfirmationPolicy.apply(signal(GridAvailability.UNKNOWN), false, true).availability)
    }
}
