package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var statusText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var scanButton: Button

    private var isScanning = false
    private val foundDevices = linkedMapOf<String, String>()
    private val handler = Handler(Looper.getMainLooper())

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = requiredPermissions().all { perm ->
                results[perm] == true || ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                checkBluetoothAndScan()
            } else {
                statusText.text = "Berechtigungen fehlen. Bluetooth-Scan nicht möglich."
                Toast.makeText(this, "Bitte erlaube alle Berechtigungen.", Toast.LENGTH_LONG).show()
            }
        }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val address = device.address
                val name = device.name ?: result.scanRecord?.deviceName ?: "Unbekanntes BLE-Gerät"
                foundDevices[address] = name
                updateDeviceList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "BLE-Scan Fehler Code: $errorCode"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter ?: run {
            showBluetoothUnavailableScreen()
            return
        }

        buildUserInterface()
        statusText.text = "Bereit für den BLE-Scan."
    }

    private fun buildUserInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 24)
            setBackgroundColor(android.graphics.Color.rgb(12, 12, 18))
        }

        val title = TextView(this).apply {
            text = "SYRAK SCOOTERLAB"
            textSize = 25f
            setTextColor(android.graphics.Color.rgb(0, 230, 255))
            setPadding(0, 0, 0, 12)
        }

        val subtitle = TextView(this).apply {
            text = "Bluetooth Low Energy Scanner"
            textSize = 17f
            setTextColor(android.graphics.Color.WHITE)
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 20, 0, 20)
        }

        scanButton = Button(this).apply {
            text = "Bluetooth-Scan starten"
            setOnClickListener {
                requestPermissionsAndScan()
            }
        }

        val listTitle = TextView(this).apply {
            text = "GEFUNDENE GERÄTE"
            textSize = 16f
            setTextColor(android.graphics.Color.rgb(0, 230, 255))
            setPadding(0, 24, 0, 12)
        }

        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this).apply {
            addView(
                deviceList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(
            scanButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(listTitle)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissionsAndScan() {
        if (!hasRequiredPermissions()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        checkBluetoothAndScan()
    }

    private fun checkBluetoothAndScan() {
        if (!bluetoothAdapter.isEnabled) {
            statusText.text = "Bluetooth ist ausgeschaltet. Bitte aktivieren."
            return
        }

        if (!isLocationEnabled()) {
            statusText.text = "Bitte Standort / GPS am Handy aktivieren!"
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        startBleScan()
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning) return

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            statusText.text = "BLE-Scanner nicht verfügbar."
            return
        }

        foundDevices.clear()
        updateDeviceList()

        isScanning = true
        scanButton.isEnabled = false
        statusText.text = "BLE-Scan läuft (10 Sek.) ..."

        scanner.startScan(leScanCallback)

        handler.postDelayed({
            if (isScanning) {
                scanner.stopScan(leScanCallback)
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = if (foundDevices.isEmpty()) {
                    "Keine Geräte in Reichweite gefunden."
                } else {
                    "${foundDevices.size} Gerät(e) gefunden."
                }
            }
        }, 10000)
    }

    private fun updateDeviceList() {
        deviceList.removeAllViews()
        if (foundDevices.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Suche läuft..."
                textSize = 14f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 8, 0, 8)
            }
            deviceList.addView(emptyText)
            return
        }

        foundDevices.forEach { (address, name) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(android.graphics.Color.rgb(28, 28, 40))
            }

            val nameText = TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
            }

            val addressText = TextView(this).apply {
                text = "MAC: $address"
                textSize = 13f
                setTextColor(android.graphics.Color.rgb(0, 230, 255))
                setPadding(0, 6, 0, 0)
            }

            item.addView(nameText)
            item.addView(addressText)

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
            deviceList.addView(item, params)
        }
    }

    private fun showBluetoothUnavailableScreen() {
        val text = TextView(this).apply {
            text = "Dieses Gerät unterstützt kein Bluetooth."
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }
        setContentView(text)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(leScanCallback)
        }
    }
}
