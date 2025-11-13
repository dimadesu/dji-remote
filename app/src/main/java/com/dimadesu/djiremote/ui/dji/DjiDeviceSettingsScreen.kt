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
    var imageStabilization by remember { mutableStateOf(device.imageStabilization) }
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
    
    fun imageStabToString(value: com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization): String {
        return when (value) {
            com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.OFF -> "Off"
            com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.ROCK_STEADY -> "RockSteady"
            com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.ROCK_STEADY_PLUS -> "RockSteady+"
            com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.HORIZON_BALANCING -> "HorizonBalancing"
            com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.HORIZON_STEADY -> "HorizonSteady"
        }
    }
    
    fun stringToImageStab(value: String): com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization {
        return when (value) {
            "RockSteady" -> com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.ROCK_STEADY
            "RockSteady+" -> com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.ROCK_STEADY_PLUS
            "HorizonBalancing" -> com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.HORIZON_BALANCING
            "HorizonSteady" -> com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.HORIZON_STEADY
            else -> com.dimadesu.djiremote.dji.SettingsDjiDeviceImageStabilization.OFF
        }
    }
    
    val imageStabOptions = listOf("Off", "RockSteady", "RockSteady+", "HorizonBalancing", "HorizonSteady")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Name section (no header in Moblin)
        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Device section
        Text("Device", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onOpenScanner,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(device.bluetoothPeripheralName ?: "Select device")
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
        
        // WiFi section
        Text("WiFi", style = MaterialTheme.typography.titleMedium)
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
        
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "The DJI device will connect to and stream RTMP over this WiFi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // RTMP section
        Text("RTMP", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        TextField(
            value = rtmpUrl,
            onValueChange = { rtmpUrl = it },
            label = { Text("URL") },
            placeholder = { Text("rtmp://server/live/stream") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Settings section
        Text("Settings", style = MaterialTheme.typography.titleMedium)
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
                value = imageStabToString(imageStabilization),
                onValueChange = {},
                readOnly = true,
                label = { Text("Image stabilization") },
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
                            imageStabilization = stringToImageStab(option)
                            expandedImageStab = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "High bitrates may be unstable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Auto-restart section (no header, just toggle)
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Auto-restart live stream when broken", modifier = Modifier.weight(1f))
            Switch(checked = autoRestart, onCheckedChange = { autoRestart = it })
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Status section (no header in Moblin, just centered text)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = when (device.state) {
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.IDLE -> "Not started"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.DISCOVERING -> "Discovering"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.CONNECTING -> "Connecting"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.PAIRING -> "Pairing"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.PREPARING_STREAM -> "Preparing to stream"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.STARTING_STREAM -> "Starting stream"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.STREAMING -> "Streaming"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.WIFI_SETUP_FAILED -> "WiFi setup failed"
                    else -> "Unknown"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Start/Stop button section (no header in Moblin)
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
                    device.imageStabilization = imageStabilization
                    device.autoRestartStream = autoRestart
                    DjiRepository.updateDevice(device)
                    
                    com.dimadesu.djiremote.dji.DjiModel.startStreaming(context, device)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start live stream")
            }
        } else {
            Button(
                onClick = {
                    com.dimadesu.djiremote.dji.DjiModel.stopStreaming(device)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Stop live stream")
            }
        }
    }
}
