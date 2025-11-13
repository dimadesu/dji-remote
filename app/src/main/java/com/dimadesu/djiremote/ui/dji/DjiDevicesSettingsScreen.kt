package com.dimadesu.djiremote.ui.dji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dimadesu.djiremote.dji.DjiRepository
import com.dimadesu.djiremote.dji.SettingsDjiDevice

@Composable
fun DjiDevicesSettingsScreen(onOpenDevice: (SettingsDjiDevice) -> Unit) {
    val devices by DjiRepository.devices.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("DJI Devices", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Button(onClick = {
                val d = SettingsDjiDevice(name = "DJI Device ${devices.size + 1}")
                DjiRepository.addDevice(d)
            }) {
                Text("Create")
            }
        }
        
        if (devices.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No devices yet",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap Create to add a device",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(devices) { device ->
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOpenDevice(device) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = device.name,
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = device.state.name,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = when (device.state) {
                                        com.dimadesu.djiremote.dji.SettingsDjiDeviceState.STREAMING -> 
                                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                                        com.dimadesu.djiremote.dji.SettingsDjiDeviceState.WIFI_SETUP_FAILED -> 
                                            androidx.compose.material3.MaterialTheme.colorScheme.error
                                        else -> 
                                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            if (device.bluetoothPeripheralName != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = device.bluetoothPeripheralName!!,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
