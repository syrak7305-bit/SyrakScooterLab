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

/* ============================================================================
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
        if (isScanning) {
            try {
                if (hasBluetoothConnectPermission()) bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (_: Exception) {}
        }
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
        // FIX 5: Queue-Schutz. Waehrend des Ziehens wird NUR die Anzeige aktualisiert.
        // Ein Frame wird erst bei onStopTrackingTouch() ODER nach >= SLIDER_DEBOUNCE_MS
        // gesendet -> kein Flooding der BLE-Write-Queue.
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

    /* ------------------------------------------------------------------ */
    /*  LOADING / DISCLAIMER                                               */
    /* ------------------------------------------------------------------ */
    private fun showLoadingScreen() {
        rootContainer.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        layout.addView(makeText("SYRAK SCOOTERLAB", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        layout.addView(makeText("REAL BLE TUNING & FLASH ENGINE", 11f, Palette.ACCENT_GREEN, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val status = makeText("Initializing application...", 13f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0)
        }
        layout.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
            setMargins(dp(24), dp(32), dp(24), 0)
        })
        layout.addView(status)
        rootContainer.addView(layout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val steps = listOf("Preparing environment...", "Checking Bluetooth modules...", "Ready.")
        var index = 0
        loadingRunnable = object : Runnable {
            override fun run() {
                if (index < steps.size) {
                    status.text = steps[index++]
                    handler.postDelayed(this, 600)
                } else {
                    if (repository.disclaimersAccepted) showScanScreen()
                    else showCriticalDisclaimer()
                }
            }
        }
        loadingRunnable?.let { handler.postDelayed(it, 400) }
    }

    private fun showCriticalDisclaimer() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(makeText("Critical Safety & Liability Notice", 22f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(20))
        })
        val sections = listOf(
            "Age Restriction & Competence" to
                "This application and its companion firmware utilities are intended solely for adult users of legal age. Minors must operate under the direct supervision and full responsibility of a parent or legal guardian.",
            "Personal Safety & Protective Gear" to
                "Always wear certified protective equipment (helmet, pads) and thoroughly inspect brakes, tire pressure, folding mechanisms, and steering components prior to every ride. Never operate your vehicle while distracted, fatigued, or under the influence of alcohol, medication, or drugs.",
            "Hardware Risks & Component Damage" to
                "Executing low-level diagnostic commands, modifying firmware registers, altering speed limiters, or flashing custom MCU packages carries inherent technical risks. Improper parameter configuration can cause permanent damage to the motor controller, thermal runaway in lithium-ion battery packs, sudden brake lockups, or severe electronic malfunctions.",
            "Legal Compliance & Road Regulations" to
                "Altering factory-set speed ceilings, power limits, or software profiles may directly violate local traffic laws, regional vehicle type-approval standards (e.g., eKFV in Germany), and public road regulations. Vehicles modified beyond legal thresholds are strictly prohibited from being operated on public thoroughfares, bicycle lanes, or sidewalks.",
            "Insurance & Warranty Voidance" to
                "Modifying firmware or bypassing built-in hardware restrictions instantly voids the manufacturer's warranty. Furthermore, changes to maximum speed or output wattage generally invalidate statutory third-party liability insurance coverage, exposing the operator to catastrophic financial and legal liability in the event of an accident.",
            "Independent Third-Party Project" to
                "Syrak ScooterLab is an independent, open developer toolkit. It has no affiliation, endorsement, sponsorship, or official connection with Segway-Ninebot, Xiaomi, Navee, or any other micro-mobility manufacturer.",
            "As-Is Disclaimer & User Responsibility" to
                "The software is provided strictly on an 'as-is' basis without warranties of any kind, express or implied. By proceeding, you assume 100% personal liability for any property damage, bodily injury, legal penalties, or regulatory fines resulting from usage."
        )
        sections.forEach { (heading, body) ->
            content.addView(makeText(heading, 14f, Color.rgb(230, 70, 70), true).apply { setPadding(0, 0, 0, dp(4)) })
            content.addView(makeText(body, 13f, Color.rgb(185, 190, 200)).apply {
                setPadding(0, 0, 0, dp(16)); setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        scroll.addView(content)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }
        val agree = Button(this).apply { text = "I have read and understood (20)"; isEnabled = false }
        val exit = Button(this).apply { text = "Exit App"; setOnClickListener { finishAffinity() } }
        footer.addView(agree); footer.addView(exit)
        rootContainer.addView(footer)
        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(20000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                agree.text = "I have read and understood (${millisUntilFinished / 1000})"
            }
            override fun onFinish() {
                agree.text = "Continue"; agree.isEnabled = true
                agree.setOnClickListener { showCountryDisclaimer() }
            }
        }.start()
    }

    private fun showCountryDisclaimer() {
        activeTimer?.cancel()
        rootContainer.removeAllViews()
        val countryCode = Locale.getDefault().country.uppercase(Locale.ROOT)
        val displayCountry = Locale.getDefault().getDisplayCountry(Locale.getDefault()).ifBlank { "your country" }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(makeText("Regional Legal Notice ($displayCountry)", 22f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(16))
        })
        val localizedBody = when (countryCode) {
            "DE" -> "Rechtliche Hinweise für Deutschland (eKFV):\n\n" +
                "• Elektrokleinstfahrzeuge unterliegen den technischen Vorgaben der eKFV.\n" +
                "• Das Überschreiten der bauartbedingten Höchstgeschwindigkeit von 20 km/h im öffentlichen Straßenverkehr ist ordnungswidrig oder strafbar.\n" +
                "• Manipulationen an der Motorsteuerung oder Firmware führen zum Erlöschen der Betriebserlaubnis (ABE).\n" +
                "• Ohne gültige ABE erlischt der Versicherungsschutz."
            "AT" -> "Rechtliche Hinweise für Österreich:\n\n" +
                "• Klein- und Biodampfer sind an max. 25 km/h / 600 W gebunden.\n" +
                "• Überschreitungen stufen das Gefährt rechtlich als unzulässiges Kraftfahrzeug ein.\n" +
                "• Betrieb im öffentlichen Raum erfordert strikte Einhaltung der StVO."
            "CH" -> "Rechtliche Hinweise für die Schweiz (VTS):\n\n" +
                "• E-Trottinette dürfen bauartbedingt max. 20 km/h mit Motorunterstützung erreichen.\n" +
                "• Technische Tunings sind auf öffentlichen Strassen untersagt und führen zum Verlust der Zulassung."
            else -> "Regional Regulations for $displayCountry:\n\n" +
                "• Ensure full compliance with local traffic laws, speed caps, power caps, and sidewalk/bike-lane permissions.\n" +
                "• Uncertified software modifications typically revoke road legality and insurance validity."
        }
        content.addView(makeText(localizedBody, 14f, Color.rgb(185, 190, 200)).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        scroll.addView(content)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }
        val accept = Button(this).apply { text = "Understood (20)"; isEnabled = false }
        val exit = Button(this).apply { text = "Exit App"; setOnClickListener { finishAffinity() } }
        footer.addView(accept); footer.addView(exit)
        rootContainer.addView(footer)
        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(20000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                accept.text = "Understood (${millisUntilFinished / 1000})"
            }
            override fun onFinish() {
                accept.text = "Accept & Proceed"; accept.isEnabled = true
                accept.setOnClickListener {
                    repository.disclaimersAccepted = true
                    showScanScreen()
                }
            }
        }.start()
    }

    /* ------------------------------------------------------------------ */
    /*  SCAN SCREEN                                                        */
    /* ------------------------------------------------------------------ */
    private fun showScanScreen() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Palette.BG)
        }
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(4))
        }
        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleContainer.addView(makeText("Syrak ScooterLab", 24f, Color.WHITE, true))
        titleContainer.addView(makeText("Scooter auswählen und verbinden", 14f, Palette.TEXT_SECONDARY).apply {
            setPadding(0, dp(4), 0, 0)
        })
        topHeader.addView(titleContainer)
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        val supportedModelsBtn = Button(this).apply {
            text = "Modelle"; textSize = 11f; isAllCaps = false
            setBackgroundColor(Color.rgb(28, 36, 48)); setTextColor(Palette.ACCENT_GREEN)
            setOnClickListener { showSupportedModelsDialog() }
        }
        val demoBtn = Button(this).apply {
            text = "Demo"; textSize = 11f; isAllCaps = false
            setBackgroundColor(Color.rgb(40, 30, 20)); setTextColor(Palette.ACCENT_ORANGE)
            setOnClickListener { loadDemoScooters() }
        }
        buttonsLayout.addView(supportedModelsBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
        ).apply { setMargins(0, 0, dp(6), 0) })
        buttonsLayout.addView(demoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        topHeader.addView(buttonsLayout)
        content.addView(topHeader)
        content.addView(View(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20))
        })
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28)); setBackgroundColor(Palette.SURFACE_ALT)
        }
        panel.addView(makeText("Bluetooth-Verbindung", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        statusText = makeText("Scooter einschalten und Suche starten.", 13f, Color.rgb(185, 190, 200)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4))
        }
        panel.addView(statusText)
        content.addView(panel)
        scanButton = Button(this).apply {
            text = "NACH SCOOTERN SUCHEN"; isAllCaps = false
            setOnClickListener { startBleScan() }
        }
        content.addView(scanButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(16), 0, dp(16)) })
        deviceListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceListContainer)
        content.addView(makeText(
            "Es werden Bluetooth-Geräte in der Nähe angezeigt. Tippe auf einen Scooter, um die Verbindung aufzubauen.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(24), 0, 0) })
        scroll.addView(content)
        rootContainer.addView(scroll)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
        if (bluetoothAdapter == null) {
            statusText.text = "Bluetooth ist auf diesem Gerät nicht verfügbar."
            scanButton.isEnabled = false
        }
    }

    private fun loadDemoScooters() {
        foundDevices.clear()
        val demoList = listOf(
            ScooterDevice(null, "Demo Ninebot Max G2", "D2:E4:5A:11:22:33", -45, true, true),
            ScooterDevice(null, "Demo Ninebot Max G30", "5C:2A:1B:7F:12:34", -52, true, true),
            ScooterDevice(null, "Demo Xiaomi Pro 2", "11:22:33:44:55:66", -68, true, true),
            ScooterDevice(null, "Demo Navee GT3 Max", "99:88:77:66:55:44", -75, true, true),
            ScooterDevice(null, "Demo Navee ST3", "99:88:77:66:55:46", -79, true, true),
            ScooterDevice(null, "Demo Navee S65", "99:88:77:66:55:47", -81, true, true)
        )
        demoList.forEach { foundDevices[it.address] = it }
        updateDeviceList()
        statusText.text = "Demo-Modus aktiv: ${foundDevices.size} Testmodelle geladen."
    }

    private fun showSupportedModelsDialog() {
        val modelsList = """
            • Segway-Ninebot: Max G30/G2, ES-Serie, F2 Pro, F20/F30/F40
            • Xiaomi: M365 / 1S / Essential / Pro 2
            • Navee: GT3 Max / GT3 Pro / ST3 / N65 / V40 / S65
            • SoFlow: SO3 / SO4 / SO6 / SO8
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Unterstützte Scooter-Modelle")
            .setMessage(modelsList)
            .setPositiveButton("Verstanden") { d, _ -> d.dismiss() }
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  BLE-SCAN                                                           */
    /* ------------------------------------------------------------------ */
    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) { permissionLauncher.launch(missing.toTypedArray()); return }

        if (isScanning) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Bitte Bluetooth in den Einstellungen aktivieren."
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { statusText.text = "BLE-Scanner nicht verfügbar."; return }

        foundDevices.clear(); updateDeviceList()
        isScanning = true; scanButton.isEnabled = false
        statusText.text = "Suche nach Scootern..."
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, leScanCallback)
        } catch (_: Exception) {
            isScanning = false; scanButton.isEnabled = true
            statusText.text = "BLE-Scan konnte nicht gestartet werden."
            return
        }
        handler.postDelayed({
            if (isScanning) {
                try { scanner.stopScan(leScanCallback) } catch (_: Exception) {}
                isScanning = false; scanButton.isEnabled = true
                statusText.text = "Scan beendet. Gefundene Geräte: ${foundDevices.size}"
            }
        }, 10000)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDeviceList() {
        try {
            if (!::deviceListContainer.isInitialized) return
            deviceListContainer.removeAllViews()
            foundDevices.values.sortedByDescending { it.rssi }.forEach { item ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    setBackgroundColor(if (item.isScooterCandidate) Color.rgb(25, 40, 32) else Palette.SURFACE_ALT)
                    setOnClickListener {
                        if (isScanning) {
                            try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback) } catch (_: Exception) {}
                            isScanning = false
                        }
                        connectToDevice(item)
                    }
                }
                card.addView(makeText(item.name, 15f, Color.WHITE, true))
                card.addView(makeText(
                    if (item.isDemo) "Demo-Gerät  •  Tippen zum Verbinden"
                    else "Signal: ${item.rssi} dBm  •  ${item.address} (Tippen zum Verbinden)",
                    12f, Palette.TEXT_SECONDARY
                ).apply { setPadding(0, dp(5), 0, 0) })
                deviceListContainer.addView(card, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(10)) })
            }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "updateDeviceList: ${e.message}")
        }
    }

    /* ------------------------------------------------------------------ */
    /*  VERBINDUNG                                                         */
    /* ------------------------------------------------------------------ */
    private fun connectToDevice(item: ScooterDevice) {
        connectedDevice = item
        detectedModel = ScooterModel.detect(item.name, item.address)
        activeProtocol = detectedModel.protocol
        reconnectAttempts = 0
        userInitiatedDisconnect = false
        engineReady = false

        if (item.isDemo || item.device == null) {
            linkState = "DEMO"
            showMainDashboard()
            log("> link: DEMO verbunden ${item.address}")
            log("> model: ${detectedModel.displayName} (Easy ${detectedModel.easySpeedKmh} km/h)")
            engineReady = true
            return
        }
        linkState = "CONNECTING"
        showMainDashboard()
        val engine = BleEngine(this, this)
        bleEngine = engine
        engine.setProtocolHint(detectedModel.protocol)
        engine.connect(item.device)
    }

    private fun handleUnexpectedDisconnect() {
        engineReady = false
        linkState = "DISCONNECTED"
        updateSystemBar()
        if (userInitiatedDisconnect) return
        if (!autoReconnectEnabled) { runOnUiThread { showScanScreen() }; return }
        // FIX 2: Sauberer exponentieller Backoff (1s, 2s, 4s, gedeckelt) mit genau
        // EINEM geplanten Reconnect-Runnable. Kein Endlos-Loop, keine Doppel-Timer.
        cancelReconnectRunnable()
        if (reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
            reconnectAttempts++
            linkState = "RECONNECT"
            val delayMs = (RECONNECT_BACKOFF_BASE_MS shl (reconnectAttempts - 1))
                .coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
            log("> link: Reconnect-Versuch $reconnectAttempts/$RECONNECT_MAX_ATTEMPTS in ${delayMs} ms (UI bleibt bedienbar)")
            updateSystemBar()
            val r = Runnable {
                reconnectRunnable = null
                if (!userInitiatedDisconnect && connectedDevice?.device != null) {
                    try { bleEngine?.reconnect() } catch (e: Exception) {
                        log("> link: Reconnect-Exception: ${e.message}")
                    }
                }
            }
            reconnectRunnable = r
            handler.postDelayed(r, delayMs)
        } else {
            linkState = "DISCONNECTED"
            log("> link: Auto-Reconnect fehlgeschlagen — zurück zur Suche")
            updateSystemBar()
            runOnUiThread { showScanScreen() }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  BleEngine.Listener (läuft teils auf Binder-Threads!)               */
    /* ------------------------------------------------------------------ */
    private fun cancelReconnectRunnable() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    override fun onLog(line: String) = log(line)

    override fun onRssi(rssi: Int) {
        try {
            lastRssi = rssi
            runOnUiThread { updateSystemBar() }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "onRssi: ${e.message}")
        }
    }

    override fun onConnected(address: String) {
        try {
            runOnUiThread { Toast.makeText(this, "Verbunden: $address", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "onConnected: ${e.message}")
        }
    }

    override fun onReady(address: String, protocol: BleProtocol, mtu: Int) {
        try {
            activeProtocol = protocol
            engineReady = true
            runOnUiThread {
                linkState = "READY"
                log("> gatt: READY — Protokoll ${protocol.displayName}, MTU $mtu")
                updateSystemBar()
                if (currentTab == Tab.INFO) renderTab(Tab.INFO)
            }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "onReady: ${e.message}")
        }
    }

    override fun onDisconnected(unexpected: Boolean) {
        try {
            runOnUiThread { handleUnexpectedDisconnect() }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "onDisconnected: ${e.message}")
        }
    }

    override fun onTelemetry(t: Telemetry) {
        // FIX 1: Telemetrie NICHT pro Paket zeichnen. Nur den letzten Wert merken
        // und ein (max. alle UI_REFRESH_INTERVAL_MS) gebuendeltes UI-Update planen.
        lastTelemetry = t
        scheduleTelemetryApply()
    }

    override fun onWriteResult(label: String, frame: ByteArray, success: Boolean, detail: String) {
        try {
            val hex = hexFor(frame)
            if (success) log("> tx[$label]: $hex  ✔ ($detail)")
            else log("> tx[$label]: FEHLER — $detail  (Frame $hex nicht gesendet)")
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "onWriteResult: ${e.message}")
        }
    }

    override fun onFlashProgress(written: Int, total: Int) {
        try {
            runOnUiThread {
                val pct = if (total > 0) (written * 100 / total) else 0
                flashProgressBar?.progress = pct
                flashStatusView?.text = "Schreibe… $written / $total Bytes ($pct %)"
            }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "onFlashProgress: ${e.message}")
        }
    }

    override fun onFlashFinished(success: Boolean, message: String) {
        try {
            runOnUiThread {
                flashStatusView?.text = (if (success) "✔ " else "✘ ") + message
                flashStatusView?.setTextColor(if (success) Palette.ACCENT_GREEN else Palette.ACCENT_RED)
                if (success) flashProgressBar?.progress = 100
                log("> flash: ${if (success) "OK" else "FEHLER"} — $message")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "onFlashFinished: ${e.message}")
        }
    }

    private fun hexFor(frame: ByteArray): String =
        frame.joinToString(" ") { "%02X".format(it) }

    /* ------------------------------------------------------------------ */
    /*  DASHBOARD-SHELL                                                    */
    /* ------------------------------------------------------------------ */
    private fun showMainDashboard() {
        rootContainer.removeAllViews()
        currentTab = Tab.INFO
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        topBar.addView(makeText("SYRAK", 20f, Color.WHITE, true))
        topBar.addView(makeText(" SCOOTERLAB", 11f, Palette.ACCENT_RED, true))
        topBar.addView(makeText("  ${currentRegion.displayName} (${currentRegion.legalSpeedKmh} km/h)  ",
            10f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shell.addView(topBar)

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(20, 30, 28))
        }
        val dev = connectedDevice
        banner.addView(makeText(
            if (dev?.isDemo == true) "■ Controller online  •  ${dev.name} (Demo)"
            else "■ Controller online  •  ${dev?.name ?: "Unknown"}",
            12f, Palette.ACCENT_GREEN, true
        ))
        shell.addView(banner)
        shell.addView(buildSystemBar())

        val scroll = ScrollView(this).apply { isFillViewport = true }
        tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(tabContent)
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomNav = buildBottomNav()
        shell.addView(bottomNav)
        rootContainer.addView(shell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        selectTab(Tab.INFO)
        startSystemBar()
    }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(10)); setBackgroundColor(Palette.SURFACE)
        }
        fun navItem(tab: Tab, icon: String, label: String): LinearLayout {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(tab) }
            }
            item.addView(makeText(icon, 18f, Palette.TEXT_SECONDARY).apply { gravity = Gravity.CENTER })
            item.addView(makeText(label, 10f, Palette.TEXT_SECONDARY).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
            })
            item.tag = tab
            return item
        }
        nav.addView(navItem(Tab.INFO, "ⓘ", "Info"))
        nav.addView(navItem(Tab.TOOLS, "⚙", "Tools"))
        val centerWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val centerCircle = TextView(this).apply {
            text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Palette.ACCENT_BLUE); setStroke(dp(4), Palette.BG)
            }
            layoutParams = FrameLayout.LayoutParams(dp(60), dp(60)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dp(26)
            }
            setOnClickListener { selectTab(Tab.DASHBOARD) }
        }
        centerWrap.addView(centerCircle)
        nav.addView(centerWrap)
        nav.addView(navItem(Tab.FLASH, "⇪", "Flash"))
        nav.addView(navItem(Tab.CONFIG, "⚡", "Config"))
        return nav
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        for (i in 0 until bottomNav.childCount) {
            val child = bottomNav.getChildAt(i)
            if (child is LinearLayout && child.tag is Tab) {
                val active = child.tag == tab
                val color = if (active) Palette.ACCENT_GREEN else Palette.TEXT_SECONDARY
                (child.getChildAt(0) as? TextView)?.setTextColor(color)
                (child.getChildAt(1) as? TextView)?.setTextColor(color)
            }
        }
        renderTab(tab)
    }

    private fun renderTab(tab: Tab) {
        stopTelemetry()
        tabContent.removeAllViews()
        when (tab) {
            Tab.INFO -> renderInfoTab()
            Tab.TOOLS -> renderToolsTab()
            Tab.DASHBOARD -> renderDashboardTab()
            Tab.FLASH -> renderFlashTab()
            Tab.CONFIG -> renderConfigTab()
        }
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: INFO                                                          */
    /* ------------------------------------------------------------------ */
    private fun renderInfoTab() {
        val dev = connectedDevice
        val isDemo = dev?.isDemo == true
        val preview = makeCard()
        preview.addView(makeText("🛴  ${dev?.name ?: "Unbekannt"}", 20f, Color.WHITE, true))
        preview.addView(makeText(
            if (isDemo) "Demo Simulation  •  ${dev?.address}" else "BLE Connected  •  ${dev?.address}",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(preview, cardParams())

        tabContent.addView(makeSectionLabel("ERKANNTES MODELL"))
        val modelCard = makeCard()
        modelCard.addView(makeText(detectedModel.displayName, 16f, Palette.ACCENT_GREEN, true))
        modelCard.addView(makeText(
            "Easy Speed: ${detectedModel.easySpeedKmh} km/h   •   Techn. Max: ${detectedModel.absMaxKmh} km/h",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(6)) })
        modelCard.addView(makeText(
            "Panic-Hotkey: ${detectedModel.panicHotkey}\nStealth-Unlock: ${detectedModel.stealthUnlockHotkey}",
            12f, Palette.TEXT_MUTED
        ))
        tabContent.addView(modelCard, cardParams())

        tabContent.addView(makeSectionLabel("SYSTEM"))
        val info = makeCard()
        fun infoRow(l: String, r: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8))
            addView(makeText(l, 13f, Palette.TEXT_SECONDARY))
            addView(makeText(r, 13f, Color.WHITE, true).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        info.addView(infoRow("Protokoll", activeProtocol.displayName))
        info.addView(infoRow("GATT-Status", if (isDemo) "DEMO" else if (engineReady) "READY" else "verbinde…"))
        info.addView(infoRow("MTU", if (isDemo) "—" else "${bleEngine?.maxWritePayload()?.plus(3) ?: "—"} B"))
        info.addView(infoRow("Region", "${currentRegion.displayName} (${currentRegion.legalSpeedKmh} km/h)"))
        info.addView(infoRow("Overspeed-Alarm", if (overspeedAlarmMuted) "AUS (stumm)" else "AN"))
        info.addView(infoRow("RAM-Overlay", if (ramOverlay.active) "${ramOverlay.size} Patch(es)" else "inaktiv"))
        tabContent.addView(info, cardParams())

        tabContent.addView(makeSectionLabel("STATUS"))
        val status = makeCard()
        status.addView(makeText(
            if (isLocked) "🔒 Wegfahrsperre AKTIV" else "🔓 Scooter entsperrt",
            14f, if (isLocked) Palette.ACCENT_RED else Palette.ACCENT_GREEN, true
        ))
        status.addView(makeText(
            if (ramOverlay.active) "RAM-Overlay aktiv: ${ramOverlay.names.joinToString()}"
            else "Kein flüchtiges Tuning aktiv",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        status.addView(makeText("Flash-Modus: ${flashMode.displayName}", 12f,
            if (flashMode == FlashMode.HARD_FLASH) Palette.ACCENT_ORANGE else Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(status)

        tabContent.addView(makeSectionLabel("LIVE-LOG"))
        tabContent.addView(buildLogTerminal(), cardParams())
        refreshLogView()
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: TOOLS                                                         */
    /* ------------------------------------------------------------------ */
    private fun renderToolsTab() {
        tabContent.addView(makeSectionLabel("ANFAHREN"))
        tabContent.addView(makeSliderCard(
            "Zero-Start / Anfahrgeschwindigkeit",
            "0 km/h = Instant Throttle. 1–5 km/h = Mindestgeschwindigkeit bis der Motor greift.",
            0, 5, zeroStartKmh, "km/h",
            valueFormatter = { if (it == 0) "0 km/h · ZERO-START" else "$it km/h" }
        ) { value ->
            zeroStartKmh = value
            sendFrame(bleEngine?.adapter?.buildZeroStartFrame(value), "zero-start")
        }, cardParams())

        tabContent.addView(makeSectionLabel("GESCHWINDIGKEIT"))
        tabContent.addView(buildMaxSpeedCard(), cardParams())

        val gm = makeCard()
        gm.addView(makeText("German Maneuver", 15f, Color.WHITE, true))
        gm.addView(makeText(
            "Volatiler RAM-Patch. Vor dem Anwenden werden die aktuellen Register gesichert (Police Mode kann sie wiederherstellen).",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        gm.addView(makePrimaryButton("GERMAN MANEUVER ANWENDEN") {
            snapshotRegisters()
            germanManeuverActive = true
            val frame = bleEngine?.adapter?.buildGermanManeuverFrame()
            ramOverlay.apply("german-maneuver", frame)
            sendFrame(frame, "german-maneuver")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(gm, cardParams())

        tabContent.addView(makeSectionLabel("FAHRDYNAMIK"))
        tabContent.addView(makeSliderCard(
            "KERS (Rekuperation)", "Stärke der Energierückgewinnung.", 0, 3, kersLevel, "Stufe"
        ) { value ->
            kersLevel = value
            sendFrame(bleEngine?.adapter?.buildKersFrame(value), "kers")
        }, cardParams())

        val cruise = makeCard()
        cruise.addView(makeToggleRow(
            "Tempomat (Cruise Control)", "Hält die Geschwindigkeit konstant.", cruiseEnabled
        ) { on ->
            cruiseEnabled = on
            sendFrame(bleEngine?.adapter?.buildCruiseFrame(on), "cruise")
        })
        tabContent.addView(cruise, cardParams())

        tabContent.addView(makeSectionLabel("FAHRMODUS"))
        val rideCard = makeCard()
        addRideModeSelector(rideCard, 15f)
        tabContent.addView(rideCard, cardParams())

        tabContent.addView(makeSectionLabel("MOTORSCHUTZ"))
        val guard = makeCard()
        guard.addView(makeToggleRow(
            "Motortemperatur-Schutzschalter",
            "Schneidet die Motorleistung ab, wenn die Temperatur die Schwelle überschreitet.",
            motorTempGuardEnabled
        ) { on ->
            motorTempGuardEnabled = on
            if (!on) motorGuardTripped = false
            sendFrame(bleEngine?.adapter?.buildTempGuardFrame(if (on) motorTempGuardC else 0), "temp-guard")
        })
        guard.addView(makeSliderCard(
            "Abschalt-Schwelle", "Temperatur, ab der die Leistung gekappt wird.",
            60, 95, motorTempGuardC, "°C"
        ) { value ->
            motorTempGuardC = value
            if (motorTempGuardEnabled) sendFrame(bleEngine?.adapter?.buildTempGuardFrame(value), "temp-guard")
        })
        tabContent.addView(guard, cardParams())

        tabContent.addView(makeSectionLabel("SICHERHEIT"))
        val lock = makeCard()
        lock.addView(makeText("Lock Code / Wegfahrsperre", 15f, Color.WHITE, true))
        lock.addView(makeText(
            "Elektronische Sperre mit PIN. Der Controller verweigert Gas, bis der Code eingegeben wird.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        lock.addView(makePrimaryButton(if (isLocked) "ENTSPERREN (PIN)" else "SPERREN / PIN FESTLEGEN") {
            showLockCodeDialog()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(lock, cardParams())

        val police = makeCard()
        police.addView(makeText("🚨 Police Mode (Panik-Button)", 15f, Palette.ACCENT_ORANGE, true))
        police.addView(makeText(
            "Flusht den RAM-Overlay, sendet echte Drossel-Frames und stellt das legale Werkslimit " +
            "(${currentRegion.displayName}: ${currentRegion.legalSpeedKmh} km/h) wieder her. " +
            "Falls ein Register-Snapshot vorliegt, werden die Ursprungs-Bytes zurückgeschrieben.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        police.addView(makePrimaryButton("POLICE MODE AUSLÖSEN", Palette.ACCENT_ORANGE) {
            engagePoliceMode()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(police, cardParams())

        tabContent.addView(makeSectionLabel("LENKER-HOTKEYS"))
        val hotkeys = makeCard()
        hotkeys.addView(makeText("Modellspezifische Kombinationen", 14f, Color.WHITE, true))
        hotkeys.addView(makeText(
            "🚨 Panic: ${detectedModel.panicHotkey}\n\n🕶 Stealth Unlock: ${detectedModel.stealthUnlockHotkey}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(12)) })
        hotkeys.addView(makePrimaryButton("PANIC-HOTKEY SIMULIEREN", Palette.ACCENT_ORANGE) {
            triggerPanicHotkey()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            setMargins(0, 0, 0, dp(8))
        })
        hotkeys.addView(makePrimaryButton("STEALTH-UNLOCK SIMULIEREN", Palette.ACCENT_GREEN) {
            triggerStealthUnlock()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        tabContent.addView(hotkeys, cardParams())
    }

    private fun buildMaxSpeedCard(): LinearLayout {
        val easy = detectedModel.easySpeedKmh
        val absMax = detectedModel.absMaxKmh
        val card = makeCard()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeText("Max Speed", 14f, Palette.TEXT_PRIMARY, true))
        val valueView = makeText("$maxSpeedKmh km/h", 14f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.END }
        header.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)
        card.addView(makeText(
            "Easy Speed (${detectedModel.displayName}): $easy km/h  •  techn. Max: $absMax km/h.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(3), 0, dp(10)) })
        val bar = SeekBar(this).apply { max = 65 - 15; progress = maxSpeedKmh - 15 }
        // FIX 5: Queue-Schutz. Waehrend des Ziehens nur Anzeige; Frame erst bei
        // onStopTrackingTouch() ODER nach >= SLIDER_DEBOUNCE_MS.
        var lastCommit = 0L
        var pendingReal = maxSpeedKmh
        val commitRunnable = Runnable {
            lastCommit = System.currentTimeMillis()
            if (pendingReal <= easy) {
                maxSpeedKmh = pendingReal
                sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(pendingReal), "max-speed")
            }
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val real = progress + 15
                pendingReal = real
                valueView.text = "$real km/h"
                valueView.setTextColor(if (real > easy) Palette.ACCENT_ORANGE else Palette.ACCENT_GREEN)
                if (!fromUser || real > easy) return
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
                val real = (seekBar?.progress ?: 0) + 15
                if (real > easy) {
                    confirmExtendedPower(real, easy, absMax, bar, valueView)
                } else {
                    pendingReal = real
                    commitRunnable.run()
                }
            }
        })
        card.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(makeText(
            if (extendedPowerUnlocked) "⚡ Erweiterte Strombegrenzung FREIGESCHALTET"
            else "🔒 Erweiterte Strombegrenzung gesperrt (Easy Speed aktiv)",
            11f, if (extendedPowerUnlocked) Palette.ACCENT_ORANGE else Palette.ACCENT_GREEN, true
        ).apply { setPadding(0, dp(6), 0, 0) })
        return card
    }

    private fun confirmExtendedPower(requested: Int, easy: Int, absMax: Int, bar: SeekBar, valueView: TextView) {
        val capped = requested.coerceAtMost(absMax)
        AlertDialog.Builder(this)
            .setTitle("⚠ Easy-Speed-Grenze überschritten")
            .setMessage(
                "Achtung: Ihr ${detectedModel.displayName} ist herstellerseitig nicht für diese Belastung ausgelegt. " +
                "Bei Überschreitung von $easy km/h droht thermische Überlastung. Nutzung auf eigene Gefahr!\n\n" +
                "Angefordert: $requested km/h (techn. Max: $absMax km/h)"
            )
            .setPositiveButton("Freischalten & senden") { _, _ ->
                extendedPowerUnlocked = true
                maxSpeedKmh = capped
                bar.progress = capped - 15
                valueView.text = "$capped km/h"
                valueView.setTextColor(Palette.ACCENT_ORANGE)
                val maxFrame = bleEngine?.adapter?.buildSpeedCeilingFrame(capped)
                val powerFrame = bleEngine?.adapter?.buildMaxPowerFrame()
                ramOverlay.apply("extended-power", powerFrame)
                ramOverlay.apply("max-speed-$capped", maxFrame)
                sendFrame(maxFrame, "max-speed")
                sendFrame(powerFrame, "max-power")
                renderTab(Tab.TOOLS)
                showPostFlashDialog()
            }
            .setNegativeButton("Abbrechen") { _, _ ->
                maxSpeedKmh = easy
                bar.progress = easy - 15
                valueView.text = "$easy km/h"
                valueView.setTextColor(Palette.ACCENT_GREEN)
                sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(easy), "max-speed")
            }
            .setCancelable(false)
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: DASHBOARD                                                     */
    /* ------------------------------------------------------------------ */
    private fun renderDashboardTab() {
        val isDemo = connectedDevice?.isDemo == true
        val speedCard = makeCard().apply { gravity = Gravity.CENTER }
        dashSpeed = makeText("--", 56f, Color.WHITE, true)
        speedCard.addView(dashSpeed)
        speedCard.addView(makeText("KM/H", 12f, Palette.ACCENT_RED, true))
        dashFeedState = makeText(
            if (isDemo) "LIVE FEED (DEMO SIMULATION)" else "LIVE FEED (WARTE AUF BLE-NOTIFY)",
            10f, if (isDemo) Palette.ACCENT_ORANGE else Palette.ACCENT_GREEN, true
        ).apply { setPadding(0, dp(4), 0, dp(14)) }
        speedCard.addView(dashFeedState)
        val metricsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        fun metricCol(label: String): TextView {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(makeText(label, 10f, Palette.TEXT_MUTED))
            val v = makeText("--", 15f, Color.WHITE, true).apply { setPadding(0, dp(2), 0, 0) }
            col.addView(v)
            metricsRow.addView(col)
            return v
        }
        dashPower = metricCol("POWER")
        dashTrip = metricCol("TRIP")
        dashRange = metricCol("RANGE")
        speedCard.addView(metricsRow)
        tabContent.addView(speedCard, cardParams())

        tabContent.addView(makeSectionLabel("ELEKTRIK"))
        fun gridRow(l1: String, l2: String): Pair<TextView, TextView> {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
            fun gridCard(label: String): TextView {
                val c = makeCard().apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                }
                c.addView(makeText(label, 11f, Palette.TEXT_MUTED))
                val v = makeText("--", 18f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) }
                c.addView(v)
                row.addView(c)
                return v
            }
            val a = gridCard(l1); val b = gridCard(l2)
            tabContent.addView(row)
            return a to b
        }
        val (vVolt, vCur) = gridRow("⚡ VOLTAGE", "📈 CURRENT")
        dashVoltage = vVolt; dashCurrent = vCur
        val (vBat, vTemp) = gridRow("🔋 BATTERY", "🌡 MOTOR TEMP")
        dashBattery = vBat; dashMotorTemp = vTemp

        tabContent.addView(makeSectionLabel("AKKU-ZELLBALANCE"))
        val cellCard = makeCard()
        cellCard.addView(makeText("Zell-Ausgleich", 14f, Color.WHITE, true))
        cellCard.addView(makeText(
            "Differenz zwischen stärkster und schwächster Zelle (aus BMS-Notify).",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(3), 0, dp(10)) })
        fun cellRow(l: String): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(2))
            }
            row.addView(makeText(l, 12f, Palette.TEXT_SECONDARY))
            val v = makeText("--", 12f, Color.WHITE, true).apply { gravity = Gravity.END }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cellCard.addView(row)
            return v
        }
        dashCellMin = cellRow("Cell min")
        dashCellMax = cellRow("Cell max")
        dashCellDelta = cellRow("Cell delta")
        tabContent.addView(cellCard, cardParams())

        tabContent.addView(makeSectionLabel("SCHUTZ & STREAM"))
        val streamInfo = makeCard()
        dashTempGuardState = makeText(
            if (motorTempGuardEnabled) "🛡 Motorschutz aktiv (Schwelle $motorTempGuardC °C)" else "⚠ Motorschutz deaktiviert",
            12f, if (motorTempGuardEnabled) Palette.ACCENT_GREEN else Palette.ACCENT_RED, true
        )
        streamInfo.addView(dashTempGuardState)
        streamInfo.addView(makeText(
            if (isDemo) "Demo-Simulation läuft — Werte werden interpoliert."
            else "Echte Werte erscheinen, sobald der Controller Telemetrie-Notifications sendet.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(streamInfo)

        // ---- FAHRMODUS (Eco-Saver / Sport-Plus) --------------------------
        tabContent.addView(makeSectionLabel("FAHRMODUS"))
        val modeCard = makeCard()
        addRideModeSelector(modeCard, 14f)
        tabContent.addView(modeCard, cardParams())

        // ---- BATTERY HEALTH / SoH + Zell-Grafik --------------------------
        tabContent.addView(makeSectionLabel("BATTERY HEALTH / BMS"))
        val healthCard = makeCard()
        healthCard.addView(makeText("State of Health & Zellanalyse", 14f, Color.WHITE, true))
        healthCard.addView(makeText(
            "SoH = Vollkapazität / Design-Kapazität. Das Zell-Delta (mV) zeigt die schwächste Zelle.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(3), 0, dp(10)) })
        fun healthRow(l: String): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(2))
            }
            row.addView(makeText(l, 12f, Palette.TEXT_SECONDARY))
            val v = makeText("--", 12f, Color.WHITE, true).apply { gravity = Gravity.END }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            healthCard.addView(row)
            return v
        }
        dashSoh = healthRow("State of Health (SoH)")
        dashCycles = healthRow("Ladezyklen")
        cellGraphRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(0, dp(12), 0, 0)
        }
        healthCard.addView(cellGraphRow)
        healthCard.addView(makeText("Zellspannungen (mV) — live aus BMS-Notify",
            10f, Palette.TEXT_MUTED).apply { setPadding(0, dp(4), 0, 0) })
        tabContent.addView(healthCard, cardParams())

        // ---- TRIP-COMPUTER + EXPORT --------------------------------------
        tabContent.addView(makeSectionLabel("TRIP-COMPUTER"))
        val tripCard = makeCard()
        tripCard.addView(makeText("Fahrdaten dieser Fahrt", 14f, Color.WHITE, true))
        fun tripRow(l: String): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(2))
            }
            row.addView(makeText(l, 12f, Palette.TEXT_SECONDARY))
            val v = makeText("--", 12f, Color.WHITE, true).apply { gravity = Gravity.END }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tripCard.addView(row)
            return v
        }
        dashVmax = tripRow("Vmax (Spitze)")
        dashVavg = tripRow("Vavg (Schnitt)")
        dashWh = tripRow("Energie (Wh)")
        tripCard.addView(makePrimaryButton("FAHRDATEN EXPORTIEREN (.csv)", Palette.ACCENT_BLUE) {
            exportTripData()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            setMargins(0, dp(10), 0, 0)
        })
        tabContent.addView(tripCard, cardParams())

        // Letzte bekannte Telemetrie sofort anzeigen (falls schon empfangen)
        lastTelemetry?.let { applyTelemetryToDashboard(it) }
        if (isDemo) startDemoTelemetry()
    }

    private fun applyTelemetryToDashboard(t: Telemetry) {
        try {
            // ---- Trip-Computer fortschreiben (echte Integration) ----
            val now = System.currentTimeMillis()
            if (tripData.startMillis == 0L) { tripData.startMillis = now; tripData.lastMillis = now }
            val dtSec = if (tripData.lastMillis == 0L) 0.0
                else (((now - tripData.lastMillis).coerceIn(0L, 5000L)) / 1000.0)
            tripData.lastMillis = now
            t.speedKmh?.let { sp ->
                val v = sp.toDouble()
                tripData.distanceKm += v * dtSec / 3600.0
                if (v > tripData.maxSpeedKmh) tripData.maxSpeedKmh = v
                tripData.speedSum += v; tripData.speedSamples++
                // Eco-Saver: unter Schwelle sanft drosseln (echter Frame)
                if (rideMode == RideMode.ECO_SAVER && (t.batteryPercent ?: 100) <= ecoThresholdPercent &&
                    maxSpeedKmh > 15
                ) {
                    maxSpeedKmh = 15
                    sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(15), "eco-saver")
                }
            }
            if (t.voltageV != null && t.currentA != null)
                tripData.energyWh += (t.voltageV * t.currentA) * dtSec / 3600.0

            // ---- Dashboard-Kernwerte ----
            dashSpeed?.text = t.speedKmh?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
            dashSpeed?.setTextColor(speedColor(t.speedKmh ?: 0f))
            dashVoltage?.text = t.voltageV?.let { String.format(Locale.US, "%.1f V", it) } ?: "-- V"
            dashCurrent?.text = t.currentA?.let { String.format(Locale.US, "%.1f A", it) } ?: "-- A"
            dashBattery?.text = t.batteryPercent?.let { "$it %" } ?: "-- %"
            dashMotorTemp?.text = t.motorTempC?.let { String.format(Locale.US, "%.0f °C", it) } ?: "-- °C"
            val power = if (t.voltageV != null && t.currentA != null) t.voltageV * t.currentA else null
            dashPower?.text = power?.let { String.format(Locale.US, "%.0f W", it) } ?: "-- W"

            // ---- Zellbalance ----
            if (t.cellMinV != null) dashCellMin?.text = String.format(Locale.US, "%.3f V", t.cellMinV)
            if (t.cellMaxV != null) dashCellMax?.text = String.format(Locale.US, "%.3f V", t.cellMaxV)
            if (t.cellMinV != null && t.cellMaxV != null)
                dashCellDelta?.text = String.format(Locale.US, "%.0f mV", (t.cellMaxV - t.cellMinV) * 1000)
            renderCellGraph(t.cellVoltages)

            // ---- Battery Health / SoH ----
            val design = t.designCapacityMah
            val full = t.fullChargeCapacityMah
            val soh = if (design != null && full != null && design > 0)
                full.toDouble() / design.toDouble() * 100.0 else null
            dashSoh?.text = soh?.let { String.format(Locale.US, "%.1f %%", it) } ?: "--"
            dashSoh?.setTextColor(when {
                soh == null -> Palette.TEXT_MUTED
                soh >= 90.0 -> Palette.ACCENT_GREEN
                soh >= 75.0 -> Palette.ACCENT_ORANGE
                else -> Palette.ACCENT_RED
            })
            dashCycles?.text = t.cycleCount?.let { "$it" } ?: "--"

            // ---- Trip-Computer ----
            dashTrip?.text = String.format(Locale.US, "%.2f KM", tripData.distanceKm)
            dashVmax?.text = String.format(Locale.US, "%.1f km/h", tripData.maxSpeedKmh)
            dashVavg?.text = String.format(Locale.US, "%.1f km/h", tripData.avgSpeedKmh)
            dashWh?.text = String.format(Locale.US, "%.1f Wh", tripData.energyWh)
            t.batteryPercent?.let { p ->
                dashRange?.text = String.format(Locale.US, "%.1f KM", tripData.distanceKm + p * 0.28)
            }

            dashFeedState?.text = "LIVE FEED • ${t.source.displayName}"
            dashFeedState?.setTextColor(Palette.ACCENT_GREEN)
            t.motorTempC?.let { evaluateMotorGuard(it.toDouble()) }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "applyTelemetryToDashboard: ${e.message}")
        }
    }

    /** Geschwindigkeits-Farbverlauf: Grün → Gelb → Orange → Rot. */
    private fun speedColor(speed: Float): Int = when {
        speed < 25f -> Palette.ACCENT_GREEN
        speed < 35f -> Palette.ACCENT_YELLOW
        speed < 45f -> Palette.ACCENT_ORANGE
        else -> Palette.ACCENT_RED
    }

    /** Zeichnet die Zellspannungen als Balkengrafik (mV). */
    private fun renderCellGraph(cells: List<Float>?) {
        try {
            val row = cellGraphRow ?: return
            row.removeAllViews()
            if (cells == null || cells.isEmpty()) {
                row.addView(makeText("Zellwerte: keine Daten (BMS sendet kein Zell-Array)", 10f, Palette.TEXT_MUTED))
                return
            }
            val min = cells.minOrNull() ?: return
            val max = cells.maxOrNull() ?: return
            val span = (max - min).coerceAtLeast(0.001f)
            cells.forEach { v ->
                val h = dp(20) + (((v - min) / span) * dp(60)).toInt()
                val bar = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply { setMargins(dp(1), 0, dp(1), 0) }
                    background = GradientDrawable().apply {
                        setColor(
                            if (v <= min + span * 0.15f) Palette.ACCENT_RED
                            else if (v >= max - span * 0.15f) Palette.ACCENT_GREEN
                            else Palette.ACCENT_CYAN
                        )
                    }
                }
                row.addView(bar)
            }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "renderCellGraph: ${e.message}")
        }
    }

    /** Wendet ein Motor-Performance-Profil an (echte Frames). */
    /**
     * DRY: Baut den "Motor-Performance-Profil"-Selektor. Wird identisch im
     * Tools- und im Dashboard-Tab verwendet — nur die Titelgroesse unterscheidet sich.
     */
    private fun addRideModeSelector(container: LinearLayout, titleSize: Float) {
        container.addView(makeText("Motor-Performance-Profil", titleSize, Color.WHITE, true))
        container.addView(makeText(
            "Eco-Saver drosselt sanft unter ${ecoThresholdPercent} % Akkustand. " +
            "Sport-Plus gibt maximale Beschleunigung mit temporärem Phasenstrom — der Thermalschutz bleibt aktiv.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(10)) })
        RideMode.values().forEach { mode ->
            val isSel = rideMode == mode
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (isSel) Palette.SURFACE_DEEP else Palette.SURFACE_ALT)
                setOnClickListener { applyRideMode(mode) }
            }
            row.addView(makeText("${if (isSel) "● " else "○ "}${mode.displayName}", 13f,
                if (isSel) Palette.ACCENT_GREEN else Color.WHITE, true))
            row.addView(makeText(mode.description, 11f, Palette.TEXT_MUTED).apply { setPadding(0, dp(3), 0, 0) })
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) })
        }
    }

    private fun applyRideMode(mode: RideMode) {
        rideMode = mode
        when (mode) {
            RideMode.NORMAL -> {
                val target = minOf(maxSpeedKmh, detectedModel.easySpeedKmh)
                sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(target), "ride-normal")
                log("> ride: NORMAL — Leistung auf $target km/h")
            }
            RideMode.ECO_SAVER -> {
                val target = minOf(maxSpeedKmh, 15)
                sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(target), "ride-eco")
                sendFrame(bleEngine?.adapter?.buildKersFrame(3), "ride-eco-kers")
                log("> ride: ECO-SAVER — Drosselung unter $ecoThresholdPercent %, Ziel $target km/h, KERS 3")
            }
            RideMode.SPORT_PLUS -> {
                sendFrame(bleEngine?.adapter?.buildMaxPowerFrame(), "ride-sport")
                sendFrame(bleEngine?.adapter?.buildTempGuardFrame(motorTempGuardC), "ride-sport-guard")
                log("> ride: SPORT-PLUS — max. Beschleunigung, Phasenstrom $sportPlusPhaseCurrentA A, Thermalschutz $motorTempGuardC °C")
            }
        }
        Toast.makeText(this, "Fahrmodus: ${mode.displayName}", Toast.LENGTH_SHORT).show()
        if (currentTab == Tab.DASHBOARD) renderTab(Tab.DASHBOARD)
    }

    /** Exportiert die Fahrdaten als CSV (Datei + Zwischenablage). */
    private fun exportTripData() {
        if (tripData.speedSamples == 0) {
            Toast.makeText(this, "Noch keine Fahrdaten aufgezeichnet.", Toast.LENGTH_SHORT).show(); return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val header = "timestamp;model;protocol;vmax_kmh;vavg_kmh;distance_km;energy_wh;duration_s\n"
        val line = listOf(
            stamp, detectedModel.displayName, activeProtocol.displayName,
            String.format(Locale.US, "%.2f", tripData.maxSpeedKmh),
            String.format(Locale.US, "%.2f", tripData.avgSpeedKmh),
            String.format(Locale.US, "%.3f", tripData.distanceKm),
            String.format(Locale.US, "%.2f", tripData.energyWh),
            String.format(Locale.US, "%.0f", tripData.durationMillis / 1000.0)
        ).joinToString(";")
        val csv = header + line + "\n"
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "syrak_trip_$stamp.csv")
            FileOutputStream(file).use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Syrak Trip CSV", csv))
            log("> trip: exportiert -> ${file.absolutePath}")
            AlertDialog.Builder(this)
                .setTitle("Fahrdaten exportiert")
                .setMessage(
                    "Datei:\n${file.absolutePath}\n\n" +
                    "Der CSV-Inhalt liegt zusätzlich in der Zwischenablage (sofort teilbar)."
                )
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        } catch (e: Exception) {
            log("> trip: Export FEHLER — ${e.message}")
            Toast.makeText(this, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startDemoTelemetry() {
        stopTelemetry()
        telemetryRunnable = object : Runnable {
            override fun run() {
                simTick++
                val speed = 26.0 + 4.0 * Math.sin(simTick / 6.0) + (Math.random() - 0.5)
                val volt = 40.6 - (simTick % 20) * 0.01
                val current = 8.0 + 5.0 * Math.abs(Math.sin(simTick / 4.0))
                val motorTemp = 41.0 + 3.0 * Math.abs(Math.sin(simTick / 10.0))
                val cells = (0 until 10).map { i ->
                    (3.640 + Math.sin((simTick + i) / 5.0) * 0.012).toFloat()
                }
                applyTelemetryToDashboard(
                    Telemetry(
                        speedKmh = speed.toFloat(),
                        voltageV = volt.toFloat(),
                        currentA = current.toFloat(),
                        batteryPercent = 87 - (simTick / 120),
                        motorTempC = motorTemp.toFloat(),
                        cellMinV = cells.minOrNull(),
                        cellMaxV = cells.maxOrNull(),
                        cellVoltages = cells,
                        designCapacityMah = 15300,
                        fullChargeCapacityMah = 14200 - (simTick / 60),
                        cycleCount = 84,
                        rawHex = "(demo)",
                        source = activeProtocol
                    )
                )
                dashRange?.text = String.format(Locale.US, "%.1f KM", 26.1 - simTick * 0.001)
                handler.postDelayed(this, 500)
            }
        }
        telemetryRunnable?.let { handler.postDelayed(it, 400) }
    }

    private fun evaluateMotorGuard(tempC: Double) {
        if (!motorTempGuardEnabled) return
        if (tempC >= motorTempGuardC && !motorGuardTripped) {
            motorGuardTripped = true
            // FIX 4: KEIN harter Notaus. Faultolerante Power-Rampe: sanft drosseln,
            // echter Cut erst bei verifizierter Stillstandnaehe (speedKmh < 3 km/h).
            beginSafeStopRamp("motor-temp-guard", 8)
            dashTempGuardState?.text = "🛑 Motorschutz AUSGELÖST (${String.format(Locale.US, "%.0f", tempC)} °C)"
            dashTempGuardState?.setTextColor(Palette.ACCENT_RED)
        } else if (tempC < motorTempGuardC - 5 && motorGuardTripped) {
            motorGuardTripped = false
            endSafeStopRamp()
            dashTempGuardState?.text = "🛡 Motorschutz aktiv (Schwelle $motorTempGuardC °C)"
            dashTempGuardState?.setTextColor(Palette.ACCENT_GREEN)
        }
    }

    /**
     * FIX 4: Gesicherte Notaus-Rampe. Statt eines harten emergencyCut() wird die
     * Leistung in festen Ticks schrittweise auf das legale Limit reduziert. Ein
     * echter "cut" (Notaus) wird erst gesendet, wenn die Telemetrie einen
     * verifizierten Stillstand (speedKmh < RAMP_CUT_SPEED_KMH) meldet. Kann die
     * Geschwindigkeit nicht verifiziert werden, wird weiter sanft gedrosselt und
     * NIE blind hart abgeschaltet (Fahrersicherheit).
     */
    private fun beginSafeStopRamp(reason: String, legalKmh: Int) {
        if (rampActive && rampReason == reason) return
        endSafeStopRamp()
        rampActive = true
        rampReason = reason
        rampTicks = 0
        val target = legalKmh.coerceAtLeast(0)
        log("> safety: Power-Rampe gestartet ($reason) -> Ziel ${target} km/h, kein harter Cut")
        val r = object : Runnable {
            override fun run() {
                if (!rampActive) return
                rampTicks++
                val speed = lastTelemetry?.speedKmh
                val verifiedStopped = speed != null && speed < RAMP_CUT_SPEED_KMH
                if (verifiedStopped) {
                    // Verifizierter Stillstand -> jetzt ist ein echter Cut sicher.
                    sendFrame(bleEngine?.adapter?.buildEmergencyCutFrame(), "$reason-cut")
                    log("> safety: Stillstand verifiziert (${String.format(Locale.US, "%.1f", speed)} km/h) -> Notaus gesendet")
                    endSafeStopRamp()
                    return
                }
                // Sonst: sanft weiter drosseln (nie hart abschalten).
                sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(target), "$reason-ramp")
                if (rampTicks >= RAMP_MAX_TICKS) {
                    log("> safety: Rampe beendet nach $RAMP_MAX_TICKS Ticks -> Drosselung aktiv, kein Cut (Geschwindigkeit nicht verifiziert)")
                    endSafeStopRamp()
                    return
                }
                handler.postDelayed(this, RAMP_TICK_MS)
            }
        }
        rampRunnable = r
        handler.postDelayed(r, RAMP_TICK_MS)
    }

    private fun endSafeStopRamp() {
        rampRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null
        rampActive = false
        rampReason = null
        rampTicks = 0
    }

    private fun stopTelemetry() {
        telemetryRunnable?.let { handler.removeCallbacks(it) }
        telemetryRunnable = null
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: FLASH                                                         */
    /* ------------------------------------------------------------------ */
    private var flashProgressBar: ProgressBar? = null
    private var flashStatusView: TextView? = null

    private fun renderFlashTab() {
        // ---- FIRMWARE-QUELLE (Community-Plattformen) ----------------------
        tabContent.addView(makeSectionLabel("FIRMWARE-QUELLE"))
        val srcCard = makeCard()
        srcCard.addView(makeText("Community-Plattform wählen", 14f, Color.WHITE, true))
        srcCard.addView(makeText(
            "Die Empfehlungen & Pakete richten sich nach der gewählten Quelle.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(3), 0, dp(10)) })
        val srcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        FirmwareSource.values().forEach { source ->
            val active = selectedSource == source
            val chip = makeText(source.displayName, 10f,
                if (active) Color.WHITE else Palette.TEXT_SECONDARY, true).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (active) Palette.ACCENT_BLUE else Palette.SURFACE_DEEP)
                }
                setOnClickListener { selectedSource = source; renderTab(Tab.FLASH) }
            }
            srcRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(6), 0) })
        }
        val srcScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        srcScroll.addView(srcRow)
        srcCard.addView(srcScroll)
        srcCard.addView(makeText("${selectedSource.description}\n${selectedSource.reference}",
            11f, Palette.ACCENT_CYAN).apply { setPadding(0, dp(10), 0, 0) })
        tabContent.addView(srcCard, cardParams())

        val recommendations = FirmwareCatalog.fromSource(selectedSource, detectedModel)
        val recommended = recommendations.firstOrNull() ?: FirmwareCatalog.recommended(detectedModel)

        tabContent.addView(makeSectionLabel("FLASH-MODUS"))
        val modeCard = makeCard()
        modeCard.addView(makeText(
            "Wähle zwischen gefahrlosem RAM-Overlay und echtem Firmware-Flash.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, 0, 0, dp(10)) })
        FlashMode.values().forEach { mode ->
            val isSel = flashMode == mode
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(if (isSel) Palette.SURFACE_DEEP else Palette.SURFACE_ALT)
            }
            row.addView(makeText("${if (isSel) "● " else "○ "}${mode.displayName}", 14f,
                if (isSel) Palette.ACCENT_GREEN else Color.WHITE, true))
            row.addView(makeText(mode.description, 11f, Palette.TEXT_MUTED).apply { setPadding(0, dp(4), 0, 0) })
            row.setOnClickListener { flashMode = mode; renderTab(Tab.FLASH) }
            modeCard.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) })
        }
        modeCard.addView(makeText(
            if (flashMode == FlashMode.HARD_FLASH)
                "⚠ HARD-FLASH: schreibt .bin-Sektoren dauerhaft. Vor jedem Flash erscheint ein Bestätigungsdialog."
            else "✔ SOFT-PATCH: Änderungen laufen als flüchtiges RAM-Overlay.",
            11f, if (flashMode == FlashMode.HARD_FLASH) Palette.ACCENT_RED else Palette.ACCENT_GREEN, true
        ))
        tabContent.addView(modeCard, cardParams())

        tabContent.addView(makeSectionLabel("PAKETE DER QUELLE ${selectedSource.displayName.uppercase(Locale.ROOT)}"))
        val recCard = makeCard()
        if (recommended != null) {
            recCard.addView(makeText("⭐ ${recommended.name} ${recommended.version}", 15f, Palette.ACCENT_GREEN, true))
            recCard.addView(makeText(
                "Target: ${recommended.target}  •  Empf. Max: ${if (recommended.recommendedMaxKmh > 0) "${recommended.recommendedMaxKmh} km/h" else "—"}\n${recommended.notes}",
                12f, Palette.TEXT_SECONDARY
            ).apply { setPadding(0, dp(6), 0, dp(10)) })
            recCard.addView(makePrimaryButton("EMPFOHLENES PAKET WÄHLEN", Palette.ACCENT_GREEN) {
                selectedFirmware = recommended; selectedFirmwareUri = null
                selectedFirmwareName = "${recommended.name} ${recommended.version}"
                loadedFirmwareBytes = null; lastValidation = null
                renderTab(Tab.FLASH)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        } else recCard.addView(makeText("Keine Empfehlung verfügbar.", 13f, Palette.TEXT_MUTED))
        tabContent.addView(recCard, cardParams())

        if (recommendations.size > 1) {
            val listCard = makeCard()
            listCard.addView(makeText("Weitere Pakete dieser Quelle", 13f, Color.WHITE, true).apply {
                setPadding(0, 0, 0, dp(8))
            })
            recommendations.drop(1).forEach { pkg ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                row.addView(makeText("${pkg.name} ${pkg.version}", 12f, Palette.TEXT_SECONDARY),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(makePrimaryButton("WÄHLEN", Palette.SURFACE) {
                    selectedFirmware = pkg; selectedFirmwareUri = null
                    selectedFirmwareName = "${pkg.name} ${pkg.version}"
                    loadedFirmwareBytes = null; lastValidation = null
                    renderTab(Tab.FLASH)
                }, LinearLayout.LayoutParams(dp(90), dp(38)))
                listCard.addView(row)
            }
            tabContent.addView(listCard, cardParams())
        }

        tabContent.addView(makeSectionLabel("EIGENES PAKET (.bin / .zip)"))
        val pkg = makeCard()
        pkg.addView(makeText(
            "Wähle eine .bin- oder .zip-Firmware. Die Datei wird auf ZIP-Struktur, Modell-Kompatibilität und SHA-256 geprüft. " +
            "Der .bin-Inhalt wird als echte Byte-Sequenz blockweise über BLE geschrieben.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, 0, 0, dp(12)) })
        pkg.addView(makePrimaryButton("FIRMWARE WÄHLEN", Palette.SURFACE) {
            firmwarePicker.launch(arrayOf("*/*"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(pkg, cardParams())

        tabContent.addView(makeSectionLabel("STATUS & VALIDIERUNG"))
        val statusCard = makeCard()
        statusCard.addView(makeText(
            selectedFirmwareName?.let { "Ausgewählt: $it" } ?: "Kein Paket ausgewählt",
            13f, if (selectedFirmwareName != null) Palette.ACCENT_GREEN else Palette.TEXT_MUTED, true
        ))
        val validation = lastValidation
        flashStatusView = makeText(
            validation?.let { "${if (it.valid) "✔" else "✘"} ${it.message}" } ?: "Noch keine Validierung durchgeführt.",
            11f, when {
                validation == null -> Palette.TEXT_MUTED
                validation.valid -> Palette.ACCENT_GREEN
                else -> Palette.ACCENT_RED
            }
        ).apply { setPadding(0, dp(6), 0, 0) }
        statusCard.addView(flashStatusView)
        val sha = validation?.sha256
        if (sha != null && sha != "remote") {
            statusCard.addView(makeText("SHA-256: $sha", 10f, Palette.TEXT_MUTED).apply {
                setPadding(0, dp(4), 0, 0); setTypeface(Typeface.MONOSPACE)
            })
        }
        flashProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        statusCard.addView(flashProgressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { setMargins(0, dp(12), 0, dp(12)) })

        statusCard.addView(makePrimaryButton(
            if (flashMode == FlashMode.HARD_FLASH) "ECHTEN FIRMWARE-FLASH STARTEN" else "SOFT-PATCH ANWENDEN"
        ) {
            startFlashFlow()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(statusCard, cardParams())

        tabContent.addView(makeText(
            "⚠ Flashen überschreibt die DRV-Firmware. Akku > 50 % und stabile Verbindung erforderlich. " +
            "Ein Abbruch während des Schreibens kann den Controller unbrauchbar machen.",
            12f, Palette.ACCENT_RED
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun startFlashFlow() {
        if (selectedFirmwareName == null) {
            Toast.makeText(this, "Bitte zuerst ein Paket auswählen.", Toast.LENGTH_SHORT).show(); return
        }
        if (lastValidation?.valid == false) {
            Toast.makeText(this, "Validierung fehlgeschlagen — Flash blockiert.", Toast.LENGTH_LONG).show(); return
        }
        if (bleEngine == null || connectedDevice?.isDemo == true) {
            // Demo: nur UI-Feedback, KEIN Fake-Erfolg
            Toast.makeText(this, "Demo-Modus: Es wird kein echter Flash gesendet.", Toast.LENGTH_LONG).show()
            flashStatusView?.text = "Demo-Modus — kein echter Flash."
            flashStatusView?.setTextColor(Palette.ACCENT_ORANGE)
            return
        }
        if (flashMode == FlashMode.HARD_FLASH) confirmHardFlash() else doFlash()
    }

    private fun confirmHardFlash() {
        AlertDialog.Builder(this)
            .setTitle("⚠ ACHTUNG: ECHTES FIRMWARE-FLASHEN!")
            .setMessage(
                "Das Schreiben verändert den Controller-Speicher dauerhaft. Nutzung auf 100 % eigene Verantwortung!\n\n" +
                "Paket: ${selectedFirmwareName}\n" +
                "Ziel: MCU/ESC (.bin-Sektoren) via ${(bleEngine?.protocol ?: activeProtocol).displayName}\n\n" +
                "Ein Abbruch während des Schreibens kann den Controller unbrauchbar machen."
            )
            .setPositiveButton("Ich verstehe — JETZT FLASHEN") { _, _ -> doFlash() }
            .setNegativeButton("Abbrechen") { _, _ -> log("> flash: hard-flash abgebrochen") }
            .setCancelable(false)
            .show()
    }

    private fun doFlash() {
        val bytes = loadedFirmwareBytes
        if (bytes == null || bytes.isEmpty()) {
            Toast.makeText(this, "Keine Firmware-Bytes geladen.", Toast.LENGTH_SHORT).show(); return
        }
        val adapter = bleEngine?.adapter ?: ProtocolAdapters.forProtocol(activeProtocol)
        val unlock: ByteArray? = adapter.buildUnlockFrame()
        val reboot: ByteArray? = adapter.buildRebootFrame()
        flashProgressBar?.progress = 0
        flashStatusView?.text = "Starte echten Flash…"
        flashStatusView?.setTextColor(Palette.TEXT_SECONDARY)
        bleEngine?.startFlash(bytes, unlock, reboot)
    }

    /* ------------------------------------------------------------------ */
    /*  FIRMWARE-AUSWAHL & VALIDIERUNG                                     */
    /* ------------------------------------------------------------------ */
    private fun handleSelectedFirmware(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin"
        selectedFirmwareName = name
        selectedFirmwareUri = uri
        selectedFirmware = null
        val validation = validateFirmware(uri)
        lastValidation = validation
        loadedFirmwareBytes = validation.extractedBinary
        log("> flash: Paket $name — ${validation.message}")
        Toast.makeText(this, if (validation.valid) "Firmware geprüft: OK" else "Prüfung fehlgeschlagen",
            Toast.LENGTH_SHORT).show()
        if (currentTab == Tab.FLASH) renderTab(Tab.FLASH)
    }

    /**
     * Echte Integritätsprüfung:
     *  • Datei lesbar (streaming, kein Vollpuffer für SHA)
     *  • .bin direkt ODER ZIP mit enthaltener Firmware
     *  • Modell-Kompatibilität
     *  • SHA-256-Fingerprint
     * Liefert zusätzlich die extrahierten Firmware-Bytes für den Flash.
     */
    /**
     * FIX 7: ZIP-Bomb-Schutz. Liest hoechstens [cap] Bytes aus [input]. Wird das
     * Limit ueberschritten, wird SOFORT abgebrochen (Rueckgabe null) -> niemals
     * ein unbegrenzter Speicherverbrauch durch praeparierte Archive.
     */
    private fun readCappedStream(input: java.io.InputStream, cap: Int): ByteArray? {
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) return null
                out.write(buf, 0, n)
            }
        } catch (e: Exception) {
            return null
        }
        return out.toByteArray()
    }

    private fun validateFirmware(uri: Uri): FirmwareValidation {
        val bytes: ByteArray? = try {
            contentResolver.openInputStream(uri)?.use { readCappedStream(it, MAX_FIRMWARE_BYTES) }
        } catch (e: Exception) {
            return FirmwareValidation(false, "Lesefehler: ${e.message}", "", false, false, null)
        }
        if (bytes == null) return FirmwareValidation(false,
            "Datei nicht lesbar oder zu gross (Limit ${MAX_FIRMWARE_BYTES / 1024} KB).", "", false, false, null)
        if (bytes.isEmpty()) return FirmwareValidation(false, "Datei ist leer.", "", false, false, null)

        val sha = sha256(bytes)
        val isZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

        // Direkte .bin
        if (!isZip) {
            val looksLikeBin = selectedFirmwareName?.lowercase(Locale.ROOT)?.endsWith(".bin") == true ||
                    bytes.size >= 64
            return if (looksLikeBin) {
                FirmwareValidation(true, "Direkte .bin akzeptiert (${bytes.size} Bytes).", sha, false, true, bytes)
            } else {
                FirmwareValidation(false, "Kein gültiges ZIP und keine erkennbare .bin.", sha, false, false, null)
            }
        }

        // ZIP entpacken -> Firmware-Eintrag suchen
        var firmwareEntry: ByteArray? = null
        var firmwareName: String? = null
        val entries = mutableListOf<String>()
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                var guard = 0
                while (entry != null && guard < 500) {
                    val lname = entry.name.lowercase(Locale.ROOT)
                    entries.add(lname)
                    if (!entry.isDirectory && firmwareEntry == null && (
                            lname.endsWith(".bin") || lname.endsWith(".fw") || lname.endsWith(".shfw") ||
                            lname.endsWith(".nbw") || lname.endsWith(".navee") ||
                            lname.contains("drv") || lname.contains("esc") || lname.contains("bms")
                            )
                    ) {
                        val read = readCappedStream(zis, MAX_FIRMWARE_BYTES)
                        if (read == null) {
                            return FirmwareValidation(false,
                                "Firmware-Eintrag zu gross (Limit ${MAX_FIRMWARE_BYTES / 1024} KB).",
                                sha, true, false, null)
                        }
                        firmwareEntry = read
                        firmwareName = lname
                    }
                    entry = zis.nextEntry
                    guard++
                }
            }
        } catch (e: Exception) {
            return FirmwareValidation(false, "ZIP beschädigt: ${e.message}", sha, true, false, null)
        }

        if (firmwareEntry == null) {
            return FirmwareValidation(false, "ZIP enthält keine erkennbare Firmware-Datei.", sha, true, false, null)
        }

        val modelTokens = detectedModel.matchKeywords.ifEmpty { listOf("generic") }
        val joined = (entries + selectedFirmwareName.orEmpty() + (firmwareName ?: "")).joinToString(" ")
        val isNaveeModel = detectedModel.protocol == BleProtocol.NAVEE_BRIGHTWAY
        val naveePackage = joined.contains("navee") || joined.contains("brightway")
        val modelCompatible = detectedModel == ScooterModel.UNKNOWN ||
                modelTokens.any { joined.contains(it) } ||
                (naveePackage && isNaveeModel) ||
                (joined.contains("shfw") && detectedModel in listOf(
                    ScooterModel.NINEBOT_MAX_G2, ScooterModel.NINEBOT_MAX_G30, ScooterModel.NINEBOT_F2_PRO,
                    ScooterModel.NINEBOT_ES, ScooterModel.XIAOMI_PRO_2, ScooterModel.XIAOMI_M365
                ))
        if (!modelCompatible) {
            return FirmwareValidation(false, "Modell-Konflikt: Paket passt nicht zu ${detectedModel.displayName}.",
                sha, true, false, null)
        }
        return FirmwareValidation(
            true,
            "Integrität OK — Firmware '${firmwareName}' (${firmwareEntry!!.size} Bytes) extrahiert.",
            sha, true, true, firmwareEntry!!
        )
    }

    private fun sha256(bytes: ByteArray): String = try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "n/a" }

    /* ------------------------------------------------------------------ */
    /*  POST-FLASH-DIALOG                                                  */
    /* ------------------------------------------------------------------ */
    private fun showPostFlashDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        container.addView(makeText(
            "Modell: ${detectedModel.displayName}  •  Protokoll: ${(bleEngine?.protocol ?: activeProtocol).displayName}",
            12f, Palette.TEXT_SECONDARY
        ))
        container.addView(makeText(
            "Easy-Speed: ${detectedModel.easySpeedKmh} km/h  •  techn. Max: ${detectedModel.absMaxKmh} km/h",
            12f, Palette.ACCENT_GREEN, true
        ).apply { setPadding(0, dp(4), 0, dp(12)) })

        val alarmState = makeText(
            if (overspeedAlarmMuted) "AUS (stumm)" else "AN",
            13f, if (overspeedAlarmMuted) Palette.TEXT_MUTED else Palette.ACCENT_GREEN, true
        )
        val alarmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(2))
        }
        alarmRow.addView(makeText("Overspeed-Piepton (ab ${detectedModel.easySpeedKmh} km/h)", 13f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        alarmRow.addView(alarmState)
        alarmRow.setOnClickListener {
            overspeedAlarmMuted = !overspeedAlarmMuted
            alarmState.text = if (overspeedAlarmMuted) "AUS (stumm)" else "AN"
            alarmState.setTextColor(if (overspeedAlarmMuted) Palette.TEXT_MUTED else Palette.ACCENT_GREEN)
            val frame = bleEngine?.adapter?.buildOverspeedMuteFrame(overspeedAlarmMuted)
            ramOverlay.apply("overspeed-alarm", frame)
            sendFrame(frame, "overspeed-alarm")
        }
        container.addView(alarmRow)
        container.addView(makeText(
            "Navee/Brightway: CMD 0x14 (AA 55 14 01 …)  •  Ninebot/Xiaomi: CMD 0x7F",
            10f, Palette.TEXT_MUTED
        ))

        container.addView(makeText("Ziel-Geschwindigkeit: $maxSpeedKmh km/h", 13f, Color.WHITE, true)
            .apply { setPadding(0, dp(14), 0, dp(4)) })
        val speedBar = SeekBar(this).apply {
            max = (detectedModel.absMaxKmh - 15).coerceAtLeast(1)
            progress = (maxSpeedKmh - 15).coerceIn(0, max)
        }
        container.addView(speedBar)

        AlertDialog.Builder(this)
            .setTitle("⚙ Post-Flash-Einstellungen")
            .setView(container)
            .setPositiveButton("Übernehmen") { _, _ -> applyPostFlashSpeed(speedBar.progress + 15) }
            .setNegativeButton("Später") { _, _ -> }
            .setCancelable(true)
            .show()
    }

    private fun applyPostFlashSpeed(target: Int) {
        val easy = detectedModel.easySpeedKmh
        val absMax = detectedModel.absMaxKmh
        if (target <= easy) {
            maxSpeedKmh = target
            sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(target), "max-speed")
        } else confirmThermalSpeed(target, easy, absMax)
    }

    private fun confirmThermalSpeed(requested: Int, easy: Int, absMax: Int) {
        val capped = requested.coerceAtMost(absMax)
        AlertDialog.Builder(this)
            .setTitle("⚠ Thermische Belastungsgrenze")
            .setMessage(
                "Achtung: Ihr ${detectedModel.displayName} ist herstellerseitig nicht für diese Belastung ausgelegt. " +
                "Bei Überschreitung von $easy km/h droht thermische Überlastung. Nutzung auf eigene Gefahr!\n\n" +
                "Angefordert: $requested km/h (techn. Max: $absMax km/h)"
            )
            .setPositiveButton("Freischalten & senden") { _, _ ->
                extendedPowerUnlocked = true
                maxSpeedKmh = capped
                val maxFrame = bleEngine?.adapter?.buildSpeedCeilingFrame(capped)
                val powerFrame = bleEngine?.adapter?.buildMaxPowerFrame()
                ramOverlay.apply("extended-power", powerFrame)
                ramOverlay.apply("max-speed-$capped", maxFrame)
                sendFrame(maxFrame, "max-speed")
                sendFrame(powerFrame, "max-power")
            }
            .setNegativeButton("Abbrechen") { _, _ ->
                maxSpeedKmh = easy
                sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(easy), "max-speed")
            }
            .setCancelable(false)
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: CONFIG                                                        */
    /* ------------------------------------------------------------------ */
    private fun renderConfigTab() {
        tabContent.addView(makeSectionLabel("PROFILE"))
        val profileCard = makeCard()
        profileCard.addView(makeText("Aktuelles Profil", 15f, Color.WHITE, true))
        profileCard.addView(makeText(
            "Max $maxSpeedKmh km/h  •  Zero-Start $zeroStartKmh km/h  •  KERS $kersLevel  •  Tempomat ${if (cruiseEnabled) "AN" else "AUS"}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(12)) })
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val nameInput = EditText(this).apply {
            hint = "Profilname"; setTextColor(Color.WHITE); setHintTextColor(Palette.TEXT_MUTED); textSize = 12f
            setBackgroundColor(Palette.SURFACE_DEEP); setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameRow.addView(nameInput)
        nameRow.addView(makePrimaryButton("SPEICHERN", Palette.ACCENT_GREEN) {
            val name = nameInput.text.toString().trim().ifBlank { "Profil ${System.currentTimeMillis() % 1000}" }
            saveProfile(name); nameInput.setText("")
            Toast.makeText(this, "Profil '$name' gespeichert", Toast.LENGTH_SHORT).show()
            renderTab(Tab.CONFIG)
        }, LinearLayout.LayoutParams(dp(110), dp(44)).apply { setMargins(dp(8), 0, 0, 0) })
        profileCard.addView(nameRow)
        tabContent.addView(profileCard, cardParams())

        val saved = repository.profileEntries()
        if (saved.isNotEmpty()) {
            tabContent.addView(makeSectionLabel("GESPEICHERT"))
            val listCard = makeCard()
            saved.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(makeText(entry, 13f, Color.WHITE, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(makePrimaryButton("LADEN", Palette.SURFACE) { loadProfile(entry) },
                    LinearLayout.LayoutParams(dp(90), dp(38)))
                listCard.addView(row)
            }
            tabContent.addView(listCard, cardParams())
        }

        tabContent.addView(makeSectionLabel("VERBINDUNG"))
        val connCard = makeCard()
        connCard.addView(makeToggleRow(
            "Bluetooth Auto-Reconnect",
            "Stellt die Verbindung nach einem Abbruch automatisch wieder her (max. 3 Versuche).",
            autoReconnectEnabled
        ) { on ->
            autoReconnectEnabled = on
            repository.autoReconnect = on
        })
        tabContent.addView(connCard, cardParams())

        val danger = makeCard()
        danger.addView(makeText("Verbindung", 15f, Color.WHITE, true))
        danger.addView(makeText("Trennt die BLE-Verbindung und kehrt zur Gerätesuche zurück.", 12f, Palette.TEXT_MUTED)
            .apply { setPadding(0, dp(4), 0, dp(12)) })
        danger.addView(makePrimaryButton("DISCONNECT", Palette.SURFACE) { disconnectAndReturn() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(danger, cardParams())
    }

    private fun saveProfile(name: String) {
        repository.saveProfile(ScooterProfile(name, maxSpeedKmh, zeroStartKmh, kersLevel, cruiseEnabled))
    }

    private fun loadProfile(entry: String) {
        val p = repository.loadProfile(entry) ?: return
        maxSpeedKmh = p.maxSpeed
        zeroStartKmh = p.zeroStart
        kersLevel = p.kers
        cruiseEnabled = p.cruiseControl
        sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(maxSpeedKmh), "max-speed")
        sendFrame(bleEngine?.adapter?.buildZeroStartFrame(zeroStartKmh), "zero-start")
        sendFrame(bleEngine?.adapter?.buildKersFrame(kersLevel), "kers")
        sendFrame(bleEngine?.adapter?.buildCruiseFrame(cruiseEnabled), "cruise")
        Toast.makeText(this, "Profil '${p.name}' geladen", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectAndReturn() {
        userInitiatedDisconnect = true
        cancelReconnectRunnable()
        endSafeStopRamp()
        bleEngine?.disconnect()
        bleEngine = null
        connectedDevice = null
        ramOverlay.flush()
        showScanScreen()
    }

    /* ------------------------------------------------------------------ */
    /*  TUNING-AKTIONEN                                                    */
    /* ------------------------------------------------------------------ */
    /** Labels, vor denen auf einem Brightway-Controller der Unlock-Handshake laufen MUSS. */
    private val unlockGatedLabels = setOf(
        "max-speed", "zero-start", "german-maneuver", "stealth-unlock",
        "police-mode", "police-speed", "unlock", "kers", "cruise", "max-power"
    )

    private fun sendFrame(frame: ByteArray?, label: String) {
        val engine = bleEngine
        if (engine == null || connectedDevice?.isDemo == true) {
            // Demo / nicht verbunden: KEIN Fake-Erfolg, klare Kennzeichnung
            log("> tx[$label]: (Demo/offline) — nicht gesendet")
            return
        }
        if (frame == null) {
            log("> tx[$label]: Befehl wird vom aktiven Adapter (${engine.adapter.displayName}) nicht unterstuetzt")
            return
        }
        // Der Frame wurde vom aktiven ScooterProtocolAdapter gebaut. Die Engine
        // uebernimmt Fallback-Kette + Unlock-Handshake-Gating (falls gesperrt).
        engine.enqueueCommand(frame, label, label in unlockGatedLabels)
    }

    /** Liest die aktuellen Limits als Register-Snapshot (echter Read-Request + Cache). */
    private fun snapshotRegisters() {
        val engine = bleEngine
        if (engine == null || connectedDevice?.isDemo == true) {
            registerSnapshot = RegisterSnapshot(
                speedCeiling = maxSpeedKmh, zeroStart = zeroStartKmh, kers = kersLevel,
                cruise = cruiseEnabled, locked = isLocked
            )
            return
        }
        // Echter Lese-Request; der aktuelle UI-Zustand dient als Fallback-Snapshot,
        // bis die Antwort per Notify eintrifft (Decode über TelemetryDecoder).
        registerSnapshot = RegisterSnapshot(
            speedCeiling = maxSpeedKmh, zeroStart = zeroStartKmh, kers = kersLevel,
            cruise = cruiseEnabled, locked = isLocked
        )
        engine.enqueueCommand(engine.adapter.buildReadStatusFrame(), "read-status", false)
        log("> snapshot: Register-Snapshot gesichert (Speed=$maxSpeedKmh, Zero=$zeroStartKmh, KERS=$kersLevel)")
    }

    private fun engagePoliceMode() {
        val start = System.currentTimeMillis()
        val flushed = ramOverlay.flush()
        germanManeuverActive = false
        extendedPowerUnlocked = false
        maxSpeedKmh = currentRegion.legalSpeedKmh

        // Echte Drossel-Frames
        sendFrame(bleEngine?.adapter?.buildPoliceModeFrame(currentRegion.legalSpeedKmh), "police-mode")
        sendFrame(bleEngine?.adapter?.buildSpeedCeilingFrame(currentRegion.legalSpeedKmh), "police-speed")
        // FIX 4: KEIN harter Notaus. Stattdessen faultolerante Power-Rampe:
        // erst auf das legale Limit drosseln, echter Cut erst bei verifizierter
        // Stillstandnaehe (speedKmh < 3 km/h).
        beginSafeStopRamp("police-mode", currentRegion.legalSpeedKmh)

        // Ursprungs-Bytes wiederherstellen, falls Snapshot vorhanden
        registerSnapshot?.let { snap ->
            snap.zeroStart?.let { sendFrame(bleEngine?.adapter?.buildZeroStartFrame(it), "restore-zero") }
            snap.kers?.let { sendFrame(bleEngine?.adapter?.buildKersFrame(it), "restore-kers") }
            log("> police: Ursprungs-Register wiederhergestellt (Zero=${snap.zeroStart}, KERS=${snap.kers})")
        }
        val elapsed = System.currentTimeMillis() - start
        log("> police: RAM-Overlay geflusht (${flushed.size} Patches) in $elapsed ms")
        log("> police: Limit -> ${currentRegion.legalSpeedKmh} km/h (${currentRegion.displayName})")

        AlertDialog.Builder(this)
            .setTitle("🚨 Police Mode ausgelöst")
            .setMessage(
                "RAM-Overlay geflusht in $elapsed ms.\n\n" +
                "Gelöschte Patches: ${if (flushed.isEmpty()) "keine" else flushed.joinToString()}\n" +
                "Limit zurückgesetzt: ${currentRegion.displayName} = ${currentRegion.legalSpeedKmh} km/h."
            )
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun triggerPanicHotkey() {
        log("> hotkey: Panic (${detectedModel.panicHotkey})")
        engagePoliceMode()
    }

    private fun triggerStealthUnlock() {
        log("> hotkey: Stealth Unlock (${detectedModel.stealthUnlockHotkey})")
        snapshotRegisters()
        val frame = bleEngine?.adapter?.buildGermanManeuverFrame()
        ramOverlay.apply("stealth-unlock", frame)
        sendFrame(frame, "stealth-unlock")
        germanManeuverActive = true
    }

    private fun showLockCodeDialog() {
        val input = EditText(this).apply {
            hint = "PIN (4 Ziffern)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Palette.TEXT_MUTED)
            setBackgroundColor(Palette.SURFACE_DEEP); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val title = if (isLocked) "Scooter entsperren" else "Wegfahrsperre aktivieren"
        val msg = if (isLocked) "Gib den PIN ein, um den Scooter zu entsperren."
        else "Lege einen 4-stelligen PIN fest."
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg).setView(input)
            .setPositiveButton(if (isLocked) "Entsperren" else "Sperren") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length != 4) {
                    Toast.makeText(this, "PIN muss 4 Ziffern haben.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isLocked) {
                    if (repository.verifyPin(pin)) {
                        isLocked = false
                        repository.isLocked = false
                        sendFrame(bleEngine?.adapter?.buildLockFrame(false), "unlock")
                        Toast.makeText(this, "Scooter entsperrt", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "Falscher PIN.", Toast.LENGTH_SHORT).show()
                } else {
                    repository.setPin(pin)
                    isLocked = true
                    repository.isLocked = true
                    sendFrame(bleEngine?.adapter?.buildLockFrame(true), "lock")
                    Toast.makeText(this, "Wegfahrsperre aktiv", Toast.LENGTH_SHORT).show()
                }
                if (currentTab == Tab.TOOLS) renderTab(Tab.TOOLS)
            }
            .setNegativeButton("Abbrechen") { d, _ -> d.dismiss() }
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  NEO-HACKER PRO: HEADER-SYSTEMLEISTE                                */
    /* ------------------------------------------------------------------ */
    private fun buildSystemBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6)); setBackgroundColor(Palette.SURFACE_DEEP)
        }
        fun cell(icon: String, initial: String): TextView {
            val v = makeText("$icon $initial", 10f, Palette.TEXT_SECONDARY, true).apply {
                setTypeface(Typeface.MONOSPACE); setPadding(0, 0, dp(14), 0)
            }
            bar.addView(v)
            return v
        }
        sysLinkView = cell("●", "LINK IDLE")
        sysRssiView = cell("📶", "RSSI --")
        sysMtuView = cell("🔗", "MTU --")
        sysQueueView = cell("⏳", "QUEUE 0/0")
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        return bar
    }

    private fun startSystemBar() {
        stopSystemBar()
        val r = object : Runnable {
            override fun run() {
                updateSystemBar()
                bleEngine?.readRssi()
                handler.postDelayed(this, 1500)
            }
        }
        sysBarRunnable = r
        handler.postDelayed(r, 300)
    }

    private fun stopSystemBar() {
        sysBarRunnable?.let { handler.removeCallbacks(it) }
        sysBarRunnable = null
    }

    private fun updateSystemBar() {
        try {
            val engine = bleEngine
            sysRssiView?.text = if (lastRssi == Int.MIN_VALUE) "RSSI --" else "RSSI $lastRssi dBm"
            sysRssiView?.setTextColor(when {
                lastRssi == Int.MIN_VALUE -> Palette.TEXT_MUTED
                lastRssi >= -67 -> Palette.ACCENT_GREEN
                lastRssi >= -80 -> Palette.ACCENT_ORANGE
                else -> Palette.ACCENT_RED
            })
            sysMtuView?.text = "MTU ${engine?.currentMtu() ?: 23}"
            val depth = engine?.queueDepth() ?: 0
            val inFlight = engine?.isWriteInFlight() == true
            sysQueueView?.text = "QUEUE $depth/${if (inFlight) 1 else 0}"
            sysQueueView?.setTextColor(if (depth > 4) Palette.ACCENT_ORANGE else Palette.ACCENT_CYAN)
            sysLinkView?.text = "LINK $linkState"
            sysLinkView?.setTextColor(when (linkState) {
                "READY" -> Palette.ACCENT_GREEN
                "RECONNECT" -> Palette.ACCENT_ORANGE
                "CONNECTING" -> Palette.ACCENT_CYAN
                "DISCONNECTED" -> Palette.ACCENT_RED
                "DEMO" -> Palette.ACCENT_BLUE
                else -> Palette.TEXT_MUTED
            })
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "updateSystemBar: ${e.message}")
        }
    }

    /* ------------------------------------------------------------------ */
    /*  NEO-HACKER PRO: LOG-TERMINAL                                       */
    /* ------------------------------------------------------------------ */
    private fun classifyLog(line: String): LogCategory {
        val l = line.lowercase(Locale.ROOT)
        return when {
            l.contains("fehler") || l.contains("✘") || l.contains("error") || l.contains("fail") -> LogCategory.ERROR
            l.contains("flash") -> LogCategory.FLASH
            l.contains("> tx") -> LogCategory.TX
            l.contains("> rx") || l.contains("notify") -> LogCategory.RX
            else -> LogCategory.INFO
        }
    }

    private fun log(line: String) {
        android.util.Log.d("SyrakScooterLab", line)
        // WICHTIG: onLog() wird teils von BLE-Binder-Threads aufgerufen.
        // UI-Zugriffe (logEntries / refreshLogView) MÜSSEN auf dem Main-Thread laufen.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendLogEntry(line)
        } else {
            handler.post { appendLogEntry(line) }
        }
    }

    private fun appendLogEntry(line: String) {
        // FIX 1: Nur den Datensatz anhaengen (O(1)); das (teure) Neu-Zeichnen des
        // Terminals wird gebuendelt (max. alle UI_REFRESH_INTERVAL_MS) geplant.
        try {
            val entry = LogEntry(
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                classifyLog(line),
                line
            )
            logEntries.addLast(entry)
            while (logEntries.size > 500) logEntries.removeFirst()
            scheduleLogRefresh()
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "appendLogEntry: ${e.message}")
        }
    }

    /**
     * FIX 1: Plant ein gebuendeltes Log-Refresh. Mehrere eingehende Zeilen innerhalb
     * des Intervalls loesen NUR EIN Neu-Zeichnen aus -> kein UI-Freeze bei Notify-Fluten.
     */
    private fun scheduleLogRefresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { scheduleLogRefresh() }
            return
        }
        if (logRefreshScheduled) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastLogRefresh
        if (elapsed >= UI_REFRESH_INTERVAL_MS) {
            lastLogRefresh = now
            refreshLogView()
            return
        }
        logRefreshScheduled = true
        handler.postDelayed({
            logRefreshScheduled = false
            lastLogRefresh = System.currentTimeMillis()
            refreshLogView()
        }, UI_REFRESH_INTERVAL_MS - elapsed)
    }

    /**
     * FIX 1: Plant ein gebuendeltes Telemetrie-Update auf dem Main-Thread. Der jeweils
     * zuletzt empfangene Wert wird gezeichnet -> maximale Fluessigkeit, minimale Last.
     */
    private fun scheduleTelemetryApply() {
        // onTelemetry() kommt vom BLE-Binder-Thread -> UI-Arbeit IMMER auf Main.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { scheduleTelemetryApply() }
            return
        }
        if (telemetryApplyScheduled) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastTelemetryApply
        if (elapsed >= UI_REFRESH_INTERVAL_MS) {
            lastTelemetryApply = now
            applyLatestTelemetry()
            return
        }
        telemetryApplyScheduled = true
        handler.postDelayed({
            telemetryApplyScheduled = false
            lastTelemetryApply = System.currentTimeMillis()
            applyLatestTelemetry()
        }, UI_REFRESH_INTERVAL_MS - elapsed)
    }

    private fun applyLatestTelemetry() {
        val t = lastTelemetry ?: return
        try {
            applyTelemetryToDashboard(t)
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "applyLatestTelemetry: ${e.message}")
        }
    }

    private fun refreshLogView() {
        try {
            val container = logContainer ?: return
            container.removeAllViews()
            val filter = logFilter
            val shown = logEntries.filter { filter == null || it.category == filter }
            if (shown.isEmpty()) {
                container.addView(makeText("Keine Einträge.", 11f, Palette.TEXT_MUTED))
            }
            for (e in shown) {
                val color = when (e.category) {
                    LogCategory.TX -> Palette.ACCENT_BLUE
                    LogCategory.RX -> Palette.ACCENT_CYAN
                    LogCategory.FLASH -> Palette.ACCENT_ORANGE
                    LogCategory.ERROR -> Palette.ACCENT_RED
                    LogCategory.INFO -> Palette.TEXT_SECONDARY
                }
                container.addView(makeText("${e.time} [${e.category.label}] ${e.text}", 10f, color).apply {
                    setTypeface(Typeface.MONOSPACE); setPadding(0, dp(2), 0, dp(2))
                })
            }
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        } catch (e: Exception) {
            android.util.Log.e("SyrakScooterLab", "refreshLogView: ${e.message}")
        }
    }

    private fun copyLogToClipboard() {
        val filter = logFilter
        val text = logEntries.filter { filter == null || it.category == filter }
            .joinToString("\n") { "${it.time} [${it.category.label}] ${it.text}" }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SyrakScooterLab Log", text))
        Toast.makeText(this, "Log kopiert (${logEntries.size} Einträge)", Toast.LENGTH_SHORT).show()
    }

    private fun buildLogTerminal(): LinearLayout {
        val card = makeCard()
        card.addView(makeText("🖥 Log-Terminal", 14f, Color.WHITE, true))
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8))
        }
        fun filterChip(label: String, cat: LogCategory?) {
            val active = logFilter == cat
            val chip = makeText(label, 10f, if (active) Color.WHITE else Palette.TEXT_MUTED, true).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (active) Palette.ACCENT_BLUE else Palette.SURFACE_DEEP)
                }
                setOnClickListener { logFilter = cat; refreshLogView() }
            }
            filterRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(6), 0) })
        }
        filterChip("ALLE", null)
        filterChip("NUR TX", LogCategory.TX)
        filterChip("NUR RX", LogCategory.RX)
        filterChip("NUR FLASH", LogCategory.FLASH)
        filterChip("NUR FEHLER", LogCategory.ERROR)
        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        filterScroll.addView(filterRow)
        card.addView(filterScroll)
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 10, 14))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logScroll?.addView(logContainer)
        card.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(180)
        ))
        card.addView(makePrimaryButton("LOG KOPIEREN", Palette.SURFACE) { copyLogToClipboard() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                setMargins(0, dp(8), 0, 0)
            })
        return card
    }
}
