package com.dimadesu.djiremote.dji

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private const val TAG = "DjiModel"

// Simple model that manages DjiDevice instances and acts as the delegate
object DjiModel : DjiDeviceDelegate {
    private val devices = mutableMapOf<String, DjiDevice>()
    private val scope: CoroutineScope = MainScope()
    private val contextsByAddress = mutableMapOf<String, Context>()
    // Track which SettingsDjiDevice profile (by UUID) is actively streaming per BT address
    private val activeProfileByAddress = mutableMapOf<String, java.util.UUID>()

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
        contextsByAddress[address] = context
        activeProfileByAddress[address] = settings.id
        val device = devices.getOrPut(address) { DjiDevice(context).also { it.delegate = this } }
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
        val address = settings.bluetoothPeripheralAddress ?: return
        val device = devices[address] ?: return
        device.stopLiveStream()
        activeProfileByAddress.remove(address)
        scope.launch(Dispatchers.Main) {
            DjiRepository.updateDevice(settings.copy(isStarted = false, state = SettingsDjiDeviceState.IDLE))
        }
    }

    override fun djiDeviceStreamingState(device: DjiDevice, state: DjiDeviceState) {
        // find device by instance and update repository entries if address known
        // best-effort: update all devices where DjiDevice instance matches
        val address = devices.entries.firstOrNull { it.value === device }?.key
        if (address == null) return
        scope.launch(Dispatchers.Main) {
            val currentList = DjiRepository.devices.value
            val profileId = activeProfileByAddress[address]
            val existing = (if (profileId != null) {
                currentList.firstOrNull { it.id == profileId }
            } else {
                currentList.firstOrNull { it.bluetoothPeripheralAddress == address }
            }) ?: return@launch
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
            
            // Build updated copy — never mutate the object already in the list
            var updated = existing.copy(state = newState)

            when (state) {
                DjiDeviceState.IDLE, DjiDeviceState.WIFI_SETUP_FAILED -> {
                    updated = updated.copy(isStarted = false)
                    activeProfileByAddress.remove(address)
                }
                else -> {}
            }
            DjiRepository.updateDevice(updated)
        }
    }

}
