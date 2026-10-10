package com.syrak.scooterlab.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Thread-safe BLE transport for scooter controllers.
 *
 * Responsibilities:
 *  - Discovery with service-UUID filters (or unfiltered brand sweep).
 *  - Connection lifecycle with strict timeouts.
 *  - GATT bring-up: service discovery, notification subscription, MTU negotiation.
 *  - A serialized, MTU-aware write pump (BLE permits only one outstanding op).
 *  - A hot stream of inbound frames for the protocol layer.
 *
 * All public entry points are safe to call from any dispatcher. GATT callbacks are
 * pinned to the main looper for deterministic ordering.
 */
@SuppressLint("MissingPermission")
class BleManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /* ---------------------------------- State --------------------------------- */

    private val _scanResults = MutableStateFlow<List<DiscoveredScooter>>(emptyList())
    val scanResults: StateFlow<List<DiscoveredScooter>> = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _gattEvents = MutableSharedFlow<GattEvent>(extraBufferCapacity = 128)
    val gattEvents: SharedFlow<GattEvent> = _gattEvents.asSharedFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    /* -------------------------------- Internals ------------------------------- */

    private val discovered = ConcurrentHashMap<String, DiscoveredScooter>()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private var currentMtu: Int = BleConstants.DEFAULT_MTU
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBrand: ScooterBrand = ScooterBrand.UNKNOWN

    private var readyDeferred: CompletableDeferred<Unit>? = null
    private var writeDeferred: CompletableDeferred<Unit>? = null

    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var writerJob: Job? = null

    private var scanning = false

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF) {
                    Timber.w("Bluetooth adapter powered off — tearing down session")
                    _gattEvents.tryEmit(GattEvent.Warning("Bluetooth turned off"))
                    close()
                }
            }
        }
    }.also {
        appContext.registerReceiver(it, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    /* --------------------------------- Scanning ------------------------------- */

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun startScan(filtered: Boolean = true) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) {
            _gattEvents.tryEmit(GattEvent.Error("Bluetooth is disabled or unavailable"))
            _connectionState.value = ConnectionState.Failed(null, "Bluetooth disabled")
            return
        }
        if (scanning) return

        discovered.clear()
        _scanResults.value = emptyList()
        _connectionState.value = ConnectionState.Scanning
        scanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        val filters: List<ScanFilter> =
            if (filtered) BleConstants.SCAN_SERVICE_FILTERS.map { uuid ->
                ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
            } else emptyList()

        try {
            scanner.startScan(filters, settings, scanCallback)
            Timber.i("BLE scan started (filtered=%s)", filtered)
        } catch (e: SecurityException) {
            scanning = false
            _gattEvents.tryEmit(GattEvent.Error("Scan permission denied", e))
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Timber.w(e, "stopScan denied")
        }
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = ingest(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::ingest)
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _gattEvents.tryEmit(GattEvent.Error("Scan failed (code $errorCode)"))
            _connectionState.value = ConnectionState.Failed(null, "Scan failed: $errorCode")
        }
    }

    private fun ingest(result: ScanResult) {
        val device = result.device ?: return
        val name = try { device.name } catch (e: SecurityException) { null }
        val brand = ScooterBrand.classify(name)

        // When unfiltered, drop devices that clearly aren't scooters to keep the list clean.
        val looksLikeScooter = brand != ScooterBrand.UNKNOWN ||
            name?.let { n -> BleConstants.NAME_PREFIXES.any { n.contains(it, ignoreCase = true) } } == true
        if (brand == ScooterBrand.UNKNOWN && !looksLikeScooter) return

        discovered[device.address] = DiscoveredScooter(
            address = device.address,
            name = name,
            rssi = result.rssi,
            brand = brand,
            lastSeenMs = System.currentTimeMillis(),
        )
        _scanResults.value = discovered.values.sortedByDescending { it.rssi }
    }

    /* -------------------------------- Connection ------------------------------ */

    /**
     * Connect, discover services, subscribe to notifications and negotiate MTU.
     * Suspends until the link is fully operational or the timeout elapses.
     */
    suspend fun connect(scooter: DiscoveredScooter): Result<Unit> {
        val localAdapter = adapter ?: return Result.failure(IOException("No Bluetooth adapter"))
        if (!localAdapter.isEnabled) return Result.failure(IOException("Bluetooth disabled"))

        stopScan()
        close()

        val device: BluetoothDevice = try {
            localAdapter.getRemoteDevice(scooter.address)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        currentAddress = scooter.address
        currentName = scooter.name
        currentBrand = scooter.brand
        _connectionState.value = ConnectionState.Connecting(scooter.address)

        val deferred = CompletableDeferred<Unit>()
        readyDeferred = deferred

        val g = try {
            device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                mainHandler,
            )
        } catch (e: SecurityException) {
            return Result.failure(e)
        }

        if (g == null) {
            _connectionState.value = ConnectionState.Failed(scooter.address, "connectGatt returned null")
            return Result.failure(IOException("connectGatt failed"))
        }
        gatt = g

        return try {
            withTimeout(BleConstants.CONNECT_TIMEOUT_MS) { deferred.await() }
            startWritePump()
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Connect handshake failed")
            _gattEvents.tryEmit(GattEvent.Error("Connection failed: ${e.message}", e))
            _connectionState.value = ConnectionState.Failed(scooter.address, e.message ?: "timeout")
            close()
            Result.failure(e)
        }
    }

    /** Enqueue a fully-formed logical frame; the pump chunks it to the current MTU. */
    suspend fun write(frame: ByteArray) {
        writeChannel.send(frame)
    }

    fun disconnect() {
        close()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun close() {
        writerJob?.cancel()
        writerJob = null
        readyDeferred?.cancel()
        readyDeferred = null
        writeDeferred = null
        writeChar = null
        notifyChar = null
        currentMtu = BleConstants.DEFAULT_MTU
        try {
            gatt?.let { g ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Requires CONNECT permission — already gated upstream.
                }
                g.disconnect()
                g.close()
            }
        } catch (e: SecurityException) {
            Timber.w(e, "GATT close denied")
        } finally {
            gatt = null
        }
    }

    fun release() {
        close()
        try {
            appContext.unregisterReceiver(adapterReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
    }

    /* -------------------------------- Write pump ------------------------------ */

    private fun startWritePump() {
        writerJob?.cancel()
        writerJob = scope.launch {
            for (frame in writeChannel) {
                try {
                    writeFrame(frame)
                } catch (e: Exception) {
                    Timber.e(e, "Frame write failed")
                    _gattEvents.tryEmit(GattEvent.Error("Write failed: ${e.message}", e))
                }
            }
        }
    }

    private suspend fun writeFrame(frame: ByteArray) {
        val chunkSize = (currentMtu - BleConstants.ATT_HEADER_BYTES).coerceAtLeast(20)
        var offset = 0
        while (offset < frame.size) {
            val end = min(offset + chunkSize, frame.size)
            val chunk = frame.copyOfRange(offset, end)
            if (!performWriteWithRetry(chunk)) {
                throw IOException("GATT write failed at offset $offset")
            }
            offset = end
        }
        _gattEvents.tryEmit(GattEvent.FrameSent(frame.size))
    }

    private suspend fun performWriteWithRetry(chunk: ByteArray): Boolean {
        val char = writeChar ?: return false
        var attempt = 0
        while (attempt <= BleConstants.MAX_WRITE_RETRIES) {
            val deferred = CompletableDeferred<Unit>()
            writeDeferred = deferred
            val started = writeChunk(char, chunk)
            if (started) {
                val ok = try {
                    withTimeout(BleConstants.OPERATION_TIMEOUT_MS) { deferred.await() }
                    true
                } catch (_: Exception) {
                    false
                }
                if (ok) return true
            }
            attempt++
            Timber.w("Write retry %d/%d", attempt, BleConstants.MAX_WRITE_RETRIES)
        }
        return false
    }

    private fun writeChunk(char: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val g = gatt ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                char.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
        } catch (e: SecurityException) {
            false
        }
    }

    /* ------------------------------ GATT callback ----------------------------- */

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Timber.d("onConnectionStateChange status=%d newState=%d", status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        _gattEvents.tryEmit(GattEvent.Connected(g.device.address))
                        _connectionState.value = ConnectionState.Discovering(g.device.address)
                        if (!g.discoverServices()) {
                            failReady("Service discovery could not start")
                        }
                    } else {
                        failReady("Connect status $status")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasReady = readyDeferred?.isActive == false
                    if (readyDeferred?.isActive == true) {
                        failReady("Disconnected during handshake (status $status)")
                    } else if (wasReady) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                    _gattEvents.tryEmit(GattEvent.Warning("Disconnected (status $status)"))
                    close()
                    if (readyDeferred == null) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failReady("Service discovery failed ($status)")
                return
            }
            val service = g.getService(BleConstants.UART_SERVICE)
            writeChar = service?.getCharacteristic(BleConstants.UART_WRITE_CHAR)
            notifyChar = service?.getCharacteristic(BleConstants.UART_NOTIFY_CHAR)

            _gattEvents.tryEmit(GattEvent.ServicesDiscovered(g.services.size))

            if (writeChar == null || notifyChar == null) {
                failReady("UART characteristics not found — unsupported controller")
                return
            }
            if (!enableNotify(g, notifyChar!!)) {
                failReady("Failed to subscribe to notifications")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleConstants.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failReady("CCCD write failed ($status)")
                return
            }
            _gattEvents.tryEmit(GattEvent.NotificationEnabled(descriptor.characteristic.uuid.toString()))
            // Negotiate MTU last; onMtuChanged finalises the Ready state.
            if (!g.requestMtu(BleConstants.REQUESTED_MTU)) {
                finalizeReady(BleConstants.DEFAULT_MTU)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            val negotiated = if (status == BluetoothGatt.GATT_SUCCESS) mtu else BleConstants.DEFAULT_MTU
            _gattEvents.tryEmit(GattEvent.MtuNegotiated(negotiated))
            finalizeReady(negotiated)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) writeDeferred?.complete(Unit)
            else writeDeferred?.completeExceptionally(IOException("Write status $status"))
        }

        // API 33+ carries the value directly.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BleConstants.UART_NOTIFY_CHAR) emitIncoming(value)
        }

        // Legacy path (API < 33).
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BleConstants.UART_NOTIFY_CHAR) {
                characteristic.value?.let { emitIncoming(it) }
            }
        }
    }

    private fun emitIncoming(value: ByteArray) {
        if (value.isEmpty()) return
        _gattEvents.tryEmit(GattEvent.FrameReceived(value.size))
        _incoming.tryEmit(value)
    }

    private fun enableNotify(g: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
        return try {
            if (!g.setCharacteristicNotification(char, true)) return false
            val cccd = char.getDescriptor(BleConstants.CCCD) ?: return false
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (e: SecurityException) {
            false
        }
    }

    private fun finalizeReady(mtu: Int) {
        currentMtu = mtu
        _connectionState.value = ConnectionState.Ready(
            address = currentAddress.orEmpty(),
            name = currentName,
            brand = currentBrand,
            mtu = mtu,
        )
        readyDeferred?.complete(Unit)
    }

    private fun failReady(reason: String) {
        Timber.w("GATT bring-up failed: %s", reason)
        _gattEvents.tryEmit(GattEvent.Error(reason))
        _connectionState.value = ConnectionState.Failed(currentAddress, reason)
        readyDeferred?.completeExceptionally(IOException(reason))
    }
}
