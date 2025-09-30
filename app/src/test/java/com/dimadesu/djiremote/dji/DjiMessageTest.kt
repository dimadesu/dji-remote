package com.dimadesu.djiremote.dji

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

class DjiMessageTest {
    @Test
    fun encodeDecodeRoundtrip() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val msg = DjiMessage(target = 0x0702, id = 0x1234, type = 0x112233, payload = payload)
        val encoded = msg.encode()
        val decoded = DjiMessage.fromBytes(encoded)
        assertEquals(msg.target, decoded.target)
        assertEquals(msg.id, decoded.id)
        assertEquals(msg.type, decoded.type)
    assertEquals(msg.payload.toList(), decoded.payload.toList())
    }

    @Test
    fun headerCrcMismatch() {
        val payload = byteArrayOf(0x01)
        val msg = DjiMessage(target = 1, id = 2, type = 3, payload = payload)
        val encoded = msg.encode()
        // corrupt header
        encoded[2] = 0x05
    assertThrows(IllegalArgumentException::class.java) { DjiMessage.fromBytes(encoded) }
    }
}
