package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmSchedulePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudibleAlarmSchedulePolicyTest {
    @Test fun `best effort never requests an exact alarm`() {
        assertFalse(AudibleAlarmSchedulePolicy.useExact(false, 36, true))
    }

    @Test fun `old Android can use exact alarms without special access`() {
        assertTrue(AudibleAlarmSchedulePolicy.useExact(true, 23, false))
    }

    @Test fun `modern Android falls back while exact access is missing`() {
        assertFalse(AudibleAlarmSchedulePolicy.useExact(true, 31, false))
    }

    @Test fun `modern Android uses exact alarms after access is granted`() {
        assertTrue(AudibleAlarmSchedulePolicy.useExact(true, 31, true))
    }
}
