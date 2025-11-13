package com.dimadesu.djiremote.dji

import org.junit.Test
import java.security.MessageDigest

class DjiHashCalculationTest {
    @Test
    fun testPossibleHashSources() {
        // The hash in iOS is: 20 32 38 34 61 65 35 62...
        // This is 33 bytes, which doesn't match common hash sizes
        // MD5 = 16 bytes, SHA1 = 20 bytes, SHA256 = 32 bytes
        // 33 bytes might be SHA256 + 1 byte prefix?
        
        val testInputs = listOf(
            "mbln",
            "E4:7A:2C:80:0B:AA",
            "E47A2C800BAA", 
            "OsmoAction4-0BA9",
            "DJI",
            ""
        )
        
        for (input in testInputs) {
            println("\nInput: '$input'")
            
            // Try MD5
            val md5 = MessageDigest.getInstance("MD5")
            val md5Hash = md5.digest(input.toByteArray())
            println("  MD5 (${md5Hash.size} bytes): ${md5Hash.joinToString(" ") { "%02X".format(it) }}")
            
            // Try SHA-256
            val sha256 = MessageDigest.getInstance("SHA-256")
            val sha256Hash = sha256.digest(input.toByteArray())
            println("  SHA256 (${sha256Hash.size} bytes): ${sha256Hash.joinToString(" ") { "%02X".format(it) }}")
            
            // Check if adding a length byte gives us 33
            val withLength = byteArrayOf(sha256Hash.size.toByte()) + sha256Hash
            println("  SHA256 with length prefix (${withLength.size} bytes): ${withLength.take(10).joinToString(" ") { "%02X".format(it) }}...")
        }
        
        // Check if the iOS hash is actually ASCII
        val iosHash = byteArrayOf(
            0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
            0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
            0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
            0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
            0x33
        )
        
        // First byte 0x20 is space in ASCII
        println("\niOS hash as ASCII: '${String(iosHash)}'")
        // This looks like: " 284ae5b8d76b3375a04a6417ad71bea3"
        // Which is 32 hex chars (16 bytes) with a space prefix!
        
        val hexString = String(iosHash).trim()
        println("Trimmed: '$hexString'")
        println("Length: ${hexString.length} chars")
        
        if (hexString.length == 32) {
            println("This is a 32-char hex string = 16 bytes = MD5 hash!")
            // Convert hex string to bytes
            val bytes = hexString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            println("As bytes: ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
    }
}
