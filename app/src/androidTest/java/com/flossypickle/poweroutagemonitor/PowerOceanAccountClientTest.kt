package com.flossypickle.poweroutagemonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class PowerOceanAccountClientTest {
    @Test fun encryptedPortalConnectionDetailsDecodeOnOldAndroid() {
        // Independent Python cryptography AES-CFB128 vector, containing fake credentials only.
        val encoded = "lxfRB9M5TgxfgQVIgG0lcO2ZAGLZxzTcPb7zKw1mSpvmcNt07eSEMNjwMB8ry8ztoF5Qop6FkYStsFyxaLcyN3ECHE8qhOFH5ohvAudQaSFs4tbCtTpem4Rq181HnUfDzpu1YY/vim3h98L7ypgLuAm777pAfnNe746bl0SuIHpE5Odu94/wZVznnwQX7bNK"
        val response = JSONObject().put("code", "0").put("data", encoded).toString()
        val client = PowerOceanAccountClient { url -> Fixture(url, response) }
        val account = PowerOceanAccountClient.Connection("owner@example.com", "private-password", "EXAMPLE-SERIAL")
        val session = PowerOceanAccountClient.Session("example-private-token", account, "12345", "api-e.ecoflow.com")
        val result = client.pushCredentials(session)
        assertTrue("Expected decoded fake credentials, got $result", result is EcoFlowCloudClient.Result.Success)
        val connection = (result as EcoFlowCloudClient.Result.Success).value
        assertEquals("mqtt-e.ecoflow.com", connection.host)
        assertEquals(8084, connection.port)
        assertEquals("/mqtt", connection.path)
        assertEquals("owner.account@example.com", connection.account)
        assertEquals("secret-xyz", connection.password)
        assertFalse(connection.toString().contains("secret-xyz"))
    }

    @Test fun unsupportedConnectionResponseGetsSpecificSafeFeedback() {
        val client = PowerOceanAccountClient { url -> Fixture(url, """{"code":"0","data":{"privateValue":"never-display"}}""") }
        val account = PowerOceanAccountClient.Connection("owner@example.com", "private-password", "EXAMPLE-SERIAL")
        val result = client.pushCredentials(PowerOceanAccountClient.Session("example-private-token", account, "12345", "api-e.ecoflow.com"))
        val failure = result as EcoFlowCloudClient.Result.Failure
        assertTrue(failure.message.contains("expected format"))
        assertFalse(failure.message.contains("never-display"))
    }

    @Test fun regionalLoginAndDeviceReadUsePrivateTokenWithoutInverterCommands() {
        val responses = listOf("""{"code":"0","data":{"token":"example-private-token","user":{"userId":"12345"}}}""",
            """{"code":"0","data":{"sysGridPwr":100,"quota":{"JTS1_BP_STA_REPORT":{"private-battery-id":"{\"bpSoc\":60,\"bpSn\":\"private-battery-id\"}"},"JTS1_EMS_HEARTBEAT":{"meterAVoltage":230}}}}""")
        val connections = mutableListOf<Fixture>()
        val client = PowerOceanAccountClient { url ->
            Fixture(url, responses[connections.size]).also { connections.add(it) }
        }
        val account = PowerOceanAccountClient.Connection("owner@example.com", "private-password", "EXAMPLE-SERIAL")
        val session = (client.login(account) as EcoFlowCloudClient.Result.Success).value
        val response = (client.read(session) as EcoFlowCloudClient.Result.Success).value
        assertEquals("https://api-e.ecoflow.com/auth/login", connections[0].url.toString())
        assertEquals("POST", connections[0].requestMethod)
        assertEquals("IOT_APP", JSONObject(connections[0].sent.toString("UTF-8")).getString("scene"))
        assertEquals("/provider-service/user/device/detail", connections[1].url.path)
        assertEquals("GET", connections[1].requestMethod)
        assertEquals("86", connections[1].getRequestProperty("product-type"))
        assertEquals("Bearer example-private-token", connections[1].getRequestProperty("authorization"))
        assertFalse(connections[1].instanceFollowRedirects)
        val snapshot = PowerOceanAccountTelemetry.snapshot(response, 1000)
        val readings = snapshot.sections.flatMap { it.readings }
        assertTrue(readings.any { it.label == "Battery 1 · Battery charge" && it.value == "60" })
        assertTrue(readings.any { it.label == "Meter phase A voltage" && it.value == "230" })
        assertFalse(readings.toString().contains("private-battery-id"))
        assertFalse(account.toString().contains("private-password"))
        assertFalse(session.toString().contains("example-private-token"))
    }
    private class Fixture(url: URL, private val response: String) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        private val headers = mutableMapOf<String, String>()
        override fun setRequestProperty(key: String, value: String) { headers[key.lowercase(java.util.Locale.ROOT)] = value }
        override fun getRequestProperty(key: String): String? = headers[key.lowercase(java.util.Locale.ROOT)]
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        override fun getResponseCode() = 200
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
