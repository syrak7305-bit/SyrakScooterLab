package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------------------------------------------------------------------
//  Region Model & Registry (Region-Aware Police Mode Logic)
// ---------------------------------------------------------------------------

data class RegionProfile(
    val countryCode: String,
    val displayName: String,
    val stockSpeedKmh: Int,
    val flag: String
)

object RegionRegistry {
    val INTERNATIONAL = RegionProfile("--", "International (Default)", 25, "🌐")

    private val table: Map<String, RegionProfile> = listOf(
        RegionProfile("US", "United States", 32, "🇺🇸"),
        RegionProfile("CA", "Canada", 32, "🇨🇦"),
        RegionProfile("MX", "Mexico", 30, "🇲🇽"),
        RegionProfile("BR", "Brazil", 30, "🇧🇷"),
        RegionProfile("DE", "Germany", 20, "🇩🇪"),
        RegionProfile("AT", "Austria", 20, "🇦🇹"),
        RegionProfile("CH", "Switzerland", 20, "🇨🇭"),
        RegionProfile("SE", "Sweden", 20, "🇸🇪"),
        RegionProfile("NO", "Norway", 20, "🇳🇴"),
        RegionProfile("DK", "Denmark", 20, "🇩🇰"),
        RegionProfile("PL", "Poland", 20, "🇵🇱"),
        RegionProfile("JP", "Japan", 20, "🇯🇵"),
        RegionProfile("FR", "France", 25, "🇫🇷"),
        RegionProfile("IT", "Italy", 25, "🇮🇹"),
        RegionProfile("ES", "Spain", 25, "🇪🇸"),
        RegionProfile("PT", "Portugal", 25, "🇵🇹"),
        RegionProfile("NL", "Netherlands", 25, "🇳🇱"),
        RegionProfile("BE", "Belgium", 25, "🇧🇪"),
        RegionProfile("GB", "United Kingdom", 25, "🇬🇧"),
        RegionProfile("IE", "Ireland", 25, "🇮🇪"),
        RegionProfile("FI", "Finland", 25, "🇫🇮"),
        RegionProfile("AU", "Australia", 25, "🇦🇺"),
        RegionProfile("NZ", "New Zealand", 25, "🇳🇿"),
        RegionProfile("SG", "Singapore", 25, "🇸🇬")
    ).associateBy { it.countryCode }

    fun resolve(countryCode: String?): RegionProfile {
        val cc = countryCode?.trim()?.uppercase(Locale.US).orEmpty()
        return table[cc] ?: INTERNATIONAL
    }

    fun detectFromLocale(): RegionProfile = resolve(Locale.getDefault().country)

    fun selectable(): List<RegionProfile> =
        (table.values + INTERNATIONAL).distinct().sortedBy { it.displayName }
}

private enum class BannerState { STOCK, MANEUVER, POLICE }

