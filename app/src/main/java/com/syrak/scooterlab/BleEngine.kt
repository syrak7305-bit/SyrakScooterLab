package com.syrak.scooterlab

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.syrak.scooterlab.diagnostics.GattCharacteristicRecord
import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.diagnostics.GattServiceRecord
import java.util.UUID

/* ============================================================================
 *  BLE-ENGINE  (Real GATT Engine)
 *  ----------------------------------------------------------------------------
 *  Verantwortlich fuer:
 *    • echte GATT-Verbindung + Reconnect
 *    • Service-/Characteristic-Lookup (Navee FFE0/FFE1/FFE2, Ninebot E003/FFF1/FFF2)
 *    • CCCD-Notify-Setup (0x2902), MTU-Negotiation
 *    • serielle Write-Queue (genau EINE GATT-Operation gleichzeitig)
 *    • echtes Controller-ACK-Handling (gleiche CMD-ID + gueltige Pruefsumme)
 *    • chunkweises Firmware-Flashing mit Fortschritt anhand geschriebener Bytes
 *
 *  WICHTIG: Die Engine enthaelt KEINE herstellerspezifischen Byte-Operationen
 *  mehr. ALLE Frames werden ueber den aktiven [ScooterProtocolAdapter] gebaut
 *  und geparst. Der Adapter wird bei der Service-Discovery automatisch gesetzt.
 *
 *  BUGFIXES (aus dem Review):
 *    FIX A: commandIdOf() Bounds-Guard (Ninebot braucht >= 5 Bytes) -> FrameCodec.
 *    FIX B: isCurrentGatt() verwirft verspaetete Callbacks einer ALTEN Verbindung
 *           auch bei Reconnect auf dasselbe Geraet (aktive GATT hat Vorrang).
 *    FIX C: ACK-Fallback funktioniert jetzt AUCH beim allerersten Befehl
 *           (lastRxMillis > 0 war faelschlich Voraussetzung).
 *    FIX D: abortFlash() entsperrt die Write-Queue sauber via failPendingWrites()
 *           (vorher blieb writeInFlight gesetzt -> Queue haengte).
 *    FIX E: Rueckgabewert von discoverServices() wird ausgewertet.
 * ==========================================================================*/
