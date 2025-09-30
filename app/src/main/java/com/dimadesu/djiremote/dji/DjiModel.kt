package com.dimadesu.djiremote.dji

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// Simple model that manages DjiDevice instances and acts as the delegate
object DjiModel : DjiDeviceDelegate {
    private val devices = mutableMapOf<String, DjiDevice>()
    private val scope: CoroutineScope = MainScope()

    fun startStreaming(context: Context, settings: SettingsDjiDevice) {
        val address = settings.bluetoothPeripheralAddress ?: return
        val device = devices.getOrPut(address) { DjiDevice(context).also { it.delegate = this } }
        // map Settings to DjiDevice start params
        val model = settings.model
        val res = when (settings.resolution) {
            "1080p" -> SettingsDjiDeviceResolution.r1080p
            "720p" -> SettingsDjiDeviceResolution.r720p
            else -> SettingsDjiDeviceResolution.r1080p
        }
        val imageStab = if (settings.imageStabilization) SettingsDjiDeviceImageStabilization.rockSteady else SettingsDjiDeviceImageStabilization.off
        device.startLiveStream(
            address = address,
            wifiSsid = settings.wifiSsid,
            wifiPassword = settings.wifiPassword,
            rtmpUrl = if (settings.rtmpUrlType == SettingsDjiDeviceUrlType.CUSTOM) settings.customRtmpUrl else settings.serverRtmpUrl,
            resolution = res,
            fps = settings.fps,
            bitrateKbps = settings.bitrate / 1000,
            imageStabilization = imageStab,
            model = model
        )
        // update repository state
        scope.launch(Dispatchers.Main) {
            settings.isStarted = true
            settings.state = SettingsDjiDeviceState.PREPARING_STREAM
            DjiRepository.updateDevice(settings)
        }
    }

    fun stopStreaming(settings: SettingsDjiDevice) {
        val address = settings.bluetoothPeripheralAddress ?: return
        val device = devices[address] ?: return
        device.stopLiveStream()
        scope.launch(Dispatchers.Main) {
            settings.isStarted = false
            settings.state = SettingsDjiDeviceState.IDLE
            DjiRepository.updateDevice(settings)
        }
    }

    override fun djiDeviceStreamingState(device: DjiDevice, state: DjiDeviceState) {
        // find device by instance and update repository entries if address known
        // best-effort: update all devices where DjiDevice instance matches
        val address = devices.entries.firstOrNull { it.value === device }?.key
        if (address == null) return
        scope.launch(Dispatchers.Main) {
            val currentList = DjiRepository.devices.value
            val existing = currentList.firstOrNull { it.bluetoothPeripheralAddress == address } ?: return@launch
            val newState = when (state) {
                DjiDeviceState.IDLE -> SettingsDjiDeviceState.IDLE
                DjiDeviceState.DISCOVERING -> SettingsDjiDeviceState.DISCOVERING
                DjiDeviceState.CONNECTING -> SettingsDjiDeviceState.CONNECTING
                DjiDeviceState.PAIRING -> SettingsDjiDeviceState.PAIRING
                DjiDeviceState.PREPARING_STREAM -> SettingsDjiDeviceState.PREPARING_STREAM
                DjiDeviceState.STARTING_STREAM -> SettingsDjiDeviceState.STARTING_STREAM
                DjiDeviceState.STREAMING -> SettingsDjiDeviceState.STREAMING
                DjiDeviceState.WIFI_SETUP_FAILED -> SettingsDjiDeviceState.WIFI_SETUP_FAILED
                else -> SettingsDjiDeviceState.UNKNOWN
            }
            val updated = existing.copy(state = newState)
            DjiRepository.updateDevice(updated)
        }
    }
}
