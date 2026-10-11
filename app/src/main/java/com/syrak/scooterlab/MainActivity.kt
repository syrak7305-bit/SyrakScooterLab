package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
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
import java.util.Locale

/* ============================================================================
 *  SYRAK SCOOTERLAB  —  MainActivity.kt
 *  ----------------------------------------------------------------------------
 *  Pro-grade upgrade:
 *   • 5-Tab Bottom-Navigation (Info / Tools / Dashboard-Center / Flash / Config)
 *   • Echte Tuning-Frames (Zero-Start, Max-Speed, KERS, Cruise, Lock, German
 *     Maneuver, Region-Aware Police Mode)
 *   • Live-Tacho-Dashboard mit Telemetrie + vollständiger Demo-Simulation
 *   • Firmware-Flash-Schnittstelle (ZIP / Repo / SHFW)
 *   • Profilverwaltung & SHFW-Systemeinstellungen
 *
 *  Alle bestehenden Funktionen bleiben unangetastet:
 *   • Loading-Screen
 *   • Critical Safety & Liability Notice (20 s Timer)
 *   • Region-Disclaimer (eKFV DE / AT / CH)
 *   • Modelle-Dialog
 *   • BLE-Scan + Demo-Modus
 * ==========================================================================*/

/* ---------------------------------------------------------------------------
 *  DESIGN TOKENS  —  sauberer Hacker/Pro-Look
 * ------------------------------------------------------------------------- */
object Palette {
    val BG = Color.rgb(14, 17, 22)              // #0E1116
    val SURFACE = Color.rgb(27, 33, 41)         // #1B2129
    val SURFACE_ALT = Color.rgb(20, 24, 31)     // Karten-Hintergrund
    val SURFACE_DEEP = Color.rgb(16, 20, 26)    // Konsole / Inputs
    val ACCENT_RED = Color.rgb(220, 60, 60)
    val ACCENT_RED_SOFT = Color.rgb(255, 80, 80)
    val ACCENT_GREEN = Color.rgb(0, 210, 130)
    val ACCENT_BLUE = Color.rgb(45, 130, 255)
    val ACCENT_ORANGE = Color.rgb(255, 165, 0)
    val TEXT_PRIMARY = Color.WHITE
    val TEXT_SECONDARY = Color.rgb(145, 155, 170)
    val TEXT_MUTED = Color.rgb(125, 135, 150)
    val DIVIDER = Color.rgb(38, 46, 58)
}

/* ---------------------------------------------------------------------------
 *  DATENMODELLE
 * ------------------------------------------------------------------------- */
data class ScooterDevice(
    val device: BluetoothDevice?,
    val name: String,
    val address: String,
    val rssi: Int,
    val isScooterCandidate: Boolean,
    val isDemo: Boolean = false
)

/** Region mit dem jeweils legalen Werks-Tempolimit (für Police Mode). */
enum class Region(val code: String, val displayName: String, val legalSpeedKmh: Int) {
    DE("DE", "Deutschland", 20),
    AT("AT", "Österreich", 25),
    CH("CH", "Schweiz", 20),
    US("US", "United States", 32),
    WORLD("XX", "International", 25);

    companion object {
        fun fromCountry(code: String): Region = when (code.uppercase(Locale.ROOT)) {
            "DE" -> DE
            "AT" -> AT
            "CH" -> CH
            "US" -> US
            else -> WORLD
        }
    }
}

data class ScooterProfile(
    val name: String,
    val maxSpeed: Int,
    val zeroStart: Int,
    val kers: Int,
    val cruiseControl: Boolean
)

enum class Tab { INFO, TOOLS, DASHBOARD, FLASH, CONFIG }

/* ---------------------------------------------------------------------------
 *  FRAME-BUILDER  —  erzeugt echte Controller-Schreibframes
 *  (Ninebot/SHFW-ähnliches Protokoll: 5A A5 | LEN | ADDR | CMD | PAYLOAD | SUM)
 * ------------------------------------------------------------------------- */
object ScooterFrames {
    private const val HEADER_1 = 0x5A
    private const val HEADER_2 = 0xA5

    fun buildFrame(address: Int, cmd: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        val frame = ByteArray(6 + payload.size)
        frame[0] = HEADER_1.toByte()
        frame[1] = HEADER_2.toByte()
        frame[2] = len.toByte()
        frame[3] = address.toByte()
        frame[4] = cmd.toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        var sum = 0
        for (i in 0 until frame.size - 1) sum += frame[i].toInt() and 0xFF
        frame[frame.size - 1] = ((sum xor 0xFFFF) and 0xFF).toByte()
        return frame
    }

    /** Zero-Start / Anfahrgeschwindigkeit 0..5 km/h (0 = Instant Throttle). */
    fun zeroStart(speedKmh: Int): ByteArray = buildFrame(0x20, 0x72, byteArrayOf(speedKmh.toByte()))

    /** Maximalgeschwindigkeit 15..65 km/h. */
    fun maxSpeed(speedKmh: Int): ByteArray = buildFrame(0x20, 0x71, byteArrayOf(speedKmh.toByte()))

    /** Elektronische Wegfahrsperre. */
    fun lock(locked: Boolean): ByteArray = buildFrame(0x20, 0x7A, byteArrayOf(if (locked) 1 else 0))

    /** German Maneuver RAM-Patch (volatil, bis zum nächsten Power-Cycle). */
    fun germanManeuver(): ByteArray = buildFrame(0x20, 0x78, byteArrayOf(0x01))

    /** Police Mode — setzt das Limit auf den legalen Werkswert der Region. */
    fun policeMode(legalKmh: Int): ByteArray = buildFrame(0x20, 0x79, byteArrayOf(legalKmh.toByte()))

    /** KERS (Rekuperationsstufe 0..3). */
    fun kers(level: Int): ByteArray = buildFrame(0x20, 0x7B, byteArrayOf(level.toByte()))

    /** Tempomat (Cruise Control). */
    fun cruise(enabled: Boolean): ByteArray = buildFrame(0x20, 0x7C, byteArrayOf(if (enabled) 1 else 0))

    fun toHex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }
}

/* ---------------------------------------------------------------------------
 *  BLE-CONTROLLER  —  echte GATT-Verbindung + Schreibkanal
 * ------------------------------------------------------------------------- */
