package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.syrak.scooterlab.core.ble.BleSessionService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/* ==========================================================================
 *  SYRAK SCOOTERLAB  —  MainActivity.kt  (v7 "Modular Protocol Engine")
 *  ----------------------------------------------------------------------------
 *  Diese Activity ist jetzt ein SCHLANKER UI-CONTROLLER:
 *    • KEINE herstellerspezifischen Byte-Operationen mehr im UI.
 *    • Jeder Tuning-Befehl wird vom aktiven [ScooterProtocolAdapter] gebaut
 *      (siehe sendFrame()/sendCommand()).
 *    • Persistenz laeuft ueber [ScooterRepository] (PBKDF2-PIN, Profile, Trip).
 *    • BLE-Mechanik + ACK-Handling liegen in [BleEngine].
 *  
 *  Die GESAMTE UI (Tabs, Trip-Computer, Dashboards, Log-Terminal, Systemleiste,
 *  Disclaimers, Scan, Flash, Config) bleibt unveraendert funktionsfaehig.
 * ==========================================================================*/
class MainActivity : AppCompatActivity(), BleEngine.Listener {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var rootContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var activeTimer: CountDownTimer? = null
    private var loadingRunnable: Runnable? = null

    private var isScanning = false
    private val foundDevices = linkedMapOf<String, ScooterDevice>()

    // Scan-UI-Throttle: Scan-Callbacks kommen teils mehrfach pro Sekunde und pro
    // Geraet. Ein vollstaendiger Listen-Rebuild je Callback fuehrt zu Ruckeln und
    // zu flackernden RSSI-Werten. Daher max. ein Rebuild alle LIST_THROTTLE_MS.
    private var lastListUpdateMs = 0L
    private var pendingListUpdate: Runnable? = null
    private val LIST_THROTTLE_MS = 350L
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListContainer: LinearLayout

    private lateinit var repository: ScooterRepository

    private var connectedDevice: ScooterDevice? = null
    private var bleEngine: BleEngine? = null
    private var currentRegion: Region = Region.WORLD
    private var detectedModel: ScooterModel = ScooterModel.UNKNOWN
    private var detectedConfidence: DetectionConfidence = DetectionConfidence.UNKNOWN
    private var detectionEvidence: String = ""
    private var activeProtocol: BleProtocol = BleProtocol.NINEBOT
    private var autoReconnectEnabled = true
    private var reconnectAttempts = 0
    // ---- FIX 2: Reconnect-Backoff ---------------------------------------
    private var reconnectRunnable: Runnable? = null
    private val RECONNECT_BACKOFF_BASE_MS = 1000L
    private val RECONNECT_BACKOFF_MAX_MS = 8000L
    private val RECONNECT_MAX_ATTEMPTS = 3
    private var userInitiatedDisconnect = false
    private var engineReady = false

    private lateinit var tabContent: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private var currentTab = Tab.INFO

    private var maxSpeedKmh = 25
    private var zeroStartKmh = 0
    private var kersLevel = 1
    private var cruiseEnabled = false
    private var isLocked = false
    private var germanManeuverActive = false
    private var extendedPowerUnlocked = false
    private var motorTempGuardC = 75
    private var motorTempGuardEnabled = true

    private val ramOverlay = RamOverlay()
    private var registerSnapshot: RegisterSnapshot? = null

    private var selectedFirmware: FirmwarePackage? = null
    private var selectedFirmwareUri: Uri? = null
    private var selectedFirmwareName: String? = null
    private var lastValidation: FirmwareValidation? = null
    private var loadedFirmwareBytes: ByteArray? = null
    /** FIX 7: hartes Limit gegen ZIP-Bomben / uebergrosse Firmware-Dateien. */
    private val MAX_FIRMWARE_BYTES = FirmwareValidator.MAX_FIRMWARE_BYTES

    private var flashMode: FlashMode = FlashMode.SOFT_PATCH
    private var overspeedAlarmMuted = false

    // ---- Neo-Hacker Pro: Log-Terminal -----------------------------------
    private val logEntries = ArrayDeque<LogEntry>()
    private var logFilter: LogCategory? = null
    private var logContainer: LinearLayout? = null
    private var logScroll: ScrollView? = null

    // ---- FIX 1: UI-/Log-Batching (Main-Handler-Throttle) ----------------
    private var logRefreshScheduled = false
    private var lastLogRefresh = 0L
    private var telemetryApplyScheduled = false
    private var lastTelemetryApply = 0L
    private val UI_REFRESH_INTERVAL_MS = 150L
    private val SLIDER_DEBOUNCE_MS = 300L

    // ---- Neo-Hacker Pro: Header-Systemleiste ----------------------------
    private var sysRssiView: TextView? = null
    private var sysMtuView: TextView? = null
    private var sysQueueView: TextView? = null
    private var sysLinkView: TextView? = null
    private var linkState: BleLinkState = BleLinkState.IDLE
    private var lastRssi: Int = Int.MIN_VALUE
    private var sysBarRunnable: Runnable? = null

    // ---- Fahrmodus (Eco-Saver / Sport-Plus) -----------------------------
    private var rideMode: RideMode = RideMode.NORMAL
    private val ecoThresholdPercent = 20
    private val sportPlusPhaseCurrentA = 27

    // ---- Trip-Computer --------------------------------------------------
    private val tripData get() = repository.trip
    private var dashSoh: TextView? = null
    private var dashCycles: TextView? = null
    private var dashVmax: TextView? = null
    private var dashVavg: TextView? = null
    private var dashWh: TextView? = null
    private var cellGraphRow: LinearLayout? = null

    // ---- Firmware-Quelle (Community-Plattformen) ------------------------
    private var selectedSource: FirmwareSource = FirmwareSource.FACTORY

    // Live-Telemetrie-Views
    private var dashSpeed: TextView? = null
    private var dashPower: TextView? = null
    private var dashVoltage: TextView? = null
    private var dashCurrent: TextView? = null
    private var dashBattery: TextView? = null
    private var dashMotorTemp: TextView? = null
    private var dashTrip: TextView? = null
    private var dashRange: TextView? = null
    private var dashCellMin: TextView? = null
    private var dashCellMax: TextView? = null
    private var dashCellDelta: TextView? = null
    private var dashTempGuardState: TextView? = null
    private var dashFeedState: TextView? = null
    @Volatile private var lastTelemetry: Telemetry? = null
    private var motorGuardTripped = false
    // ---- FIX 4: Gesicherte Notaus-Rampe (Fahrersicherheit) --------------
    private var rampActive = false
    private var rampTicks = 0
    private var rampRunnable: Runnable? = null
    private var rampReason: String? = null
    private val RAMP_TICK_MS = 400L
    private val RAMP_MAX_TICKS = 25
    private val RAMP_CUT_SPEED_KMH = 3f

