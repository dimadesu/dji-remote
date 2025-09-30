package com.dimadesu.djiremote.dji

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID

object DjiBleScanner {
    private val _discovered = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())
    val discovered: StateFlow<List<Triple<String, String, String>>> = _discovered

    private var scanning = false
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null

    fun hasPermissions(context: Context): Boolean {
        val scanGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val connectGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        return scanGranted && connectGranted
    }

    @SuppressLint("MissingPermission")
    fun startScanning(context: Context) {
        if (scanning) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return
    val found = mutableListOf<Triple<String, String, String>>()
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val address = device.address ?: return
                    val name = device.name ?: address
                    val id = UUID.nameUUIDFromBytes(address.toByteArray(StandardCharsets.UTF_8)).toString()
                    synchronized(found) {
                        if (found.none { it.first == id }) {
                            found.add(Triple(id, address, name))
                            _discovered.value = found.toList()
                        }
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                // ignore for now
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, settings, callback)
        scanning = true

        // stop after 30s automatically
        handler.postDelayed({ stopScanning() }, 30_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!scanning) return
        try {
            scanner?.stopScan(callback)
        } catch (e: Exception) {
            // ignore
        }
        callback = null
        scanner = null
        scanning = false
    }
}
