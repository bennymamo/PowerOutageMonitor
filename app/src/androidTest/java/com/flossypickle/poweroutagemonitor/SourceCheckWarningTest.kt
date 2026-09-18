package com.flossypickle.poweroutagemonitor

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.flossypickle.poweroutagemonitor.integrations.alerts.*
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore
import com.flossypickle.poweroutagemonitor.integrations.power.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SourceCheckWarningTest {
    private class QaContext : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val prefix = "source_warning_qa_${UUID.randomUUID()}_"
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return super.getSharedPreferences(prefix + name, mode)
        }
        fun clear() = names.forEach { super.getSharedPreferences(prefix + it, 0).edit().clear().commit() }
    }
    private fun completed(at: Long, valid: Boolean = false, state: PowerSourceCheck.CycleState = PowerSourceCheck.CycleState.FAILED) =
        PowerSourceCheck(at - 50, if (valid) at - 10 else null, valid, cycleState = state, finishedAtEpochMs = at)

    @Test fun chargerOffWarningAndRecoverySurviveCoordinatorRestartWithoutRepeating() {
        val context = QaContext(); val messages = mutableListOf<AlertMessage>()
        try {
            TelegramRemoteStore(context).save(TelegramRemoteStore.Settings(checkWarnings = false))
            fun coordinator() = SourceCheckWarningCoordinator(context, { messages.add(it); true }, {})
            coordinator().process(completed(100), false)
            coordinator().process(completed(100), false)
            coordinator().process(completed(200, state = PowerSourceCheck.CycleState.WAITING), false)
            assertEquals(1, messages.size)
            assertEquals("Grid status is unknown, and the charger has no power", messages.single().title)
            assertTrue(messages.single().body.contains("Please confirm manually."))
            assertTrue(messages.single().body.contains("EcoFlow has not confirmed an outage."))
            coordinator().process(completed(300, true, PowerSourceCheck.CycleState.WAITING), false)
            coordinator().process(completed(400, true, PowerSourceCheck.CycleState.WAITING), false)
            assertEquals(listOf(AlertKind.SOURCE_UNAVAILABLE, AlertKind.SOURCE_RESTORED), messages.map { it.kind })
            assertEquals(messages[0].eventId, messages[1].eventId)
        } finally { context.clear() }
    }

    @Test fun unknownSwitchCanSuppressAndReenableWarnings() {
        val context = QaContext(); val messages = mutableListOf<AlertMessage>()
        try {
            val source = PowerSourceStore(context)
            source.setPowerOceanAssistedSettings(source.powerOceanAssistedSettings().copy(notifyOnUnknown = false))
            val coordinator = SourceCheckWarningCoordinator(context, { messages.add(it); true }, {})
            coordinator.process(completed(100), false)
            assertTrue(messages.isEmpty())
            source.setPowerOceanAssistedSettings(source.powerOceanAssistedSettings().copy(notifyOnUnknown = true))
            coordinator.process(completed(200, state = PowerSourceCheck.CycleState.WAITING), false)
            assertEquals(1, messages.size)
        } finally { context.clear() }
    }

    @Test fun activePausedAndRoutineDisabledChecksDoNotSendAlerts() {
        val context = QaContext(); val messages = mutableListOf<AlertMessage>()
        try {
            TelegramRemoteStore(context).save(TelegramRemoteStore.Settings(checkWarnings = false))
            val coordinator = SourceCheckWarningCoordinator(context, { messages.add(it); true }, {})
            coordinator.process(completed(100), true)
            coordinator.process(completed(200), null)
            coordinator.process(completed(300, state = PowerSourceCheck.CycleState.COLLECTING), false)
            coordinator.process(completed(400, state = PowerSourceCheck.CycleState.PAUSED), false)
            PowerSourceStore(context).setPowerOceanAssistancePaused(true)
            coordinator.process(completed(500), false)
            assertTrue(messages.isEmpty())
        } finally { context.clear() }
    }

    @Test fun losingChargerEscalatesRoutineWarningOnlyOnce() {
        val context = QaContext(); val messages = mutableListOf<AlertMessage>()
        try {
            val coordinator = SourceCheckWarningCoordinator(context, { messages.add(it); true }, {})
            coordinator.process(completed(100), true)
            coordinator.process(completed(200), false)
            coordinator.process(completed(300), false)
            assertEquals(2, messages.size)
            assertNotEquals(messages[0].eventId, messages[1].eventId)
            assertTrue(messages[1].title.contains("charger has no power"))
        } finally { context.clear() }
    }

    @Test fun unsuccessfulQueueingKeepsEventIdentityForNextCheck() {
        val context = QaContext(); val attempts = mutableListOf<AlertMessage>()
        try {
            val coordinator = SourceCheckWarningCoordinator(context, { attempts.add(it); attempts.size > 1 }, {})
            coordinator.process(completed(100), false)
            coordinator.process(completed(200), false)
            coordinator.process(completed(300), false)
            assertEquals(2, attempts.size)
            assertEquals(attempts[0].eventId, attempts[1].eventId)
        } finally { context.clear() }
    }
}
