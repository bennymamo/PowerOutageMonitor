package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceCheck
import org.junit.Assert.*
import org.junit.Test

class PowerSourceCheckTest {
    @Test fun noReportsMovesFromCheckingToTimeoutAtFortyFiveSeconds() {
        val c = PowerSourceCheck(1000, null, false)
        assertEquals(PowerSourceCheck.Phase.CHECKING, c.phase(45999))
        assertEquals(PowerSourceCheck.Phase.TIMED_OUT, c.phase(46000))
    }
    @Test fun oldLiveReportCannotCompleteANewCheck() {
        assertEquals(PowerSourceCheck.Phase.CHECKING, PowerSourceCheck(1000, 999, true).phase(2000))
    }
    @Test fun livePowerWithoutGridEvidenceIsShownAsPartial() {
        assertEquals(PowerSourceCheck.Phase.LIVE_RECEIVED, PowerSourceCheck(1000, 1100, false).phase(2000))
    }
    @Test fun verifiedGridCompletesCheckAndLateReportsCanRecoverTimeout() {
        assertEquals(PowerSourceCheck.Phase.GRID_VERIFIED, PowerSourceCheck(1000, 47000, true).phase(48000))
    }
}
