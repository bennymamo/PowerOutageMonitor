package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanActivationPolicy as Policy
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerOceanActivationPolicyTest {
    @Test fun savedAccountAndSupportedEquipmentAreRequired() {
        assertEquals(Policy.Stage.ACCOUNT_REQUIRED, Policy.evaluate(false, true, true, 1000, 1000))
        assertEquals(Policy.Stage.UNSUPPORTED_MODEL, Policy.evaluate(true, false, true, 1000, 1000))
    }
    @Test fun liveReadingsCannotReplacePhysicalProfileVerification() {
        assertEquals(Policy.Stage.PROFILE_REQUIRED, Policy.evaluate(true, true, false, 1000, 1000))
    }
    @Test fun cachedConnectionWithoutSuccessfulLiveTestCannotActivate() {
        assertEquals(Policy.Stage.LIVE_TEST_REQUIRED, Policy.evaluate(true, true, true, 0, 1000))
        assertEquals(Policy.Stage.LIVE_TEST_REQUIRED, Policy.evaluate(true, true, true, 2000, 1000))
    }
    @Test fun readyExpiresAtTheSameBoundaryUsedByTheSelectionGate() {
        assertEquals(Policy.Stage.READY, Policy.evaluate(true, true, true, 1000, 91000))
        assertEquals(Policy.Stage.LIVE_TEST_REQUIRED, Policy.evaluate(true, true, true, 1000, 91001))
    }
    @Test fun previousSuccessfulTestSkipsTheLiveCheckExpiry() {
        assertEquals(Policy.Stage.READY, Policy.evaluate(true, true, true, 0, 100000, previousTestAccepted = true))
        assertEquals(Policy.Stage.READY, Policy.evaluate(true, true, true, 1000, 100000, previousTestAccepted = true))
    }
    @Test fun previousTestDoesNotBypassAccountModelOrPhysicalProfileRequirements() {
        assertEquals(Policy.Stage.ACCOUNT_REQUIRED, Policy.evaluate(false, true, true, 0, 100000, true))
        assertEquals(Policy.Stage.UNSUPPORTED_MODEL, Policy.evaluate(true, false, true, 0, 100000, true))
        assertEquals(Policy.Stage.PROFILE_REQUIRED, Policy.evaluate(true, true, false, 0, 100000, true))
    }

}
