package com.dimadesu.djiremote.ui.dji

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    // Observe the live device from the repository so UI recomposes on state/isStarted changes
    val allDevices by DjiRepository.devices.collectAsState()
    val liveDevice = allDevices.firstOrNull { it.id == device.id } ?: device
    
    var name by remember { mutableStateOf(liveDevice.name) }
    var ssid by remember { mutableStateOf(liveDevice.wifiSsid) }
    var password by remember { mutableStateOf(liveDevice.wifiPassword) }
    var rtmpUrl by remember { mutableStateOf(liveDevice.rtmpUrl) }
    var resolution by remember { mutableStateOf(liveDevice.resolution) }
    var bitrate by remember { mutableStateOf(liveDevice.bitrate) }
    var imageStabilization by remember { mutableStateOf(liveDevice.imageStabilization) }

    var expandedResolution by remember { mutableStateOf(false) }
    var expandedBitrate by remember { mutableStateOf(false) }
    var expandedImageStab by remember { mutableStateOf(false) }
    var expandedFps by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    val resolutionOptions = listOf("1080p", "720p", "480p")
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
    
    // Save config to repository whenever user leaves the screen.
    // Read the latest device from the repository (not the captured liveDevice) so we
    // preserve runtime state (isStarted, state) that may have changed since first composition.
    DisposableEffect(Unit) {
        onDispose {
            val latest = DjiRepository.devices.value.firstOrNull { it.id == device.id } ?: return@onDispose
            DjiRepository.updateDevice(latest.copy(
                name = name,
                wifiSsid = ssid,
                wifiPassword = password,
                rtmpUrl = rtmpUrl,
                resolution = resolution,
                bitrate = bitrate,
                imageStabilization = imageStabilization
            ))
        }
    }
    
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
            modifier = Modifier.fillMaxWidth(),
            enabled = !liveDevice.isStarted
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Device section
        Text("Device", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onOpenScanner,
            modifier = Modifier.fillMaxWidth(),
            enabled = !liveDevice.isStarted
        ) {
            Text(liveDevice.bluetoothPeripheralName ?: "Select device")
        }
        if (liveDevice.bluetoothPeripheralAddress != null) {
            Text(
                text = liveDevice.bluetoothPeripheralAddress!!,
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
            modifier = Modifier.fillMaxWidth(),
            enabled = !liveDevice.isStarted
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !liveDevice.isStarted,
            visualTransformation = PasswordVisualTransformation()
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
            modifier = Modifier.fillMaxWidth(),
            enabled = !liveDevice.isStarted
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
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                enabled = !liveDevice.isStarted
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
                value = "${bitrate / 1_000_000} Mbps",
                onValueChange = {},
                readOnly = true,
                label = { Text("Bitrate") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedBitrate) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                enabled = !liveDevice.isStarted
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
        
        // Image Stabilization dropdown (only for models that support it)
        if (liveDevice.model.hasImageStabilization()) {
        Spacer(modifier = Modifier.height(8.dp))
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
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                enabled = !liveDevice.isStarted
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
        } // end if hasImageStabilization
        
        // FPS picker (only for Osmo Pocket 3, matching Moblin)
        if (liveDevice.model == com.dimadesu.djiremote.dji.SettingsDjiDeviceModel.OSMO_POCKET_3) {
            val fpsOptions = listOf(25, 30)
            var fps by remember { mutableStateOf(liveDevice.fps) }
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = expandedFps,
                onExpandedChange = { expandedFps = !expandedFps }
            ) {
                TextField(
                    value = "$fps",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("FPS") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFps) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    enabled = !liveDevice.isStarted
                )
                ExposedDropdownMenu(
                    expanded = expandedFps,
                    onDismissRequest = { expandedFps = false }
                ) {
                    fpsOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("$option") },
                            onClick = {
                                fps = option
                                expandedFps = false
                            }
                        )
                    }
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
        
        // Status section (no header in Moblin, just centered text)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = when (liveDevice.state) {
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.IDLE -> "Not started"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.DISCOVERING -> "Discovering"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.CONNECTING -> "Connecting"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.PAIRING -> "Pairing"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.STOPPING_STREAM -> "Stopping stream"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.PREPARING_STREAM -> "Preparing to stream"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.SETTING_UP_WIFI -> "Setting up WiFi"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.WIFI_SETUP_FAILED -> "WiFi setup failed"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.CONFIGURING -> "Configuring"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.STARTING_STREAM -> "Starting stream"
                    com.dimadesu.djiremote.dji.SettingsDjiDeviceState.STREAMING -> "Streaming"
                    else -> "Unknown"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Start/Stop button section (no header in Moblin)
        if (!liveDevice.isStarted) {
            Button(
                onClick = {
                    // Save before starting
                    liveDevice.name = name
                    liveDevice.wifiSsid = ssid
                    liveDevice.wifiPassword = password
                    liveDevice.rtmpUrl = rtmpUrl
                    liveDevice.resolution = resolution
                    liveDevice.bitrate = bitrate
                    liveDevice.imageStabilization = imageStabilization
                    DjiRepository.updateDevice(liveDevice)
                    
                    com.dimadesu.djiremote.dji.DjiModel.startStreaming(context, liveDevice)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start live stream")
            }
        } else {
            Button(
                onClick = {
                    com.dimadesu.djiremote.dji.DjiModel.stopStreaming(liveDevice)
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
