package dev.romestylez.pocketchat.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

// Type aliases for DJI Protocol
private typealias UInt16 = UShort
private typealias UInt32 = UInt

/**
 * DJI BLE Service - ECHTE Implementation basierend auf Moblin
 * Protokoll direkt aus: https://github.com/eerimoq/moblin
 * 
 * SINGLETON Pattern - Es gibt nur EINE Instanz pro App
 */
@SuppressLint("MissingPermission")
class DjiBleService private constructor(private val context: Context) {
    
    private var rxBuffer = ByteArray(0)

    companion object {
        private const val TAG = "DjiBleService"
        
        @Volatile
        private var INSTANCE: DjiBleService? = null
        
        fun getInstance(context: Context): DjiBleService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DjiBleService(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    // File Logger fuer DJI Debugging
    private fun getLogFile(): java.io.File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return java.io.File(dir, "dji_debug.txt")
    }

    private fun djiLog(msg: String, level: String = "D") {
        android.util.Log.d(TAG, msg)
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            getLogFile().appendText(ts + " " + msg + "\n")
        } catch (_: Exception) {}
    }

    fun getLogPath() = getLogFile().absolutePath

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    
    private var bluetoothGatt: BluetoothGatt? = null
    
    // DJI Service UUIDs - aus Moblin
    private val FFF4_UUID = UUID.fromString("0000FFF4-0000-1000-8000-00805F9B34FB")
    private val FFF5_UUID = UUID.fromString("0000FFF5-0000-1000-8000-00805F9B34FB")
    
    // DJI Manufacturer Data Company IDs
    private val DJI_COMPANY_ID = byteArrayOf(0xAA.toByte(), 0x08.toByte())
    private val XTRA_COMPANY_ID = byteArrayOf(0xAA.toByte(), 0xF7.toByte())
    // DJI Model bytes (bytes 2-3 of manufacturer data)
    private val MODEL_OSMO_ACTION_2   = byteArrayOf(0x10, 0x00)
    private val MODEL_OSMO_ACTION_3   = byteArrayOf(0x12, 0x00)
    private val MODEL_OSMO_ACTION_4   = byteArrayOf(0x14, 0x00)
    private val MODEL_OSMO_ACTION_5_PRO = byteArrayOf(0x15, 0x00)
    private val MODEL_OSMO_360        = byteArrayOf(0x17, 0x00)
    private val MODEL_OSMO_ACTION_6   = byteArrayOf(0x18, 0x00)
    private val MODEL_OSMO_POCKET_3   = byteArrayOf(0x20, 0x00)

    enum class DjiModel {
        OSMO_ACTION_2, OSMO_ACTION_3, OSMO_ACTION_4, OSMO_ACTION_5_PRO,
        OSMO_ACTION_6, OSMO_360, OSMO_POCKET_3, UNKNOWN;
        fun hasNewProtocol() = this == OSMO_ACTION_5_PRO || this == OSMO_ACTION_6 || this == OSMO_360
    }
    private var detectedModel: DjiModel = DjiModel.UNKNOWN

    private fun parseModel(mfr: ByteArray): DjiModel {
        if (mfr.size < 4) return DjiModel.UNKNOWN
        val m = mfr.copyOfRange(2, 4)
        return when {
            m.contentEquals(MODEL_OSMO_ACTION_2)    -> DjiModel.OSMO_ACTION_2
            m.contentEquals(MODEL_OSMO_ACTION_3)    -> DjiModel.OSMO_ACTION_3
            m.contentEquals(MODEL_OSMO_ACTION_4)    -> DjiModel.OSMO_ACTION_4
            m.contentEquals(MODEL_OSMO_ACTION_5_PRO)-> DjiModel.OSMO_ACTION_5_PRO
            m.contentEquals(MODEL_OSMO_360)         -> DjiModel.OSMO_360
            m.contentEquals(MODEL_OSMO_ACTION_6)    -> DjiModel.OSMO_ACTION_6
            m.contentEquals(MODEL_OSMO_POCKET_3)    -> DjiModel.OSMO_POCKET_3
            else -> DjiModel.UNKNOWN
        }
    }
    private fun isDjiDevice(mfr: ByteArray): Boolean {
        if (mfr.size < 2) return false
        val c = mfr.copyOfRange(0, 2)
        return c.contentEquals(DJI_COMPANY_ID) || c.contentEquals(XTRA_COMPANY_ID)
    }

    // Configure (neues Protokoll: Action5Pro/Action6/360)
    private val CONFIGURE_TRANSACTION_ID: UInt16 = 0x8C2Du
    private val CONFIGURE_TARGET: UInt16          = 0x0102u
    private val CONFIGURE_TYPE: UInt32            = 0x8E0240u

    // Transaction IDs - aus Moblin
    private val PAIR_TRANSACTION_ID: UInt16 = 0x8092u
    private val STOP_STREAMING_TRANSACTION_ID: UInt16 = 0xEAC8u
    private val PREPARING_TO_LIVESTREAM_TRANSACTION_ID: UInt16 = 0x8C12u
    private val SETUP_WIFI_TRANSACTION_ID: UInt16 = 0x8C19u
    private val START_STREAMING_TRANSACTION_ID: UInt16 = 0x8C2Cu
    
    // Targets - aus Moblin
    private val PAIR_TARGET: UInt16 = 0x0702u
    private val STOP_STREAMING_TARGET: UInt16 = 0x0802u
    private val PREPARING_TO_LIVESTREAM_TARGET: UInt16 = 0x0802u
    private val SETUP_WIFI_TARGET: UInt16 = 0x0702u
    private val START_STREAMING_TARGET: UInt16 = 0x0802u
    
