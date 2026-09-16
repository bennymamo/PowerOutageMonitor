package com.flossypickle.poweroutagemonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class EcoFlowReadOnlyClientTest {
    private val credentials = EcoFlowCloudClient.Credentials("example-access", "example-secret")

    @Test fun requestedReadingsUseSignedReadOnlyPostAndParseNestedArrays() {
        val connection = FixtureConnection("""{"code":"0","data":{"pcsAPhase":{"vol":230},"mpptHeartBeat":[{"mpptPv":[{"pwr":0}]}],"bpSoc":60,"sn":"private-serial"}}""")
        val client = EcoFlowCloudClient(clock = { 1000 }, nonce = { "123456" }, openConnection = {
            assertEquals("https://api.ecoflow.com/iot-open/sign/device/quota", it.toString())
            connection
        })
        val result = client.readPowerOceanQuota(credentials, "example-serial", requestedFields = true)
        assertTrue(result is EcoFlowCloudClient.Result.Success)
        val quota = (result as EcoFlowCloudClient.Result.Success).value
        assertEquals(listOf(230.0), quota.phaseVoltages)
        assertEquals("0", quota.reportedValues["mpptHeartBeat[0].mpptPv[0].pwr"])
        assertFalse(quota.reportedValues.containsKey("sn"))
        assertTrue(quota.requestedFields)
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        val body = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals("example-serial", body.getString("sn"))
        val fields = body.getJSONObject("params").getJSONArray("quotas")
        assertEquals(EcoFlowPowerOceanRequest.fields, (0 until fields.length()).map { fields.getString(it) })
        assertEquals(EcoFlowCloudSigner.sign(EcoFlowPowerOceanRequest.signingParameters("example-serial"),
            credentials.accessKey, credentials.secretKey, "123456", 1000), connection.getRequestProperty("sign"))
    }

    @Test fun allReadingsRemainGetAndDenialIsNotATelemetrySnapshot() {
        val connection = FixtureConnection("""{"code":"1006","message":"current device is not allowed to get device info example-serial example-secret"}""")
        val client = EcoFlowCloudClient(openConnection = {
            assertEquals("/iot-open/sign/device/quota/all", it.path)
            assertEquals("sn=example-serial", it.query)
            connection
        })
        val result = client.readPowerOceanQuota(credentials, "example-serial")
        assertTrue(result is EcoFlowCloudClient.Result.Failure)
        val message = (result as EcoFlowCloudClient.Result.Failure).message
        assertTrue(message.contains("1006"))
        assertFalse(message.contains("example-serial"))
        assertFalse(message.contains("example-secret"))
        assertEquals("GET", connection.requestMethod)
        assertEquals(0, connection.sent.size())
    }

    @Test fun mqttConnectionDetailsRequireTlsAndRemainRedacted() {
        val connection = FixtureConnection("""{"code":"0","data":{"url":"mqtt.ecoflow.com","port":"8883","protocol":"mqtts","certificateAccount":"example-account","certificatePassword":"example-password"}}""")
        val result = EcoFlowCloudClient(openConnection = {
            assertEquals("/iot-open/sign/certification", it.path)
            connection
        }).readMqttConnectionInfo(credentials)
        val info = (result as EcoFlowCloudClient.Result.Success).value
        assertEquals("mqtt.ecoflow.com", info.host)
        assertEquals(8883, info.port)
        assertFalse(info.toString().contains("example-password"))
        assertFalse(info.toString().contains("example-account"))
        val unsafe = FixtureConnection("""{"code":"0","data":{"url":"mqtt.ecoflow.com","port":"1883","protocol":"mqtt","certificateAccount":"example-account","certificatePassword":"example-password"}}""")
        assertTrue(EcoFlowCloudClient(openConnection = { unsafe }).readMqttConnectionInfo(credentials) is EcoFlowCloudClient.Result.Failure)
    }

    private class FixtureConnection(private val response: String) : HttpsURLConnection(URL("https://api.ecoflow.com")) {
        val sent = ByteArrayOutputStream()
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