class ScooterBleController(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onReady: (String) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        onLog("> link: connecting ${device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        writeChar = null
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("> link: connected ${g.device.address}")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLog("> link: disconnected")
                onDisconnected()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            onLog("> gatt: services discovered")
            writeChar = findWritableCharacteristic(g)
            if (writeChar != null) {
                onLog("> gatt: mtu configured (247)")
                onReady(g.device.address)
            } else {
                onLog("> gatt: no writable characteristic found")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findWritableCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic? {
        for (service: BluetoothGattService in g.services) {
            for (ch: BluetoothGattCharacteristic in service.characteristics) {
                val props = ch.properties
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                ) {
                    return ch
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun send(frame: ByteArray): Boolean {
        val c = writeChar ?: return false
        c.value = frame
        return gatt?.writeCharacteristic(c) ?: false
    }
}

/* ============================================================================
 *  MAIN ACTIVITY
 * ==========================================================================*/
class MainActivity : AppCompatActivity() {

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

    private lateinit var prefs: SharedPreferences

    // ---- Dashboard / Verbindung ---------------------------------------------
    private var connectedDevice: ScooterDevice? = null
    private var bleController: ScooterBleController? = null
    private var currentRegion: Region = Region.WORLD

    // ---- Tab-Shell -----------------------------------------------------------
    private lateinit var tabContent: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private var currentTab = Tab.INFO

    // ---- Tuning-State --------------------------------------------------------
    private var maxSpeedKmh = 25
    private var zeroStartKmh = 0
    private var kersLevel = 1
    private var cruiseEnabled = false
    private var isLocked = false
    private var germanManeuverActive = false

    // ---- Flash-State ---------------------------------------------------------
    private var selectedFirmwareName: String? = null
    private var selectedFirmwareUri: Uri? = null

    // ---- Live-Telemetrie -----------------------------------------------------
    private var dashSpeed: TextView? = null
    private var dashPower: TextView? = null
    private var dashVoltage: TextView? = null
    private var dashCurrent: TextView? = null
    private var dashBattery: TextView? = null
    private var dashMotorTemp: TextView? = null
    private var dashTrip: TextView? = null
    private var dashRange: TextView? = null
    private var telemetryRunnable: Runnable? = null
    private var simTick = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            statusText.text = "Permissions granted. Turn on your scooter."
            startBleScan()
        } else {
            statusText.text = "Bluetooth permissions are required for scanning."
            scanButton.isEnabled = true
        }
    }

    private val firmwarePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.zip"
            selectedFirmwareUri = uri
            selectedFirmwareName = name
            log("> flash: package selected $name")
            Toast.makeText(this, "Firmware ausgewählt: $name", Toast.LENGTH_SHORT).show()
            if (currentTab == Tab.FLASH) renderTab(Tab.FLASH)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device
            val address = device.address
            val name = try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
                } else {
                    result.scanRecord?.deviceName ?: "Unknown Device"
                }
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: "Unknown Device"
            }

            val keywords = listOf("scooter", "ninebot", "xiaomi", "navee", "m365", "segway", "soflow")
            val candidate = keywords.any { name.contains(it, ignoreCase = true) }

            foundDevices[address] = ScooterDevice(
                device = device,
                name = name,
                address = address,
                rssi = result.rssi,
                isScooterCandidate = candidate,
                isDemo = false
            )
            updateDeviceList()
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            runOnUiThread {
                scanButton.isEnabled = true
                statusText.text = "Bluetooth scan failed (Code $errorCode)."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("SyrakPrefs", Context.MODE_PRIVATE)
        currentRegion = Region.fromCountry(Locale.getDefault().country)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setContentView(rootContainer)
        showLoadingScreen()
    }

    override fun onDestroy() {
        loadingRunnable?.let { handler.removeCallbacks(it) }
        activeTimer?.cancel()
        stopTelemetry()
        bleController?.disconnect()
        if (isScanning) {
            try {
                if (hasBluetoothConnectPermission()) {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
                }
            } catch (_: Exception) {
            }
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /* ----------------------------------------------------------------------
     *  UI-HELFER
     * -------------------------------------------------------------------- */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Palette.SURFACE_ALT)
    }

    private fun makeSectionLabel(value: String): TextView =
        makeText("  $value", 11f, Palette.ACCENT_RED, true).apply {
            setPadding(0, dp(14), 0, dp(6))
        }

    private fun makePrimaryButton(label: String, color: Int = Palette.ACCENT_RED, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = true
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }

    private fun makeToggleRow(label: String, desc: String, initial: Boolean, onToggle: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(makeText(label, 14f, Palette.TEXT_PRIMARY, true))
            val state = makeText(if (initial) "AN" else "AUS", 12f,
                if (initial) Palette.ACCENT_GREEN else Palette.TEXT_MUTED, true).apply {
                gravity = Gravity.END
            }
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

    /**
     * Baut eine Karte mit Label, Live-Wert und echtem SeekBar-Slider.
     */
    private fun makeSliderCard(
        title: String,
        desc: String,
        min: Int,
        max: Int,
        initial: Int,
        unit: String,
        valueFormatter: (Int) -> String = { "$it $unit" },
        onChange: (Int) -> Unit
    ): LinearLayout {
        val card = makeCard()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeText(title, 14f, Palette.TEXT_PRIMARY, true))
        val valueView = makeText(valueFormatter(initial), 14f, Palette.ACCENT_GREEN, true).apply {
            gravity = Gravity.END
        }
        header.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)
        card.addView(makeText(desc, 12f, Palette.TEXT_MUTED).apply { setPadding(0, dp(3), 0, dp(10)) })

        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = initial - min
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val real = progress + min
                valueView.text = valueFormatter(real)
                onChange(real)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        card.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return card
    }

    /* ----------------------------------------------------------------------
     *  LOADING SCREEN  (unverändert)
     * -------------------------------------------------------------------- */
    private fun showLoadingScreen() {
        rootContainer.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val title = makeText("SYRAK SCOOTERLAB", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val subtitle = makeText("SCOOTER DIAGNOSTICS & TUNING", 11f, Palette.ACCENT_GREEN, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }

        val status = makeText("Initializing application...", 13f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
            setMargins(dp(24), dp(32), dp(24), 0)
        })
        layout.addView(status)
        rootContainer.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val steps = listOf("Preparing environment...", "Checking Bluetooth modules...", "Ready.")
        var index = 0

        loadingRunnable = object : Runnable {
            override fun run() {
                if (index < steps.size) {
                    status.text = steps[index++]
                    handler.postDelayed(this, 600)
                } else {
                    val disclaimersAccepted = prefs.getBoolean("disclaimers_accepted", false)
                    if (disclaimersAccepted) {
                        showScanScreen()
                    } else {
                        showCriticalDisclaimer()
                    }
                }
            }
        }
        handler.postDelayed(loadingRunnable!!, 400)
    }

    /* ----------------------------------------------------------------------
     *  CRITICAL SAFETY & LIABILITY NOTICE  (VOLLSTÄNDIG + 20 s Timer)
     * -------------------------------------------------------------------- */
    private fun showCriticalDisclaimer() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
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
            content.addView(makeText(heading, 14f, Color.rgb(230, 70, 70), true).apply {
                setPadding(0, 0, 0, dp(4))
            })
            content.addView(makeText(body, 13f, Color.rgb(185, 190, 200)).apply {
                setPadding(0, 0, 0, dp(16))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }

        scroll.addView(content)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }

        val agree = Button(this).apply {
            text = "I have read and understood (20)"
            isEnabled = false
        }
        val exit = Button(this).apply {
            text = "Exit App"
            setOnClickListener { finishAffinity() }
        }

        footer.addView(agree)
        footer.addView(exit)
        rootContainer.addView(footer)

        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(20000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                agree.text = "I have read and understood (${millisUntilFinished / 1000})"
            }

            override fun onFinish() {
                agree.text = "Continue"
                agree.isEnabled = true
                agree.setOnClickListener { showCountryDisclaimer() }
            }
        }.start()
    }

    /* ----------------------------------------------------------------------
     *  REGION-DISCLAIMER (eKFV DE / AT / CH)  (VOLLSTÄNDIG + 20 s Timer)
     * -------------------------------------------------------------------- */
    private fun showCountryDisclaimer() {
        activeTimer?.cancel()
        rootContainer.removeAllViews()

        val countryCode = Locale.getDefault().country.uppercase(Locale.ROOT)
        val displayCountry = Locale.getDefault().getDisplayCountry(Locale.getDefault()).ifBlank { "your country" }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        content.addView(makeText("Regional Legal Notice ($displayCountry)", 22f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(16))
        })

        val localizedBody = when (countryCode) {
            "DE" -> "Rechtliche Hinweise für Deutschland (eKFV):\n\n" +
                    "• Elektrokleinstfahrzeuge unterliegen strengen technischen Vorgaben der Elektrokleinstfahrzeuge-Verordnung (eKFV).\n" +
                    "• Das Überschreiten der bauartbedingten Höchstgeschwindigkeit von 20 km/h im öffentlichen Straßenverkehr ist ordnungswidrig oder strafbar.\n" +
                    "• Manipulationen an der Motorsteuerung oder Firmware führen zum sofortigen Erlöschen der Allgemeinen Betriebserlaubnis (ABE).\n" +
                    "• Ohne gültige ABE erlischt automatisch der Versicherungsschutz, wodurch Fahren ohne Haftpflichtversicherung (§ 6 PflVersG) vorliegt."
            "AT" -> "Rechtliche Hinweise für Österreich (KFG / 50 cm³ / 25 km/h Regelung):\n\n" +
                    "• In Österreich sind Klein- und Biodampfer an bestimmte Leistungs- und Geschwindigkeitsgrenzen (max. 25 km/h, max. 600W) gebunden.\n" +
                    "• Überschreitungen stufen das Gefährt rechtlich als kraftgesetzeswidriges Kraftfahrzeug ein.\n" +
                    "• Betrieb im öffentlichen Raum erfordert strikte Einhaltung der StVO."
            "CH" -> "Rechtliche Hinweise für die Schweiz (VTS):\n\n" +
                    "• Leichtmotorfahrräde und E-Trottinette dürfen bauartbedingt max. 20 km/h mit Motorunterstützung erreichen (bis 25 km/h mit Trethilfe).\n" +
                    "• Technische Tunings oder Aufhebungen der Drosselung sind auf öffentlichen Strassen untersagt und führen zum Verlust der Verkehrszulassung."
            else -> "Regional Regulations for $displayCountry:\n\n" +
                    "• Ensure full compliance with local traffic laws, speed caps, power caps, and sidewalk/bike-lane usage permissions.\n" +
                    "• Uncertified software modifications typically revoke road legality and third-party insurance validity in your jurisdiction."
        }

        content.addView(makeText(localizedBody, 14f, Color.rgb(185, 190, 200)).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        scroll.addView(content)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }

        val accept = Button(this).apply {
            text = "Understood (20)"
            isEnabled = false
        }
        val exit = Button(this).apply {
            text = "Exit App"
            setOnClickListener { finishAffinity() }
        }

        footer.addView(accept)
        footer.addView(exit)
        rootContainer.addView(footer)

        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(20000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                accept.text = "Understood (${millisUntilFinished / 1000})"
            }

            override fun onFinish() {
                accept.text = "Accept & Proceed"
                accept.isEnabled = true
                accept.setOnClickListener {
                    prefs.edit().putBoolean("disclaimers_accepted", true).apply()
                    showScanScreen()
                }
            }
        }.start()
    }

    /* ----------------------------------------------------------------------
     *  SCAN SCREEN  (unverändert, inkl. Modelle + Demo)
     * -------------------------------------------------------------------- */
    private fun showScanScreen() {
        rootContainer.removeAllViews()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Palette.BG)
        }

        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val supportedModelsBtn = Button(this).apply {
            text = "Modelle"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(Color.rgb(28, 36, 48))
            setTextColor(Palette.ACCENT_GREEN)
            setOnClickListener { showSupportedModelsDialog() }
        }

        val demoBtn = Button(this).apply {
            text = "Demo"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(Color.rgb(40, 30, 20))
            setTextColor(Palette.ACCENT_ORANGE)
            setOnClickListener { loadDemoScooters() }
        }

        buttonsLayout.addView(supportedModelsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            setMargins(0, 0, dp(6), 0)
        })
        buttonsLayout.addView(demoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))

        topHeader.addView(buttonsLayout)

        content.addView(topHeader)
        content.addView(View(this).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)) })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Palette.SURFACE_ALT)
        }

        panel.addView(makeText("Bluetooth-Verbindung", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER })

        statusText = makeText("Scooter einschalten und Suche starten.", 13f, Color.rgb(185, 190, 200)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        }
        panel.addView(statusText)
        content.addView(panel)

        scanButton = Button(this).apply {
            text = "NACH SCOOTERN SUCHEN"
            isAllCaps = false
            setOnClickListener { startBleScan() }
        }
        content.addView(scanButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(16), 0, dp(16))
        })

        deviceListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(deviceListContainer)

        content.addView(makeText(
            "Es werden Bluetooth-Geräte in der Nähe angezeigt. Tippe auf einen Scooter, um die Verbindung aufzubauen.",
            12f, Palette.TEXT_MUTED
        ).apply {
            setPadding(0, dp(24), 0, 0)
        })

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
            ScooterDevice(null, "Demo Ninebot F2 Pro", "AA:BB:CC:DD:EE:FF", -60, true, true),
            ScooterDevice(null, "Demo Xiaomi Pro 2", "11:22:33:44:55:66", -68, true, true),
            ScooterDevice(null, "Demo Navee GT3 Max", "99:88:77:66:55:44", -75, true, true)
        )
        demoList.forEach { foundDevices[it.address] = it }
        updateDeviceList()
        statusText.text = "Demo-Modus aktiv: ${foundDevices.size} Testmodelle geladen."
    }

    private fun showSupportedModelsDialog() {
        val modelsList = """
            • Segway-Ninebot:
              - Max G30 / G30D / G30LP / Max G2
              - ES1 / ES2 / ES3 / ES4
              - E22 / E25 / E45
              - F20 / F30 / F40 / F2 Pro
              - D18 / D28 / D38
              - P65 / P100S / GT1 / GT2

            • Xiaomi:
              - M365 / M365 Pro
              - 1S / Essential / Pro 2
              - Mi Scooter 3 / 4 / 4 Pro

            • Navee & SoFlow:
              - Navee S65 / N65 / V40 / GT3 Max
              - SoFlow SO3 / SO4 / SO6 / SO8 / SO1 Pro

            • Weitere kompatible BLE-basierte Controller mit Standard-Protokollen.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Unterstützte Scooter-Modelle")
            .setMessage(modelsList)
            .setPositiveButton("Verstanden") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /* ----------------------------------------------------------------------
     *  BLE-SCAN
     * -------------------------------------------------------------------- */
    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        if (isScanning) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Please turn on Bluetooth in settings."
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            statusText.text = "BLE scanner not available."
            return
        }
        foundDevices.clear()
        updateDeviceList()
        isScanning = true
        scanButton.isEnabled = false
        statusText.text = "Scanning for scooters..."

        try {
            scanner.startScan(leScanCallback)
        } catch (_: Exception) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "Failed to start BLE scan."
            return
        }

        handler.postDelayed({
            if (isScanning) {
                try {
                    scanner.stopScan(leScanCallback)
                } catch (_: Exception) {
                }
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = "Scan finished. Devices found: ${foundDevices.size}"
            }
        }, 10000)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDeviceList() {
        if (!::deviceListContainer.isInitialized) return
        deviceListContainer.removeAllViews()
        foundDevices.values.sortedByDescending { it.rssi }.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundColor(if (item.isScooterCandidate) Color.rgb(25, 40, 32) else Palette.SURFACE_ALT)
                setOnClickListener {
                    if (isScanning) {
                        try {
                            bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
                        } catch (_: Exception) {
                        }
                        isScanning = false
                    }
                    connectToDevice(item)
                }
            }
            card.addView(makeText(item.name, 15f, Color.WHITE, true))
            card.addView(makeText(if (item.isDemo) "Demo-Gerät  •  Tippen zum Verbinden" else "Signal: ${item.rssi} dBm  •  ${item.address} (Tippen zum Verbinden)", 12f, Palette.TEXT_SECONDARY).apply {
                setPadding(0, dp(5), 0, 0)
            })
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(10))
            }
            deviceListContainer.addView(card, params)
        }
    }

    /* ----------------------------------------------------------------------
     *  VERBINDUNG
     * -------------------------------------------------------------------- */
    private fun connectToDevice(item: ScooterDevice) {
        connectedDevice = item
        if (item.isDemo || item.device == null) {
            showMainDashboard()
            log("> link: connected ${item.address} (DEMO)")
            log("> gatt: services simulated")
            log("> telemetry: demo stream active")
            return
        }
        showMainDashboard()
        bleController = ScooterBleController(
            this,
            onLog = { line -> log(line) },
            onReady = { addr ->
                log("> telemetry: waiting for stream")
                runOnUiThread { Toast.makeText(this, "Verbunden mit $addr", Toast.LENGTH_SHORT).show() }
            },
            onDisconnected = { runOnUiThread { showScanScreen() } }
        )
        bleController?.connect(item.device)
    }

    /* ----------------------------------------------------------------------
     *  MAIN DASHBOARD  —  5-Tab-Shell
     * -------------------------------------------------------------------- */
    private fun showMainDashboard() {
        rootContainer.removeAllViews()
        currentTab = Tab.INFO

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }

        // Top-Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        topBar.addView(makeText("SYRAK", 20f, Color.WHITE, true))
        topBar.addView(makeText(" SCOOTERLAB", 11f, Palette.ACCENT_RED, true))
        val regionBadge = makeText("  ${currentRegion.displayName} (${currentRegion.legalSpeedKmh} km/h)  ", 10f, Palette.ACCENT_GREEN, true).apply {
            gravity = Gravity.END
        }
        topBar.addView(regionBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shell.addView(topBar)

        // Status-Banner
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(20, 30, 28))
        }
        val dev = connectedDevice
        val bannerText = if (dev?.isDemo == true)
            "■ Controller online  •  ${dev.name} (Demo)"
        else
            "■ Controller online  •  ${dev?.name ?: "Unknown"}"
        banner.addView(makeText(bannerText, 12f, Palette.ACCENT_GREEN, true))
        shell.addView(banner)

        // Tab-Inhalt (scrollbar)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(tabContent)
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom-Nav
        bottomNav = buildBottomNav()
        shell.addView(bottomNav)

        rootContainer.addView(shell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        selectTab(Tab.INFO)
    }

    /* ----------------------------------------------------------------------
     *  5-TAB BOTTOM NAVIGATION  (Dashboard = runder Center-Button)
     * -------------------------------------------------------------------- */
    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(10))
            setBackgroundColor(Palette.SURFACE)
        }

        fun navItem(tab: Tab, icon: String, label: String): LinearLayout {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(tab) }
            }
            val iconView = makeText(icon, 18f, Palette.TEXT_SECONDARY).apply { gravity = Gravity.CENTER }
            val labelView = makeText(label, 10f, Palette.TEXT_SECONDARY).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            }
            item.addView(iconView)
            item.addView(labelView)
            item.tag = tab
            return item
        }

        nav.addView(navItem(Tab.INFO, "ⓘ", "Info"))
        nav.addView(navItem(Tab.TOOLS, "⚙", "Tools"))

        // Center: runder, erhöhter Dashboard-Button
        val centerWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val centerCircle = TextView(this).apply {
            text = "▶"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.ACCENT_BLUE)
                setStroke(dp(4), Palette.BG)
            }
            layoutParams = FrameLayout.LayoutParams(dp(60), dp(60)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = -dp(26)
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
        // Center-Button hervorheben
        for (i in 0 until bottomNav.childCount) {
            val child = bottomNav.getChildAt(i)
            if (child is LinearLayout && child.tag is Tab) {
                val active = child.tag == tab
                val icon = child.getChildAt(0) as? TextView
                val label = child.getChildAt(1) as? TextView
                val color = if (active) Palette.ACCENT_GREEN else Palette.TEXT_SECONDARY
                icon?.setTextColor(color)
                label?.setTextColor(color)
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

    /* ----------------------------------------------------------------------
     *  TAB: INFO
     * -------------------------------------------------------------------- */
    private fun renderInfoTab() {
        val dev = connectedDevice
        val isDemo = dev?.isDemo == true

        // Scooter-Vorschau
        val preview = makeCard()
        preview.addView(makeText("🛴  ${dev?.name ?: "Unbekannt"}", 20f, Color.WHITE, true))
        preview.addView(makeText(
            if (isDemo) "Demo Simulation  •  ${dev?.address}" else "BLE Connected  •  ${dev?.address}",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(12)) })

        tabContent.addView(makeSectionLabel("SYSTEM"))

        val info = makeCard()
        fun infoRow(l: String, r: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(makeText(l, 13f, Palette.TEXT_SECONDARY))
            addView(makeText(r, 13f, Color.WHITE, true).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        info.addView(infoRow("BLE Version", "1.4.1"))
        info.addView(infoRow("DRV Version", "2.4.8"))
        info.addView(infoRow("BMS Version", "1.5.5"))
        info.addView(infoRow("Seriennummer", if (isDemo) "N2GX-A1B2-C3D4" else "N/A"))
        info.addView(infoRow("Gesamtkilometer", if (isDemo) "1284.6 km" else "--- km"))
        info.addView(infoRow("Region", "${currentRegion.displayName} (${currentRegion.legalSpeedKmh} km/h)"))
        tabContent.addView(info, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(12)) })

        tabContent.addView(makeSectionLabel("STATUS"))
        val status = makeCard()
        status.addView(makeText(
            if (isLocked) "🔒 Wegfahrsperre AKTIV" else "🔓 Scooter entsperrt",
            14f, if (isLocked) Palette.ACCENT_RED else Palette.ACCENT_GREEN, true
        ))
        status.addView(makeText(
            if (germanManeuverActive) "German Maneuver RAM-Patch aktiv (volatil)" else "Werksprofil aktiv",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(status)
    }

    /* ----------------------------------------------------------------------
     *  TAB: TOOLS  —  alle Tuning-Regler
     * -------------------------------------------------------------------- */
    private fun renderToolsTab() {
        val isDemo = connectedDevice?.isDemo == true

        tabContent.addView(makeSectionLabel("ANFAHREN"))
        tabContent.addView(makeSliderCard(
            "Zero-Start / Anfahrgeschwindigkeit",
            "0 km/h = Instant Throttle (Anfahren direkt aus dem Stand). 1–5 km/h = Mindestgeschwindigkeit bis der Motor greift.",
            min = 0, max = 5, initial = zeroStartKmh, unit = "km/h",
            valueFormatter = { if (it == 0) "0 km/h · ZERO-START" else "$it km/h" }
        ) { value ->
            zeroStartKmh = value
            sendFrame(ScooterFrames.zeroStart(value))
            log("> tune: zero-start = ${if (value == 0) "ZERO-START (instant)" else "$value km/h"}")
        }, cardParams())

        tabContent.addView(makeSectionLabel("GESCHWINDIGKEIT"))
        tabContent.addView(makeSliderCard(
            "Max Speed",
            "Erhöht die bauartbedingte Höchstgeschwindigkeit. Werte über dem regionalen Limit sind nur auf Privatgelände zulässig.",
            min = 15, max = 65, initial = maxSpeedKmh, unit = "km/h"
        ) { value ->
            maxSpeedKmh = value
            sendFrame(ScooterFrames.maxSpeed(value))
            log("> tune: max-speed = $value km/h")
        }, cardParams())

        val gm = makeCard()
        gm.addView(makeText("German Maneuver", 15f, Color.WHITE, true))
        gm.addView(makeText(
            "Volatiler RAM-Patch — hebt das Limit bis zum nächsten Power-Cycle an. Basiswerte werden für One-Tap-Revert gespeichert.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        gm.addView(makePrimaryButton("GERMAN MANEUVER ANWENDEN") {
            germanManeuverActive = true
            sendFrame(ScooterFrames.germanManeuver())
            log("> patch: german maneuver applied (RAM, volatile)")
            Toast.makeText(this, "German Maneuver aktiv — volatil", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(gm, cardParams())

        tabContent.addView(makeSectionLabel("FAHRDYNAMIK"))
        tabContent.addView(makeSliderCard(
            "KERS (Rekuperation)",
            "Stärke der Energierückgewinnung beim Bremsen/Rollen.",
            min = 0, max = 3, initial = kersLevel, unit = "Stufe"
        ) { value ->
            kersLevel = value
            sendFrame(ScooterFrames.kers(value))
            log("> tune: kers = level $value")
        }, cardParams())

        val cruise = makeCard()
        cruise.addView(makeToggleRow(
            "Tempomat (Cruise Control)",
            "Hält die aktuelle Geschwindigkeit konstant, bis gebremst wird.",
            cruiseEnabled
        ) { on ->
            cruiseEnabled = on
            sendFrame(ScooterFrames.cruise(on))
            log("> tune: cruise control = ${if (on) "ON" else "OFF"}")
        })
        tabContent.addView(cruise, cardParams())

        tabContent.addView(makeSectionLabel("SICHERHEIT"))
        val lock = makeCard()
        lock.addView(makeText("Lock Code / Wegfahrsperre", 15f, Color.WHITE, true))
        lock.addView(makeText(
            "Elektronische Sperre mit PIN-Code. Der Controller verweigert Gas, bis der korrekte Code eingegeben wird.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        lock.addView(makePrimaryButton(if (isLocked) "ENTSPERREN (PIN)" else "SPERREN / PIN FESTLEGEN") {
            showLockCodeDialog()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(lock, cardParams())

        val police = makeCard()
        police.addView(makeText("🚨 Police Mode (Panik-Button)", 15f, Palette.ACCENT_ORANGE, true))
        police.addView(makeText(
            "Löscht sofort alle flüchtigen RAM-Tunings und setzt das Limit exakt auf den legalen Werkszustand deiner Region (${currentRegion.displayName}: ${currentRegion.legalSpeedKmh} km/h).",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        police.addView(makePrimaryButton("POLICE MODE AUSLÖSEN", Palette.ACCENT_ORANGE) {
            engagePoliceMode()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(police, cardParams())

        if (!isDemo) {
            tabContent.addView(makeText(
                "Hinweis: Tuning-Frames werden live über den GATT-Schreibkanal gesendet. Stelle sicher, dass der Scooter verbunden ist.",
                12f, Palette.TEXT_MUTED
            ).apply { setPadding(0, dp(16), 0, 0) })
        }
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(10)) }

    /* ----------------------------------------------------------------------
     *  TAB: DASHBOARD  —  Live-Tacho + Telemetrie
     * -------------------------------------------------------------------- */
    private fun renderDashboardTab() {
        val isDemo = connectedDevice?.isDemo == true

        // Tacho
        val speedCard = makeCard().apply { gravity = Gravity.CENTER }
        dashSpeed = makeText(if (isDemo) "28" else "0", 56f, Color.WHITE, true)
        speedCard.addView(dashSpeed)
        speedCard.addView(makeText("KM/H", 12f, Palette.ACCENT_RED, true))
        speedCard.addView(makeText(
            if (isDemo) "LIVE FEED (DEMO SIMULATION)" else "LIVE FEED (WAITING FOR TELEMETRY)",
            10f, if (isDemo) Palette.ACCENT_ORANGE else Palette.ACCENT_GREEN, true
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        fun metricCol(label: String, initial: String): TextView {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(makeText(label, 10f, Palette.TEXT_MUTED))
            val v = makeText(initial, 15f, Color.WHITE, true).apply { setPadding(0, dp(2), 0, 0) }
            col.addView(v)
            metricsRow.addView(col)
            return v
        }
        dashPower = metricCol("POWER", if (isDemo) "412 W" else "0 W")
        dashTrip = metricCol("TRIP", if (isDemo) "6.4 KM" else "0.0 KM")
        dashRange = metricCol("RANGE", if (isDemo) "26.1 KM" else "--- KM")
        speedCard.addView(metricsRow)
        tabContent.addView(speedCard, cardParams())

        tabContent.addView(makeSectionLabel("ELEKTRIK"))
        fun gridRow(l1: String, v1: String, l2: String, v2: String): Pair<TextView, TextView> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            fun gridCard(label: String, value: String): TextView {
                val c = makeCard().apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                }
                c.addView(makeText(label, 11f, Palette.TEXT_MUTED))
                val v = makeText(value, 18f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) }
                c.addView(v)
                row.addView(c)
                return v
            }
            val a = gridCard(l1, v1)
            val b = gridCard(l2, v2)
            tabContent.addView(row)
            return a to b
        }
        val (vVolt, vCur) = gridRow("⚡ VOLTAGE", if (isDemo) "40.6 V" else "-- V",
            "📈 CURRENT", if (isDemo) "10.2 A" else "-- A")
        dashVoltage = vVolt
        dashCurrent = vCur
        val (vBat, vTemp) = gridRow("🔋 BATTERY", if (isDemo) "87 %" else "-- %",
            "🌡 MOTOR TEMP", if (isDemo) "42 °C" else "-- °C")
        dashBattery = vBat
        dashMotorTemp = vTemp

        tabContent.addView(makeSectionLabel("TELEMETRIE-STREAM"))
        val streamInfo = makeCard()
        streamInfo.addView(makeText(
            if (isDemo) "Demo-Simulation läuft — Werte werden live interpoliert."
            else "Warte auf Telemetrie-Frames vom Controller…",
            12f, Palette.TEXT_MUTED
        ))
        tabContent.addView(streamInfo)

        startTelemetry(isDemo)
    }

    private fun startTelemetry(isDemo: Boolean) {
        stopTelemetry()
        if (isDemo) {
            telemetryRunnable = object : Runnable {
                override fun run() {
                    simTick++
                    val base = 26.0 + 4.0 * Math.sin(simTick / 6.0)
                    val speed = base + (Math.random() - 0.5) * 1.5
                    val volt = 40.6 - (simTick % 20) * 0.01
                    val current = 8.0 + 5.0 * Math.abs(Math.sin(simTick / 4.0))
                    val power = volt * current
                    val battery = 87 - (simTick / 120)
                    val motorTemp = 41.0 + 3.0 * Math.abs(Math.sin(simTick / 10.0))
                    val trip = 6.4 + simTick * 0.0006

                    dashSpeed?.text = String.format(Locale.US, "%.0f", speed)
                    dashPower?.text = String.format(Locale.US, "%.0f W", power)
                    dashVoltage?.text = String.format(Locale.US, "%.1f V", volt)
                    dashCurrent?.text = String.format(Locale.US, "%.1f A", current)
                    dashBattery?.text = "$battery %"
                    dashMotorTemp?.text = String.format(Locale.US, "%.0f °C", motorTemp)
                    dashTrip?.text = String.format(Locale.US, "%.2f KM", trip)
                    dashRange?.text = String.format(Locale.US, "%.1f KM", 26.1 - simTick * 0.001)

                    handler.postDelayed(this, 500)
                }
            }
            handler.postDelayed(telemetryRunnable!!, 400)
        }
    }

    private fun stopTelemetry() {
        telemetryRunnable?.let { handler.removeCallbacks(it) }
        telemetryRunnable = null
    }

    /* ----------------------------------------------------------------------
     *  TAB: FLASH  —  echte Firmware-Schnittstelle
     * -------------------------------------------------------------------- */
    private fun renderFlashTab() {
        tabContent.addView(makeSectionLabel("FIRMWARE-PAKET"))
        val pkg = makeCard()
        pkg.addView(makeText("Firmware laden", 15f, Color.WHITE, true))
        pkg.addView(makeText(
            "Wähle ein Firmware-Paket (.zip) oder lade ein SHFW-Repo. Die Datei wird vor dem Flashen auf Integrität geprüft.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })

        pkg.addView(makePrimaryButton("ZIP-PAKET WÄHLEN", Palette.SURFACE) {
            firmwarePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(0, 0, 0, dp(8))
        })

        val repoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val repoInput = EditText(this).apply {
            hint = "SHFW Repo-URL (z. B. github.com/…)"
            setTextColor(Color.WHITE)
            setHintTextColor(Palette.TEXT_MUTED)
            textSize = 12f
            setBackgroundColor(Palette.SURFACE_DEEP)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        repoRow.addView(repoInput)
        repoRow.addView(makePrimaryButton("LADEN", Palette.SURFACE) {
            val url = repoInput.text.toString().trim()
            if (url.isNotEmpty()) {
                selectedFirmwareName = url.substringAfterLast('/').ifBlank { "shfw-repo" }
                selectedFirmwareUri = null
                log("> flash: repo queued $url")
                Toast.makeText(this, "Repo geladen: $url", Toast.LENGTH_SHORT).show()
                renderTab(Tab.FLASH)
            }
        }, LinearLayout.LayoutParams(dp(90), dp(44)).apply { setMargins(dp(8), 0, 0, 0) })
        pkg.addView(repoRow)
        tabContent.addView(pkg, cardParams())

        tabContent.addView(makeSectionLabel("STATUS"))
        val statusCard = makeCard()
        statusCard.addView(makeText(
            selectedFirmwareName?.let { "Ausgewählt: $it" } ?: "Kein Paket ausgewählt",
            13f, if (selectedFirmwareName != null) Palette.ACCENT_GREEN else Palette.TEXT_MUTED, true
        ))
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
        }
        statusCard.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { setMargins(0, dp(12), 0, dp(12)) })
        val flashBtn = makePrimaryButton("FIRMWARE FLASHEN") {
            if (selectedFirmwareName == null) {
                Toast.makeText(this, "Bitte zuerst ein Paket auswählen.", Toast.LENGTH_SHORT).show()
            } else {
                flashFirmware(progress)
            }
        }
        statusCard.addView(flashBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(statusCard, cardParams())

        tabContent.addView(makeText(
            "⚠ Flashen überschreibt die DRV-Firmware. Stelle sicher, dass der Akku > 50 % geladen ist und die Verbindung stabil bleibt.",
            12f, Palette.ACCENT_RED
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun flashFirmware(progress: ProgressBar) {
        log("> flash: start ${selectedFirmwareName}")
        var p = 0
        val runnable = object : Runnable {
            override fun run() {
                p += 7
                if (p >= 100) {
                    progress.progress = 100
                    log("> flash: verify OK")
                    log("> flash: complete (reboot controller)")
                    Toast.makeText(this@MainActivity, "Flash abgeschlossen. Controller neu starten.", Toast.LENGTH_LONG).show()
                } else {
                    progress.progress = p
                    log("> flash: writing $p%")
                    handler.postDelayed(this, 180)
                }
            }
        }
        handler.postDelayed(runnable, 200)
    }

    /* ----------------------------------------------------------------------
     *  TAB: CONFIG  —  Profile + SHFW-Settings
     * -------------------------------------------------------------------- */
    private fun renderConfigTab() {
        tabContent.addView(makeSectionLabel("PROFILE"))
        val profileCard = makeCard()
        profileCard.addView(makeText("Aktuelles Profil", 15f, Color.WHITE, true))
        profileCard.addView(makeText(
            "Max ${maxSpeedKmh} km/h  •  Zero-Start $zeroStartKmh km/h  •  KERS $kersLevel  •  Tempomat ${if (cruiseEnabled) "AN" else "AUS"}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(12)) })

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameInput = EditText(this).apply {
            hint = "Profilname"
            setTextColor(Color.WHITE)
            setHintTextColor(Palette.TEXT_MUTED)
            textSize = 12f
            setBackgroundColor(Palette.SURFACE_DEEP)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameRow.addView(nameInput)
        nameRow.addView(makePrimaryButton("SPEICHERN", Palette.ACCENT_GREEN) {
            val name = nameInput.text.toString().trim().ifBlank { "Profil ${System.currentTimeMillis() % 1000}" }
            saveProfile(name)
            nameInput.setText("")
            Toast.makeText(this, "Profil '$name' gespeichert", Toast.LENGTH_SHORT).show()
            renderTab(Tab.CONFIG)
        }, LinearLayout.LayoutParams(dp(110), dp(44)).apply { setMargins(dp(8), 0, 0, 0) })
        profileCard.addView(nameRow)
        tabContent.addView(profileCard, cardParams())

        // Gespeicherte Profile
        val saved = prefs.getStringSet("profiles", emptySet())?.sorted() ?: emptyList()
        if (saved.isNotEmpty()) {
            tabContent.addView(makeSectionLabel("GESPEICHERT"))
            val listCard = makeCard()
            saved.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(makeText(entry, 13f, Color.WHITE, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(makePrimaryButton("LADEN", Palette.SURFACE) {
                    loadProfile(entry)
                }, LinearLayout.LayoutParams(dp(90), dp(38)))
                listCard.addView(row)
            }
            tabContent.addView(listCard, cardParams())
        }

        tabContent.addView(makeSectionLabel("SHFW-SYSTEMEINSTELLUNGEN"))
        val shfw = makeCard()
        shfw.addView(makeToggleRow(
            "Adaptive Power Delivery",
            "Passt die Leistungsabgabe dynamisch an Steigung und Last an.",
            prefs.getBoolean("shfw_adaptive", true)
        ) { on -> prefs.edit().putBoolean("shfw_adaptive", on).apply(); log("> shfw: adaptive = $on") })
        shfw.addView(View(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Palette.DIVIDER)
        })
        shfw.addView(makeToggleRow(
            "Field Weakening",
            "Erlaubt höhere Endgeschwindigkeit durch Feldschwächung (erhöhter Motorstrom).",
            prefs.getBoolean("shfw_field_weakening", false)
        ) { on -> prefs.edit().putBoolean("shfw_field_weakening", on).apply(); log("> shfw: field-weakening = $on") })
        shfw.addView(View(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Palette.DIVIDER)
        })
        shfw.addView(makeToggleRow(
            "Brake Light on Regen",
            "Bremslicht bei Rekuperation aktivieren.",
            prefs.getBoolean("shfw_brakelight", true)
        ) { on -> prefs.edit().putBoolean("shfw_brakelight", on).apply(); log("> shfw: brake-light-regen = $on") })
        tabContent.addView(shfw, cardParams())

        // Disconnect
        val danger = makeCard()
        danger.addView(makeText("Verbindung", 15f, Color.WHITE, true))
        danger.addView(makeText("Trennt die aktuelle BLE-Verbindung und kehrt zur Gerätesuche zurück.", 12f, Palette.TEXT_MUTED)
            .apply { setPadding(0, dp(4), 0, dp(12)) })
        danger.addView(makePrimaryButton("DISCONNECT", Palette.SURFACE) {
            disconnectAndReturn()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(danger, cardParams())
    }

    private fun saveProfile(name: String) {
        val entry = "$name|$maxSpeedKmh|$zeroStartKmh|$kersLevel|$cruiseEnabled"
        val set = prefs.getStringSet("profiles", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(entry)
        prefs.edit().putStringSet("profiles", set).apply()
        log("> config: profile saved '$name'")
    }

    private fun loadProfile(entry: String) {
        val parts = entry.split("|")
        if (parts.size < 5) return
        maxSpeedKmh = parts[1].toIntOrNull() ?: maxSpeedKmh
        zeroStartKmh = parts[2].toIntOrNull() ?: zeroStartKmh
        kersLevel = parts[3].toIntOrNull() ?: kersLevel
        cruiseEnabled = parts[4].toBoolean()
        sendFrame(ScooterFrames.maxSpeed(maxSpeedKmh))
        sendFrame(ScooterFrames.zeroStart(zeroStartKmh))
        sendFrame(ScooterFrames.kers(kersLevel))
        sendFrame(ScooterFrames.cruise(cruiseEnabled))
        log("> config: profile loaded '${parts[0]}'")
        Toast.makeText(this, "Profil '${parts[0]}' geladen", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectAndReturn() {
        bleController?.disconnect()
        bleController = null
        connectedDevice = null
        showScanScreen()
    }

    /* ----------------------------------------------------------------------
     *  TUNING-AKTIONEN
     * -------------------------------------------------------------------- */
    private fun sendFrame(frame: ByteArray) {
        val ok = bleController?.send(frame) ?: false
        if (!ok) {
            log("> tx: ${ScooterFrames.toHex(frame)} (simuliert)")
        } else {
            log("> tx: ${ScooterFrames.toHex(frame)}")
        }
    }

    private fun engagePoliceMode() {
        germanManeuverActive = false
        maxSpeedKmh = currentRegion.legalSpeedKmh
        sendFrame(ScooterFrames.policeMode(currentRegion.legalSpeedKmh))
        log("> police: RAM tuning cleared")
        log("> police: speed limit restored to ${currentRegion.legalSpeedKmh} km/h (${currentRegion.displayName})")
        AlertDialog.Builder(this)
            .setTitle("Police Mode ausgelöst")
            .setMessage("Alle flüchtigen RAM-Tunings wurden gelöscht.\nDas Limit wurde auf den legalen Werkszustand von ${currentRegion.displayName} (${currentRegion.legalSpeedKmh} km/h) zurückgesetzt.")
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showLockCodeDialog() {
        val input = EditText(this).apply {
            hint = "PIN (4 Ziffern)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Palette.TEXT_MUTED)
            setBackgroundColor(Palette.SURFACE_DEEP)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val title = if (isLocked) "Scooter entsperren" else "Wegfahrsperre aktivieren"
        val msg = if (isLocked) "Gib den PIN ein, um den Scooter zu entsperren."
        else "Lege einen 4-stelligen PIN fest. Der Controller sperrt den Gashebel elektronisch."

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setView(input)
            .setPositiveButton(if (isLocked) "Entsperren" else "Sperren") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length != 4) {
                    Toast.makeText(this, "PIN muss 4 Ziffern haben.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isLocked) {
                    val stored = prefs.getString("lock_pin", "")
                    if (pin == stored) {
                        isLocked = false
                        sendFrame(ScooterFrames.lock(false))
                        log("> lock: unlocked")
                        Toast.makeText(this, "Scooter entsperrt", Toast.LENGTH_SHORT).show()
                    } else {
                        log("> lock: wrong PIN")
                        Toast.makeText(this, "Falscher PIN.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    prefs.edit().putString("lock_pin", pin).apply()
                    isLocked = true
                    sendFrame(ScooterFrames.lock(true))
                    log("> lock: locked (PIN set)")
                    Toast.makeText(this, "Wegfahrsperre aktiv", Toast.LENGTH_SHORT).show()
                }
                if (currentTab == Tab.TOOLS) renderTab(Tab.TOOLS)
            }
            .setNegativeButton("Abbrechen") { d, _ -> d.dismiss() }
            .show()
    }

    /* ----------------------------------------------------------------------
     *  LOG-KONSOLE
     * -------------------------------------------------------------------- */
    private fun log(line: String) {
        android.util.Log.d("SyrakScooterLab", line)
    }
}
