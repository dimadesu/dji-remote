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
}