    // Types - aus Moblin
    private val PAIR_TYPE: UInt32 = 0x450740u
    private val STOP_STREAMING_TYPE: UInt32 = 0x8E0240u
    private val PREPARING_TO_LIVESTREAM_TYPE: UInt32 = 0xE10240u
    private val SETUP_WIFI_TYPE: UInt32 = 0x470740u
    private val START_STREAMING_TYPE: UInt32 = 0x780840u
    
    private val PAIR_PIN_CODE = "mbln"
    
    // State Flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel
    
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming
    
    private var fff5Characteristic: BluetoothGattCharacteristic? = null
    private var deviceState = DeviceState.IDLE
    private var connectedDeviceName: String? = null  // Speichere Kamera-Name
    private var manualStop = false  // Flag: User hat manuell gestoppt
    var autoStreamEnabled = false     // Flag: Automatisch streamen nach Verbindung

    // Auto-Reconnect nach unerwartetem Disconnect (Kamera aus/ein)
    private var autoReconnectEnabled = false
    private var preparingConfirmed = false  // True after camera confirms PREPARING_TO_LIVESTREAM   // Wird aktiviert sobald Stream lief
    private var reconnectAttempt = 0           // Aktueller Versuch (0-3)
    private val maxReconnectAttempts = 3       // Max 3 Versuche
    private var reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    
    // RX Buffer for multi-chunk message reassembly
    private val fragmentBuffers = mutableMapOf<Int, ByteArray>() // msgId -> accumulated bytes
    
    // Descriptor write queue - BLE allows only one operation at a time
    private val descriptorQueue = mutableListOf<BluetoothGattDescriptor>()
    private var isWritingDescriptor = false
    private var fff4WasWritten = false  // Track if FFF4 descriptor was successfully written
    
