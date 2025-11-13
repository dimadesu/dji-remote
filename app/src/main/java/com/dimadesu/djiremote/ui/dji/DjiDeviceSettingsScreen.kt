package com.dimadesu.djiremote.ui.dji

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dimadesu.djiremote.dji.DjiRepository
import com.dimadesu.djiremote.dji.SettingsDjiDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjiDeviceSettingsScreen(
    device: SettingsDjiDevice, 
    onBack: () -> Unit = {},
    onOpenScanner: () -> Unit = {}
) {
    var name by remember { mutableStateOf(device.name) }
    var ssid by remember { mutableStateOf(device.wifiSsid) }
    var password by remember { mutableStateOf(device.wifiPassword) }
    var rtmpUrl by remember { mutableStateOf(device.customRtmpUrl) }
    var resolution by remember { mutableStateOf(device.resolution) }
    var bitrate by remember { mutableStateOf(device.bitrate) }
    var imageStabilization by remember { mutableStateOf(
        when {
            !device.imageStabilization -> "Off"
            else -> "RockSteady"
        }
    ) }
    var autoRestart by remember { mutableStateOf(device.autoRestartStream) }
    var expandedResolution by remember { mutableStateOf(false) }
    var expandedBitrate by remember { mutableStateOf(false) }
    var expandedImageStab by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    val resolutionOptions = listOf("480p", "720p", "1080p")
    val bitrateOptions = listOf(
        20_000_000,
        16_000_000,
        12_000_000,
        10_000_000,
        8_000_000,
        6_000_000,
        4_000_000,
        2_000_000
    )
    val imageStabOptions = listOf("Off", "RockSteady", "RockSteady+", "HorizonBalancing", "HorizonSteady")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Name
        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Bluetooth device selection
        Text("Bluetooth Device", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = onOpenScanner,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(device.bluetoothPeripheralName ?: "Select Bluetooth device")
        }
        if (device.bluetoothPeripheralAddress != null) {
            Text(
                text = device.bluetoothPeripheralAddress!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // WiFi Settings
        Text("WiFi Settings", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        TextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text("SSID") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // RTMP Settings
        Text("RTMP Settings", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        TextField(
            value = rtmpUrl,
            onValueChange = { rtmpUrl = it },
            label = { Text("RTMP URL") },
            placeholder = { Text("rtmp://server/live/stream") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Stream Settings
        Text("Stream Settings", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        // Resolution dropdown
        ExposedDropdownMenuBox(
            expanded = expandedResolution,
            onExpandedChange = { expandedResolution = !expandedResolution }
        ) {
            TextField(
                value = resolution,
                onValueChange = {},
                readOnly = true,
                label = { Text("Resolution") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedResolution) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expandedResolution,
                onDismissRequest = { expandedResolution = false }
            ) {
                resolutionOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            resolution = option
                            expandedResolution = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Bitrate dropdown
        ExposedDropdownMenuBox(
            expanded = expandedBitrate,
            onExpandedChange = { expandedBitrate = !expandedBitrate }
        ) {
            TextField(
                value = "${bitrate / 1_000_000} Mbps (${bitrate} bps)",
                onValueChange = {},
                readOnly = true,
                label = { Text("Bitrate") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedBitrate) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expandedBitrate,
                onDismissRequest = { expandedBitrate = false }
            ) {
                bitrateOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("${option / 1_000_000} Mbps") },
                        onClick = {
                            bitrate = option
                            expandedBitrate = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Image Stabilization dropdown
        ExposedDropdownMenuBox(
            expanded = expandedImageStab,
            onExpandedChange = { expandedImageStab = !expandedImageStab }
        ) {
            TextField(
                value = imageStabilization,
                onValueChange = {},
                readOnly = true,
                label = { Text("Image Stabilization") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedImageStab) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expandedImageStab,
                onDismissRequest = { expandedImageStab = false }
            ) {
                imageStabOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            imageStabilization = option
                            expandedImageStab = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Auto Restart", modifier = Modifier.weight(1f))
            Switch(checked = autoRestart, onCheckedChange = { autoRestart = it })
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Status
        Text("Status", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "State: ${device.state.name}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (device.bluetoothPeripheralName != null) {
                    Text(
                        text = "Device: ${device.bluetoothPeripheralName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    // Save all changes
                    device.name = name
                    device.wifiSsid = ssid
                    device.wifiPassword = password
                    device.customRtmpUrl = rtmpUrl
                    device.resolution = resolution
                    device.bitrate = bitrate
                    device.imageStabilization = imageStabilization != "Off"
                    device.autoRestartStream = autoRestart
                    DjiRepository.updateDevice(device)
                    onBack()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save")
            }
            
            if (!device.isStarted) {
                Button(
                    onClick = {
                        // Save before starting
                        device.name = name
                        device.wifiSsid = ssid
                        device.wifiPassword = password
                        device.customRtmpUrl = rtmpUrl
                        device.resolution = resolution
                        device.bitrate = bitrate
                        device.imageStabilization = imageStabilization != "Off"
                        device.autoRestartStream = autoRestart
                        DjiRepository.updateDevice(device)
                        
                        com.dimadesu.djiremote.dji.DjiModel.startStreaming(context, device)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Start Stream")
                }
            } else {
                Button(
                    onClick = {
                        com.dimadesu.djiremote.dji.DjiModel.stopStreaming(device)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Stop Stream")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Delete button
        OutlinedButton(
            onClick = {
                DjiRepository.removeDevice(device.id)
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Delete Device")
        }
    }
}
