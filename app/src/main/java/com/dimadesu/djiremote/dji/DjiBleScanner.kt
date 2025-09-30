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

    fun hasPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            scanGranted && connectGranted
        } else {
            // Older Android versions require location permission to scan
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    @SuppressLint("MissingPermission")
    fun startScanning(context: Context, filterOnlyDji: Boolean = true) {
        if (scanning) return
        this.filterOnlyDji = filterOnlyDji
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            _scanError.value = "Bluetooth is disabled"
            return
        }
        scanner = adapter.bluetoothLeScanner ?: return
    val found = mutableListOf<Triple<String, String, String>>()
        _scanError.value = null
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val scanRecord = result.scanRecord
                    if (this@DjiBleScanner.filterOnlyDji && !isDjiAdvertisement(scanRecord)) return
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
                _scanError.value = "Scan failed: $errorCode"
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
        _scanError.value = null
        _discovered.value = emptyList()
    }

    private fun isDjiAdvertisement(record: ScanRecord?): Boolean {
        if (record == null) return false
        // manufacturer specific data can be fetched via getManufacturerSpecificData
        val mfg = record.getManufacturerSpecificData(0x00)
        // fall back: iterate all manufacturer entries
        val keys = record.manufacturerSpecificData?.let { (0 until it.size()).map { i -> it.keyAt(i) } } ?: emptyList()
        for (k in keys) {
            val data = record.getManufacturerSpecificData(k)
            if (data != null && data.size >= 2 && data[0] == 0xAA.toByte() && data[1] == 0x08.toByte()) return true
        }
        return false
    }
}
