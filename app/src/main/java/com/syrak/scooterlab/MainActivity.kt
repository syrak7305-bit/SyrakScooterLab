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
            statusText.text = "System bereit. Berechtigungen aktiv."
            scanButton.isEnabled = true
        } else {
            statusText.text = "Bitte erteile die Bluetooth-Berechtigungen."
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { res ->
                val device = res.device
                val address = device.address
                val name = try {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        device.name ?: res.scanRecord?.deviceName ?: "Unbekanntes Gerät"
                    } else {
                        res.scanRecord?.deviceName ?: "Unbekanntes Gerät"
                    }
                } catch (e: Exception) {
                    "Unbekanntes Gerät"
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
            statusText.text = "Scan-Fehler Code: $errorCode"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(15, 17, 21)) // Premium Dark Theme
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setContentView(rootContainer)
        showLoadingScreen()
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

        val logoText = TextView(this).apply {
            text = "🛴"
            textSize = 56f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val titleText = TextView(this).apply {
            text = "Syrak ScooterLab"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        val versionText = TextView(this).apply {
            text = "v1.0.0 release / #33 PRO"
            textSize = 13f
            setTextColor(Color.rgb(120, 130, 145))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(600, 12).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val loadingStatus = TextView(this).apply {
            text = "Initialisiere Core-Module..."
            textSize = 14f
            setTextColor(Color.rgb(80, 180, 255))
            gravity = Gravity.CENTER
        }

        innerLayout.addView(logoText)
        innerLayout.addView(titleText)
        innerLayout.addView(versionText)
        innerLayout.addView(progressBar)
        innerLayout.addView(loadingStatus)
        rootContainer.addView(innerLayout)

        val steps = listOf(
            "Lade Konfigurationsdaten (1/4)...",
            "Prüfe Bluetooth-BLE-Schnittstelle (2/4)...",
            "Bereite RAM-Tuning Engine vor (3/4)...",
            "System bereit! (4/4)"
        )

        var currentStep = 0
        val stepHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (currentStep < steps.size) {
                    loadingStatus.text = steps[currentStep]
                    currentStep++
                    stepHandler.postDelayed(this, 700)
                } else {
                    showDisclaimerScreen()
                }
            }
        }
        stepHandler.postDelayed(runnable, 500)
    }

    private fun showDisclaimerScreen() {
        rootContainer.removeAllViews()

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val innerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 32)
        }

        val headerText = TextView(this).apply {
            text = "⚠️ Haftungsausschluss & Warnung"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(255, 90, 90))
            setPadding(0, 0, 0, 24)
        }

        innerLayout.addView(headerText)

        // Sauber strukturierte Absätze ohne hässliche Markdown-Sterne
        val sections = listOf(
            Pair("1. Keine Haftung", "Die Nutzung von Syrak ScooterLab, insbesondere Funktionen wie RAM-Tuning (German Maneuver), Regionsänderungen oder Firmware-Flashing, erfolgt vollkommen auf eigene Gefahr. Der Entwickler haftet für keinerlei Sach- oder Personenschäden."),
            Pair("2. Keine Garantie", "Es wird keine Garantie oder Gewährleistung für die Unversehrtheit des E-Scooters, der Steuereinheit (DRV) oder des Batteriemanagementsystems übernommen."),
            Pair("3. Straßenverkehr (StVO)", "Geschwindigkeitserhöhungen und Modifikationen verstoßen gegen gesetzliche Bestimmungen und sind im öffentlichen Straßenverkehr strengstens untersagt.")
        )

        sections.forEach { (title, body) ->
            val titleView = TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 4)
            }
            val bodyView = TextView(this).apply {
                text = body
                textSize = 13f
                setTextColor(Color.rgb(180, 190, 205))
                setPadding(0, 0, 0, 20)
            }
            innerLayout.addView(titleView)
            innerLayout.addView(bodyView)
        }

        val acceptButton = Button(this).apply {
            text = "✓ Akzeptieren & Fortfahren"
            setPadding(0, 24, 0, 24)
            setOnClickListener { showMainDashboard() }
        }

        val declineButton = Button(this).apply {
            text = "✕ Ablehnen & Schließen"
            setOnClickListener { finishAffinity() }
        }

        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 0)
        }
        btnContainer.addView(acceptButton)
        btnContainer.addView(declineButton)

        innerLayout.addView(btnContainer)
        scrollView.addView(innerLayout)
        rootContainer.addView(scrollView)
    }

    private fun showMainDashboard() {
        rootContainer.removeAllViews()

        val titleText = TextView(this).apply {
            text = "🛵 Syrak ScooterLab"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        statusText = TextView(this).apply {
            text = "Bereit zum Scannen..."
            textSize = 13f
            setTextColor(Color.rgb(160, 175, 195))
            setPadding(0, 0, 0, 16)
        }

        scanButton = Button(this).apply {
            text = "🔍 SCOOTER SUCHEN"
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
            statusText.text = "Bluetooth auf diesem Gerät nicht verfügbar."
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
            statusText.text = "Bluetooth ist ausgeschaltet."
            return
        }

        foundDevices.clear()
        updateDeviceList()

        isScanning = true
        scanButton.isEnabled = false
        statusText.text = "Suche läuft in der Umgebung..."

        try {
            scanner.startScan(leScanCallback)
        } catch (e: Exception) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "Fehler beim Starten: ${e.message}"
            return
        }

        handler.postDelayed({
            if (isScanning) {
                try { scanner.stopScan(leScanCallback) } catch (e: Exception) {}
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = "Scan beendet. ${foundDevices.size} Gerät(e) gefunden."
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
                    if (scooter.isScooterCandidate) Color.rgb(20, 55, 35)
                    else Color.rgb(26, 30, 38)
                )
            }

            val nameText = TextView(this).apply {
                text = if (scooter.isScooterCandidate) "🛵 ${scooter.name}" else scooter.name
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val detailsText = TextView(this).apply {
                text = "MAC: ${scooter.address} | Signal: ${scooter.rssi} dBm"
                textSize = 12f
                setTextColor(Color.rgb(170, 185, 200))
                setPadding(0, 4, 0, 0)
            }

            card.addView(nameText)
            card.addView(detailsText)

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }

            deviceListContainer.addView(card, params)
        }
    }
}
