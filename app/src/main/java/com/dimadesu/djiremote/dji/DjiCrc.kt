package com.dimadesu.djiremote.dji

object DjiCrc {
    // CRC-8: spec params init=0xEE, poly=0x31, refIn=true, refOut=true
    // Reflected (LSB-first) algorithm uses reflected init and poly:
    //   reflected init = reflect8(0xEE) = 0x77
    //   reflected poly = reflect8(0x31) = 0x8C
    fun computeCrc8(data: ByteArray, initial: Int = 0x77, poly: Int = 0x8C): Int {
        var crc = initial and 0xFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                if ((crc and 0x01) != 0) {
                    crc = (crc ushr 1) xor poly
                } else {
                    crc = crc ushr 1
                }
            }
        }
        return crc and 0xFF
    }

    // CRC-16: spec params init=0x496C, poly=0x1021, refIn=true, refOut=true
    // Reflected (LSB-first) algorithm uses reflected init and poly:
    //   reflected init = reflect16(0x496C) = 0x3692
    //   reflected poly = reflect16(0x1021) = 0x8408
    fun computeCrc16(data: ByteArray, initial: Int = 0x3692, poly: Int = 0x8408): Int {
        var crc = initial and 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                if ((crc and 0x01) != 0) {
                    crc = (crc ushr 1) xor poly
                } else {
                    crc = crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}
