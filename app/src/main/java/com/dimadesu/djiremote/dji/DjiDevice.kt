package com.dimadesu.djiremote.dji

import android.bluetooth.*
import android.content.Context
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
        Log.d(TAG, "Got remote device, calling connectGatt...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        setState(DjiDeviceState.CONNECTING)
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
        gatt.writeDescriptor(descriptor)
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
                Log.d(TAG, "Connected! Requesting MTU and discovering services...")
                // request a large MTU (max 517) then discover services; MTU change is async
                try {
                    gatt.requestMtu(517)
                } catch (e: Exception) {
                    // ignore if not supported
                }
                gatt.discoverServices()
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
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: descriptor=${descriptor.uuid}, status=$status")
            isWritingDescriptor = false
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Continue with next descriptor or start pairing check if done
                if (descriptorWriteQueue.isEmpty()) {
                    Log.d(TAG, "All descriptors written, starting pairing check...")
                    setState(DjiDeviceState.CHECKING_IF_PAIRED)
                    // Send pair check: in iOS this is implemented by expecting a response for pairTransactionId
                    val pairPayload = DjiPairMessagePayload(PAIR_PIN_CODE).encode()
                    val msg = DjiMessage(PAIR_TARGET, PAIR_TRANSACTION_ID, PAIR_TYPE, pairPayload)
                    writeMessage(msg)
                } else {
                    writeNextDescriptor()
                }
            } else {
                Log.e(TAG, "Descriptor write failed with status $status")
                // Try next one anyway
                writeNextDescriptor()
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
                val char = service.getCharacteristic(FFF5_UUID)
                if (char != null) {
                    Log.d(TAG, "Found FFF5 characteristic!")
                    fff5Characteristic = char
                    // enable notifications on all characteristics similar to iOS
                    for (c in service.characteristics) {
                        Log.d(TAG, "    Enabling notifications for: ${c.uuid}")
                        gatt.setCharacteristicNotification(c, true)
                        // Queue descriptor write to enable notifications
                        val descriptor = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            descriptorWriteQueue.add(descriptor)
                        }
                    }
                    break
                }
            }
            
            if (fff5Characteristic == null) {
                Log.e(TAG, "FFF5 characteristic not found!")
                return
            }
            
            // Start writing descriptors, pairing check will happen after all complete
            Log.d(TAG, "Starting descriptor writes (${descriptorWriteQueue.size} queued)...")
            writeNextDescriptor()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            try {
                val message = DjiMessage.fromBytes(value)
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
                        // ignore
                    }
                }
            } catch (e: Exception) {
                // ignore corrupt messages
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // We still pace writes via a handler; if platform provides acks for writes
            // we can optionally react here. For now we rely on pacing.
        }
    }

    fun writeMessage(message: DjiMessage) {
        val bytes = message.encode()
        enqueueWrite(bytes)
    }

    private fun enqueueWrite(value: ByteArray) {
        // Split into chunks of (negotiatedMtu - overhead)
        val payloadSize = maxOf(1, negotiatedMtu - writePayloadOverhead)
        var offset = 0
        while (offset < value.size) {
            val end = minOf(offset + payloadSize, value.size)
            val chunk = value.copyOfRange(offset, end)
            writeQueue.addLast(chunk)
            offset = end
        }
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
            isWriting = false
            return
        }
        val char = fff5Characteristic ?: run {
            // clear queue if characteristic missing
            writeQueue.clear()
            isWriting = false
            return
        }
        char.value = chunk
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        bluetoothGatt?.writeCharacteristic(char)
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
        if (response.id != PAIR_TRANSACTION_ID) return
        if (response.payload.contentEquals(byteArrayOf(0, 1))) {
            processPairing()
        } else {
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
