package com.dimadesu.djiremote.dji

import java.util.UUID

enum class SettingsDjiDeviceModel {
    UNKNOWN, OSMO_ACTION_4, OSMO_ACTION_5_PRO, OSMO_POCKET_3
}

enum class SettingsDjiDeviceState {
    IDLE, DISCOVERING, CONNECTING, PAIRING, PREPARING_STREAM, STARTING_STREAM, STREAMING, WIFI_SETUP_FAILED, UNKNOWN
}

enum class SettingsDjiDeviceUrlType { SERVER, CUSTOM }

data class SettingsDjiDevice(
    val id: UUID = UUID.randomUUID(),
    var name: String = "DJI Device",
    var bluetoothPeripheralName: String? = null,
    var bluetoothPeripheralId: UUID? = null,
    var model: SettingsDjiDeviceModel = SettingsDjiDeviceModel.UNKNOWN,
    var wifiSsid: String = "",
    var wifiPassword: String = "",
    var rtmpUrlType: SettingsDjiDeviceUrlType = SettingsDjiDeviceUrlType.SERVER,
    var serverRtmpStreamId: UUID? = null,
    var serverRtmpUrl: String = "",
    var customRtmpUrl: String = "",
    var resolution: String = "1080p",
    var fps: Int = 30,
    var bitrate: Int = 4_000_000,
    var imageStabilization: Boolean = false,
    var autoRestartStream: Boolean = false,
    var isStarted: Boolean = false,
    var state: SettingsDjiDeviceState = SettingsDjiDeviceState.IDLE
)
