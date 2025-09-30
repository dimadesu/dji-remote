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
        Text("DJI Devices", modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDevice(device) }
                    .padding(vertical = 8.dp)
                ) {
                    Text(device.name, modifier = Modifier.weight(1f))
                    Text(device.state.name)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = {
                val d = SettingsDjiDevice(name = "${'$'}{SettingsDjiDevice::class.simpleName} ${devices.size + 1}")
                DjiRepository.addDevice(d)
            }) {
                Text("Add device")
            }
        }
    }
}