class BleEngine(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onLog(line: String)
        fun onConnected(address: String)
        fun onReady(address: String, protocol: BleProtocol, mtu: Int)
        fun onDisconnected(unexpected: Boolean)
        fun onTelemetry(t: Telemetry)
        fun onWriteResult(label: String, frame: ByteArray, success: Boolean, detail: String)
        fun onFlashProgress(written: Int, total: Int)
        fun onFlashFinished(success: Boolean, message: String)
        fun onRssi(rssi: Int)

        /**
         * PHASE 11: Roh-Frame einer eingehenden Notification, noch VOR der
         * Dekodierung. Wird fuer den exportierbaren Hardware-Testbericht
         * benoetigt. Default-Implementierung leer, damit bestehende
         * Implementierungen unveraendert bleiben.
         */
        fun onRawNotification(bytes: ByteArray) {}
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Navee / Brightway
        private val NAVEE_SERVICE = UUID.fromString(NaveeFrames.SERVICE_UUID)
        private val NAVEE_CHAR_WRITE = UUID.fromString(NaveeFrames.CHAR_UUID)
        private val NAVEE_CHAR_WRITE_ALT = UUID.fromString(NaveeFrames.CHAR_WRITE_UUID)

        // Bevorzugte Notify-UUIDs (Brightway: FFE1 = Notify, FFE2 = Write).
        // Diese UUIDs stammen aus den bereits dokumentierten Projekt-Konstanten,
        // es werden KEINE neuen UUIDs erfunden.
        private val NAVEE_CHAR_NOTIFY = UUID.fromString(NaveeFrames.CHAR_UUID)
        private val NAVEE_CHAR_NOTIFY_ALT = UUID.fromString(NaveeFrames.CHAR_WRITE_UUID)

        // Ninebot / Xiaomi
        private val NINEBOT_SERVICE = UUID.fromString("0000e003-0000-1000-8000-00805f9b34fb")
        private val NINEBOT_CHAR_WRITE = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val NINEBOT_CHAR_WRITE_ALT = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 247
        private const val ATT_HEADER = 3

        /** Genau 1 automatischer Retry pro Schreibvorgang. */
        private const val MAX_WRITE_RETRIES = 1

        /** Wartezeit auf ein Controller-ACK (Notify), bevor ein Fallback versucht wird. */
        private const val RESPONSE_TIMEOUT_MS = 900L

        /** Max. Wartezeit auf das per-Chunk-ACK beim Flash (sonst Abbruch). */
        private const val FLASH_CHUNK_TIMEOUT_MS = 3000L

        /** Max. Zeit vom connectGatt() bis zur einsatzbereiten Verbindung (onReady). */
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var lastDevice: BluetoothDevice? = null
    @Volatile private var negotiatedMtu = 23
    @Volatile private var connectingDevice: BluetoothDevice? = null

    /**
     * FIX B: monotone Verbindungs-Generation. Wird bei jedem connect()/reconnect()
     * erhoeht. Verspaetete Callbacks einer alten GATT-Instanz werden verworfen,
     * sobald die neue GATT aktiv ist — auch beim Reconnect auf DASSELBE Geraet.
     */
    @Volatile private var connectionGeneration = 0

    /** Der aktive Protokoll-Adapter. Wird bei der Service-Discovery gesetzt. */
    @Volatile var adapter: ScooterProtocolAdapter = ProtocolAdapters.forProtocol(BleProtocol.NINEBOT)
        private set

    /** Aktive Protokollfamilie (abgeleitet aus dem Adapter). */
    val protocol: BleProtocol get() = adapter.protocol

    /**
     * PHASE 10: Vom UI erkanntes Modell. Wird NUR fuer die modellspezifische
     * Adapter-Auswahl benutzt. Bleibt UNKNOWN, wenn nichts erkannt wurde.
     */
    @Volatile private var modelHint: ScooterModel = ScooterModel.UNKNOWN

    @Volatile private var naveeHandshakeSent = false

    /**
     * PHASE 8.1 (Sicherheitsaudit, Punkt 1): Erlaubt den EAGER-Unlock-Handshake
     * bereits beim Verbindungsaufbau. Standard = false/false, damit an ein NICHT
     * verifiziertes Geraet kein Schreibbefehl geht. Die Activity setzt dies
     * ueber [setAutoHandshakeAllowed] nur, wenn Modell erkannt UND Hardware
     * bestaetigt sind. Der LAZY-Handshake vor erlaubten Befehlen (inkl. Not-Aus)
     * bleibt davon unberuehrt.
     */
    @Volatile private var autoHandshakeModelKnown = false
    @Volatile private var autoHandshakeHardwareVerified = false

    /**
     * Setzt die Freigabe fuer den EAGER-Unlock-Handshake beim Verbindungsaufbau.
     * Beide Bedingungen muessen erfuellt sein (siehe [AutoHandshakePolicy]).
     */
    fun setAutoHandshakeAllowed(modelKnown: Boolean, hardwareVerified: Boolean) {
        autoHandshakeModelKnown = modelKnown
        autoHandshakeHardwareVerified = hardwareVerified
    }

    @Volatile private var notifyActive = false
    @Volatile private var lastRxMillis = 0L
    private var responseTimeout: Runnable? = null
    private var awaitingAck: PendingWrite? = null

    // ---- Schreib-Queue (nur EINE GATT-Operation gleichzeitig) --------------
    private class PendingWrite(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val label: String,
        val onResult: ((Boolean) -> Unit)?,
        val retries: Int = 0,
        val alternatives: List<ByteArray> = emptyList(),
        val expectResponse: Boolean = false
    )

    private val writeLock = Any()
    private val writeQueue = ArrayDeque<PendingWrite>()
    @Volatile private var writeInFlight = false
    @Volatile private var currentWrite: PendingWrite? = null

    /** afterMtu() (Notify-Setup + Handshake + onReady) laeuft pro Verbindung GENAU EINMAL. */
    @Volatile private var initializationStarted = false

    // ---- Verbindungs-Timeout ----------------------------------------------
    /**
     * Wird bei connect()/reconnect() scharf geschaltet und nach onReady() bzw.
     * nach DISCONNECTED wieder abgebrochen. Verhindert, dass die UI bei einem
     * stillen/haengenden Verbindungsaufbau dauerhaft im "Verbinde..."-Zustand
     * bleibt (klassischer Fehler bei GATT 133 / Geraet ausser Reichweite).
     */
    private var connectTimeout: Runnable? = null
    @Volatile private var readyReached = false

    private fun armConnectTimeout(address: String) {
        cancelConnectTimeout()
        readyReached = false
        val r = Runnable {
            if (!readyReached) {
                listener.onLog("> link: TIMEOUT — keine einsatzbereite Verbindung zu $address " +
                        "nach ${CONNECT_TIMEOUT_MS} ms (Discovery/MTU/Notify unvollstaendig)")
                // Sauber abraeumen: GATT schliessen, Queue verwerfen, UI informieren.
                val old = synchronized(writeLock) { val o = gatt; gatt = null; o }
                try { old?.disconnect(); old?.close() } catch (_: Exception) {}
                resetConnectionState()
                failPendingWrites("connect timeout")
                listener.onDisconnected(true)
            }
        }
        connectTimeout = r
        Handler(Looper.getMainLooper()).postDelayed(r, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        connectTimeout = null
    }

    // ---- Flash-Status -----------------------------------------------------
    @Volatile private var flashChunks: List<ByteArray> = emptyList()
    @Volatile private var flashTotalBytes = 0
    @Volatile private var flashWrittenBytes = 0
    @Volatile private var flashIndex = 0
    @Volatile private var flashActive = false
    @Volatile private var flashChunkTimeout: Runnable? = null

    fun setProtocolHint(p: BleProtocol) {
        adapter = ProtocolAdapters.forProtocol(p)
    }

    /**
     * PHASE 10: Setzt das erkannte Modell. Die Service-Discovery bevorzugt
     * danach einen Adapter, der GENAU dieses Modell fuehrt. Gibt es keinen,
     * bleibt der Protokoll-Adapter als reiner Transport-Fallback aktiv — ohne
     * Tuning-Freigabe (die entscheidet [ProtocolAdapters.supportsTuning]).
     */
    fun setModelHint(model: ScooterModel) {
        modelHint = model
    }

    /**
     * Bevorzugt einen Adapter, der das erkannte Modell EXPLIZIT fuehrt und zur
     * entdeckten Protokollfamilie passt. Sonst Transport-Fallback.
     */
    private fun adapterForDiscovered(protocol: BleProtocol): ScooterProtocolAdapter {
        val exact = ProtocolAdapters.resolveForModel(modelHint).adapter
        return if (exact != null && exact.protocol == protocol) exact
        else ProtocolAdapters.forProtocol(protocol)
    }

    /** Effektive Nutzlast pro Write = MTU - 3 (ATT-Overhead), gedeckelt. */
    fun maxWritePayload(): Int = (negotiatedMtu - ATT_HEADER).coerceIn(20, 512)

    /** Ausgehandelter MTU-Wert (fuer die Header-Systemleiste). */
    fun currentMtu(): Int = negotiatedMtu

    /** Anzahl der aktuell in der Write-Queue wartenden Operationen. */
    fun queueDepth(): Int = synchronized(writeLock) { writeQueue.size }

    /** true, wenn gerade genau eine GATT-Operation in Flight ist. */
    fun isWriteInFlight(): Boolean = synchronized(writeLock) { writeInFlight }

    @SuppressLint("MissingPermission")
    fun readRssi() {
        try { gatt?.readRemoteRssi() } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        lastDevice = device
        resetConnectionState()
        synchronized(writeLock) {
            connectingDevice = device
            connectionGeneration++          // FIX B
        }
        listener.onLog("> link: connectGatt(${device.address}) [gen=$connectionGeneration]")
        armConnectTimeout(device.address)
        val newGatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            listener.onLog("> link: connectGatt() Exception: ${e.message}")
            null
        }
        synchronized(writeLock) { gatt = newGatt; connectingDevice = null }
        if (newGatt == null) listener.onLog("> link: FEHLER — connectGatt() lieferte null")
    }

    @SuppressLint("MissingPermission")
    fun reconnect() {
        val d = lastDevice ?: return
        listener.onLog("> link: auto-reconnect ${d.address}")
        armConnectTimeout(d.address)
        try { gatt?.close() } catch (_: Exception) {}
        resetConnectionState()
        synchronized(writeLock) {
            gatt = null
            connectingDevice = d
            connectionGeneration++          // FIX B
        }
        val newGatt = try {
            d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            listener.onLog("> link: connectGatt() Exception: ${e.message}")
            null
        }
        synchronized(writeLock) { gatt = newGatt; connectingDevice = null }
    }

    /**
     * Trennt die Verbindung bewusst (Nutzeraktion oder onDestroy).
     *
     * PHASE 4 FIX: Bisher wurde `gatt` VOR `old.disconnect()` auf null gesetzt.
     * Der DISCONNECTED-Callback der alten GATT wurde danach von [isCurrentGatt]
     * als "veraltet" verworfen — [Listener.onDisconnected] feuerte also NIE.
     * Folge: UI, Telemetrie und Vordergrunddienst blieben auf "verbunden"
     * stehen, obwohl keine Verbindung mehr existierte.
     *
     * Deshalb meldet die Engine das Ende jetzt selbst. Der Callback der alten
     * GATT bleibt weiterhin gefiltert, es kommt also genau EINE Meldung.
     *
     * @param notifyListener false nur aus onDestroy, wenn die UI schon weg ist.
     */
    @SuppressLint("MissingPermission")
    fun disconnect(notifyListener: Boolean = true) {
        lastDevice = null
        cancelConnectTimeout()
        readyReached = false
        val old = synchronized(writeLock) { val o = gatt; gatt = null; connectingDevice = null; o }
        try { old?.disconnect(); old?.close() } catch (_: Exception) {}
        writeChar = null
        notifyChar = null
        resetConnectionState()
        failPendingWrites("disconnect")
        if (notifyListener) listener.onDisconnected(false)
    }

    /** Setzt den gesamten Verbindungs-/Initialisierungszustand zurueck. */
    private fun resetConnectionState() {
        synchronized(writeLock) {
            initializationStarted = false
            awaitingAck = null
        }
        cancelResponseTimeout()
        naveeHandshakeSent = false
        adapter.setUnlocked(false)
        notifyActive = false
        lastRxMillis = 0L
    }

    /* ------------------------------------------------------------------ */
    /*  FIX B: GUARDS GEGEN VERALTETE GATT-CALLBACKS                       */
    /* ------------------------------------------------------------------ */
    /**
     * Liefert true, wenn [g] die aktuell gueltige GATT-Instanz ist.
     * Ist bereits eine aktive GATT gesetzt, hat AUSSCHLIESSLICH diese Vorrang
     * (ein verspaeteter Callback der alten Verbindung wird verworfen — auch bei
     * Reconnect auf dasselbe Geraet). Nur waehrend des kurzen Fensters zwischen
     * connectGatt() und der Zuweisung gilt der "connectingDevice"-Sonderfall.
     */
    private fun isCurrentGatt(g: BluetoothGatt): Boolean =
        synchronized(writeLock) {
            val current = gatt
            if (current != null) current === g
            else (connectingDevice != null && deviceMatches(g, connectingDevice))
        }

    private fun deviceMatches(g: BluetoothGatt, d: BluetoothDevice?): Boolean {
        if (d == null) return false
        return try { g.device == d } catch (_: Exception) { false }
    }

    private fun closeStaleGatt(g: BluetoothGatt) {
        try { g.close() } catch (_: Exception) {}
    }

    /**
     * Bricht ALLE wartenden Schreiboperationen ab, ruft deren onResult(false)
     * und entsperrt die Queue. Verhindert ein dauerhaftes Haengen der App.
     */
    private fun failPendingWrites(reason: String) {
        val drained = ArrayList<PendingWrite>()
        val inFlight: PendingWrite?
        synchronized(writeLock) {
            drained.addAll(writeQueue)
            writeQueue.clear()
            inFlight = currentWrite
            currentWrite = null
            writeInFlight = false
            awaitingAck = null
        }
        cancelResponseTimeout()
        listener.onLog("> tx: failPendingWrites($reason) — ${drained.size} wartende Op(s) verworfen")
        for (op in drained) {
            try { op.onResult?.invoke(false) } catch (_: Exception) {}
        }
        val hang = inFlight
        if (hang != null && drained.none { it === hang }) {
            try { hang.onResult?.invoke(false) } catch (_: Exception) {}
        }
    }

    /* ------------------------------------------------------------------ */
    /*  GATT CALLBACK                                                      */
    /* ------------------------------------------------------------------ */
    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("> link: GATT-Fehler status=$status")
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        listener.onLog("> link: CONNECTED ${g.device.address} — discoverServices()")
                        resetConnectionState()
                        listener.onConnected(g.device.address)
                        // FIX E: Rueckgabewert auswerten.
                        val started = try { g.discoverServices() } catch (_: Exception) { false }
                        if (!started) {
                            listener.onLog("> link: discoverServices() abgelehnt — Verbindung instabil")
                            listener.onDisconnected(true)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        listener.onLog("> link: DISCONNECTED (status=$status)")
                        cancelConnectTimeout()
                        resetConnectionState()
                        failPendingWrites("disconnected")
                        listener.onDisconnected(status != BluetoothGatt.GATT_SUCCESS)
                    }
                }
            } catch (e: Exception) {
                listener.onLog("> link: Exception in onConnectionStateChange: ${e.message}")
                failPendingWrites("onConnectionStateChange exception")
                listener.onDisconnected(true)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // PHASE 8 FIX (Punkt 3, GATT-Service-Discovery): Bisher wurde
                    // bei fehlgeschlagener Discovery nur geloggt und zurueckgekehrt.
                    // Die UI blieb dann bis zum 15-s-Connect-Timeout im Zustand
                    // "Verbinde...". Jetzt wird die Verbindung sofort sauber
                    // abgebrochen (analog zum discoverServices()==false-Pfad in
                    // onConnectionStateChange). Der Connect-Timeout bleibt als
                    // Sicherheitsnetz weiterhin scharf.
                    listener.onLog("> gatt: Service-Discovery FEHLGESCHLAGEN status=$status \u2014 Verbindung wird abgebrochen")
                    failPendingWrites("service discovery failed")
                    listener.onDisconnected(true)
                    return
                }
                listener.onLog("> gatt: ${g.services.size} Services gefunden")

                // 1) Protokoll anhand vorhandener Services bestimmen + Adapter setzen
                val naveeWrite = findChar(g, NAVEE_SERVICE, listOf(NAVEE_CHAR_WRITE, NAVEE_CHAR_WRITE_ALT))
                if (naveeWrite != null) {
                    adapter = adapterForDiscovered(BleProtocol.NAVEE_BRIGHTWAY)
                    adapter.setUnlocked(false)
                    writeChar = naveeWrite.first
                    // FIX: Notify-Characteristic UNABHAENGIG vom Write-Characteristic waehlen.
                    // Brightway nutzt FFE1 fuer Notify und FFE2 fuer Write; ein Write-Char
                    // ohne NOTIFY-Property haette sonst Telemetrie und ACK verhindert.
                    notifyChar = findNotifyChar(
                        g, NAVEE_SERVICE,
                        listOf(NAVEE_CHAR_NOTIFY, NAVEE_CHAR_NOTIFY_ALT),
                        naveeWrite.first
                    )
                    listener.onLog("> gatt: Navee/Brightway erkannt (FFE0) -> Adapter '${adapter.id}', " +
                            "write=${writeChar?.uuid}, notify=${notifyChar?.uuid}")
                } else {
                    val nbWrite = findChar(g, NINEBOT_SERVICE, listOf(NINEBOT_CHAR_WRITE, NINEBOT_CHAR_WRITE_ALT))
                    if (nbWrite != null) {
                        adapter = ProtocolAdapters.forProtocol(BleProtocol.NINEBOT)
                        writeChar = nbWrite.first
                        notifyChar = findNotifyChar(
                            g, NINEBOT_SERVICE,
                            listOf(NINEBOT_CHAR_WRITE, NINEBOT_CHAR_WRITE_ALT),
                            nbWrite.first
                        )
                        listener.onLog("> gatt: Ninebot/Xiaomi erkannt (E003) -> Adapter '${adapter.id}', " +
                                "write=${writeChar?.uuid}, notify=${notifyChar?.uuid}")
                    } else {
                        val generic = firstWritable(g)
                        if (generic != null) {
                            writeChar = generic
                            val svc = generic.service?.uuid
                            notifyChar = if (svc != null) {
                                findNotifyChar(g, svc, listOf(generic.uuid), generic)
                            } else generic
                            listener.onLog("> gatt: kein bekanntes Service — generischer Write-Char ${generic.uuid}")
                        } else {
                            listener.onLog("> gatt: KEIN writable Characteristic gefunden — Tuning nicht moeglich")
                        }
                    }
                }

                // 1b) Vollstaendigen GATT-Baum protokollieren (echte Daten des Controllers).
                //     Damit laesst sich ein unbekanntes Geraet (z. B. GT3 Max mit
                //     abweichendem Service) diagnostizieren, ohne UUIDs zu raten.
                dumpGatt("Service-Discovery")

                // 2) MTU verhandeln (echt!)
                listener.onLog("> gatt: requestMtu($REQUESTED_MTU)")
                if (!g.requestMtu(REQUESTED_MTU)) {
                    listener.onLog("> gatt: requestMtu() von Stack abgelehnt — nutze Default 23")
                    afterMtu(g)
                }
            } catch (e: Exception) {
                listener.onLog("> gatt: Exception in onServicesDiscovered: ${e.message}")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                listener.onLog("> gatt: MTU = $negotiatedMtu (status=$status) -> Chunk-Payload ${maxWritePayload()} B")
                afterMtu(g)
            } catch (e: Exception) {
                listener.onLog("> gatt: Exception in onMtuChanged: ${e.message}")
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) listener.onRssi(rssi)
            } catch (e: Exception) {
                listener.onLog("> link: Exception in onReadRemoteRssi: ${e.message}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                cancelResponseTimeout()
                val op: PendingWrite?
                synchronized(writeLock) { op = currentWrite }
                val success = status == BluetoothGatt.GATT_SUCCESS
                if (op != null) {
                    when {
                        // 1) Fehler -> genau 1x automatisch wiederholen
                        !success && op.retries < MAX_WRITE_RETRIES -> {
                            listener.onLog("> tx[${op.label}]: Fehler status=$status — " +
                                    "Retry ${op.retries + 1}/$MAX_WRITE_RETRIES")
                            synchronized(writeLock) {
                                writeQueue.addFirst(
                                    PendingWrite(op.characteristic, op.data, op.label,
                                        op.onResult, op.retries + 1, op.alternatives, op.expectResponse)
                                )
                            }
                        }
                        // 2) Fehler nach Retry -> alternative Kodierung probieren
                        !success && op.alternatives.isNotEmpty() -> {
                            val next = op.alternatives.first()
                            val rest = op.alternatives.drop(1)
                            listener.onLog("> tx[${op.label}]: Fehler status=$status — " +
                                    "Fallback auf alternative Kodierung ${hexOf(next)}")
                            synchronized(writeLock) {
                                writeQueue.addFirst(
                                    PendingWrite(op.characteristic, next, op.label,
                                        op.onResult, 0, rest, op.expectResponse)
                                )
                            }
                        }
                        // 3) Endgueltig
                        else -> {
                            listener.onWriteResult(op.label, op.data, success, "status=$status")
                            op.onResult?.invoke(success)
                            if (!success && adapter.protocol == BleProtocol.NAVEE_BRIGHTWAY) {
                                adapter.setUnlocked(false)
                            }
                            // FIX C: ACK-Erwartung scharf schalten — OHNE lastRxMillis-Bedingung,
                            // damit auch der allererste Befehl einen ACK-Fallback erhalten kann.
                            if (success && op.expectResponse && notifyActive && op.alternatives.isNotEmpty()) {
                                synchronized(writeLock) { awaitingAck = op }
                                armAckTimeout(op)
                            }
                        }
                    }
                }
                synchronized(writeLock) {
                    currentWrite = null
                    writeInFlight = false
                }
                processNextWrite()
            } catch (e: Exception) {
                listener.onLog("> tx: Exception in onCharacteristicWrite: ${e.message}")
                failPendingWrites("onCharacteristicWrite exception")
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                    notifyActive = true
                    listener.onLog("> gatt: Notify real aktiv (CCCD-Descriptor-Write OK)")
                }
            } catch (e: Exception) {
                listener.onLog("> gatt: Exception in onDescriptorWrite: ${e.message}")
            }
        }

        /**
         * ABWAERTSKOMPATIBEL: Wir ueberschreiben NUR die (in API 33 deprecated)
         * 2-Argument-Variante. Der AOSP-Default der 3-Argument-Variante ruft intern
         * exakt diese auf -> Notifications auf ALLEN API-Leveln (auch Android 13+).
         */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic
        ) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                lastRxMillis = System.currentTimeMillis()
                val v = ch.value
                if (v == null || v.isEmpty()) {
                    listener.onLog("> rx: Notify ohne Wert (characteristic.value == null)")
                    return
                }
                // Nur ein ECHTES Controller-ACK (gleiche CMD-ID + gueltige Pruefsumme)
                // darf den ACK-Timeout abbrechen. Reine Telemetrie zaehlt NICHT.
                val pending: PendingWrite?
                synchronized(writeLock) { pending = awaitingAck }
                if (pending != null && adapter.isAckFor(pending.data, v)) {
                    listener.onLog("> tx[${pending.label}]: echtes Controller-ACK empfangen (${hexOf(v)})")
                    cancelResponseTimeout()
                    synchronized(writeLock) { awaitingAck = null }
                }
                handleNotification(v)
            } catch (e: Exception) {
                listener.onLog("> rx: Exception in onCharacteristicChanged: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun afterMtu(g: BluetoothGatt) {
        try {
            val alreadyStarted = synchronized(writeLock) {
                if (initializationStarted) true else { initializationStarted = true; false }
            }
            if (alreadyStarted) {
                listener.onLog("> gatt: afterMtu() bereits ausgefuehrt — ignoriere Doppelaufruf")
                return
            }

            // Notifications aktivieren (CCCD 0x2902)
            val nChar = notifyChar
            if (nChar != null) {
                val props = nChar.properties
                val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val canIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (canNotify || canIndicate) {
                    g.setCharacteristicNotification(nChar, true)
                    val cccd = nChar.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        val enable = if (canIndicate && !canNotify)
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        cccd.value = enable
                        @Suppress("DEPRECATION")
                        val ok = g.writeDescriptor(cccd)
                        listener.onLog("> gatt: Notify aktiviert (CCCD 0x2902) — ok=$ok")
                    } else {
                        listener.onLog("> gatt: kein CCCD-Descriptor an ${nChar.uuid} — Notify evtl. inaktiv")
                    }
                } else {
                    listener.onLog("> gatt: Char ${nChar.uuid} unterstuetzt kein Notify/Indicate")
                }
            }

            // Unlock-Handshake (adapter-getrieben): Der Scooter gilt erst als
            // entsperrt, wenn der Handshake quittiert wurde. Vor jedem Tuning-Befehl
            // wird der Zustand geprueft und bei Bedarf erneut gesendet.
            //
            // PHASE 8.1 FIX (Punkt 1): Der EAGER-Handshake wird nur noch gesendet,
            // wenn [AutoHandshakePolicy] ihn freigibt (Modell erkannt + Hardware
            // bestaetigt). Sonst wuerde an ein unbekanntes FFE0-Geraet ungefragt
            // ein Schreibbefehl gehen. Der LAZY-Handshake in enqueueCommand()
            // bleibt fuer erlaubte Befehle und Not-Aus vollstaendig erhalten.
            if (adapter.requiresUnlock && !naveeHandshakeSent) {
                if (AutoHandshakePolicy.shouldSendEagerHandshake(
                        requiresUnlock = true,
                        modelKnown = autoHandshakeModelKnown,
                        hardwareVerified = autoHandshakeHardwareVerified
                    )
                ) {
                    val hs = adapter.handshake()
                    if (hs != null) {
                        naveeHandshakeSent = true
                        enqueueWrite(hs, "unlock-handshake") { ok -> if (ok) adapter.setUnlocked(true) }
                    }
                } else {
                    listener.onLog("> gatt: " +
                        (AutoHandshakePolicy.suppressionReason(
                            requiresUnlock = true,
                            modelKnown = autoHandshakeModelKnown,
                            hardwareVerified = autoHandshakeHardwareVerified
                        ) ?: "Unlock-Handshake unterdrueckt.") +
                        " (lazy vor erlaubtem Befehl/Not-Aus)")
                }
            }

            readyReached = true
            cancelConnectTimeout()
            listener.onReady(g.device?.address ?: "?", adapter.protocol, negotiatedMtu)
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "afterMtu: ${e.message}")
        }
    }

    /* ------------------------------------------------------------------ */
    /*  LOOKUP-HELFER                                                      */
    /* ------------------------------------------------------------------ */
    /**
     * Waehlt das Notify-Characteristic UNABHAENGIG vom Write-Characteristic.
     *
     * Belegter Defekt: zuvor wurde `notifyChar = writeChar` gesetzt. Bei
     * Brightway/Navee ist FFE1 das Notify- und FFE2 das Write-Characteristic.
     * Wurde FFE2 als schreibbar gewaehlt, blieben Notifications aus (keine
     * Telemetrie, kein echtes ACK). Die eigentliche Auswahl ist als reine
     * Funktion in [GattCharacteristicSelector] gekapselt und damit testbar.
     *
     * @param preferredUuids bevorzugte Notify-UUIDs in Prioritaetsreihenfolge.
     * @param fallback       Rueckfall, wenn kein notify-faehiges Char existiert
     *                       (bisheriges Verhalten: das Write-Characteristic).
     */
    private fun findNotifyChar(
        g: BluetoothGatt,
        serviceUuid: UUID,
        preferredUuids: List<UUID>,
        fallback: BluetoothGattCharacteristic?
    ): BluetoothGattCharacteristic? {
        val candidates = ArrayList<GattCharacteristicSelector.Candidate>()
        val byUuid = HashMap<String, BluetoothGattCharacteristic>()
        for (s: BluetoothGattService in g.services) {
            if (s.uuid != serviceUuid) continue
            for (ch: BluetoothGattCharacteristic in s.characteristics) {
                val p = ch.properties
                val notifiable =
                    p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                candidates.add(
                    GattCharacteristicSelector.Candidate(
                        uuid = ch.uuid.toString(),
                        writable = isWritable(ch),
                        notifiable = notifiable,
                    )
                )
                byUuid[ch.uuid.toString()] = ch
            }
        }
        val picked = GattCharacteristicSelector.pickNotify(
            candidates,
            preferredUuids.map { it.toString() },
        ) ?: return fallback
        return byUuid[picked.uuid] ?: fallback
    }

    private fun findChar(
        g: BluetoothGatt, serviceUuid: UUID, charUuids: List<UUID>
    ): Pair<BluetoothGattCharacteristic, BluetoothGattService>? {
        for (s: BluetoothGattService in g.services) {
            if (s.uuid != serviceUuid) continue
            for (ch: BluetoothGattCharacteristic in s.characteristics) {
                if (ch.uuid in charUuids && isWritable(ch)) return ch to s
            }
            for (ch: BluetoothGattCharacteristic in s.characteristics) {
                if (isWritable(ch)) return ch to s
            }
        }
        return null
    }

    private fun firstWritable(g: BluetoothGatt): BluetoothGattCharacteristic? {
        for (s: BluetoothGattService in g.services)
            for (ch: BluetoothGattCharacteristic in s.characteristics)
                if (isWritable(ch)) return ch
        return null
    }

    private fun isWritable(ch: BluetoothGattCharacteristic): Boolean {
        val p = ch.properties
        return p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    /* ------------------------------------------------------------------ */
    /*  GATT-DIAGNOSE                                                      */
    /*  Liest die REAL vom Controller gemeldeten Services/Characteristics */
    /*  und protokolliert sie. Es werden KEINE UUIDs erfunden: was hier    */
    /*  erscheint, stammt ausschliesslich aus der GATT-Datenbank des       */
    /*  verbundenen Geraets.                                               */
    /* ------------------------------------------------------------------ */
    private fun propertiesOf(ch: BluetoothGattCharacteristic): String {
        val p = ch.properties
        val out = ArrayList<String>(6)
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) out.add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) out.add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) out.add("WRITE_NR")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) out.add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) out.add("INDICATE")
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) out.add("BROADCAST")
        if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) out.add("SIGNED_WRITE")
        return if (out.isEmpty()) "0x%02x".format(p) else out.joinToString("|")
    }

    private fun serviceLabel(uuid: UUID): String = when (uuid) {
        NAVEE_SERVICE -> "Navee/Brightway (Projekt-UUID)"
        NINEBOT_SERVICE -> "Ninebot/Xiaomi (Projekt-UUID)"
        else -> "unbekannt"
    }

    /**
     * Schreibt einen vollstaendigen GATT-Baum in das Log-Terminal.
     * @param reason Kurzkontext (z. B. "nach Service-Discovery").
     */
    @SuppressLint("MissingPermission")
    fun dumpGatt(reason: String = "manuell") {
        val g = synchronized(writeLock) { gatt }
        if (g == null) {
            listener.onLog("> gatt-dump: keine aktive Verbindung")
            return
        }
        try {
            val services = g.services
            listener.onLog("> gatt-dump [$reason]: ${services.size} Services, MTU=$negotiatedMtu")
            if (services.isEmpty()) {
                listener.onLog("> gatt-dump: keine Services gecacht (Discovery nicht abgeschlossen?)")
                return
            }
            for (s in services) {
                listener.onLog(">   svc ${s.uuid} [${serviceLabel(s.uuid)}]" +
                        if (s.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) " primary" else " secondary")
                for (ch in s.characteristics) {
                    listener.onLog(">     chr ${ch.uuid} props=${propertiesOf(ch)}" +
                            if (isWritable(ch)) " -> schreibbar" else "")
                    for (d in ch.descriptors) {
                        listener.onLog(">       dsc ${d.uuid}")
                    }
                }
            }
            if (writeChar == null) {
                listener.onLog("> gatt-dump: WARNUNG — kein schreibbares Characteristic ermittelt")
            } else {
                listener.onLog("> gatt-dump: aktiver Write-Char ${writeChar?.uuid}, " +
                        "Notify-Char ${notifyChar?.uuid}, Adapter '${adapter.id}'")
            }
        } catch (e: Exception) {
            listener.onLog("> gatt-dump: Exception: ${e.message}")
        }
    }

    /**
     * PHASE 11: Strukturierter GATT-Snapshot fuer den exportierbaren
     * Hardware-Testbericht. Liefert den real gecachten GATT-Baum (Services ->
     * Characteristics -> Properties). Leere Liste, wenn keine Verbindung oder
     * Discovery noch nicht abgeschlossen ist. Es werden KEINE UUIDs erfunden.
     */
    fun gattSnapshot(): List<GattServiceRecord> {
        val g = synchronized(writeLock) { gatt } ?: return emptyList()
        return try {
            g.services.map { s ->
                GattServiceRecord(
                    uuid = s.uuid.toString(),
                    label = serviceLabel(s.uuid),
                    characteristics = s.characteristics.map { ch ->
                        GattCharacteristicRecord(
                            uuid = ch.uuid.toString(),
                            properties = propertySet(ch)
                        )
                    }
                )
            }
        } catch (e: Exception) {
            listener.onLog("> gatt-snapshot: Exception: ${e.message}")
            emptyList()
        }
    }

    private fun propertySet(ch: BluetoothGattCharacteristic): Set<GattProperty> {
        val p = ch.properties
        val out = LinkedHashSet<GattProperty>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) out.add(GattProperty.READ)
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) out.add(GattProperty.WRITE)
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) out.add(GattProperty.WRITE_NO_RESPONSE)
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) out.add(GattProperty.NOTIFY)
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) out.add(GattProperty.INDICATE)
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) out.add(GattProperty.BROADCAST)
        if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) out.add(GattProperty.SIGNED_WRITE)
        if (p and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) out.add(GattProperty.EXTENDED_PROPERTIES)
        if (out.isEmpty()) out.add(GattProperty.UNKNOWN)
        return out
    }

    /* ------------------------------------------------------------------ */
    /*  SCHREIB-QUEUE                                                      */
    /* ------------------------------------------------------------------ */
    /**
     * PHASE 12: true, wenn aktuell ein Write-Characteristic vorhanden ist.
     * Damit kann die UI Telemetrie-Polling nur starten, wenn es tatsaechlich
     * schreibbar ist — statt jede Sekunde einen Fehler zu protokollieren.
     */
    fun hasWriteCharacteristic(): Boolean = writeChar != null

    fun enqueueWrite(data: ByteArray, label: String, onResult: ((Boolean) -> Unit)?): Boolean {
        val c = writeChar
        if (c == null) {
            listener.onLog("> tx: FEHLER — kein Write-Characteristic (nicht verbunden?)")
            onResult?.invoke(false)
            return false
        }
        return try {
            synchronized(writeLock) {
                writeQueue.addLast(PendingWrite(c, data, label, onResult))
                if (!writeInFlight) processNextWrite()
            }
            true
        } catch (e: Exception) {
            listener.onLog("> tx: Exception beim Einreihen: ${e.message}")
            onResult?.invoke(false)
            false
        }
    }

    /* ------------------------------------------------------------------ */
    /*  BEFEHL MIT FALLBACK-KETTE (adapter-getrieben)                      */
    /* ------------------------------------------------------------------ */
    /**
     * Reiht einen Tuning-Befehl ein. Der Frame wurde bereits vom aktiven Adapter
     * gebaut. Die Fallback-Kette (Standard -> Alt-Header) liefert der Adapter.
     * Ist der Scooter noch gesperrt (requiresUnlock), wird ZUERST der
     * Unlock-Handshake gesendet.
     */
    fun enqueueCommand(command: ByteArray, label: String, requiresUnlock: Boolean): Boolean {
        val c = writeChar
        if (c == null) {
            listener.onLog("> tx[$label]: FEHLER — kein Write-Characteristic (nicht verbunden?)")
            return false
        }
        return try {
            val frames = adapter.buildOutboundFrames(command)
            val needHandshake = requiresUnlock && adapter.requiresUnlock && !adapter.isUnlocked
            synchronized(writeLock) {
                if (needHandshake) {
                    val hs = adapter.handshake()
                    if (hs != null) {
                        listener.onLog("> tx[$label]: Scooter gesperrt — sende Unlock-Handshake zuerst")
                        writeQueue.addLast(
                            PendingWrite(c, hs, "unlock-handshake",
                                { ok -> if (ok) adapter.setUnlocked(true) })
                        )
                    }
                }
                writeQueue.addLast(
                    PendingWrite(c, frames.first(), label, null, 0, frames.drop(1), frames.size > 1)
                )
                if (!writeInFlight) processNextWrite()
            }
            true
        } catch (e: Exception) {
            listener.onLog("> tx[$label]: Exception beim Einreihen: ${e.message}")
            false
        }
    }

    /** Scharfschaltung des ACK-Timeouts (nur wenn Notify real aktiv war). */
    private fun armAckTimeout(op: PendingWrite) {
        cancelResponseTimeout()
        val r = Runnable {
            val stillAwaiting = synchronized(writeLock) { awaitingAck === op }
            if (stillAwaiting && op.alternatives.isNotEmpty()) {
                val next = op.alternatives.first()
                val rest = op.alternatives.drop(1)
                listener.onLog("> tx[${op.label}]: kein Controller-ACK in ${RESPONSE_TIMEOUT_MS} ms — " +
                        "versuche alternative Kodierung ${hexOf(next)}")
                synchronized(writeLock) { awaitingAck = null }
                val c = writeChar
                if (c != null) {
                    synchronized(writeLock) {
                        writeQueue.addFirst(
                            PendingWrite(c, next, op.label, op.onResult, 0, rest, true)
                        )
                        if (!writeInFlight) processNextWrite()
                    }
                }
            }
        }
        responseTimeout = r
        Handler(Looper.getMainLooper()).postDelayed(r, RESPONSE_TIMEOUT_MS)
    }

    private fun cancelResponseTimeout() {
        responseTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        responseTimeout = null
    }

    @SuppressLint("MissingPermission")
    private fun processNextWrite() {
        try {
            val op: PendingWrite
            synchronized(writeLock) {
                if (writeQueue.isEmpty()) { writeInFlight = false; return }
                op = writeQueue.removeFirst()
                writeInFlight = true
                currentWrite = op
            }
            val g = synchronized(writeLock) { gatt }
            if (g == null) {
                listener.onWriteResult(op.label, op.data, false, "GATT null")
                op.onResult?.invoke(false)
                synchronized(writeLock) {
                    currentWrite = null
                    writeInFlight = false
                }
                processNextWrite()
                return
            }
            // Write-Type anhand der tatsaechlichen Characteristic-Properties waehlen.
            // WRITE_TYPE_DEFAULT (mit Response) bevorzugen, damit onCharacteristicWrite
            // zuverlaessig als Queue-Freigabe dient. Nur wenn die Characteristic
            // ausschliesslich WRITE_NO_RESPONSE kann, wird dieser Typ genutzt.
            val supportsWriteWithResponse =
                (op.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val writeType = if (supportsWriteWithResponse)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            val started: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: neuer, nicht-deprecated Aufruf mit expliziten Bytes und Write-Type.
                // Rueckgabe ist ein BluetoothStatusCodes-Status, KEIN Boolean.
                val status = g.writeCharacteristic(op.characteristic, op.data, writeType)
                started = status == BluetoothStatusCodes.SUCCESS
                if (!started) {
                    listener.onLog("> tx[${op.label}]: writeCharacteristic(API33) status=$status " +
                            "(type=$writeType, ${op.data.size} B)")
                }
            } else {
                // API < 33: Legacy-Pfad (Characteristic.value + writeCharacteristic(char)).
                @Suppress("DEPRECATION")
                run {
                    op.characteristic.writeType = writeType
                    op.characteristic.value = op.data
                }
                @Suppress("DEPRECATION")
                started = g.writeCharacteristic(op.characteristic)
                if (!started) {
                    listener.onLog("> tx[${op.label}]: writeCharacteristic() returned false " +
                            "(type=$writeType, ${op.data.size} B)")
                }
            }
            if (!started) {
                val retryOp = if (op.retries < MAX_WRITE_RETRIES)
                    PendingWrite(op.characteristic, op.data, op.label, op.onResult,
                        op.retries + 1, op.alternatives, op.expectResponse)
                else if (op.alternatives.isNotEmpty())
                    PendingWrite(op.characteristic, op.alternatives.first(), op.label, op.onResult,
                        0, op.alternatives.drop(1), op.expectResponse)
                else null
                synchronized(writeLock) {
                    if (retryOp != null) writeQueue.addFirst(retryOp)
                    currentWrite = null
                    writeInFlight = false
                }
                if (retryOp == null) {
                    listener.onWriteResult(op.label, op.data, false, "writeCharacteristic() returned false")
                    op.onResult?.invoke(false)
                }
                processNextWrite()
            }
        } catch (e: Exception) {
            listener.onLog("> tx: Exception in processNextWrite: ${e.message}")
            failPendingWrites("processNextWrite exception")
        }
    }

    /* ------------------------------------------------------------------ */
    /*  NOTIFY -> TELEMETRIE (adapter-getrieben)                           */
    /* ------------------------------------------------------------------ */
    private fun handleNotification(value: ByteArray) {
        try {
            // PHASE 11: Roh-Frame fuer den Hardware-Testbericht melden.
            listener.onRawNotification(value)
            // Primaer ueber den aktiven Adapter dekodieren, danach Cross-Fallback,
            // damit auch abweichende/unvollstaendige Frames sicher greifen.
            var t = adapter.parseTelemetry(value)
            if (t == null) {
                t = when (adapter.protocol) {
                    BleProtocol.NAVEE_BRIGHTWAY -> TelemetryDecoder.decodeNinebot(value)
                    BleProtocol.NINEBOT -> TelemetryDecoder.decodeNavee(value)
                }
            }
            if (t != null) {
                listener.onTelemetry(t)
            } else {
                listener.onLog("> rx: unbehandeltes Frame ${hexOf(value)}")
            }
        } catch (e: Exception) {
            listener.onLog("> rx: Exception beim Dekodieren: ${e.message}")
        }
    }

    /* ------------------------------------------------------------------ */
    /*  ECHTES CHUNK-FLASHING                                              */
    /* ------------------------------------------------------------------ */
    fun startFlash(
        firmware: ByteArray,
        unlockFrame: ByteArray?,
        rebootFrame: ByteArray?
    ) {
        if (writeChar == null) {
            listener.onFlashFinished(false, "Kein Write-Characteristic — Flash abgebrochen.")
            return
        }
        if (firmware.isEmpty()) {
            listener.onFlashFinished(false, "Firmware-Daten leer.")
            return
        }
        val chunkSize = maxWritePayload()
        val chunks = ArrayList<ByteArray>()
        var off = 0
        while (off < firmware.size) {
            val end = (off + chunkSize).coerceAtMost(firmware.size)
            chunks.add(firmware.copyOfRange(off, end))
            off = end
        }
        flashChunks = chunks
        flashTotalBytes = firmware.size
        flashWrittenBytes = 0
        flashIndex = 0
        flashActive = true
        listener.onLog("> flash: START — ${firmware.size} Bytes in ${chunks.size} Chunks à $chunkSize B")

        val begin = {
            if (unlockFrame != null) {
                listener.onLog("> flash: Unlock-Handshake ${hexOf(unlockFrame)}")
                enqueueWrite(unlockFrame, "flash-unlock") { ok ->
                    if (!ok) finishFlash(false, "Unlock-Handshake fehlgeschlagen.")
                    else postToMain { writeNextChunk(rebootFrame) }
                }
            } else {
                writeNextChunk(rebootFrame)
            }
        }
        begin()
    }

    private fun writeNextChunk(rebootFrame: ByteArray?) {
        if (!flashActive) return
        if (flashIndex >= flashChunks.size) {
            if (rebootFrame != null) {
                listener.onLog("> flash: Reboot-Frame ${hexOf(rebootFrame)}")
                enqueueWrite(rebootFrame, "flash-reboot") { ok ->
                    finishFlash(ok, if (ok) "Flash abgeschlossen & Reboot gesendet."
                    else "Reboot-Frame fehlgeschlagen.")
                }
            } else {
                finishFlash(true, "Flash abgeschlossen.")
            }
            return
        }
        val chunk = flashChunks[flashIndex]
        val idx = flashIndex
        armFlashChunkTimeout(idx)
        enqueueWrite(chunk, "flash-chunk-$idx") { ok ->
            cancelFlashChunkTimeout()
            if (!ok) {
                finishFlash(false, "Chunk $idx fehlgeschlagen — Flash abgebrochen.")
                return@enqueueWrite
            }
            flashWrittenBytes += chunk.size
            flashIndex++
            listener.onFlashProgress(flashWrittenBytes, flashTotalBytes)
            postToMain { writeNextChunk(rebootFrame) }
        }
    }

    private fun finishFlash(success: Boolean, message: String) {
        cancelFlashChunkTimeout()
        flashActive = false
        flashChunks = emptyList()
        listener.onFlashFinished(success, message)
    }

    private fun armFlashChunkTimeout(idx: Int) {
        cancelFlashChunkTimeout()
        val r = Runnable {
            if (flashActive && flashIndex == idx) {
                listener.onLog("> flash: Timeout fuer Chunk $idx (kein ACK in ${FLASH_CHUNK_TIMEOUT_MS} ms) - Flash abgebrochen")
                finishFlash(false, "Chunk $idx Timeout - Flash abgebrochen.")
            }
        }
        flashChunkTimeout = r
        Handler(Looper.getMainLooper()).postDelayed(r, FLASH_CHUNK_TIMEOUT_MS)
    }

    private fun cancelFlashChunkTimeout() {
        flashChunkTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        flashChunkTimeout = null
    }

    /**
     * FIX D: Manueller Flash-Abbruch. Vorher wurde nur die Queue geleert, wodurch
     * writeInFlight gesetzt blieb und die Queue dauerhaft blockierte. Jetzt werden
     * ALLE wartenden Ops sauber verworfen und die Queue entsperrt.
     */
    fun abortFlash() {
        if (!flashActive) return
        cancelFlashChunkTimeout()
        flashActive = false
        failPendingWrites("flash-abort")
        listener.onFlashFinished(false, "Flash manuell abgebrochen.")
    }

    /* ------------------------------------------------------------------ */
    /*  HILFEN                                                             */
    /* ------------------------------------------------------------------ */
    private fun postToMain(block: () -> Unit) =
        Handler(Looper.getMainLooper()).post(block)

    private fun hexOf(frame: ByteArray) =
        frame.joinToString(" ") { "%02X".format(it) }
}
