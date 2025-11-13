package com.dimadesu.djiremote.dji

import org.junit.Test
import org.junit.Assert.*

class DjiCrcTest {
    @Test
    fun testCrc8() {
        // Test with known values from the iOS implementation
        val data1 = byteArrayOf(0x55.toByte(), 0x33, 0x04)
        val crc1 = DjiCrc.computeCrc8(data1)
        // The CRC8 for these bytes should be 0x04 based on the logs
        assertEquals(0x04, crc1)
    }
    
    @Test
    fun testCrc16() {
        // Test with a complete message minus the CRC
        val message = byteArrayOf(
            0x55, 0x33, 0x04, 0x04, // Header with CRC
            0x02, 0x07, // Target (little-endian)
            0x92.toByte(), 0x80.toByte(), // ID (little-endian) 
            0x40, 0x07, 0x45, // Type (little-endian 24-bit)
            // Payload (38 bytes)
            0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
            0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
            0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
            0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
            0x33, 0x04, 0x6D, 0x62, 0x6C, 0x6E
        )
        val crc16 = DjiCrc.computeCrc16(message)
        // The CRC16 should be 0x1CE8 based on the logs (little-endian: E8 1C)
        assertEquals(0x1CE8, crc16)
    }
}