    // Config
    private var wifiSsid: String? = null
    private var wifiPassword: String? = null
    private var rtmpUrl: String? = null
    
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Scanning : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    enum class DeviceState {
        IDLE,
        DISCOVERING,
        CONNECTING,
        CHECKING_IF_PAIRED,
        PAIRING,
        CLEANING_UP,
        PREPARING_STREAM,
        SETTING_UP_WIFI,
        CONFIGURING,
        STARTING_STREAM,
        STREAMING,
        STOPPING_STREAM
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (deviceState != DeviceState.IDLE) return

            val device = result.device
            val deviceName = device.name ?: ""

            // Primär: Manufacturer Data direkt mit bekannten DJI Company IDs abfragen
            val scanRecord = result.scanRecord
            val isDji = run {
                val djiIds = listOf(0x08AA, 0xF7AA)
                var found = false
                for (companyId in djiIds) {
                    val data = scanRecord?.getManufacturerSpecificData(companyId)
                    if (data != null) {
                        val idLo = (companyId and 0xFF).toByte()
                        val idHi = ((companyId shr 8) and 0xFF).toByte()
                        val fullMfr = byteArrayOf(idLo, idHi) + data
                        detectedModel = parseModel(fullMfr)
                        djiLog("DJI erkannt per Manufacturer Data: model=$detectedModel")
                        found = true
                        break
                    }
                }
                found
            }

            // Fallback: Namensbasiert
            val isDjiByName = !isDji && (
                deviceName.contains("OSMO", ignoreCase = true) ||
                deviceName.contains("POCKET", ignoreCase = true) ||
                deviceName.contains("ACTION", ignoreCase = true))
            if (isDjiByName) {
                detectedModel = DjiModel.UNKNOWN
                djiLog("DJI erkannt per Name: $deviceName (Modell unbekannt)")
            }

            if (isDji || isDjiByName) {
                djiLog("DJI Kamera gefunden: $deviceName model=$detectedModel")
                deviceState = DeviceState.CONNECTING
                stopScan()
                connectToDevice(device)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            djiLog("Scan failed: $errorCode")
            _connectionState.value = ConnectionState.Error("Scan fehlgeschlagen")
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDeviceName = gatt.device.name ?: "DJI Kamera"
                    djiLog("Verbunden mit $connectedDeviceName")
                    _connectionState.value = ConnectionState.Connected(connectedDeviceName!!)
                    deviceState = DeviceState.CONNECTING
                    // MTU erhöhen damit lange Pakete (z.B. 0x020D00 Battery = 47 bytes) in einem Chunk kommen
                    djiLog("Requesting MTU 128...")
                    gatt.requestMtu(128)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    djiLog("Getrennt")
                    _connectionState.value = ConnectionState.Disconnected

                    if (manualStop) {
                        // Manueller Stop → normaler Reconnect nach 2 Sekunden
                        djiLog("### DISCONNECTED after manual stop - reconnecting in 2s...")
                        cleanup()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (manualStop) {
                                djiLog("### Starting reconnect scan...")
                                startScan()
                            }
                        }, 2000)
                    } else if (autoReconnectEnabled && reconnectAttempt < maxReconnectAttempts) {
                        // Unerwarteter Disconnect (Kamera aus/ein) → Auto-Reconnect
                        cleanup()
                        scheduleAutoReconnect()
                    } else {
                        cleanup()
                    }
                }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            djiLog("MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                djiLog("Services discovered")
                
                // Reset flags
                fff4WasWritten = false
                
                // Collect all descriptors first
                descriptorQueue.clear()
                gatt.services?.forEach { service ->
                    service.characteristics?.forEach { characteristic ->
                        if (characteristic.uuid == FFF5_UUID) {
                            fff5Characteristic = characteristic
                            // FFF5 is TX - don't enable notifications for it!
                            return@forEach
                        }
                        // Enable notifications locally for RX characteristics only
                        gatt.setCharacteristicNotification(characteristic, true)
                        
                        // Add descriptors to queue
                        characteristic.descriptors?.forEach { descriptor ->
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            descriptorQueue.add(descriptor)
                        }
                    }
                }
                
                djiLog("Queued ${descriptorQueue.size} descriptors to write")
                // Start writing descriptors sequentially
                writeNextDescriptor()
            }
        }
        
        private fun writeNextDescriptor() {
            if (isWritingDescriptor || descriptorQueue.isEmpty()) {
                if (descriptorQueue.isEmpty()) {
                    djiLog("All descriptors written successfully")
                }
                return
            }
            
            val descriptor = descriptorQueue.removeAt(0)
            isWritingDescriptor = true
            
            djiLog("Writing descriptor UUID=${descriptor.uuid} for characteristic: ${descriptor.characteristic.uuid}")
            bluetoothGatt?.writeDescriptor(descriptor)
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isWritingDescriptor = false
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                djiLog("Descriptor written successfully: ${descriptor.characteristic.uuid}")
                
                // Remember if this was FFF4
                if (descriptor.characteristic.uuid == FFF4_UUID) {
                    fff4WasWritten = true
                }
            } else {
                djiLog("Descriptor write failed: status=$status (ignoring - Android BLE bug after reconnect)")
                // Don't fail! Android often returns status=128 after reconnect
                // Mark FFF4 as written anyway if it was this characteristic
                if (descriptor.characteristic.uuid == FFF4_UUID) {
                    fff4WasWritten = true
                }
            }
            
            // Continue writing remaining descriptors
            writeNextDescriptor()
            
            djiLog("Checking pairing trigger: fff4Written=$fff4WasWritten, queueEmpty=${descriptorQueue.isEmpty()}, isWriting=$isWritingDescriptor, state=$deviceState")
            
            // Send pair message after FFF4 is done AND no more descriptors are being written
            if (fff4WasWritten && descriptorQueue.isEmpty() && !isWritingDescriptor && deviceState == DeviceState.CONNECTING) {
                djiLog("All notifications enabled - sending pair message immediately")
                sendPairMessage()
                deviceState = DeviceState.CHECKING_IF_PAIRED
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            djiLog("RX: onCharacteristicChanged uuid=${characteristic.uuid}, ${value.size} bytes: ${value.toHexString()}")
            
            // Pakete >20 bytes:
            // Mit erhöhter MTU (517) kommen diese als vollständiger Chunk an → normal verarbeiten.
            // Folge-Chunks (kein 0x55) können trotzdem noch vorkommen → verwerfen.
            if (value.isNotEmpty() && value[0] != 0x55.toByte()) {
                return
            }
            
            rxBuffer += value
            
            while (rxBuffer.size >= 2) {
                if (rxBuffer[0] != 0x55.toByte()) {
                    rxBuffer = rxBuffer.copyOfRange(1, rxBuffer.size)
                    continue
                }
                
                val messageLength = rxBuffer[1].toInt() and 0xFF
                if (messageLength < 11) {
                    rxBuffer = rxBuffer.copyOfRange(1, rxBuffer.size)
                    continue
                }
                
                if (rxBuffer.size < messageLength) {
                    djiLog("RX: Fragment ${rxBuffer.size}/${messageLength}B, warte...")
                    break
                }
                
                val completeMessage = rxBuffer.copyOfRange(0, messageLength)
                rxBuffer = rxBuffer.copyOfRange(messageLength, rxBuffer.size)
                
                try {
                    val message = DjiMessage.decode(completeMessage)
                    handleMessage(message)
                } catch (e: Exception) {
                    djiLog("RX: Corrupt message verworfen: ${e.message}")
                }
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWritingCharacteristic = false
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                djiLog("Characteristic write SUCCESS: uuid=${characteristic.uuid}")
                
                // Continue writing remaining chunks
                writeNextChunk()
            } else {
                djiLog("Characteristic write FAILED: uuid=${characteristic.uuid}, status=$status")
                writeQueue.clear() // Clear queue on error
            }
        }
    }
    
    fun startScan() {

        djiLog("### START SCAN")
        
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            djiLog("### SCAN FAILED - No scanner available!")
            _connectionState.value = ConnectionState.Error("Bluetooth nicht verfügbar")
            return
        }
        
        _connectionState.value = ConnectionState.Scanning
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        djiLog("### Starting BLE scan...")
        scanner.startScan(emptyList(), scanSettings, scanCallback)
        
        djiLog("Suche nach DJI Kameras...")
    }
    
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }
    
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }
    
    /**
     * WiFi konfigurieren und Stream starten (kompletter Flow)
     */
    fun startLivestream(ssid: String, password: String, rtmpUrl: String) {
        this.wifiSsid = ssid
        this.wifiPassword = password
        this.rtmpUrl = rtmpUrl
        
        djiLog("Starting livestream: SSID=$ssid, URL=$rtmpUrl, State=$deviceState")
        
        when (deviceState) {
            DeviceState.IDLE -> {
                // Nicht verbunden - muss erst scannen/verbinden
                djiLog("Not connected - need to scan first")
                _connectionState.value = ConnectionState.Error("Bitte erst mit Kamera verbinden")
            }
            DeviceState.CONNECTING, DeviceState.CHECKING_IF_PAIRED, DeviceState.PAIRING -> {
                // Verbindung läuft - Credentials gespeichert, Flow startet nach Pairing automatisch
                djiLog("Connection in progress - will start after pairing")
            }
            else -> {
                // Bereits verbunden/gepairt - Cleanup und neu starten
                djiLog("Already connected - restarting stream flow")
                sendStopStreamMessage()
                deviceState = DeviceState.CLEANING_UP
            }
        }
    }
    
    /**
     * Stream stoppen
     */
    fun stopLivestream() {
        if (deviceState != DeviceState.IDLE) {
            sendStopStreamMessage()
            deviceState = DeviceState.STOPPING_STREAM
        }
    }
    
    /**
     * Message Handler - State Machine wie in Moblin
     */
    private fun handleMessage(message: DjiMessage) {
        djiLog("RX: ${message.format()}")
        
        when (deviceState) {
            DeviceState.CHECKING_IF_PAIRED -> {
                if (message.id.toUShort() == PAIR_TRANSACTION_ID) {
                    if (message.payload.contentEquals(byteArrayOf(0x00, 0x01))) {
                        // Already paired
                        processPairing()
                    } else {
                        deviceState = DeviceState.PAIRING
                    }
                }
            }
            DeviceState.PAIRING -> {
                processPairing()
            }
            DeviceState.CLEANING_UP -> {
                if (message.id.toUShort() == STOP_STREAMING_TRANSACTION_ID) {
                    sendPreparingToLivestreamMessage()
                    deviceState = DeviceState.PREPARING_STREAM
                    
                    // Wenn manualStop gesetzt ist, Button sofort aktivieren!
                    if (manualStop) {
                        djiLog("*** PREPARING_STREAM reached after manual stop - ready for restart")
                        // WICHTIG: Im Main Thread setzen für UI-Update!
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            _isStreaming.value = false
                            djiLog("*** isStreaming set to false on main thread")
                        }
                    }
                }
            }
            DeviceState.PREPARING_STREAM -> {
                if (message.id.toUShort() == PREPARING_TO_LIVESTREAM_TRANSACTION_ID) {
                    // Camera is ready - now auto-start stream if configured
                    preparingConfirmed = true
                    djiLog("Camera ready for WiFi/RTMP configuration")
                    // KEIN State-Update hier - Name bleibt erhalten!
                    
                    // ✅ AUTO-START: Wenn WiFi/RTMP konfiguriert sind UND kein manueller Stop, automatisch senden
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        djiLog("*** AUTO-START HANDLER - State: $deviceState, manualStop: $manualStop")
                        
                        // WICHTIG: Checke State nochmal - könnte inzwischen manuell gestartet sein!
                        if (deviceState == DeviceState.IDLE || deviceState == DeviceState.CONNECTING || deviceState == DeviceState.PAIRING) {
                            djiLog("*** AUTO-START SKIPPED - Still connecting (state: $deviceState)")
                            // Kamera noch nicht bereit - State wird später erreicht
                        } else if (deviceState != DeviceState.PREPARING_STREAM) {
                            djiLog("*** AUTO-START SKIPPED - State changed to $deviceState")
                        } else if (manualStop) {
                            djiLog("*** AUTO-START SKIPPED - manualStop=true, ready for manual start")
                            manualStop = false  // Reset für nächstes Mal
                            _isStreaming.value = false  // ✅ Button aktivieren!
                            // State bleibt PREPARING_STREAM für manuellen Start!
                        } else if (autoStreamEnabled && !wifiSsid.isNullOrBlank() && !wifiPassword.isNullOrBlank() && !rtmpUrl.isNullOrBlank()) {
                            djiLog("*** AUTO-START GO - Sending WiFi setup")
                            sendSetupWifiMessage(wifiSsid!!, wifiPassword!!)
                            deviceState = DeviceState.SETTING_UP_WIFI
                        } else {
                            djiLog("*** AUTO-START BLOCKED - WiFi/RTMP not configured")
                        }
                    }, 3000)  // 3 Sekunden warten - Kamera braucht Zeit!
                }
            }
            DeviceState.SETTING_UP_WIFI -> {
                djiLog("@@@ SETTING_UP_WIFI - Received message ID: 0x${message.id.toUShort().toString(16)}, expected: 0x${SETUP_WIFI_TRANSACTION_ID.toString(16)}")
                if (message.id.toUShort() == SETUP_WIFI_TRANSACTION_ID) {
                    djiLog("@@@ WiFi Response - Payload: ${message.payload.joinToString(" ") { "%02X".format(it) }}")
                    val wifiSuccess = message.payload.size >= 2 && message.payload[0] == 0x00.toByte()
                    djiLog("@@@ WiFi Response payload[0]=${message.payload.getOrNull(0)?.let { "%02X".format(it) }} success=$wifiSuccess")
                    if (wifiSuccess) {
                        djiLog("@@@ WiFi SUCCESS - model=$detectedModel")
                        when (detectedModel) {
                            DjiModel.OSMO_ACTION_5_PRO, DjiModel.OSMO_ACTION_6, DjiModel.OSMO_360 -> {
                                // Neues Protokoll: Configure mit oa5=true, dann Start
                                sendConfigureMessage()
                                deviceState = DeviceState.CONFIGURING
                            }
                            else -> {
                                // Action 4, 3, 2, Pocket3: direkt Start (bewährt)
                                sendStartStreamingMessage()
                                deviceState = DeviceState.STARTING_STREAM
                            }
                        }
                    } else {
                        djiLog("@@@ WiFi FAILED - Full payload: ${message.payload.joinToString(" ") { "%02X".format(it) }}")
                        _connectionState.value = ConnectionState.Error("WiFi Setup fehlgeschlagen")
                        deviceState = DeviceState.PREPARING_STREAM  // nicht cleanup, nur zurück
                    }
                }
            }
            DeviceState.CONFIGURING -> {
                if (message.id.toUShort() == CONFIGURE_TRANSACTION_ID) {
                    djiLog("Configure OK - starting stream")
                    sendStartStreamingMessage()
                    deviceState = DeviceState.STARTING_STREAM
                }
            }
            DeviceState.STARTING_STREAM -> {
                val msgId = message.id.toUShort()
                djiLog("STARTING_STREAM: RX id=0x${msgId.toString(16)}, payload=${message.payload.toHexString()}")
                if (msgId == START_STREAMING_TRANSACTION_ID) {
                    // 0x8C2C Response erhalten → jetzt Confirm senden (nur bei neuen Modellen)
                    if (detectedModel.hasNewProtocol()) {
                        djiLog("0x8C2C Response → sende Confirm (0xEAC8)")
                        val confirmPayload = byteArrayOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x01)
                        val confirmMsg = DjiMessage.create(
                            target = STOP_STREAMING_TARGET,
                            id = STOP_STREAMING_TRANSACTION_ID,
                            type = STOP_STREAMING_TYPE,
                            payload = confirmPayload
                        )
                        writeMessage(confirmMsg)
                    } else {
                        // Altes Protokoll: 0x8C2C direkt = Stream läuft
                        deviceState = DeviceState.STREAMING
                        _isStreaming.value = true
                        autoReconnectEnabled = true
                        reconnectAttempt = 0
                        djiLog("Stream gestartet! (via 0x8C2C, altes Protokoll)")
                    }
                } else if (msgId == STOP_STREAMING_TRANSACTION_ID) {
                    // 0xEAC8 Response = Confirm bestätigt → Stream läuft
                    deviceState = DeviceState.STREAMING
                    _isStreaming.value = true
                    autoReconnectEnabled = true
                    reconnectAttempt = 0
                    djiLog("Stream gestartet! (via 0xEAC8 Confirm)")
                }
            }
            DeviceState.STREAMING -> {
                // Battery: type 0x020D00, payload[20] (Action 4 + Action 6)
                if (message.type == 0x020D00u && message.payload.size >= 21) {
                    val battery = message.payload[20].toInt() and 0xFF
                    _batteryLevel.value = battery
                    djiLog("Battery: $battery%")
                }
            }
            DeviceState.STOPPING_STREAM -> {
                if (message.id.toUShort() == STOP_STREAMING_TRANSACTION_ID) {
                    djiLog("I", "Stop confirmed by camera")
                    onStreamStopped()
                }
            }
            else -> {
                djiLog("Message in state $deviceState ignored")
            }
        }
    }
    
    private fun processPairing() {
        preparingConfirmed = false
        sendStopStreamMessage()
        deviceState = DeviceState.CLEANING_UP
    }
    
    /**
     * Pair Message - aus Moblin
     */
    private fun sendPairMessage() {
        val payload = buildPairPayload(PAIR_PIN_CODE)
        val message = DjiMessage.create(
            target = PAIR_TARGET,
            id = PAIR_TRANSACTION_ID,
            type = PAIR_TYPE,
            payload = payload
        )
        writeMessage(message)
    }
    
    /**
     * Stop Stream Message
     */
    private fun sendStopStreamMessage() {
        val payload = byteArrayOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x02)
        val message = DjiMessage.create(
            target = STOP_STREAMING_TARGET,
            id = STOP_STREAMING_TRANSACTION_ID,
            type = STOP_STREAMING_TYPE,
            payload = payload
        )
        writeMessage(message)
    }
    
    /**
     * Preparing to Livestream Message
     */
    private fun sendPreparingToLivestreamMessage() {
        val payload = byteArrayOf(0x1A)
        val message = DjiMessage.create(
            target = PREPARING_TO_LIVESTREAM_TARGET,
            id = PREPARING_TO_LIVESTREAM_TRANSACTION_ID,
            type = PREPARING_TO_LIVESTREAM_TYPE,
            payload = payload
        )
        writeMessage(message)
    }
    
    /**
     * WiFi Setup Message
     */
    private fun sendSetupWifiMessage(ssid: String, password: String) {
        val payload = buildWifiPayload(ssid, password)
        val message = DjiMessage.create(
            target = SETUP_WIFI_TARGET,
            id = SETUP_WIFI_TRANSACTION_ID,
            type = SETUP_WIFI_TYPE,
            payload = payload
        )
        writeMessage(message)
    }
    
    /**
     * Start Streaming Message
     */
    private fun sendConfigureMessage() {
        // DjiConfigureMessagePayload für Action4 (oa5=false) und Action5Pro/6/360 (oa5=true)
        // Stabisierung ist immer "off" (0x00) da wir es nicht konfigurieren
        val isOa5 = detectedModel.hasNewProtocol()
        val payload = buildConfigurePayload(isOa5)
        val message = DjiMessage.create(
            target = CONFIGURE_TARGET,
            id = CONFIGURE_TRANSACTION_ID,
            type = CONFIGURE_TYPE,
            payload = payload
        )
        writeMessage(message)
    }

    private fun sendStartStreamingMessage() {
        rtmpUrl?.let { url ->
            val payload = buildStartStreamingPayload(url, detectedModel.hasNewProtocol())
            val message = DjiMessage.create(
                target = START_STREAMING_TARGET,
                id = START_STREAMING_TRANSACTION_ID,
                type = START_STREAMING_TYPE,
                payload = payload
            )
            writeMessage(message)
            // Confirm wird erst nach 0x8C2C-Response gesendet (im STARTING_STREAM Handler)
        }
    }
    
    /**
     * Pair Payload Builder
     */
    private fun buildPairPayload(pinCode: String): ByteArray {
        val staticPart = byteArrayOf(
            0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
            0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
            0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
            0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
            0x33
        )
        val pinBytes = packString(pinCode)
        return staticPart + pinBytes
    }
    
    /**
     * WiFi Payload Builder
     */
    private fun buildWifiPayload(ssid: String, password: String): ByteArray {
        val ssidPacked = packString(ssid)
        val pwdPacked = packString(password)
        val result = ssidPacked + pwdPacked
        
        djiLog("### WiFi Payload Debug:")
        djiLog("    SSID: '$ssid' (${ssid.length} chars)")
        djiLog("    SSID bytes: ${ssidPacked.joinToString(" ") { "%02X".format(it) }}")
        djiLog("    PWD: '${password.take(3)}***' (${password.length} chars)")  
        djiLog("    PWD bytes: ${pwdPacked.joinToString(" ") { "%02X".format(it) }}")
        djiLog("    Total payload: ${result.joinToString(" ") { "%02X".format(it) }}")
        
        return result
    }
    
    /**
     * Start Streaming Payload Builder
     */
    /**
     * Start Streaming Payload
     * oa5=false (OA2/OA3/OA4/Pocket3): Byte1 = 0x2E
     * oa5=true  (OA5Pro/OA6/360):      Byte1 = 0x2A
     */
    private fun buildStartStreamingPayload(url: String, oa5: Boolean = false): ByteArray {
        val resolution: Byte = 0x0A  // 1080p
        val fps: Byte = 0x03         // 30fps
        val bitrateKbps: Short = 6000

        val result = ByteArrayList()
        result.add(0x00)
        result.add(if (oa5) 0x2A else 0x2E)
        result.add(0x00)
        result.add(resolution)
        result.addShortLE(bitrateKbps)
        result.add(0x02)
        result.add(0x00)
        result.add(fps)
        result.add(0x00)
        result.add(0x00)
        result.add(0x00)
        result.addAll(packUrl(url).toList())
        return result.toByteArray()
    }

    /**
     * Configure Payload (nach WiFi-Setup bei OA4/OA5Pro/OA6/360)
     * Stabisierung: immer OFF (0x00)
     * oa5=false (OA4): andere Bytes
     * oa5=true  (OA5Pro/OA6/360): neuere Struktur
     */
    /**
     * Configure Payload - aus Moblin DjiDeviceMessage.swift
     * payload1=[0x01,0x01] + byte1 + payload2=[0x00,0x01] + imageStabilization(0=off)
     * oa5=true  (OA5Pro/OA6/360): byte1 = 0x1A
     * oa5=false (OA4):            byte1 = 0x08
     */
    private fun buildConfigurePayload(oa5: Boolean): ByteArray {
        val byte1: Byte = if (oa5) 0x1A else 0x08
        val imageStabilization: Byte = 0x00  // off
        return byteArrayOf(0x01, 0x01, byte1, 0x00, 0x01, imageStabilization)
    }
    
    /**
     * Pack String - DJI Format
     */
    private fun packString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(bytes.size.toByte()) + bytes
    }
    
    /**
     * Pack URL - DJI Format
     */
    private fun packUrl(url: String): ByteArray {
        val bytes = url.toByteArray(Charsets.UTF_8)
        return byteArrayOf(bytes.size.toByte(), 0x00) + bytes
    }
    
    /**
     * Write Message to BLE
     */
    // Write queue for message chunks
    private val writeQueue = mutableListOf<ByteArray>()
    private var isWritingCharacteristic = false
    
    private fun writeMessage(message: DjiMessage) {
        djiLog("TX: ${message.format()}")
        val data = message.encode()
        djiLog("TX BYTES (${data.size}): ${data.toHexString()}")
        
        // Split into 20-byte chunks
        val chunkSize = 20
        writeQueue.clear()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            writeQueue.add(chunk)
            offset = end
        }
        
        djiLog("Queued ${writeQueue.size} chunks to send")
        writeNextChunk()
    }
    
    private fun writeNextChunk() {
        if (isWritingCharacteristic || writeQueue.isEmpty()) return
        
        val chunk = writeQueue.removeAt(0)
        isWritingCharacteristic = true
        
        djiLog("Sending chunk ${chunk.toHexString()}")
        fff5Characteristic?.let { char ->
            bluetoothGatt?.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
    }
    
    fun requestBatteryStatus() {
        // Battery updates kommen automatisch während Streaming
        djiLog("Battery wird automatisch während Streaming gesendet")
    }
    
    fun disconnect() {
        djiLog("### DISCONNECT - closing GATT connection")
        stopScan()
        bluetoothGatt?.disconnect()  // ✅ Triggert STATE_DISCONNECTED Event!
        // cleanup() wird in onConnectionStateChange aufgerufen!
    }
    
    /**
     * Configure WiFi and RTMP settings (call this before startStream)
     */
    fun configure(ssid: String, password: String, rtmpUrl: String) {
        this.wifiSsid = ssid
        this.wifiPassword = password
        this.rtmpUrl = rtmpUrl
    }
    
    fun configureStream(ssid: String, password: String, rtmpUrl: String) {
        wifiSsid = ssid
        wifiPassword = password
        this.rtmpUrl = rtmpUrl
        djiLog("Stream configured: WiFi=$ssid, RTMP=$rtmpUrl")
    }
    
    /**
     * Start streaming with configured settings
     */
    fun startStream() {
        manualStop = false  // ✅ SOFORT resetten - User will streamen!
        
        djiLog(">>> USER CLICKED START - State: $deviceState, manualStop: $manualStop")
        
        // Stream kann nur in PREPARING_STREAM State gestartet werden
        if (deviceState != DeviceState.PREPARING_STREAM) {
            djiLog(">>> START BLOCKED - Wrong state: $deviceState")
            _connectionState.value = ConnectionState.Error("Warte bis Kamera bereit ist")
            return
        }
        
        val ssid = wifiSsid
        val pwd = wifiPassword
        val url = rtmpUrl
        
        if (ssid == null || pwd == null || url == null) {
            djiLog(">>> START BLOCKED - WiFi/RTMP not configured!")
            _connectionState.value = ConnectionState.Error("Bitte WiFi und RTMP konfigurieren")
            return
        }
        
        if (!preparingConfirmed) {
            djiLog(">>> START DELAYED - Waiting for camera preparing confirmation")
            _connectionState.value = ConnectionState.Error("Kamera noch nicht bereit, kurz warten...")
            // Retry after 2 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (deviceState == DeviceState.PREPARING_STREAM && preparingConfirmed) {
                    djiLog(">>> DELAYED START GO - Camera now confirmed ready")
                    sendSetupWifiMessage(wifiSsid ?: return@postDelayed, wifiPassword ?: return@postDelayed)
                    deviceState = DeviceState.SETTING_UP_WIFI
                } else {
                    djiLog(">>> DELAYED START FAILED - State=$deviceState preparingConfirmed=$preparingConfirmed")
                }
            }, 2000)
            return
        }
        
        djiLog(">>> START OK - Sending WiFi setup...")
        sendSetupWifiMessage(ssid, pwd)
        deviceState = DeviceState.SETTING_UP_WIFI
        
        // Timeout: Wenn nach 10 Sekunden keine Response → zurück zu PREPARING_STREAM
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (deviceState == DeviceState.SETTING_UP_WIFI) {
                djiLog(">>> TIMEOUT - WiFi setup took too long, retrying possible")
                deviceState = DeviceState.PREPARING_STREAM
                _connectionState.value = ConnectionState.Error("WiFi Setup Timeout - nochmal versuchen")
            }
        }, 10000)
    }
    
    fun stopStream() {
        djiLog("<<< USER CLICKED STOP - State: $deviceState")

        if (deviceState == DeviceState.STREAMING) {
            autoReconnectEnabled = false  // Manueller Stop → kein Auto-Reconnect
            reconnectAttempt = 0
            cancelAutoReconnect()
            sendStopStreamMessage()
            deviceState = DeviceState.STOPPING_STREAM

            djiLog("<<< STOP OK - Disconnecting for clean reconnect...")
            manualStop = true  // Trigger normalen Reconnect in onConnectionStateChange

            disconnect()
        } else {
            djiLog("<<< STOP BLOCKED - Not streaming (state: $deviceState)")
        }
    }
    
    private fun onStreamStopped() {
        // Nach Stop: Bereit für neuen Stream
        deviceState = DeviceState.PREPARING_STREAM
        _isStreaming.value = false
        djiLog("I", "Stream stopped, ready to start again (state: PREPARING_STREAM)")
    }
    
    /**
     * Auto-Reconnect Logik:
     * - Versuch 1: nach 10 Sekunden
     * - Versuch 2: nach weiteren 5 Sekunden
     * - Versuch 3: nach weiteren 5 Sekunden
     * - Danach: aufgeben, User muss manuell verbinden
     */
    private fun scheduleAutoReconnect() {
        reconnectAttempt++
        val delayMs = if (reconnectAttempt == 1) 10_000L else 5_000L

        djiLog("### AUTO-RECONNECT Versuch $reconnectAttempt/$maxReconnectAttempts in ${delayMs/1000}s...")
        _connectionState.value = ConnectionState.Scanning  // UI: "Suche Kamera..."

        val runnable = Runnable {
            if (!autoReconnectEnabled) return@Runnable
            djiLog("### AUTO-RECONNECT startet Scan (Versuch $reconnectAttempt)...")
            startScan()

            // Timeout: wenn nach 15s nicht verbunden → nächsten Versuch oder aufgeben
            reconnectHandler.postDelayed({
                if (_connectionState.value !is ConnectionState.Connected
                    && _connectionState.value !is ConnectionState.Disconnected) {
                    // Noch am Scannen aber nichts gefunden
                    djiLog("### AUTO-RECONNECT Versuch $reconnectAttempt Timeout")
                    stopScan()
                    cleanup()
                    if (reconnectAttempt < maxReconnectAttempts) {
                        scheduleAutoReconnect()
                    } else {
                        djiLog("### AUTO-RECONNECT aufgegeben nach $maxReconnectAttempts Versuchen")
                        autoReconnectEnabled = false
                        reconnectAttempt = 0
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }, 15_000L)
        }

        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
        djiLog("Auto-Reconnect abgebrochen")
    }

    private fun cleanup() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        fff5Characteristic = null
        deviceState = DeviceState.IDLE
        connectedDeviceName = null
        _isStreaming.value = false
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString(" ") { "%02X".format(it) }
    }
}

