
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
    private var loadingRunnable: Runnable? = null

    private var isScanning = false
    private val foundDevices = linkedMapOf<String, ScooterDevice>()
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListContainer: LinearLayout

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            statusText.text = "Bluetooth-Berechtigungen erteilt. Scooter einschalten."
            startBleScan()
        } else {
            statusText.text = "Bluetooth-Berechtigungen werden zum Suchen benötigt."
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
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    device.name ?: result.scanRecord?.deviceName ?: "Unbekanntes Gerät"
                } else {
                    result.scanRecord?.deviceName ?: "Unbekanntes Gerät"
                }
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: "Unbekanntes Gerät"
            }

            val keywords = listOf(
                "scooter", "ninebot", "xiaomi", "navee",
                "m365", "segway", "soflow"
            )
            val candidate = keywords.any { name.contains(it, ignoreCase = true) }

            foundDevices[address] = ScooterDevice(
                device = device,
                name = name,
                address = address,
                rssi = result.rssi,
                isScooterCandidate = candidate
            )
            updateDeviceList()
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            runOnUiThread {
                scanButton.isEnabled = true
                statusText.text = "Bluetooth-Suche fehlgeschlagen (Code $errorCode)."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(12, 14, 18)
        window.navigationBarColor = Color.rgb(12, 14, 18)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 14, 18))
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

        if (isScanning) {
            try {
                if (hasBluetoothConnectPermission()) {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
                }
            } catch (_: Exception) {
                // Activity is closing.
            }
        }

        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
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

        val title = makeText("SYRAK SCOOTERLAB", 22f, Color.WHITE, true)
        title.gravity = Gravity.CENTER

        val subtitle = makeText("SCOOTER DIAGNOSTICS", 11f, Color.rgb(0, 200, 120), true)
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(0, dp(8), 0, 0)

        val progress = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
        }

        val status = makeText("Anwendung wird gestartet …", 13f, Color.LTGRAY)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(18), 0, 0)

        layout.addView(title)
        layout.addView(subtitle)

        layout.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(4)
        ).apply {
            setMargins(dp(24), dp(32), dp(24), 0)
        })

        layout.addView(status)
        rootContainer.addView(layout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val steps = listOf(
            "Anwendung wird vorbereitet …",
            "Bluetooth wird überprüft …",
            "Bereit."
        )
        var index = 0

        loadingRunnable = object : Runnable {
            override fun run() {
                if (index < steps.size) {
                    status.text = steps[index++]
                    handler.postDelayed(this, 600)
                } else {
                    showCriticalDisclaimer()
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

        content.addView(makeText(
            "Wichtige Sicherheitshinweise",
            22f, Color.WHITE, true
        ).apply {
            setPadding(0, 0, 0, dp(20))
        })

        val sections = listOf(
            "Mindestalter" to
                "Die App richtet sich an volljährige Nutzer. Minderjährige sollten Änderungen an Fahrzeugen nur mit Zustimmung und Unterstützung eines verantwortlichen Erwachsenen durchführen.",

            "Sicherheit" to
                "Trage geeignete Schutzausrüstung und überprüfe Bremsen, Reifen und Lenkung regelmäßig. Fahre niemals abgelenkt oder unter Alkohol- oder Drogeneinfluss.",

            "Mögliche Schäden" to
                "Diagnose- oder Firmwareänderungen können zu Fehlfunktionen, Schäden am Controller oder an der Batterie führen. Verwende nur Funktionen, deren Wirkung du verstehst.",

            "Gesetze und Versicherung" to
                "Prüfe vor Änderungen die geltenden Vorschriften und Versicherungsbedingungen. Eine technische Änderung kann dazu führen, dass ein Fahrzeug nicht mehr im öffentlichen Verkehr verwendet werden darf.",

            "Keine Herstellerzugehörigkeit" to
                "Syrak ScooterLab ist ein unabhängiges Projekt und wird nicht von Segway-Ninebot, Xiaomi, Navee oder anderen Fahrzeugherstellern unterstützt oder betrieben.",

            "Keine Garantie" to
                "Die App wird ohne Garantie für Verfügbarkeit oder Eignung für einen bestimmten Zweck bereitgestellt. Prüfe alle angezeigten Informationen selbst.",

            "Verantwortung" to
                "Beende die App, wenn du diese Hinweise nicht verstanden hast oder die möglichen Risiken nicht akzeptierst."
        )

        sections.forEach { (heading, body) ->
            content.addView(makeText(
                heading, 14f, Color.rgb(230, 70, 70), true
            ).apply {
                setPadding(0, 0, 0, dp(4))
            })

            content.addView(makeText(
                body, 13f, Color.rgb(185, 190, 200)
            ).apply {
                setPadding(0, 0, 0, dp(16))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }

        scroll.addView(content)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }

        val agree = Button(this).apply {
            text = "Ich habe die Hinweise gelesen (20)"
            isEnabled = false
        }

        val exit = Button(this).apply {
            text = "App beenden"
            setOnClickListener { finishAffinity() }
        }

        footer.addView(agree)
        footer.addView(exit)
        rootContainer.addView(footer)

        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(20000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                agree.text = "Ich habe die Hinweise gelesen (${millisUntilFinished / 1000})"
            }

            override fun onFinish() {
                agree.text = "Weiter"
                agree.isEnabled = true
                agree.setOnClickListener { showCountryDisclaimer() }
            }
        }.start()
    }

    private fun showCountryDisclaimer() {
        activeTimer?.cancel()
        rootContainer.removeAllViews()

        val country = Locale.getDefault()
            .getDisplayCountry(Locale.GERMAN)
            .ifBlank { "deinem Land" }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        content.addView(makeText(
            "Rechtlicher Hinweis", 22f, Color.WHITE, true
        ).apply {
            setPadding(0, 0, 0, dp(16))
        })

        val body = if (Locale.getDefault().country.equals("DE", true)) {
            "In Deutschland gelten für Elektrokleinstfahrzeuge besondere technische und rechtliche Anforderungen.\n\n" +
                "• Veränderungen können die Betriebserlaubnis beeinflussen.\n\n" +
                "• Ein Fahrzeug darf im öffentlichen Verkehr nur eingesetzt werden, wenn es die geltenden Vorschriften erfüllt.\n\n" +
                "• Prüfe mögliche Auswirkungen auf Versicherungsschutz und Zulassung vor jeder Änderung.\n\n" +
                "• Nutze Diagnose- und Testfunktionen verantwortungsvoll und beachte die geltenden Gesetze."
        } else {
            "Informiere dich vor der Nutzung über die Vorschriften für Elektrokleinstfahrzeuge in $country.\n\n" +
                "Technische Änderungen können Auswirkungen auf Zulassung, zulässige Nutzung und Versicherungsschutz haben."
        }

        content.addView(makeText(
            body, 14f, Color.rgb(185, 190, 200)
        ).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        scroll.addView(content)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }

        val accept = Button(this).apply {
            text = "Verstanden (20)"
            isEnabled = false
        }
        val exit = Button(this).apply {
            text = "App beenden"
            setOnClickListener { finishAffinity() }
        }

        footer.addView(accept)
        footer.addView(exit)
        rootContainer.addView(footer)

        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(20000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                accept.text = "Verstanden (${millisUntilFinished / 1000})"
            }

            override fun onFinish() {
                accept.text = "Akzeptieren und fortfahren"
                accept.isEnabled = true
                accept.setOnClickListener { showMainDashboard() }
            }
        }.start()
    }

    private fun showMainDashboard() {
        rootContainer.removeAllViews()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
        }

        content.addView(makeText(
            "Syrak ScooterLab", 24f, Color.WHITE, true
        ))
        content.addView(makeText(
            "Scooter auswählen und verbinden", 14f, Color.rgb(145, 155, 170)
        ).apply {
            setPadding(0, dp(6), 0, dp(24))
        })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.rgb(20, 24, 31))
        }

        panel.addView(makeText(
            "Bluetooth-Verbindung", 17f, Color.WHITE, true
        ).apply { gravity = Gravity.CENTER })

        statusText = makeText(
            "Scooter einschalten und Suche starten.", 13f,
            Color.rgb(185, 190, 200)
        ).apply {
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
            "Es werden Bluetooth-Geräte in der Nähe angezeigt. " +
                "Ein gefundener Gerätename bestätigt noch keine Kompatibilität.",
            12f, Color.rgb(125, 135, 150)
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

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!checkPermissions()) return
        if (isScanning) return

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Bitte Bluetooth in den Geräteeinstellungen einschalten."
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            statusText.text = "Bluetooth-Suche ist nicht verfügbar."
            return
        }

        foundDevices.clear()
        updateDeviceList()
        isScanning = true
        scanButton.isEnabled = false
        statusText.text = "Suche läuft …"

        try {
            scanner.startScan(leScanCallback)
        } catch (_: SecurityException) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "Keine Berechtigung für die Bluetooth-Suche."
            return
        } catch (_: Exception) {
            isScanning = false
            scanButton.isEnabled = true
            statusText.text = "Bluetooth-Suche konnte nicht gestartet werden."
            return
        }

        handler.postDelayed({
            if (isScanning) {
                try {
                    scanner.stopScan(leScanCallback)
                } catch (_: Exception) {
                    // Scanner may already be stopped.
                }
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = "Suche beendet. Geräte gefunden: ${foundDevices.size}"
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
                setBackgroundColor(
                    if (item.isScooterCandidate) Color.rgb(25, 40, 32)
                    else Color.rgb(20, 24, 31)
                )
            }

            card.addView(makeText(
                item.name, 15f, Color.WHITE, true
            ))

            card.addView(makeText(
                "Signal: ${item.rssi} dBm",
                12f, Color.rgb(145, 155, 170)
            ).apply {
                setPadding(0, dp(5), 0, 0)
            })

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(10))
            }

            deviceListContainer.addView(card, params)
        }
    }
}
