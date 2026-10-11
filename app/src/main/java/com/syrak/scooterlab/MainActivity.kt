package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

enum class DashboardTab { INFO, TOOLS, DASHBOARD, FLASH, CONFIG }

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

    // Dashboard State
    private var currentTab = DashboardTab.DASHBOARD
    private var connectedDeviceName = ""
    private var connectedDeviceAddress = ""
    private var isDemoMode = false

    private lateinit var tabContentContainer: FrameLayout

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
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

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    // --- LOADING & DISCLAIMER SCREENS ---

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
            "Age Restriction & Competence" to "This application and its companion firmware utilities are intended solely for adult users of legal age.",
            "Personal Safety & Protective Gear" to "Always wear certified protective equipment (helmet, pads) and thoroughly inspect brakes and tire pressure.",
            "Hardware Risks & Component Damage" to "Modifying firmware or speed limiters carries technical risks. Parameter misconfiguration can damage controllers.",
            "Legal Compliance & Road Regulations" to "Altering speed limits may violate local road laws (e.g., eKFV in Germany). Modified vehicles may not be used on public roads.",
            "As-Is Disclaimer & User Responsibility" to "The software is provided strictly on an 'as-is' basis without warranties."
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
                agree.setOnClickListener {
                    prefs.edit().putBoolean("disclaimers_accepted", true).apply()
                    showScanScreen()
                }
            }
        }.start()
    }

    // --- BLE SCAN SCREEN ---

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

        val demoBtn = Button(this).apply {
            text = "Demo"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(Color.rgb(40, 30, 20))
            setTextColor(Color.rgb(255, 165, 0))
            setOnClickListener { loadDemoScooters() }
        }
        topHeader.addView(demoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))

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

        scroll.addView(content)
        rootContainer.addView(scroll)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
    }

    private fun loadDemoScooters() {
        foundDevices.clear()
        val demoList = listOf(
            ScooterDevice(null, "Demo Ninebot Max G2", "D2:E4:5A:11:22:33", -45, true, true),
            ScooterDevice(null, "Demo Ninebot Max G30", "5C:2A:1B:7F:12:34", -52, true, true),
            ScooterDevice(null, "Demo Xiaomi Pro 2", "11:22:33:44:55:66", -68, true, true)
        )
        demoList.forEach { foundDevices[it.address] = it }
        updateDeviceList()
        statusText.text = "Demo-Modus aktiv: ${foundDevices.size} Testmodelle geladen."
    }

    // --- HAUPTDASHBOARD MIT 5 TABS & BOTTOM NAVIGATION ---

    private fun openMainInterface(deviceName: String, deviceAddress: String, isDemo: Boolean) {
        connectedDeviceName = deviceName
        connectedDeviceAddress = deviceAddress
        isDemoMode = isDemo
        currentTab = DashboardTab.DASHBOARD

        renderMainLayout()
    }

    private fun renderMainLayout() {
        rootContainer.removeAllViews()

        // 1. Top Bar (Demo Banner & Disconnect)
        val topBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(40, 32, 50))
            gravity = Gravity.CENTER_VERTICAL
        }
        val bannerText = makeText("🧪 DEMO MODE — $connectedDeviceName", 13f, Color.rgb(220, 180, 255), true)
        val exitDemoBtn = TextView(this).apply {
            text = "Exit demo"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.END
            setOnClickListener { showScanScreen() }
        }
        topBanner.addView(bannerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBanner.addView(exitDemoBtn)
        rootContainer.addView(topBanner)

        // 2. Dynamic Content Area
        tabContentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootContainer.addView(tabContentContainer)

        // Render current active tab content
        updateTabContent()

        // 3. Bottom Navigation Bar with Curved/Floating Center Dashboard Button
        val bottomNav = buildBottomNavigationBar()
        rootContainer.addView(bottomNav)
    }

    private fun updateTabContent() {
        tabContentContainer.removeAllViews()

        val view: View = when (currentTab) {
            DashboardTab.INFO -> buildInfoTab()
            DashboardTab.TOOLS -> buildToolsTab()
            DashboardTab.DASHBOARD -> buildDashboardTab()
            DashboardTab.FLASH -> buildFlashTab()
            DashboardTab.CONFIG -> buildConfigTab()
        }

        tabContentContainer.addView(view)
    }

    // --- TAB 1: INFO ---
    private fun buildInfoTab(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(12, 14, 18))
        }

        val cardInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(Color.rgb(20, 24, 31), 16f)
        }
        cardInfo.addView(makeText(connectedDeviceName, 20f, Color.WHITE, true))
        cardInfo.addView(makeText("Submodel: Ninebot Max G2 / Global", 13f, Color.rgb(0, 180, 255)).apply { setPadding(0, dp(4), 0, dp(16)) })

        fun addInfoRow(label: String, valStr: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(makeText(label, 13f, Color.rgb(150, 160, 175)))
            row.addView(makeText(valStr, 14f, Color.WHITE, true).apply { gravity = Gravity.END })
            row.getChildAt(1).layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            cardInfo.addView(row)
            cardInfo.addView(View(this).apply {
                setBackgroundColor(Color.rgb(30, 36, 46))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
        }

        addInfoRow("BLE", "1.2.3")
        addInfoRow("DRV", "1.2.3")
        addInfoRow("BMS", "1.2.3")
        addInfoRow("Serial number", "01GXC1234C1234")
        addInfoRow("Region", "US 35 km/h ✏️")
        addInfoRow("Total mileage", "1,284 km")

        layout.addView(cardInfo)
        scroll.addView(layout)
        return scroll
    }

    // --- TAB 2: TOOLS ---
    private fun buildToolsTab(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(12, 14, 18))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(20, 24, 31), 16f)
        }

        // Max Speed Slider
        card.addView(makeText("Max speed", 15f, Color.WHITE, true))
        val speedVal = makeText("40 km/h", 14f, Color.rgb(0, 180, 255), true).apply { gravity = Gravity.END }
        card.addView(speedVal)

        val speedSeekBar = SeekBar(this).apply {
            max = 50 // 15 to 65
            progress = 25 // 40 km/h
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, v: Int, p2: Boolean) {
                    speedVal.text = "${15 + v} km/h"
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        card.addView(speedSeekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(8), 0, dp(16))
        })

        // Zero-Start Speed Slider
        card.addView(makeText("Start speed (Zero-Start)", 15f, Color.WHITE, true))
        val startSpeedVal = makeText("0 km/h (Instant Throttle)", 14f, Color.rgb(0, 210, 130), true).apply { gravity = Gravity.END }
        card.addView(startSpeedVal)

        val startSeekBar = SeekBar(this).apply {
            max = 5
            progress = 0 // 0 km/h = Zero-Start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, v: Int, p2: Boolean) {
                    if (v == 0) {
                        startSpeedVal.text = "0 km/h (Zero-Start Active)"
                    } else {
                        startSpeedVal.text = "$v km/h"
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        card.addView(startSeekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(8), 0, dp(16))
        })

        layout.addView(card)
        scroll.addView(layout)
        return scroll
    }

    // --- TAB 3: DASHBOARD (CENTER) ---
    private fun buildDashboardTab(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(12, 14, 18))
        }

        // Circular Speedometer Simulation View
        val speedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(32), dp(16), dp(24))
            background = rounded(Color.rgb(20, 24, 31), 20f)
        }

        speedCard.addView(makeText("0", 64f, Color.WHITE, true))
        speedCard.addView(makeText("km/h", 14f, Color.rgb(140, 150, 165), true))

        layout.addView(speedCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(16))
        })

        // Grid Metrics
        fun createGridCard(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(20, 24, 31), 12f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            addView(makeText(label, 11f, Color.rgb(130, 140, 155)))
            addView(makeText(value, 18f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) })
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            addView(createGridCard("Current", "0 A"))
            addView(createGridCard("Voltage", "40.6 V"))
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            addView(createGridCard("Trip mileage", "0 km"))
            addView(createGridCard("Trip time", "0 s"))
        }

        layout.addView(row1)
        layout.addView(row2)

        scroll.addView(layout)
        return scroll
    }

    // --- TAB 4: FLASH ---
    private fun buildFlashTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(12, 14, 18))
        }

        layout.addView(makeText("⚡ Ready to Flash", 22f, Color.WHITE, true))
        layout.addView(makeText("Select a firmware package below to get started.", 13f, Color.rgb(150, 160, 175)).apply {
            setPadding(0, dp(8), 0, dp(24))
        })

        val btnZip = Button(this).apply { text = "📁 Load ZIP" }
        val btnRepo = Button(this).apply { text = "☁️ From Repo" }

        layout.addView(btnZip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, 0, 0, dp(12)) })
        layout.addView(btnRepo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        return layout
    }

    // --- TAB 5: CONFIG ---
    private fun buildConfigTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(12, 14, 18))
        }

        layout.addView(makeText("⚙️ SHFW not detected", 20f, Color.WHITE, true))
        layout.addView(makeText("Install SHFW from Flash tab to configure profiles and system settings.", 13f, Color.rgb(150, 160, 175)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        })

        return layout
    }

    // --- BOTTOM NAVIGATION BAR (WITH BLUE CENTER DASHBOARD BUTTON) ---

    private fun buildBottomNavigationBar(): View {
        val navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(18, 22, 28))
            setPadding(0, dp(6), 0, dp(6))
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 5f
        }

        fun createNavItem(label: String, iconStr: String, tab: DashboardTab, isCenter: Boolean = false): View {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    currentTab = tab
                    updateTabContent()
                    renderMainLayout()
                }
            }

            if (isCenter) {
                val centerCircle = FrameLayout(this).apply {
                    background = rounded(Color.rgb(120, 190, 255), 28f) // Highlighted Blue Circle
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                        gravity = Gravity.CENTER
                    }
                }
                val iconView = TextView(this).apply {
                    text = "⏱"
                    textSize = 22f
                    gravity = Gravity.CENTER
                }
                centerCircle.addView(iconView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                item.addView(centerCircle)
            } else {
                val isSelected = currentTab == tab
                val iconView = makeText(iconStr, 18f, if (isSelected) Color.rgb(0, 180, 255) else Color.rgb(130, 140, 155))
                val labelView = makeText(label, 11f, if (isSelected) Color.rgb(0, 180, 255) else Color.rgb(130, 140, 155), isSelected)

                item.addView(iconView)
                item.addView(labelView)
            }

            return item
        }

        navContainer.addView(createNavItem("Info", "田", DashboardTab.INFO))
        navContainer.addView(createNavItem("Tools", "🔧", DashboardTab.TOOLS))
        navContainer.addView(createNavItem("Dashboard", "⏱", DashboardTab.DASHBOARD, isCenter = true))
        navContainer.addView(createNavItem("Flash", "⚡", DashboardTab.FLASH))
        navContainer.addView(createNavItem("Config", "🎛", DashboardTab.CONFIG))

        return navContainer
    }

    // --- BLE HELPERS ---

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
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

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
                    openMainInterface(item.name, item.address, item.isDemo)
                }
            }
            card.addView(makeText(item.name, 15f, Color.WHITE, true))
            card.addView(makeText(if (item.isDemo) "Demo-Gerät • Tippen zum Verbinden" else "Signal: ${item.rssi} dBm • ${item.address}", 12f, Color.rgb(145, 155, 170)))
            deviceListContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(10))
            })
        }
    }
}
