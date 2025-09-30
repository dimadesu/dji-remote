package com.dimadesu.djiremote.dji

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

// UUIDs from the iOS implementation (16-bit) expanded to 128-bit base
val FFF4_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
val FFF5_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")

class DjiDevice(private val context: Context) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var fff5Characteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connectToAddress(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val device = adapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val serviceList = gatt.services
            for (service in serviceList) {
                val char = service.getCharacteristic(FFF5_UUID)
                if (char != null) {
                    fff5Characteristic = char
                    // enable notifications on all characteristics similar to iOS
                    for (c in service.characteristics) {
                        gatt.setCharacteristicNotification(c, true)
                    }
                    break
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            // Attempt to decode message
            try {
                val msg = DjiMessage.fromBytes(value)
                // TODO: forward to state machine handler
            } catch (e: Exception) {
                // ignore
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // write-without-response may not trigger this; handle confirmations elsewhere if needed
        }
    }

    fun writeMessage(message: DjiMessage) {
        val bytes = message.encode()
        writeValue(bytes)
    }

    private fun writeValue(value: ByteArray) {
        val char = fff5Characteristic ?: return
        // TODO: handle MTU and chunking for large writes
        char.value = value
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        bluetoothGatt?.writeCharacteristic(char)
    }
}
