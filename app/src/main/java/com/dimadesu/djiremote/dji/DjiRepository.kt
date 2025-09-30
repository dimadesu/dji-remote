package com.dimadesu.djiremote.dji

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

object DjiRepository {
    private val _devices = MutableStateFlow<List<SettingsDjiDevice>>(emptyList())
    val devices: StateFlow<List<SettingsDjiDevice>> = _devices

    init {
        // seed with an example device
        _devices.value = listOf(SettingsDjiDevice(name = "ActionCam 1"))
    }

    fun addDevice(device: SettingsDjiDevice) {
        _devices.value = _devices.value + device
    }

    fun removeDevice(id: UUID) {
        _devices.value = _devices.value.filterNot { it.id == id }
    }

    fun updateDevice(device: SettingsDjiDevice) {
        _devices.value = _devices.value.map { if (it.id == device.id) device else it }
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
