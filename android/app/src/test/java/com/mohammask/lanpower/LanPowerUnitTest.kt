package com.mohammask.lanpower

import com.mohammask.lanpower.model.NetworkValidation
import com.mohammask.lanpower.network.LanPowerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPowerUnitTest {

    @Test
    fun normalizeAndValidateMacAddress() {
        assertEquals("AA:BB:CC:DD:EE:FF", NetworkValidation.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("01:23:45:67:89:AB", NetworkValidation.normalizeMac(" 01:23:45:67:89:ab "))
        assertTrue(NetworkValidation.isValidMac("aa-bb-cc-dd-ee-ff"))
        assertTrue(NetworkValidation.isValidMac("AA:BB:CC:DD:EE:FF"))
        assertFalse(NetworkValidation.isValidMac("AA:BB:CC:DD:EE"))
        assertFalse(NetworkValidation.isValidMac("GG:BB:CC:DD:EE:FF"))
    }

    @Test
    fun validateIpv4AndComputeBroadcastIp() {
        assertTrue(NetworkValidation.isValidIpv4("192.168.1.50"))
        assertTrue(NetworkValidation.isValidIpv4("10.0.0.1"))
        assertFalse(NetworkValidation.isValidIpv4("192.168.1.256"))
        assertFalse(NetworkValidation.isValidIpv4("192.168.01.1"))
        assertFalse(NetworkValidation.isValidIpv4("not-an-ip"))

        assertEquals("192.168.1.255", NetworkValidation.computeBroadcastIp("192.168.1.50", 24))
        assertEquals("10.0.255.255", NetworkValidation.computeBroadcastIp("10.0.42.7", 16))
        assertEquals("192.168.1.127", NetworkValidation.computeBroadcastIp("192.168.1.50", 25))
    }

    @Test
    fun buildMagicPacketProducesStandard102BytePayload() {
        val client = LanPowerClient()
        val packet = client.buildMagicPacket("aa-bb-cc-dd-ee-ff")

        assertEquals(102, packet.size)
        for (i in 0 until 6) {
            assertEquals(0xFF.toByte(), packet[i])
        }
        val expectedMac = byteArrayOf(
            0xAA.toByte(),
            0xBB.toByte(),
            0xCC.toByte(),
            0xDD.toByte(),
            0xEE.toByte(),
            0xFF.toByte(),
        )
        for (rep in 0 until 16) {
            for (b in 0 until 6) {
                assertEquals(expectedMac[b], packet[6 + rep * 6 + b])
            }
        }
    }
}
