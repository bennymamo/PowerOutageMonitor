package com.flossypickle.poweroutagemonitor

import android.app.ApplicationExitInfo
import com.flossypickle.poweroutagemonitor.storage.ProcessExitClassifier
import com.flossypickle.poweroutagemonitor.storage.ProcessExitWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitClassifierTest {
    @Test fun `package update is expected but generic user stop is not`() {
        assertTrue(
            ProcessExitClassifier.classify(
                ApplicationExitInfo.REASON_PACKAGE_UPDATED,
                null
            )!!.appUpdated
        )
        assertTrue(
            ProcessExitClassifier.classify(
                ApplicationExitInfo.REASON_USER_REQUESTED,
                "stop app due to installPackageLI"
            )!!.appUpdated
        )
        assertFalse(
            ProcessExitClassifier.classify(
                ApplicationExitInfo.REASON_USER_REQUESTED,
                "removed from Recents"
            )!!.appUpdated
        )
    }

    @Test fun `a confirmed crash is explained without treating it as an update`() {
        val insight = ProcessExitClassifier.classify(ApplicationExitInfo.REASON_CRASH, null)!!
        assertFalse(insight.appUpdated)
        assertEquals(
            "Android reports that the previous app process crashed.",
            insight.explanation
        )
    }

    @Test fun `an old exit cannot explain a new monitoring session`() {
        val now = 1_000_000L
        val previousStart = now - 60_000L
        assertTrue(ProcessExitWindow.includes(now - 5_000L, now, previousStart))
        assertFalse(ProcessExitWindow.includes(now - 90_000L, now, previousStart))
        assertFalse(ProcessExitWindow.includes(now - 121_000L, now, 0L))
        assertFalse(ProcessExitWindow.includes(now + 1_000L, now, previousStart))
    }
}
