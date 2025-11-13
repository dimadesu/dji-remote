package com.dimadesu.djiremote.dji

// Helpers to pack strings/urls like the iOS code
fun djiPackString(value: String): ByteArray {
    val data = value.toByteArray(Charsets.UTF_8)
    val writer = ByteWriter()
    writer.writeUInt8(data.size)
    writer.writeBytes(data)
    return writer.data
}

fun djiPackUrl(url: String): ByteArray {
    val data = url.toByteArray(Charsets.UTF_8)
    val writer = ByteWriter()
    writer.writeUInt8(data.size)
    writer.writeUInt8(0)
    writer.writeBytes(data)
    return writer.data
}

enum class SettingsDjiDeviceResolution { r480p, r720p, r1080p }

class DjiPairMessagePayload(private val pairPinCode: String) {
    companion object {
        val payload = byteArrayOf(
            0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
            0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
            0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
            0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
            0x33
        )
    }

    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeBytes(payload)
        writer.writeBytes(djiPackString(pairPinCode))
        return writer.data
    }
}

class DjiPreparingToLivestreamMessagePayload {
    companion object {
        val payload = byteArrayOf(0x1A)
    }

    fun encode(): ByteArray = payload
}

class DjiSetupWifiMessagePayload(private val wifiSsid: String, private val wifiPassword: String) {
    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeBytes(djiPackString(wifiSsid))
        writer.writeBytes(djiPackString(wifiPassword))
        return writer.data
    }
}

class DjiStartStreamingMessagePayload(
    private val rtmpUrl: String,
    private val resolution: SettingsDjiDeviceResolution,
    private val fps: Int,
    private val bitrateKbps: Int,
    private val oa5: Boolean
) {
    companion object {
        val payload1 = byteArrayOf(0x00)
        val payload2 = byteArrayOf(0x00)
        val payload3 = byteArrayOf(0x02, 0x00)
        val payload4 = byteArrayOf(0x00, 0x00, 0x00)
    }

    fun encode(): ByteArray {
        val resolutionByte: Int = when (resolution) {
            SettingsDjiDeviceResolution.r480p -> 0x47
            SettingsDjiDeviceResolution.r720p -> 0x04
            SettingsDjiDeviceResolution.r1080p -> 0x0A
        }
        val fpsByte: Int = when (fps) {
            25 -> 2
            30 -> 3
            else -> 0
        }
        val byte1 = if (oa5) 0x2A else 0x2E

        val writer = ByteWriter()
        writer.writeBytes(payload1)
        writer.writeUInt8(byte1)
        writer.writeBytes(payload2)
        writer.writeUInt8(resolutionByte)
        writer.writeUInt16Le(bitrateKbps and 0xFFFF)
        writer.writeBytes(payload3)
        writer.writeUInt8(fpsByte)
        writer.writeBytes(payload4)
        writer.writeBytes(djiPackUrl(rtmpUrl))
        return writer.data
    }
}

class DjiConfirmStartStreamingMessagePayload {
    companion object {
        val payload = byteArrayOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x01)
    }

    fun encode(): ByteArray = payload
}

class DjiStopStreamingMessagePayload {
    companion object {
        val payload = byteArrayOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x02)
    }

    fun encode(): ByteArray = payload
}

class DjiConfigureMessagePayload(private val imageStabilization: SettingsDjiDeviceImageStabilization, private val oa5: Boolean) {
    companion object {
        val payload1 = byteArrayOf(0x01, 0x01)
        val payload2 = byteArrayOf(0x00, 0x01)
    }

    fun encode(): ByteArray {
        val imageStabilizationByte = when (imageStabilization) {
            SettingsDjiDeviceImageStabilization.OFF -> 0
            SettingsDjiDeviceImageStabilization.ROCK_STEADY -> 1
            SettingsDjiDeviceImageStabilization.ROCK_STEADY_PLUS -> 3
            SettingsDjiDeviceImageStabilization.HORIZON_BALANCING -> 4
            SettingsDjiDeviceImageStabilization.HORIZON_STEADY -> 2
        }
        val byte1 = if (oa5) 0x1A else 0x08
        val writer = ByteWriter()
        writer.writeBytes(payload1)
        writer.writeUInt8(byte1)
        writer.writeBytes(payload2)
        writer.writeUInt8(imageStabilizationByte)
        return writer.data
    }
}
