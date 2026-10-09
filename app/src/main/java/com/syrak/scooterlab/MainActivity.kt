package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.UUID

data class ScooterDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isScooterCandidate: Boolean
)

class MainActivity : AppCompatActivity() {

    companion object {
        val UART_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val UART_TX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val UART_RX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        val NB_SERVICE_UUID: UUID = UUID.fromString("0000e0ff-0000-1000-8000-00805f9b34fb")
        val NB_NOTIFY_UUID: UUID = UUID.fromString("0000e001-0000-1000-8000-00805f9b34fb")
        val NB_WRITE_UUID: UUID = UUID.fromString("0000e002-0000-1000-8000-00805f9b34fb")

        val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var statusText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var filterButton: Button

    // Telemetrie & Dashboard UI Elements
    private lateinit var dashboardView: LinearLayout
    private lateinit var connectedDeviceTitle: TextView
    private lateinit var safetyGuardStatus: TextView
    private lateinit var batteryText: TextView
    private lateinit var speedText: TextView
    private lateinit var firmwareInfoText: TextView
    private lateinit var telemetryLogText: TextView
    private lateinit var germanManeuverBtn: Button
    private lateinit var forceOverrideBtn: Button
    private lateinit var panicButton: Button
    private lateinit var disconnectButton: Button

    private var isScanning = false
    private var showOnlyScooters = true
    private val foundDevices = linkedMapOf<String, ScooterDevice>()
    private val handler = Handler(Looper.getMainLooper())
    private var currentGatt: BluetoothGatt? = null

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isGermanManeuverActive = false
    private var isForceOverrideEnabled = false

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
                statusText.text = "Berechtigungen fehlen. BLE-Scan nicht möglich."
            }
        }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { res ->
                val device = res.device
                val address = device.address
                val name = try {
                    device.name ?: res.scanRecord?.deviceName ?: "Unbekanntes BLE-Gerät"
                } catch (e: SecurityException) {
                    "Unbekanntes BLE-Gerät"
                }
                val rssi = res.rssi

                val knownScooterKeywords = listOf(
                    "scooter", "ninebot", "xiaomi", "navee", "m365",
                    "segway", "soflow", "inmotion", "e-scooter", "mi", "kukirin"
                )
                val isCandidate = knownScooterKeywords.any { name.lowercase().contains(it) }

                foundDevices[address] = ScooterDevice(device, name, address, rssi, isCandidate)
                updateDeviceList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "BLE-Scan Fehler Code: $errorCode"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val name = try {
                        gatt?.device?.name ?: gatt?.device?.address ?: "E-Scooter"
                    } catch (e: SecurityException) {
                        "E-Scooter"
                    }
                    statusText.text = "Verbunden mit $name! Analysiere Services..."
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    statusText.text = "Verbindung getrennt."
                    showScanUI()
                    currentGatt?.close()
                    currentGatt = null
                    writeCharacteristic = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                    setupScooterCommunication(gatt)
                } else {
                    statusText.text = "Dienst-Erkennung fehlgeschlagen."
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val data = characteristic?.value ?: return
            parseScooterTelemetry(data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
        } catch (e: Exception) {
            bluetoothAdapter = null
        }

        if (bluetoothAdapter == null) {
            showBluetoothUnavailableScreen()
            return
        }

        buildUserInterface()
        statusText.text = "Bereit. Starte den Scan nahe deines E-Scooters."
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
            setPadding(0, 0, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "Tuning, Telemetrie & Safety Guard"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 16, 0, 16)
        }

        scanButton = Button(this).apply {
            text = "BLE-SCAN STARTEN"
            setOnClickListener {
                requestPermissionsAndScan()
            }
        }

        val filterHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 10)
        }

        val listTitle = TextView(this).apply {
            text = "GEFUNDENE GERÄTE"
            textSize = 15f
            setTextColor(android.graphics.Color.rgb(0, 230, 255))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        filterButton = Button(this).apply {
            textSize = 11f
            updateFilterButtonText()
            setOnClickListener {
                showOnlyScooters = !showOnlyScooters
                updateFilterButtonText()
                updateDeviceList()
            }
        }

        filterHeaderLayout.addView(listTitle)
        filterHeaderLayout.addView(filterButton)

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

        // --- DASHBOARD LAYOUT ---
        dashboardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 16, 0, 16)
        }

        connectedDeviceTitle = TextView(this).apply {
            textSize = 20f
            setTextColor(android.graphics.Color.rgb(0, 255, 200))
            setPadding(0, 0, 0, 10)
        }

        safetyGuardStatus = TextView(this).apply {
            text = "🛡️ Safety Guard: Prüfe Firmware-Kompatibilität..."
            textSize = 14f
            setTextColor(android.graphics.Color.YELLOW)
            setPadding(0, 0, 0, 16)
        }

        speedText = TextView(this).apply {
            text = "Geschwindigkeit: 0.0 km/h"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
        }

        batteryText = TextView(this).apply {
            text = "Akkustand: -- %"
            textSize = 16f
            setTextColor(android.graphics.Color.YELLOW)
            setPadding(0, 8, 0, 8)
        }

        firmwareInfoText = TextView(this).apply {
            text = "Protokoll: Initialisiere BLE-Verbindung..."
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 0, 0, 12)
        }

        telemetryLogText = TextView(this).apply {
            text = "Telemetrie-Kanal bereit."
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 0, 0, 16)
        }

        germanManeuverBtn = Button(this).apply {
            text = "⚡ GERMAN MANEUVER (RAM TUNING)"
            setBackgroundColor(android.graphics.Color.rgb(0, 150, 200))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                toggleGermanManeuver()
            }
        }

        forceOverrideBtn = Button(this).apply {
            text = "⚠️ FORCE FLASH / OVERRIDE (AUF EIGENE GEFAHR)"
            setBackgroundColor(android.graphics.Color.rgb(180, 100, 0))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                showForceOverrideWarningDialog()
            }
        }

        panicButton = Button(this).apply {
            text = "🚨 POLICE MODE / PANIK-BUTTON"
            setBackgroundColor(android.graphics.Color.rgb(200, 30, 30))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                triggerPoliceMode()
            }
        }

        disconnectButton = Button(this).apply {
            text = "VERBINDUNG TRENNEN"
            setOnClickListener {
                disconnectGatt()
            }
        }

        dashboardView.addView(connectedDeviceTitle)
        dashboardView.addView(safetyGuardStatus)
        dashboardView.addView(speedText)
        dashboardView.addView(batteryText)
        dashboardView.addView(firmwareInfoText)
        dashboardView.addView(telemetryLogText)

        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 10
        }

        dashboardView.addView(germanManeuverBtn, btnParams)
        dashboardView.addView(forceOverrideBtn, btnParams)
        dashboardView.addView(panicButton, btnParams)
        dashboardView.addView(disconnectButton, btnParams)

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
        root.addView(filterHeaderLayout)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(dashboardView)

        setContentView(root)
    }

    @SuppressLint("MissingPermission")
    private fun setupScooterCommunication(gatt: BluetoothGatt) {
        var notifyChar: BluetoothGattCharacteristic? = null

        val service = gatt.getService(UART_SERVICE_UUID) ?: gatt.getService(NB_SERVICE_UUID)
        if (service != null) {
            writeCharacteristic = service.getCharacteristic(UART_TX_UUID) ?: service.getCharacteristic(NB_WRITE_UUID)
            notifyChar = service.getCharacteristic(UART_RX_UUID) ?: service.getCharacteristic(NB_NOTIFY_UUID)
        } else {
            for (s in gatt.services) {
                for (c in s.characteristics) {
                    if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        notifyChar = c
                    }
                    if ((c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        writeCharacteristic = c
                    }
                }
            }
        }

        if (notifyChar != null) {
            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        val deviceName = try {
            gatt.device.name ?: "E-Scooter"
        } catch (e: SecurityException) {
            "E-Scooter"
        }
        showDashboardUI(deviceName)

        safetyGuardStatus.text = "🛡️ Safety Guard: Aktiv (Warnhinweise aktiv | Override erlaubt)"
        safetyGuardStatus.setTextColor(android.graphics.Color.GREEN)
        firmwareInfoText.text = "GATT-Verbindung hergestellt. Telemetrie aktiv."
    }

    private fun parseScooterTelemetry(data: ByteArray) {
        runOnUiThread {
            val hexString = data.joinToString("") { "%02X ".format(it) }
            telemetryLogText.text = "Empfangen: $hexString"
        }
    }

    private fun toggleGermanManeuver() {
        if (!isGermanManeuverActive) {
            isGermanManeuverActive = true
            germanManeuverBtn.text = "⚡ GERMAN MANEUVER: AKTIV (30 km/h RAM)"
            germanManeuverBtn.setBackgroundColor(android.graphics.Color.rgb(0, 200, 100))
            statusText.text = "German Maneuver im RAM aktiviert! (30 km/h temporär)"
        } else {
            isGermanManeuverActive = false
            germanManeuverBtn.text = "⚡ GERMAN MANEUVER (RAM TUNING)"
            germanManeuverBtn.setBackgroundColor(android.graphics.Color.rgb(0, 150, 200))
            statusText.text = "German Maneuver deaktiviert. (Zurück auf 20 km/h)"
        }
    }

    private fun showForceOverrideWarningDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("⚠️ WARNUNG & RECHTSHINWEIS")
                .setMessage("Möchtest du Hersteller-Sperren oder Sicherheits-Warnungen übergehen (Force Flash / Override)?\n\n" +
                        "• Das Flashen von ungeeigneter Firmware kann den Controller (DRV/BLE) dauerhaft beschädigen (Bricking).\n" +
                        "• Du handelst zu 100 % auf eigene Verantwortung und eigenes Risiko.\n\n" +
                        "Möchtest du den Override-Modus aktivieren und Befehle erzwingen?")
                .setPositiveButton("JA, AUF EIGENE GEFAHR") { dialog, _ ->
                    isForceOverrideEnabled = true
                    forceOverrideBtn.text = "🔥 OVERRIDE AKTIV: SPERREN AUF EIGENE GEFAHR FREIGESCHALTET"
                    forceOverrideBtn.setBackgroundColor(android.graphics.Color.rgb(220, 50, 0))
                    statusText.text = "⚠️ Force Flash / Override Modus vom Benutzer aktiviert!"
                    dialog.dismiss()
                }
                .setNegativeButton("ABBRECHEN") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } catch (e: Exception) {
            statusText.text = "Fehler beim Öffnen des Dialogs: ${e.message}"
        }
    }

    private fun triggerPoliceMode() {
        isGermanManeuverActive = false
        isForceOverrideEnabled = false
        germanManeuverBtn.text = "⚡ GERMAN MANEUVER (RAM TUNING)"
        germanManeuverBtn.setBackgroundColor(android.graphics.Color.rgb(0, 150, 200))
        forceOverrideBtn.text = "⚠️ FORCE FLASH / OVERRIDE (AUF EIGENE GEFAHR)"
        forceOverrideBtn.setBackgroundColor(android.graphics.Color.rgb(180, 100, 0))
        statusText.text = "🚨 POLICE MODE TRIPPED: RAM blitzschnell gelöscht! Scooter legal (20 km/h)."
    }

    private fun updateFilterButtonText() {
        filterButton.text = if (showOnlyScooters) "FILTER: NUR SCOOTER" else "FILTER: ALLE GERÄTE"
    }

    private fun showDashboardUI(deviceName: String) {
        scanButton.visibility = View.GONE
        deviceList.visibility = View.GONE
        filterButton.visibility = View.GONE
        dashboardView.visibility = View.VISIBLE

        connectedDeviceTitle.text = "🛴 $deviceName"
        statusText.text = "Erfolgreich gekoppelt!"
    }

    private fun showScanUI() {
        scanButton.visibility = View.VISIBLE
        deviceList.visibility = View.VISIBLE
        filterButton.visibility = View.VISIBLE
        dashboardView.visibility = View.GONE
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        try {
            currentGatt?.disconnect()
        } catch (e: Exception) {}
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
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Bluetooth ist ausgeschaltet. Bitte aktivieren."
            return
        }

        if (!isLocationEnabled()) {
            statusText.text = "Bitte Standort / GPS aktivieren!"
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

        try {
            currentGatt?.disconnect()
            currentGatt?.close()
        } catch (e: Exception) {}
        currentGatt = null

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            statusText.text = "BLE-Scanner nicht verfügbar."
            return
        }

        foundDevices.clear()
        updateDeviceList()

        isScanning = true
        scanButton.isEnabled = false
        statusText.text = "Suche läuft ..."

        scanner.startScan(leScanCallback)

        handler.postDelayed({
            if (isScanning) {
                try {
                    scanner.stopScan(leScanCallback)
                } catch (e: Exception) {}
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = if (foundDevices.isEmpty()) {
                    "Keine Geräte in Reichweite gefunden."
                } else {
                    "${foundDevices.size} Gerät(e) erkannt."
                }
            }
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(scooter: ScooterDevice) {
        if (isScanning) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (e: Exception) {}
            isScanning = false
            scanButton.isEnabled = true
        }

        statusText.text = "Verbinde mit ${scooter.name} (${scooter.address}) ..."
        try {
            currentGatt?.close()
        } catch (e: Exception) {}
        currentGatt = scooter.device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDeviceList() {
        deviceList.removeAllViews()

        val filteredList = foundDevices.values.filter {
            if (showOnlyScooters) it.isScooterCandidate else true
        }.sortedWith(
            compareByDescending<ScooterDevice> { it.isScooterCandidate }
                .thenByDescending { it.rssi }
        )

        if (filteredList.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = if (showOnlyScooters && foundDevices.isNotEmpty()) {
                    "Kein E-Scooter erkannt. Schalte deinen Scooter ein oder tippe oben auf 'FILTER: ALLE GERÄTE'."
                } else {
                    "Suche läuft..."
                }
                textSize = 14f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 8, 0, 8)
            }
            deviceList.addView(emptyText)
            return
        }

        filteredList.forEach { scooter ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                setBackgroundColor(
                    if (scooter.isScooterCandidate)
                        android.graphics.Color.rgb(10, 50, 40)
                    else
                        android.graphics.Color.rgb(28, 28, 40)
                )
            }

            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val nameText = TextView(this).apply {
                text = if (scooter.isScooterCandidate) "🛴 ${scooter.name}" else scooter.name
                textSize = 16f
                setTextColor(
                    if (scooter.isScooterCandidate)
                        android.graphics.Color.rgb(0, 255, 200)
                    else
                        android.graphics.Color.WHITE
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val rssiText = TextView(this).apply {
                text = "${scooter.rssi} dBm"
                textSize = 13f
                setTextColor(
                    if (scooter.rssi > -70) android.graphics.Color.GREEN
                    else if (scooter.rssi > -85) android.graphics.Color.YELLOW
                    else android.graphics.Color.RED
                )
            }

            headerLayout.addView(nameText)
            headerLayout.addView(rssiText)

            val addressText = TextView(this).apply {
                text = "MAC: ${scooter.address}"
                textSize = 13f
                setTextColor(android.graphics.Color.rgb(0, 230, 255))
                setPadding(0, 6, 0, 0)
            }

            val connectBtn = Button(this).apply {
                text = "VERBINDEN"
                textSize = 12f
                setOnClickListener {
                    connectToDevice(scooter)
                }
            }

            item.addView(headerLayout)
            item.addView(addressText)
            item.addView(
                connectBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                }
            )

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 14
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
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (e: Exception) {}
        }
        try {
            currentGatt?.close()
        } catch (e: Exception) {}
    }
}