/**
 * Helper Extensions
 */
private fun UInt16.toInt() = this.toInt()
private fun UInt32.toInt() = this.toInt()

private class ByteArrayList : ArrayList<Byte>() {
    fun addShortLE(value: Short) {
        add((value.toInt() and 0xFF).toByte())
        add(((value.toInt() shr 8) and 0xFF).toByte())
    }
}

/**
 * DJI Message - Protocol Implementation
 */
private data class DjiMessage(
    val target: UInt16,
    val id: UInt16,
    val type: UInt32,
    val payload: ByteArray
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(13 + payload.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        
        // Header
        buffer.put(0x55.toByte())
        buffer.put((13 + payload.size).toByte())
        buffer.put(0x04.toByte())
        
        // CRC8 für Header
        val headerCrc = calculateCrc8(buffer.array(), 0, 3)
        buffer.put(headerCrc)
        
        // Message
        buffer.putShort(target.toShort())
        buffer.putShort(id.toShort())
        
        // Type (24-bit little-endian)
        buffer.put((type.toInt() and 0xFF).toByte())
        buffer.put(((type.toInt() shr 8) and 0xFF).toByte())
        buffer.put(((type.toInt() shr 16) and 0xFF).toByte())
        
        // Payload
        buffer.put(payload)
        
        // CRC16 über alles außer CRC selbst
        val crc16 = calculateCrc16(buffer.array(), 0, buffer.position())
        buffer.putShort(crc16.toShort())
        
        // Return only the bytes we actually wrote
        return buffer.array().copyOf(buffer.position())
    }
    
    fun format(): String {
        return "target=0x${target.toString(16)}, id=0x${id.toString(16)}, type=0x${type.toString(16)}, payload=${payload.joinToString(" ") { "%02X".format(it) }}"
    }
    
    companion object {
        fun create(target: UInt16, id: UInt16, type: UInt32, payload: ByteArray): DjiMessage {
            return DjiMessage(target, id, type, payload)
        }
        
        fun decode(data: ByteArray): DjiMessage {
            if (data.size < 13) throw IllegalArgumentException("Message too short")
            
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            
            // Header
            if (buffer.get() != 0x55.toByte()) throw IllegalArgumentException("Bad first byte")
            val length = buffer.get().toInt() and 0xFF
            if (data.size != length) throw IllegalArgumentException("Bad length")
            if (buffer.get() != 0x04.toByte()) throw IllegalArgumentException("Bad version")
            
            val headerCrc = buffer.get()
            val calculatedHeaderCrc = calculateCrc8(data, 0, 3)
            if (headerCrc != calculatedHeaderCrc) {
                throw IllegalArgumentException("Bad header CRC")
            }
            
            // Message
            val target = buffer.short.toUShort()
            val id = buffer.short.toUShort()
            
            // Type (24-bit)
            val type = ((buffer.get().toInt() and 0xFF) or
                       ((buffer.get().toInt() and 0xFF) shl 8) or
                       ((buffer.get().toInt() and 0xFF) shl 16)).toUInt()
            
            // Payload
            val payloadSize = length - 13
            val payload = ByteArray(payloadSize)
            buffer.get(payload)
            
            // CRC16
            val crc16 = buffer.short
            val calculatedCrc16 = calculateCrc16(data, 0, data.size - 2)
            if (crc16 != calculatedCrc16.toShort()) {
                throw IllegalArgumentException("Bad CRC16")
            }
            
            return DjiMessage(target, id, type, payload)
        }
        
        /**
         * CRC8 - DJI specific
         * Initial: 0xEE, Poly: 0x31, RefIn: true, RefOut: true
         */
        private fun calculateCrc8(data: ByteArray, offset: Int, length: Int): Byte {
            var crc = 0xEE
            
            for (i in offset until offset + length) {
                var byte = data[i].toInt() and 0xFF
                byte = reverseBits8(byte)
                crc = crc xor byte
                
                for (j in 0 until 8) {
                    if ((crc and 0x80) != 0) {
                        crc = (crc shl 1) xor 0x31
                    } else {
                        crc = crc shl 1
                    }
                }
            }
            
            crc = reverseBits8(crc)
            return (crc and 0xFF).toByte()
        }
        
        private fun validateCrc16(data: ByteArray): Boolean {
            if (data.size < 2) return false
            val expected = calculateCrc16(data, 0, data.size - 2)
            val actual = ((data[data.size - 1].toInt() and 0xFF) shl 8) or (data[data.size - 2].toInt() and 0xFF)
            return expected == actual
        }
        
        /**
         * CRC16 - DJI specific
         * Initial: 0x496C, Poly: 0x1021, RefIn: true, RefOut: true
         */
        internal fun calculateCrc16(data: ByteArray, offset: Int, length: Int): Int {
            var crc = 0x496C
            
            for (i in offset until offset + length) {
                var byte = data[i].toInt() and 0xFF
                byte = reverseBits8(byte)
                crc = crc xor (byte shl 8)
                
                for (j in 0 until 8) {
                    if ((crc and 0x8000) != 0) {
                        crc = (crc shl 1) xor 0x1021
                    } else {
                        crc = crc shl 1
                    }
                }
            }
            
            crc = reverseBits16(crc)
            return crc and 0xFFFF
        }
        
        private fun reverseBits8(value: Int): Int {
            var v = value
            var result = 0
            for (i in 0 until 8) {
                result = (result shl 1) or (v and 1)
                v = v shr 1
            }
            return result
        }
        
        private fun reverseBits16(value: Int): Int {
            var v = value
            var result = 0
            for (i in 0 until 16) {
                result = (result shl 1) or (v and 1)
                v = v shr 1
            }
            return result
        }
    }
}
