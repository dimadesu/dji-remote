package com.dimadesu.djiremote.dji

import org.junit.Test
import org.junit.Assert.*

class DjiCrcTest {
    @Test
    fun testCrc8() {
        // Test CRC8 with the header bytes from our pairing message
        val data = byteArrayOf(0x55.toByte(), 0x33, 0x04)
        val crc = DjiCrc.computeCrc8(data)
        assertEquals(0x04, crc) // The 4th byte in our message
    }
    
    @Test
    fun testCrc16() {
        // Test with the full pairing message (minus the last 2 CRC bytes)
        val message = byteArrayOf(
            0x55, 0x33, 0x04, 0x04, // Header with CRC
            0x02, 0x07, // Target (0x0702 little-endian)
            0x92.toByte(), 0x80.toByte(), // ID (0x8092 little-endian)
            0x40, 0x07, 0x45, // Type (0x450740 little-endian 24-bit)
            // Payload (38 bytes total)
            0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
            0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
            0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
            0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
            0x33, 0x04, 0x6D, 0x62, 0x6C, 0x6E
        )
        val crc16 = DjiCrc.computeCrc16(message)
        // The CRC16 in our message is E8 1C (little-endian for 0x1CE8)
        assertEquals(0x1CE8, crc16)
    }
    
    @Test
    fun testPairMessageStructure() {
        // Verify the complete pairing message structure
        val pairPayload = DjiPairMessagePayload("mbln").encode()
        val msg = DjiMessage(0x0702, 0x8092, 0x450740, pairPayload)
        val bytes = msg.encode()
        
        // Check message structure
        assertEquals(51, bytes.size)
        assertEquals(0x55.toByte(), bytes[0]) // Start byte
        assertEquals(51, bytes[1].toInt() and 0xFF) // Length
        assertEquals(0x04.toByte(), bytes[2]) // Version
        
        // Verify we can decode our own message
        val decoded = DjiMessage.fromBytes(bytes)
        assertEquals(0x0702, decoded.target)
        assertEquals(0x8092, decoded.id)
        assertEquals(0x450740, decoded.type)
        assertArrayEquals(pairPayload, decoded.payload)
    }
}
