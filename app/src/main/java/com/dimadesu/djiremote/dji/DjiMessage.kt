package com.dimadesu.djiremote.dji

import java.lang.IllegalArgumentException

class DjiMessage(var target: Int, var id: Int, var type: Int, var payload: ByteArray) {
    companion object {
        @Throws(IllegalArgumentException::class)
        fun fromBytes(data: ByteArray): DjiMessage {
            val reader = ByteReader(data)
            val first = reader.readUInt8()
            if (first != 0x55) throw IllegalArgumentException("Bad first byte")
            val length = reader.readUInt8()
            if (data.size != length) throw IllegalArgumentException("Bad length")
            val version = reader.readUInt8()
            if (version != 0x04) throw IllegalArgumentException("Bad version")
            val headerCrc = reader.readUInt8()
            val calculatedHeaderCrc = DjiCrc.computeCrc8(data.copyOfRange(0, 3))
            if (headerCrc != calculatedHeaderCrc) throw IllegalArgumentException("Bad header CRC")
            val target = reader.readUInt16Le()
            val id = reader.readUInt16Le()
            val type = reader.readUInt24Le()
            val payload = reader.readBytes(reader.bytesAvailable - 2)
            val crc = reader.readUInt16Le()
            val withoutCrc = data.copyOfRange(0, data.size - 2)
            val calcCrc = DjiCrc.computeCrc16(withoutCrc)
            if (crc != calcCrc) throw IllegalArgumentException("Bad CRC")
            return DjiMessage(target, id, type, payload)
        }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeUInt8(0x55)
        writer.writeUInt8(13 + payload.size)
        writer.writeUInt8(0x04)
        val headerCrc = DjiCrc.computeCrc8(writer.data)
        writer.writeUInt8(headerCrc)
        writer.writeUInt16Le(target)
        writer.writeUInt16Le(id)
        writer.writeUInt24Le(type)
        writer.writeBytes(payload)
        val withoutCrc = writer.data
        val crc = DjiCrc.computeCrc16(withoutCrc)
        writer.writeUInt16Le(crc)
        return writer.data
    }

    fun format(): String {
        return "target: $target, id: $id, type: $type ${payload.joinToString("") { String.format("%02x", it) }}"
    }
}
