package com.dimadesu.djiremote.dji

import org.junit.Test
import org.junit.Assert.*

class DjiPayloadsTest {
    @Test
    fun pairPayloadRoundtrip() {
        val p = DjiPairMessagePayload("mbln")
        val enc = p.encode()
        val msg = DjiMessage(target = 0x0702, id = 0x8092, type = 0x450740, payload = enc)
        val out = DjiMessage.fromBytes(msg.encode())
        assertArrayEquals(enc, out.payload)
    }

    @Test
    fun setupWifiRoundtrip() {
        val p = DjiSetupWifiMessagePayload("ssid", "pass")
        val enc = p.encode()
        val msg = DjiMessage(target = 0x0702, id = 0x8C19, type = 0x470740, payload = enc)
        val out = DjiMessage.fromBytes(msg.encode())
        assertArrayEquals(enc, out.payload)
    }

    @Test
    fun startStreamingRoundtrip() {
        val p = DjiStartStreamingMessagePayload("rtmp://example/stream", SettingsDjiDeviceResolution.r1080p, 30, 4000, false)
        val enc = p.encode()
        val msg = DjiMessage(target = 0x0802, id = 0x8C2C, type = 0x780840, payload = enc)
        val out = DjiMessage.fromBytes(msg.encode())
        assertArrayEquals(enc, out.payload)
    }

    @Test
    fun pairMessageFormat() {
        val p = DjiPairMessagePayload("mbln")
        val enc = p.encode()
        val msg = DjiMessage(target = 0x0702, id = 0x8092, type = 0x450740, payload = enc)
        val bytes = msg.encode()

        // Log the message for debugging
        println("Pair message bytes (${bytes.size}): ${bytes.joinToString(" ") { "%02X".format(it) }}")

        // Verify message starts with 0x55 (DJI header)
        assertEquals(0x55.toByte(), bytes[0])

        // Verify length byte
        assertEquals(51, bytes[1].toInt() and 0xFF)

        // Verify we can decode our own message
        val decoded = DjiMessage.fromBytes(bytes)
        assertEquals(0x0702, decoded.target)
        assertEquals(0x8092, decoded.id)
        assertEquals(0x450740, decoded.type)
        assertArrayEquals(enc, decoded.payload)
    }
}
