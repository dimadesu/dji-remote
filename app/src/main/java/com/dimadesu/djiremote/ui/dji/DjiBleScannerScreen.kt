package com.dimadesu.djiremote.ui.dji

import android.content.Context
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

@Composable
fun DjiBleScannerScreen(onSelect: (String, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val discoveredState by DjiBleScanner.discovered.collectAsState()

    LaunchedEffect(Unit) {
        if (DjiBleScanner.hasPermissions(context)) {
            DjiBleScanner.startScanning(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select DJI device")
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(discoveredState) { (id, name) ->
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(id, name) }
                    .padding(8.dp)) {
                    Text(name, modifier = Modifier.weight(1f))
                    Text(id)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { DjiBleScanner.startScanning(context) }) { Text("Rescan") }
            Button(onClick = { DjiBleScanner.stopScanning(); onBack() }) { Text("Back") }
        }
    }
}
