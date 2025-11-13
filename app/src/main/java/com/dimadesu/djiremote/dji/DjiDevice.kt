package com.dimadesu.djiremote.dji

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

private const val TAG = "DjiDevice"

// UUIDs from the iOS implementation (16-bit) expanded to 128-bit base
val FFF4_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
val FFF5_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")

// Transaction ids / targets / types ported from the iOS implementation
private const val PAIR_TRANSACTION_ID = 0x8092
private const val STOP_STREAMING_TRANSACTION_ID = 0xEAC8
private const val PREPARING_TO_LIVESTREAM_TRANSACTION_ID = 0x8C12
private const val SETUP_WIFI_TRANSACTION_ID = 0x8C19
private const val START_STREAMING_TRANSACTION_ID = 0x8C2C
private const val CONFIGURE_TRANSACTION_ID = 0x8C2D

private const val PAIR_TARGET = 0x0702
private const val STOP_STREAMING_TARGET = 0x0802
private const val PREPARING_TO_LIVESTREAM_TARGET = 0x0802
private const val SETUP_WIFI_TARGET = 0x0702
private const val CONFIGURE_TARGET = 0x0102
private const val START_STREAMING_TARGET = 0x0802

private const val PAIR_TYPE = 0x450740
private const val STOP_STREAMING_TYPE = 0x8E0240
private const val PREPARING_TO_LIVESTREAM_TYPE = 0xE10240
private const val SETUP_WIFI_TYPE = 0x470740
private const val CONFIGURE_TYPE = 0x8E0240
private const val START_STREAMING_TYPE = 0x780840

private const val PAIR_PIN_CODE = "mbln"

enum class DjiDeviceState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CHECKING_IF_PAIRED,
    PAIRING,
    CLEANING_UP,
    PREPARING_STREAM,
    SETTING_UP_WIFI,
    WIFI_SETUP_FAILED,
    CONFIGURING,
    STARTING_STREAM,
    STREAMING,
    STOPPING_STREAM
}

interface DjiDeviceDelegate {
    fun djiDeviceStreamingState(device: DjiDevice, state: DjiDeviceState)
}

