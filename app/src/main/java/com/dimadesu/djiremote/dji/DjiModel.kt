package com.dimadesu.djiremote.dji

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private const val TAG = "DjiModel"

// Manages DjiDevice instances keyed by profile UUID, matching Moblin's approach.
// Each SettingsDjiDevice profile gets its own DjiDevice instance.
object DjiModel : DjiDeviceDelegate {
    private val deviceWrappers = mutableMapOf<java.util.UUID, DjiDevice>()
    private val scope: CoroutineScope = MainScope()

    fun startStreaming(context: Context, settings: SettingsDjiDevice) {
        Log.d(TAG, "startStreaming called for device: ${settings.name}")
        Log.d(TAG, "  BT Address: ${settings.bluetoothPeripheralAddress}")
        Log.d(TAG, "  WiFi SSID: ${settings.wifiSsid}")
        Log.d(TAG, "  WiFi Password: ${settings.wifiPassword}")
        Log.d(TAG, "  RTMP URL: ${settings.rtmpUrl}")
        Log.d(TAG, "  Model: ${settings.model}")
        
        val address = settings.bluetoothPeripheralAddress
        if (address == null) {
            Log.e(TAG, "Cannot start streaming: Bluetooth device address is null!")
            return
        }
        if (settings.wifiSsid.isEmpty()) {
            Log.e(TAG, "Cannot start streaming: WiFi SSID is empty!")
            return
        }
        if (settings.wifiPassword.isEmpty()) {
            Log.e(TAG, "Cannot start streaming: WiFi password is empty!")
            return
        }
        if (settings.rtmpUrl.isEmpty()) {
            Log.e(TAG, "Cannot start streaming: RTMP URL is empty!")
            return
        }
        
        Log.d(TAG, "All prerequisites met, starting stream...")
        val device = deviceWrappers.getOrPut(settings.id) {
            DjiDevice(context).also { it.delegate = this }
        }
        // map Settings to DjiDevice start params
        val model = settings.model
        val res = when (settings.resolution) {
            "1080p" -> SettingsDjiDeviceResolution.r1080p
            "720p" -> SettingsDjiDeviceResolution.r720p
            "480p" -> SettingsDjiDeviceResolution.r480p
            else -> SettingsDjiDeviceResolution.r1080p
        }
        val imageStab = settings.imageStabilization
        device.startLiveStream(
            address = address,
            wifiSsid = settings.wifiSsid,
            wifiPassword = settings.wifiPassword,
            rtmpUrl = settings.rtmpUrl,
            resolution = res,
            fps = settings.fps,
            bitrateKbps = settings.bitrate / 1000,
            imageStabilization = imageStab,
            model = model
        )
        Log.d(TAG, "DjiDevice.startLiveStream() called")
        // update repository state — use copy() so StateFlow sees a new object
        scope.launch(Dispatchers.Main) {
            DjiRepository.updateDevice(settings.copy(isStarted = true, state = SettingsDjiDeviceState.PREPARING_STREAM))
        }
    }

    fun stopStreaming(settings: SettingsDjiDevice) {
        val device = deviceWrappers[settings.id] ?: return
        device.stopLiveStream()
        scope.launch(Dispatchers.Main) {
            DjiRepository.updateDevice(settings.copy(isStarted = false, state = SettingsDjiDeviceState.IDLE))
        }
    }

    // Find which profile owns a DjiDevice instance via === identity, matching Moblin
    private fun getSettingsForDevice(djiDevice: DjiDevice): SettingsDjiDevice? {
        val profileId = deviceWrappers.entries.firstOrNull { it.value === djiDevice }?.key
            ?: return null
        return DjiRepository.devices.value.firstOrNull { it.id == profileId }
    }

    override fun djiDeviceStreamingState(device: DjiDevice, state: DjiDeviceState) {
        scope.launch(Dispatchers.Main) {
            val existing = getSettingsForDevice(device) ?: return@launch
            val newState = when (state) {
                DjiDeviceState.IDLE -> SettingsDjiDeviceState.IDLE
                DjiDeviceState.DISCOVERING -> SettingsDjiDeviceState.DISCOVERING
                DjiDeviceState.CONNECTING -> SettingsDjiDeviceState.CONNECTING
                DjiDeviceState.CHECKING_IF_PAIRED -> SettingsDjiDeviceState.PAIRING
                DjiDeviceState.PAIRING -> SettingsDjiDeviceState.PAIRING
                DjiDeviceState.CLEANING_UP -> SettingsDjiDeviceState.STOPPING_STREAM
                DjiDeviceState.STOPPING_STREAM -> SettingsDjiDeviceState.STOPPING_STREAM
                DjiDeviceState.PREPARING_STREAM -> SettingsDjiDeviceState.PREPARING_STREAM
                DjiDeviceState.SETTING_UP_WIFI -> SettingsDjiDeviceState.SETTING_UP_WIFI
                DjiDeviceState.WIFI_SETUP_FAILED -> SettingsDjiDeviceState.WIFI_SETUP_FAILED
                DjiDeviceState.CONFIGURING -> SettingsDjiDeviceState.CONFIGURING
                DjiDeviceState.STARTING_STREAM -> SettingsDjiDeviceState.STARTING_STREAM
                DjiDeviceState.STREAMING -> SettingsDjiDeviceState.STREAMING
            }
            
            var updated = existing.copy(state = newState)

            when (state) {
                DjiDeviceState.IDLE, DjiDeviceState.WIFI_SETUP_FAILED -> {
                    updated = updated.copy(isStarted = false)
                }
                else -> {}
            }
            DjiRepository.updateDevice(updated)
        }
    }

}