data class ScooterDevice(
    val device: BluetoothDevice?,
    val name: String,
    val address: String,
    val rssi: Int,
    val isScooterCandidate: Boolean,
    val isDemo: Boolean = false
)

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

    // Link / session & Region states for Police Mode
    private var demoMode = true
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var detectedRegion: RegionProfile = RegionRegistry.INTERNATIONAL
    private var regionOverride: RegionProfile? = null
    private val activeRegion: RegionProfile
        get() = regionOverride ?: detectedRegion

    private var ramPatchActive = false
    private var currentLimitKmh = 25
    private var liveSpeedKmh = 0.0
    private var bannerState = BannerState.STOCK

    private lateinit var statusBanner: TextView
    private lateinit var liveSpeedValue: TextView
    private lateinit var limitValue: TextView
    private lateinit var regionValue: TextView
    private lateinit var consoleView: TextView
    private lateinit var policeButton: Button
    private lateinit var maneuverButton: Button

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val rng = Random(System.currentTimeMillis())

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

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device
            val address = device.address
            val name = try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
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
                device = device, name = name, address = address,
                rssi = result.rssi, isScooterCandidate = candidate, isDemo = false
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

        detectedRegion = RegionRegistry.detectFromLocale()
        currentLimitKmh = activeRegion.stockSpeedKmh

        window.statusBarColor = Color.rgb(12, 14, 18)
        window.navigationBarColor = Color.rgb(12, 14, 18)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 14, 18))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        setContentView(rootContainer)
        showLoadingScreen()
    }

    override fun onDestroy() {
        loadingRunnable?.let { handler.removeCallbacks(it) }
        activeTimer?.cancel()
        if (isScanning) {
            try {
                if (hasBluetoothConnectPermission()) {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
                }
            } catch (_: Exception) {}
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun showLoadingScreen() {
        rootContainer.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val title = makeText("SYRAK SCOOTERLAB", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val subtitle = makeText("SCOOTER DIAGNOSTICS & TUNING", 11f, Color.rgb(0, 200, 120), true).apply {
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
            "Age Restriction & Competence" to "This application and its companion firmware utilities are intended solely for adult users of legal age. Minors must operate under the direct supervision and full responsibility of a parent or legal guardian.",
            "Personal Safety & Protective Gear" to "Always wear certified protective equipment (helmet, pads) and thoroughly inspect brakes, tire pressure, folding mechanisms, and steering components prior to every ride. Never operate your vehicle while distracted, fatigued, or under the influence of alcohol, medication, or drugs.",
            "Hardware Risks & Component Damage" to "Executing low-level diagnostic commands, modifying firmware registers, altering speed limiters, or flashing custom MCU packages carries inherent technical risks. Improper parameter configuration can cause permanent damage to the motor controller, thermal runaway in lithium-ion battery packs, sudden brake lockups, or severe electronic malfunctions.",
            "Legal Compliance & Road Regulations" to "Altering factory-set speed ceilings, power limits, or software profiles may directly violate local traffic laws, regional vehicle type-approval standards (e.g., eKFV in Germany), and public road regulations. Vehicles modified beyond legal thresholds are strictly prohibited from being operated on public thoroughfares, bicycle lanes, or sidewalks.",
            "Insurance & Warranty Voidance" to "Modifying firmware or bypassing built-in hardware restrictions instantly voids the manufacturer's warranty. Furthermore, changes to maximum speed or output wattage generally invalidate statutory third-party liability insurance coverage, exposing the operator to catastrophic financial and legal liability in the event of an accident.",
            "Independent Third-Party Project" to "Syrak ScooterLab is an independent, open developer toolkit. It has no affiliation, endorsement, sponsorship, or official connection with Segway-Ninebot, Xiaomi, Navee, or any other micro-mobility manufacturer.",
            "As-Is Disclaimer & User Responsibility" to "The software is provided strictly on an 'as-is' basis without warranties of any kind, express or implied. By proceeding, you assume 100% personal liability for any property damage, bodily injury, legal penalties, or regulatory fines resulting from usage."
        )

        sections.forEach { (heading, body) ->
            content.addView(makeText(heading, 14f, Color.rgb(230, 70, 70), true).apply { setPadding(0, 0, 0, dp(4)) })
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

    private fun showScanScreen() {
        rootContainer.removeAllViews()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(12, 14, 18))
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
        titleContainer.addView(makeText("Scooter auswählen und verbinden", 14f, Color.rgb(145, 155, 170)).apply {
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
            setTextColor(Color.rgb(0, 210, 130))
            setOnClickListener { showSupportedModelsDialog() }
        }

        val demoBtn = Button(this).apply {
            text = "Demo"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(Color.rgb(40, 30, 20))
            setTextColor(Color.rgb(255, 165, 0))
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
            setBackgroundColor(Color.rgb(20, 24, 31))
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
        content.addView(scanButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(16), 0, dp(16))
        })

        deviceListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceListContainer)

        content.addView(makeText("Es werden Bluetooth-Geräte in der Nähe angezeigt. Tippe auf einen Scooter, um die Verbindung aufzubauen.", 12f, Color.rgb(125, 135, 150)).apply {
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

    private fun showMainDashboard(deviceName: String, deviceAddress: String, isDemo: Boolean) {
        rootContainer.removeAllViews()

        demoMode = isDemo
        currentLimitKmh = activeRegion.stockSpeedKmh

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.rgb(12, 14, 18))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        topBar.addView(makeText("SYRAK", 20f, Color.WHITE, true))
        topBar.addView(makeText(" SCOOTERLAB", 11f, Color.rgb(200, 50, 50), true))
        content.addView(topBar)

        // Region-Aware Status Banner
        statusBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(46, 125, 50), 8f)
        }
        content.addView(statusBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val deviceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(20, 24, 31))
        }
        deviceCard.addView(makeText(deviceName, 16f, Color.WHITE, true))
        deviceCard.addView(makeText(if (isDemo) "Demo Simulation  •  $deviceAddress  •  MTU 247" else "BLE Connected  •  $deviceAddress  •  MTU 247", 12f, Color.rgb(130, 140, 155)).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val disconnectBtn = Button(this).apply {
            text = "DISCONNECT"
            isAllCaps = true
            setBackgroundColor(Color.rgb(30, 38, 48))
            setTextColor(Color.WHITE)
            setOnClickListener { showScanScreen() }
        }
        deviceCard.addView(disconnectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        content.addView(deviceCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })

        // Speed card with Live Feed & Controller Limit
        val speedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(20))
            setBackgroundColor(Color.rgb(20, 24, 31))
        }
        liveSpeedValue = makeText(if (isDemo) "28" else "0", 54f, Color.WHITE, true)
        speedCard.addView(liveSpeedValue)
        speedCard.addView(makeText("KM/H", 12f, Color.rgb(220, 60, 60), true))
        speedCard.addView(makeText(if (isDemo) "LIVE FEED (DEMO SIMULATION)" else "LIVE FEED (WAITING FOR TELEMETRY)", 10f, if (isDemo) Color.rgb(255, 165, 0) else Color.rgb(0, 200, 120), true).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        fun createMetricCol(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(makeText(label, 10f, Color.rgb(130, 140, 155)))
            addView(makeText(value, 15f, Color.WHITE, true).apply { setPadding(0, dp(2), 0, 0) })
        }
        metricsRow.addView(createMetricCol("POWER", if (isDemo) "412 W" else "0 W"))
        metricsRow.addView(createMetricCol("ODOMETER", if (isDemo) "1284.6 KM" else "--- KM"))
        metricsRow.addView(createMetricCol("RANGE", if (isDemo) "26.1 KM" else "--- KM"))
        speedCard.addView(metricsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(speedCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })

        // --- REGION BASELINE CARD ---
        val regionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(20, 24, 31))
        }
        regionCard.addView(makeText("REGION BASELINE", 11f, Color.rgb(130, 140, 155), true).apply { setPadding(0, 0, 0, dp(6)) })
        regionValue = makeText("", 15f, Color.WHITE, true)
        regionCard.addView(regionValue)
        regionCard.addView(makeText("Police Mode restores this region's original stock speed — never a rigid global constant.", 11f, Color.rgb(130, 140, 155)).apply {
            setPadding(0, dp(2), 0, dp(10))
        })
        val regionButtonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val detectBtn = makeButton("AUTO-DETECT", Color.rgb(0, 137, 123))
        detectBtn.setOnClickListener { detectRegion() }
        val changeBtn = makeButton("CHANGE REGION ▸", Color.rgb(42, 50, 61))
        changeBtn.setOnClickListener { cycleRegion() }
        regionButtonsRow.addView(rowChild(detectBtn))
        regionButtonsRow.addView(spacer(8))
        regionButtonsRow.addView(rowChild(changeBtn))
        regionCard.addView(regionButtonsRow)
        content.addView(regionCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })

        content.addView(makeText("  POWER", 11f, Color.rgb(220, 60, 60), true).apply { setPadding(0, dp(4), 0, dp(6)) })

        fun createGridCard(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(20, 24, 31))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            addView(makeText(label, 11f, Color.rgb(130, 140, 155)))
            addView(makeText(value, 18f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) })
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            addView(createGridCard("⚡ VOLTAGE", if (isDemo) "40.6 V" else "-- V"))
            addView(createGridCard("📈 CURRENT", if (isDemo) "10.2 A" else "-- A"))
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            addView(createGridCard("🔋 BATTERY", if (isDemo) "87 %" else "-- %"))
            addView(createGridCard("🌡 MOTOR TEMP", if (isDemo) "42 °C" else "-- °C"))
        }
        content.addView(row1)
        content.addView(row2)

        content.addView(makeText("  THERMAL & CELLS", 11f, Color.rgb(220, 60, 60), true).apply { setPadding(0, dp(12), 0, dp(6)) })

        val thermalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(20, 24, 31))
        }
        val thermalHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thermalHeader.addView(makeText("Pack health", 15f, Color.WHITE, true))
        val nominalBadge = TextView(this).apply {
            text = if (isDemo) "  NOMINAL  " else "  LIVE  "
            textSize = 10f
            setTextColor(Color.rgb(0, 210, 130))
            gravity = Gravity.END
        }
        thermalHeader.addView(nominalBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        thermalCard.addView(thermalHeader)

        fun createCellDetailRow(l: String, r: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
            addView(makeText(l, 12f, Color.rgb(130, 140, 155)))
            addView(makeText(r, 12f, Color.WHITE, true).apply { gravity = Gravity.END })
        }

        val c1 = createCellDetailRow("Cell min", if (isDemo) "3.621 V" else "--- V")
        c1.getChildAt(1).layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val c2 = createCellDetailRow("Cell max", if (isDemo) "3.688 V" else "--- V")
        c2.getChildAt(1).layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val c3 = createCellDetailRow("Cell delta", if (isDemo) "67 mV" else "--- mV")
        c3.getChildAt(1).layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val c4 = createCellDetailRow("Battery temp", if (isDemo) "31.4 °C" else "--- °C")
        c4.getChildAt(1).layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        thermalCard.addView(c1)
        thermalCard.addView(c2)
        thermalCard.addView(c3)
        thermalCard.addView(c4)
        content.addView(thermalCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })

        content.addView(makeText("  SAFETY / PANIC", 11f, Color.rgb(220, 60, 60), true).apply { setPadding(0, dp(4), 0, dp(6)) })

        val safetyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(20, 24, 31))
        }

        // --- THE NEW POLICE MODE / PANIC BUTTON ---
        policeButton = Button(this).apply {
            text = "⚠   POLICE MODE (PANIC)"
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(Color.rgb(191, 54, 12), 12f)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            stateListAnimator = null
            setOnClickListener { triggerPoliceMode() }
        }
        safetyCard.addView(policeButton)
        safetyCard.addView(makeText("One tap: flush the volatile RAM speed patch and instantly restore your regional stock profile.", 11f, Color.rgb(130, 140, 155)).apply {
            setPadding(0, dp(6), 0, dp(12))
        })

        safetyCard.addView(makeText("Emergency killswitch", 15f, Color.WHITE, true))
        safetyCard.addView(makeText("Cuts throttle output and locks the controller. Electronic containment only — always use the physical brake.", 12f, Color.rgb(130, 140, 155)).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val killswitchBtn = Button(this).apply {
            text = "ENGAGE KILLSWITCH"
            isAllCaps = true
            setBackgroundColor(Color.rgb(255, 60, 60))
            setTextColor(Color.WHITE)
        }
        safetyCard.addView(killswitchBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        content.addView(safetyCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })

        content.addView(makeText("  TUNING", 11f, Color.rgb(220, 60, 60), true).apply { setPadding(0, dp(4), 0, dp(6)) })

        val tuningCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(20, 24, 31))
        }
        tuningCard.addView(makeText("German Maneuver", 15f, Color.WHITE, true))
        tuningCard.addView(makeText("Volatile RAM patch — raises the speed ceiling until the next power cycle. Baseline values are captured for one-tap revert.", 12f, Color.rgb(130, 140, 155)).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        maneuverButton = Button(this).apply {
            text = "APPLY GERMAN MANEUVER (TEST)"
            isAllCaps = true
            textSize = 13f
            setBackgroundColor(Color.rgb(239, 108, 0))
            setTextColor(Color.WHITE)
            setOnClickListener { applyGermanManeuver() }
        }
        tuningCard.addView(maneuverButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        content.addView(tuningCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })

        content.addView(makeText("  CONSOLE", 11f, Color.rgb(220, 60, 60), true).apply { setPadding(0, dp(4), 0, dp(6)) })

        consoleView = TextView(this).apply {
            setTextColor(Color.rgb(124, 227, 139))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.rgb(5, 7, 10))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            movementMethod = ScrollingMovementMethod()
            maxLines = 12
        }
        content.addView(consoleView)

        scroll.addView(content)
        rootContainer.addView(scroll)

        appendConsole("boot: Syrak ScooterLab safety console ready")
        appendConsole("region: auto-detected ${activeRegion.flag} ${activeRegion.displayName} (stock ${activeRegion.stockSpeedKmh} km/h)")
        appendConsole("link: connected $deviceAddress (${if (isDemo) "DEMO" else "BLE"})")

        refreshUi()
        startSpeedSimulation()
    }

    // =========================================================================
    //  Region & Police Mode Operations
    // =========================================================================

    private fun detectRegion() {
        detectedRegion = RegionRegistry.detectFromLocale()
        regionOverride = null
        appendConsole("region: re-detected ${activeRegion.flag} ${activeRegion.displayName} (stock ${activeRegion.stockSpeedKmh} km/h)")
        if (!ramPatchActive) {
            currentLimitKmh = activeRegion.stockSpeedKmh
            pushControllerFrame(buildSpeedLimitFrame(currentLimitKmh, volatile = false), "set stock limit ${currentLimitKmh} km/h")
        }
        refreshUi()
    }

    private fun cycleRegion() {
        val options = RegionRegistry.selectable()
        val currentIndex = options.indexOfFirst { it.countryCode == activeRegion.countryCode }
        val next = options[(currentIndex + 1) % options.size]
        regionOverride = next
        appendConsole("region: baseline pinned to ${next.flag} ${next.displayName} (stock ${next.stockSpeedKmh} km/h)")
        if (!ramPatchActive) {
            currentLimitKmh = next.stockSpeedKmh
            pushControllerFrame(buildSpeedLimitFrame(currentLimitKmh, volatile = false), "set stock limit ${currentLimitKmh} km/h")
        }
        refreshUi()
    }

    private fun applyGermanManeuver() {
        if (ramPatchActive) {
            appendConsole("maneuver: already active — ignoring duplicate request")
            return
        }
        val unlocked = activeRegion.stockSpeedKmh + 12
        ramPatchActive = true
        currentLimitKmh = unlocked
        pushControllerFrame(buildSpeedLimitFrame(unlocked, volatile = true), "RAM speed patch → $unlocked km/h (volatile)")
        appendConsole("maneuver: German Maneuver engaged — volatile patch is LIVE")
        bannerState = BannerState.MANEUVER
        refreshUi()
    }

    private fun flushVolatileRamPatch() {
        if (!ramPatchActive) {
            appendConsole("maneuver: no volatile RAM patch present — flush skipped")
            return
        }
        pushControllerFrame(buildSpeedLimitFrame(0, volatile = true), "flush volatile RAM speed patch")
        ramPatchActive = false
        appendConsole("maneuver: volatile RAM speed patch flushed")
    }

    private fun triggerPoliceMode() {
        flushVolatileRamPatch()
        revertToRegionalStock()
        bannerState = BannerState.POLICE
        refreshUi()

        appendConsole("security: police mode triggered — restored regional stock profile for ${activeRegion.displayName}")
        Toast.makeText(this, "Police Mode: stock restored for ${activeRegion.displayName}", Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            if (bannerState == BannerState.POLICE) {
                bannerState = BannerState.STOCK
                refreshUi()
            }
        }, 4000L)
    }

    private fun revertToRegionalStock() {
        val stock = activeRegion.stockSpeedKmh
        currentLimitKmh = stock
        pushControllerFrame(buildSpeedLimitFrame(stock, volatile = false), "restore regional stock limit $stock km/h")
        if (liveSpeedKmh > stock) liveSpeedKmh = stock.toDouble()
        appendConsole("controller: speed limit reverted to regional stock $stock km/h for ${activeRegion.displayName}")
    }

    private fun pushControllerFrame(payload: ByteArray, label: String) {
        val activeGatt = gatt
        val characteristic = writeCharacteristic
        if (demoMode || activeGatt == null || characteristic == null) {
            appendConsole("demo: link simulated → $label")
            return
        }
        try {
            characteristic.value = payload
            val ok = activeGatt.writeCharacteristic(characteristic)
            appendConsole(if (ok) "ble: tx ok → $label" else "ble: tx failed → $label")
        } catch (_: Exception) {
            appendConsole("ble: error during frame push → $label")
        }
    }

    private fun buildSpeedLimitFrame(limitKmh: Int, volatile: Boolean): ByteArray {
        val limit = limitKmh.coerceIn(0, 255).toByte()
        val flags = if (volatile) 0x01.toByte() else 0x00.toByte()
        val body = byteArrayOf(0xA5.toByte(), 0x21.toByte(), limit, flags)
        val checksum = body.fold(0) { acc, b -> acc xor b.toInt() }.toByte()
        return body + checksum
    }

    private fun refreshUi() {
        if (::regionValue.isInitialized) {
            regionValue.text = "${activeRegion.flag}  ${activeRegion.displayName}  ·  stock ${activeRegion.stockSpeedKmh} km/h"
        }
        if (::limitValue.isInitialized) {
            limitValue.text = "$currentLimitKmh km/h"
        }
        if (::statusBanner.isInitialized) {
            when (bannerState) {
                BannerState.STOCK -> {
                    statusBanner.text = "■ STOCK / SAFE — ${activeRegion.displayName} (${activeRegion.stockSpeedKmh} km/h)"
                    statusBanner.background = rounded(Color.rgb(46, 125, 50), 8f)
                }
                BannerState.MANEUVER -> {
                    statusBanner.text = "■ ⚠ GERMAN MANEUVER ACTIVE — ($currentLimitKmh km/h)"
                    statusBanner.background = rounded(Color.rgb(239, 108, 0), 8f)
                }
                BannerState.POLICE -> {
                    statusBanner.text = "■ 🛡 POLICE MODE — STOCK RESTORED (${activeRegion.displayName})"
                    statusBanner.background = rounded(Color.rgb(0, 105, 92), 8f)
                }
            }
        }
        if (::maneuverButton.isInitialized) {
            maneuverButton.isEnabled = !ramPatchActive
            maneuverButton.alpha = if (ramPatchActive) 0.5f else 1f
        }
    }

    private fun appendConsole(line: String) {
        val stamp = timeFmt.format(Date())
        val entry = "[$stamp] $line"
        if (::consoleView.isInitialized) {
            consoleView.append(if (consoleView.text.isEmpty()) entry else "\n$entry")
        }
    }

    private fun startSpeedSimulation() {
        handler.post(object : Runnable {
            override fun run() {
                val ceiling = currentLimitKmh.toDouble()
                val drift = rng.nextDouble(-3.5, 4.5)
                liveSpeedKmh = (liveSpeedKmh + drift).coerceIn(0.0, ceiling)
                if (::liveSpeedValue.isInitialized) {
                    liveSpeedValue.text = "${liveSpeedKmh.roundToInt()}"
                }
                handler.postDelayed(this, 1000L)
            }
        })
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun rowChild(view: View): View {
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return view
    }

    private fun spacer(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun makeButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(color, 10f)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        stateListAnimator = null
    }

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
                } catch (_: Exception) {}
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
                setBackgroundColor(if (item.isScooterCandidate) Color.rgb(25, 40, 32) else Color.rgb(20, 24, 31))
                setOnClickListener {
                    if (isScanning) {
                        try {
                            bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
                        } catch (_: Exception) {}
                        isScanning = false
                    }
                    showMainDashboard(item.name, item.address, item.isDemo)
                }
            }
            card.addView(makeText(item.name, 15f, Color.WHITE, true))
            card.addView(makeText(if (item.isDemo) "Demo-Gerät  •  Tippen zum Verbinden" else "Signal: ${item.rssi} dBm  •  ${item.address} (Tippen zum Verbinden)", 12f, Color.rgb(145, 155, 170)).apply {
                setPadding(0, dp(5), 0, 0)
            })
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(10))
            }
            deviceListContainer.addView(card, params)
        }
    }
}
