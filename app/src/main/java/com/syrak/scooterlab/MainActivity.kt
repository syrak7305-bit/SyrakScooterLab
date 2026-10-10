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
            statusText.text = "System active. Ready for diagnostics."
            scanButton.isEnabled = true
        } else {
            statusText.text = "Bluetooth permissions required."
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { res ->
                val device = res.device
                val address = device.address
                val name = try {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        device.name ?: res.scanRecord?.deviceName ?: "Unknown Device"
                    } else {
                        res.scanRecord?.deviceName ?: "Unknown Device"
                    }
                } catch (e: Exception) {
                    "Unknown Device"
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
            statusText.text = "Scan error code: $errorCode"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(15, 17, 21))
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

        val titleText = TextView(this).apply {
            text = "Syrak ScooterLab"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        val versionText = TextView(this).apply {
            text = "Diagnostic Suite v1.0"
            textSize = 13f
            setTextColor(Color.rgb(120, 130, 145))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(600, 10).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val loadingStatus = TextView(this).apply {
            text = "Initializing core environment..."
            textSize = 13f
            setTextColor(Color.rgb(80, 180, 255))
            gravity = Gravity.CENTER
        }

        innerLayout.addView(titleText)
        innerLayout.addView(versionText)
        innerLayout.addView(progressBar)
        innerLayout.addView(loadingStatus)
        rootContainer.addView(innerLayout)

        val steps = listOf(
            "Loading system configurations...",
            "Checking low-energy bluetooth stack...",
            "Preparing diagnostic modules...",
            "System ready."
        )

        var currentStep = 0
        val stepHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (currentStep < steps.size) {
                    loadingStatus.text = steps[currentStep]
                    currentStep++
                    stepHandler.postDelayed(this, 600)
                } else {
                    showCriticalDisclaimer()
                }
            }
        }
        stepHandler.postDelayed(runnable, 400)
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
            setPadding(16, 16, 16, 16)
        }

        val headerText = TextView(this).apply {
            text = "Safety & Liability Notice"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        innerLayout.addView(headerText)

        val sections = listOf(
            Pair("Age Restriction:", "You must be of legal age in your jurisdiction to operate or modify electric micro-mobility vehicles."),
            Pair("Hardware Integrity:", "Modifications to firmware or RAM parameters are performed entirely at your own risk. The developer assumes no responsibility for hardware bricking or component failure."),
            Pair("Operational Safety:", "Always wear appropriate safety gear and inspect your vehicle before operation.")
        )

        sections.forEach { (title, body) ->
            val row = TextView(this).apply {
                text = "$title $body"
                textSize = 13f
                setTextColor(Color.rgb(180, 190, 205))
                setPadding(0, 0, 0, 16)
            }
            innerLayout.addView(row)
        }

        scrollView.addView(innerLayout)
        rootContainer.addView(scrollView)

        val bottomLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        val agreeButton = Button(this).apply {
            text = "Agree (10)"
            isEnabled = false
            setBackgroundColor(Color.rgb(50, 55, 65))
            setTextColor(Color.LTGRAY)
            setOnClickListener {
                showCountryDisclaimer()
            }
        }

        val exitButton = Button(this).apply {
            text = "Exit Application"
            setBackgroundColor(Color.rgb(35, 40, 50))
            setTextColor(Color.WHITE)
            setOnClickListener {
                finishAffinity()
            }
        }

        bottomLayout.addView(agreeButton)
        bottomLayout.addView(exitButton)
        rootContainer.addView(bottomLayout)

        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                agreeButton.text = "Agree ($seconds)"
            }
            override fun onFinish() {
                agreeButton.text = "Agree"
                agreeButton.isEnabled = true
                agreeButton.setBackgroundColor(Color.rgb(70, 90, 110))
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
            setPadding(16, 16, 16, 16)
        }

        val headerText = TextView(this).apply {
            text = "Zusätzlicher Hinweis: Nutzung in $countryName"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        innerLayout.addView(headerText)

        val bodyTextContent = if (countryCode.equals("DE", ignoreCase = true)) {
            "Diese Anwendung ist nicht für den öffentlichen Straßenverkehr in Deutschland bestimmt.\n\n" +
                    "• Erlöschen der Betriebserlaubnis (ABE): Jede Modifikation der Leistung oder Höchstgeschwindigkeit führt zum Verlust der Straßenzulassung nach StVZO.\n" +
                    "• Rechtliche Konsequenzen: Modifikationen im öffentlichen Raum können unter § 21 StVG (Fahren ohne Fahrerlaubnis) oder § 6 PflVG (Fahren ohne Versicherungsschutz) fallen."
        } else {
            "Bitte beachten Sie die lokalen Gesetze und Vorschriften für Elektrokleinstfahrzeuge in $countryName.\n\n" +
                    "• Geschwindigkeitsbegrenzungen und technische Änderungen unterliegen den nationalen Verkehrsgesetzen.\n" +
                    "• Die Nutzung außerhalb privatem Gelände kann behördlichen Restriktionen unterliegen."
        }

        val bodyView = TextView(this).apply {
            text = bodyTextContent
            textSize = 13f
            setTextColor(Color.rgb(180, 190, 205))
            setPadding(0, 0, 0, 16)
        }
        innerLayout.addView(bodyView)

        scrollView.addView(innerLayout)
        rootContainer.addView(scrollView)

        val bottomLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        val acceptButton = Button(this).apply {
            text = "Verstanden (10)"
            isEnabled = false
            setBackgroundColor(Color.rgb(50, 55, 65))
            setTextColor(Color.LTGRAY)
            setOnClickListener {
                showMainDashboard()
            }
        }

        val exitButton = Button(this).apply {
            text = "App beenden"
            setBackgroundColor(Color.rgb(35, 40, 50))
            setTextColor(Color.WHITE)
            setOnClickListener {
                finishAffinity()
            }
        }

        bottomLayout.addView(acceptButton)
        bottomLayout.addView(exitButton)
        rootContainer.addView(bottomLayout)

        activeTimer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                acceptButton.text = "Verstanden ($seconds)"
            }
            override fun onFinish() {
                acceptButton.text = "Verstanden & Fortfahren"
                acceptButton.isEnabled = true
                acceptButton.setBackgroundColor(Color.rgb(40, 130, 70))
                acceptButton.setTextColor(Color.WHITE)
            }
        }.start()
    }

    private fun showMainDashboard() {
        rootContainer.removeAllViews()

        val titleText = TextView(this).apply {
            text = "Syrak ScooterLab"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        statusText = TextView(this).apply {
            text = "Ready to scan for nearby units..."
            textSize = 13f
            setTextColor(Color.rgb(160, 175, 195))
            setPadding(0, 0, 0, 16)
        }

        scanButton = Button(this).apply {
            text = "SCAN FOR SCOOTERS"
            setOnClickListener { startBleScan() }
        }

        deviceListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
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

        dashboardLayout.addView(titleText)
        dashboardLayout.addView(statusText)
        dashboardLayout.addView(scanButton)
        dashboardLayout.addView(deviceListContainer)
        scrollView.addView(dashboardLayout)

        rootContainer.addView(scrollView)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            statusText.text = "Bluetooth unavailable on this device."
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
            statusText.text = "Bluetooth is disabled."
            return
        }

        foundDevices.clear()
        updateDeviceList()

        isScanning = true
        scanButton.isEnabled = false
        statusText.text = "Scanning surroundings..."

        try {
            scanner.startScan(leScanCallback)
        } catch (e: Exception) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "Scan failed to start."
            return
        }

        handler.postDelayed({
            if (isScanning) {
                try { scanner.stopScan(leScanCallback) } catch (e: Exception) {}
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = "Scan completed. Found ${foundDevices.size} device(s)."
            }
        }, 10000)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDeviceList() {
        deviceListContainer.removeAllViews()

        foundDevices.values.sortedByDescending { it.rssi }.forEach { scooter ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(
                    if (scooter.isScooterCandidate) Color.rgb(20, 50, 35)
                    else Color.rgb(24, 28, 36)
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
                setTextColor(Color.rgb(150, 165, 180))
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
