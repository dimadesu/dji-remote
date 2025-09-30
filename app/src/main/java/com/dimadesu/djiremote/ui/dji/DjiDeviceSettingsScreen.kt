package com.dimadesu.djiremote.ui.dji

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dimadesu.djiremote.dji.DjiRepository
import com.dimadesu.djiremote.dji.SettingsDjiDevice

@Composable
fun DjiDeviceSettingsScreen(device: SettingsDjiDevice, onBack: () -> Unit = {}) {
    var ssid by remember { mutableStateOf(device.wifiSsid) }
    var password by remember { mutableStateOf(device.wifiPassword) }
    var autoRestart by remember { mutableStateOf(device.autoRestartStream) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(device.name)
        Spacer(modifier = Modifier.height(8.dp))
        TextField(value = ssid, onValueChange = { ssid = it }, label = { Text("SSID") })
        Spacer(modifier = Modifier.height(8.dp))
        TextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Auto restart")
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = autoRestart, onCheckedChange = { autoRestart = it })
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = {
                // Update repository
                device.wifiSsid = ssid
                device.wifiPassword = password
                device.autoRestartStream = autoRestart
                DjiRepository.updateDevice(device)
                onBack()
            }) {
                Text("Save")
            }
            if (!device.isStarted) {
                Button(onClick = {
                    device.isStarted = true
                    device.state = com.dimadesu.djiremote.dji.SettingsDjiDeviceState.CONNECTING
                    DjiRepository.updateDevice(device)
                }) { Text("Start stream") }
            } else {
                Button(onClick = {
                    device.isStarted = false
                    device.state = com.dimadesu.djiremote.dji.SettingsDjiDeviceState.IDLE
                    DjiRepository.updateDevice(device)
                }) { Text("Stop stream") }
            }
        }
    }
}
