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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListContainer: LinearLayout

    private var isScanning = false
    private val foundDevices = linkedMapOf<String, ScooterDevice>()
    private val handler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            statusText.text = "Berechtigungen erteilt. Tippe auf Scan."
            scanButton.isEnabled = true
        } else {
            statusText.text = "Bitte erteile alle Bluetooth-Berechtigungen."
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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val titleText = TextView(this).apply {
            text = "🛵 Syrak ScooterLab"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }

        statusText = TextView(this).apply {
            text = "Bereit zum Scannen..."
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }

        scanButton = Button(this).apply {
            text = "🔍 SCOOTER SUCHEN"
            setOnClickListener { startBleScan() }
        }

        deviceListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 0)
        }

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(scanButton)
        rootLayout.addView(deviceListContainer)

        setContentView(rootLayout)

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
        statusText.text = "Suche nach E-Scootern..."

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
                setPadding(20, 20, 20, 20)
                setBackgroundColor(
                    if (scooter.isScooterCandidate) android.graphics.Color.rgb(20, 60, 40)
                    else android.graphics.Color.rgb(40, 40, 40)
                )
            }

            val nameText = TextView(this).apply {
                text = if (scooter.isScooterCandidate) "🛵 ${scooter.name}" else scooter.name
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
            }

            val detailsText = TextView(this).apply {
                text = "MAC: ${scooter.address} | Signal: ${scooter.rssi} dBm"
                textSize = 12f
                setTextColor(android.graphics.Color.LTGRAY)
            }

            card.addView(nameText)
            card.addView(detailsText)

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 15) }

            deviceListContainer.addView(card, params)
        }
    }
}
