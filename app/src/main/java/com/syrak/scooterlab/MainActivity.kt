package com.syrak.scooterlab

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            statusText.text = "Berechtigungen erteilt! Bluetooth ist einsatzbereit."
        } else {
            statusText.text = "Bitte erteile die Bluetooth- & Standort-Berechtigungen."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sauberes, dynamisches UI ohne externe XML-Abhängigkeiten
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val titleText = TextView(this).apply {
            text = "🛴 Syrak ScooterLab"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }

        statusText = TextView(this).apply {
            text = "Status: Bereit zur Initialisierung..."
            textSize = 15f
            setPadding(0, 0, 0, 30)
        }

        scanButton = Button(this).apply {
            text = "Bluetooth-Berechtigungen prüfen"
            setOnClickListener {
                checkAndRequestPermissions()
            }
        }

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(scanButton)

        setContentView(rootLayout)

        // Bluetooth Manager & Adapter initialisieren
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            statusText.text = "Fehler: Bluetooth wird von diesem Gerät nicht unterstützt."
            scanButton.isEnabled = false
        } else {
            statusText.text = "Bluetooth-Adapter erfolgreich geladen."
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val needsPermission = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            permissionLauncher.launch(permissions)
        } else {
            statusText.text = "Alle erforderlichen Berechtigungen sind bereits aktiv!"
        }
    }
}
