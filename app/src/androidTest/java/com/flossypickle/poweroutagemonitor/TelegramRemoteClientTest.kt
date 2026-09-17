package com.flossypickle.poweroutagemonitor

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.content.SharedPreferences
import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.*
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.ui.configurePrivatePasswordInput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class TelegramRemoteClientTest {
    private val token = "123456789:example_fake_token_for_tests"
    @Test fun receiverDiscardsBacklogAndCheckpointsBeforeExecutingTrustedCommand() {
        val context = QaContext(InstrumentationRegistry.getInstrumentation().targetContext); context.clear()
        val config = TelegramConfigStore(context)
        config.save(token, false, "Fixture bot", emptyList())
        val store = TelegramRemoteStore(context)
        store.save(TelegramRemoteStore.Settings(enabled = true, trustedChatIds = setOf("123")))
        val date = System.currentTimeMillis() / 1000 + 1
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        val handled = java.util.concurrent.CountDownLatch(1)
        val api = TelegramClient { url ->
            val response = if (url.path.endsWith("getUpdates")) when (polls.incrementAndGet()) {
                1 -> """{"ok":true,"result":[{"update_id":10,"message":{"chat":{"id":123,"type":"private"},"from":{"id":123},"date":1,"text":"/monitor_off"}}]}"""
                2 -> """{"ok":true,"result":[{"update_id":11,"message":{"chat":{"id":123,"type":"private"},"from":{"id":123},"date":$date,"text":"/status"}},{"update_id":12,"message":{"chat":{"id":999,"type":"private"},"from":{"id":999},"date":$date,"text":"/monitor_off"}}]}"""
                else -> { Thread.sleep(50); """{"ok":true,"result":[]}""" }
            } else """{"ok":true,"result":{"message_id":1}}"""
            Fixture(url, response)
        }
        val controller = TelegramRemoteController(context, { command ->
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(12L, store.offset(hash))
            commands.add(command.name); handled.countDown(); "Fixture reply"
        }, { api })
        try {
            controller.refresh()
            assertTrue(handled.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(150)
            assertEquals(listOf("status"), commands.toList())
        } finally { controller.stop(); config.clear(); store.save(TelegramRemoteStore.Settings()); context.clear() }
    }
    @Test fun longPollParsesOnlyNewMessagesAndUsesBoundedTimeout() {
        lateinit var connection: Fixture
        val client = TelegramClient { url -> Fixture(url, """{"ok":true,"result":[{"update_id":12,"message":{"chat":{"id":123,"type":"private"},"from":{"id":123,"is_bot":false},"date":100,"text":"/status"}},{"update_id":13,"edited_message":{"text":"/monitor_off"}}]}""").also { connection = it } }
        val result = client.pollUpdates(token, 12, 25) as TelegramClient.ApiResult.Success
        assertEquals(2, result.value.size)
        assertEquals("/status", result.value.first().message?.text)
        assertNull(result.value.last().message)
        val sent = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals(12, sent.getLong("offset")); assertEquals(25, sent.getInt("timeout"))
        assertEquals(40_000, connection.readTimeout)
        assertEquals("message", sent.getJSONArray("allowed_updates").getString(0))
    }
    @Test fun menuIsScopedToOnePrivateChat() {
        lateinit var connection: Fixture
        val client = TelegramClient { Fixture(it, """{"ok":true,"result":true}""").also { fixture -> connection = fixture } }
        assertTrue(client.installCommandMenu(token, "123") is TelegramClient.ApiResult.Success)
        val payload = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals("chat", payload.getJSONObject("scope").getString("type"))
        assertEquals("123", payload.getJSONObject("scope").getString("chat_id"))
        assertEquals(TelegramRemotePolicy.commands.size, payload.getJSONArray("commands").length())
    }
    @Test fun throttlingRetainsRetryAfterAndRedactsToken() {
        val client = TelegramClient { Fixture(it, """{"ok":false,"description":"rate limit $token","parameters":{"retry_after":45}}""", 429) }
        val result = client.pollUpdates(token, 1, 25) as TelegramClient.ApiResult.Failure
        assertEquals(45, result.retryAfterSeconds); assertFalse(result.message.contains(token))
    }
    @Test fun masterOffKeepsOneHostOnlyWhenRemoteEnabled() {
        val context = QaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        context.clear()
        val monitor = MonitorStore(context); val remote = TelegramRemoteStore(context)
        monitor.setMonitoringEnabled(true); remote.save(TelegramRemoteStore.Settings(enabled = true, trustedChatIds = setOf("123")))
        TelegramRemoteActions(context).execute(TelegramRemotePolicy.Command("monitor_off", ""))
        assertFalse(monitor.settings().monitoringEnabled)
        assertTrue(MonitoringService.shouldHost(context)); assertTrue(context.starts > 0); assertEquals(0, context.stops)
        remote.save(TelegramRemoteStore.Settings())
        MonitoringService.syncHosting(context)
        assertFalse(MonitoringService.shouldHost(context)); assertEquals(1, context.stops)
        TelegramRemoteActions(context).execute(TelegramRemotePolicy.Command("monitor_on", ""))
        assertTrue(monitor.settings().monitoringEnabled)
        context.clear()
    }
    @Test fun quietAndUnquietDoNotChangeMasterSwitch() {
        val context = QaContext(InstrumentationRegistry.getInstrumentation().targetContext); context.clear()
        MonitorStore(context).setMonitoringEnabled(true)
        val actions = TelegramRemoteActions(context)
        actions.execute(TelegramRemotePolicy.Command("quiet", "30"))
        assertTrue(TelegramRemoteStore(context).isQuiet()); assertTrue(MonitorStore(context).settings().monitoringEnabled)
        actions.execute(TelegramRemotePolicy.Command("unquiet", ""))
        assertFalse(TelegramRemoteStore(context).isQuiet())
        actions.execute(TelegramRemotePolicy.Command("quiet", "2000"))
        assertFalse(TelegramRemoteStore(context).isQuiet()); context.clear()
    }
    @Test fun passwordInputRequestsNoSuggestionsAndNoLearningWhenSupported() {
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT }
        configurePrivatePasswordInput(info)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        if (Build.VERSION.SDK_INT >= 26) assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }
    private class QaContext(base: Context) : ContextWrapper(base) {
        var starts = 0; var stops = 0
        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("remote_qa_$name", mode)
        override fun getFilesDir() = java.io.File(super.getFilesDir(), "remote_qa").apply { mkdirs() }
        override fun startService(service: Intent): ComponentName { starts++; return ComponentName(packageName, MonitoringService::class.java.name) }
        override fun startForegroundService(service: Intent): ComponentName = startService(service)
        override fun stopService(service: Intent): Boolean { stops++; return true }
        fun clear() { listOf("monitor_state", "telegram_remote", "operational_history", "scheduled_alerts").forEach { getSharedPreferences(it, 0).edit().clear().commit() } }
    }
    private class Fixture(url: URL, private val response: String, private val code: Int = 200) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = inputStream
        override fun getResponseCode() = code
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
