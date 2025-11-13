package com.dimadesu.djiremote.dji

import org.junit.Test
import org.junit.Assert.*

class DjiAlternativeMessagesTest {
    @Test
    fun testAlternativePairingMessages() {
        // Test different PIN codes
        val pins = listOf("", "0000", "1234", "mbln", "MBLN")
        
        for (pin in pins) {
            val payload = DjiPairMessagePayload(pin).encode()
            val msg = DjiMessage(0x0702, 0x8092, 0x450740, payload)
            val bytes = msg.encode()
            
            println("PIN '$pin' message (${bytes.size} bytes):")
            println("  ${bytes.joinToString(" ") { "%02X".format(it) }}")
            
            // Verify we can decode it
            val decoded = DjiMessage.fromBytes(bytes)
            assertEquals(0x0702, decoded.target)
        }
    }
    
    @Test 
    fun testMinimalMessage() {
        // Test a minimal valid DJI message
        val msg = DjiMessage(0x0000, 0x0000, 0x000000, byteArrayOf())
        val bytes = msg.encode()
        
        println("Minimal message (${bytes.size} bytes):")
        println("  ${bytes.joinToString(" ") { "%02X".format(it) }}")
        
        // Should be header (4) + target (2) + id (2) + type (3) + crc (2) = 13 bytes minimum
        assertTrue(bytes.size >= 13)
        assertEquals(0x55.toByte(), bytes[0])
    }
    
    @Test
    fun testWakeupSequence() {
        // Try different "wake up" messages
        val wakeupMessages = listOf(
            DjiMessage(0x0001, 0x0001, 0x000001, byteArrayOf(0x00)),
            DjiMessage(0x0702, 0x0000, 0x000000, byteArrayOf()),
            DjiMessage(0x0802, 0x0000, 0x000000, byteArrayOf())
        )
        
        for ((i, msg) in wakeupMessages.withIndex()) {
            val bytes = msg.encode()
            println("Wakeup message $i (${bytes.size} bytes):")
            println("  ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
    }
    
    @Test
    fun testPairingMessageDetails() {
        val payload = DjiPairMessagePayload("mbln").encode()
        println("Pairing payload (${payload.size} bytes): ${payload.joinToString(" ") { "%02X".format(it) }}")
        
        val msg = DjiMessage(0x0702, 0x8092, 0x450740, payload)
        val bytes = msg.encode()
        
        println("\nComplete message breakdown:")
        println("Header:")
        println("  Magic: 0x${"%02X".format(bytes[0])}")
        println("  Length: ${bytes[1].toInt() and 0xFF}")
        println("  Version: 0x${"%02X".format(bytes[2])}")
        println("  CRC8: 0x${"%02X".format(bytes[3])}")
        
        println("\nMessage:")
        println("  Target: 0x${"%02X".format(bytes[4])}${"%02X".format(bytes[5])} (little-endian)")
        println("  ID: 0x${"%02X".format(bytes[6])}${"%02X".format(bytes[7])} (little-endian)")
        println("  Type: 0x${"%02X".format(bytes[8])}${"%02X".format(bytes[9])}${"%02X".format(bytes[10])} (little-endian)")
        println("  Payload: ${bytes.slice(11 until bytes.size - 2).joinToString(" ") { "%02X".format(it) }}")
        println("  CRC16: 0x${"%02X".format(bytes[bytes.size-2])}${"%02X".format(bytes[bytes.size-1])} (little-endian)")
        
        println("\nFull message (${bytes.size} bytes):")
        println(bytes.joinToString(" ") { "%02X".format(it) })
    }
    
    @Test
    fun testReadCharacteristicResponse() {
        // The response we got from reading 00002a00
        val deviceName = byteArrayOf(
            0x4F, 0x73, 0x6D, 0x6F, 0x41, 0x63, 0x74, 0x69,
            0x6F, 0x6E, 0x34, 0x2D, 0x30, 0x42, 0x41, 0x39
        )
        val nameStr = String(deviceName)
        println("Device name: $nameStr")
        assertEquals("OsmoAction4-0BA9", nameStr)
    }
}
