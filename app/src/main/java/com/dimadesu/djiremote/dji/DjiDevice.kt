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
    private val mainHandler = Handler(Looper.getMainLooper())
    // Write queue
    private val writeChunkSize = 20 // Fixed 20-byte chunks like working Android reference
    private val writeQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var isWriting: Boolean = false
    private val writeIntervalMs: Long = 30L // pacing between writes for NO_RESPONSE
    
    // RX reassembly buffer for multi-chunk message reassembly
    private var rxBuffer = ByteArray(0)
    
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
        rxBuffer = ByteArray(0)
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
        
        // Send pair message via FFF5 (same path as all other messages)
        val pairPayload = DjiPairMessagePayload(PAIR_PIN_CODE).encode()
        val msg = DjiMessage(PAIR_TARGET, PAIR_TRANSACTION_ID, PAIR_TYPE, pairPayload)
        writeMessage(msg)
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
        
        // Use NOTIFY (0x0100) for ALL characteristics - DJI camera doesn't support INDICATE on Android
        val descriptorValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE // 0x01 00
        
        // Use new API for Android 13+ (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, descriptorValue)
            Log.d(TAG, "  Descriptor write initiated (new API): result=$result, value=0x0100 (NOTIFY)")
        } else {
            descriptor.value = descriptorValue
            val result = gatt.writeDescriptor(descriptor)
            Log.d(TAG, "  Descriptor write initiated (old API): result=$result, value=0x0100 (NOTIFY)")
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
                    gatt.requestMtu(128)
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
                        Log.d(TAG, "  Found FFF3 characteristic")
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
                    
                    // Enable notifications on RX characteristics only (FFF3, FFF4)
                    // FFF5 is TX (write-only) - do NOT enable notifications or write descriptor for it
                    for (c in service.characteristics) {
                        if (c.uuid == FFF5_UUID) {
                            Log.d(TAG, "    Skipping notifications for FFF5 (TX channel)")
                            continue
                        }
                        Log.d(TAG, "    Enabling notifications for: ${c.uuid}")
                        gatt.setCharacteristicNotification(c, true)
                        
                        // Queue all descriptors except FFF4 (added last to trigger pairing)
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
            if (descriptorWriteQueue.isEmpty()) {
                // If no descriptors to write, go straight to pairing
                Log.d(TAG, "No descriptors to write, going to pairing")
                setState(DjiDeviceState.CHECKING_IF_PAIRED)
                sendPairingMessage()
            } else {
                writeNextDescriptor()
            }
        }

        // Android 13+ (API 33 TIRAMISU) uses this new overload - the old one without value param is NOT called
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processIncomingData(characteristic, value)
        }

        // Pre-Android 13 uses this deprecated overload
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            processIncomingData(characteristic, value)
        }

        private fun processIncomingData(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isEmpty()) return
            
            Log.d(TAG, "RX: ${characteristic.uuid}, ${value.size} bytes: ${value.joinToString(" ") { "%02X".format(it) }}")
            
            // Continuation chunks (no 0x55 header) should be discarded per reference implementation
            // MTU 128 means responses fit in a single notification so multi-chunk RX shouldn't happen
            if (value[0] != 0x55.toByte()) {
                Log.d(TAG, "RX: Discarding non-0x55 continuation chunk")
                return
            }
            
            // Start fresh buffer with this message
            rxBuffer = value.copyOf()
            
            // Try to extract complete DJI messages from the buffer
            while (rxBuffer.size >= 2) {
                if (rxBuffer[0] != 0x55.toByte()) {
                    // Skip garbage byte
                    rxBuffer = rxBuffer.copyOfRange(1, rxBuffer.size)
                    continue
                }
                
                val messageLength = rxBuffer[1].toInt() and 0xFF
                if (messageLength < 11) {
                    // Invalid length, skip this byte
                    rxBuffer = rxBuffer.copyOfRange(1, rxBuffer.size)
                    continue
                }
                
                if (rxBuffer.size < messageLength) {
                    // Incomplete message, wait for more data
                    Log.d(TAG, "RX: Fragment ${rxBuffer.size}/$messageLength bytes, waiting...")
                    break
                }
                
                val completeMessage = rxBuffer.copyOfRange(0, messageLength)
                rxBuffer = rxBuffer.copyOfRange(messageLength, rxBuffer.size)
                
                try {
                    val message = DjiMessage.fromBytes(completeMessage)
                    Log.d(TAG, "RX decoded: target=0x${message.target.toString(16)}, id=0x${message.id.toString(16)}, type=0x${message.type.toString(16)}")
                    handleDecodedMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "RX: Corrupt message discarded: ${e.message}")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onCharacteristicWrite: characteristic=${characteristic.uuid}, status=$status")
        }
    }

    private fun handleDecodedMessage(message: DjiMessage) {
        Log.d(TAG, "handleDecodedMessage: state=$state, id=0x${message.id.toString(16)}")
        when (state) {
            DjiDeviceState.CHECKING_IF_PAIRED -> processCheckingIfPaired(message)
            DjiDeviceState.PAIRING -> processPairing()
            DjiDeviceState.CLEANING_UP -> processCleaningUp(message)
            DjiDeviceState.PREPARING_STREAM -> processPreparingStream(message)
            DjiDeviceState.SETTING_UP_WIFI -> processSettingUpWifi(message)
            DjiDeviceState.CONFIGURING -> processConfiguring(message)
            DjiDeviceState.STARTING_STREAM -> processStartingStream(message)
            DjiDeviceState.STREAMING -> processStreaming(message)
            DjiDeviceState.STOPPING_STREAM -> processStoppingStream(message)
            else -> {
                Log.d(TAG, "Message in state $state ignored")
            }
        }
    }

    fun writeMessage(message: DjiMessage) {
        Log.d(TAG, "writeMessage: target=0x${message.target.toString(16)}, type=0x${message.type.toString(16)}, id=0x${message.id.toString(16)}")
        val bytes = message.encode()
        Log.d(TAG, "  Encoded ${bytes.size} bytes: ${bytes.joinToString(" ") { "%02X".format(it) }}")
        
        // Write to FFF5 with WRITE_NO_RESPONSE (matching Moblin iOS and working Android reference)
        enqueueWrite(bytes)
    }

    private fun enqueueWrite(value: ByteArray) {
        // Split into fixed 20-byte chunks (matching working Android reference)
        var offset = 0
        var chunkCount = 0
        while (offset < value.size) {
            val end = minOf(offset + writeChunkSize, value.size)
            val chunk = value.copyOfRange(offset, end)
            writeQueue.addLast(chunk)
            chunkCount++
            offset = end
        }
        Log.d(TAG, "  Enqueued $chunkCount chunks (chunkSize=$writeChunkSize)")
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
        if (model.hasImageStabilization()) {
            val imageStab = imageStabilization ?: return
            val payload = DjiConfigureMessagePayload(imageStab, model.hasNewProtocol()).encode()
            val message = DjiMessage(CONFIGURE_TARGET, CONFIGURE_TRANSACTION_ID, CONFIGURE_TYPE, payload)
            writeMessage(message)
            setState(DjiDeviceState.CONFIGURING)
        } else {
            sendStartStreaming()
        }
    }

    private fun processConfiguring(response: DjiMessage) {
        if (response.id != CONFIGURE_TRANSACTION_ID) return
        sendStartStreaming()
    }

    private fun sendStartStreaming() {
        val rtmp = rtmpUrl ?: return
        val res = resolution ?: return
        val oa5 = model.hasNewProtocol()
        val payload = DjiStartStreamingMessagePayload(rtmp, res, fps, bitrateKbps, oa5).encode()
        val message = DjiMessage(START_STREAMING_TARGET, START_STREAMING_TRANSACTION_ID, START_STREAMING_TYPE, payload)
        writeMessage(message)

        // New protocol devices (OA5P, OA6, 360): send confirm-start payload
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
