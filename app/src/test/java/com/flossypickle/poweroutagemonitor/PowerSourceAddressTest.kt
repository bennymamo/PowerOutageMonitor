package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.ui.isPrivateIpv4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSourceAddressTest {
    @Test
    fun `accepts common private local network addresses`() {
        assertTrue(isPrivateIpv4("10.0.0.8"))
        assertTrue(isPrivateIpv4("172.16.4.20"))
        assertTrue(isPrivateIpv4("172.31.255.254"))
        assertTrue(isPrivateIpv4("192.168.1.50"))
    }

    @Test
    fun `rejects public malformed and hostname inputs`() {
        assertFalse(isPrivateIpv4("8.8.8.8"))
        assertFalse(isPrivateIpv4("172.32.0.1"))
        assertFalse(isPrivateIpv4("192.168.1.999"))
        assertFalse(isPrivateIpv4("inverter.local"))
        assertFalse(isPrivateIpv4("https://192.168.1.50"))
    }
}
