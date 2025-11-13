package com.dimadesu.djiremote.dji

import org.junit.Test
import org.junit.Assert.*

class DjiMessageVerificationTest {
    @Test
    fun testPairingMessageFormat() {
        // Generate our pairing message
        val pairPayload = DjiPairMessagePayload("mbln").encode()
        val msg = DjiMessage(0x0702, 0x8092, 0x450740, pairPayload)
        val bytes = msg.encode()
        
        // Expected format from iOS logs
        val expected = byteArrayOf(
            0x55.toByte(),  // Start byte
            0x33,           // Length (51)
            0x04,           // Version
            0x04,           // CRC8 of header
            0x02, 0x07,     // Target (0x0702 little-endian)
            0x92.toByte(), 0x80.toByte(), // ID (0x8092 little-endian)
            0x40, 0x07, 0x45, // Type (0x450740 little-endian)
            // Payload starts here (38 bytes total)
            0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
            0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
            0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
            0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
            0x33, // Fixed payload ends
            0x04, // Length of "mbln"
            0x6D, 0x62, 0x6C, 0x6E, // "mbln"
            0xE8.toByte(), 0x1C // CRC16 (little-endian)
        )
        
        println("Generated message (${bytes.size} bytes):")
        println(bytes.joinToString(" ") { "%02X".format(it) })
        println("\nExpected message (${expected.size} bytes):")
        println(expected.joinToString(" ") { "%02X".format(it) })
        
        // Verify they match
        assertArrayEquals("Message doesn't match expected format", expected, bytes)
    }
    
    @Test
    fun testEmptyPairingMessage() {
        // Test with empty PIN
        val pairPayload = DjiPairMessagePayload("").encode()
        val msg = DjiMessage(0x0702, 0x8092, 0x450740, pairPayload)
        val bytes = msg.encode()
        
        println("Empty PIN message (${bytes.size} bytes):")
        println(bytes.joinToString(" ") { "%02X".format(it) })
        
        // Should be 47 bytes (51 - 4 for "mbln")
        assertEquals(47, bytes.size)
    }
    
    @Test
    fun testAlternativeTarget() {
        // Try with target 0x0802 instead of 0x0702
        val pairPayload = DjiPairMessagePayload("mbln").encode()
        val msg = DjiMessage(0x0802, 0x8092, 0x450740, pairPayload)
        val bytes = msg.encode()
        
        println("Alternative target message:")
        println(bytes.joinToString(" ") { "%02X".format(it) })
        
        // Check target bytes
        assertEquals(0x02.toByte(), bytes[4])
        assertEquals(0x08.toByte(), bytes[5])
    }
}