    // Demo-Simulation (NUR isDemo)
    private var telemetryRunnable: Runnable? = null
    private var simTick = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            if (::statusText.isInitialized) {
                statusText.text = "Berechtigungen erteilt. Scooter einschalten."
            }
            startBleScan()
        } else {
            if (::statusText.isInitialized) {
                statusText.text = "Bluetooth-Berechtigungen erforderlich."
            }
            if (::scanButton.isInitialized) {
                scanButton.isEnabled = true
            }
        }
    }

    private val firmwarePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) handleSelectedFirmware(uri) }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device
            val address = device.address
            val name = try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
                ) device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
                else result.scanRecord?.deviceName ?: "Unknown Device"
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: "Unknown Device"
            }

            val keywords = listOf("scooter", "ninebot", "xiaomi", "navee", "m365", "segway", "soflow")
            val candidate = keywords.any { name.contains(it, ignoreCase = true) }

            // WICHTIG: Callback laeuft auf Binder-Thread -> UI-Update auf Main-Thread
            runOnUiThread {
                foundDevices[address] = ScooterDevice(device, name, address, result.rssi, candidate, false)
                requestThrottledListUpdate()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                isScanning = false
                // PHASE 5 FIX: Auch bei einem Fehlschlag das geplante Timeout
                // entfernen, damit es nicht spaeter einen neuen Scan stoert.
                cancelScanTimeout()
                scanTimeoutGuard.end()
                // Null-sicher: onScanFailed kann vor dem UI-Aufbau oder nach einem
                // Recreate eintreffen.
                if (::scanButton.isInitialized) scanButton.isEnabled = true
                if (::statusText.isInitialized) {
                    statusText.text = "BLE-Scan fehlgeschlagen (Code $errorCode)."
                }
                addLog(LogCategory.ERROR, "BLE-Scan fehlgeschlagen (Code $errorCode).")
            }
        }
    }

    /** Baut die Geraeteliste hoechstens alle [LIST_THROTTLE_MS] neu auf. */
    private fun requestThrottledListUpdate() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastListUpdateMs
        if (elapsed >= LIST_THROTTLE_MS) {
            lastListUpdateMs = now
            updateDeviceList()
            return
        }
        if (pendingListUpdate != null) return
        val r = Runnable {
            pendingListUpdate = null
            lastListUpdateMs = SystemClock.elapsedRealtime()
            updateDeviceList()
        }
        pendingListUpdate = r
        handler.postDelayed(r, LIST_THROTTLE_MS - elapsed)
    }

    private fun cancelPendingListUpdate() {
        pendingListUpdate?.let { handler.removeCallbacks(it) }
        pendingListUpdate = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ScooterRepository(this)
        currentRegion = repository.region
        autoReconnectEnabled = repository.autoReconnect

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)
        showLoadingScreen()
    }

    override fun onDestroy() {
        loadingRunnable?.let { handler.removeCallbacks(it) }
        cancelReconnectRunnable()
        endSafeStopRamp()
        activeTimer?.cancel()
        stopTelemetry()
        stopSystemBar()
        stopBleSession()
        // notifyListener = false: die Activity wird gerade zerstoert, ein
        // UI-Update waere hier nur ein spaeter Callback ohne Empfaenger.
        bleEngine?.disconnect(notifyListener = false)
        safeStopBleScan()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /*  UI-HELFER                                                          */
    /* ------------------------------------------------------------------ */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Palette.SURFACE_ALT)
    }

    private fun makeSectionLabel(value: String): TextView =
        makeText("  $value", 11f, Palette.ACCENT_RED, true).apply { setPadding(0, dp(14), 0, dp(6)) }

    private fun makePrimaryButton(label: String, color: Int = Palette.ACCENT_RED, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = true; setBackgroundColor(color)
            setTextColor(Color.WHITE); setOnClickListener { onClick() }
        }

    private fun makeToggleRow(label: String, desc: String, initial: Boolean, onToggle: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(makeText(label, 14f, Palette.TEXT_PRIMARY, true))
            val state = makeText(if (initial) "AN" else "AUS", 12f,
                if (initial) Palette.ACCENT_GREEN else Palette.TEXT_MUTED, true).apply { gravity = Gravity.END }
            header.addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(header)
            addView(makeText(desc, 12f, Palette.TEXT_MUTED).apply { setPadding(0, dp(3), 0, 0) })
            var value = initial
            setOnClickListener {
                value = !value
                state.text = if (value) "AN" else "AUS"
                state.setTextColor(if (value) Palette.ACCENT_GREEN else Palette.TEXT_MUTED)
                onToggle(value)
            }
        }

    private fun makeSliderCard(
        title: String, desc: String, min: Int, max: Int, initial: Int, unit: String,
        valueFormatter: (Int) -> String = { "$it $unit" }, onChange: (Int) -> Unit
    ): LinearLayout {
        val card = makeCard()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeText(title, 14f, Palette.TEXT_PRIMARY, true))
        val valueView = makeText(valueFormatter(initial), 14f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.END }
        header.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)
        card.addView(makeText(desc, 12f, Palette.TEXT_MUTED).apply { setPadding(0, dp(3), 0, dp(10)) })
        val bar = SeekBar(this).apply { this.max = max - min; progress = initial - min }
        var lastCommit = 0L
        var pendingReal = initial
        val commitRunnable = Runnable {
            lastCommit = System.currentTimeMillis()
            onChange(pendingReal)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val real = progress + min
                pendingReal = real
                valueView.text = valueFormatter(real)
                if (!fromUser) return
                val now = System.currentTimeMillis()
                handler.removeCallbacks(commitRunnable)
                if (now - lastCommit >= SLIDER_DEBOUNCE_MS) {
                    commitRunnable.run()
                } else {
                    handler.postDelayed(commitRunnable, SLIDER_DEBOUNCE_MS - (now - lastCommit))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(commitRunnable)
                commitRunnable.run()
            }
        })
        card.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return card
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(10)) }

    private fun safeStopBleScan() {
        // PHASE 5 FIX: Ein noch geplantes Timeout-Runnable MUSS immer entfernt
        // werden — auch wenn der Scan bereits (z. B. durch onScanFailed) beendet
        // wurde. Sonst feuert es spaeter gegen einen NEU gestarteten Scan und
        // beendet diesen vorzeitig. Die Generation entwertet zusaetzlich jeden
        // bereits laufenden Timeout.
        cancelScanTimeout()
        scanTimeoutGuard.end()
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (_: SecurityException) {
            android.util.Log.w("SyrakScooterLab", "BLE scanner stop denied by permission policy")
        } catch (_: Exception) {
            android.util.Log.w("SyrakScooterLab", "BLE scanner stop failed unexpectedly")
        } finally {
            isScanning = false
            cancelPendingListUpdate()
            if (::scanButton.isInitialized) {
                scanButton.isEnabled = true
            }
        }
    }

    /** Entfernt ein evtl. noch geplantes Scan-Timeout-Runnable aus der Queue. */
    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    /* ================================================================== */
    /*  ZUSÄTZLICHER UI-ZUSTAND                                           */
    /* ================================================================== */
    private var flashProgressBar: ProgressBar? = null
    private var flashStatusText: TextView? = null
    private var demoActive = false
    private var scanTimeoutRunnable: Runnable? = null
    /** PHASE 5 FIX: entwertet veraltete Scan-Timeouts (siehe [ScanTimeoutGuard]). */
    private val scanTimeoutGuard = ScanTimeoutGuard()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun adapter(): ScooterProtocolAdapter =
        bleEngine?.adapter ?: ProtocolAdapters.forProtocol(activeProtocol)

    /* ================================================================== */
    /*  START: LADEN / HAFTUNG / PIN                                      */
    /* ================================================================== */

    private fun showLoadingScreen() {
        rootContainer.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.BG)
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        box.addView(makeText("SYRAK", 42f, Palette.ACCENT_RED, true).apply { gravity = Gravity.CENTER })
        box.addView(makeText("SCOOTERLAB", 15f, Palette.TEXT_SECONDARY, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.35f
        })
        box.addView(makeText("v7 · Modular Protocol Engine", 12f, Palette.TEXT_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(30))
        })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        box.addView(bar, LinearLayout.LayoutParams(dp(190), WRAP))
        rootContainer.addView(box, LinearLayout.LayoutParams(MATCH, MATCH))

        loadingRunnable = Runnable {
            if (!repository.disclaimersAccepted) {
                showDisclaimer()
            } else if (repository.hasPin) {
                showPinDialog()
            } else {
                enterMain()
            }
        }
        handler.postDelayed(loadingRunnable!!, 900L)
    }

    private fun showDisclaimer() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(22), dp(26), dp(22), dp(26))
        }
        col.addView(makeText("SICHERHEITS- & HAFTUNGSHINWEIS", 18f, Palette.ACCENT_RED, true))
        col.addView(makeText(
            "Syrak ScooterLab ist ein Diagnose- und Analysewerkzeug für E-Scooter. " +
                "Es werden KEINE offiziellen Firmware-Downloads, keine geprüften Prüfsummen und " +
                "keine Hardware-Kompatibilitätsgarantien bereitgestellt.\n\n" +
                "Tuning-Eingriffe (Geschwindigkeit, Zero-Start, KERS, Cruise, German Maneuver, " +
                "Polizei-Modus, Notaus) können die Betriebserlaubnis erlöschen lassen, die " +
                "Gewährleistung aufheben und Personen gefährden. In Deutschland gilt für " +
                "E-Scooter die eKFV mit 20 km/h. Das Überschreiten ist im öffentlichen " +
                "Straßenverkehr unzulässig.\n\n" +
                "Firmware-Flashen ist in dieser Version gesperrt, solange kein verifizierter " +
                "SHA-256 und keine belegte Modell-/Hardware-Kompatibilität vorliegen. " +
                "Der Notaus ist eine elektronische Unterstützung und ersetzt NICHT die " +
                "mechanische Bremse.\n\n" +
                "Die Nutzung erfolgt ausschließlich auf eigene Verantwortung. Führe riskante " +
                "Schreibzugriffe nur auf einem eigenen Testgerät und nie im öffentlichen " +
                "Verkehr durch.",
            13f, Palette.TEXT_SECONDARY
        ).apply { setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(16), 0, dp(24)) })
        col.addView(makePrimaryButton("HINWEIS AKZEPTIEREN") {
            repository.disclaimersAccepted = true
            if (repository.hasPin) showPinDialog() else enterMain()
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        scroll.addView(col)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("PIN erforderlich")
            .setMessage("Die App ist mit einer PIN geschützt.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Entsperren") { _, _ ->
                if (repository.verifyPin(input.text.toString())) {
                    enterMain()
                } else {
                    toast("Falsche PIN.")
                    showPinDialog()
                }
            }
            .setNegativeButton("Beenden") { _, _ -> finish() }
            .show()
    }

    private fun enterMain() {
        rootContainer.removeAllViews()

        val sysBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.SURFACE_DEEP)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        sysRssiView = makeText("RSSI —", 10f, Palette.TEXT_MUTED, true)
        sysMtuView = makeText("MTU —", 10f, Palette.TEXT_MUTED, true)
        sysQueueView = makeText("Q 0", 10f, Palette.TEXT_MUTED, true)
        sysLinkView = makeText("IDLE", 10f, Palette.ACCENT_ORANGE, true)
        sysBar.addView(sysLinkView, LinearLayout.LayoutParams(0, WRAP, 1f))
        sysBar.addView(sysMtuView, LinearLayout.LayoutParams(0, WRAP, 1f))
        sysBar.addView(sysQueueView, LinearLayout.LayoutParams(0, WRAP, 1f))
        sysBar.addView(sysRssiView, LinearLayout.LayoutParams(0, WRAP, 1f))
        rootContainer.addView(sysBar, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(this)
        tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(14), dp(8), dp(14), dp(16))
        }
        scroll.addView(tabContent)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.SURFACE_DEEP)
        }
        rootContainer.addView(bottomNav, LinearLayout.LayoutParams(MATCH, WRAP))

        buildTabs()
        showTab(Tab.INFO)
        startSystemBar()
        addLog(LogCategory.INFO, "Syrak ScooterLab gestartet.")
    }

    /* ================================================================== */
    /*  TABS                                                              */
    /* ================================================================== */

    private fun buildTabs() {
        if (!::bottomNav.isInitialized) return
        bottomNav.removeAllViews()
        for (tab in Tab.values()) {
            val selected = tab == currentTab
            val item = makeText(tab.name, 11f,
                if (selected) Palette.ACCENT_RED else Palette.TEXT_MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(13), 0, dp(13))
                setOnClickListener { showTab(tab) }
            }
            bottomNav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        logContainer = null
        logScroll = null
        tabContent.removeAllViews()
        when (tab) {
            Tab.INFO -> renderInfo()
            Tab.TOOLS -> renderTools()
            Tab.DASHBOARD -> renderDashboard()
            Tab.FLASH -> renderFlash()
            Tab.CONFIG -> renderConfig()
        }
        buildTabs()
    }

    /* ================================================================== */
    /*  INFO / SCAN / VERBINDUNG                                          */
    /* ================================================================== */

    private fun renderInfo() {
        tabContent.addView(makeSectionLabel("VERBINDUNG"))
        val card = makeCard()
        statusText = makeText(
            if (engineReady) "Verbunden und bereit." else "Bereit. Scooter einschalten und Scan starten.",
            13f, Palette.TEXT_SECONDARY
        )
        card.addView(statusText)
        scanButton = makePrimaryButton(
            if (isScanning) "SCAN LÄUFT…" else "BLE-SCAN STARTEN",
            Palette.ACCENT_RED
        ) { if (isScanning) safeStopBleScan() else startBleScan() }
        scanButton.isEnabled = !isScanning
        card.addView(scanButton, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(0, dp(10), 0, dp(8))
        })
        // PHASE 4: Es gab bisher KEINE Moeglichkeit, eine bestehende Verbindung
        // bewusst zu trennen (der Verbinden-Button ist im verbundenen Zustand
        // deaktiviert). Ohne manuelles Trennen war "kein Reconnect nach
        // bewusstem Disconnect" gar nicht erreichbar.
        if (connectedDevice != null) {
            card.addView(makePrimaryButton("VERBINDUNG TRENNEN", Palette.ACCENT_RED) {
                manualDisconnect()
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, 0, 0, dp(8))
            })
        }
        deviceListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(deviceListContainer)
        tabContent.addView(card, cardParams())
        updateDeviceList()

        tabContent.addView(makeSectionLabel("ERKANNTES MODELL"))
        val modelCard = makeCard()
        modelCard.addView(makeText(
            if (detectedModel.isKnown)
                "${detectedModel.displayName} — ${detectedConfidence.label}"
            else "Modell nicht erkannt (${detectedConfidence.label})",
            16f,
            when (detectedConfidence) {
                DetectionConfidence.CONFIRMED -> Palette.ACCENT_GREEN
                DetectionConfidence.PROBABLE -> Palette.ACCENT_YELLOW
                DetectionConfidence.UNKNOWN -> Palette.ACCENT_ORANGE
            },
            true
        ))
        modelCard.addView(makeText(modelEvidence(), 12f, Palette.TEXT_MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
        modelCard.addView(makeText("Protokoll: ${activeProtocol.displayName}", 12f, Palette.TEXT_SECONDARY).apply {
            setPadding(0, dp(6), 0, 0)
        })
        if (detectedModel.isKnown) {
            modelCard.addView(makeText(
                "eKFV-Hinweis: ${currentRegion.legalSpeedKmh} km/h in ${currentRegion.displayName}. " +
                    "Tuning ist im öffentlichen Verkehr unzulässig.",
                12f, Palette.ACCENT_YELLOW
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        tabContent.addView(modelCard, cardParams())

        tabContent.addView(makeSectionLabel("DIAGNOSE"))
        val diag = makeCard()
        diag.addView(makeText(
            "MTU: ${bleEngine?.currentMtu() ?: "—"}   Queue: ${bleEngine?.queueDepth() ?: 0}   Link: ${linkState.name}",
            12f, Palette.TEXT_SECONDARY
        ))
        diag.addView(makeText(
            "RSSI: ${if (lastRssi == Int.MIN_VALUE) "—" else "$lastRssi dBm"}   Adapter: ${adapter().displayName}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(5), 0, 0) })
        diag.addView(makeText(
            "Telemetrie-Offsets sind NICHT am realen GT3 Max verifiziert. " +
                "Nur tatsaechlich empfangene Frames werden angezeigt; DEMO-Werte sind als DEMO markiert.",
            11f, Palette.ACCENT_YELLOW
        ).apply { setPadding(0, dp(5), 0, 0) })
        val dumpButton = makePrimaryButton("GATT-DIAGNOSE (LOG)", Palette.ACCENT_BLUE) {
            val e = bleEngine
            if (e == null) {
                addLog(LogCategory.ERROR, "GATT-Diagnose: keine Engine (nicht verbunden).")
            } else {
                e.dumpGatt("manuell")
            }
        }
        diag.addView(dumpButton, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            setMargins(0, dp(8), 0, 0)
        })
        tabContent.addView(diag, cardParams())

        tabContent.addView(makeSectionLabel("LOG-TERMINAL"))
        tabContent.addView(buildLogCard(), cardParams())
    }

    private fun modelEvidence(): String {
        val name = connectedDevice?.name ?: foundDevices.values.firstOrNull()?.name
        if (name.isNullOrBlank()) {
            return "Kein Gerätename vorhanden — es wird bewusst kein Modell angenommen."
        }
        val det = ScooterModel.detectDetailed(name, connectedDevice?.address ?: "")
        return if (det.model.isKnown) {
            "Vertrauensstufe: ${det.confidence.label} (${det.evidence}). " +
                "${det.confidence.description} Reine Namensheuristik — keine Hardware-Verifikation."
        } else {
            "\"$name\" enthält kein eindeutiges Modell-Token. " +
                "Ein Gerät mit \"NAVEE\" im Namen wird NICHT automatisch als GT3 Max eingestuft."
        }
    }

    private fun missingPermissions(): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
        val bt = bluetoothAdapter
        if (bt == null) {
            toast("Bluetooth ist auf diesem Gerät nicht verfügbar.")
            return
        }
        if (!bt.isEnabled) {
            toast("Bitte Bluetooth einschalten.")
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        foundDevices.clear()
        updateDeviceList()
        isScanning = true
        if (::scanButton.isInitialized) {
            scanButton.isEnabled = false
            scanButton.text = "SCAN LÄUFT…"
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bt.bluetoothLeScanner?.startScan(null, settings, leScanCallback)
            addLog(LogCategory.INFO, "BLE-Scan gestartet (12 s).")
        } catch (e: SecurityException) {
            isScanning = false
            toast("Scan-Berechtigung fehlt.")
            addLog(LogCategory.ERROR, "Scan verweigert: ${e.message}")
        }
        val scanGeneration = scanTimeoutGuard.begin()
        scanTimeoutRunnable = Runnable {
            // PHASE 5 FIX: Nur der Timeout des AKTUELLEN Scans darf wirken.
            // Ein veraltetes Runnable (aus einem frueheren Scan) wird verworfen,
            // statt einen neuen Scan vorzeitig zu beenden.
            if (!scanTimeoutGuard.isCurrent(scanGeneration)) return@Runnable
            safeStopBleScan()
            addLog(LogCategory.INFO, "BLE-Scan beendet.")
        }
        // Nur planen, wenn der Scan wirklich laeuft (z. B. kein Start nach
        // verweigerter Berechtigung).
        if (isScanning) {
            handler.postDelayed(scanTimeoutRunnable!!, 12_000L)
        }
    }

    private fun updateDeviceList() {
        if (!::deviceListContainer.isInitialized) return
        deviceListContainer.removeAllViews()
        if (foundDevices.isEmpty()) {
            deviceListContainer.addView(makeText(
                if (isScanning) "Suche läuft…" else "Noch keine Geräte gefunden.",
                12f, Palette.TEXT_MUTED
            ).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        val sorted = foundDevices.values.sortedWith(
            compareByDescending<ScooterDevice> { it.isScooterCandidate }
                .thenByDescending { it.rssi }
        )
        for (device in sorted) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(Palette.SURFACE)
            }
            val det = ScooterModel.detectDetailed(device.name, device.address)
            row.addView(makeText(device.name, 14f, Palette.TEXT_PRIMARY, true))
            val modelLine = if (det.model.isKnown)
                "${det.model.displayName} (${det.confidence.label})"
            else "Modell unklar (${det.confidence.label})"
            row.addView(makeText(
                "${device.address} · ${device.rssi} dBm · $modelLine",
                11f, Palette.TEXT_MUTED
            ))
            val connected = connectedDevice?.address == device.address
            val connectButton = makePrimaryButton(
                if (connected) "VERBUNDEN" else "VERBINDEN",
                Palette.ACCENT_BLUE
            ) { connectToDevice(device) }
            connectButton.isEnabled = !connected
            row.addView(connectButton, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                setMargins(0, dp(6), 0, 0)
            })
            deviceListContainer.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, dp(4), 0, 0)
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: ScooterDevice) {
        val bt = device.device
        if (bt == null) {
            toast("Demo-Gerät: es wird keine echte Verbindung aufgebaut.")
            return
        }
        safeStopBleScan()
        // FIX: Ein noch geplanter Auto-Reconnect darf einen manuellen Verbindungs-
        // aufbau nicht ueberholen. Sonst feuert der alte Runnable spaeter gegen die
        // NEUE Engine und baut eine zweite, konkurrierende GATT-Verbindung auf.
        cancelReconnectRunnable()
        // PHASE 5 FIX: Beim Wechsel auf ein ANDERES Geraet darf keine zweite
        // BleEngine parallel existieren. Die bisherige Verbindung wird sauber
        // abgebaut (sonst: zwei GATT-Clients, doppelte Telemetrie, Leak).
        if (ConnectionSwitchPolicy.mustTearDownExisting(bleEngine != null)) {
            teardownActiveConnection()
        }
        val det = ScooterModel.detectDetailed(device.name, device.address)
        detectedModel = det.model
        detectedConfidence = det.confidence
        detectionEvidence = det.evidence
        activeProtocol = detectedModel.protocol
        connectedDevice = device
        userInitiatedDisconnect = false
        reconnectAttempts = 0
        addLog(LogCategory.INFO, "Verbinde mit ${device.name} (${device.address})…")
        if (::statusText.isInitialized) statusText.text = "Verbinde…"
        val engine = BleEngine(this, this)
        bleEngine = engine
        engine.setProtocolHint(activeProtocol)
        linkState = linkState.transition(BleLinkEvent.CONNECT_REQUESTED)
        engine.connect(bt)
        updateDeviceList()
    }

    /* ================================================================== */
    /*  TOOLS / TUNING                                                    */
    /* ================================================================== */

    private fun renderTools() {
        tabContent.addView(makeSectionLabel("FAHRPARAMETER"))
        val maxLimit = if (detectedModel.isKnown) detectedModel.absMaxKmh.coerceAtMost(45) else 45
        tabContent.addView(makeSliderCard(
            "Max. Geschwindigkeit",
            "Speed-Ceiling am Controller. In Deutschland sind 20 km/h (eKFV) zulässig.",
            5, maxLimit, maxSpeedKmh.coerceIn(5, maxLimit), "km/h"
        ) { value ->
            maxSpeedKmh = value
            sendCommand(adapter().buildSpeedCeilingFrame(value), "maxSpeed=$value", true)
        }, cardParams())

        tabContent.addView(makeSliderCard(
            "Zero-Start",
            "Anfahrgeschwindigkeit ohne Gas. 0 = sofortiges Ansprechen.",
            0, 10, zeroStartKmh.coerceIn(0, 10), "km/h"
        ) { value ->
            zeroStartKmh = value
            sendCommand(adapter().buildZeroStartFrame(value), "zeroStart=$value", true)
        }, cardParams())

        tabContent.addView(makeSliderCard(
            "KERS-Rekuperation",
            "Stärke der Motorbremse / Energierückgewinnung.",
            0, 3, kersLevel.coerceIn(0, 3), "Stufe"
        ) { value ->
            kersLevel = value
            sendCommand(adapter().buildKersFrame(value), "kers=$value", true)
        }, cardParams())

        tabContent.addView(makeSliderCard(
            "Motor-Temperaturschutz",
            "Grenzwert, ab dem die Leistung reduziert wird.",
            50, 90, motorTempGuardC.coerceIn(50, 90), "°C"
        ) { value ->
            motorTempGuardC = value
            sendCommand(adapter().buildTempGuardFrame(value), "tempGuard=$value", true)
        }, cardParams())

        tabContent.addView(makeSectionLabel("SCHALTER"))
        val toggles = makeCard()
        toggles.addView(makeToggleRow(
            "Cruise Control", "Hält die Geschwindigkeit auf Tastendruck.", cruiseEnabled
        ) { value ->
            cruiseEnabled = value
            sendCommand(adapter().buildCruiseFrame(value), "cruise=$value", true)
        })
        toggles.addView(makeToggleRow(
            "Diebstahl-Sperre", "Sperrt den Motor elektronisch.", isLocked
        ) { value ->
            isLocked = value
            repository.isLocked = value
            sendCommand(adapter().buildLockFrame(value), "lock=$value", true)
        })
        toggles.addView(makeToggleRow(
            "Übergeschwindigkeits-Alarm stumm", "Unterdrückt den akustischen Alarm.", overspeedAlarmMuted
        ) { value ->
            overspeedAlarmMuted = value
            sendCommand(adapter().buildOverspeedMuteFrame(value), "overspeedMute=$value", true)
        })
        toggles.addView(makeToggleRow(
            "Motor-Temp.-Guard aktiv", "Reduziert die Leistung bei Überhitzung.", motorTempGuardEnabled
        ) { value -> motorTempGuardEnabled = value })
        tabContent.addView(toggles, cardParams())

        tabContent.addView(makeSectionLabel("PROFILE & SPEZIALFUNKTIONEN"))
        val special = makeCard()
        special.addView(makeText(
            "German Maneuver ist ein volatiler RAM-Eingriff. Er kann die Betriebserlaubnis " +
                "erlöschen lassen und wird nur auf eigenem Testgerät empfohlen.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, 0, 0, dp(8)) })
        special.addView(makePrimaryButton("GERMAN MANEUVER (VOLATIL)", Palette.ACCENT_ORANGE) {
            confirmGermanManeuver()
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        special.addView(makePrimaryButton("POLIZEI-MODUS (LEGALES LIMIT)", Palette.ACCENT_BLUE) {
            confirmPoliceMode()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        special.addView(makePrimaryButton("PANIK / NOTAUS-RAMPE", Palette.ACCENT_RED) {
            confirmPanic()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        tabContent.addView(special, cardParams())

        tabContent.addView(makeSectionLabel("FAHRMODUS"))
        val ride = makeCard()
        val rideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (mode in RideMode.values()) {
            val button = makePrimaryButton(
                mode.displayName,
                if (mode == rideMode) Palette.ACCENT_GREEN else Palette.SURFACE
            ) {
                rideMode = mode
                applyRideMode(mode)
                showTab(Tab.TOOLS)
            }
            rideRow.addView(button, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        ride.addView(rideRow)
        ride.addView(makeText(rideMode.description, 12f, Palette.TEXT_MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
        tabContent.addView(ride, cardParams())

        tabContent.addView(makeText(
            "Hinweis: Alle Schreibbefehle werden nur gesendet, wenn eine echte BLE-Verbindung " +
                "besteht und der Adapter sie unterstützt. Ohne ACK wird der Befehl als unsicher " +
                "protokolliert.",
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    private fun applyRideMode(mode: RideMode) {
        when (mode) {
            RideMode.NORMAL -> sendCommand(adapter().buildSpeedCeilingFrame(maxSpeedKmh), "ride=normal", true)
            RideMode.ECO_SAVER -> {
                val ecoLimit = currentRegion.legalSpeedKmh.coerceAtMost(maxSpeedKmh)
                sendCommand(adapter().buildSpeedCeilingFrame(ecoLimit), "ride=eco", true)
            }
            RideMode.SPORT_PLUS -> sendCommand(adapter().buildMaxPowerFrame(), "ride=sport+", true)
        }
        addLog(LogCategory.INFO, "Fahrmodus: ${mode.displayName}")
    }

    private fun confirmGermanManeuver() {
        AlertDialog.Builder(this)
            .setTitle("German Maneuver")
            .setMessage(
                "Dieser Befehl ist ein volatiler RAM-Eingriff. Er kann die Betriebserlaubnis " +
                    "erlöschen lassen. Nur auf eigenem Testgerät und nicht im öffentlichen " +
                    "Verkehr ausführen. Fortfahren?"
            )
            .setPositiveButton("Ausführen") { _, _ ->
                sendCommand(adapter().buildGermanManeuverFrame(), "germanManeuver", true)
                germanManeuverActive = true
                addLog(LogCategory.INFO, "German Maneuver gesendet (volatil, nicht verifiziert).")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmPoliceMode() {
        AlertDialog.Builder(this)
            .setTitle("Polizei-Modus")
            .setMessage(
                "Setzt das Speed-Ceiling auf ${currentRegion.legalSpeedKmh} km/h " +
                    "(${currentRegion.displayName}). Fortfahren?"
            )
            .setPositiveButton("Setzen") { _, _ ->
                sendCommand(
                    adapter().buildPoliceModeFrame(currentRegion.legalSpeedKmh),
                    "policeMode=${currentRegion.legalSpeedKmh}", true
                )
                maxSpeedKmh = currentRegion.legalSpeedKmh
                addLog(LogCategory.INFO, "Polizei-Modus auf ${currentRegion.legalSpeedKmh} km/h gesetzt.")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmPanic() {
        AlertDialog.Builder(this)
            .setTitle("Panik / Notaus")
            .setMessage(
                "Sendet sofort einen Emergency-Cut und rampt das Speed-Ceiling auf " +
                    "${RAMP_CUT_SPEED_KMH.toInt()} km/h. Dies ersetzt NICHT die mechanische Bremse. " +
                    "Ausführen?"
            )
            .setPositiveButton("NOTAUS") { _, _ -> beginSafeStopRamp("Manueller Panik-Button") }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun sendCommand(
        frame: ByteArray,
        label: String,
        requiresUnlock: Boolean,
        silent: Boolean = false
    ) {
        val engine = bleEngine
        if (engine == null || !engineReady) {
            if (!silent) toast("Keine aktive Verbindung — Befehl nicht gesendet.")
            return
        }
        val queued = engine.enqueueCommand(frame, label, requiresUnlock)
        if (!queued) {
            addLog(LogCategory.ERROR, "Befehl '$label' konnte nicht eingereiht werden.")
        } else if (!silent) {
            addLog(LogCategory.TX, "$label → ${ScooterFrames.toHex(frame)}")
        }
    }

    /* ================================================================== */
    /*  DASHBOARD / TELEMETRIE                                            */
    /* ================================================================== */

    private fun metricRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(makeText(label, 12f, Palette.TEXT_MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = makeText("—", 14f, Palette.TEXT_PRIMARY, true)
        row.addView(value)
        parent.addView(row)
        return value
    }

    private fun renderDashboard() {
        tabContent.addView(makeSectionLabel("LIVE-TELEMETRIE"))
        val live = makeCard()
        dashSpeed = metricRow(live, "Geschwindigkeit")
        dashPower = metricRow(live, "Leistung")
        dashVoltage = metricRow(live, "Spannung")
        dashCurrent = metricRow(live, "Strom")
        dashBattery = metricRow(live, "Akku")
        dashMotorTemp = metricRow(live, "Motor-Temperatur")
        dashCellMin = metricRow(live, "Zelle min")
        dashCellMax = metricRow(live, "Zelle max")
        dashCellDelta = metricRow(live, "Zell-Delta")
        dashTempGuardState = metricRow(live, "Temp-Guard")
        dashFeedState = metricRow(live, "Datenquelle")
        tabContent.addView(live, cardParams())

        tabContent.addView(makeSectionLabel("TRIP-COMPUTER"))
        val tripCard = makeCard()
        dashTrip = metricRow(tripCard, "Strecke")
        dashRange = metricRow(tripCard, "Reichweite (Schätzung)")
        dashVmax = metricRow(tripCard, "Vmax")
        dashVavg = metricRow(tripCard, "Vavg")
        dashWh = metricRow(tripCard, "Energie")
        dashSoh = metricRow(tripCard, "SoH")
        dashCycles = metricRow(tripCard, "Zyklen")
        tabContent.addView(tripCard, cardParams())

        tabContent.addView(makeSectionLabel("ZELLSPANNUNGEN"))
        val cells = makeCard()
        cellGraphRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(6), 0, dp(6))
        }
        cells.addView(cellGraphRow, LinearLayout.LayoutParams(MATCH, dp(90)))
        tabContent.addView(cells, cardParams())

        val actions = makeCard()
        actions.addView(makePrimaryButton("TRIP ZURÜCKSETZEN", Palette.ACCENT_BLUE) {
            repository.trip.reset()
            updateDashboardUi()
            addLog(LogCategory.INFO, "Trip zurückgesetzt.")
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        actions.addView(makePrimaryButton(
            if (demoActive) "DEMO-SIMULATION STOPPEN" else "DEMO-SIMULATION STARTEN",
            Palette.ACCENT_ORANGE
        ) {
            if (demoActive) stopTelemetry() else startDemoTelemetry()
            showTab(Tab.DASHBOARD)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        tabContent.addView(actions, cardParams())

        updateDashboardUi()
    }

    private fun startTelemetry() {
        stopTelemetry()
        if (!engineReady) return
        telemetryRunnable = object : Runnable {
            override fun run() {
                if (!engineReady) return
                sendCommand(adapter().buildReadStatusFrame(), "poll", false, silent = true)
                handler.postDelayed(this, 1_000L)
            }
        }
        handler.post(telemetryRunnable!!)
        addLog(LogCategory.INFO, "Telemetrie-Polling gestartet.")
    }

    private fun startDemoTelemetry() {
        stopTelemetry()
        demoActive = true
        simTick = 0
        telemetryRunnable = object : Runnable {
            override fun run() {
                if (!demoActive) return
                simTick++
                val speed = (14 + 8 * kotlin.math.sin(simTick / 4.0)).toFloat().coerceAtLeast(0f)
                val voltage = 48.2f - (simTick % 60) * 0.01f
                val current = 6.5f + 3f * kotlin.math.sin(simTick / 3.0).toFloat()
                val telemetry = Telemetry(
                    speedKmh = speed,
                    voltageV = voltage,
                    currentA = current,
                    batteryPercent = (92 - simTick / 12).coerceAtLeast(5),
                    motorTempC = 34f + (simTick % 20) * 0.4f,
                    cellMinV = 3.62f,
                    cellMaxV = 3.68f,
                    cellVoltages = List(13) { 3.62f + (it % 4) * 0.015f },
                    designCapacityMah = 15_000,
                    fullChargeCapacityMah = 14_400,
                    cycleCount = 42,
                    rawHex = "DEMO",
                    source = activeProtocol
                )
                lastTelemetry = telemetry
                updateTrip(telemetry)
                scheduleTelemetryApply()
                handler.postDelayed(this, 500L)
            }
        }
        handler.post(telemetryRunnable!!)
        addLog(LogCategory.INFO, "Demo-Simulation gestartet (keine echten Gerätedaten).")
    }

    private fun stopTelemetry() {
        telemetryRunnable?.let { handler.removeCallbacks(it) }
        telemetryRunnable = null
        demoActive = false
    }

    private fun scheduleTelemetryApply() {
        if (telemetryApplyScheduled) return
        telemetryApplyScheduled = true
        val now = System.currentTimeMillis()
        val delay = (UI_REFRESH_INTERVAL_MS - (now - lastTelemetryApply)).coerceAtLeast(0L)
        handler.postDelayed({
            telemetryApplyScheduled = false
            lastTelemetryApply = System.currentTimeMillis()
            updateDashboardUi()
        }, delay)
    }

    private fun updateTrip(t: Telemetry) {
        val speed = t.speedKmh ?: return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val trip = repository.trip
        if (trip.startMillis == 0L) trip.startMillis = nowWall
        // FIX: Integration ausschliesslich ueber die MONOTONE Uhr. Ein
        // Wanduhr-Sprung oder eine lange Pause (App im Hintergrund, Standby)
        // darf die Strecke/Energie nicht aufblaehen (siehe TripAccumulator).
        val dtHours = TripAccumulator.deltaHours(trip.lastElapsedMillis, nowElapsed)
        if (dtHours > 0.0) {
            trip.distanceKm += speed * dtHours
            val voltage = t.voltageV
            val current = t.currentA
            if (voltage != null && current != null) {
                trip.energyWh += voltage * current * dtHours
            }
        }
        trip.lastElapsedMillis = nowElapsed
        trip.lastMillis = nowWall
        if (speed > trip.maxSpeedKmh) trip.maxSpeedKmh = speed.toDouble()
        trip.speedSum += speed
        trip.speedSamples++
    }

    private fun updateDashboardUi() {
        val t = lastTelemetry
        val trip = repository.trip
        dashSpeed?.text = t?.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—"
        dashPower?.text = if (t?.voltageV != null && t.currentA != null) {
            String.format(Locale.US, "%.0f W", t.voltageV * t.currentA)
        } else "—"
        dashVoltage?.text = t?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—"
        dashCurrent?.text = t?.currentA?.let { String.format(Locale.US, "%.2f A", it) } ?: "—"
        dashBattery?.text = t?.batteryPercent?.let { "$it %" } ?: "—"
        dashMotorTemp?.text = t?.motorTempC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"
        dashCellMin?.text = t?.cellMinV?.let { String.format(Locale.US, "%.3f V", it) } ?: "—"
        dashCellMax?.text = t?.cellMaxV?.let { String.format(Locale.US, "%.3f V", it) } ?: "—"
        dashCellDelta?.text = if (t?.cellMinV != null && t.cellMaxV != null) {
            String.format(Locale.US, "%.0f mV", (t.cellMaxV - t.cellMinV) * 1000)
        } else "—"
        dashTempGuardState?.text = when {
            motorGuardTripped -> "AUSGELÖST"
            motorTempGuardEnabled -> "aktiv (${motorTempGuardC} °C)"
            else -> "inaktiv"
        }
        dashFeedState?.text = when {
            demoActive -> "DEMO (simuliert)"
            engineReady -> "live (BLE)"
            else -> "keine Daten"
        }
        dashTrip?.text = String.format(Locale.US, "%.2f km", trip.distanceKm)
        dashVmax?.text = String.format(Locale.US, "%.1f km/h", trip.maxSpeedKmh)
        dashVavg?.text = String.format(Locale.US, "%.1f km/h", trip.avgSpeedKmh)
        dashWh?.text = String.format(Locale.US, "%.1f Wh", trip.energyWh)
        dashSoh?.text = if (t?.designCapacityMah != null && t.fullChargeCapacityMah != null &&
            t.designCapacityMah > 0
        ) {
            String.format(Locale.US, "%.1f %%",
                t.fullChargeCapacityMah.toDouble() / t.designCapacityMah * 100.0)
        } else "—"
        dashCycles?.text = t?.cycleCount?.toString() ?: "—"
        // FIX: Bei unbekanntem Modell wird KEINE Reichweite geschaetzt. Der
        // frueher implizite 36-V-Default war fuer 48-V-Geraete (NAVEE) falsch.
        dashRange?.text = RangeEstimator.estimateKm(detectedModel, t?.batteryPercent)
            ?.let { String.format(Locale.US, "%.1f km", it) } ?: "—"

        cellGraphRow?.let { row ->
            row.removeAllViews()
            val cells = t?.cellVoltages
            if (cells.isNullOrEmpty()) {
                row.addView(makeText("Keine Zellwerte empfangen.", 11f, Palette.TEXT_MUTED))
            } else {
                val min = cells.minOrNull() ?: 3.0f
                val max = cells.maxOrNull() ?: 4.2f
                val span = (max - min).coerceAtLeast(0.05f)
                for (v in cells) {
                    val height = dp(20) + ((v - min) / span * dp(60)).toInt()
                    val bar = View(this).apply { setBackgroundColor(Palette.ACCENT_GREEN) }
                    row.addView(bar, LinearLayout.LayoutParams(0, height, 1f).apply {
                        setMargins(dp(1), 0, dp(1), 0)
                    })
                }
            }
        }
    }

    /* ================================================================== */
    /*  FLASH / FIRMWARE                                                  */
    /* ================================================================== */

    private fun renderFlash() {
        tabContent.addView(makeSectionLabel("FIRMWARE-QUELLE"))
        val sourceCard = makeCard()
        for (source in FirmwareSource.values()) {
            val selected = source == selectedSource
            val button = makePrimaryButton(
                source.displayName,
                if (selected) Palette.ACCENT_GREEN else Palette.SURFACE
            ) {
                selectedSource = source
                selectedFirmware = null
                lastValidation = null
                loadedFirmwareBytes = null
                showTab(Tab.FLASH)
            }
            sourceCard.addView(button, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, 0, 0, dp(4))
            })
        }
        sourceCard.addView(makeText(selectedSource.reference, 11f, Palette.TEXT_MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
        tabContent.addView(sourceCard, cardParams())

        tabContent.addView(makeSectionLabel("KATALOG (${detectedModel.displayName})"))
        val catalogCard = makeCard()
        val packages = FirmwareCatalog.fromSource(selectedSource, detectedModel)
        if (packages.isEmpty()) {
            catalogCard.addView(makeText("Keine Pakete für dieses Modell.", 12f, Palette.TEXT_MUTED))
        }
        for (pkg in packages) {
            val selected = pkg.id == selectedFirmware?.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(if (selected) Palette.SURFACE_DEEP else Palette.SURFACE)
                setOnClickListener {
                    selectedFirmware = pkg
                    lastValidation = null
                    loadedFirmwareBytes = null
                    showTab(Tab.FLASH)
                }
            }
            row.addView(makeText("${pkg.name} · ${pkg.version}", 13f, Palette.TEXT_PRIMARY, true))
            row.addView(makeText("${pkg.target} · empfohlen ${pkg.recommendedMaxKmh} km/h", 11f, Palette.TEXT_MUTED))
            row.addView(makeText(pkg.notes, 11f, Palette.TEXT_SECONDARY))
            row.addView(makeText(
                if (pkg.expectedSha256 == null) "SHA-256: NICHT verifiziert" else "SHA-256: hinterlegt",
                11f, if (pkg.expectedSha256 == null) Palette.ACCENT_ORANGE else Palette.ACCENT_GREEN
            ))
            catalogCard.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, dp(4), 0, 0)
            })
        }
        tabContent.addView(catalogCard, cardParams())

        tabContent.addView(makeSectionLabel("LOKALE DATEI PRÜFEN"))
        val fileCard = makeCard()
        fileCard.addView(makeText(
            if (selectedFirmwareName == null) "Keine Datei geladen."
            else "Datei: $selectedFirmwareName",
            12f, Palette.TEXT_SECONDARY
        ))
        fileCard.addView(makePrimaryButton("DATEI AUSWÄHLEN", Palette.ACCENT_BLUE) {
            firmwarePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        lastValidation?.let { validation ->
            fileCard.addView(makeText(
                (if (validation.valid) "OK: " else "GESPERRT: ") + validation.message,
                12f, if (validation.valid) Palette.ACCENT_GREEN else Palette.ACCENT_RED
            ).apply { setPadding(0, dp(8), 0, 0) })
            fileCard.addView(makeText("SHA-256: ${validation.sha256}", 10f, Palette.TEXT_MUTED))
            fileCard.addView(makeText(
                "Hash verifiziert: ${if (validation.hashVerified) "JA" else "NEIN"} · " +
                    "Modellkompatibel: ${if (validation.modelCompatible) "JA" else "NEIN"}",
                11f, Palette.TEXT_MUTED
            ))
        }
        tabContent.addView(fileCard, cardParams())

        tabContent.addView(makeSectionLabel("FLASH-MODUS"))
        val modeCard = makeCard()
        for (mode in FlashMode.values()) {
            val selected = mode == flashMode
            modeCard.addView(makePrimaryButton(
                mode.displayName,
                if (selected) Palette.ACCENT_ORANGE else Palette.SURFACE
            ) {
                flashMode = mode
                showTab(Tab.FLASH)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(4)) })
        }
        modeCard.addView(makeText(flashMode.description, 12f, Palette.TEXT_MUTED))
        tabContent.addView(modeCard, cardParams())

        tabContent.addView(makeSectionLabel("FLASH"))
        val flashCard = makeCard()
        flashStatusText = makeText(
            "Gesperrt, solange kein verifizierter SHA-256 und keine belegte Modellkompatibilität vorliegen.",
            12f, Palette.ACCENT_ORANGE
        )
        flashCard.addView(flashStatusText)
        flashProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        flashCard.addView(flashProgressBar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(0, dp(8), 0, dp(8))
        })
        flashCard.addView(makePrimaryButton("SOFT-PATCH SENDEN", Palette.ACCENT_GREEN) {
            applySoftPatch()
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        flashCard.addView(makePrimaryButton("HARD-FLASH STARTEN (GESPERRT)", Palette.SURFACE) {
            startHardFlashIfVerified()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        tabContent.addView(flashCard, cardParams())

        tabContent.addView(makeText(
            "Sicherheit: Firmware wird nur geschrieben, wenn ein hinterlegter SHA-256 " +
                "übereinstimmt und die Modellkompatibilität belegt ist. Der Katalog enthält " +
                "bewusst keine erfundenen Download-URLs oder Prüfsummen.",
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    private fun applySoftPatch() {
        val pkg = selectedFirmware ?: run {
            toast("Bitte zuerst ein Katalog-Paket auswählen.")
            return
        }
        if (!engineReady) {
            toast("Keine aktive Verbindung.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Soft-Patch senden")
            .setMessage(
                "Es wird ein BLE-Konfigurationsbefehl gesendet, KEINE Firmware geschrieben. " +
                    "Ziel: ${pkg.name} (${pkg.recommendedMaxKmh} km/h). Fortfahren?"
            )
            .setPositiveButton("Senden") { _, _ ->
                sendCommand(
                    adapter().buildSpeedCeilingFrame(pkg.recommendedMaxKmh),
                    "softPatch=${pkg.id}", true
                )
                addLog(LogCategory.INFO, "Soft-Patch '${pkg.name}' gesendet.")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startHardFlashIfVerified() {
        val bytes = loadedFirmwareBytes
        val validation = lastValidation
        if (bytes == null || validation == null || !validation.valid) {
            toast("Keine gültige, geprüfte Firmware geladen.")
            return
        }
        if (!validation.hashVerified) {
            toast("Hard-Flash gesperrt: SHA-256 nicht verifiziert.")
            addLog(LogCategory.ERROR, "Hard-Flash verweigert: kein verifizierter Hash.")
            return
        }
        if (!detectedModel.isKnown) {
            toast("Hard-Flash gesperrt: Modell nicht erkannt.")
            return
        }
        if (!engineReady) {
            toast("Hard-Flash gesperrt: keine aktive Verbindung.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("HARD-FLASH — letzte Warnung")
            .setMessage(
                "Es werden ${bytes.size} Bytes dauerhaft in den Controller geschrieben. " +
                    "Ein Abbruch kann das Gerät unbrauchbar machen. Nur mit bestätigter " +
                    "Hardware-/Firmware-Kompatibilität und Recovery-Pfad fortfahren."
            )
            .setPositiveButton("FLASHEN") { _, _ ->
                addLog(LogCategory.FLASH, "Hard-Flash gestartet: ${bytes.size} Bytes")
                bleEngine?.startFlash(bytes, adapter().buildUnlockFrame(), adapter().buildRebootFrame())
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun handleSelectedFirmware(uri: Uri) {
        val name = queryDisplayName(uri) ?: "firmware.bin"
        val bytes = readLimited(uri, MAX_FIRMWARE_BYTES)
        if (bytes == null || bytes.isEmpty()) {
            toast("Datei konnte nicht gelesen werden oder ist leer.")
            addLog(LogCategory.ERROR, "Firmware-Datei nicht lesbar: $name")
            return
        }
        selectedFirmwareName = name
        val validation = validateFirmware(bytes, name)
        lastValidation = validation
        loadedFirmwareBytes = validation.extractedBinary
        addLog(
            if (validation.valid) LogCategory.FLASH else LogCategory.ERROR,
            "Firmware-Prüfung '$name': ${validation.message}"
        )
        showTab(Tab.FLASH)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun readLimited(uri: Uri, limit: Int): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > limit) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun validateFirmware(bytes: ByteArray, name: String): FirmwareValidation =
        FirmwareValidator.validate(
            bytes = bytes,
            fileName = name,
            catalogName = selectedFirmware?.name,
            model = detectedModel,
            expectedSha256 = selectedFirmware?.expectedSha256
        )

    private fun renderConfig() {
        tabContent.addView(makeSectionLabel("REGION / LIMIT"))
        val regionCard = makeCard()
        regionCard.addView(makeText(
            "Aktuell: ${currentRegion.displayName} · ${currentRegion.legalSpeedKmh} km/h",
            13f, Palette.TEXT_SECONDARY
        ))
        val regionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (region in Region.values()) {
            val button = makePrimaryButton(
                region.code,
                if (region == currentRegion) Palette.ACCENT_GREEN else Palette.SURFACE
            ) {
                currentRegion = region
                repository.region = region
                addLog(LogCategory.INFO, "Region: ${region.displayName} (${region.legalSpeedKmh} km/h)")
                showTab(Tab.CONFIG)
            }
            regionRow.addView(button, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        regionCard.addView(regionRow, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(0, dp(8), 0, 0)
        })
        tabContent.addView(regionCard, cardParams())

        tabContent.addView(makeSectionLabel("VERBINDUNG"))
        val connCard = makeCard()
        connCard.addView(makeToggleRow(
            "Auto-Reconnect", "Versucht nach einem unerwarteten Abbruch die Verbindung wiederherzustellen.",
            autoReconnectEnabled
        ) { value ->
            autoReconnectEnabled = value
            repository.autoReconnect = value
            // PHASE 7: Abschalten wirkt sofort — ein bereits geplantes
            // Reconnect-Runnable wird verworfen, statt spaeter doch zu verbinden.
            if (!value) cancelReconnectRunnable()
        })
        tabContent.addView(connCard, cardParams())

        tabContent.addView(makeSectionLabel("PIN-SCHUTZ"))
        val pinCard = makeCard()
        pinCard.addView(makeText(
            if (repository.hasPin) "PIN ist gesetzt." else "Keine PIN gesetzt.",
            12f, Palette.TEXT_SECONDARY
        ))
        pinCard.addView(makePrimaryButton("PIN SETZEN / ÄNDERN", Palette.ACCENT_BLUE) {
            promptSetPin()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        if (repository.hasPin) {
            pinCard.addView(makePrimaryButton("PIN ENTFERNEN", Palette.ACCENT_RED) {
                promptClearPin()
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        }
        tabContent.addView(pinCard, cardParams())

        tabContent.addView(makeSectionLabel("TUNING-PROFILE"))
        val profileCard = makeCard()
        profileCard.addView(makePrimaryButton("AKTUELLES PROFIL SPEICHERN", Palette.ACCENT_GREEN) {
            promptSaveProfile()
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val invalidProfiles = repository.invalidProfileCount()
        if (invalidProfiles > 0) {
            // Ungueltige Alt-Eintraege werden bewusst NICHT geloescht (sie
            // koennten zu einem anderen Profil gehoeren) — aber sie sollen
            // auch nicht unsichtbar sein.
            profileCard.addView(makeText(
                "$invalidProfiles gespeicherte(r) Eintrag/Eintraege ist/sind unlesbar und " +
                    "werden uebersprungen. Sie bleiben erhalten und blockieren keine anderen Profile.",
                11f, Palette.ACCENT_YELLOW
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        for (profile in ProfileStore.decodeAll(repository.profileEntries())) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            row.addView(makePrimaryButton("LADEN: ${profile.name}", Palette.SURFACE) {
                maxSpeedKmh = profile.maxSpeed
                zeroStartKmh = profile.zeroStart
                kersLevel = profile.kers
                cruiseEnabled = profile.cruiseControl
                addLog(LogCategory.INFO, "Profil geladen: ${profile.name}")
                showTab(Tab.CONFIG)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(makePrimaryButton("L\u00d6SCHEN", Palette.ACCENT_RED) {
                confirmDeleteProfile(profile.name)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(6), 0, 0, 0) })
            profileCard.addView(row)
        }
        tabContent.addView(profileCard, cardParams())

        tabContent.addView(makeSectionLabel("LOG-TERMINAL"))
        tabContent.addView(buildLogCard(), cardParams())

        tabContent.addView(makeSectionLabel("ÜBER"))
        val about = makeCard()
        about.addView(makeText(
            "Syrak ScooterLab v7 · Diagnose ohne erfundene Firmware-Endpunkte. " +
                "Alle Telemetrie-Offsets sind als unverifiziert dokumentiert. " +
                "Ein Gerät gilt erst als verbunden, wenn onReady vom GATT-Layer kommt.",
            12f, Palette.TEXT_MUTED
        ))
        tabContent.addView(about, cardParams())
    }

    /**
     * Speichert das aktuelle Tuning als Profil unter einem Namen.
     *
     * PHASE 4: Vorher wurde der Name automatisch als "Profil N" vergeben. Ein
     * vorhandenes Profil liess sich damit nie ueberschreiben (N zaehlte immer
     * hoch) und ein sinnvoller Name war nicht moeglich. Der Vorschlag bleibt
     * derselbe, ist aber aenderbar; gleicher Name = Ueberschreiben.
     */
    private fun promptSaveProfile() {
        val suggestion = "Profil ${repository.profileEntries().size + 1}"
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "Profilname"
            setText(suggestion)
            setSelection(suggestion.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Profil speichern")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Profilname darf nicht leer sein.")
                    return@setPositiveButton
                }
                val existed = ProfileStore.contains(repository.profileEntries(), name)
                repository.saveProfile(
                    ScooterProfile(name, maxSpeedKmh, zeroStartKmh, kersLevel, cruiseEnabled)
                )
                addLog(
                    LogCategory.INFO,
                    if (existed) "Profil ueberschrieben: $name" else "Profil gespeichert: $name"
                )
                showTab(Tab.CONFIG)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmDeleteProfile(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Profil loeschen")
            .setMessage("Profil \"$name\" wirklich loeschen?")
            .setPositiveButton("Loeschen") { _, _ ->
                if (repository.deleteProfile(name)) {
                    addLog(LogCategory.INFO, "Profil geloescht: $name")
                } else {
                    addLog(LogCategory.ERROR, "Profil nicht gefunden: $name")
                }
                showTab(Tab.CONFIG)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun promptSetPin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Neue PIN (mind. 4 Ziffern)"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN setzen")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                val pin = input.text.toString()
                if (pin.length < 4) {
                    toast("PIN muss mindestens 4 Ziffern haben.")
                } else {
                    repository.setPin(pin)
                    addLog(LogCategory.INFO, "PIN gesetzt.")
                    showTab(Tab.CONFIG)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun promptClearPin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Aktuelle PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN entfernen")
            .setView(input)
            .setPositiveButton("Entfernen") { _, _ ->
                if (repository.verifyPin(input.text.toString())) {
                    repository.clearPin()
                    addLog(LogCategory.INFO, "PIN entfernt.")
                    showTab(Tab.CONFIG)
                } else {
                    toast("Falsche PIN.")
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /* ================================================================== */
    /*  LOG-TERMINAL                                                      */
    /* ================================================================== */

    private fun buildLogCard(): LinearLayout {
        val card = makeCard()
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow.addView(makeText("Filter:", 11f, Palette.TEXT_MUTED))
        val all = makeText("ALLE", 10f, Palette.ACCENT_GREEN, true).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { logFilter = null; refreshLogView() }
        }
        filterRow.addView(all)
        for (category in LogCategory.values()) {
            val item = makeText(category.label, 10f, Palette.TEXT_MUTED, true).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener { logFilter = category; refreshLogView() }
            }
            filterRow.addView(item)
        }
        card.addView(filterRow)
        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logScroll = ScrollView(this).apply { addView(logContainer) }
        card.addView(logScroll, LinearLayout.LayoutParams(MATCH, dp(190)).apply {
            setMargins(0, dp(6), 0, 0)
        })
        refreshLogView()
        return card
    }

    private fun addLog(category: LogCategory, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logEntries.addLast(LogEntry(time, category, text))
        while (logEntries.size > 400) logEntries.removeFirst()
        scheduleLogRefresh()
    }

    private fun scheduleLogRefresh() {
        if (logRefreshScheduled) return
        logRefreshScheduled = true
        val now = System.currentTimeMillis()
        val delay = (UI_REFRESH_INTERVAL_MS - (now - lastLogRefresh)).coerceAtLeast(0L)
        handler.postDelayed({
            logRefreshScheduled = false
            lastLogRefresh = System.currentTimeMillis()
            refreshLogView()
        }, delay)
    }

    private fun refreshLogView() {
        val container = logContainer ?: return
        container.removeAllViews()
        val filter = logFilter
        val entries = logEntries.filter { filter == null || it.category == filter }.takeLast(200)
        if (entries.isEmpty()) {
            container.addView(makeText("Keine Log-Einträge.", 11f, Palette.TEXT_MUTED))
            return
        }
        for (entry in entries) {
            val color = when (entry.category) {
                LogCategory.ERROR -> Palette.ACCENT_RED
                LogCategory.TX -> Palette.ACCENT_CYAN
                LogCategory.RX -> Palette.ACCENT_GREEN
                LogCategory.FLASH -> Palette.ACCENT_ORANGE
                LogCategory.INFO -> Palette.TEXT_SECONDARY
            }
            container.addView(makeText(
                "${entry.time} [${entry.category.label}] ${entry.text}",
                10f, color
            ))
        }
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    /* ================================================================== */
    /*  SYSTEMLEISTE / RECONNECT / NOTAUS-RAMPE                           */
    /* ================================================================== */

    private fun startSystemBar() {
        stopSystemBar()
        sysBarRunnable = object : Runnable {
            override fun run() {
                sysLinkView?.text = linkState.name
                sysMtuView?.text = "MTU ${bleEngine?.currentMtu() ?: "—"}"
                sysQueueView?.text = "Q ${bleEngine?.queueDepth() ?: 0}"
                sysRssiView?.text = if (lastRssi == Int.MIN_VALUE) "RSSI —" else "RSSI $lastRssi"
                if (engineReady) bleEngine?.readRssi()
                handler.postDelayed(this, 1_000L)
            }
        }
        handler.post(sysBarRunnable!!)
    }

    private fun stopSystemBar() {
        sysBarRunnable?.let { handler.removeCallbacks(it) }
        sysBarRunnable = null
    }

    private fun cancelReconnectRunnable() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    /**
     * Baut eine bestehende Verbindung vollstaendig ab, BEVOR eine neue Engine
     * erzeugt wird (Geraetewechsel).
     *
     * PHASE 5 FIX: Zuvor wurde [bleEngine] beim Wechsel einfach ueberschrieben,
     * ohne die alte GATT zu trennen -> zwei parallele Verbindungen. Hier wird die
     * alte Engine getrennt (ohne UI-Callback, da [connectToDevice] den Zustand
     * direkt neu setzt) und der abgeleitete Zustand zurueckgesetzt.
     */
    private fun teardownActiveConnection() {
        bleEngine?.disconnect(notifyListener = false)
        bleEngine = null
        engineReady = false
        stopTelemetry()
        stopBleSession()
        connectedDevice = null
        linkState = BleLinkState.IDLE
    }

    /**
     * Bewusstes Trennen durch den Nutzer.
     *
     * Setzt [userInitiatedDisconnect], damit [scheduleReconnect] NICHTS plant,
     * bricht einen bereits geplanten Reconnect ab und laesst [BleEngine] das
     * Ende melden. Der UI-Reset passiert danach zentral in [onDisconnected].
     */
    private fun manualDisconnect() {
        userInitiatedDisconnect = true
        cancelReconnectRunnable()
        reconnectAttempts = 0
        addLog(LogCategory.INFO, "Verbindung wird auf Wunsch getrennt.")
        bleEngine?.disconnect()
        // Falls keine Engine mehr existiert (z. B. nach Prozess-Neuaufbau):
        // Zustand trotzdem sauber zuruecksetzen, statt "verbunden" anzuzeigen.
        if (bleEngine == null) onDisconnected(false)
    }

    private fun scheduleReconnect() {
        // PHASE 4: Entscheidung und Backoff liegen in [ReconnectPolicy] und sind
        // damit ohne Android testbar. Verhalten unveraendert:
        // kein Reconnect bei deaktivierter Einstellung, nach bewusstem Trennen
        // oder nach Ausschoepfen der Versuche.
        if (!ReconnectPolicy.shouldSchedule(
                autoReconnect = autoReconnectEnabled,
                userInitiated = userInitiatedDisconnect,
                attempts = reconnectAttempts,
                maxAttempts = RECONNECT_MAX_ATTEMPTS
            )
        ) {
            if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
                addLog(LogCategory.ERROR, "Reconnect aufgegeben nach $reconnectAttempts Versuchen.")
            }
            return
        }
        cancelReconnectRunnable()
        val delay = ReconnectPolicy.delayMillis(
            reconnectAttempts, RECONNECT_BACKOFF_BASE_MS, RECONNECT_BACKOFF_MAX_MS
        )
        reconnectAttempts++
        addLog(LogCategory.INFO, "Reconnect in ${delay} ms (Versuch $reconnectAttempts).")
        reconnectRunnable = Runnable {
            // PHASE 7 FIX: Beim Feuern erneut pruefen. Wurde "Auto-Reconnect"
            // waehrend des Backoff-Fensters abgeschaltet oder inzwischen bewusst
            // getrennt, darf KEINE Verbindung aufgebaut werden.
            if (ReconnectPolicy.shouldFire(autoReconnectEnabled, userInitiatedDisconnect)) {
                addLog(LogCategory.INFO, "Reconnect-Versuch…")
                linkState = linkState.transition(BleLinkEvent.CONNECT_REQUESTED)
                bleEngine?.reconnect()
            } else {
                addLog(LogCategory.INFO, "Reconnect verworfen (Auto-Reconnect aus oder bewusst getrennt).")
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun beginSafeStopRamp(reason: String) {
        if (rampActive) return
        rampActive = true
        rampTicks = 0
        rampReason = reason
        addLog(LogCategory.ERROR, "NOTAUS: $reason")
        sendCommand(adapter().buildEmergencyCutFrame(), "emergencyCut", true)
        rampRunnable = object : Runnable {
            override fun run() {
                if (!rampActive) return
                rampTicks++
                val remaining = (maxSpeedKmh - RAMP_CUT_SPEED_KMH)
                val target = (maxSpeedKmh - remaining * rampTicks / RAMP_MAX_TICKS)
                    .toInt().coerceAtLeast(0)
                sendCommand(
                    adapter().buildSpeedCeilingFrame(target),
                    "ramp=$target", true, silent = true
                )
                if (rampTicks >= RAMP_MAX_TICKS) {
                    rampActive = false
                    addLog(LogCategory.INFO, "Notaus-Rampe abgeschlossen (Ziel $target km/h).")
                } else {
                    handler.postDelayed(this, RAMP_TICK_MS)
                }
            }
        }
        handler.post(rampRunnable!!)
    }

    private fun endSafeStopRamp() {
        rampActive = false
        rampRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null
    }

    /* ================================================================== */
    /*  BleEngine.Listener                                                */
    /* ================================================================== */

    /**
     * Startet den Vordergrunddienst, damit Android den Prozess (und damit den
     * GATT-Client in [BleEngine]) beim App-Wechsel oder bei ausgeschaltetem
     * Display nicht sofort freigibt. Der Dienst besitzt die Verbindung NICHT —
     * es entsteht also keine zweite GATT-Verbindung.
     *
     * Ab Android 12 darf ein Vordergrunddienst nicht beliebig aus dem
     * Hintergrund gestartet werden; der Aufruf ist deshalb abgesichert. Er
     * erfolgt hier aus [onReady], also regelmaessig im Vordergrund.
     */
    private fun startBleSession() {
        try {
            BleSessionService.start(
                this,
                "Syrak ScooterLab",
                "Verbindung zu ${detectedModel.displayName} aktiv"
            )
        } catch (e: Exception) {
            addLog(LogCategory.ERROR, "Hintergrunddienst nicht gestartet: ${e.message}")
        }
    }

    private fun stopBleSession() {
        try { BleSessionService.stop(this) } catch (_: Exception) {}
    }

    override fun onLog(line: String) {
        runOnUiThread { addLog(LogCategory.INFO, line) }
    }

    override fun onConnected(address: String) {
        runOnUiThread {
            linkState = linkState.transition(BleLinkEvent.GATT_CONNECTED)
            addLog(LogCategory.INFO, "GATT verbunden: $address")
            if (::statusText.isInitialized) statusText.text = "Verbunden. Service-Discovery läuft…"
        }
    }

    override fun onReady(address: String, protocol: BleProtocol, mtu: Int) {
        runOnUiThread {
            engineReady = true
            activeProtocol = protocol
            linkState = linkState.transition(BleLinkEvent.READY)
            reconnectAttempts = 0
            addLog(LogCategory.INFO, "Bereit: ${protocol.displayName}, MTU $mtu")
            if (::statusText.isInitialized) {
                statusText.text = "Bereit: ${detectedModel.displayName} [${detectedConfidence.label}] (${protocol.displayName})"
            }
            startTelemetry()
            // Verbindung ist vollstaendig bereit -> Prozess fuer App-Wechsel
            // und Display-Off priorisieren (Dienst besitzt die GATT NICHT).
            startBleSession()
            if (currentTab == Tab.INFO) showTab(Tab.INFO)
        }
    }

    override fun onDisconnected(unexpected: Boolean) {
        runOnUiThread {
            engineReady = false
            linkState = linkState.transition(BleLinkEvent.DISCONNECTED)
            stopTelemetry()
            stopBleSession()
            // PHASE 4 FIX: connectedDevice wurde bisher NIE zurueckgesetzt.
            // Nach jeder Trennung (auch unerwartet) blieb das Geraet in der Liste
            // als "VERBUNDEN" markiert und der Verbinden-Button deaktiviert —
            // ein erneutes Verbinden war damit nicht mehr moeglich.
            connectedDevice = null
            addLog(
                if (unexpected) LogCategory.ERROR else LogCategory.INFO,
                if (unexpected) "Verbindung unerwartet getrennt." else "Verbindung getrennt."
            )
            if (::statusText.isInitialized) statusText.text = "Nicht verbunden."
            updateDeviceList()
            if (unexpected) scheduleReconnect()
            // Erst NACH der Reconnect-Entscheidung zuruecksetzen: sonst wuerde
            // ein bewusstes Trennen den naechsten Abbruch wieder reconnects faehig
            // machen, obwohl der Nutzer genau das nicht wollte.
            userInitiatedDisconnect = false
        }
    }

    override fun onTelemetry(t: Telemetry) {
        runOnUiThread {
            lastTelemetry = t
            updateTrip(t)
            scheduleTelemetryApply()
        }
    }

    override fun onWriteResult(label: String, frame: ByteArray, success: Boolean, detail: String) {
        runOnUiThread {
            if (success) {
                addLog(LogCategory.RX, "$label bestätigt (${ScooterFrames.toHex(frame)})")
            } else {
                addLog(LogCategory.ERROR, "$label fehlgeschlagen: $detail")
            }
        }
    }

    override fun onFlashProgress(written: Int, total: Int) {
        runOnUiThread {
            val percent = if (total > 0) (written * 100 / total) else 0
            flashProgressBar?.progress = percent
            flashStatusText?.text = "Flash: $written / $total Bytes ($percent %)"
        }
    }

    override fun onFlashFinished(success: Boolean, message: String) {
        runOnUiThread {
            addLog(if (success) LogCategory.FLASH else LogCategory.ERROR, "Flash: $message")
            flashStatusText?.text = message
            AlertDialog.Builder(this)
                .setTitle(if (success) "Flash abgeschlossen" else "Flash fehlgeschlagen")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onRssi(rssi: Int) {
        runOnUiThread {
            lastRssi = rssi
            sysRssiView?.text = "RSSI $rssi"
        }
    }
}