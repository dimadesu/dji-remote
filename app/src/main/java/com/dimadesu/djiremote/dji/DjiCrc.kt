package com.dimadesu.djiremote.dji

object DjiCrc {
    // CRC-8 with parameters: initial 0xEE, poly 0x31, refIn/refOut true
    fun computeCrc8(data: ByteArray, initial: Int = 0xEE, poly: Int = 0x31): Int {
        var crc = initial and 0xFF
        for (b in data) {
            var cur = (b.toInt() and 0xFF)
            crc = crc xor cur
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

    // CRC-16 with parameters: initial 0x496C, poly 0x1021, refIn/refOut true
    fun computeCrc16(data: ByteArray, initial: Int = 0x496C, poly: Int = 0x1021): Int {
        var crc = initial and 0xFFFF
        for (b in data) {
            var cur = b.toInt() and 0xFF
            crc = crc xor cur
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
