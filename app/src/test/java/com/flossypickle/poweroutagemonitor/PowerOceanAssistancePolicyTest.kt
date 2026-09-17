package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.*
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import org.junit.Assert.*
import org.junit.Test

class PowerOceanAssistancePolicyTest {
    private val connected = PowerSignal(GridAvailability.AVAILABLE, 1000, "ecoflow", recoveryPending = true,
        evidenceReceivedAtEpochMs = 1000, dataPossiblyStalled = true)
    @Test fun warningOnlyKeepsValidGridEvidence() {
        assertEquals(connected, PowerOceanAssistancePolicy.apply(connected, false, false))
    }
    @Test fun automaticExclusionBlocksRecoveryButKeepsDiagnostic() {
        val result = PowerOceanAssistancePolicy.apply(connected, false, true)
        assertEquals(GridAvailability.UNKNOWN, result.availability)
        assertNull(result.evidenceReceivedAtEpochMs); assertFalse(result.recoveryPending)
        assertEquals(true, result.dataPossiblyStalled)
        assertEquals(GridAvailability.UNAVAILABLE, ChargerFirstPolicy.evaluate(false, result, 500, true, false, 1000).availability)
    }
    @Test fun changedReadingsAutomaticallyResumeAssistance() {
        val changed = connected.copy(dataPossiblyStalled = false)
        assertEquals(changed, PowerOceanAssistancePolicy.apply(changed, false, true))
    }
    @Test fun manualPauseBlocksEvenChangingReadingsAndResumeRestoresThem() {
        val changing = connected.copy(dataPossiblyStalled = false)
        assertEquals(GridAvailability.UNKNOWN, PowerOceanAssistancePolicy.apply(changing, true, false).availability)
        assertEquals(changing, PowerOceanAssistancePolicy.apply(changing, false, false))
    }
}