class DjiDevice(private val context: Context) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var fff5Characteristic: BluetoothGattCharacteristic? = null
    private var fff4Characteristic: BluetoothGattCharacteristic? = null
    private var fff3Characteristic: BluetoothGattCharacteristic? = null  // Add FFF3 reference
    private val mainHandler = Handler(Looper.getMainLooper())
    // MTU and write queue
    private var negotiatedMtu: Int = 23
    private val writePayloadOverhead = 3 // ATT header
    private val writeQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var isWriting: Boolean = false
    private val writeIntervalMs: Long = 30L // pacing between writes for NO_RESPONSE
    
    // Descriptor write queue
    private val descriptorWriteQueue: ArrayDeque<BluetoothGattDescriptor> = ArrayDeque()
    private var isWritingDescriptor: Boolean = false

    // state machine fields
    private var wifiSsid: String? = null
    private var wifiPassword: String? = null
    private var rtmpUrl: String? = null
    private var resolution: SettingsDjiDeviceResolution? = null
    private var fps: Int = 30
    private var bitrateKbps: Int = 4000
    private var imageStabilization: SettingsDjiDeviceImageStabilization? = null
    private var deviceAddress: String? = null
    private var model: SettingsDjiDeviceModel = SettingsDjiDeviceModel.UNKNOWN
    private var batteryPercentage: Int? = null

    private var state: DjiDeviceState = DjiDeviceState.IDLE
    var delegate: DjiDeviceDelegate? = null

    // timers (handler + runnable)
    private var startStreamingRunnable: Runnable? = null
    private var stopStreamingRunnable: Runnable? = null

    // Bonding receiver
    private var bondingReceiver: BroadcastReceiver? = null
    private var pendingBondAddress: String? = null

    fun startLiveStream(
        address: String,
        wifiSsid: String,
        wifiPassword: String,
        rtmpUrl: String,
        resolution: SettingsDjiDeviceResolution,
        fps: Int,
        bitrateKbps: Int,
        imageStabilization: SettingsDjiDeviceImageStabilization,
        model: SettingsDjiDeviceModel
    ) {
        Log.d(TAG, "startLiveStream: address=$address, model=$model")
        Log.d(TAG, "  WiFi: $wifiSsid")
        Log.d(TAG, "  RTMP: $rtmpUrl")
        Log.d(TAG, "  Resolution: $resolution, FPS: $fps, Bitrate: ${bitrateKbps}kbps")
        
        // configure
        this.wifiSsid = wifiSsid
        this.wifiPassword = wifiPassword
        this.rtmpUrl = rtmpUrl
        this.resolution = resolution
        this.fps = fps
        this.bitrateKbps = bitrateKbps
        this.imageStabilization = imageStabilization
        this.deviceAddress = address
        this.model = model

        reset()
        startStartStreamingTimer()
        setState(DjiDeviceState.DISCOVERING)
        Log.d(TAG, "Connecting to device at $address...")
        connectToAddress(address)
    }

    fun stopLiveStream() {
        if (state == DjiDeviceState.IDLE) return
        stopStartStreamingTimer()
        startStopStreamingTimer()
        sendStopStream()
        setState(DjiDeviceState.STOPPING_STREAM)
    }

    fun getBatteryPercentage(): Int? = batteryPercentage

    private fun reset() {
        stopStartStreamingTimer()
        stopStopStreamingTimer()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        fff5Characteristic = null
        batteryPercentage = null
        setState(DjiDeviceState.IDLE)
    }

    private fun startStartStreamingTimer() {
        stopStartStreamingTimer()
        startStreamingRunnable = Runnable { startStreamingTimerExpired() }
        mainHandler.postDelayed(startStreamingRunnable!!, 60_000)
    }

    private fun stopStartStreamingTimer() {
        startStreamingRunnable?.let { mainHandler.removeCallbacks(it) }
        startStreamingRunnable = null
    }

    private fun startStreamingTimerExpired() {
        reset()
    }

    private fun startStopStreamingTimer() {
        stopStopStreamingTimer()
        stopStreamingRunnable = Runnable { stopStreamingTimerExpired() }
        mainHandler.postDelayed(stopStreamingRunnable!!, 10_000)
    }

    private fun stopStopStreamingTimer() {
        stopStreamingRunnable?.let { mainHandler.removeCallbacks(it) }
        stopStreamingRunnable = null
    }

    private fun stopStreamingTimerExpired() {
        reset()
    }

    private fun setState(newState: DjiDeviceState) {
        if (newState == state) return
        Log.d(TAG, "State transition: $state -> $newState")
        state = newState
        delegate?.djiDeviceStreamingState(this, state)
    }

    fun getState(): DjiDeviceState = state

    fun connectToAddress(address: String) {
        Log.d(TAG, "connectToAddress: $address")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.e(TAG, "BluetoothAdapter is null!")
            return
        }
        val device = adapter.getRemoteDevice(address)
        Log.d(TAG, "Got remote device, bond state: ${device.bondState}")
        
        // NEW APPROACH: Skip OS-level bonding for DJI devices
        // They use application-level pairing via the "mbln" message
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.d(TAG, "✓ Device is already bonded, connecting...")
            }
            else -> {
                Log.d(TAG, "📱 Device not bonded, but DJI uses app-level pairing, connecting anyway...")
            }
        }
        
        // Connect directly without OS-level bonding
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        setState(DjiDeviceState.CONNECTING)
    }
    
    private fun sendPairingMessage() {
        Log.d(TAG, "========== SENDING PAIRING MESSAGE ==========")
        
        val gatt = bluetoothGatt ?: return
        
        // Send a wake-up/ping message first to see if camera responds
        mainHandler.post {
            Log.d(TAG, "📤 Sending minimal wake-up message to FFF3...")
            val service = gatt.services?.find { it.uuid.toString().startsWith("0000fff0") }
            val fff3 = service?.getCharacteristic(UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb"))
            if (fff3 != null) {
                // Minimal valid DJI message with empty payload
                val wakeupBytes = byteArrayOf(
                    0x55, // Magic
                    0x0B, // Length = 11 (minimum for message with empty payload)
                    0x04, // Version
                    0x00, // CRC8 placeholder
                    0x00, 0x00, // Target
                    0x00, 0x00, // ID  
                    0x00, 0x00, 0x00, // Type
                    0x00, 0x00 // CRC16 placeholder
                )
                // Calculate CRC8
                val crc8 = DjiCrc.computeCrc8(wakeupBytes.sliceArray(0..2))
                wakeupBytes[3] = crc8.toByte()
                // Calculate CRC16
                val crc16 = DjiCrc.computeCrc16(wakeupBytes.sliceArray(0 until wakeupBytes.size - 2))
                wakeupBytes[wakeupBytes.size - 2] = (crc16 and 0xFF).toByte()
                wakeupBytes[wakeupBytes.size - 1] = ((crc16 shr 8) and 0xFF).toByte()
                
                Log.d(TAG, "  Wake-up message: ${wakeupBytes.joinToString(" ") { "%02X".format(it) }}")
                fff3.value = wakeupBytes
                fff3.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // With response
                val result = gatt.writeCharacteristic(fff3)
                Log.d(TAG, "  FFF3 wake-up write result: $result")
            }
        }
        
        // Wait a bit longer before sending the actual pairing message
        mainHandler.postDelayed({
            Log.d(TAG, "📤 Now sending actual pairing message to FFF3...")
            val service = gatt.services?.find { it.uuid.toString().startsWith("0000fff0") }
            val fff3 = service?.getCharacteristic(UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb"))
            if (fff3 != null) {
                val pairPayload = DjiPairMessagePayload(PAIR_PIN_CODE).encode()
                val msg = DjiMessage(PAIR_TARGET, PAIR_TRANSACTION_ID, PAIR_TYPE, pairPayload)
                fff3.value = msg.encode()
                fff3.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // With response
                val result = gatt.writeCharacteristic(fff3)
                Log.d(TAG, "  FFF3 pairing write result: $result")
            }
        }, 1000)
        
        // Schedule periodic reads to poll for response
        for (delay in listOf(2000L, 3000L, 4000L, 5000L)) {
            mainHandler.postDelayed({
                Log.d(TAG, "📖 Polling FFF4 at ${delay}ms...")
                val service = gatt.services?.find { it.uuid.toString().startsWith("0000fff0") }
                val fff4 = service?.getCharacteristic(FFF4_UUID)
                if (fff4 != null) {
                    val result = gatt.readCharacteristic(fff4)
                    Log.d(TAG, "  FFF4 read result: $result")
                }
            }, delay)
        }
        
        // Also poll FFF3
        mainHandler.postDelayed({
            Log.d(TAG, "📖 Final read from FFF3...")
            val service = gatt.services?.find { it.uuid.toString().startsWith("0000fff0") }
            val fff3 = service?.getCharacteristic(UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb"))
            if (fff3 != null && (fff3.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                val result = gatt.readCharacteristic(fff3)
                Log.d(TAG, "  FFF3 read result: $result")
            }
        }, 6000)
        
        // Fallback: Assume pairing succeeded after timeout if no response
        mainHandler.postDelayed({
            if (state == DjiDeviceState.CHECKING_IF_PAIRED) {
                Log.w(TAG, "⚠️ No pairing response received, assuming paired and proceeding...")
                processPairing()
            }
        }, 10000)
    }
    
    private fun writeNextDescriptor() {
        if (isWritingDescriptor) return
        val descriptor = descriptorWriteQueue.removeFirstOrNull()
        if (descriptor == null) {
            Log.d(TAG, "writeNextDescriptor: queue empty")
            return
        }
        
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.e(TAG, "writeNextDescriptor: gatt is null!")
            return
        }
        
        Log.d(TAG, "Writing descriptor ${descriptor.characteristic.uuid}...")
        isWritingDescriptor = true
        
        // FFF3 uses INDICATE, FFF4 and FFF5 use NOTIFY
        val descriptorValue = if (descriptor.characteristic.uuid.toString() == "0000fff3-0000-1000-8000-00805f9b34fb") {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE  // 0x02 00 for FFF3
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE // 0x01 00 for FFF4/FFF5
        }
        
        val valueStr = if (descriptorValue.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
            "0x0200 (INDICATE)"
        } else {
            "0x0100 (NOTIFY)"
        }
        
        // Use new API for Android 13+ (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, descriptorValue)
            Log.d(TAG, "  Descriptor write initiated (new API): result=$result, value=$valueStr")
        } else {
            descriptor.value = descriptorValue
            val result = gatt.writeDescriptor(descriptor)
            Log.d(TAG, "  Descriptor write initiated (old API): result=$result, value=$valueStr")
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        setState(DjiDeviceState.IDLE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected! Requesting MTU...")
                // request a large MTU (max 517) then discover services; MTU change is async
                try {
                    gatt.requestMtu(517)
                } catch (e: Exception) {
                    Log.w(TAG, "MTU request failed, discovering services anyway")
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from device (status=$status)")
                reset()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            // Now discover services after MTU is set
            Log.d(TAG, "MTU negotiated, discovering services...")
            gatt.discoverServices()
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: descriptor=${descriptor.uuid}, status=$status, characteristic=${descriptor.characteristic.uuid}, state=$state")
            isWritingDescriptor = false
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Check if this was the FFF4 characteristic's notification descriptor
                if (descriptor.characteristic.uuid == FFF4_UUID && descriptorWriteQueue.isEmpty()) {
                    // Guard on state like Moblin does
                    if (state != DjiDeviceState.CONNECTING) {
                        Log.w(TAG, "FFF4 notification enabled but not in CONNECTING state, ignoring")
                        return
                    }
                    Log.d(TAG, "FFF4 notifications enabled in CONNECTING state")
                    setState(DjiDeviceState.CHECKING_IF_PAIRED)
                    sendPairingMessage()
                    
                } else if (!descriptorWriteQueue.isEmpty()) {
                    writeNextDescriptor()
                }
            } else {
                Log.e(TAG, "⚠️ Descriptor write failed with status $status")
                
                // Map error codes for debugging
                val errorMsg = when (status) {
                    0x05 -> "GATT_INSUFFICIENT_AUTHENTICATION"
                    0x0F -> "GATT_INSUFFICIENT_ENCRYPTION"
                    0x80 -> "GATT_NO_RESOURCES or GATT_INTERNAL_ERROR" 
                    0x85 -> "GATT_ERROR"
                    0x86 -> "GATT_NOT_SUPPORTED"
                    else -> "Unknown error"
                }
                Log.e(TAG, "  Error details: $errorMsg")
                
                // Continue with next descriptor or trigger pairing anyway
                if (!descriptorWriteQueue.isEmpty()) {
                    writeNextDescriptor()
                } else if (state == DjiDeviceState.CONNECTING) {
                    // All descriptors failed, try pairing anyway
                    Log.w(TAG, "⚠️ All descriptor writes failed, attempting pairing anyway")
                    setState(DjiDeviceState.CHECKING_IF_PAIRED)
                    sendPairingMessage()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed!")
                return
            }
            val serviceList = gatt.services
            Log.d(TAG, "Found ${serviceList.size} services")
            for (service in serviceList) {
                Log.d(TAG, "  Service: ${service.uuid}")
                val fff5 = service.getCharacteristic(FFF5_UUID)
                val fff4 = service.getCharacteristic(FFF4_UUID)
                val fff3 = service.getCharacteristic(UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb"))
                
                if (fff5 != null || fff4 != null || fff3 != null) {
                    Log.d(TAG, "Found DJI characteristics in service ${service.uuid}!")
                    
                    if (fff3 != null) {
                        Log.d(TAG, "  Found FFF3 characteristic!")
                        val properties = fff3.properties
                        Log.d(TAG, "  FFF3 properties: 0x${properties.toString(16)}")
                        Log.d(TAG, "    INDICATE: ${(properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0}")
                        fff3Characteristic = fff3
                    }
                    
                    if (fff5 != null) {
                        Log.d(TAG, "  Found FFF5 characteristic!")
                        val properties = fff5.properties
                        Log.d(TAG, "  FFF5 properties: 0x${properties.toString(16)}")
                        Log.d(TAG, "    WRITE_NO_RESPONSE: ${(properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0}")
                        Log.d(TAG, "    WRITE: ${(properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0}")
                        Log.d(TAG, "    NOTIFY: ${(properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0}")
                        Log.d(TAG, "    INDICATE: ${(properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0}")
                        fff5Characteristic = fff5
                    }
                    
                    if (fff4 != null) {
                        Log.d(TAG, "  Found FFF4 characteristic!")
                        val fff4Props = fff4.properties
                        Log.d(TAG, "  FFF4 properties: 0x${fff4Props.toString(16)}")
                        Log.d(TAG, "    NOTIFY: ${(fff4Props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0}")
                        Log.d(TAG, "    INDICATE: ${(fff4Props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0}")
                        Log.d(TAG, "    READ: ${(fff4Props and BluetoothGattCharacteristic.PROPERTY_READ) != 0}")
                        Log.d(TAG, "    WRITE: ${(fff4Props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0}")
                        Log.d(TAG, "    WRITE_NO_RESPONSE: ${(fff4Props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0}")
                        fff4Characteristic = fff4
                    }
                    
                    // Queue descriptor writes: FFF4 must be last (triggers pairing when enabled)
                    val fff4Descriptor = fff4?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    
                    // Enable notifications on all characteristics
                    for (c in service.characteristics) {
                        Log.d(TAG, "    Enabling notifications for: ${c.uuid}")
                        gatt.setCharacteristicNotification(c, true)
                        // Queue descriptor write - but skip FFF4 for now
                        if (c.uuid != FFF4_UUID) {
                            val descriptor = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            if (descriptor != null) {
                                descriptorWriteQueue.add(descriptor)
                            }
                        }
                    }
                    
                    // Add FFF4 descriptor last
                    if (fff4Descriptor != null) {
                        Log.d(TAG, "    Adding FFF4 descriptor last")
                        descriptorWriteQueue.add(fff4Descriptor)
                    }
                    break
                }
            }
            
            if (fff5Characteristic == null) {
                Log.e(TAG, "FFF5 characteristic not found!")
                return
            }
            
            // Start writing descriptors, pairing will happen when FFF4 is enabled
            Log.d(TAG, "Starting descriptor writes (${descriptorWriteQueue.size} queued)...")
            writeNextDescriptor()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value
            Log.d(TAG, "🔔 onCharacteristicChanged: characteristic=${characteristic.uuid}, bytes=${value?.size ?: 0}, state=$state")
            
            // CRITICAL: Log ANY data we receive, even single bytes
            if (value != null) {
                Log.d(TAG, "  📦 NOTIFICATION DATA (${value.size} bytes): ${value.joinToString(" ") { "%02X".format(it) }}")
                
                // Log which characteristic sent this
                when (characteristic.uuid) {
                    UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb") -> Log.d(TAG, "  ✅ From FFF3 - RESPONSE RECEIVED!")
                    FFF4_UUID -> Log.d(TAG, "  ✅ From FFF4 - RESPONSE RECEIVED!")
                    FFF5_UUID -> Log.d(TAG, "  ✅ From FFF5 - RESPONSE RECEIVED!")
                    else -> Log.d(TAG, "  From unknown characteristic")
                }
                
                // Check different patterns
                when {
                    value.size > 0 && value[0] == 0x55.toByte() -> {
                        Log.d(TAG, "  ✓ DJI message detected (starts with 0x55)")
                    }
                    value.size == 1 -> {
                        Log.d(TAG, "  Single byte response: 0x${"%02X".format(value[0])}")
                    }
                    value.size == 2 -> {
                        Log.d(TAG, "  Two byte response: 0x${"%02X".format(value[0])}${"%02X".format(value[1])}")
                    }
                    value.all { it in 32..126 } -> {
                        Log.d(TAG, "  ASCII text: ${String(value)}")
                    }
                    else -> {
                        Log.d(TAG, "  Unknown format")
                    }
                }
            }
            
            if (value == null || value.isEmpty()) {
                Log.d(TAG, "  Empty notification received")
                return
            }
            
            // Try to parse as DJI message if it starts with 0x55
            if (value.size > 0 && value[0] == 0x55.toByte()) {
                try {
                    val message = DjiMessage.fromBytes(value)
                    Log.d(TAG, "  Successfully decoded DJI message!")
                    Log.d(TAG, "    Target: 0x${message.target.toString(16)}")
                    Log.d(TAG, "    Type: 0x${message.type.toString(16)}")
                    Log.d(TAG, "    ID: 0x${message.id.toString(16)}")
                    Log.d(TAG, "    Payload (${message.payload.size} bytes): ${message.payload.take(20).joinToString(" ") { "%02X".format(it) }}${if(message.payload.size > 20) "..." else ""}")
                    
                    // Process message based on state
                    when (state) {
                        DjiDeviceState.CHECKING_IF_PAIRED -> {
                            Log.d(TAG, "  Processing in CHECKING_IF_PAIRED state")
                            processCheckingIfPaired(message)
                        }
                        DjiDeviceState.PAIRING -> processPairing()
                        DjiDeviceState.CLEANING_UP -> processCleaningUp(message)
                        DjiDeviceState.PREPARING_STREAM -> processPreparingStream(message)
                        DjiDeviceState.SETTING_UP_WIFI -> processSettingUpWifi(message)
                        DjiDeviceState.CONFIGURING -> processConfiguring(message)
                        DjiDeviceState.STARTING_STREAM -> processStartingStream(message)
                        DjiDeviceState.STREAMING -> processStreaming(message)
                        DjiDeviceState.STOPPING_STREAM -> processStoppingStream(message)
                        else -> {
                            Log.d(TAG, "  Unexpected message in state $state - processing anyway")
                            // Try processing as pairing response anyway
                            if (message.id == PAIR_TRANSACTION_ID) {
                                Log.d(TAG, "  This looks like a pairing response!")
                                processCheckingIfPaired(message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode as DJI message: ${e.message}")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onCharacteristicWrite: characteristic=${characteristic.uuid}, status=$status")
        }
        
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val value = characteristic.value
            Log.d(TAG, "📖 onCharacteristicRead: characteristic=${characteristic.uuid}, status=$status, bytes=${value?.size ?: 0}")
            if (value != null && value.isNotEmpty()) {
                Log.d(TAG, "  Read bytes: ${value.joinToString(" ") { "%02X".format(it) }}")
                // Check if it's a DJI message
                if (value.size > 0 && value[0] == 0x55.toByte()) {
                    Log.d(TAG, "  ⚡ This looks like a DJI message in the read buffer!")
                }
            } else {
                Log.d(TAG, "  Read returned empty/null")
            }
        }
    }

    fun writeMessage(message: DjiMessage) {
        Log.d(TAG, "writeMessage: target=0x${message.target.toString(16)}, type=0x${message.type.toString(16)}, id=0x${message.id.toString(16)}")
        val bytes = message.encode()
        Log.d(TAG, "  Encoded ${bytes.size} bytes: ${bytes.joinToString(" ") { "%02X".format(it) }}")
        
        // Try writing to FFF3 with WRITE (with response) for important messages
        val gatt = bluetoothGatt
        if (gatt != null && fff3Characteristic != null) {
            mainHandler.post {
                Log.d(TAG, "  Trying to write to FFF3 with response...")
                fff3Characteristic?.value = bytes
                fff3Characteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val result = gatt.writeCharacteristic(fff3Characteristic)
                Log.d(TAG, "  FFF3 write result: $result")
            }
        } else {
            // Fall back to FFF5 with WRITE_NO_RESPONSE
            enqueueWrite(bytes)
        }
    }

    private fun enqueueWrite(value: ByteArray) {
        // Split into chunks of (negotiatedMtu - overhead)
        val payloadSize = maxOf(1, negotiatedMtu - writePayloadOverhead)
        var offset = 0
        var chunkCount = 0
        while (offset < value.size) {
            val end = minOf(offset + payloadSize, value.size)
            val chunk = value.copyOfRange(offset, end)
            writeQueue.addLast(chunk)
            chunkCount++
            offset = end
        }
        Log.d(TAG, "  Enqueued $chunkCount chunks (payloadSize=$payloadSize)")
        startWriteLoopIfNeeded()
    }

    private fun startWriteLoopIfNeeded() {
        if (isWriting) return
        isWriting = true
        mainHandler.post { writeNextChunk() }
    }

    private fun writeNextChunk() {
        val chunk = writeQueue.removeFirstOrNull()
        if (chunk == null) {
            Log.d(TAG, "writeNextChunk: queue empty, stopping write loop")
            isWriting = false
            return
        }
        val char = fff5Characteristic ?: run {
            Log.e(TAG, "writeNextChunk: fff5Characteristic is null!")
            // clear queue if characteristic missing
            writeQueue.clear()
            isWriting = false
            return
        }
        Log.d(TAG, "writeNextChunk: writing ${chunk.size} bytes: ${chunk.joinToString(" ") { "%02X".format(it) }}")
        
        // Use new API for Android 13+ (API 33+)
        val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                char,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) ?: BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        } else {
            // Old API for older Android versions
            char.value = chunk
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (bluetoothGatt?.writeCharacteristic(char) == true) {
                BluetoothStatusCodes.SUCCESS
            } else {
                BluetoothGatt.GATT_FAILURE
            }
        }
        Log.d(TAG, "  Write result: $result")
        
        // schedule next chunk after a short delay to avoid saturating the controller
        mainHandler.postDelayed({ writeNextChunk() }, writeIntervalMs)
    }

    // MARK: - State machine handlers (ported from Swift)
    private fun sendStopStream() {
        val payload = DjiStopStreamingMessagePayload().encode()
        val message = DjiMessage(STOP_STREAMING_TARGET, STOP_STREAMING_TRANSACTION_ID, STOP_STREAMING_TYPE, payload)
        writeMessage(message)
    }

    private fun processCheckingIfPaired(response: DjiMessage) {
        Log.d(TAG, "processCheckingIfPaired: id=0x${response.id.toString(16)}, expecting=0x${PAIR_TRANSACTION_ID.toString(16)}")
        if (response.id != PAIR_TRANSACTION_ID) {
            Log.d(TAG, "  Not a pairing response, ignoring")
            return
        }
        Log.d(TAG, "  Pairing response payload: ${response.payload.joinToString(" ") { "%02X".format(it) }}")
        if (response.payload.contentEquals(byteArrayOf(0, 1))) {
            Log.d(TAG, "  Device reports paired successfully")
            processPairing()
        } else {
            Log.d(TAG, "  Device not paired, entering pairing state")
            setState(DjiDeviceState.PAIRING)
        }
    }

    private fun processPairing() {
        sendStopStream()
        setState(DjiDeviceState.CLEANING_UP)
    }

    private fun processCleaningUp(response: DjiMessage) {
        if (response.id != STOP_STREAMING_TRANSACTION_ID) return
        val payload = DjiPreparingToLivestreamMessagePayload().encode()
        val message = DjiMessage(PREPARING_TO_LIVESTREAM_TARGET, PREPARING_TO_LIVESTREAM_TRANSACTION_ID, PREPARING_TO_LIVESTREAM_TYPE, payload)
        writeMessage(message)
        setState(DjiDeviceState.PREPARING_STREAM)
    }

    private fun processPreparingStream(response: DjiMessage) {
        if (response.id != PREPARING_TO_LIVESTREAM_TRANSACTION_ID) return
        val ssid = wifiSsid ?: return
        val pwd = wifiPassword ?: return
        val payload = DjiSetupWifiMessagePayload(ssid, pwd).encode()
        val message = DjiMessage(SETUP_WIFI_TARGET, SETUP_WIFI_TRANSACTION_ID, SETUP_WIFI_TYPE, payload)
        writeMessage(message)
        setState(DjiDeviceState.SETTING_UP_WIFI)
    }

    private fun processSettingUpWifi(response: DjiMessage) {
        if (response.id != SETUP_WIFI_TRANSACTION_ID) return
        if (!response.payload.contentEquals(byteArrayOf(0x00, 0x00))) {
            reset()
            setState(DjiDeviceState.WIFI_SETUP_FAILED)
            return
        }
        when (model) {
            SettingsDjiDeviceModel.OSMO_ACTION_4 -> {
                val imageStab = imageStabilization ?: return
                val payload = DjiConfigureMessagePayload(imageStab, false).encode()
                val message = DjiMessage(CONFIGURE_TARGET, CONFIGURE_TRANSACTION_ID, CONFIGURE_TYPE, payload)
                writeMessage(message)
                setState(DjiDeviceState.CONFIGURING)
            }
            SettingsDjiDeviceModel.OSMO_ACTION_5_PRO -> {
                val imageStab = imageStabilization ?: return
                val payload = DjiConfigureMessagePayload(imageStab, true).encode()
                val message = DjiMessage(CONFIGURE_TARGET, CONFIGURE_TRANSACTION_ID, CONFIGURE_TYPE, payload)
                writeMessage(message)
                setState(DjiDeviceState.CONFIGURING)
            }
            else -> {
                sendStartStreaming()
            }
        }
    }

    private fun processConfiguring(response: DjiMessage) {
        if (response.id != CONFIGURE_TRANSACTION_ID) return
        sendStartStreaming()
    }

    private fun sendStartStreaming() {
        val rtmp = rtmpUrl ?: return
        val res = resolution ?: return
        val oa5 = model == SettingsDjiDeviceModel.OSMO_ACTION_5_PRO
        val payload = DjiStartStreamingMessagePayload(rtmp, res, fps, bitrateKbps, oa5).encode()
        val message = DjiMessage(START_STREAMING_TARGET, START_STREAMING_TRANSACTION_ID, START_STREAMING_TYPE, payload)
        writeMessage(message)

        // OA5P patch: send confirm payload
        if (oa5) {
            val confirmPayload = DjiConfirmStartStreamingMessagePayload().encode()
            val confirmMsg = DjiMessage(STOP_STREAMING_TARGET, STOP_STREAMING_TRANSACTION_ID, STOP_STREAMING_TYPE, confirmPayload)
            writeMessage(confirmMsg)
        }

        setState(DjiDeviceState.STARTING_STREAM)
    }

    private fun processStartingStream(response: DjiMessage) {
        if (response.id != START_STREAMING_TRANSACTION_ID) return
        setState(DjiDeviceState.STREAMING)
        stopStartStreamingTimer()
    }

    private fun processStreaming(message: DjiMessage) {
        when (message.type) {
            0x020D00 -> {
                if (message.payload.size >= 21) {
                    batteryPercentage = message.payload[20].toInt()
                }
            }
            else -> {
            }
        }
    }

    private fun processStoppingStream(response: DjiMessage) {
        // mirror Swift: when stop response arrives, go to idle
        if (response.id != STOP_STREAMING_TRANSACTION_ID) return
        reset()
    }
}
