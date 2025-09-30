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
    private val restartHandlers = mutableMapOf<String, android.os.Handler>()
    private val restartRunnables = mutableMapOf<String, Runnable>()
    private val restartDelayMs: Long = 5_000 // default auto-restart delay
    private val contextsByAddress = mutableMapOf<String, Context>()

    fun startStreaming(context: Context, settings: SettingsDjiDevice) {
        val address = settings.bluetoothPeripheralAddress ?: return
        contextsByAddress[address] = context
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

            // Auto-restart logic: if device went to IDLE or WIFI_SETUP_FAILED and the
            // settings indicate autoRestartStream and isStarted (user-initiated), schedule a restart.
            when (state) {
                DjiDeviceState.IDLE, DjiDeviceState.WIFI_SETUP_FAILED -> {
                    if (existing.autoRestartStream && existing.isStarted) {
                        scheduleRestartForAddress(existing.bluetoothPeripheralAddress, updated)
                    }
                }
                DjiDeviceState.STREAMING -> {
                    // cancel any pending restart when streaming
                    cancelScheduledRestart(existing.bluetoothPeripheralAddress)
                }
                else -> {
                }
            }
        }
    }

    private fun scheduleRestartForAddress(address: String?, settings: SettingsDjiDevice) {
        if (address == null) return
        cancelScheduledRestart(address)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = Runnable {
            // Re-start if still marked started
            val current = DjiRepository.devices.value.firstOrNull { it.bluetoothPeripheralAddress == address } ?: return@Runnable
            if (!current.isStarted) return@Runnable
            // call startStreaming using the stored context
            val ctx = contextsByAddress[address] ?: return@Runnable
            startStreaming(ctx, current)
        }
        restartHandlers[address] = handler
        restartRunnables[address] = runnable
        handler.postDelayed(runnable, restartDelayMs)
    }

    private fun cancelScheduledRestart(address: String?) {
        if (address == null) return
        val handler = restartHandlers.remove(address)
        val runnable = restartRunnables.remove(address)
        if (handler != null && runnable != null) {
            handler.removeCallbacks(runnable)
        }
    }
}
