package com.dimadesu.djiremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.ui.unit.dp
import com.dimadesu.djiremote.dji.SettingsDjiDevice
import com.dimadesu.djiremote.ui.dji.DjiDeviceSettingsScreen
import com.dimadesu.djiremote.ui.dji.DjiDevicesSettingsScreen
import com.dimadesu.djiremote.ui.dji.DjiBleScannerScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.dimadesu.djiremote.ui.theme.DJIRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DJIRemoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var screen by remember { mutableStateOf("home") }
                    var selectedDevice by remember { mutableStateOf<SettingsDjiDevice?>(null) }

                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (screen) {
                            "home" -> {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Greeting(name = "Android")
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { screen = "scanner" }) { 
                                        Text("Test BLE Scanner") 
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(onClick = { screen = "devices" }) { 
                                        Text("DJI Devices") 
                                    }
                                }
                            }
                            "scanner" -> {
                                DjiBleScannerScreen(
                                    onSelect = { address, name ->
                                        // For testing, just go back
                                        screen = "home"
                                    },
                                    onBack = { screen = "home" }
                                )
                            }
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
                                            // Store current device and go to scanner
                                            screen = "device-scanner"
                                        }
                                    )
                                }
                            }
                            "device-scanner" -> {
                                DjiBleScannerScreen(
                                    onSelect = { address, name ->
                                        // Update selected device and go back
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

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DJIRemoteTheme {
        Greeting("Android")
    }
}