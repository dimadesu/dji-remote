package com.dimadesu.djiremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dimadesu.djiremote.dji.SettingsDjiDevice
import com.dimadesu.djiremote.ui.dji.DjiDeviceSettingsScreen
import com.dimadesu.djiremote.ui.dji.DjiDevicesSettingsScreen
import com.dimadesu.djiremote.ui.dji.DjiBleScannerScreen
import com.dimadesu.djiremote.ui.theme.DJIRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize repository with context
        com.dimadesu.djiremote.dji.DjiRepository.initialize(this)

        enableEdgeToEdge()
        setContent {
            DJIRemoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var screen by remember { mutableStateOf("devices") }
                    var selectedDevice by remember { mutableStateOf<SettingsDjiDevice?>(null) }

                    BackHandler(enabled = screen != "devices") {
                        screen = when (screen) {
                            "device" -> "devices"
                            "device-scanner" -> "device"
                            else -> "devices"
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (screen) {
                            "devices" -> {
                                DjiDevicesSettingsScreen(onOpenDevice = {
                                    selectedDevice = it
                                    screen = "device"
                                })
                            }
                            "device" -> {
                                selectedDevice?.let { d ->
                                    DjiDeviceSettingsScreen(
                                        device = d,
                                        onBack = { screen = "devices" },
                                        onOpenScanner = {
                                            screen = "device-scanner"
                                        }
                                    )
                                }
                            }
                            "device-scanner" -> {
                                DjiBleScannerScreen(
                                    onSelect = { address, name ->
                                        selectedDevice?.let { d ->
                                            d.bluetoothPeripheralAddress = address
                                            d.bluetoothPeripheralName = name
                                            com.dimadesu.djiremote.dji.DjiRepository.updateDevice(d)
                                        }
                                        screen = "device"
                                    },
                                    onBack = { screen = "device" }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}