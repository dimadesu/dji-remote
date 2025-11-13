package com.dimadesu.djiremote.dji

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

object DjiRepository {
    private val _devices = MutableStateFlow<List<SettingsDjiDevice>>(emptyList())
    val devices: StateFlow<List<SettingsDjiDevice>> = _devices
    
    private var context: Context? = null
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        this.context = context.applicationContext
        isInitialized = true
        
        // Load devices from storage
        _devices.value = DjiDeviceStorage.loadDevices(context)
        
        // If no devices, seed with example
        if (_devices.value.isEmpty()) {
            _devices.value = listOf(SettingsDjiDevice(name = "ActionCam 1"))
            saveToStorage()
        }
    }
    
    private fun saveToStorage() {
        context?.let { ctx ->
            DjiDeviceStorage.saveDevices(ctx, _devices.value)
        }
    }

    fun addDevice(device: SettingsDjiDevice) {
        _devices.value = _devices.value + device
        saveToStorage()
    }

    fun removeDevice(id: UUID) {
        _devices.value = _devices.value.filterNot { it.id == id }
        saveToStorage()
    }

    fun updateDevice(device: SettingsDjiDevice) {
        _devices.value = _devices.value.map { if (it.id == device.id) device else it }
        saveToStorage()
    }
}

// Fake scanner that returns discovered (simulated) devices
object DjiScanner {
    // Return triples of (id, address, name). In real scanning the id is derived from address.
    private val simulated = listOf(
        Triple(UUID.randomUUID().toString(), "AA:BB:CC:DD:EE:01", "DJI-Cam-001"),
        Triple(UUID.randomUUID().toString(), "AA:BB:CC:DD:EE:02", "DJI-Cam-002")
    )

    fun getDiscoveredDevices(): List<Triple<String, String, String>> = simulated
}
