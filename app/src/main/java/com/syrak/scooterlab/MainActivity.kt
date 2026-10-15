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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

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
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListContainer: LinearLayout

    private lateinit var repository: ScooterRepository

    private var connectedDevice: ScooterDevice? = null
    private var bleEngine: BleEngine? = null
    private var currentRegion: Region = Region.WORLD
    private var detectedModel: ScooterModel = ScooterModel.UNKNOWN
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
    private val MAX_FIRMWARE_BYTES = 512 * 1024

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
    private var linkState: String = "IDLE"
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
            statusText.text = "Berechtigungen erteilt. Scooter einschalten."
            startBleScan()
        } else {
            statusText.text = "Bluetooth-Berechtigungen erforderlich."
            scanButton.isEnabled = true
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

            // WICHTIG: Callback läuft auf Binder-Thread -> UI-Update auf Main-Thread
            runOnUiThread {
                foundDevices[address] = ScooterDevice(device, name, address, result.rssi, candidate, false)
                updateDeviceList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = "BLE-Scan fehlgeschlagen (Code $errorCode)."
            }
        }
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
        bleEngine?.disconnect()
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
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (_: SecurityException) {
            android.util.Log.w("SyrakScooterLab", "BLE scanner stop denied by permission policy")
        } catch (_: Exception) {
            android.util.Log.w("SyrakScooterLab", "BLE scanner stop failed unexpectedly")
        } finally {
            isScanning = false
            if (::scanButton.isInitialized) {
                scanButton.isEnabled = true
            }
        }
    }

    // ... file continued unchanged below
}