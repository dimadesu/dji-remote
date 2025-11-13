package com.dimadesu.djiremote.dji

import java.util.UUID

enum class SettingsDjiDeviceModel {
    UNKNOWN, OSMO_ACTION_4, OSMO_ACTION_5_PRO, OSMO_POCKET_3
}

enum class SettingsDjiDeviceState {
    IDLE, DISCOVERING, CONNECTING, PAIRING, PREPARING_STREAM, STARTING_STREAM, STREAMING, WIFI_SETUP_FAILED, UNKNOWN
}

enum class SettingsDjiDeviceImageStabilization {
    OFF, ROCK_STEADY, ROCK_STEADY_PLUS, HORIZON_BALANCING, HORIZON_STEADY
}

data class SettingsDjiDevice(
    val id: UUID = UUID.randomUUID(),
    var name: String = "DJI Device",
    var bluetoothPeripheralName: String? = null,
    var bluetoothPeripheralId: UUID? = null,
    var bluetoothPeripheralAddress: String? = null,
    var model: SettingsDjiDeviceModel = SettingsDjiDeviceModel.UNKNOWN,
    var wifiSsid: String = "",
    var wifiPassword: String = "",
    var rtmpUrl: String = "",
    var resolution: String = "1080p",
    var fps: Int = 30,
    var bitrate: Int = 6_000_000,
    var imageStabilization: SettingsDjiDeviceImageStabilization = SettingsDjiDeviceImageStabilization.OFF,
    var autoRestartStream: Boolean = false
) {
    // Runtime state - not persisted
    @Transient
    var isStarted: Boolean = false
    @Transient
    var state: SettingsDjiDeviceState = SettingsDjiDeviceState.IDLE
}
