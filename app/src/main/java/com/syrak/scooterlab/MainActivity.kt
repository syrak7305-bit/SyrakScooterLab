package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

data class ScooterDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isScooterCandidate: Boolean
)

class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var rootContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var activeTimer: CountDownTimer? = null

    private var isScanning = false
    private val foundDevices = linkedMapOf<String, ScooterDevice>()
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListContainer: LinearLayout

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            statusText.text = "Diagnostic core online. Ready for BLE scan."
            scanButton.isEnabled = true
        } else {
            statusText.text = "Bluetooth permissions required for operation."
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { res ->
                val device = res.device
                val address = device.address
                val name = try {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        device.name ?: res.scanRecord?.deviceName ?: "Unknown Unit"
                    } else {
                        res.scanRecord?.deviceName ?: "Unknown Unit"
                    }
                } catch (e: Exception) {
                    "Unknown Unit"
                }

                val knownKeywords = listOf("scooter", "ninebot", "xiaomi", "navee", "m365", "segway", "soflow")
                val isCandidate = knownKeywords.any { name.lowercase().contains(it) }

                foundDevices[address] = ScooterDevice(device, name, address, res.rssi, isCandidate)
                updateDeviceList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "Scan execution failed: $errorCode"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(12, 14, 18)) // Deep Cyberpunk Dark
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setContentView(rootContainer)
        showLoadingScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeTimer?.cancel()
    }

    private fun showLoadingScreen() {
        rootContainer.removeAllViews()

        val innerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Cyber Logo Header Badge
        val logoBadge = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.rgb(20, 24, 33))
            gravity = Gravity.CENTER
        }

        val badgeTitle = TextView(this).apply {
            text = "SYRAK SCOOTERLAB"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val badgeSub = TextView(this).apply {
            text = "ELITE FIRMWARE & TUNING SUITE"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(0, 230, 118)) // Acid Green
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 0)
        }

        logoBadge.addView(badgeTitle)
        logoBadge.addView(badgeSub)

        val versionText = TextView(this).apply {
            text = "v4.2.1-PRO // BUILD #36"
            textSize = 12f
            setTextColor(Color.rgb(100, 110, 125))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(550, 8).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val loadingStatus = TextView(this).apply {
            text = "Establishing secure memory connection..."
            textSize = 13f
            setTextColor(Color.rgb(255, 60, 60)) // Neon Red Accent
            gravity = Gravity.CENTER
        }

        innerLayout.addView(logoBadge)
        innerLayout.addView(versionText)
        innerLayout.addView(progressBar)
        innerLayout.addView(loadingStatus)
        rootContainer.addView(innerLayout)

        val steps = listOf(
            "Decrypting bootstrap modules (1/4)...",
            "Verifying controller compatibility (2/4)...",
            "Initializing RAM tuning hooks (3/4)...",
            "System environment ready (4/4)"
        )

        var currentStep = 0
        val stepHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (currentStep < steps.size) {
                    loadingStatus.text = steps[currentStep]
                    currentStep++
                    stepHandler.postDelayed(this, 650)
                } else {
                    showCriticalDisclaimer()
                }
            }
        }
        stepHandler.postDelayed(runnable, 500)
    }

    private fun showCriticalDisclaimer() {
        rootContainer.removeAllViews()

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        val innerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        val headerText = TextView(this).apply {
            text = "Critical Disclaimer: Read Before Use"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        innerLayout.addView(headerText)

        val sections = listOf(
            Pair("Age Requirement:", "You must be at least 18 years old to use this application. If you are under the legal age, you are strictly prohibited from modifying electric micro-mobility vehicles and must exit immediately."),
            Pair("Safety First:", "Always wear certified protective gear, test brakes and steering components regularly, obey all local traffic regulations, and remain fully aware of your surroundings during operation."),
            Pair("Potential for Hardware Damage:", "Modifying firmware, overriding speed governors, or executing RAM patches carries inherent risks of hardware failure, controller bricking (DRV damage), and battery management system (BMS) errors. All operations are executed entirely at your own risk."),
            Pair("No Affiliations:", "Syrak ScooterLab is an independent diagnostic development tool and has no official affiliation with Segway-Ninebot, Xiaomi, Navee, or any other scooter manufacturer."),
            Pair("Use at Your Own Risk & Liability:", "The developer assumes absolute zero liability for personal injury, property damage, hardware destruction, or legal penalties resulting from the deployment of this software.")
        )

        sections.forEach { (title, body) ->
            val titleView = TextView(this).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(255, 90, 90))
                setPadding(0, 0, 0, 2)
            }
            val bodyView = TextView(this).apply {
                text = body
                textSize = 13f
                setTextColor(Color.rgb(175, 185, 200))
                setPadding(0, 0, 0, 16)
            }
            innerLayout.addView(titleView)
            innerLayout.addView(bodyView)
        }

        scrollView.addView(innerLayout)
        rootContainer.addView(scrollView)

        val bottomLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }

        val agreeButton = Button(this).apply {
            text = "Agree (15)"
            isEnabled = false
            setBackgroundColor(Color.rgb(45, 50, 60))
            setTextColor(Color.GRAY)
            setOnClickListener {
                showCountryDisclaimer()
            }
        }

        val exitButton = Button(this).apply {
            text = "Exit application"
            setBackgroundColor(Color.rgb(30, 34, 42))
            setTextColor(Color.WHITE)
            setOnClickListener {
                finishAffinity()
            }
        }

        bottomLayout.addView(agreeButton)
        bottomLayout.addView(exitButton)
        rootContainer.addView(bottomLayout)

        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(15000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                agreeButton.text = "Agree ($seconds)"
            }
            override fun onFinish() {
                agreeButton.text = "Agree & Proceed"
                agreeButton.isEnabled = true
                agreeButton.setBackgroundColor(Color.rgb(0, 160, 90))
                agreeButton.setTextColor(Color.WHITE)
            }
        }.start()
    }

    private fun showCountryDisclaimer() {
        activeTimer?.cancel()
        rootContainer.removeAllViews()

        val currentLocale = Locale.getDefault()
        val countryName = currentLocale.getDisplayCountry(Locale.GERMAN).ifEmpty { "Ihrem Land" }
        val countryCode = currentLocale.country

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        val innerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        val headerText = TextView(this).apply {
            text = "Zusätzlicher Hinweis: Nutzung in $countryName"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        innerLayout.addView(headerText)

        val bodyTextContent = if (countryCode.equals("DE", ignoreCase = true)) {
            "Diese Anwendung ist explizit nicht für den Betrieb von Elektrokleinstfahrzeugen im öffentlichen Straßenverkehr in der Bundesrepublik Deutschland bestimmt.\n\n" +
                    "• Erlöschen der Betriebserlaubnis (ABE): Jede unautorisierte Modifikation der Motorleistung, Drehmomentbegrenzung oder Höchstgeschwindigkeit führt zum sofortigen Erlöschen der Allgemeinen Betriebserlaubnis nach StVZO.\n" +
                    "• Strafrechtliche Konsequenzen: Das Fahren von modifizierten Fahrzeugen im öffentlichen Verkehrsraum erfüllt unter anderem Straftatbestände nach § 21 StVG (Fahren ohne Fahrerlaubnis) sowie § 6 PflVG (Verstoß gegen das Pflichtversicherungsgesetz), geahndet mit Geldstrafen oder Freiheitsstrafen bis zu einem Jahr.\n" +
                    "• Finanzielle Haftung: Bei Unfällen mit manipulierten Fahrzeugen entfällt jeglicher Versicherungsschutz. Sie haften persönlich und unbeschränkt mit Ihrem gesamten Privatvermögen."
        } else {
            "Bitte beachten Sie strikt die geltenden nationalen Gesetze und Vorschriften für Elektrokleinstfahrzeuge in $countryName.\n\n" +
                    "• Geschwindigkeitsbegrenzungen und technische Veränderungen unterliegen den lokalen Verkehrsbehörden.\n" +
                    "• Jeglicher Betrieb außerhalb von ausgewiesenen Privatgeländen erfolgt auf eigene rechtliche und zivile Verantwortung."
        }

        val bodyView = TextView(this).apply {
            text = bodyTextContent
            textSize = 13f
            setTextColor(Color.rgb(175, 185, 200))
            setPadding(0, 0, 0, 16)
        }
        innerLayout.addView(bodyView)

        scrollView.addView(innerLayout)
        rootContainer.addView(scrollView)

        val bottomLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }

        val acceptButton = Button(this).apply {
            text = "Ich verstehe und akzeptiere (15)"
            isEnabled = false
            setBackgroundColor(Color.rgb(45, 50, 60))
            setTextColor(Color.GRAY)
            setOnClickListener {
                showMainDashboard()
            }
        }

        val exitButton = Button(this).apply {
            text = "App beenden"
            setBackgroundColor(Color.rgb(30, 34, 42))
            setTextColor(Color.WHITE)
            setOnClickListener {
                finishAffinity()
            }
        }

        bottomLayout.addView(acceptButton)
        bottomLayout.addView(exitButton)
        rootContainer.addView(bottomLayout)

        activeTimer = object : CountDownTimer(15000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                acceptButton.text = "Ich verstehe und akzeptiere ($seconds)"
            }
            override fun onFinish() {
                acceptButton.text = "Ich verstehe und akzeptiere"
                acceptButton.isEnabled = true
                acceptButton.setBackgroundColor(Color.rgb(0, 160, 90))
                acceptButton.setTextColor(Color.WHITE)
            }
        }.start()
    }

    private fun showMainDashboard() {
        rootContainer.removeAllViews()

        val headerBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 12, 20, 12)
            setBackgroundColor(Color.rgb(20, 24, 33))
        }

        val titleText = TextView(this).apply {
            text = "Syrak ScooterLab"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        headerBadge.addView(titleText)

        statusText = TextView(this).apply {
            text = "Status: Idle. Ready for BLE scan."
            textSize = 13f
            setTextColor(Color.rgb(150, 165, 180))
            setPadding(0, 16, 0, 12)
        }

        scanButton = Button(this).apply {
            text = "SCAN FOR SCOOTERS"
            setOnClickListener { startBleScan() }
        }

        deviceListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val dashboardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        dashboardLayout.addView(headerBadge)
        dashboardLayout.addView(statusText)
        dashboardLayout.addView(scanButton)
        dashboardLayout.addView(deviceListContainer)
        scrollView.addView(dashboardLayout)

        rootContainer.addView(scrollView)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            statusText.text = "Status: Bluetooth unavailable."
            scanButton.isEnabled = false
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun startBleScan() {
        if (!checkPermissions()) return
        if (isScanning) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            statusText.text = "Status: Bluetooth is disabled."
            return
        }

        foundDevices.clear()
        updateDeviceList()

        isScanning = true
        scanButton.isEnabled = false
        statusText.text = "Status: Scanning ambient frequencies..."

        try {
            scanner.startScan(leScanCallback)
        } catch (e: Exception) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "Status: Scan initialization failed."
            return
        }

        handler.postDelayed({
            if (isScanning) {
                try { scanner.stopScan(leScanCallback) } catch (e: Exception) {}
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = "Status: Scan completed. Found ${foundDevices.size} device(s)."
            }
        }, 10000)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDeviceList() {
        deviceListContainer.removeAllViews()

        foundDevices.values.sortedByDescending { it.rssi }.forEach { scooter ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                setBackgroundColor(
                    if (scooter.isScooterCandidate) Color.rgb(15, 45, 30)
                    else Color.rgb(22, 26, 34)
                )
            }

            val nameText = TextView(this).apply {
                text = scooter.name
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val detailsText = TextView(this).apply {
                text = "MAC: ${scooter.address} | RSSI: ${scooter.rssi} dBm"
                textSize = 12f
                setTextColor(Color.rgb(140, 155, 170))
                setPadding(0, 4, 0, 0)
            }

            card.addView(nameText)
            card.addView(detailsText)

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }

            deviceListContainer.addView(card, params)
        }
    }
}
