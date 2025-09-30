package com.dimadesu.djiremote.ui.dji

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dimadesu.djiremote.dji.DjiBleScanner
import android.os.Build

@Composable
fun DjiBleScannerScreen(onSelect: (String, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val discoveredState by DjiBleScanner.discovered.collectAsState()
    val scanError by DjiBleScanner.scanError.collectAsState(initial = null)
    val isBtEnabled = DjiBleScanner.isBluetoothEnabled(context)
    var showAllDevices by remember { mutableStateOf(false) }

    val hasPermissions = DjiBleScanner.hasPermissions(context)

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            val granted = perms.values.all { it }
            if (granted) {
                DjiBleScanner.startScanning(context)
            }
        }
    )

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            DjiBleScanner.startScanning(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select DJI device")
        Spacer(modifier = Modifier.height(8.dp))

        if (!hasPermissions) {
            Text("This feature requires Bluetooth permissions to scan for DJI devices.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissionsLauncher.launch(arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ))
                } else {
                    permissionsLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ))
                }
            }) { Text("Grant Bluetooth permissions") }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { onBack() }) { Text("Back") }
            Spacer(modifier = Modifier.height(8.dp))
            return@Column
        }

        if (!isBtEnabled) {
            Text("Bluetooth is disabled on this device. Please enable Bluetooth and try again.")
            Spacer(modifier = Modifier.height(8.dp))
        }

        scanError?.let { err ->
            Text("Scan error: $err")
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Show all devices")
            Spacer(modifier = Modifier.width(8.dp))
            androidx.compose.material3.Switch(checked = showAllDevices, onCheckedChange = { showAllDevices = it })
        }
        Spacer(modifier = Modifier.height(8.dp))

        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(discoveredState) { (id, address, name) ->
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(address, name) }
                    .padding(8.dp)) {
                    Text(name, modifier = Modifier.weight(1f))
                    Text(address)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { DjiBleScanner.startScanning(context, !showAllDevices) }) { Text("Rescan") }
            Button(onClick = { DjiBleScanner.stopScanning(); onBack() }) { Text("Back") }
        }
    }
}
