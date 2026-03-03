package com.dimadesu.djiremote.dji

import java.util.UUID

enum class SettingsDjiDeviceModel {
    UNKNOWN,
    OSMO_ACTION_2,
    OSMO_ACTION_3,
    OSMO_ACTION_4,
    OSMO_ACTION_5_PRO,
    OSMO_ACTION_6,
    OSMO_POCKET_3,
    OSMO_360;

    fun hasImageStabilization(): Boolean = when (this) {
        OSMO_ACTION_4, OSMO_ACTION_5_PRO, OSMO_ACTION_6, OSMO_360 -> true
        else -> false
    }

    fun hasNewProtocol(): Boolean = when (this) {
        OSMO_ACTION_5_PRO, OSMO_ACTION_6, OSMO_360 -> true
        else -> false
    }
}

enum class SettingsDjiDeviceState {
    IDLE, DISCOVERING, CONNECTING, PAIRING, STOPPING_STREAM, PREPARING_STREAM,
    SETTING_UP_WIFI, WIFI_SETUP_FAILED, CONFIGURING, STARTING_STREAM, STREAMING, UNKNOWN
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
    // Runtime state - included in data class equals/hashCode so StateFlow emits on change
    @Transient var isStarted: Boolean = false,
    @Transient var state: SettingsDjiDeviceState = SettingsDjiDeviceState.IDLE
)
