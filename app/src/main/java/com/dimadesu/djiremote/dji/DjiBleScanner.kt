package com.dimadesu.djiremote.dji

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID
import android.bluetooth.le.ScanRecord
import android.util.SparseArray
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log

private const val TAG = "DjiBleScanner"

object DjiBleScanner {
    private val _discovered = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())
    val discovered: StateFlow<List<Triple<String, String, String>>> = _discovered

    private var scanning = false
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null
    private var filterOnlyDji: Boolean = true
    private val foundDevices = mutableListOf<Triple<String, String, String>>()

    fun hasPermissions(context: Context): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            // Even on Android S+, we still need location permission to get scan results!
            val locationGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "hasPermissions (S+): scan=$scanGranted, connect=$connectGranted, location=$locationGranted")
            scanGranted && connectGranted && locationGranted
        } else {
            // Older Android versions require location permission to scan
            val locationGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "hasPermissions (<S): location=$locationGranted")
            locationGranted
        }
        return result
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    @SuppressLint("MissingPermission")
    fun startScanning(context: Context, filterOnlyDji: Boolean = true) {
        Log.d(TAG, "startScanning called: filterOnlyDji=$filterOnlyDji, already scanning=$scanning")
        if (scanning) return
        this.filterOnlyDji = filterOnlyDji
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.e(TAG, "BluetoothAdapter is null")
            _scanError.value = "Bluetooth adapter not available"
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            _scanError.value = "Bluetooth is disabled"
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null")
            _scanError.value = "BLE scanner not available"
            return
        }
        
        // Clear previous results
        foundDevices.clear()
        _discovered.value = emptyList()
        _scanError.value = null
        Log.d(TAG, "Starting BLE scan...")
        
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                Log.d(TAG, "onScanResult called! callbackType=$callbackType, result=${result != null}")
                result?.device?.let { device ->
                    val scanRecord = result.scanRecord
                    val address = device.address ?: return
                    val name = device.name ?: address
                    
                    Log.d(TAG, "Found device: name=$name, address=$address")
                    
                    // Log manufacturer data for debugging
                    scanRecord?.manufacturerSpecificData?.let { mfgData ->
                        for (i in 0 until mfgData.size()) {
                            val key = mfgData.keyAt(i)
                            val data = mfgData.valueAt(i)
                            val hex = data.joinToString(" ") { "%02X".format(it) }
                            Log.d(TAG, "  Manufacturer data [key=$key]: $hex")
                        }
                    }
                    
                    if (this@DjiBleScanner.filterOnlyDji && !isDjiAdvertisement(scanRecord)) {
                        Log.d(TAG, "  Filtered out (not DJI)")
                        return
                    }
                    
                    val id = UUID.nameUUIDFromBytes(address.toByteArray(StandardCharsets.UTF_8)).toString()
                    synchronized(foundDevices) {
                        if (foundDevices.none { it.first == id }) {
                            Log.d(TAG, "  Adding to list")
                            foundDevices.add(Triple(id, address, name))
                            _discovered.value = foundDevices.toList()
                        }
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                Log.d(TAG, "onBatchScanResults called! count=${results?.size ?: 0}")
                results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                _scanError.value = "Scan failed: $errorCode"
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        Log.d(TAG, "Calling startScan with settings=${settings.scanMode}")
        try {
            scanner?.startScan(null, settings, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan", e)
            _scanError.value = "Scan error: ${e.message}"
            return
        }
        scanning = true
        Log.d(TAG, "Scan started successfully")

        // stop after 30s automatically
        handler.postDelayed({ stopScanning() }, 30_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        Log.d(TAG, "stopScanning called: scanning=$scanning")
        if (!scanning) return
        try {
            scanner?.stopScan(callback)
            Log.d(TAG, "Scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        callback = null
        scanner = null
        scanning = false
        _scanError.value = null
        foundDevices.clear()
        _discovered.value = emptyList()
    }

    private fun isDjiAdvertisement(record: ScanRecord?): Boolean {
        if (record == null) return false
        
        // DJI manufacturer ID is 0x08AA (2218 in decimal)
        // The manufacturer ID is stored as little-endian in the key
        val djiManufacturerId = 2218
        
        // Check if this manufacturer ID exists
        val data = record.getManufacturerSpecificData(djiManufacturerId)
        if (data != null) {
            Log.d(TAG, "  Found DJI manufacturer ID!")
            return true
        }
        
        // Also check old format: data starting with 0xAA 0x08
        val keys = record.manufacturerSpecificData?.let { (0 until it.size()).map { i -> it.keyAt(i) } } ?: emptyList()
        for (k in keys) {
            val mfgData = record.getManufacturerSpecificData(k)
            if (mfgData != null && mfgData.size >= 2 && mfgData[0] == 0xAA.toByte() && mfgData[1] == 0x08.toByte()) {
                Log.d(TAG, "  Found DJI manufacturer data pattern!")
                return true
            }
        }
        
        return false
    }
}
