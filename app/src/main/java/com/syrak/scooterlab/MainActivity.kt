package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice.ACTION_FOUND
import android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED
import android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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

    private var receiverRegistered = false
    private var isScanning = false

    private val foundDevices = linkedMapOf<String, String>()

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = requiredPermissions().all { permission ->
                results[permission] == true ||
                    ContextCompat.checkSelfPermission(
                        this,
                        permission
                    ) == PackageManager.PERMISSION_GRANTED
            }

            if (allGranted) {
                checkBluetoothAndScan()
            } else {
                statusText.text =
                    "Berechtigungen fehlen. Bluetooth-Scan nicht möglich."
                Toast.makeText(
                    this,
                    "Bitte erlaube die benötigten Berechtigungen.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val bluetoothEnableLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (hasRequiredPermissions()) {
                if (isBluetoothEnabled()) {
                    startBluetoothScan()
                } else {
                    statusText.text =
                        "Bluetooth ist ausgeschaltet."
                }
            }
        }

    private val bluetoothReceiver = object : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {

            when (intent.action) {

                ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE
                            )
                        }

                    device?.let {
                        val address = it.address
                        val name = it.name ?: "Unbekanntes Gerät"

                        foundDevices[address] = name
                        updateDeviceList()
                    }
                }

                ACTION_DISCOVERY_STARTED -> {
                    isScanning = true
                    statusText.text = "Bluetooth-Scan läuft ..."
                    scanButton.isEnabled = false
                }

                ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                    scanButton.isEnabled = true

                    statusText.text =
                        if (foundDevices.isEmpty()) {
                            "Keine Geräte gefunden."
                        } else {
                            "${foundDevices.size} Gerät(e) gefunden."
                        }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        bluetoothAdapter = bluetoothManager.adapter
            ?: run {
                showBluetoothUnavailableScreen()
                return
            }

        buildUserInterface()
        registerBluetoothReceiver()

        statusText.text = "Bereit für den Bluetooth-Scan."
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
            text = "Bluetooth-Diagnose"
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
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
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

        if (!isBluetoothEnabled()) {
            statusText.text =
                "Bluetooth ist ausgeschaltet. Bitte aktiviere es."

            bluetoothEnableLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            )
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !isLocationEnabled()
        ) {
            statusText.text =
                "Bitte aktiviere die Standortdienste."

            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        startBluetoothScan()
    }

    private fun isBluetoothEnabled(): Boolean {
        return try {
            bluetoothAdapter.isEnabled
        } catch (e: SecurityException) {
            false
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(
                    contentResolver,
                    Settings.Secure.LOCATION_MODE
                ) != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {

        if (!hasRequiredPermissions()) {
            requestPermissionsAndScan()
            return
        }

        if (!isBluetoothEnabled()) {
            statusText.text = "Bluetooth ist ausgeschaltet."
            return
        }

        if (isScanning) {
            statusText.text = "Scan läuft bereits."
            return
        }

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }

            foundDevices.clear()
            updateDeviceList()

            statusText.text = "Suche nach Bluetooth-Geräten ..."

            val started = bluetoothAdapter.startDiscovery()

            if (!started) {
                statusText.text =
                    "Scan konnte nicht gestartet werden. Bitte erneut versuchen."
            }

        } catch (e: SecurityException) {
            statusText.text =
                "Bluetooth-Berechtigung fehlt oder wurde widerrufen."
        } catch (e: Exception) {
            statusText.text = "Bluetooth-Fehler: ${e.message}"
        }
    }

    private fun updateDeviceList() {

        deviceList.removeAllViews()

        if (foundDevices.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Noch keine Geräte gefunden."
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
                setBackgroundColor(
                    android.graphics.Color.rgb(28, 28, 40)
                )
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

    private fun registerBluetoothReceiver() {

        val filter = IntentFilter().apply {
            addAction(ACTION_FOUND)
            addAction(ACTION_DISCOVERY_STARTED)
            addAction(ACTION_DISCOVERY_FINISHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.registerReceiver(
                bluetoothReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            this.registerReceiver(bluetoothReceiver, filter)
        }

        receiverRegistered = true
    }

    private fun showBluetoothUnavailableScreen() {
        val text = TextView(this).apply {
            text = "Dieses Smartphone unterstützt kein Bluetooth."
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }

        setContentView(text)
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            if (::bluetoothAdapter.isInitialized &&
                hasRequiredPermissions() &&
                bluetoothAdapter.isDiscovering
            ) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (_: SecurityException) {
        }

        if (receiverRegistered) {
            try {
                this.unregisterReceiver(bluetoothReceiver)
            } catch (_: IllegalArgumentException) {
            }

            receiverRegistered = false
        }
    }
}
