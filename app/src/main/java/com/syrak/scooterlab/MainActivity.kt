package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.syrak.scooterlab.core.ble.BleSessionService
import com.syrak.scooterlab.core.ble.ScooterBrand
import com.syrak.scooterlab.diagnostics.DataStatus
import com.syrak.scooterlab.diagnostics.DiagnosticEventLog
import com.syrak.scooterlab.diagnostics.DiagnosticSeverity
import com.syrak.scooterlab.diagnostics.DiagnosticValue
import com.syrak.scooterlab.diagnostics.DiagnosticsBuilder
import com.syrak.scooterlab.diagnostics.DiagnosticsInput
import com.syrak.scooterlab.diagnostics.DiagnosticsReport
import com.syrak.scooterlab.diagnostics.ConnectionRecord
import com.syrak.scooterlab.diagnostics.FrameDirection
import com.syrak.scooterlab.diagnostics.HardwareErrorKind
import com.syrak.scooterlab.diagnostics.HardwareTestCollector
import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.diagnostics.HardwareTestReport
import com.syrak.scooterlab.diagnostics.ScanRecord
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/* ==========================================================================
 *  SYRAK SCOOTERLAB  —  MainActivity.kt  (v7 "Modular Protocol Engine")
 *  ----------------------------------------------------------------------------
 *  Diese Activity ist jetzt ein SCHLANKER UI-CONTROLLER:
 *    • KEINE herstellerspezifischen Byte-Operationen mehr im UI.
 *    • Jeder Tuning-Befehl wird vom aktiven [ScooterProtocolAdapter] gebaut
 *      (siehe sendFrame()/sendCommand()).
 *    • Persistenz laeuft ueber [ScooterRepository] (PBKDF2-PIN, Profile, Trip).
 *    • BLE-Mechanik + ACK-Handling liegen in [BleEngine].
 *  
 *  Die GESAMTE UI (Tabs, Trip-Computer, Dashboards, Log-Terminal, Systemleiste,
 *  Disclaimers, Scan, Flash, Config) bleibt unveraendert funktionsfaehig.
 * ==========================================================================*/
class MainActivity : AppCompatActivity(), BleEngine.Listener {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var rootContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var activeTimer: CountDownTimer? = null

    /* ------------------------------------------------------------------ */
    /*  PHASE 13: SPLASH + NICHT UMGEHBARE COUNTDOWN-GATES                */
    /* ------------------------------------------------------------------ */
    private var splashView: SplashScreenView? = null
    private var splashCancelled = false

    private enum class GateScreen { NONE, DISCLAIMER, COUNTRY }

    private var gateScreen = GateScreen.NONE
    private var gateStartElapsed = 0L
    private var gateRequiredMs = 0L
    private var gateRequiresScroll = false
    private var gateScrollOk = true
    private var gateAcceptLabel = "HINWEIS AKZEPTIEREN"
    private var gateButton: Button? = null
    private var gateStatusView: TextView? = null
    private var gateScroll: ScrollView? = null
    private var gateAction: (() -> Unit)? = null

    /** Startzeitpunkte (monoton) der beiden Pflicht-Countdowns. */
    private var disclaimerStartElapsed = 0L
    private var countryStartElapsed = 0L

    private val DISCLAIMER_COUNTDOWN_MS = 10_000L
    private val COUNTRY_COUNTDOWN_MS = 8_000L
    private val KEY_DISCLAIMER_START = "phase13_disclaimer_start"
    private val KEY_COUNTRY_START = "phase13_country_start"

    /* ------------------------------------------------------------------ */
    /*  PHASE 14 (Teil C): SPRACHAUFLÖSUNG FÜR RECHTSTEXTE                */
    /* ------------------------------------------------------------------ */
    /**
     * Sprache des allgemeinen Haftungstextes. Beim ersten Start IMMER Englisch
     * (Vorgabe), danach optional vom Nutzer auf Deutsch umschaltbar.
     */
    private var disclaimerLanguage: AppLanguage = AppLanguage.EN

    /**
     * Sprache des Länderhinweises: zuerst die Android-Systemsprache, danach
     * die Landessprache, sonst Englisch. Sprache und Land bleiben getrennt:
     * die Sprache bestimmt nur die Darstellung, das Land den Inhalt.
     */
    private fun noticeLanguage(): AppLanguage =
        LanguageResolver.resolve(Locale.getDefault().toLanguageTag(), repository.region.code)

    private var isScanning = false
    private val foundDevices = linkedMapOf<String, ScooterDevice>()
    // PHASE 12.3: Scan-Ansicht — standardmaessig nur Scooter, unbekannte
    // Geraete bleiben optional sichtbar (nicht verstecken).
    private var showAllDevices = false
    private var manufacturerFilter: String? = null
    private var scanStatusView: TextView? = null
    // PHASE 12.6: erweiterte Tools einklappbar.
    private var showAdvancedTools = false

    // Scan-UI-Throttle: Scan-Callbacks kommen teils mehrfach pro Sekunde und pro
    // Geraet. Ein vollstaendiger Listen-Rebuild je Callback fuehrt zu Ruckeln und
    // zu flackernden RSSI-Werten. Daher max. ein Rebuild alle LIST_THROTTLE_MS.
    private var lastListUpdateMs = 0L
    private var pendingListUpdate: Runnable? = null
    private val LIST_THROTTLE_MS = 350L
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListContainer: LinearLayout

    private lateinit var repository: ScooterRepository

    private var connectedDevice: ScooterDevice? = null
    private var bleEngine: BleEngine? = null
    private var currentRegion: Region = Region.WORLD
    private var detectedModel: ScooterModel = ScooterModel.UNKNOWN
    private var detectedConfidence: DetectionConfidence = DetectionConfidence.UNKNOWN
    /** PHASE 8: Hersteller-Ebene, getrennt von Modell und Sicherheit. */
    private var detectedManufacturer: ScooterManufacturer = ScooterManufacturer.UNKNOWN
    private var detectionEvidence: String = ""
    private var activeProtocol: BleProtocol = BleProtocol.NINEBOT
    private var autoReconnectEnabled = true
    /**
     * PHASE 8 (Regel 6): Tuning-Schreibbefehle sind standardmaessig GESPERRT.
     * Der Nutzer muss die Hardware-Kompatibilitaet in CONFIG bewusst
     * bestaetigen. Bewusst NICHT persistiert: die Bestaetigung gilt nur fuer
     * die laufende Sitzung und wird bei jedem App-Start zurueckgesetzt.
     */
    private var hardwareVerified = false
    private var reconnectAttempts = 0
    // ---- FIX 2: Reconnect-Backoff ---------------------------------------
    private var reconnectRunnable: Runnable? = null
    private val RECONNECT_BACKOFF_BASE_MS = 1000L
    private val RECONNECT_BACKOFF_MAX_MS = 8000L
    private val RECONNECT_MAX_ATTEMPTS = 3
    private var userInitiatedDisconnect = false
    private var engineReady = false

    private lateinit var tabContent: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private var currentTab = Tab.INFO

    private var maxSpeedKmh = 25
    private var zeroStartKmh = 0
    private var kersLevel = 1
    private var cruiseEnabled = false
    private var isLocked = false
    private var germanManeuverActive = false
    private var extendedPowerUnlocked = false
    private var motorTempGuardC = 75
    private var motorTempGuardEnabled = true

    private val ramOverlay = RamOverlay()
    private var registerSnapshot: RegisterSnapshot? = null

    private var selectedFirmware: FirmwarePackage? = null
    private var selectedFirmwareUri: Uri? = null
    private var selectedFirmwareName: String? = null
    private var lastValidation: FirmwareValidation? = null
    private var loadedFirmwareBytes: ByteArray? = null
    /** FIX 7: hartes Limit gegen ZIP-Bomben / uebergrosse Firmware-Dateien. */
    private val MAX_FIRMWARE_BYTES = FirmwareValidator.MAX_FIRMWARE_BYTES

    private var flashMode: FlashMode = FlashMode.SOFT_PATCH
    private var overspeedAlarmMuted = false

    // ---- Neo-Hacker Pro: Log-Terminal -----------------------------------
    private val logEntries = ArrayDeque<LogEntry>()
    private var logFilter: LogCategory? = null
    private var logContainer: LinearLayout? = null
    private var logScroll: ScrollView? = null

    // ---- FIX 1: UI-/Log-Batching (Main-Handler-Throttle) ----------------
    private var logRefreshScheduled = false
    private var lastLogRefresh = 0L
    private var telemetryApplyScheduled = false
    private var lastTelemetryApply = 0L
    private val UI_REFRESH_INTERVAL_MS = 150L
    private val SLIDER_DEBOUNCE_MS = 300L

    // ---- Neo-Hacker Pro: Header-Systemleiste ----------------------------
    private var sysRssiView: TextView? = null
    private var sysMtuView: TextView? = null
    private var sysQueueView: TextView? = null
    private var sysLinkView: TextView? = null
    private var linkState: BleLinkState = BleLinkState.IDLE
    private var lastRssi: Int = Int.MIN_VALUE
    /** PHASE 15: echte RSSI-Messreihe fuer die Verbindungsqualitaet. */
    private val rssiSamples = ArrayDeque<LinkSample>()
    private val MAX_RSSI_SAMPLES = 60
    private var sysBarRunnable: Runnable? = null

    // ---- Fahrmodus (Eco-Saver / Sport-Plus) -----------------------------
    private var rideMode: RideMode = RideMode.NORMAL
    private val ecoThresholdPercent = 20
    private val sportPlusPhaseCurrentA = 27

    // ---- Trip-Computer --------------------------------------------------
    private val tripData get() = repository.trip
    private var dashSoh: TextView? = null
    private var dashCycles: TextView? = null
    private var dashVmax: TextView? = null
    private var dashVavg: TextView? = null
    private var dashWh: TextView? = null
    private var cellGraphRow: LinearLayout? = null

    // ---- PHASE 15: ehrlicher Firmware-Lebenszyklus ----------------------
    /** Wurde im FLASH-Bereich bewusst "Vorschlaege" gedrueckt? */
    private var flashSuggestionsShown = false
    /** Wurde ein Paket in den App-Speicher uebernommen (KEIN Flash)? */
    private var flashPackageObtained = false
    /** Wurde ein echter Flash vom Controller erfolgreich bestaetigt? */
    private var flashFlashedOk = false

    // Live-Telemetrie-Views
    private var dashSpeed: TextView? = null
    private var dashPower: TextView? = null
    private var dashVoltage: TextView? = null
    private var dashCurrent: TextView? = null
    private var dashBattery: TextView? = null
    private var dashMotorTemp: TextView? = null
    private var dashTrip: TextView? = null
    // PHASE 12.1: wiederhergestellte Fahrtzeit-Anzeige (Trip-Timer).
    private var dashTripTime: TextView? = null
    private var tripClockRunnable: Runnable? = null
    private var dashRange: TextView? = null
    private var dashCellMin: TextView? = null
    private var dashCellMax: TextView? = null
    private var dashCellDelta: TextView? = null
    private var dashTempGuardState: TextView? = null
    private var dashFeedState: TextView? = null

    // ---- PHASE 12.5: Cockpit (Tacho + Verlaufsgraphen) ----
    private var dashGauge: CockpitGaugeView? = null
    private var speedSpark: SparklineView? = null
    private var powerSpark: SparklineView? = null
    private var dashHistoryMinMax: TextView? = null
    private val speedHistory = ArrayDeque<Float>()
    private val powerHistory = ArrayDeque<Float>()
    private val HISTORY_MAX = 60

    // ---- PHASE 9: Diagnose ----
    private var diagOverall: TextView? = null
    private var diagConnection: TextView? = null
    private var diagProtocol: TextView? = null
    private var diagDataAge: TextView? = null
    private var diagCommErrors: TextView? = null
    private var diagBatteryState: TextView? = null
    private var diagBatteryVoltage: TextView? = null
    private var diagBatteryDelta: TextView? = null
    private var diagBatteryTemp: TextView? = null
    private var diagBatterySoh: TextView? = null
    private var diagChargeState: TextView? = null
    private var diagMotorRpm: TextView? = null
    private var diagMotorTemp: TextView? = null
    private var diagEcuStatus: TextView? = null
    private var diagFirmware: TextView? = null
    private var diagWarnings: TextView? = null
    private var diagLogButton: Button? = null

    // ---- PHASE 16.2: Telemetrie-Empfangsdiagnose ----
    private var diagRxNotifications: TextView? = null
    private var diagRxBytes: TextView? = null
    private var diagRxLast: TextView? = null
    private var diagRxFrames: TextView? = null
    private var diagRxDecode: TextView? = null
    private var diagRxBuffer: TextView? = null
    private var diagRxAdapter: TextView? = null
    private var diagRxNotify: TextView? = null
    private var diagRxNotifyChar: TextView? = null
    private var diagRxCccd: TextView? = null
    private var diagRxCause: TextView? = null
    /** Letzter von der Engine gemeldeter Diagnose-Schnappschuss (UI-Thread). */
    @Volatile private var lastTelemetryDiag: TelemetryDiagnosticsSnapshot? = null

    /** Monotone Empfangszeit der letzten Telemetrie (SystemClock.elapsedRealtime). */
    @Volatile private var lastTelemetryAtElapsed = 0L
    /** Zaehlt BLE-/Protokoll-Kommunikationsfehler seit dem letzten Verbindungsaufbau. */
    @Volatile private var commErrorCount = 0
    /** Ringpuffer der Diagnose-Ereignisse (thread-sicher, read-only Nutzung). */
    private val diagnosticLog = DiagnosticEventLog()

    // ---- PHASE 11: Hardware-Test-Erfassung (exportierbar) ---------------
    private val hardwareTest = HardwareTestCollector()
    private var htConnectedAt = 0L
    private var htReadyAt = 0L
    private var htDisconnectedAt = 0L
    private var htUnexpectedDisconnect = false
    // PHASE 12.7: Verbindungsqualitaet (Abbrueche zaehlen)
    private var disconnectCount = 0
    // PHASE 12.3: granularer Verbindungsstatus
    private var lastConnectFailed = false
    private var connStageView: TextView? = null
    private var htLastGatt: List<com.syrak.scooterlab.diagnostics.GattServiceRecord> = emptyList()
    // ---- PHASE 16: Read-Pfad --------------------------------------------
    /** Mindestens eine lesbare Characteristic im echten GATT-Baum gefunden. */
    @Volatile private var readPropertyFound = false
    /** Zaehlt echte erfolgreiche Reads (Callback mit Bytes) seit Verbindungsaufbau. */
    @Volatile private var readSuccessCount = 0
    /** Letzter Read-Ausgang fuer die UI. */
    @Volatile private var lastReadOutcome: ReadOutcome? = null
    private var pendingExportContent: String? = null
    private var pendingExportMime: String = "application/json"

    /** SAF-Export: Nutzer waehlt Zielort, wir schreiben den Bericht dorthin. */
    private fun writeExport(uri: Uri?) {
        val content = pendingExportContent
        if (uri == null || content == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            toast("Bericht gespeichert.")
            addLog(LogCategory.INFO, "Hardware-Testbericht exportiert: $uri")
        } catch (e: Exception) {
            addLog(LogCategory.ERROR, "Export fehlgeschlagen: ${e.message}")
        } finally {
            pendingExportContent = null
        }
    }

    private val jsonExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> writeExport(uri) }

    private val csvExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? -> writeExport(uri) }

    @Volatile private var lastTelemetry: Telemetry? = null
    private var motorGuardTripped = false
    // ---- FIX 4: Gesicherte Notaus-Rampe (Fahrersicherheit) --------------
    private var rampActive = false
    private var rampTicks = 0
    private var rampRunnable: Runnable? = null
    private var rampReason: String? = null
    private val RAMP_TICK_MS = 400L
    private val RAMP_MAX_TICKS = 25
    private val RAMP_CUT_SPEED_KMH = 3f

    // Demo-Simulation (NUR isDemo)
    private var telemetryRunnable: Runnable? = null
    private var simTick = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            if (::statusText.isInitialized) {
                statusText.text = "Berechtigungen erteilt. Scooter einschalten."
            }
            startBleScan()
        } else {
            if (::statusText.isInitialized) {
                statusText.text = "Bluetooth-Berechtigungen erforderlich."
            }
            if (::scanButton.isInitialized) {
                scanButton.isEnabled = true
            }
        }
    }

    private val firmwarePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) handleSelectedFirmware(uri) }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device
            val address = device.address
            val name = try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
                ) device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
                else result.scanRecord?.deviceName ?: "Unknown Device"
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: "Unknown Device"
            }

            // PHASE 8: Kandidaten-Erkennung in eine reine, testbare Funktion
            // ausgelagert ([ScooterCandidateFilter]). Vorher lag hier eine
            // private Inline-Liste, die KuKirin nicht kannte und nicht testbar war.
            val candidate = ScooterCandidateFilter.looksLikeScooter(name)

            // WICHTIG: Callback laeuft auf Binder-Thread -> UI-Update auf Main-Thread
            runOnUiThread {
                foundDevices[address] = ScooterDevice(device, name, address, result.rssi, candidate, false)
                // PHASE 11: Scan-Treffer strukturiert fuer den Export erfassen.
                val det = ScooterModel.detectDetailed(name, address)
                val now = SystemClock.elapsedRealtime()
                hardwareTest.recordScan(
                    ScanRecord(
                        name = name,
                        address = address,
                        rssi = result.rssi,
                        brand = ScooterBrand.classify(name).displayName,
                        detectedModel = if (det.model.isKnown) det.model.displayName else "unbekannt",
                        confidence = det.confidence.label,
                        firstSeenMs = now,
                        lastSeenMs = now,
                        seenCount = 1
                    )
                )
                requestThrottledListUpdate()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                isScanning = false
                // PHASE 5 FIX: Auch bei einem Fehlschlag das geplante Timeout
                // entfernen, damit es nicht spaeter einen neuen Scan stoert.
                cancelScanTimeout()
                scanTimeoutGuard.end()
                // Null-sicher: onScanFailed kann vor dem UI-Aufbau oder nach einem
                // Recreate eintreffen.
                if (::scanButton.isInitialized) scanButton.isEnabled = true
                if (::statusText.isInitialized) {
                    statusText.text = "BLE-Scan fehlgeschlagen (Code $errorCode)."
                }
                addLog(LogCategory.ERROR, "BLE-Scan fehlgeschlagen (Code $errorCode).")
            }
        }
    }

    /** Baut die Geraeteliste hoechstens alle [LIST_THROTTLE_MS] neu auf. */
    private fun requestThrottledListUpdate() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastListUpdateMs
        if (elapsed >= LIST_THROTTLE_MS) {
            lastListUpdateMs = now
            updateDeviceList()
            return
        }
        if (pendingListUpdate != null) return
        val r = Runnable {
            pendingListUpdate = null
            lastListUpdateMs = SystemClock.elapsedRealtime()
            updateDeviceList()
        }
        pendingListUpdate = r
        handler.postDelayed(r, LIST_THROTTLE_MS - elapsed)
    }

    private fun cancelPendingListUpdate() {
        pendingListUpdate?.let { handler.removeCallbacks(it) }
        pendingListUpdate = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ScooterRepository(this)
        currentRegion = repository.region
        autoReconnectEnabled = repository.autoReconnect

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        // PHASE 13: Ein laufender Countdown wird nach Prozess-Tod korrekt
        // fortgesetzt (monotone Zeitbasis). Bei frischem Start bleibt der
        // Startwert 0 und die Wartezeit beginnt erst beim Anzeigen.
        val nowElapsed = SystemClock.elapsedRealtime()
        val savedDisclaimer = savedInstanceState?.getLong(KEY_DISCLAIMER_START, 0L) ?: 0L
        val savedCountry = savedInstanceState?.getLong(KEY_COUNTRY_START, 0L) ?: 0L
        disclaimerStartElapsed = if (savedDisclaimer > 0L) CountdownGate.restore(savedDisclaimer, nowElapsed) else 0L
        countryStartElapsed = if (savedCountry > 0L) CountdownGate.restore(savedCountry, nowElapsed) else 0L

        showLoadingScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DISCLAIMER_START, disclaimerStartElapsed)
        outState.putLong(KEY_COUNTRY_START, countryStartElapsed)
    }

    override fun onResume() {
        super.onResume()
        // PHASE 13: Nach einer Unterbrechung wird der sichtbare Countdown-Zustand
        // sofort neu berechnet — es entsteht kein "haengender" Freigabe-Zustand.
        if (gateScreen != GateScreen.NONE) {
            renderGate()
            startGateTicker()
        }
    }

    override fun onPause() {
        super.onPause()
        // Timer anhalten; die Wartezeit selbst laeuft monoton weiter und wird
        // beim naechsten onResume neu aus der Startzeit berechnet.
        activeTimer?.cancel()
        activeTimer = null
    }

    override fun onDestroy() {
        splashCancelled = true
        splashView = null
        disarmGate()
        cancelReconnectRunnable()
        endSafeStopRamp()
        activeTimer?.cancel()
        stopTelemetry()
        stopTripClock()
        stopSystemBar()
        stopBleSession()
        // notifyListener = false: die Activity wird gerade zerstoert, ein
        // UI-Update waere hier nur ein spaeter Callback ohne Empfaenger.
        bleEngine?.disconnect(notifyListener = false)
        safeStopBleScan()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /*  UI-HELFER                                                          */
    /* ------------------------------------------------------------------ */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    /** PHASE 12: einheitlicher, abgerundeter Hintergrund mit dezenter Kontur. */
    private fun roundedBg(fill: Int, stroke: Int, radiusDp: Int = Palette.RADIUS_DP): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1).coerceAtLeast(1), stroke)
        }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Palette.SPACE_MD), dp(Palette.SPACE_MD), dp(Palette.SPACE_MD), dp(Palette.SPACE_MD))
        background = roundedBg(Palette.SURFACE_ALT, Palette.OUTLINE, 14)
        elevation = dp(1).toFloat()
    }

    /**
     * PHASE 14 (Teil B): Sektionslabel bewusst zurueckhaltend in Grau.
     * Rot bleibt echten Marken-/Warnakzenten vorbehalten, damit die Oberflaeche
     * ruhig und hochwertig wirkt statt bunt.
     */
    private fun makeSectionLabel(value: String): TextView =
        makeText(value.uppercase(), 10.5f, Palette.TEXT_MUTED, true).apply {
            letterSpacing = 0.22f
            setPadding(dp(2), dp(18), 0, dp(8))
        }

    private fun makePrimaryButton(label: String, color: Int = Palette.ACCENT, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = true
            // PHASE 13: Ripple statt flacher Flaeche (praezises Motorsport-Feedback).
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                roundedBg(color, color, 10),
                null
            )
            stateListAnimator = null
            setTextColor(Color.WHITE)
            textSize = 12f
            letterSpacing = 0.08f
            minHeight = dp(Palette.BUTTON_HEIGHT_DP)
            minimumHeight = dp(Palette.BUTTON_HEIGHT_DP)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }

    /** PHASE 12: kompakter Sekundaer-Button (nur Kontur, kein Vollflaechen-Farbtopf). */
    private fun makeOutlineButton(label: String, color: Int = Palette.ACCENT, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = true
            background = RippleDrawable(
                ColorStateList.valueOf(0x22FFFFFF),
                roundedBg(Color.TRANSPARENT, color, 10),
                null
            )
            stateListAnimator = null
            setTextColor(color)
            textSize = 12f
            letterSpacing = 0.06f
            minHeight = dp(Palette.BUTTON_HEIGHT_DP)
            minimumHeight = dp(Palette.BUTTON_HEIGHT_DP)
            setOnClickListener { onClick() }
        }

    private fun makeToggleRow(label: String, desc: String, initial: Boolean, onToggle: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(makeText(label, 14f, Palette.TEXT_PRIMARY, true))
            val state = makeText(if (initial) "AN" else "AUS", 12f,
                if (initial) Palette.ACCENT_GREEN else Palette.TEXT_MUTED, true).apply { gravity = Gravity.END }
            header.addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(header)
            addView(makeText(desc, 12f, Palette.TEXT_MUTED).apply { setPadding(0, dp(3), 0, 0) })
            var value = initial
            setOnClickListener {
                value = !value
                state.text = if (value) "AN" else "AUS"
                state.setTextColor(if (value) Palette.ACCENT_GREEN else Palette.TEXT_MUTED)
                onToggle(value)
            }
        }

    private fun makeSliderCard(
        title: String, desc: String, min: Int, max: Int, initial: Int, unit: String,
        valueFormatter: (Int) -> String = { "$it $unit" }, onChange: (Int) -> Unit
    ): LinearLayout {
        val card = makeCard()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeText(title, 14f, Palette.TEXT_PRIMARY, true))
        val valueView = makeText(valueFormatter(initial), 14f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.END }
        header.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)
        card.addView(makeText(desc, 12f, Palette.TEXT_MUTED).apply { setPadding(0, dp(3), 0, dp(10)) })
        val bar = SeekBar(this).apply { this.max = max - min; progress = initial - min }
        var lastCommit = 0L
        var pendingReal = initial
        val commitRunnable = Runnable {
            lastCommit = System.currentTimeMillis()
            onChange(pendingReal)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val real = progress + min
                pendingReal = real
                valueView.text = valueFormatter(real)
                if (!fromUser) return
                val now = System.currentTimeMillis()
                handler.removeCallbacks(commitRunnable)
                if (now - lastCommit >= SLIDER_DEBOUNCE_MS) {
                    commitRunnable.run()
                } else {
                    handler.postDelayed(commitRunnable, SLIDER_DEBOUNCE_MS - (now - lastCommit))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(commitRunnable)
                commitRunnable.run()
            }
        })
        card.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // PHASE 12.6: Min/Max in einer Zeile, damit der Wertebereich klar ist.
        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        rangeRow.addView(makeText("Min: $min $unit", 10f, Palette.TEXT_MUTED),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        val maxView = makeText("Max: $max $unit", 10f, Palette.TEXT_MUTED)
        maxView.gravity = Gravity.END
        rangeRow.addView(maxView, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(rangeRow)
        return card
    }

    /** PHASE 12.6: kennzeichnet gefaehrliche Funktionen sichtbar (Text + Farbe). */
    private fun addDangerBadge(parent: LinearLayout, text: String) {
        parent.addView(makeText("⚠ GEF\u00c4HRLICH \u00b7 $text", 10f, Palette.DANGER, true).apply {
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(10)) }

    private fun safeStopBleScan() {
        // PHASE 5 FIX: Ein noch geplantes Timeout-Runnable MUSS immer entfernt
        // werden — auch wenn der Scan bereits (z. B. durch onScanFailed) beendet
        // wurde. Sonst feuert es spaeter gegen einen NEU gestarteten Scan und
        // beendet diesen vorzeitig. Die Generation entwertet zusaetzlich jeden
        // bereits laufenden Timeout.
        cancelScanTimeout()
        scanTimeoutGuard.end()
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (_: SecurityException) {
            android.util.Log.w("SyrakScooterLab", "BLE scanner stop denied by permission policy")
        } catch (_: Exception) {
            android.util.Log.w("SyrakScooterLab", "BLE scanner stop failed unexpectedly")
        } finally {
            isScanning = false
            cancelPendingListUpdate()
            if (::scanButton.isInitialized) {
                scanButton.isEnabled = true
            }
        }
    }

    /** Entfernt ein evtl. noch geplantes Scan-Timeout-Runnable aus der Queue. */
    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    /* ================================================================== */
    /*  ZUSÄTZLICHER UI-ZUSTAND                                           */
    /* ================================================================== */
    private var flashProgressBar: ProgressBar? = null
    private var flashStatusText: TextView? = null
    private var demoActive = false
    private var scanTimeoutRunnable: Runnable? = null
    /** PHASE 5 FIX: entwertet veraltete Scan-Timeouts (siehe [ScanTimeoutGuard]). */
    private val scanTimeoutGuard = ScanTimeoutGuard()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun adapter(): ScooterProtocolAdapter =
        bleEngine?.adapter ?: ProtocolAdapters.forProtocol(activeProtocol)

    /**
     * PHASE 10: true nur, wenn ein Adapter das erkannte Modell EXPLIZIT fuehrt.
     * Ein Hersteller-Treffer allein reicht nicht.
     */
    private fun modelAdapterExact(): Boolean =
        ProtocolAdapters.resolveForModel(detectedModel).exact

    /** PHASE 12: belegt, ob der aktive Adapter Status-Telemetrie lesen kann. */
    private fun adapterSupportsRead(): Boolean =
        adapter().capabilities.supportsRead(ReadOperation.STATUS_TELEMETRY)

    /**
     * PHASE 11: Zentrale Sperrpruefung ueber [WriteGuard]. Buendelt die
     * bestehende [TuningSafetyGate] mit der Forderung nach einem
     * modellspezifischen, schreibfaehigen Adapter. Unbekannte Modelle bleiben
     * read-only; Lesen ist weiterhin immer erlaubt.
     */
    private fun tuningBlockReason(): String? =
        WriteGuard.blockReason(detectedModel, hardwareVerified, adapter())

    /* ================================================================== */
    /*  PHASE 11: HARDWARE-TESTBERICHT (EXPORT)                           */
    /* ================================================================== */

    /** Baut den exportierbaren Bericht aus tatsaechlich beobachteten Daten. */
    private fun buildHardwareReport(): HardwareTestReport {
        val support = SupportMatrix.snapshot(
            detectedModel,
            adapter(),
            SupportRuntime(
                gattDiscovered = linkState == BleLinkState.READY,
                telemetryReceived = lastTelemetryAtElapsed > 0L,
                hardwareVerified = hardwareVerified,
                tuningGateOpen = WriteGuard.evaluate(detectedModel, hardwareVerified, adapter()).allowed
            ),
            detectedManufacturer
        )
        val deviceName = connectedDevice?.name
            ?: foundDevices.values.firstOrNull()?.name
        val address = connectedDevice?.address
            ?: foundDevices.values.firstOrNull()?.address
            ?: "—"
        val liveGatt = bleEngine?.gattSnapshot().orEmpty()
        if (liveGatt.isNotEmpty()) htLastGatt = liveGatt
        return HardwareTestReport(
            generatedAtEpochMs = System.currentTimeMillis(),
            appVersion = appVersionName(),
            deviceName = deviceName,
            address = address,
            manufacturer = detectedManufacturer.displayName,
            detectedModel = if (detectedModel.isKnown) detectedModel.displayName else "unbekannt",
            confidence = detectedConfidence.label,
            supportStatus = support.status.label,
            readOnly = WriteGuard.isReadOnly(detectedModel, hardwareVerified, adapter()),
            protocol = activeProtocol.displayName,
            adapterId = adapter().id,
            connection = ConnectionRecord(
                connectedAtMs = htConnectedAt.takeIf { it > 0 },
                readyAtMs = htReadyAt.takeIf { it > 0 },
                disconnectedAtMs = htDisconnectedAt.takeIf { it > 0 },
                mtu = bleEngine?.currentMtu() ?: 0,
                protocol = activeProtocol.displayName,
                adapterId = adapter().id,
                disconnectUnexpected = htUnexpectedDisconnect
            ),
            scanResults = hardwareTest.scanResults(),
            services = htLastGatt,
            frames = hardwareTest.frames(),
            errors = hardwareTest.errors(),
            reads = hardwareTest.reads(),
            rssiSamples = hardwareTest.rssiSamples(),
            notes = buildList {
                add("Reine Beobachtungsdaten. Keine erfundenen UUIDs, Kommandos oder Decoder-Offsets.")
                if (!detectedModel.isKnown) {
                    add("Modell nicht erkannt: Bericht ist read-only, es wurden keine Schreibbefehle gesendet.")
                }
                if (detectedModel == ScooterModel.NAVEE_GT3_MAX) {
                    add("Telemetrie-Offsets fuer GT3 Max sind NICHT am realen Geraet verifiziert.")
                }
            }
        )
    }

    /**
     * PHASE 12.7 B: Speichert den aktuellen Diagnosestand lokal (SharedPreferences).
     * Es werden ausschliesslich bereits vorhandene Beobachtungsdaten abgelegt.
     */
    private fun saveDiagnosticHistory() {
        val report = buildHardwareReport()
        val entry = DiagnosticHistoryEntry(
            timestampMs = report.generatedAtEpochMs,
            model = report.detectedModel,
            supportStatus = report.supportStatus,
            errors = report.errors.size,
            warnings = report.notes.size,
            summary = "Frames ${report.frames.size} \u00b7 MTU ${report.connection.mtu}"
        )
        val existing = DiagnosticHistoryCodec.decode(repository.diagnosticHistoryRaw).toMutableList()
        existing.add(entry)
        // Begrenzung: nur die letzten 20 Berichte behalten.
        val trimmed = if (existing.size > 20) existing.takeLast(20) else existing
        repository.diagnosticHistoryRaw = DiagnosticHistoryCodec.encode(trimmed)
        addLog(LogCategory.INFO, "Diagnosebericht gespeichert (${trimmed.size} Eintraege).")
        showTab(Tab.INFO)
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Startet den SAF-Export (JSON oder CSV). */
    private fun exportHardwareReport(csv: Boolean) {
        val report = buildHardwareReport()
        pendingExportContent = if (csv) report.toCsv() else report.toJson()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val name = if (csv) "syrak-hardwaretest-$stamp.csv" else "syrak-hardwaretest-$stamp.json"
        if (csv) csvExporter.launch(name) else jsonExporter.launch(name)
    }

    /** Kopiert die Kurzfassung in die Zwischenablage (schneller Blick). */
    private fun copyHardwareSummary() {
        val summary = buildHardwareReport().toSummary()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Syrak Hardware-Test", summary))
        toast("Zusammenfassung kopiert.")
    }

    /* ================================================================== */
    /*  START: LADEN / HAFTUNG / PIN                                      */
    /* ================================================================== */

    private fun showLoadingScreen() {
        rootContainer.removeAllViews()
        splashCancelled = false
        val splash = SplashScreenView(this)
        splashView = splash
        rootContainer.addView(splash, LinearLayout.LayoutParams(MATCH, MATCH))
        splash.setStage(SplashSequence.initializing(), 0f, 0)
        runSplashSteps(splash)
    }

    /**
     * PHASE 13: Premium-Splash mit AUSSCHLIESSLICH echten Startschritten.
     *
     * Jeder Schritt liest einen bereits vorhandenen Zustand (Konfiguration,
     * Adapter-Registry, Bluetooth-Adapter, Tab-Registry). Es werden KEINE
     * Hardwarepruefungen, Verbindungen oder Firmware-Checks vorgetaeuscht.
     * Die Navigation haengt an diesen Schritten — keine kuenstliche Wartezeit,
     * keine doppelte Initialisierung und kein Blockieren des BLE-Starts
     * (BLE wird weiterhin erst beim Verbinden initialisiert).
     */
    private fun runSplashSteps(splash: SplashScreenView) {
        val steps: List<Pair<SplashStage, () -> String>> = listOf(
            SplashSequence.initializing() to {
                "Konfiguration geladen · Region ${currentRegion.code} · " +
                    (if (repository.hasPin) "PIN-Schutz aktiv" else "kein PIN gesetzt")
            },
            SplashSequence.modules() to {
                "Protokoll-Adapter: ${ProtocolAdapters.all.size} · " +
                    ProtocolAdapters.all.joinToString(" / ") { it.displayName }
            },
            SplashSequence.bluetooth() to {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                when {
                    adapter == null -> "Kein Bluetooth-Adapter auf diesem Geraet"
                    !adapter.isEnabled -> "Bluetooth-Adapter vorhanden · deaktiviert"
                    else -> {
                        val open = missingPermissions().size
                        "Bluetooth-Adapter bereit · $open Berechtigung(en) offen"
                    }
                }
            },
            SplashSequence.dashboard() to {
                "Bereiche registriert: ${Tab.values().size} · " +
                    Tab.values().joinToString(" / ") { it.name }
            }
        )

        fun step(index: Int) {
            if (splashCancelled || splashView !== splash) return
            if (index >= steps.size) {
                finishSplash(splash)
                return
            }
            val (stage, detail) = steps[index]
            splash.setStage(
                stage.copy(detail = detail()),
                SplashSequence.progress(index),
                SplashSequence.percent(index)
            )
            handler.postDelayed({ step(index + 1) }, 170L)
        }
        step(0)
    }

    private fun finishSplash(splash: SplashScreenView) {
        splash.setReady()
        handler.postDelayed({
            if (splashCancelled || splashView !== splash) return@postDelayed
            splashCancelled = true
            splashView = null
            when {
                !repository.disclaimersAccepted -> showDisclaimer()
                !repository.countryNoticeAccepted -> showCountryDisclaimer()
                !repository.onboardingSeen -> showOnboarding()
                repository.hasPin -> showPinDialog()
                else -> enterMain()
            }
        }, 240L)
    }

    /* ------------------------------------------------------------------ */
    /*  PHASE 13: COUNTDOWN-GATE (Disclaimer + Laenderhinweis)            */
    /* ------------------------------------------------------------------ */

    /**
     * Aktiviert ein Gate: Der Button bleibt deaktiviert, bis die Wartezeit
     * vollstaendig abgelaufen ist UND (optional) bis zum Ende gescrollt wurde.
     * Der Klick-Handler prueft den Zustand zusaetzlich selbst — schnelles
     * Tippen oder ein alter Listener kann das Gate nicht umgehen.
     */
    private fun armGate(
        screen: GateScreen,
        requiredMs: Long,
        startElapsed: Long,
        requireScroll: Boolean,
        scroll: ScrollView,
        status: TextView,
        button: Button,
        acceptLabel: String,
        onAccept: () -> Unit
    ) {
        activeTimer?.cancel()
        gateScreen = screen
        gateRequiredMs = requiredMs
        gateStartElapsed = startElapsed
        gateRequiresScroll = requireScroll
        gateScrollOk = !requireScroll
        gateAcceptLabel = acceptLabel
        gateScroll = scroll
        gateStatusView = status
        gateButton = button
        gateAction = onAccept

        button.isEnabled = false
        button.alpha = 0.45f
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateGateScroll() }
        scroll.post { updateGateScroll() }
        button.setOnClickListener {
            if (!gateReady()) {
                // Defense-in-depth: kein Fortfahren ohne erfuellte Bedingungen.
                renderGate()
                return@setOnClickListener
            }
            val action = gateAction
            disarmGate()
            action?.invoke()
        }
        renderGate()
        startGateTicker()
    }

    private fun disarmGate() {
        activeTimer?.cancel()
        activeTimer = null
        gateScroll?.setOnScrollChangeListener(null)
        gateScreen = GateScreen.NONE
        gateButton = null
        gateStatusView = null
        gateScroll = null
        gateAction = null
        gateRequiresScroll = false
        gateScrollOk = true
    }

    private fun updateGateScroll() {
        val s = gateScroll ?: return
        val child = s.getChildAt(0)
        if (child == null) {
            gateScrollOk = true
            renderGate()
            return
        }
        val viewport = s.height - s.paddingTop - s.paddingBottom
        gateScrollOk = ScrollGate.isAtBottom(s.scrollY, child.height, viewport, dp(28))
        renderGate()
    }

    private fun gateReady(): Boolean {
        val state = CountdownGate.state(gateRequiredMs, gateStartElapsed, SystemClock.elapsedRealtime())
        return state.finished && (!gateRequiresScroll || gateScrollOk)
    }

    private fun startGateTicker() {
        if (gateScreen == GateScreen.NONE) return
        activeTimer?.cancel()
        val remaining = CountdownGate.state(
            gateRequiredMs, gateStartElapsed, SystemClock.elapsedRealtime()
        ).remainingMs
        // Kurzer Nachlauf, damit der letzte Tick sicher den Endzustand rendert.
        activeTimer = object : CountDownTimer(remaining + 1_200L, 200L) {
            override fun onTick(millisUntilFinished: Long) = renderGate()
            override fun onFinish() = renderGate()
        }.start()
    }

    private fun renderGate() {
        val button = gateButton ?: return
        val status = gateStatusView
        val state = CountdownGate.state(gateRequiredMs, gateStartElapsed, SystemClock.elapsedRealtime())
        val scrollOk = !gateRequiresScroll || gateScrollOk
        val enabled = state.finished && scrollOk

        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
        button.text = when {
            !state.finished -> "GESPERRT · ${state.remainingSeconds}s"
            !scrollOk -> "BITTE BIS ZUM ENDE SCROLLEN"
            else -> gateAcceptLabel
        }
        status?.text = when {
            !state.finished -> "Bitte warte noch ${state.remainingSeconds} Sekunden …"
            !scrollOk -> "Bitte lies den Hinweis bis zum Ende (scrollen)."
            else -> "Bestätigung freigeschaltet — du kannst jetzt fortfahren."
        }
        status?.setTextColor(
            when {
                !state.finished -> Palette.WARNING
                !scrollOk -> Palette.WARNING
                else -> Palette.SUCCESS
            }
        )
    }

    private fun showDisclaimer() {
        splashCancelled = true
        splashView = null
        disarmGate()
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        // PHASE 13: Branding-Kopf mit neuem Logo
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_syrak_mark)
            contentDescription = "Syrak ScooterLab Logo"
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        headerText.addView(makeText("SYRAK SCOOTERLAB", 15f, Palette.TEXT_PRIMARY, true).apply {
            letterSpacing = 0.08f
        })
        headerText.addView(makeText("DIAGNOSTIC CORE · PHASE 13", 9.5f, Palette.TEXT_MUTED, true).apply {
            letterSpacing = 0.16f
        })
        header.addView(headerText, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(header)

        // PHASE 14 (Teil C): Der allgemeine Haftungstext erscheint beim ersten
        // Start IMMER auf Englisch. Eine optionale Umschaltung auf Deutsch ist
        // moeglich, ohne den Inhalt zu veraendern (keine Maschinenuebersetzung).
        val lang = disclaimerLanguage
        col.addView(makeText(DisclaimerText.title(lang), 18f, Palette.DANGER, true))
        col.addView(makeText(
            DisclaimerText.intro(lang),
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, dp(10)) })

        // Sprachumschaltung: Englisch (Standard) / Deutsch. Der Text bleibt in
        // beiden Faellen identisch im Umfang - es wird nichts gekuerzt.
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        val enChip = makeOutlineButton(
            "ENGLISH",
            if (lang == AppLanguage.EN) Palette.ACCENT else Palette.OUTLINE
        ) {
            if (disclaimerLanguage != AppLanguage.EN) {
                disclaimerLanguage = AppLanguage.EN
                showDisclaimer()
            }
        }
        val deChip = makeOutlineButton(
            "DEUTSCH",
            if (lang == AppLanguage.DE) Palette.ACCENT else Palette.OUTLINE
        ) {
            if (disclaimerLanguage != AppLanguage.DE) {
                disclaimerLanguage = AppLanguage.DE
                showDisclaimer()
            }
        }
        langRow.addView(enChip, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, 0, dp(6), 0) })
        langRow.addView(deChip, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        col.addView(langRow)

        // PHASE 12.1: Die vollstaendige, mehrteilige Fassung bleibt erhalten.
        // Keine Warnung wurde entfernt - nur sprachlich ausgelagert.
        DisclaimerText.sections(lang).forEach { section ->
            col.addView(makeText(section.heading, 13f, Palette.TEXT_PRIMARY, true).apply { setPadding(0, dp(12), 0, dp(4)) })
            col.addView(makeText(section.body, 12.5f, Palette.TEXT_SECONDARY).apply {
                setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, 0, 0, dp(6))
            })
        }
        col.addView(makeText(
            DisclaimerText.emergency(lang),
            12f, Palette.WARNING
        ).apply { setPadding(0, dp(14), 0, dp(20)) })

        // PHASE 13: sichtbarer Countdown + Scroll-Pflicht (10 s).
        if (disclaimerStartElapsed <= 0L) {
            disclaimerStartElapsed = CountdownGate.start(SystemClock.elapsedRealtime())
        }
        val gateHint = makeText(
            DisclaimerText.gateHint(lang, (DISCLAIMER_COUNTDOWN_MS / 1000L).toInt()),
            11f, Palette.TEXT_MUTED
        ).apply { setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, 0, 0, dp(8)) }
        col.addView(gateHint)
        val gateStatus = makeText("", 12.5f, Palette.WARNING, true).apply {
            setPadding(0, 0, 0, dp(8))
        }
        col.addView(gateStatus)
        val accept = makePrimaryButton(
            DisclaimerText.lockedButton(lang, (DISCLAIMER_COUNTDOWN_MS / 1000L).toInt()),
            Palette.ACCENT
        ) {}
        col.addView(accept, LinearLayout.LayoutParams(MATCH, WRAP))

        scroll.addView(col)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))

        armGate(
            GateScreen.DISCLAIMER,
            DISCLAIMER_COUNTDOWN_MS,
            disclaimerStartElapsed,
            requireScroll = true,
            scroll = scroll,
            status = gateStatus,
            button = accept,
            acceptLabel = DisclaimerText.acceptLabel(disclaimerLanguage)
        ) {
            repository.disclaimersAccepted = true
            disclaimerStartElapsed = 0L
            continueAfterDisclaimer()
        }
    }

    /** Nach dem Haftungshinweis: Laender-Hinweis (falls noetig), dann Onboarding/PIN. */
    private fun continueAfterDisclaimer() {
        activeTimer?.cancel()
        if (!repository.countryNoticeAccepted) showCountryDisclaimer() else continueAfterCountry()
    }

    private fun continueAfterCountry() {
        if (!repository.onboardingSeen) showOnboarding() else {
            if (repository.hasPin) showPinDialog() else enterMain()
        }
    }

    /**
     * PHASE 12.1 / PHASE 13: Laender-Hinweis mit belegbaren Quellen und Stand.
     * Es werden KEINE Geschwindigkeitsgrenzen erfunden; unbekannte Laender
     * erhalten einen neutralen Hinweis. Das Land kann manuell gewaehlt werden.
     *
     * PHASE 13: eigener, nicht umgehbarer Countdown (8 s) + Scroll-Pflicht.
     * Die bestehende Auswahl, Speicherung und Quellenangabe bleiben erhalten.
     */
    private fun showCountryDisclaimer() {
        splashCancelled = true
        splashView = null
        disarmGate()
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_syrak_mark)
            contentDescription = "Syrak ScooterLab Logo"
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(makeText("SYRAK SCOOTERLAB", 13f, Palette.TEXT_PRIMARY, true).apply {
            letterSpacing = 0.08f
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(header)

        // PHASE 14 (Teil C): Sprache aus Systemsprache/Land ableiten. Der Inhalt
        // (Land, Grenzwerte, Quellen) bleibt unveraendert - nur die Darstellung
        // wird lokalisiert. Es findet KEINE Maschinenuebersetzung statt.
        val lang = noticeLanguage()
        val notice = CountryNoticeTexts.forRegion(repository.region, lang)
        col.addView(makeText(CountryNoticeTexts.title(lang), 18f, Palette.ACCENT, true))
        col.addView(makeText(notice.displayName, 13f, Palette.TEXT_MUTED).apply {
            setPadding(0, dp(4), 0, dp(4))
        })
        col.addView(makeText(CountryNoticeTexts.languageLine(lang), 10.5f, Palette.TEXT_MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })
        col.addView(makeText(
            CountryNoticeTexts.speedLine(lang, notice.legalSpeedKmh, notice.displayName),
            14f, if (notice.verified) Palette.SUCCESS else Palette.WARNING, true
        ))
        col.addView(makeText(
            CountryNoticeTexts.verifiedLine(lang, notice.verified),
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        notice.points.forEach { p ->
            col.addView(makeText("\u2022  $p", 12.5f, Palette.TEXT_SECONDARY).apply {
                setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, 0, 0, dp(8))
            })
        }
        col.addView(makeText("${CountryNoticeTexts.sourceLabel(lang)}: ${notice.source}", 11f, Palette.TEXT_MUTED).apply {
            setPadding(0, dp(8), 0, dp(2))
        })
        col.addView(makeText("${CountryNoticeTexts.dateLabel(lang)}: ${notice.sourceDate}", 11f, Palette.TEXT_MUTED))

        // PHASE 15: veralteten Katalogstand ausdruecklich kennzeichnen.
        val nowCal = java.util.Calendar.getInstance()
        if (CountryNoticeTexts.catalogOutdated(
                CountryLegalCatalog.CATALOG_DATE,
                nowCal.get(java.util.Calendar.YEAR),
                nowCal.get(java.util.Calendar.MONTH) + 1
            )
        ) {
            col.addView(makeText(
                CountryNoticeTexts.outdatedBanner(lang, CountryLegalCatalog.CATALOG_DATE),
                11f, Palette.ACCENT_RED, true
            ).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, dp(0))
            })
        }

        // PHASE 13: transparente Kennzeichnung der Aktualitaet/Unsicherheit.
        // Es werden bewusst KEINE neuen Grenzwerte behauptet.
        col.addView(makeText(
            CountryNoticeTexts.transparency(lang, CountryLegalCatalog.CATALOG_DATE),
            11f, Palette.WARNING
        ).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(10), 0, dp(4))
        })

        val change = makeOutlineButton(CountryNoticeTexts.changeCountry(lang), Palette.ACCENT) {
            showCountryPicker()
        }
        col.addView(change, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(16), 0, dp(8)) })

        val gateHint = makeText(
            DisclaimerText.gateHint(lang, (COUNTRY_COUNTDOWN_MS / 1000L).toInt()),
            11f, Palette.TEXT_MUTED
        ).apply { setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, 0, 0, dp(8)) }
        col.addView(gateHint)
        val gateStatus = makeText("", 12.5f, Palette.WARNING, true).apply {
            setPadding(0, 0, 0, dp(8))
        }
        col.addView(gateStatus)
        val accept = makePrimaryButton(
            DisclaimerText.lockedButton(lang, (COUNTRY_COUNTDOWN_MS / 1000L).toInt()),
            Palette.ACCENT
        ) {}
        col.addView(accept, LinearLayout.LayoutParams(MATCH, WRAP))

        scroll.addView(col)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))

        if (countryStartElapsed <= 0L) {
            countryStartElapsed = CountdownGate.start(SystemClock.elapsedRealtime())
        }
        armGate(
            GateScreen.COUNTRY,
            COUNTRY_COUNTDOWN_MS,
            countryStartElapsed,
            requireScroll = true,
            scroll = scroll,
            status = gateStatus,
            button = accept,
            acceptLabel = CountryNoticeTexts.accept(lang)
        ) {
            repository.countryNoticeAccepted = true
            countryStartElapsed = 0L
            continueAfterCountry()
        }
    }

    private fun showCountryPicker() {
        val regions = Region.values()
        val labels = regions.map { "${it.displayName} (${it.code})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(CountryNoticeTexts.changeCountry(noticeLanguage()).lowercase().replaceFirstChar { it.uppercase() })
            .setItems(labels) { _, which ->
                val previous = repository.region
                repository.region = regions[which]
                currentRegion = regions[which]
                // PHASE 13/14: Ein Laenderwechsel setzt die Lesebestaetigung
                // zurueck UND startet die Lesephase neu - ein Wechsel darf den
                // Countdown nicht verkuerzen und nicht als Zustimmung gelten.
                if (previous != regions[which]) {
                    repository.countryNoticeAccepted = false
                }
                countryStartElapsed = 0L
                showCountryDisclaimer()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /**
     * PHASE 12.7 E: kurzes Onboarding beim ersten Start. Ersetzt NICHT den
     * Haftungshinweis, sondern erklaert danach Funktionen und Grenzen.
     */
    private fun showOnboarding() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(makeText("WILLKOMMEN BEI SYRAK SCOOTERLAB", 18f, Palette.ACCENT, true))
        val items = listOf(
            "Was die App kann" to
                "Diagnose, Telemetrie und Werkstatt-Funktionen fuer E-Scooter: Live-Werte, Trip-Computer, BLE-Diagnose und exportierbare Hardware-Testberichte.",
            "Welche Scooter erkannt werden" to
                "Bekannte Hersteller: NAVEE, Xiaomi, Ninebot/Segway, KuKirin, SoFlow. Unbekannte Geraete werden NICHT automatisch als Scooter behandelt, bleiben aber in der Liste sichtbar.",
            "Was 'Read-Only' bedeutet" to
                "Bei unbekanntem Modell oder nicht bestaetigter Hardware werden KEINE Schreibbefehle gesendet. Die App liest und diagnostiziert nur.",
            "Noch nicht hardware-validiert" to
                "Viele Protokolle, UUIDs und Decoder-Offsets sind dokumentiert, aber noch nicht an echter Hardware verifiziert. Erfolgsmeldungen bedeuten daher nicht automatisch, dass ein Befehl am Roller gewirkt hat."
        )
        items.forEach { (h, b) ->
            col.addView(makeText(h, 13f, Palette.TEXT_PRIMARY, true).apply { setPadding(0, dp(12), 0, dp(4)) })
            col.addView(makeText(b, 12.5f, Palette.TEXT_SECONDARY).apply {
                setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, 0, 0, dp(6))
            })
        }
        val go = makePrimaryButton("VERSTANDEN", Palette.ACCENT) {
            repository.onboardingSeen = true
            if (repository.hasPin) showPinDialog() else enterMain()
        }
        col.addView(go, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(16), 0, 0) })
        scroll.addView(col)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("PIN erforderlich")
            .setMessage("Die App ist mit einer PIN geschützt.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Entsperren") { _, _ ->
                if (repository.verifyPin(input.text.toString())) {
                    enterMain()
                } else {
                    toast("Falsche PIN.")
                    showPinDialog()
                }
            }
            .setNegativeButton("Beenden") { _, _ -> finish() }
            .show()
    }

    private fun enterMain() {
        rootContainer.removeAllViews()
        startTripClock()

        // PHASE 13: feine rote Signaturkante als Motorsport-Akzent.
        rootContainer.addView(View(this).apply {
            setBackgroundColor(Palette.DANGER)
        }, LinearLayout.LayoutParams(MATCH, dp(2)))

        val sysBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.SURFACE_DEEP)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        sysRssiView = makeText("RSSI —", 10f, Palette.TEXT_MUTED, true)
        sysMtuView = makeText("MTU —", 10f, Palette.TEXT_MUTED, true)
        sysQueueView = makeText("Q 0", 10f, Palette.TEXT_MUTED, true)
        sysLinkView = makeText("IDLE", 10f, Palette.ACCENT_ORANGE, true)
        sysBar.addView(sysLinkView, LinearLayout.LayoutParams(0, WRAP, 1f))
        sysBar.addView(sysMtuView, LinearLayout.LayoutParams(0, WRAP, 1f))
        sysBar.addView(sysQueueView, LinearLayout.LayoutParams(0, WRAP, 1f))
        sysBar.addView(sysRssiView, LinearLayout.LayoutParams(0, WRAP, 1f))
        rootContainer.addView(sysBar, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(this)
        tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(14), dp(8), dp(14), dp(16))
        }
        scroll.addView(tabContent)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.SURFACE_DEEP)
        }
        rootContainer.addView(bottomNav, LinearLayout.LayoutParams(MATCH, WRAP))

        buildTabs()
        showTab(Tab.INFO)
        startSystemBar()
        addLog(LogCategory.INFO, "Syrak ScooterLab gestartet.")
    }

    /* ================================================================== */
    /*  TABS                                                              */
    /* ================================================================== */

    private fun buildTabs() {
        if (!::bottomNav.isInitialized) return
        bottomNav.removeAllViews()
        for (tab in Tab.values()) {
            val selected = tab == currentTab
            // PHASE 13: aktiver Tab mit praeziser Unterkante statt Textpunkt.
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(8))
                setOnClickListener { showTab(tab) }
            }
            item.addView(makeText(tab.name, 10.5f,
                if (selected) Palette.ACCENT else Palette.TEXT_MUTED, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
            })
            item.addView(View(this).apply {
                setBackgroundColor(if (selected) Palette.ACCENT else Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(20), dp(2)).apply { topMargin = dp(5) })
            bottomNav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        logContainer = null
        logScroll = null
        tabContent.removeAllViews()
        when (tab) {
            Tab.INFO -> renderInfo()
            Tab.TOOLS -> renderTools()
            Tab.DASHBOARD -> renderDashboard()
            Tab.FLASH -> renderFlash()
            Tab.CONFIG -> renderConfig()
        }
        buildTabs()
    }

    /* ================================================================== */
    /*  INFO / SCAN / VERBINDUNG                                          */
    /* ================================================================== */

    private fun renderInfo() {
        // PHASE 13: Branding-Kopf mit neuem Logo (INFO-Bereich).
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_syrak_mark)
            contentDescription = "Syrak ScooterLab Logo"
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(makeText("SYRAK SCOOTERLAB", 16f, Palette.TEXT_PRIMARY, true).apply {
            letterSpacing = 0.06f
        })
        brandText.addView(makeText(
            "DIAGNOSTIC CORE \u00b7 ${appVersionName()} \u00b7 PHASE 13",
            9.5f, Palette.TEXT_MUTED, true
        ).apply { letterSpacing = 0.14f })
        brand.addView(brandText, LinearLayout.LayoutParams(0, WRAP, 1f))
        tabContent.addView(brand)

        tabContent.addView(makeSectionLabel("VERBINDUNG"))
        val card = makeCard()
        statusText = makeText(
            if (engineReady) "Verbunden und bereit." else "Bereit. Scooter einschalten und Scan starten.",
            13f, Palette.TEXT_SECONDARY
        )
        card.addView(statusText)
        // PHASE 12.3: feiner aufgeloester Verbindungsstatus.
        connStageView = makeText("", 13f, Palette.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(connStageView)
        scanButton = makePrimaryButton(
            if (isScanning) "SCAN ABBRECHEN" else "BLE-SCAN STARTEN",
            if (isScanning) Palette.WARNING else Palette.ACCENT
        ) { if (isScanning) safeStopBleScan() else startBleScan() }
        // PHASE 12.3: Scan muss sich jederzeit abbrechen lassen -> immer aktiv.
        card.addView(scanButton, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(0, dp(10), 0, dp(8))
        })
        // PHASE 4: Es gab bisher KEINE Moeglichkeit, eine bestehende Verbindung
        // bewusst zu trennen (der Verbinden-Button ist im verbundenen Zustand
        // deaktiviert). Ohne manuelles Trennen war "kein Reconnect nach
        // bewusstem Disconnect" gar nicht erreichbar.
        if (connectedDevice != null) {
            card.addView(makePrimaryButton("VERBINDUNG TRENNEN", Palette.ACCENT_RED) {
                manualDisconnect()
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, 0, 0, dp(8))
            })
        }
        // PHASE 12.3: Filter-/Statuszeile fuer den Scan.
        scanStatusView = makeText("", 11f, Palette.TEXT_MUTED).apply { setPadding(0, dp(2), 0, dp(6)) }
        card.addView(scanStatusView)
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        filterRow.addView(makeOutlineButton(
            if (showAllDevices) "NUR SCOOTER" else "ALLE GER\u00c4TE",
            Palette.ACCENT
        ) {
            showAllDevices = !showAllDevices
            showTab(Tab.INFO)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, 0, dp(4), 0) })
        filterRow.addView(makeOutlineButton(
            manufacturerFilter?.let { "FILTER: $it" } ?: "HERSTELLER-FILTER",
            if (manufacturerFilter != null) Palette.SUCCESS else Palette.ACCENT
        ) {
            showManufacturerFilter()
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        card.addView(filterRow)
        deviceListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(deviceListContainer)
        tabContent.addView(card, cardParams())
        updateDeviceList()

        // ---- PHASE 12.7 A: SCHNELLZUGRIFF AUF ZULETZT VERWENDETEN SCOOTER ----
        // Bewusst KEIN automatischer Verbindungsaufbau: der Nutzer tippt, und
        // es laufen dieselben Sicherheitspruefungen wie bei jedem anderen Geraet.
        val lastAddr = repository.lastDeviceAddress
        if (lastAddr.isNotBlank()) {
            tabContent.addView(makeSectionLabel("ZULETZT VERWENDET"))
            val lastCard = makeCard()
            lastCard.addView(makeText(
                repository.lastDeviceName.ifBlank { lastAddr },
                15f, Palette.TEXT_PRIMARY, true
            ))
            lastCard.addView(makeText(lastAddr, 11f, Palette.TEXT_MUTED, true).apply {
                setPadding(0, dp(2), 0, 0)
            })
            lastCard.addView(makePrimaryButton("SCHNELLZUGRIFF: VERBINDEN", Palette.ACCENT) {
                val known = foundDevices[lastAddr]
                if (known != null) {
                    connectToDevice(known)
                } else {
                    val btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
                    val remote = try {
                        btAdapter?.getRemoteDevice(lastAddr)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (remote == null) {
                        toast("Gespeicherte Adresse ist ungueltig \u2014 bitte neu scannen.")
                    } else {
                        connectToDevice(
                            ScooterDevice(
                                remote,
                                repository.lastDeviceName.ifBlank { lastAddr },
                                lastAddr,
                                Int.MIN_VALUE,
                                false,
                                false
                            )
                        )
                    }
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
            lastCard.addView(makeText(
                "Es wird keine Verbindung ohne dein Tippen aufgebaut. Modell- und " +
                    "Hardware-Pruefungen laufen unveraendert.",
                11f, Palette.TEXT_MUTED
            ).apply { setPadding(0, dp(6), 0, 0) })
            tabContent.addView(lastCard, cardParams())
        }

        tabContent.addView(makeSectionLabel("ERKANNTES MODELL"))
        val modelCard = makeCard()
        modelCard.addView(makeText(
            if (detectedModel.isKnown)
                "${detectedModel.displayName} — ${detectedConfidence.label}"
            else "Modell nicht erkannt (${detectedConfidence.label})",
            16f,
            when (detectedConfidence) {
                DetectionConfidence.CONFIRMED -> Palette.ACCENT_GREEN
                DetectionConfidence.PROBABLE -> Palette.ACCENT_YELLOW
                DetectionConfidence.UNKNOWN -> Palette.ACCENT_ORANGE
            },
            true
        ))
        modelCard.addView(makeText(modelEvidence(), 12f, Palette.TEXT_MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
        // PHASE 8: Hersteller, Modell und Erkennungssicherheit getrennt darstellen.
        modelCard.addView(makeText(
            "Hersteller: ${detectedManufacturer.displayName}  ·  " +
                "Modell: ${if (detectedModel.isKnown) detectedModel.displayName else "unbekannt"}  ·  " +
                "Sicherheit: ${detectedConfidence.label}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, 0) })
        modelCard.addView(makeText("Protokoll: ${activeProtocol.displayName}", 12f, Palette.TEXT_SECONDARY).apply {
            setPadding(0, dp(6), 0, 0)
        })
        // PHASE 10: Ehrlicher Support-Status — getrennt von Hersteller/Modell.
        val support = SupportMatrix.snapshot(
            detectedModel,
            adapter(),
            SupportRuntime(
                gattDiscovered = linkState == BleLinkState.READY,
                telemetryReceived = lastTelemetryAtElapsed > 0L,
                hardwareVerified = hardwareVerified,
                tuningGateOpen = hardwareVerified && detectedModel.isKnown &&
                    ProtocolAdapters.supportsTuning(detectedModel)
            ),
            detectedManufacturer
        )
        modelCard.addView(makeText(
            "Support-Status: ${support.status.label}",
            12f,
            when {
                support.status.atLeast(SupportStatus.DIAGNOSTICS_SUPPORTED) -> Palette.ACCENT_GREEN
                support.status.atLeast(SupportStatus.DISCOVERED) -> Palette.ACCENT_YELLOW
                else -> Palette.ACCENT_ORANGE
            }
        ).apply { setPadding(0, dp(6), 0, 0) })
        modelCard.addView(makeText(
            "Diagnose: ${if (support.diagnosticsSupported) "unterstützt" else "nicht verfügbar"}  ·  " +
                "Tuning: ${if (support.tuningSupported) "freigegeben" else "gesperrt"}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(4), 0, 0) })
        if (support.unavailable.isNotEmpty()) {
            modelCard.addView(makeText(
                "Nicht verfügbar: ${support.unavailable.joinToString(", ")}",
                11f, Palette.TEXT_MUTED
            ).apply { setPadding(0, dp(4), 0, 0) })
        }
        modelCard.addView(makeText(support.note, 11f, Palette.TEXT_MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        })
        // PHASE 16 §9: Ehrliche Hardware-Support-Kennzeichnung. Diese Ebenen
        // werden strikt getrennt. Eine simulierte BLE-Verbindung ist KEIN
        // Hardware-Nachweis; ohne echten Scooter-Test bleibt die oberste Stufe offen.
        val realDeviceTested = hardwareVerified
        modelCard.addView(makeText("Hardware-Nachweis (ehrlich):", 12f, Palette.TEXT_PRIMARY, true).apply {
            setPadding(0, dp(8), 0, 0)
        })
        listOf(
            "Code implementiert: ja",
            "Unit-getestet: ja (JVM-Tests, ohne echtes Geraet)",
            "BLE-Verhalten simuliert: ja (Tests, kein Hardware-Beweis)",
            "Mit echtem Geraet getestet: " + if (realDeviceTested) "ja" else "nein",
            "Mit echtem Scooter-Protokoll bestaetigt: " + if (realDeviceTested) "ja" else "nein"
        ).forEach { line ->
            modelCard.addView(makeText("\u2022 $line", 11f, Palette.TEXT_SECONDARY))
        }
        if (!realDeviceTested) {
            modelCard.addView(makeText(
                "Hinweis: Es liegt noch KEIN bestaetigter Hardware-Test vor. Das Geraet wird " +
                    "daher nicht als vollstaendig unterstuetzt bezeichnet.",
                11f, Palette.ACCENT_YELLOW
            ).apply { setPadding(0, dp(4), 0, 0) })
        }
        if (detectedModel.isKnown) {
            modelCard.addView(makeText(
                "eKFV-Hinweis: ${currentRegion.legalSpeedKmh} km/h in ${currentRegion.displayName}. " +
                    "Tuning ist im öffentlichen Verkehr unzulässig.",
                12f, Palette.ACCENT_YELLOW
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        tabContent.addView(modelCard, cardParams())

        // ---- PHASE 14 (Teil E): KOMPATIBILITAETSLEITER ----------------------
        // Es werden ausschliesslich tatsaechlich belegte Stufen angezeigt.
        // Geraet erkannt != Protokoll unterstuetzt != Telemetrie validiert.
        tabContent.addView(makeSectionLabel("KOMPATIBILITAET"))
        val compatCard = makeCard()
        // PHASE 16: Belege strenger fassen.
        // - Stufe 3 (Read-Property gefunden) und Stufe 4 (echter Read erfolgreich)
        //   sind getrennt. Eine gemeldete Read-Property ist KEIN Leseerfolg; erst
        //   ein echter onCharacteristicRead-Callback mit Bytes zaehlt.
        // - Empfangene BLE-Daten sind nicht automatisch bestaetigte Telemetrie.
        //   Es muessen plausible Kernwerte vorliegen und die Messung darf nicht
        //   veraltet sein.
        val telemetryFresh = Freshness.state(lastTelemetryAtElapsed, SystemClock.elapsedRealtime())
            .let { it == FreshnessState.FRESH || it == FreshnessState.AGING }
        val telemetryPlausible = telemetryFresh &&
            lastTelemetry?.speedKmh != null && lastTelemetry?.voltageV != null
        val compatEvidence = CompatibilityEvidence(
            detected = connectedDevice != null || lastTelemetryAtElapsed > 0L,
            gattDiscovered = htLastGatt.isNotEmpty(),
            // PHASE 16: Read-Property (Bestandsaufnahme) ist NICHT der Read-Erfolg.
            readPropertyFound = readPropertyFound,
            readOnlyTested = readSuccessCount > 0,
            telemetryValidated = telemetryPlausible,
            writeProtocolValidated = hardwareVerified && detectedModel.isKnown,
            hardwareVerified = hardwareVerified && detectedModel.isKnown
        )
        val compatReport = CompatibilityLadder.evaluate(compatEvidence, AppLanguage.DE)
        compatCard.addView(makeText(
            "Hoechste belegte Stufe: " +
                (compatReport.highest?.label(AppLanguage.DE) ?: "keine"),
            13f, if (compatReport.highest == null) Palette.TEXT_MUTED else Palette.SUCCESS, true
        ))
        compatReport.stages.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(makeText(
                if (entry.reached) "\u2713" else "\u00b7",
                13f, if (entry.reached) Palette.SUCCESS else Palette.TEXT_MUTED, true
            ), LinearLayout.LayoutParams(dp(20), WRAP))
            row.addView(makeText(
                entry.stage.label(AppLanguage.DE), 12.5f,
                if (entry.reached) Palette.TEXT_PRIMARY else Palette.TEXT_MUTED
            ), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(makeText(
                entry.evidence, 10.5f,
                if (entry.reached) Palette.SUCCESS else Palette.TEXT_MUTED
            ))
            compatCard.addView(row)
        }
        compatCard.addView(makeText(
            "Nicht belegte Stufen werden nie als erreicht dargestellt. " +
                "Schreibbefehle bleiben gesperrt, solange die Schreibstufe nicht belegt ist.",
            11f, Palette.TEXT_MUTED
        ).apply { setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(8), 0, 0) })
        tabContent.addView(compatCard, cardParams())

        tabContent.addView(makeSectionLabel("DIAGNOSE"))
        val diag = makeCard()
        diag.addView(makeText(
            "MTU: ${bleEngine?.currentMtu() ?: "—"}   Queue: ${bleEngine?.queueDepth() ?: 0}   Link: ${linkState.name}",
            12f, Palette.TEXT_SECONDARY
        ))
        diag.addView(makeText(
            "RSSI: ${if (lastRssi == Int.MIN_VALUE) "—" else "$lastRssi dBm"}   Adapter: ${adapter().displayName}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(5), 0, 0) })
        // PHASE 14 (Teil E): Veraltete Messwerte werden klar gekennzeichnet.
        val freshState = Freshness.state(
            lastTelemetryAtElapsed.takeIf { it > 0L },
            SystemClock.elapsedRealtime()
        )
        val freshAge = Freshness.ageSeconds(
            lastTelemetryAtElapsed.takeIf { it > 0L },
            SystemClock.elapsedRealtime()
        )
        diag.addView(makeText(
            "Telemetrie: ${freshState.label(AppLanguage.DE)}" +
                (freshAge?.let { "  ·  vor ${it}s" } ?: ""),
            12f,
            when (freshState) {
                FreshnessState.FRESH -> Palette.SUCCESS
                FreshnessState.AGING -> Palette.WARNING
                FreshnessState.STALE -> Palette.DANGER
                FreshnessState.NEVER -> Palette.TEXT_MUTED
            },
            true
        ).apply { setPadding(0, dp(5), 0, 0) })
        diag.addView(makeText(
            "Telemetrie-Offsets sind NICHT am realen GT3 Max verifiziert. " +
                "Nur tatsaechlich empfangene Frames werden angezeigt; DEMO-Werte sind als DEMO markiert.",
            11f, Palette.ACCENT_YELLOW
        ).apply { setPadding(0, dp(5), 0, 0) })
        val dumpButton = makePrimaryButton("GATT-DIAGNOSE (LOG)", Palette.SURFACE_HIGH) {
            val e = bleEngine
            if (e == null) {
                addLog(LogCategory.ERROR, "GATT-Diagnose: keine Engine (nicht verbunden).")
            } else {
                e.dumpGatt("manuell")
            }
        }
        diag.addView(dumpButton, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            setMargins(0, dp(8), 0, 0)
        })
        // PHASE 11: Schreib-/Read-only-Status ehrlich anzeigen.
        diag.addView(makeText(
            WriteGuard.statusLine(detectedModel, hardwareVerified, adapter()),
            12f,
            if (WriteGuard.evaluate(detectedModel, hardwareVerified, adapter()).allowed)
                Palette.ACCENT_GREEN else Palette.ACCENT_ORANGE
        ).apply { setPadding(0, dp(8), 0, 0) })
        diag.addView(makeText(
            "Hardware-Test: ${hardwareTest.frameCount()} Frames, " +
                "${hardwareTest.errorCount()} Fehler, ${hardwareTest.scanResults().size} Scan-Treffer erfasst.",
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, 0) })
        // PHASE 11: Exportierbarer Hardware-Testbericht.
        diag.addView(makePrimaryButton("TESTBERICHT ALS JSON EXPORTIEREN", Palette.SURFACE_HIGH) {
            exportHardwareReport(csv = false)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        diag.addView(makePrimaryButton("TESTBERICHT ALS CSV EXPORTIEREN", Palette.SURFACE_HIGH) {
            exportHardwareReport(csv = true)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        diag.addView(makePrimaryButton("ZUSAMMENFASSUNG KOPIEREN", Palette.TEXT_SECONDARY) {
            copyHardwareSummary()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        tabContent.addView(diag, cardParams())

        // ---- PHASE 12.7 D: SYSTEM-STATUS UEBERSICHT -------------------------
        // Sieben Zeilen, jeweils mit Statuswort (OK/OFFEN) - die Information
        // haengt damit nicht allein an der Farbe (Barrierefreiheit).
        tabContent.addView(makeSectionLabel("SYSTEM-STATUS"))
        val sysCard = makeCard()
        val btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        val gattKnown = htLastGatt.any { svc ->
            adapter().capabilities.gattServiceUuids.any { it.equals(svc.uuid, ignoreCase = true) }
        }
        val protocolVerified = hardwareVerified && detectedModel.isKnown
        val statusRows = SystemStatus.build(
            SystemStatusInput(
                bluetoothAvailable = btAdapter?.isEnabled == true,
                permissionsGranted = missingPermissions().isEmpty(),
                connected = engineReady,
                gattServiceKnown = gattKnown,
                protocolVerified = protocolVerified,
                telemetryAvailable = lastTelemetryAtElapsed > 0L,
                diagnosticsPossible = engineReady && gattKnown
            )
        )
        statusRows.forEach { row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            line.addView(makeText(row.label, 12f, Palette.TEXT_SECONDARY),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            line.addView(makeText("${row.stateWord} \u00b7 ${row.detail}", 12f,
                if (row.ok) Palette.SUCCESS else Palette.WARNING, true))
            sysCard.addView(line)
        }
        tabContent.addView(sysCard, cardParams())

        // ---- PHASE 14 (Teil D): TECHNIK IN DEN ENTWICKLERBEREICH ------------
        // Die frueheren Abschnitte GATT-STRUKTUR, VERBINDUNGSQUALITAET,
        // DIAGNOSE-HISTORIE und LOG-TERMINAL sind fuer normale Nutzer nicht
        // noetig. Sie bleiben vollstaendig erhalten, sind aber nur noch im
        // geschuetzten Entwicklerbereich sichtbar.
        tabContent.addView(makeSectionLabel("ENTWICKLERBEREICH"))
        val devCard = makeCard()
        devCard.addView(makeText(
            "GATT-Struktur, Verbindungsqualitaet, Diagnose-Historie und das Log-Terminal " +
                "sind technische Rohdaten. Sie bleiben fuer Entwicklung und Hardware-Tests " +
                "erhalten und sind hier gebuendelt.",
            12f, Palette.TEXT_SECONDARY
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        devCard.addView(makeOutlineButton("ENTWICKLERBEREICH OEFFNEN", Palette.TEXT_SECONDARY) {
            requestDeveloperAccess { showDeveloperArea() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })
        tabContent.addView(devCard, cardParams())
    }

    /* ------------------------------------------------------------------ */
    /*  PHASE 14 (Teil D): GESCHUETZTER ENTWICKLERBEREICH                 */
    /* ------------------------------------------------------------------ */
    /**
     * Interne Diagnose- und Hardware-Ansichten (GATT-Struktur, Verbindungs-
     * qualitaet, Diagnose-Historie, Log-Terminal) sind fuer normale Nutzer
     * nicht noetig. Sie werden NICHT geloescht, sondern hier gebuendelt und
     * nur nach bewusster Freigabe erreichbar (PIN, falls gesetzt). Der
     * Rohdaten-Export fuer Entwicklung und Hardware-Tests bleibt erhalten.
     */
    private fun requestDeveloperAccess(onGranted: () -> Unit) {
        if (repository.hasPin) {
            val input = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "PIN"
                setSingleLine()
            }
            AlertDialog.Builder(this)
                .setTitle("Entwicklerbereich \u00b7 PIN-geschuetzt")
                .setMessage(
                    "Dieser Bereich enthaelt technische Rohdaten (GATT, Logs, Historie). " +
                        "Zugang ist durch die gesetzte PIN geschuetzt."
                )
                .setView(input)
                .setPositiveButton("Oeffnen") { _, _ ->
                    if (repository.verifyPin(input.text.toString())) onGranted()
                    else toast("Falsche PIN.")
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Entwicklerbereich \u00b7 KEIN PIN gesetzt")
                .setMessage(
                    "Technische Rohdaten fuer Entwicklung und Hardware-Tests: " +
                        "GATT-Struktur, Verbindungsqualitaet, Diagnose-Historie und Log-Terminal.\n\n" +
                        "WICHTIG: Es ist keine PIN gesetzt. Dieser Dialog ist nur eine " +
                        "Bestaetigung und KEIN echter PIN-Schutz. Fuer echten Schutz bitte " +
                        "in den Einstellungen eine PIN vergeben."
                )
                .setPositiveButton("Ohne PIN-Schutz oeffnen") { _, _ -> onGranted() }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun showDeveloperArea() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        header.addView(makeOutlineButton("ZURUECK", Palette.TEXT_SECONDARY) {
            showTab(Tab.INFO)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        header.addView(makeText("ENTWICKLERBEREICH", 16f, Palette.TEXT_PRIMARY, true).apply {
            letterSpacing = 0.06f
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        body.addView(header)
        body.addView(makeText(
            "Technische Rohdaten. Nur fuer Entwicklung und Hardware-Tests - " +
                "nicht Teil des normalen Nutzerbetriebs.",
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(0, 0, 0, dp(12)) })

        // ---- PHASE 12.4: GATT-STRUKTUR --------------------------------------
        body.addView(makeSectionLabel("GATT-STRUKTUR"))
        val gattCard = makeCard()
        if (htLastGatt.isEmpty()) {
            gattCard.addView(makeText(
                "Noch kein GATT-Baum gelesen. Struktur erscheint nach erfolgreicher Service-Discovery.",
                12f, Palette.TEXT_MUTED
            ))
        } else {
            htLastGatt.take(12).forEach { svc ->
                gattCard.addView(makeText(
                    "Service ${svc.uuid}" + if (svc.label.isNotBlank()) " (${svc.label})" else "",
                    12f, Palette.ACCENT, true
                ).apply { setPadding(0, dp(6), 0, 0) })
                svc.characteristics.take(10).forEach { ch ->
                    gattCard.addView(makeText(
                        "  [${ch.shortFlags()}] ${ch.uuid}",
                        11f, Palette.TEXT_SECONDARY, true
                    ))
                }
                if (svc.characteristics.size > 10) {
                    gattCard.addView(makeText(
                        "  ... ${svc.characteristics.size - 10} weitere Characteristics",
                        11f, Palette.TEXT_MUTED
                    ))
                }
            }
            if (htLastGatt.size > 12) {
                gattCard.addView(makeText(
                    "... ${htLastGatt.size - 12} weitere Services", 11f, Palette.TEXT_MUTED
                ))
            }
        }
        gattCard.addView(makeText(
            "Legende: R=Read, W=Write, N=Notify, I=Indicate. Properties werden so angezeigt, " +
                "wie sie das Geraet meldet - nichts wird ergaenzt.",
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(8), 0, 0) })
        // PHASE 15: echte GATT-Auswertung aus den tatsaechlich gelesenen Daten.
        val gattAnalysis = GattAnalyzer.analyze(htLastGatt.map { svc ->
            ServiceFacts(
                uuid = svc.uuid,
                label = svc.label,
                characteristics = svc.characteristics.map { ch ->
                    CharacteristicFacts(
                        uuid = ch.uuid,
                        readable = ch.readable,
                        writable = ch.writable,
                        notifying = GattProperty.NOTIFY in ch.properties,
                        indicating = GattProperty.INDICATE in ch.properties
                    )
                }
            )
        })
        gattCard.addView(makeText(
            "Auswertung: ${gattAnalysis.services} Services, ${gattAnalysis.characteristics} Characteristics " +
                "\u00b7 lesbar ${gattAnalysis.readable} \u00b7 schreibbar ${gattAnalysis.writable} " +
                "\u00b7 notify ${gattAnalysis.notifying} \u00b7 indicate ${gattAnalysis.indicating}",
            11f, Palette.TEXT_SECONDARY, true
        ).apply { setPadding(0, dp(8), 0, 0) })
        gattCard.addView(makeText(
            "Vollstaendigkeit: ${gattAnalysis.completeness.label}",
            11f, if (gattAnalysis.completeness == GattCompleteness.COMPLETE) Palette.SUCCESS else Palette.WARNING
        ))
        gattAnalysis.notes.forEach { note ->
            gattCard.addView(makeText(note, 11f, Palette.TEXT_MUTED))
        }
        // PHASE 16: UUID-Herkunft aus der zentralen Registry (BleUuids).
        gattCard.addView(makeText(
            "UUID-Registry: ${BleUuids.all().size} Definitionen, davon " +
                "${BleUuids.unprovenCount()} ohne externen Beleg, " +
                "${BleUuids.confirmedOnHardwareCount()} am realen Geraet bestaetigt.",
            11f, Palette.TEXT_SECONDARY, true
        ).apply { setPadding(0, dp(8), 0, 0) })
        BleUuids.transportContradictions().forEach { line ->
            gattCard.addView(makeText(line, 11f, Palette.WARNING))
        }
        body.addView(gattCard, cardParams())

        // ---- PHASE 12.7 C: VERBINDUNGSQUALITAET ------------------------------
        body.addView(makeSectionLabel("VERBINDUNGSQUALITAET"))
        val qualityCard = makeCard()
        val sig = if (lastRssi == Int.MIN_VALUE) null else SignalQualityScale.fromRssi(lastRssi)
        qualityCard.addView(makeText(
            "Signal: " + (sig?.let { "${it.accessibleLabel} (${lastRssi} dBm)" } ?: "Nicht verfuegbar"),
            12f, if (sig == null) Palette.TEXT_MUTED else Palette.TEXT_PRIMARY, true
        ))
        // PHASE 15: echte Messreihe statt Einzelwert.
        val linkSummary = LinkQuality.summarize(
            rssiSamples.toList(), SystemClock.elapsedRealtime()
        )
        qualityCard.addView(makeText(
            if (linkSummary.available)
                "Messreihe: ${linkSummary.samples} Werte \u00b7 " +
                    "\u00d8 ${linkSummary.avgDbm} dBm \u00b7 min ${linkSummary.minDbm} / max ${linkSummary.maxDbm} dBm"
            else "Messreihe: noch keine RSSI-Werte erfasst.",
            11f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(4), 0, 0) })
        qualityCard.addView(makeText(
            "Qualitaet: ${linkSummary.label} \u00b7 Tendenz: ${linkSummary.trend.label}" +
                if (linkSummary.stale) " \u00b7 Messung veraltet" else "",
            11f, when {
                !linkSummary.available -> Palette.TEXT_MUTED
                linkSummary.stale -> Palette.WARNING
                else -> Palette.TEXT_PRIMARY
            }
        ))
        qualityCard.addView(makeText(
            "MTU: ${bleEngine?.currentMtu() ?: 0}  \u00b7  " +
                "Verbunden seit: " + (htConnectedAt.takeIf { it > 0 && engineReady }?.let {
                    TripClock.format(SystemClock.elapsedRealtime() - it)
                } ?: "\u2014"),
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(5), 0, 0) })
        qualityCard.addView(makeText(
            "Abbrueche in dieser Sitzung: $disconnectCount  \u00b7  " +
                "Kommunikationsfehler: $commErrorCount",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(5), 0, 0) })
        val unstable = disconnectCount > 0 || commErrorCount > 5
        qualityCard.addView(makeText(
            if (unstable) "Hinweis: Verbindung war in dieser Sitzung instabil."
            else "Hinweis: Keine Instabilitaet beobachtet.",
            11f, if (unstable) Palette.WARNING else Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        body.addView(qualityCard, cardParams())

        // ---- PHASE 12.7 B: DIAGNOSE-HISTORIE --------------------------------
        body.addView(makeSectionLabel("DIAGNOSE-HISTORIE"))
        val histCard = makeCard()
        val history = DiagnosticHistoryCodec.decode(repository.diagnosticHistoryRaw)
        if (history.isEmpty()) {
            histCard.addView(makeText("Noch keine gespeicherten Berichte.", 12f, Palette.TEXT_MUTED))
        } else {
            history.takeLast(5).reversed().forEach { e ->
                val date = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
                    .format(java.util.Date(e.timestampMs))
                histCard.addView(makeText(
                    "$date  \u00b7  ${e.model}  \u00b7  ${e.supportStatus}",
                    12f, Palette.TEXT_PRIMARY, true
                ))
                histCard.addView(makeText(
                    "Fehler ${e.errors}  \u00b7  Warnungen ${e.warnings}  \u00b7  ${e.summary}",
                    11f, Palette.TEXT_MUTED
                ).apply { setPadding(0, 0, 0, dp(6)) })
            }
        }
        histCard.addView(makePrimaryButton("AKTUELLEN BERICHT SPEICHERN", Palette.ACCENT) {
            saveDiagnosticHistory()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        if (history.size >= 2) {
            histCard.addView(makePrimaryButton("LETZTE ZWEI BERICHTE VERGLEICHEN", Palette.SURFACE_HIGH) {
                val old = history[history.size - 2]
                val new = history[history.size - 1]
                AlertDialog.Builder(this)
                    .setTitle("Berichtsvergleich")
                    .setMessage(DiagnosticHistoryCodec.compare(old, new))
                    .setPositiveButton("OK", null)
                    .show()
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        }
        if (history.isNotEmpty()) {
            histCard.addView(makeOutlineButton("HISTORIE LOESCHEN", Palette.WARNING) {
                repository.diagnosticHistoryRaw = ""
                showDeveloperArea()
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        }
        body.addView(histCard, cardParams())

        body.addView(makeSectionLabel("LOG-TERMINAL"))
        body.addView(buildLogCard(), cardParams())

        scroll.addView(body)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))
    }

    /**
     * PHASE 12.3: Verbindungszustand in klar getrennten Stufen.
     * Wichtig: "Verbunden" und "Diagnose bereit" sind NICHT dasselbe.
     */
    private fun connectionStage(): Pair<String, Int> = when {
        engineReady && detectedModel.isKnown && hardwareVerified ->
            "Diagnose bereit" to Palette.SUCCESS
        engineReady && !detectedModel.isKnown ->
            "Diagnose bereit \u00b7 Modell unbekannt" to Palette.WARNING
        engineReady ->
            "Diagnose bereit \u00b7 Protokoll nicht verifiziert" to Palette.WARNING
        linkState == BleLinkState.CONNECTED ->
            "Services werden untersucht" to Palette.ACCENT
        linkState == BleLinkState.CONNECTING ->
            "Verbindung wird hergestellt" to Palette.ACCENT
        lastConnectFailed ->
            "Verbindung fehlgeschlagen" to Palette.DANGER
        isScanning ->
            "Suche l\u00e4uft" to Palette.ACCENT
        connectedDevice != null ->
            "Ger\u00e4t erkannt" to Palette.ACCENT
        else ->
            "Nicht verbunden" to Palette.TEXT_MUTED
    }

    private fun refreshConnectionStage() {
        val (label, color) = connectionStage()
        connStageView?.text = label
        connStageView?.setTextColor(color)
    }

    private fun modelEvidence(): String {
        val name = connectedDevice?.name ?: foundDevices.values.firstOrNull()?.name
        if (name.isNullOrBlank()) {
            return "Kein Gerätename vorhanden — es wird bewusst kein Modell angenommen."
        }
        val det = ScooterModel.detectDetailed(name, connectedDevice?.address ?: "")
        return if (det.model.isKnown) {
            "Vertrauensstufe: ${det.confidence.label} (${det.evidence}). " +
                "${det.confidence.description} Reine Namensheuristik — keine Hardware-Verifikation."
        } else {
            "\"$name\" enthält kein eindeutiges Modell-Token. " +
                "Ein Gerät mit \"NAVEE\" im Namen wird NICHT automatisch als GT3 Max eingestuft."
        }
    }

    private fun missingPermissions(): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
        val bt = bluetoothAdapter
        if (bt == null) {
            toast("Bluetooth ist auf diesem Gerät nicht verfügbar.")
            return
        }
        if (!bt.isEnabled) {
            toast("Bitte Bluetooth einschalten.")
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        foundDevices.clear()
        updateDeviceList()
        isScanning = true
        if (::scanButton.isInitialized) {
            scanButton.isEnabled = false
            scanButton.text = "SCAN LÄUFT…"
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bt.bluetoothLeScanner?.startScan(null, settings, leScanCallback)
            addLog(LogCategory.INFO, "BLE-Scan gestartet (12 s).")
        } catch (e: SecurityException) {
            isScanning = false
            toast("Scan-Berechtigung fehlt.")
            addLog(LogCategory.ERROR, "Scan verweigert: ${e.message}")
        }
        val scanGeneration = scanTimeoutGuard.begin()
        scanTimeoutRunnable = Runnable {
            // PHASE 5 FIX: Nur der Timeout des AKTUELLEN Scans darf wirken.
            // Ein veraltetes Runnable (aus einem frueheren Scan) wird verworfen,
            // statt einen neuen Scan vorzeitig zu beenden.
            if (!scanTimeoutGuard.isCurrent(scanGeneration)) return@Runnable
            safeStopBleScan()
            addLog(LogCategory.INFO, "BLE-Scan beendet.")
        }
        // Nur planen, wenn der Scan wirklich laeuft (z. B. kein Start nach
        // verweigerter Berechtigung).
        if (isScanning) {
            handler.postDelayed(scanTimeoutRunnable!!, 12_000L)
        }
    }

    private fun updateDeviceList() {
        if (!::deviceListContainer.isInitialized) return
        deviceListContainer.removeAllViews()

        val all = foundDevices.values.toList()
        val classified = all.map { it to ScanClassifier.classify(it.name, it.address) }
        val scooterLike = classified.filter { it.second.isScooterLike }
        val unknown = classified.filter { !it.second.isScooterLike }

        scanStatusView?.text = when {
            isScanning -> "Suche laeuft\u2026 ${all.size} Geraet(e), davon ${scooterLike.size} Scooter-Kandidat(en)."
            all.isEmpty() -> "Noch keine Geraete gefunden."
            else -> "${all.size} Geraet(e): ${scooterLike.size} Scooter-Kandidat(en), ${unknown.size} sonstige."
        }

        val visible = if (showAllDevices) classified else scooterLike
        val filtered = manufacturerFilter?.let { f ->
            visible.filter { it.second.brandLabel.equals(f, ignoreCase = true) }
        } ?: visible

        if (filtered.isEmpty()) {
            deviceListContainer.addView(makeText(
                when {
                    all.isEmpty() && isScanning -> "Suche laeuft\u2026"
                    all.isEmpty() -> "Noch keine Geraete gefunden."
                    else -> "Keine Treffer fuer die aktuelle Filterung. 'ALLE GERAETE' zeigt auch Unbekanntes."
                },
                12f, Palette.TEXT_MUTED
            ).apply { setPadding(0, dp(8), 0, 0) })
            return
        }

        // Scooter-Kandidaten zuerst, danach (optional) unbekannte Geraete.
        val ordered = filtered.sortedWith(
            compareByDescending<Pair<ScooterDevice, ScanClassification>> { it.second.category == ScanCategory.KNOWN_SCOOTER }
                .thenByDescending { it.second.isScooterLike }
                .thenByDescending { it.first.rssi }
        )
        var unknownHeaderShown = false
        for ((device, cls) in ordered) {
            if (!cls.isScooterLike && !unknownHeaderShown) {
                unknownHeaderShown = true
                deviceListContainer.addView(makeText(
                    "SONSTIGE BLUETOOTH-GERAETE (nicht als Scooter behandelt)",
                    10f, Palette.WARNING, true
                ).apply { setPadding(0, dp(12), 0, dp(4)) })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBg(Palette.SURFACE, Palette.OUTLINE, 10)
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(makeText(device.name, 14f, Palette.TEXT_PRIMARY, true),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            val sig = SignalQualityScale.fromRssi(device.rssi)
            val sigColor = when (sig.bars) {
                4, 3 -> Palette.SUCCESS
                2 -> Palette.WARNING
                else -> Palette.DANGER
            }
            titleRow.addView(makeText("${"▮".repeat(sig.bars)}${"▯".repeat(4 - sig.bars)} ${sig.label}",
                10f, sigColor, true))
            row.addView(titleRow)

            val categoryLabel = when (cls.category) {
                ScanCategory.KNOWN_SCOOTER -> "Scooter erkannt"
                ScanCategory.PROBABLE_SCOOTER -> "Scooter wahrscheinlich"
                ScanCategory.UNKNOWN_DEVICE -> "kein Scooter-Hinweis"
            }
            row.addView(makeText(
                "${device.address} \u00b7 ${device.rssi} dBm \u00b7 $categoryLabel",
                11f, Palette.TEXT_MUTED
            ).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(makeText(
                "Hersteller: ${cls.brandLabel} \u00b7 Modell: ${cls.modelLabel} \u00b7 ${cls.confidenceLabel}",
                11f, Palette.TEXT_SECONDARY
            ))

            val connected = connectedDevice?.address == device.address
            val connectButton = makePrimaryButton(
                if (connected) "VERBUNDEN" else "VERBINDEN",
                if (cls.isScooterLike) Palette.ACCENT else Palette.SURFACE_HIGH
            ) { connectToDevice(device) }
            connectButton.isEnabled = !connected
            row.addView(connectButton, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                setMargins(0, dp(8), 0, 0)
            })
            deviceListContainer.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, dp(6), 0, 0)
            })
        }
    }

    /** PHASE 12.3: Hersteller-Filter aus den tatsaechlich gefundenen Marken. */
    private fun showManufacturerFilter() {
        val brands = foundDevices.values
            .map { ScanClassifier.classify(it.name, it.address).brandLabel }
            .filter { it != "unbekannt" }
            .distinct()
            .sorted()
        if (brands.isEmpty()) {
            toast("Noch keine Hersteller erkannt \u2014 zuerst scannen.")
            return
        }
        val options = (listOf("Alle Hersteller") + brands).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Hersteller-Filter")
            .setItems(options) { _, which ->
                manufacturerFilter = if (which == 0) null else brands[which - 1]
                showTab(Tab.INFO)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: ScooterDevice) {
        val bt = device.device
        if (bt == null) {
            toast("Demo-Gerät: es wird keine echte Verbindung aufgebaut.")
            return
        }
        safeStopBleScan()
        // FIX: Ein noch geplanter Auto-Reconnect darf einen manuellen Verbindungs-
        // aufbau nicht ueberholen. Sonst feuert der alte Runnable spaeter gegen die
        // NEUE Engine und baut eine zweite, konkurrierende GATT-Verbindung auf.
        cancelReconnectRunnable()
        // PHASE 5 FIX: Beim Wechsel auf ein ANDERES Geraet darf keine zweite
        // BleEngine parallel existieren. Die bisherige Verbindung wird sauber
        // abgebaut (sonst: zwei GATT-Clients, doppelte Telemetrie, Leak).
        if (ConnectionSwitchPolicy.mustTearDownExisting(bleEngine != null)) {
            teardownActiveConnection()
        }
        val det = ScooterModel.detectDetailed(device.name, device.address)
        detectedModel = det.model
        detectedConfidence = det.confidence
        detectedManufacturer = det.manufacturer
        detectionEvidence = det.evidence
        // PHASE 8: Erkennungsergebnis nachvollziehbar protokollieren
        // (Hersteller, Modell, Sicherheit, Evidenz). Keine privaten Daten.
        addLog(
            LogCategory.INFO,
            "Erkennung: Hersteller=${det.manufacturer.displayName}, " +
                "Modell=${det.model.displayName}, Sicherheit=${det.confidence.label}, " +
                "Evidenz=${det.evidence}"
        )
        activeProtocol = detectedModel.protocol
        connectedDevice = device
        userInitiatedDisconnect = false
        reconnectAttempts = 0
        addLog(LogCategory.INFO, "Verbinde mit ${device.name} (${device.address})…")
        if (::statusText.isInitialized) statusText.text = "Verbinde…"
        val engine = BleEngine(this, this)
        bleEngine = engine
        engine.setProtocolHint(activeProtocol)
        // PHASE 10: Modell-Hinweis fuer die modellspezifische Adapter-Auswahl.
        engine.setModelHint(detectedModel)
        // PHASE 8.1 (Punkt 1) + PHASE 10: Eager-Unlock-Handshake nur bei
        // erkanntem Modell, vorhandenem MODELLSPEZIFISCHEM Adapter UND
        // bestaetigter Hardware. Sonst geht beim Verbindungsaufbau kein
        // Schreibbefehl an ein unbekanntes/fremdes Geraet.
        engine.setAutoHandshakeAllowed(
            modelKnown = detectedModel.isKnown && modelAdapterExact(),
            hardwareVerified = hardwareVerified
        )
        linkState = linkState.transition(BleLinkEvent.CONNECT_REQUESTED)
        engine.connect(bt)
        updateDeviceList()
    }

    /* ================================================================== */
    /*  TOOLS / TUNING                                                    */
    /* ================================================================== */

    private fun renderTools() {
        // PHASE 8: Sichtbarer Sperrhinweis, damit die Slider nicht faelschlich
        // so wirken, als wuerden sie sofort etwas am Scooter aendern.
        val lockReason = TuningSafetyGate.blockReason(hardwareVerified, detectedModel.isKnown)
        if (lockReason != null) {
            val banner = makeCard()
            banner.addView(makeText("TUNING GESPERRT", 14f, Palette.ACCENT_RED, true))
            banner.addView(makeText(lockReason, 11f, Palette.ACCENT_YELLOW).apply {
                setPadding(0, dp(4), 0, 0)
            })
            banner.addView(makeText(
                "Freigabe in CONFIG unter \"SICHERHEIT / TUNING-FREIGABE\".",
                11f, Palette.TEXT_MUTED
            ).apply { setPadding(0, dp(4), 0, 0) })
            tabContent.addView(banner, cardParams())
        }
        tabContent.addView(makeSectionLabel("FAHRPARAMETER"))
        val dangerCard = makeCard()
        addDangerBadge(dangerCard, "Fahrparameter veraendern das Controller-Verhalten. Nur auf eigenem Testgeraet.")
        tabContent.addView(dangerCard, cardParams())
        val maxLimit = if (detectedModel.isKnown) detectedModel.absMaxKmh.coerceAtMost(45) else 45
        tabContent.addView(makeSliderCard(
            "Max. Geschwindigkeit",
            "Speed-Ceiling am Controller. In Deutschland sind 20 km/h (eKFV) zulässig.",
            5, maxLimit, maxSpeedKmh.coerceIn(5, maxLimit), "km/h"
        ) { value ->
            maxSpeedKmh = value
            sendTuning(adapter().buildSpeedCeilingFrame(value), "maxSpeed=$value")
        }, cardParams())

        tabContent.addView(makeSliderCard(
            "Zero-Start",
            "Anfahrgeschwindigkeit ohne Gas. 0 = sofortiges Ansprechen.",
            0, 10, zeroStartKmh.coerceIn(0, 10), "km/h"
        ) { value ->
            zeroStartKmh = value
            sendTuning(adapter().buildZeroStartFrame(value), "zeroStart=$value")
        }, cardParams())

        // PHASE 12.6: erweiterte Einstellungen einklappbar.
        tabContent.addView(makeOutlineButton(
            if (showAdvancedTools) "ERWEITERTE EINSTELLUNGEN AUSBLENDEN"
            else "ERWEITERTE EINSTELLUNGEN ANZEIGEN",
            Palette.ACCENT
        ) {
            showAdvancedTools = !showAdvancedTools
            showTab(Tab.TOOLS)
        }, cardParams())

        if (showAdvancedTools) {
            tabContent.addView(makeSliderCard(
                "KERS-Rekuperation",
                "St\u00e4rke der Motorbremse / Energier\u00fcckgewinnung.",
                0, 3, kersLevel.coerceIn(0, 3), "Stufe"
            ) { value ->
                kersLevel = value
                sendTuning(adapter().buildKersFrame(value), "kers=$value")
            }, cardParams())

            tabContent.addView(makeSliderCard(
                "Motor-Temperaturschutz",
                "Grenzwert, ab dem die Leistung reduziert wird.",
                50, 90, motorTempGuardC.coerceIn(50, 90), "\u00b0C"
            ) { value ->
                motorTempGuardC = value
                sendTuning(adapter().buildTempGuardFrame(value), "tempGuard=$value")
            }, cardParams())
        }

        tabContent.addView(makeSectionLabel("SCHALTER"))
        val toggles = makeCard()
        toggles.addView(makeToggleRow(
            "Cruise Control", "Hält die Geschwindigkeit auf Tastendruck.", cruiseEnabled
        ) { value ->
            cruiseEnabled = value
            sendTuning(adapter().buildCruiseFrame(value), "cruise=$value")
        })
        toggles.addView(makeToggleRow(
            "Diebstahl-Sperre", "Sperrt den Motor elektronisch.", isLocked
        ) { value ->
            isLocked = value
            repository.isLocked = value
            sendTuning(adapter().buildLockFrame(value), "lock=$value")
        })
        toggles.addView(makeToggleRow(
            "Übergeschwindigkeits-Alarm stumm", "Unterdrückt den akustischen Alarm.", overspeedAlarmMuted
        ) { value ->
            overspeedAlarmMuted = value
            sendTuning(adapter().buildOverspeedMuteFrame(value), "overspeedMute=$value")
        })
        toggles.addView(makeToggleRow(
            "Motor-Temp.-Guard aktiv", "Reduziert die Leistung bei Überhitzung.", motorTempGuardEnabled
        ) { value -> motorTempGuardEnabled = value })
        tabContent.addView(toggles, cardParams())

        tabContent.addView(makeSectionLabel("PROFILE & SPEZIALFUNKTIONEN"))
        val special = makeCard()
        special.addView(makeText(
            "German Maneuver ist ein volatiler RAM-Eingriff. Er kann die Betriebserlaubnis " +
                "erlöschen lassen und wird nur auf eigenem Testgerät empfohlen.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, 0, 0, dp(8)) })
        special.addView(makePrimaryButton("GERMAN MANEUVER (VOLATIL)", Palette.ACCENT_ORANGE) {
            confirmGermanManeuver()
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        special.addView(makePrimaryButton("POLIZEI-MODUS (LEGALES LIMIT)", Palette.SURFACE_HIGH) {
            confirmPoliceMode()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        special.addView(makePrimaryButton("PANIK / NOTAUS-RAMPE", Palette.ACCENT_RED) {
            confirmPanic()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        tabContent.addView(special, cardParams())

        tabContent.addView(makeSectionLabel("FAHRMODUS"))
        val ride = makeCard()
        val rideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (mode in RideMode.values()) {
            val button = makePrimaryButton(
                mode.displayName,
                if (mode == rideMode) Palette.ACCENT else Palette.SURFACE_HIGH
            ) {
                rideMode = mode
                applyRideMode(mode)
                showTab(Tab.TOOLS)
            }
            rideRow.addView(button, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        ride.addView(rideRow)
        ride.addView(makeText(rideMode.description, 12f, Palette.TEXT_MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
        tabContent.addView(ride, cardParams())

        tabContent.addView(makeText(
            "Hinweis: Alle Schreibbefehle werden nur gesendet, wenn eine echte BLE-Verbindung " +
                "besteht und der Adapter sie unterstützt. Ohne ACK wird der Befehl als unsicher " +
                "protokolliert.",
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    private fun applyRideMode(mode: RideMode) {
        when (mode) {
            RideMode.NORMAL -> sendTuning(adapter().buildSpeedCeilingFrame(maxSpeedKmh), "ride=normal")
            RideMode.ECO_SAVER -> {
                val ecoLimit = currentRegion.legalSpeedKmh.coerceAtMost(maxSpeedKmh)
                sendTuning(adapter().buildSpeedCeilingFrame(ecoLimit), "ride=eco")
            }
            RideMode.SPORT_PLUS -> sendTuning(adapter().buildMaxPowerFrame(), "ride=sport+")
        }
        addLog(LogCategory.INFO, "Fahrmodus: ${mode.displayName}")
    }

    private fun confirmGermanManeuver() {
        AlertDialog.Builder(this)
            .setTitle("German Maneuver")
            .setMessage(
                "Dieser Befehl ist ein volatiler RAM-Eingriff. Er kann die Betriebserlaubnis " +
                    "erlöschen lassen. Nur auf eigenem Testgerät und nicht im öffentlichen " +
                    "Verkehr ausführen. Fortfahren?"
            )
            .setPositiveButton("Ausführen") { _, _ ->
                sendTuning(adapter().buildGermanManeuverFrame(), "germanManeuver")
                germanManeuverActive = true
                addLog(LogCategory.INFO, "German Maneuver gesendet (volatil, nicht verifiziert).")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /**
     * PHASE 12.6: Polizei-Modus als rechtliches Limit — EIN Tipp, keine
     * zusaetzliche Bestaetigung, WENN alle Sicherheitsvoraussetzungen erfuellt
     * sind. Fehlt eine Voraussetzung, gibt es KEINE Erfolgsmeldung, sondern
     * eine klare Ablehnung. Der Schreibbefehl laeuft weiterhin ueber
     * [sendTuning] -> [WriteGuard] -> [TuningSafetyGate]; es wird nichts umgangen.
     */
    private fun confirmPoliceMode() {
        val engine = bleEngine
        val hasWritableLink = engineReady && engine?.hasWriteCharacteristic() == true
        val block = tuningBlockReason()
        if (!hasWritableLink) {
            toast("Polizei-Modus nicht moeglich: keine aktive, schreibbare Verbindung.")
            addLog(LogCategory.ERROR, "Polizei-Modus abgelehnt: keine schreibbare Verbindung.")
            return
        }
        if (block != null) {
            toast("Polizei-Modus gesperrt: $block")
            addLog(LogCategory.ERROR, "Polizei-Modus gesperrt: $block")
            return
        }
        val limit = currentRegion.legalSpeedKmh
        val sent = sendTuning(
            adapter().buildPoliceModeFrame(limit),
            "policeMode=$limit"
        )
        if (sent) {
            maxSpeedKmh = limit
            addLog(LogCategory.INFO, "Polizei-Modus auf $limit km/h angefordert (${currentRegion.displayName}).")
            toast("Polizei-Modus: $limit km/h angefordert.")
        } else {
            addLog(LogCategory.ERROR, "Polizei-Modus NICHT gesendet (Tuning gesperrt).")
            toast("Polizei-Modus konnte nicht gesendet werden.")
        }
    }

    private fun confirmPanic() {
        AlertDialog.Builder(this)
            .setTitle("Panik / Notaus")
            .setMessage(
                "Sendet sofort einen Emergency-Cut und rampt das Speed-Ceiling auf " +
                    "${RAMP_CUT_SPEED_KMH.toInt()} km/h. Dies ersetzt NICHT die mechanische Bremse. " +
                    "Ausführen?"
            )
            .setPositiveButton("NOTAUS") { _, _ -> beginSafeStopRamp("Manueller Panik-Button") }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /**
     * PHASE 8: Tuning-Schreibbefehl mit Sicherheitssperre.
     *
     * Bis echte Hardwaretests/Protokollvalidierung vorliegen, wird NICHTS
     * gesendet, solange nicht [TuningSafetyGate] freigibt. Die Funktion selbst
     * bleibt vollstaendig erhalten — sie ist nur gesperrt, nicht entfernt.
     * Not-Aus, Unlock, Reboot und Lese-Befehle laufen weiter ueber [sendCommand].
     */
    private fun sendTuning(frame: ByteArray, label: String): Boolean {
        val reason = tuningBlockReason()
        if (reason != null) {
            toast("Tuning gesperrt: $reason")
            addLog(LogCategory.ERROR, "Tuning-Befehl '$label' blockiert: $reason")
            return false
        }
        sendCommand(frame, label, true)
        return true
    }

    private fun sendCommand(
        frame: ByteArray,
        label: String,
        requiresUnlock: Boolean,
        silent: Boolean = false
    ) {
        val engine = bleEngine
        if (engine == null || !engineReady) {
            if (!silent) toast("Keine aktive Verbindung — Befehl nicht gesendet.")
            return
        }
        val queued = engine.enqueueCommand(frame, label, requiresUnlock)
        if (!queued) {
            // PHASE 12.4: stille Polls duerfen keine Fehlerflut erzeugen.
            if (!silent) addLog(LogCategory.ERROR, "Befehl '$label' konnte nicht eingereiht werden.")
        } else if (!silent) {
            addLog(LogCategory.TX, "$label → ${ScooterFrames.toHex(frame)}")
        }
    }

    /* ================================================================== */
    /*  DASHBOARD / TELEMETRIE                                            */
    /* ================================================================== */

    private fun metricRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(makeText(label, 12f, Palette.TEXT_MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = makeText("—", 14f, Palette.TEXT_PRIMARY, true)
        row.addView(value)
        parent.addView(row)
        return value
    }

    /* ------------------------------------------------------------------ */
    /*  PHASE 9 — Diagnose-Helfer                                          */
    /* ------------------------------------------------------------------ */

    /** Baut den Diagnosebericht aus den tatsaechlich vorhandenen Daten. */
    private fun buildDiagnosticsReport(): DiagnosticsReport {
        val live = engineReady || demoActive
        return DiagnosticsBuilder.build(
            DiagnosticsInput(
                telemetry = lastTelemetry,
                model = detectedModel,
                protocol = if (live) activeProtocol else null,
                connected = live,
                lastTelemetryAtMillis = lastTelemetryAtElapsed.takeIf { it > 0L },
                nowMillis = SystemClock.elapsedRealtime(),
                commErrorCount = commErrorCount,
                isDemo = demoActive
            )
        )
    }

    /**
     * Formatiert einen diagnostischen Wert inklusive ehrlichem Status.
     * Nicht verfuegbare Werte werden NIE durch Platzhalter-Zahlen ersetzt.
     */
    private fun formatDiag(v: DiagnosticValue<*>, unit: String = "", decimals: Int = 1): String {
        val raw = v.value ?: return v.status.label
        val num = when (raw) {
            is Float -> String.format(Locale.US, "%.${decimals}f", raw)
            is Double -> String.format(Locale.US, "%.${decimals}f", raw)
            else -> raw.toString()
        }
        val text = if (unit.isEmpty()) num else "$num $unit"
        return when (v.status) {
            DataStatus.OK -> text
            DataStatus.STALE -> "$text (veraltet)"
            DataStatus.INVALID -> "$text (ungültig)"
            DataStatus.UNKNOWN_PROTOCOL -> "${v.status.label}: $text"
            DataStatus.NOT_AVAILABLE -> v.status.label
        }
    }

    /** Zeigt das Diagnose-Ereignisprotokoll (read-only) in einem Dialog. */
    private fun showDiagnosticLogDialog() {
        val events = diagnosticLog.snapshot()
        val message = if (events.isEmpty()) {
            "Noch keine Diagnose-Ereignisse aufgezeichnet."
        } else {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            events.takeLast(100).joinToString("\n") { e ->
                "${fmt.format(Date(e.timestampMillis))} [${e.severity.label}] ${e.code}: ${e.message}"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Diagnose-Log (${events.size})")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("LEEREN") { _, _ ->
                diagnosticLog.clear()
                commErrorCount = 0
                updateDashboardUi()
            }
            .show()
    }

    private fun renderDashboard() {
        // ---- PHASE 12.5: COCKPIT -------------------------------------------
        // Der Tacho zeigt ausschliesslich echte Messwerte. Ohne Daten steht
        // dort "Nicht verfuegbar" - es gibt keine erfundene Anzeige.
        val cockpit = makeCard()
        dashGauge = CockpitGaugeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(170))
            setUnavailable("Geschwindigkeit")
        }
        cockpit.addView(dashGauge)
        val sparkRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        sparkRow.addView(makeText("GESCHWINDIGKEIT (VERLAUF)", 10f, Palette.TEXT_MUTED))
        speedSpark = SparklineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(56))
            setValues(emptyList(), 1f, Palette.ACCENT, "Noch keine Messwerte")
        }
        sparkRow.addView(speedSpark)
        sparkRow.addView(makeText("LEISTUNG (VERLAUF)", 10f, Palette.TEXT_MUTED))
        powerSpark = SparklineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(56))
            setValues(emptyList(), 1f, Palette.ACCENT_BLUE, "Noch keine Messwerte")
        }
        sparkRow.addView(powerSpark)
        dashHistoryMinMax = makeText("Verlauf: min \u2014 \u00b7 max \u2014", 11f, Palette.TEXT_MUTED, true)
        sparkRow.addView(dashHistoryMinMax, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(0, dp(6), 0, 0)
        })
        cockpit.addView(sparkRow)
        tabContent.addView(cockpit, cardParams())

        tabContent.addView(makeSectionLabel("LIVE-TELEMETRIE"))
        val live = makeCard()
        dashSpeed = metricRow(live, "Geschwindigkeit")
        dashPower = metricRow(live, "Leistung")
        dashVoltage = metricRow(live, "Spannung")
        dashCurrent = metricRow(live, "Strom")
        dashBattery = metricRow(live, "Akku")
        dashMotorTemp = metricRow(live, "Motor-Temperatur")
        dashCellMin = metricRow(live, "Zelle min")
        dashCellMax = metricRow(live, "Zelle max")
        dashCellDelta = metricRow(live, "Zell-Delta")
        dashTempGuardState = metricRow(live, "Temp-Guard")
        dashFeedState = metricRow(live, "Datenquelle")
        tabContent.addView(live, cardParams())

        tabContent.addView(makeSectionLabel("TRIP-COMPUTER"))
        val tripCard = makeCard()
        dashTrip = metricRow(tripCard, "Strecke")
        dashTripTime = metricRow(tripCard, "Fahrtzeit")
        dashRange = metricRow(tripCard, "Reichweite (Schätzung)")
        dashVmax = metricRow(tripCard, "Vmax")
        dashVavg = metricRow(tripCard, "Vavg")
        dashWh = metricRow(tripCard, "Energie")
        dashSoh = metricRow(tripCard, "SoH")
        dashCycles = metricRow(tripCard, "Zyklen")
        tabContent.addView(tripCard, cardParams())

        // ---- PHASE 9: PROFESSIONELLE DIAGNOSE -------------------------------
        // Alle Werte stammen ausschliesslich aus tatsaechlich empfangenen Daten.
        // Nicht belegte Felder (Drehzahl, Firmware, Controller-Status) werden
        // ausdruecklich als "Nicht verfuegbar" angezeigt — nichts wird erfunden.
        tabContent.addView(makeSectionLabel("DIAGNOSE"))

        val diagSystem = makeCard()
        diagOverall = metricRow(diagSystem, "Gesamtstatus")
        diagConnection = metricRow(diagSystem, "Verbindung")
        diagProtocol = metricRow(diagSystem, "Protokoll")
        diagDataAge = metricRow(diagSystem, "Datenalter")
        diagCommErrors = metricRow(diagSystem, "Kommunikationsfehler")
        tabContent.addView(diagSystem, cardParams())

        // PHASE 16.2: Empfangsdiagnose — macht sichtbar, WO Telemetrie verloren geht.
        val diagRx = makeCard()
        diagRxNotifications = metricRow(diagRx, "Notifications empfangen")
        diagRxBytes = metricRow(diagRx, "Empfangene Bytes")
        diagRxLast = metricRow(diagRx, "Letzter Empfang")
        diagRxFrames = metricRow(diagRx, "Frames (ungültig)")
        diagRxDecode = metricRow(diagRx, "Decoder ok / Fehler")
        diagRxBuffer = metricRow(diagRx, "Reassembler-Puffer")
        diagRxAdapter = metricRow(diagRx, "Aktiver Adapter")
        diagRxNotify = metricRow(diagRx, "Notify (CCCD)")
        diagRxNotifyChar = metricRow(diagRx, "Notify-Characteristic")
        diagRxCccd = metricRow(diagRx, "CCCD-Kette")
        diagRxCause = metricRow(diagRx, "Fehlerursache")
        tabContent.addView(diagRx, cardParams())

        val diagBattery = makeCard()
        diagBatteryState = metricRow(diagBattery, "Akkustand")
        diagBatteryVoltage = metricRow(diagBattery, "Packspannung")
        diagBatteryDelta = metricRow(diagBattery, "Zell-Delta")
        diagBatteryTemp = metricRow(diagBattery, "Akku-Temperatur")
        diagBatterySoh = metricRow(diagBattery, "SoH")
        diagChargeState = metricRow(diagBattery, "Ladezustand")
        tabContent.addView(diagBattery, cardParams())

        val diagMotor = makeCard()
        diagMotorRpm = metricRow(diagMotor, "Drehzahl")
        diagMotorTemp = metricRow(diagMotor, "Motor-Temperatur")
        diagEcuStatus = metricRow(diagMotor, "Controller-Status")
        diagFirmware = metricRow(diagMotor, "Firmware")
        tabContent.addView(diagMotor, cardParams())

        val diagWarnCard = makeCard()
        diagWarnings = makeText("Keine Warnungen.", 12f, Palette.TEXT_MUTED)
        diagWarnCard.addView(diagWarnings, LinearLayout.LayoutParams(MATCH, WRAP))
        diagLogButton = makePrimaryButton("DIAGNOSE-LOG (0)", Palette.SURFACE_HIGH) {
            showDiagnosticLogDialog()
        }
        diagWarnCard.addView(diagLogButton, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(0, dp(8), 0, 0)
        })
        tabContent.addView(diagWarnCard, cardParams())

        tabContent.addView(makeSectionLabel("ZELLSPANNUNGEN"))
        val cells = makeCard()
        cellGraphRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(6), 0, dp(6))
        }
        cells.addView(cellGraphRow, LinearLayout.LayoutParams(MATCH, dp(90)))
        tabContent.addView(cells, cardParams())

        val actions = makeCard()
        actions.addView(makePrimaryButton("TRIP ZURÜCKSETZEN", Palette.SURFACE_HIGH) {
            repository.trip.reset()
            updateDashboardUi()
            addLog(LogCategory.INFO, "Trip zurückgesetzt.")
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        actions.addView(makePrimaryButton(
            if (demoActive) "DEMO-SIMULATION STOPPEN" else "DEMO-SIMULATION STARTEN",
            Palette.ACCENT_ORANGE
        ) {
            if (demoActive) stopTelemetry() else startDemoTelemetry()
            showTab(Tab.DASHBOARD)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        tabContent.addView(actions, cardParams())

        updateDashboardUi()
    }

    private fun startTelemetry() {
        stopTelemetry()
        val engine = bleEngine ?: return
        // PHASE 12.4: Polling nur starten, wenn ALLE Voraussetzungen erfuellt
        // sind. Vorher feuerte der Poll jede Sekunde gegen ein fehlendes
        // Write-Characteristic und erzeugte endlose Fehlermeldungen.
        val decision = PollGate.evaluate(
            engineReady = engineReady,
            hasWriteChar = engine.hasWriteCharacteristic(),
            modelKnown = detectedModel.isKnown,
            adapterSupportsRead = adapterSupportsRead()
        )
        if (decision != PollDecision.ALLOWED) {
            addLog(LogCategory.INFO, "Telemetrie-Polling nicht gestartet: ${PollGate.reason(decision)}")
            return
        }
        telemetryRunnable = object : Runnable {
            override fun run() {
                val eng = bleEngine
                if (!engineReady || eng == null) return
                val d = PollGate.evaluate(
                    engineReady = engineReady,
                    hasWriteChar = eng.hasWriteCharacteristic(),
                    modelKnown = detectedModel.isKnown,
                    adapterSupportsRead = adapterSupportsRead()
                )
                if (d != PollDecision.ALLOWED) {
                    // Kein Endlos-Fehler: einmalig informieren und Polling beenden.
                    addLog(LogCategory.INFO, "Telemetrie-Polling gestoppt: ${PollGate.reason(d)}")
                    stopTelemetry()
                    return
                }
                sendCommand(adapter().buildReadStatusFrame(), "poll", false, silent = true)
                handler.postDelayed(this, 1_000L)
            }
        }
        handler.post(telemetryRunnable!!)
        addLog(LogCategory.INFO, "Telemetrie-Polling gestartet.")
    }

    private fun startDemoTelemetry() {
        stopTelemetry()
        demoActive = true
        simTick = 0
        telemetryRunnable = object : Runnable {
            override fun run() {
                if (!demoActive) return
                simTick++
                val speed = (14 + 8 * kotlin.math.sin(simTick / 4.0)).toFloat().coerceAtLeast(0f)
                val voltage = 48.2f - (simTick % 60) * 0.01f
                val current = 6.5f + 3f * kotlin.math.sin(simTick / 3.0).toFloat()
                val telemetry = Telemetry(
                    speedKmh = speed,
                    voltageV = voltage,
                    currentA = current,
                    batteryPercent = (92 - simTick / 12).coerceAtLeast(5),
                    motorTempC = 34f + (simTick % 20) * 0.4f,
                    cellMinV = 3.62f,
                    cellMaxV = 3.68f,
                    cellVoltages = List(13) { 3.62f + (it % 4) * 0.015f },
                    designCapacityMah = 15_000,
                    fullChargeCapacityMah = 14_400,
                    cycleCount = 42,
                    rawHex = "DEMO",
                    source = activeProtocol
                )
                lastTelemetry = telemetry
                updateTrip(telemetry)
                scheduleTelemetryApply()
                handler.postDelayed(this, 500L)
            }
        }
        handler.post(telemetryRunnable!!)
        addLog(LogCategory.INFO, "Demo-Simulation gestartet (keine echten Gerätedaten).")
    }

    private fun stopTelemetry() {
        telemetryRunnable?.let { handler.removeCallbacks(it) }
        telemetryRunnable = null
        demoActive = false
    }

    /**
     * PHASE 12.1: Der Trip-Timer laeuft auf einem eigenen Handler-Tick und
     * damit unabhaengig vom aktiven Tab weiter — wie in der frueheren Fassung.
     * Die Dauer selbst kommt aus [TripData] (Startzeitpunkt), nicht aus einer
     * erfundenen Quelle.
     */
    private fun startTripClock() {
        stopTripClock()
        val r = object : Runnable {
            override fun run() {
                dashTripTime?.text = if (tripData.startMillis > 0L)
                    TripClock.format(System.currentTimeMillis() - tripData.startMillis)
                else "00:00:00"
                handler.postDelayed(this, 1000L)
            }
        }
        tripClockRunnable = r
        handler.post(r)
    }

    private fun stopTripClock() {
        tripClockRunnable?.let { handler.removeCallbacks(it) }
        tripClockRunnable = null
    }

    private fun scheduleTelemetryApply() {
        if (telemetryApplyScheduled) return
        telemetryApplyScheduled = true
        val now = System.currentTimeMillis()
        val delay = (UI_REFRESH_INTERVAL_MS - (now - lastTelemetryApply)).coerceAtLeast(0L)
        handler.postDelayed({
            telemetryApplyScheduled = false
            lastTelemetryApply = System.currentTimeMillis()
            updateDashboardUi()
        }, delay)
    }

    private fun updateTrip(t: Telemetry) {
        val speed = t.speedKmh ?: return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val trip = repository.trip
        if (trip.startMillis == 0L) trip.startMillis = nowWall
        // FIX: Integration ausschliesslich ueber die MONOTONE Uhr. Ein
        // Wanduhr-Sprung oder eine lange Pause (App im Hintergrund, Standby)
        // darf die Strecke/Energie nicht aufblaehen (siehe TripAccumulator).
        val dtHours = TripAccumulator.deltaHours(trip.lastElapsedMillis, nowElapsed)
        if (dtHours > 0.0) {
            trip.distanceKm += speed * dtHours
            val voltage = t.voltageV
            val current = t.currentA
            if (voltage != null && current != null) {
                trip.energyWh += voltage * current * dtHours
            }
        }
        trip.lastElapsedMillis = nowElapsed
        trip.lastMillis = nowWall
        if (speed > trip.maxSpeedKmh) trip.maxSpeedKmh = speed.toDouble()
        trip.speedSum += speed
        trip.speedSamples++
    }

    private fun updateDashboardUi() {
        val t = lastTelemetry
        val trip = repository.trip
        // PHASE 16.2: Empfangsdiagnose bei jedem Refresh mit aktuellem Stand zeigen.
        lastTelemetryDiag?.let { applyTelemetryDiagnostics(it) }
        // ---- PHASE 12.5: Cockpit aktualisieren ----
        val speed = t?.speedKmh
        val powerW = if (t?.voltageV != null && t.currentA != null) t.voltageV * t.currentA else null
        dashGauge?.setValue(speed, 60f, "km/h", "Geschwindigkeit", demo = demoActive)
        if (speed == null) dashGauge?.setUnavailable("Geschwindigkeit")
        if (speed != null) {
            speedHistory.addLast(speed)
            while (speedHistory.size > HISTORY_MAX) speedHistory.removeFirst()
        }
        if (powerW != null) {
            powerHistory.addLast(powerW)
            while (powerHistory.size > HISTORY_MAX) powerHistory.removeFirst()
        }
        speedSpark?.setValues(
            speedHistory.toList(),
            maxOf(60f, speedHistory.maxOrNull() ?: 0f),
            Palette.ACCENT
        )
        powerSpark?.setValues(
            powerHistory.toList(),
            maxOf(500f, powerHistory.maxOrNull() ?: 0f),
            Palette.ACCENT_BLUE
        )
        dashHistoryMinMax?.text = if (speedHistory.isEmpty()) {
            "Verlauf: min \u2014 \u00b7 max \u2014"
        } else {
            String.format(
                Locale.US,
                "Verlauf: min %.1f km/h \u00b7 max %.1f km/h",
                speedHistory.minOrNull() ?: 0f,
                speedHistory.maxOrNull() ?: 0f
            )
        }
        dashSpeed?.text = t?.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—"
        dashPower?.text = if (t?.voltageV != null && t.currentA != null) {
            String.format(Locale.US, "%.0f W", t.voltageV * t.currentA)
        } else "—"
        dashVoltage?.text = t?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—"
        dashCurrent?.text = t?.currentA?.let { String.format(Locale.US, "%.2f A", it) } ?: "—"
        dashBattery?.text = t?.batteryPercent?.let { "$it %" } ?: "—"
        dashMotorTemp?.text = t?.motorTempC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"
        dashCellMin?.text = t?.cellMinV?.let { String.format(Locale.US, "%.3f V", it) } ?: "—"
        dashCellMax?.text = t?.cellMaxV?.let { String.format(Locale.US, "%.3f V", it) } ?: "—"
        dashCellDelta?.text = if (t?.cellMinV != null && t.cellMaxV != null) {
            String.format(Locale.US, "%.0f mV", (t.cellMaxV - t.cellMinV) * 1000)
        } else "—"
        dashTempGuardState?.text = when {
            motorGuardTripped -> "AUSGELÖST"
            motorTempGuardEnabled -> "aktiv (${motorTempGuardC} °C)"
            else -> "inaktiv"
        }
        dashFeedState?.text = when {
            demoActive -> "DEMO (simuliert)"
            engineReady -> "live (BLE)"
            else -> "keine Daten"
        }
        dashTrip?.text = String.format(Locale.US, "%.2f km", trip.distanceKm)
        dashTripTime?.text = if (trip.startMillis > 0L)
            TripClock.format(System.currentTimeMillis() - trip.startMillis) else "00:00:00"
        dashVmax?.text = String.format(Locale.US, "%.1f km/h", trip.maxSpeedKmh)
        dashVavg?.text = String.format(Locale.US, "%.1f km/h", trip.avgSpeedKmh)
        dashWh?.text = String.format(Locale.US, "%.1f Wh", trip.energyWh)
        dashSoh?.text = if (t?.designCapacityMah != null && t.fullChargeCapacityMah != null &&
            t.designCapacityMah > 0
        ) {
            String.format(Locale.US, "%.1f %%",
                t.fullChargeCapacityMah.toDouble() / t.designCapacityMah * 100.0)
        } else "—"
        dashCycles?.text = t?.cycleCount?.toString() ?: "—"
        // FIX: Bei unbekanntem Modell wird KEINE Reichweite geschaetzt. Der
        // frueher implizite 36-V-Default war fuer 48-V-Geraete (NAVEE) falsch.
        dashRange?.text = RangeEstimator.estimateKm(detectedModel, t?.batteryPercent)
            ?.let { String.format(Locale.US, "%.1f km", it) } ?: "—"

        // ---- PHASE 9: Diagnose-Anzeige -------------------------------------
        // EIN Bericht pro Refresh. Kein zusaetzlicher UI-Reload: dies laeuft im
        // bereits gedrosselten scheduleTelemetryApply()-Pfad.
        val report = buildDiagnosticsReport()
        diagOverall?.text = when (report.overall) {
            DataStatus.OK -> "OK"
            DataStatus.STALE -> "VERALTET"
            DataStatus.INVALID -> "UNPLAUSIBEL"
            DataStatus.UNKNOWN_PROTOCOL -> "Protokoll unbekannt"
            DataStatus.NOT_AVAILABLE -> "Nicht verfügbar"
        }
        diagConnection?.text = if (engineReady) "verbunden" else "nicht verbunden"
        diagProtocol?.text = when {
            demoActive -> "DEMO (simuliert)"
            engineReady -> activeProtocol.displayName
            else -> "—"
        }
        diagDataAge?.text = report.live.updateAgeMillis?.let { age ->
            if (report.live.freshness == DataStatus.STALE) "$age ms (veraltet)" else "$age ms"
        } ?: "—"
        diagCommErrors?.text = commErrorCount.toString()
        diagBatteryState?.text = formatDiag(report.battery.percent, "%", 0)
        diagBatteryVoltage?.text = formatDiag(report.battery.voltage, "V", 2)
        diagBatteryDelta?.text = formatDiag(report.battery.cellDeltaMv, "mV", 0)
        diagBatteryTemp?.text = formatDiag(report.battery.temperature, "°C", 1)
        diagBatterySoh?.text = formatDiag(report.battery.sohPercent, "%", 1)
        diagChargeState?.text = if (report.battery.chargeStateNote.isEmpty()) {
            report.battery.chargeState.label
        } else {
            "${report.battery.chargeState.label} (${report.battery.chargeStateNote})"
        }
        diagMotorRpm?.text = formatDiag(report.motor.rpm, "U/min", 0)
        diagMotorTemp?.text = formatDiag(report.motor.temperature, "°C", 1)
        diagEcuStatus?.text = report.motor.controllerStatus.status.label
        diagFirmware?.text = report.electronics.firmwareVersion.status.label
        diagWarnings?.text = if (report.warnings.isEmpty()) {
            "Keine Warnungen."
        } else {
            report.warnings.joinToString("\n") { "• $it" }
        }
        diagWarnings?.setTextColor(
            if (report.warnings.isEmpty()) Palette.TEXT_MUTED else Palette.ACCENT_ORANGE
        )
        diagLogButton?.text = "DIAGNOSE-LOG (${diagnosticLog.size()})"

        cellGraphRow?.let { row ->
            row.removeAllViews()
            val cells = t?.cellVoltages
            if (cells.isNullOrEmpty()) {
                row.addView(makeText("Keine Zellwerte empfangen.", 11f, Palette.TEXT_MUTED))
            } else {
                val min = cells.minOrNull() ?: 3.0f
                val max = cells.maxOrNull() ?: 4.2f
                val span = (max - min).coerceAtLeast(0.05f)
                for (v in cells) {
                    val height = dp(20) + ((v - min) / span * dp(60)).toInt()
                    val bar = View(this).apply { setBackgroundColor(Palette.ACCENT_GREEN) }
                    row.addView(bar, LinearLayout.LayoutParams(0, height, 1f).apply {
                        setMargins(dp(1), 0, dp(1), 0)
                    })
                }
            }
        }
    }

    /* ================================================================== */
    /*  FLASH / FIRMWARE                                                  */
    /* ================================================================== */

    private fun renderFlash() {
        val language = AppLanguage.DE
        val modelKnown = detectedModel.isKnown

        // ---- 1. ERKANNTES MODELL ------------------------------------------
        tabContent.addView(makeSectionLabel("FLASH"))
        val modelCard = makeCard()
        modelCard.addView(makeText(
            if (modelKnown) "ERKANNTES MODELL" else "KEIN MODELL ERKANNT",
            10f, Palette.TEXT_MUTED, true
        ).apply { letterSpacing = 0.12f })
        modelCard.addView(makeText(
            if (modelKnown) detectedModel.displayName else "Unbekannt",
            20f, Palette.TEXT_PRIMARY, true
        ).apply { setPadding(0, dp(2), 0, dp(4)) })
        modelCard.addView(makeText(
            if (modelKnown)
                "Protokoll: ${detectedModel.protocol.name}  \u00b7  " +
                    "Verbindung: ${if (engineReady) "aktiv" else "getrennt"}"
            else
                "F\u00fcr Firmware-Vorschl\u00e4ge wird ein erkanntes Modell ben\u00f6tigt. " +
                    "Bitte im INFO-Bereich verbinden und die Modellerkennung abwarten.",
            11f, Palette.TEXT_SECONDARY
        ))
        if (!modelKnown) {
            modelCard.addView(makeOutlineButton("ZUR VERBINDUNG / MODELLERKENNUNG", Palette.TEXT_SECONDARY) {
                showTab(Tab.INFO)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })
        }
        tabContent.addView(modelCard, cardParams())

        // ---- 2. ZENTRALER BUTTON "VORSCHLAEGE" ----------------------------
        val suggestCard = makeCard()
        suggestCard.addView(makePrimaryButton("VORSCHL\u00c4GE", Palette.ACCENT) {
            if (!modelKnown) {
                toast("Kein Modell erkannt \u2014 keine Vorschl\u00e4ge m\u00f6glich.")
            } else {
                flashSuggestionsShown = true
                showTab(Tab.FLASH)
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        suggestCard.addView(makeText(
            if (flashSuggestionsShown && modelKnown)
                "Vorschl\u00e4ge f\u00fcr ${detectedModel.displayName} werden unten angezeigt."
            else
                "Zeigt modellbezogene Firmware-Eintr\u00e4ge f\u00fcr das erkannte Modell.",
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(8), 0, 0) })
        tabContent.addView(suggestCard, cardParams())

        // ---- 3. FIRMWARE-VORSCHLAEGE --------------------------------------
        if (flashSuggestionsShown && modelKnown) {
            tabContent.addView(makeSectionLabel("FIRMWARE-VORSCHL\u00c4GE"))
            val listCard = makeCard()
            listCard.addView(makeText(
                "Alle Eintr\u00e4ge sind Referenzdaten. Es sind keine gepr\u00fcften Firmware-Dateien " +
                    "oder Download-Endpunkte hinterlegt; der Verifikationsstatus wird je Paket angezeigt.",
                11f, Palette.TEXT_MUTED
            ).apply { setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, 0, 0, dp(8)) })

            val suggestions = FirmwareSuggestions.build(
                detectedModel,
                FirmwareCatalog.forModel(detectedModel)
            )
            if (suggestions.isEmpty()) {
                listCard.addView(makeText("Keine Vorschl\u00e4ge f\u00fcr dieses Modell.", 12f, Palette.TEXT_MUTED))
            }
            for (suggestion in suggestions) {
                listCard.addView(buildSuggestionRow(suggestion, language),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
            }
            tabContent.addView(listCard, cardParams())
        }

        // ---- 4. LOKALE DATEI PRUEFEN --------------------------------------
        tabContent.addView(makeSectionLabel("LOKALE DATEI PR\u00dcFEN"))
        val fileCard = makeCard()
        fileCard.addView(makeText(
            if (selectedFirmwareName == null) "Keine Datei geladen."
            else "Datei: $selectedFirmwareName",
            12f, Palette.TEXT_SECONDARY
        ))
        fileCard.addView(makePrimaryButton("DATEI AUSW\u00c4HLEN", Palette.SURFACE_HIGH) {
            firmwarePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        lastValidation?.let { validation ->
            fileCard.addView(makeText(
                (if (validation.valid) "OK: " else "GESPERRT: ") + validation.message,
                12f, if (validation.valid) Palette.ACCENT_GREEN else Palette.ACCENT_RED
            ).apply { setPadding(0, dp(8), 0, 0) })
            fileCard.addView(makeText("SHA-256: ${validation.sha256}", 10f, Palette.TEXT_MUTED))
            fileCard.addView(makeText(
                "Hash verifiziert: ${if (validation.hashVerified) "JA" else "NEIN"} \u00b7 " +
                    "Modellkompatibel: ${if (validation.modelCompatible) "JA" else "NEIN"}",
                11f, Palette.TEXT_MUTED
            ))
        }
        tabContent.addView(fileCard, cardParams())

        // ---- 5. FLASH-STATUS (ehrliche Zustandsleiter) --------------------
        val evidence = FirmwareEvidence(
            modelDetected = detectedModel.isKnown,
            suggestionsShown = flashSuggestionsShown && detectedModel.isKnown,
            packageObtained = flashPackageObtained,
            integrityChecked = lastValidation?.valid == true,
            modelVerified = lastValidation?.hashVerified == true &&
                lastValidation?.modelCompatible == true,
            flashed = flashFlashedOk
        )
        val stageReport = FirmwareLifecycle.evaluate(evidence, language)

        tabContent.addView(makeSectionLabel("FLASH-STATUS"))
        val statusCard = makeCard()
        for (entry in stageReport.stages) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            line.addView(makeText(
                (if (entry.reached) "\u2713 " else "\u00b7 ") + entry.stage.label(language),
                12f, if (entry.reached) Palette.SUCCESS else Palette.TEXT_MUTED, entry.reached
            ), LinearLayout.LayoutParams(0, WRAP, 1f))
            line.addView(makeText(entry.evidence, 11f,
                if (entry.reached) Palette.SUCCESS else Palette.TEXT_MUTED))
            statusCard.addView(line)
        }
        statusCard.addView(makeText(
            FlashHonesty.statusLine(evidence, language),
            11f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
        tabContent.addView(statusCard, cardParams())

        // ---- 6. FLASH-AKTIONEN -------------------------------------------
        tabContent.addView(makeSectionLabel("FLASH"))
        val flashCard = makeCard()
        flashStatusText = makeText(
            "Hard-Flash ist gesperrt, solange kein verifizierter SHA-256 und keine belegte " +
                "Modellkompatibilit\u00e4t vorliegen.",
            12f, Palette.ACCENT_ORANGE
        )
        flashCard.addView(flashStatusText)
        flashProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        flashCard.addView(flashProgressBar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(0, dp(8), 0, dp(8))
        })
        flashCard.addView(makePrimaryButton("SOFT-PATCH SENDEN", Palette.ACCENT_GREEN) {
            applySoftPatch()
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val hardAllowed = FlashHonesty.hardFlashAllowed(lastValidation, detectedModel.isKnown, engineReady)
        flashCard.addView(makePrimaryButton(
            if (hardAllowed) "HARD-FLASH STARTEN" else "HARD-FLASH GESPERRT",
            if (hardAllowed) Palette.ACCENT_RED else Palette.SURFACE
        ) {
            startHardFlashIfVerified()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        tabContent.addView(flashCard, cardParams())

        tabContent.addView(makeText(
            "Sicherheit: Firmware wird nur geschrieben, wenn ein hinterlegter SHA-256 " +
                "\u00fcbereinstimmt und die Modellkompatibilit\u00e4t belegt ist. Ein \u00fcbernommenes " +
                "Paket oder ein kopierter Link ist KEIN Flash. Der Katalog enth\u00e4lt bewusst " +
                "keine erfundenen Download-URLs oder Pr\u00fcfsummen.",
            11f, Palette.TEXT_MUTED
        ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    /** Eine Vorschlagszeile mit ehrlichem Status und zwei Aktionen. */
    private fun buildSuggestionRow(suggestion: FirmwareSuggestion, language: AppLanguage): LinearLayout {
        val selected = suggestion.packageId == selectedFirmware?.id
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackground(roundedBg(
                if (selected) Palette.SURFACE_DEEP else Palette.SURFACE,
                Palette.OUTLINE, 10
            ))
        }
        row.addView(makeText("${suggestion.name} \u00b7 ${suggestion.version}", 13f, Palette.TEXT_PRIMARY, true))
        row.addView(makeText("Modell: ${suggestion.targetModel}", 11f, Palette.TEXT_SECONDARY))
        row.addView(makeText("Ziel / Controller: ${suggestion.controller}", 11f, Palette.TEXT_SECONDARY))
        row.addView(makeText(
            "Hardware-Kompatibilit\u00e4t: " +
                if (suggestion.isVerified) "belegt" else "nicht belegt",
            11f, if (suggestion.isVerified) Palette.SUCCESS else Palette.WARNING
        ))
        row.addView(makeText("Quelle: ${suggestion.sourceLabel}", 10f, Palette.TEXT_MUTED))
        row.addView(makeText(
            "Pr\u00fcfsumme (SHA-256): " + (suggestion.expectedSha256 ?: "nicht hinterlegt"),
            10f, Palette.TEXT_MUTED
        ))
        row.addView(makeText(
            "Status: ${suggestion.verification.label(language)}",
            11f, when (suggestion.verification) {
                PackageVerification.MODEL_VERIFIED -> Palette.SUCCESS
                PackageVerification.HASH_VERIFIED -> Palette.SUCCESS
                PackageVerification.HASH_PRESENT -> Palette.WARNING
                PackageVerification.UNVERIFIED -> Palette.ACCENT_ORANGE
            }, true
        ))
        row.addView(makeText(
            "Referenz-Limit: ${suggestion.referenceMaxKmh} km/h (unbest\u00e4tigt)",
            10f, Palette.TEXT_MUTED
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(makePrimaryButton("PAKET \u00dcBERNEHMEN", Palette.SURFACE_HIGH) {
            importSuggestion(suggestion)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, 0, dp(4), 0) })
        actions.addView(makePrimaryButton("DOWNLOAD-LINK KOPIEREN", Palette.SURFACE_HIGH) {
            copySuggestionLink(suggestion)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        row.addView(actions)
        return row
    }

    /**
     * Uebernimmt ein Paket in den app-eigenen Speicher. Es wird NICHTS
     * geflasht. Ohne geprueften Dateiinhalt und ohne Download-Endpunkt wird
     * ehrlich erklaert, warum keine Uebernahme moeglich ist.
     */
    private fun importSuggestion(suggestion: FirmwareSuggestion) {
        val validation = lastValidation
        val bytes = validation?.extractedBinary ?: loadedFirmwareBytes
        // PHASE 16 §5: Identitaetssicherung. Eine lokal ausgewaehlte Datei darf
        // NICHT unter dem Namen eines fremden Katalogpakets gespeichert werden.
        val decision = FirmwareImportGuard.evaluate(
            suggestionPackageId = suggestion.packageId,
            selectedPackageId = selectedFirmware?.id,
            validationValid = validation?.valid == true,
            hashVerified = validation?.hashVerified == true,
            modelCompatible = validation?.modelCompatible == true
        )
        if (decision != FirmwareImportDecision.ALLOWED) {
            val reason = FirmwareImportGuard.reason(decision, AppLanguage.DE)
            toast(reason)
            addLog(LogCategory.ERROR, "Paket '${suggestion.name}' nicht uebernommen: $reason")
            AlertDialog.Builder(this)
                .setTitle("Uebernahme abgelehnt")
                .setMessage(reason + "\n\nEs wird nichts gespeichert und nichts geflasht.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (validation != null && validation.valid && bytes != null && selectedFirmwareName != null) {
            AlertDialog.Builder(this)
                .setTitle("Paket \u00fcbernehmen")
                .setMessage(
                    "Die gepr\u00fcfte Datei \u201e$selectedFirmwareName\u201c wird als Paket " +
                        "\u201e${suggestion.name}\u201c in den App-Speicher kopiert.\n\n" +
                        "Das ist KEIN Flash. Es wird nichts an den Controller geschrieben."
                )
                .setPositiveButton("\u00dcbernehmen") { _, _ ->
                    val file = FirmwareStorage.save(filesDir, suggestion.packageId, bytes)
                    if (file == null) {
                        toast("Paket konnte nicht gespeichert werden.")
                        addLog(LogCategory.ERROR, "Paket '${suggestion.name}' nicht gespeichert.")
                    } else {
                        selectedFirmware = FirmwareCatalog.forModel(detectedModel)
                            .firstOrNull { it.id == suggestion.packageId }
                        flashPackageObtained = true
                        addLog(LogCategory.INFO,
                            "Paket '${suggestion.name}' in App-Speicher \u00fcbernommen: ${file.name} (kein Flash)")
                        toast("Paket \u00fcbernommen: ${file.name}. Das ist KEIN Flash.")
                        showTab(Tab.FLASH)
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        if (suggestion.hasDownloadLink) {
            AlertDialog.Builder(this)
                .setTitle("Paket \u00fcbernehmen")
                .setMessage(
                    "F\u00fcr dieses Paket ist ein Download-Link hinterlegt. Ein automatischer " +
                        "Download ist in dieser Version nicht implementiert.\n\n" +
                        "Link jetzt kopieren?"
                )
                .setPositiveButton("Link kopieren") { _, _ -> copySuggestionLink(suggestion) }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        toast("Kein Download-Endpunkt und keine gepr\u00fcfte Datei vorhanden.")
        addLog(LogCategory.INFO,
            "Paket '${suggestion.name}': kein Download-Endpunkt hinterlegt, keine Datei gepr\u00fcft.")
    }

    /** Kopiert den echten Download-Link - oder erklaert, dass keiner hinterlegt ist. */
    private fun copySuggestionLink(suggestion: FirmwareSuggestion) {
        val url = suggestion.downloadUrl
        if (url.isNullOrBlank()) {
            toast("F\u00fcr dieses Paket ist kein Download-Link hinterlegt.")
            addLog(LogCategory.INFO, "Paket '${suggestion.name}': kein Download-Link hinterlegt.")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Syrak Firmware-Link", url))
        toast("Download-Link kopiert. Ein Link ist KEINE Firmware und KEIN Flash.")
        addLog(LogCategory.INFO, "Download-Link f\u00fcr '${suggestion.name}' kopiert.")
    }

    private fun applySoftPatch() {
        val pkg = selectedFirmware ?: run {
            toast("Bitte zuerst ein Katalog-Paket auswählen.")
            return
        }
        if (!engineReady) {
            toast("Keine aktive Verbindung.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Soft-Patch senden")
            .setMessage(
                "Es wird ein BLE-Konfigurationsbefehl gesendet, KEINE Firmware geschrieben. " +
                    "Ziel: ${pkg.name} (${pkg.recommendedMaxKmh} km/h). Fortfahren?"
            )
            .setPositiveButton("Senden") { _, _ ->
                sendTuning(
                    adapter().buildSpeedCeilingFrame(pkg.recommendedMaxKmh),
                    "softPatch=${pkg.id}"
                )
                addLog(LogCategory.INFO, "Soft-Patch '${pkg.name}' gesendet.")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startHardFlashIfVerified() {
        val bytes = loadedFirmwareBytes
        val validation = lastValidation
        if (bytes == null || validation == null || !validation.valid) {
            toast("Keine gültige, geprüfte Firmware geladen.")
            return
        }
        if (!validation.hashVerified) {
            toast("Hard-Flash gesperrt: SHA-256 nicht verifiziert.")
            addLog(LogCategory.ERROR, "Hard-Flash verweigert: kein verifizierter Hash.")
            return
        }
        if (!validation.modelCompatible) {
            toast("Hard-Flash gesperrt: Modellkompatibilität nicht belegt.")
            addLog(LogCategory.ERROR, "Hard-Flash verweigert: Modell nicht belegt.")
            return
        }
        if (!detectedModel.isKnown) {
            toast("Hard-Flash gesperrt: Modell nicht erkannt.")
            return
        }
        if (!engineReady) {
            toast("Hard-Flash gesperrt: keine aktive Verbindung.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("HARD-FLASH — letzte Warnung")
            .setMessage(
                "Es werden ${bytes.size} Bytes dauerhaft in den Controller geschrieben. " +
                    "Ein Abbruch kann das Gerät unbrauchbar machen. Nur mit bestätigter " +
                    "Hardware-/Firmware-Kompatibilität und Recovery-Pfad fortfahren."
            )
            .setPositiveButton("FLASHEN") { _, _ ->
                flashFlashedOk = false
                addLog(LogCategory.FLASH, "Hard-Flash gestartet: ${bytes.size} Bytes")
                bleEngine?.startFlash(bytes, adapter().buildUnlockFrame(), adapter().buildRebootFrame())
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun handleSelectedFirmware(uri: Uri) {
        val name = queryDisplayName(uri) ?: "firmware.bin"
        val bytes = readLimited(uri, MAX_FIRMWARE_BYTES)
        if (bytes == null || bytes.isEmpty()) {
            toast("Datei konnte nicht gelesen werden oder ist leer.")
            addLog(LogCategory.ERROR, "Firmware-Datei nicht lesbar: $name")
            return
        }
        selectedFirmwareName = name
        val validation = validateFirmware(bytes, name)
        lastValidation = validation
        loadedFirmwareBytes = validation.extractedBinary
        addLog(
            if (validation.valid) LogCategory.FLASH else LogCategory.ERROR,
            "Firmware-Prüfung '$name': ${validation.message}"
        )
        showTab(Tab.FLASH)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun readLimited(uri: Uri, limit: Int): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > limit) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun validateFirmware(bytes: ByteArray, name: String): FirmwareValidation =
        FirmwareValidator.validate(
            bytes = bytes,
            fileName = name,
            catalogName = selectedFirmware?.name,
            model = detectedModel,
            expectedSha256 = selectedFirmware?.expectedSha256
        )

    private fun renderConfig() {
        tabContent.addView(makeSectionLabel("REGION / LIMIT"))
        val regionCard = makeCard()
        regionCard.addView(makeText(
            "Aktuell: ${currentRegion.displayName} · ${currentRegion.legalSpeedKmh} km/h",
            13f, Palette.TEXT_SECONDARY
        ))
        val regionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (region in Region.values()) {
            val button = makePrimaryButton(
                region.code,
                if (region == currentRegion) Palette.ACCENT_GREEN else Palette.SURFACE
            ) {
                currentRegion = region
                repository.region = region
                addLog(LogCategory.INFO, "Region: ${region.displayName} (${region.legalSpeedKmh} km/h)")
                showTab(Tab.CONFIG)
            }
            regionRow.addView(button, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        regionCard.addView(regionRow, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(0, dp(8), 0, 0)
        })
        tabContent.addView(regionCard, cardParams())

        tabContent.addView(makeSectionLabel("VERBINDUNG"))
        val connCard = makeCard()
        connCard.addView(makeToggleRow(
            "Auto-Reconnect", "Versucht nach einem unerwarteten Abbruch die Verbindung wiederherzustellen.",
            autoReconnectEnabled
        ) { value ->
            autoReconnectEnabled = value
            repository.autoReconnect = value
            // PHASE 7: Abschalten wirkt sofort — ein bereits geplantes
            // Reconnect-Runnable wird verworfen, statt spaeter doch zu verbinden.
            if (!value) cancelReconnectRunnable()
        })
        tabContent.addView(connCard, cardParams())

        // PHASE 8 (Regel 6): Tuning ist standardmaessig gesperrt. Diese Karte
        // macht die Sperre sichtbar und erlaubt eine bewusste Sitzungs-Freigabe.
        tabContent.addView(makeSectionLabel("SICHERHEIT / TUNING-FREIGABE"))
        val safetyCard = makeCard()
        safetyCard.addView(makeText(
            "Tuning-Schreibbefehle (Geschwindigkeit, KERS, Zero-Start, Cruise, Lock, " +
                "German Maneuver, Soft-Patch) sind GESPERRT, bis die Kompatibilitaet auf " +
                "echter Hardware geprueft wurde. Die Freigabe gilt nur fuer diese Sitzung " +
                "und wird beim naechsten App-Start zurueckgesetzt. Not-Aus, Trennen und " +
                "alle Diagnosefunktionen bleiben immer aktiv.",
            11f, Palette.ACCENT_YELLOW
        ))
        safetyCard.addView(makeToggleRow(
            "Hardware-Kompatibilitaet bestaetigt",
            "Nur aktivieren, wenn du das erkannte Modell auf deinem eigenen Geraet " +
                "tatsaechlich verifiziert hast. Ohne Freigabe wird kein Tuning-Befehl gesendet.",
            hardwareVerified
        ) { value ->
            hardwareVerified = value
            // PHASE 8.1 (Punkt 1): Freigabe des Eager-Handshakes an den neuen
            // Zustand anpassen. Bei unbekanntem Modell bleibt er gesperrt.
            bleEngine?.setAutoHandshakeAllowed(
                modelKnown = detectedModel.isKnown && modelAdapterExact(),
                hardwareVerified = value
            )
            addLog(
                LogCategory.INFO,
                "Tuning-Freigabe: ${if (value) "aktiv (Sitzung)" else "gesperrt"}"
            )
            showTab(Tab.CONFIG)
        })
        tabContent.addView(safetyCard, cardParams())

        tabContent.addView(makeSectionLabel("PIN-SCHUTZ"))
        val pinCard = makeCard()
        pinCard.addView(makeText(
            if (repository.hasPin) "PIN ist gesetzt." else "Keine PIN gesetzt.",
            12f, Palette.TEXT_SECONDARY
        ))
        pinCard.addView(makePrimaryButton("PIN SETZEN / ÄNDERN", Palette.SURFACE_HIGH) {
            promptSetPin()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        if (repository.hasPin) {
            pinCard.addView(makePrimaryButton("PIN ENTFERNEN", Palette.ACCENT_RED) {
                promptClearPin()
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        }
        tabContent.addView(pinCard, cardParams())

        tabContent.addView(makeSectionLabel("TUNING-PROFILE"))
        val profileCard = makeCard()
        profileCard.addView(makePrimaryButton("AKTUELLES PROFIL SPEICHERN", Palette.ACCENT_GREEN) {
            promptSaveProfile()
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val invalidProfiles = repository.invalidProfileCount()
        if (invalidProfiles > 0) {
            // Ungueltige Alt-Eintraege werden bewusst NICHT geloescht (sie
            // koennten zu einem anderen Profil gehoeren) — aber sie sollen
            // auch nicht unsichtbar sein.
            profileCard.addView(makeText(
                "$invalidProfiles gespeicherte(r) Eintrag/Eintraege ist/sind unlesbar und " +
                    "werden uebersprungen. Sie bleiben erhalten und blockieren keine anderen Profile.",
                11f, Palette.ACCENT_YELLOW
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        for (profile in ProfileStore.decodeAll(repository.profileEntries())) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            row.addView(makePrimaryButton("LADEN: ${profile.name}", Palette.SURFACE) {
                maxSpeedKmh = profile.maxSpeed
                zeroStartKmh = profile.zeroStart
                kersLevel = profile.kers
                cruiseEnabled = profile.cruiseControl
                addLog(LogCategory.INFO, "Profil geladen: ${profile.name}")
                showTab(Tab.CONFIG)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(makePrimaryButton("L\u00d6SCHEN", Palette.ACCENT_RED) {
                confirmDeleteProfile(profile.name)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(6), 0, 0, 0) })
            profileCard.addView(row)
        }
        tabContent.addView(profileCard, cardParams())

        // PHASE 14 (Teil D): Das doppelte Log-Terminal wurde entfernt.
        // Das Log-Terminal existiert nur noch im geschuetzten Entwicklerbereich.
        tabContent.addView(makeSectionLabel("ÜBER"))
        val about = makeCard()
        about.addView(makeText(
            "Syrak ScooterLab v7 · Diagnose ohne erfundene Firmware-Endpunkte. " +
                "Alle Telemetrie-Offsets sind als unverifiziert dokumentiert. " +
                "Ein Gerät gilt erst als verbunden, wenn onReady vom GATT-Layer kommt.",
            12f, Palette.TEXT_MUTED
        ))
        tabContent.addView(about, cardParams())
    }

    /**
     * Speichert das aktuelle Tuning als Profil unter einem Namen.
     *
     * PHASE 4: Vorher wurde der Name automatisch als "Profil N" vergeben. Ein
     * vorhandenes Profil liess sich damit nie ueberschreiben (N zaehlte immer
     * hoch) und ein sinnvoller Name war nicht moeglich. Der Vorschlag bleibt
     * derselbe, ist aber aenderbar; gleicher Name = Ueberschreiben.
     */
    private fun promptSaveProfile() {
        val suggestion = "Profil ${repository.profileEntries().size + 1}"
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "Profilname"
            setText(suggestion)
            setSelection(suggestion.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Profil speichern")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Profilname darf nicht leer sein.")
                    return@setPositiveButton
                }
                val existed = ProfileStore.contains(repository.profileEntries(), name)
                repository.saveProfile(
                    ScooterProfile(name, maxSpeedKmh, zeroStartKmh, kersLevel, cruiseEnabled)
                )
                addLog(
                    LogCategory.INFO,
                    if (existed) "Profil ueberschrieben: $name" else "Profil gespeichert: $name"
                )
                showTab(Tab.CONFIG)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmDeleteProfile(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Profil loeschen")
            .setMessage("Profil \"$name\" wirklich loeschen?")
            .setPositiveButton("Loeschen") { _, _ ->
                if (repository.deleteProfile(name)) {
                    addLog(LogCategory.INFO, "Profil geloescht: $name")
                } else {
                    addLog(LogCategory.ERROR, "Profil nicht gefunden: $name")
                }
                showTab(Tab.CONFIG)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun promptSetPin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Neue PIN (mind. 4 Ziffern)"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN setzen")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                val pin = input.text.toString()
                if (pin.length < 4) {
                    toast("PIN muss mindestens 4 Ziffern haben.")
                } else {
                    repository.setPin(pin)
                    addLog(LogCategory.INFO, "PIN gesetzt.")
                    showTab(Tab.CONFIG)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun promptClearPin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Aktuelle PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN entfernen")
            .setView(input)
            .setPositiveButton("Entfernen") { _, _ ->
                if (repository.verifyPin(input.text.toString())) {
                    repository.clearPin()
                    addLog(LogCategory.INFO, "PIN entfernt.")
                    showTab(Tab.CONFIG)
                } else {
                    toast("Falsche PIN.")
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /* ================================================================== */
    /*  LOG-TERMINAL                                                      */
    /* ================================================================== */

    private fun buildLogCard(): LinearLayout {
        val card = makeCard()
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow.addView(makeText("Filter:", 11f, Palette.TEXT_MUTED))
        val all = makeText("ALLE", 10f, Palette.ACCENT_GREEN, true).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { logFilter = null; refreshLogView() }
        }
        filterRow.addView(all)
        for (category in LogCategory.values()) {
            val item = makeText(category.label, 10f, Palette.TEXT_MUTED, true).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener { logFilter = category; refreshLogView() }
            }
            filterRow.addView(item)
        }
        card.addView(filterRow)
        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logScroll = ScrollView(this).apply { addView(logContainer) }
        card.addView(logScroll, LinearLayout.LayoutParams(MATCH, dp(190)).apply {
            setMargins(0, dp(6), 0, 0)
        })
        refreshLogView()
        return card
    }

    private fun addLog(category: LogCategory, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logEntries.addLast(LogEntry(time, category, text))
        while (logEntries.size > 400) logEntries.removeFirst()
        scheduleLogRefresh()
    }

    private fun scheduleLogRefresh() {
        if (logRefreshScheduled) return
        logRefreshScheduled = true
        val now = System.currentTimeMillis()
        val delay = (UI_REFRESH_INTERVAL_MS - (now - lastLogRefresh)).coerceAtLeast(0L)
        handler.postDelayed({
            logRefreshScheduled = false
            lastLogRefresh = System.currentTimeMillis()
            refreshLogView()
        }, delay)
    }

    private fun refreshLogView() {
        val container = logContainer ?: return
        container.removeAllViews()
        val filter = logFilter
        val entries = logEntries.filter { filter == null || it.category == filter }.takeLast(200)
        if (entries.isEmpty()) {
            container.addView(makeText("Keine Log-Einträge.", 11f, Palette.TEXT_MUTED))
            return
        }
        for (entry in entries) {
            val color = when (entry.category) {
                LogCategory.ERROR -> Palette.ACCENT_RED
                LogCategory.TX -> Palette.ACCENT_CYAN
                LogCategory.RX -> Palette.ACCENT_GREEN
                LogCategory.FLASH -> Palette.ACCENT_ORANGE
                LogCategory.INFO -> Palette.TEXT_SECONDARY
            }
            container.addView(makeText(
                "${entry.time} [${entry.category.label}] ${entry.text}",
                10f, color
            ))
        }
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    /* ================================================================== */
    /*  SYSTEMLEISTE / RECONNECT / NOTAUS-RAMPE                           */
    /* ================================================================== */

    private fun startSystemBar() {
        stopSystemBar()
        sysBarRunnable = object : Runnable {
            override fun run() {
                sysLinkView?.text = linkState.name
                sysMtuView?.text = "MTU ${bleEngine?.currentMtu() ?: "—"}"
                sysQueueView?.text = "Q ${bleEngine?.queueDepth() ?: 0}"
                sysRssiView?.text = if (lastRssi == Int.MIN_VALUE) "RSSI —" else "RSSI $lastRssi"
                if (engineReady) bleEngine?.readRssi()
                handler.postDelayed(this, 1_000L)
            }
        }
        handler.post(sysBarRunnable!!)
    }

    private fun stopSystemBar() {
        sysBarRunnable?.let { handler.removeCallbacks(it) }
        sysBarRunnable = null
    }

    private fun cancelReconnectRunnable() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    /**
     * Baut eine bestehende Verbindung vollstaendig ab, BEVOR eine neue Engine
     * erzeugt wird (Geraetewechsel).
     *
     * PHASE 5 FIX: Zuvor wurde [bleEngine] beim Wechsel einfach ueberschrieben,
     * ohne die alte GATT zu trennen -> zwei parallele Verbindungen. Hier wird die
     * alte Engine getrennt (ohne UI-Callback, da [connectToDevice] den Zustand
     * direkt neu setzt) und der abgeleitete Zustand zurueckgesetzt.
     */
    private fun teardownActiveConnection() {
        bleEngine?.disconnect(notifyListener = false)
        bleEngine = null
        engineReady = false
        stopTelemetry()
        stopBleSession()
        connectedDevice = null
        linkState = BleLinkState.IDLE
    }

    /**
     * Bewusstes Trennen durch den Nutzer.
     *
     * Setzt [userInitiatedDisconnect], damit [scheduleReconnect] NICHTS plant,
     * bricht einen bereits geplanten Reconnect ab und laesst [BleEngine] das
     * Ende melden. Der UI-Reset passiert danach zentral in [onDisconnected].
     */
    private fun manualDisconnect() {
        userInitiatedDisconnect = true
        cancelReconnectRunnable()
        reconnectAttempts = 0
        addLog(LogCategory.INFO, "Verbindung wird auf Wunsch getrennt.")
        bleEngine?.disconnect()
        // Falls keine Engine mehr existiert (z. B. nach Prozess-Neuaufbau):
        // Zustand trotzdem sauber zuruecksetzen, statt "verbunden" anzuzeigen.
        if (bleEngine == null) onDisconnected(false)
    }

    private fun scheduleReconnect() {
        // PHASE 4: Entscheidung und Backoff liegen in [ReconnectPolicy] und sind
        // damit ohne Android testbar. Verhalten unveraendert:
        // kein Reconnect bei deaktivierter Einstellung, nach bewusstem Trennen
        // oder nach Ausschoepfen der Versuche.
        if (!ReconnectPolicy.shouldSchedule(
                autoReconnect = autoReconnectEnabled,
                userInitiated = userInitiatedDisconnect,
                attempts = reconnectAttempts,
                maxAttempts = RECONNECT_MAX_ATTEMPTS
            )
        ) {
            if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
                addLog(LogCategory.ERROR, "Reconnect aufgegeben nach $reconnectAttempts Versuchen.")
            }
            return
        }
        cancelReconnectRunnable()
        val delay = ReconnectPolicy.delayMillis(
            reconnectAttempts, RECONNECT_BACKOFF_BASE_MS, RECONNECT_BACKOFF_MAX_MS
        )
        reconnectAttempts++
        addLog(LogCategory.INFO, "Reconnect in ${delay} ms (Versuch $reconnectAttempts).")
        reconnectRunnable = Runnable {
            // PHASE 7 FIX: Beim Feuern erneut pruefen. Wurde "Auto-Reconnect"
            // waehrend des Backoff-Fensters abgeschaltet oder inzwischen bewusst
            // getrennt, darf KEINE Verbindung aufgebaut werden.
            if (ReconnectPolicy.shouldFire(autoReconnectEnabled, userInitiatedDisconnect)) {
                addLog(LogCategory.INFO, "Reconnect-Versuch…")
                linkState = linkState.transition(BleLinkEvent.CONNECT_REQUESTED)
                bleEngine?.reconnect()
            } else {
                addLog(LogCategory.INFO, "Reconnect verworfen (Auto-Reconnect aus oder bewusst getrennt).")
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun beginSafeStopRamp(reason: String) {
        if (rampActive) return
        rampActive = true
        rampTicks = 0
        rampReason = reason
        addLog(LogCategory.ERROR, "NOTAUS: $reason")
        sendCommand(adapter().buildEmergencyCutFrame(), "emergencyCut", true)
        rampRunnable = object : Runnable {
            override fun run() {
                if (!rampActive) return
                rampTicks++
                val remaining = (maxSpeedKmh - RAMP_CUT_SPEED_KMH)
                val target = (maxSpeedKmh - remaining * rampTicks / RAMP_MAX_TICKS)
                    .toInt().coerceAtLeast(0)
                sendCommand(
                    adapter().buildSpeedCeilingFrame(target),
                    "ramp=$target", true, silent = true
                )
                if (rampTicks >= RAMP_MAX_TICKS) {
                    rampActive = false
                    addLog(LogCategory.INFO, "Notaus-Rampe abgeschlossen (Ziel $target km/h).")
                } else {
                    handler.postDelayed(this, RAMP_TICK_MS)
                }
            }
        }
        handler.post(rampRunnable!!)
    }

    private fun endSafeStopRamp() {
        rampActive = false
        rampRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null
    }

    /* ================================================================== */
    /*  BleEngine.Listener                                                */
    /* ================================================================== */

    /**
     * Startet den Vordergrunddienst, damit Android den Prozess (und damit den
     * GATT-Client in [BleEngine]) beim App-Wechsel oder bei ausgeschaltetem
     * Display nicht sofort freigibt. Der Dienst besitzt die Verbindung NICHT —
     * es entsteht also keine zweite GATT-Verbindung.
     *
     * Ab Android 12 darf ein Vordergrunddienst nicht beliebig aus dem
     * Hintergrund gestartet werden; der Aufruf ist deshalb abgesichert. Er
     * erfolgt hier aus [onReady], also regelmaessig im Vordergrund.
     */
    private fun startBleSession() {
        try {
            BleSessionService.start(
                this,
                "Syrak ScooterLab",
                "Verbindung zu ${detectedModel.displayName} aktiv"
            )
        } catch (e: Exception) {
            addLog(LogCategory.ERROR, "Hintergrunddienst nicht gestartet: ${e.message}")
        }
    }

    private fun stopBleSession() {
        try { BleSessionService.stop(this) } catch (_: Exception) {}
    }

    override fun onLog(line: String) {
        runOnUiThread {
            // PHASE 9: Fehlerhafte BLE-/Protokoll-Zeilen zusaetzlich im
            // Diagnose-Log mit Zeitstempel erfassen (tracebares Fehlerlog).
            val lower = line.lowercase(Locale.ROOT)
            if (lower.contains("exception") || lower.contains("fehlgeschlagen") ||
                lower.contains("timeout") || lower.contains("fehler")
            ) {
                commErrorCount++
                diagnosticLog.add(
                    SystemClock.elapsedRealtime(), DiagnosticSeverity.ERROR, "BLE", line
                )
            }
            addLog(LogCategory.INFO, line)
        }
    }

    override fun onConnected(address: String) {
        runOnUiThread {
            linkState = linkState.transition(BleLinkEvent.GATT_CONNECTED)
            commErrorCount = 0
            htConnectedAt = SystemClock.elapsedRealtime()
            htReadyAt = 0L
            htDisconnectedAt = 0L
            htUnexpectedDisconnect = false
            diagnosticLog.add(
                SystemClock.elapsedRealtime(), DiagnosticSeverity.INFO,
                "GATT_CONNECTED", "GATT verbunden: $address"
            )
            addLog(LogCategory.INFO, "GATT verbunden: $address")
            if (::statusText.isInitialized) statusText.text = "Verbunden. Service-Discovery läuft…"
        }
    }

    override fun onReady(address: String, protocol: BleProtocol, mtu: Int) {
        runOnUiThread {
            engineReady = true
            htReadyAt = SystemClock.elapsedRealtime()
            activeProtocol = protocol
            linkState = linkState.transition(BleLinkEvent.READY)
            reconnectAttempts = 0
            addLog(LogCategory.INFO, "Bereit: ${protocol.displayName}, MTU $mtu")
            if (::statusText.isInitialized) {
                statusText.text = "Bereit: ${detectedModel.displayName} [${detectedConfidence.label}] (${protocol.displayName})"
            }
            lastConnectFailed = false
            // PHASE 12.7 A: zuletzt verwendeten Scooter merken (kein Auto-Connect).
            repository.lastDeviceAddress = address
            repository.lastDeviceName = connectedDevice?.name ?: address
            refreshConnectionStage()
            startTelemetry()
            // Verbindung ist vollstaendig bereit -> Prozess fuer App-Wechsel
            // und Display-Off priorisieren (Dienst besitzt die GATT NICHT).
            startBleSession()
            if (currentTab == Tab.INFO) showTab(Tab.INFO)
        }
    }

    override fun onDisconnected(unexpected: Boolean) {
        runOnUiThread {
            engineReady = false
            htDisconnectedAt = SystemClock.elapsedRealtime()
            htUnexpectedDisconnect = unexpected
            disconnectCount++
            if (unexpected) lastConnectFailed = true
            refreshConnectionStage()
            hardwareTest.recordError(
                HardwareErrorKind.DISCONNECT,
                if (unexpected) "Verbindung unerwartet getrennt." else "Verbindung getrennt.",
                htDisconnectedAt
            )
            linkState = linkState.transition(BleLinkEvent.DISCONNECTED)
            // PHASE 8.1 FIX (Sicherheitsaudit, Punkt 2): Eine laufende Not-Aus-Rampe
            // muss beim Verbindungsabbruch sauber beendet werden. Bisher lief sie
            // weiter (sendCommand() verwarf die Schreibversuche still), und
            // rampActive blieb bis zu 10 s true — in dieser Zeit haette ein erneuter
            // Not-Aus (beginSafeStopRamp) wirkungslos abgebrochen. Jetzt: Rampe
            // stoppen, damit Not-Aus nach einem Reconnect sofort wieder greift.
            endSafeStopRamp()
            stopTelemetry()
            stopBleSession()
            // PHASE 4 FIX: connectedDevice wurde bisher NIE zurueckgesetzt.
            // Nach jeder Trennung (auch unerwartet) blieb das Geraet in der Liste
            // als "VERBUNDEN" markiert und der Verbinden-Button deaktiviert —
            // ein erneutes Verbinden war damit nicht mehr moeglich.
            connectedDevice = null
            addLog(
                if (unexpected) LogCategory.ERROR else LogCategory.INFO,
                if (unexpected) "Verbindung unerwartet getrennt." else "Verbindung getrennt."
            )
            if (::statusText.isInitialized) statusText.text = "Nicht verbunden."
            updateDeviceList()
            if (unexpected) scheduleReconnect()
            // Erst NACH der Reconnect-Entscheidung zuruecksetzen: sonst wuerde
            // ein bewusstes Trennen den naechsten Abbruch wieder reconnects faehig
            // machen, obwohl der Nutzer genau das nicht wollte.
            userInitiatedDisconnect = false
        }
    }

    override fun onTelemetry(t: Telemetry) {
        runOnUiThread {
            lastTelemetry = t
            lastTelemetryAtElapsed = SystemClock.elapsedRealtime()
            updateTrip(t)
            scheduleTelemetryApply()
        }
    }

    override fun onWriteResult(label: String, frame: ByteArray, success: Boolean, detail: String) {
        runOnUiThread {
            val now = SystemClock.elapsedRealtime()
            hardwareTest.recordFrame(FrameDirection.TX, ScooterFrames.toHex(frame), now, label)
            if (!success) {
                hardwareTest.recordError(HardwareErrorKind.WRITE_FAILED, "$label: $detail", now)
            }
            if (success) {
                addLog(LogCategory.RX, "$label bestätigt (${ScooterFrames.toHex(frame)})")
            } else {
                commErrorCount++
                diagnosticLog.add(
                    SystemClock.elapsedRealtime(), DiagnosticSeverity.ERROR,
                    "WRITE_FAILED", "$label fehlgeschlagen: $detail"
                )
                addLog(LogCategory.ERROR, "$label fehlgeschlagen: $detail")
            }
        }
    }

    override fun onRawNotification(bytes: ByteArray) {
        val hex = ScooterFrames.toHex(bytes)
        runOnUiThread {
            hardwareTest.recordFrame(
                FrameDirection.RX, hex, SystemClock.elapsedRealtime(), "notification"
            )
        }
    }

    /**
     * PHASE 16.2: Empfangsdiagnose der Engine uebernehmen und die Diagnose-Karte
     * aktualisieren. Wird vom BLE-Callback-Thread aufgerufen -> UI-Thread.
     */
    override fun onTelemetryDiagnostics(snapshot: TelemetryDiagnosticsSnapshot) {
        lastTelemetryDiag = snapshot
        runOnUiThread { applyTelemetryDiagnostics(snapshot) }
    }

    /** Schreibt einen Diagnose-Schnappschuss in die "Telemetrie-Empfang"-Karte. */
    private fun applyTelemetryDiagnostics(s: TelemetryDiagnosticsSnapshot) {
        diagRxNotifications?.text = "${s.notifications}"
        diagRxBytes?.text = "${s.bytes} B"
        diagRxLast?.text = s.lastRxAtMs?.let { ms ->
            val age = SystemClock.elapsedRealtime() - ms
            "vor ${age} ms"
        } ?: "—"
        diagRxFrames?.text = "${s.framesDetected} (${s.invalidFrames})"
        diagRxDecode?.text = "${s.decodeSuccess} / ${s.decodeFailure}"
        diagRxBuffer?.text = "${s.bufferedBytes} B"
        // PHASE 16.3: Adapter nur als "erkannt" zeigen, wenn er aus einem
        // bekannten Service abgeleitet wurde — sonst ehrlich "(angenommen)".
        diagRxAdapter?.text = when {
            s.activeAdapter.isBlank() -> "—"
            s.adapterConfirmed -> s.activeAdapter
            else -> "${s.activeAdapter} (angenommen)"
        }
        diagRxNotify?.text = if (s.notifyActive) "aktiv" else "inaktiv"
        diagRxNotifyChar?.text = when {
            s.notifyCharUuid.isNotBlank() -> s.notifyCharUuid
            s.discoveryDone -> "keine gefunden"
            else -> "—"
        }
        diagRxCccd?.text = when {
            s.cccdWriteStatus != null && s.cccdWriteStatus != TelemetryDiagnostics.GATT_SUCCESS ->
                "write-status=${s.cccdWriteStatus}"
            s.notifyActive -> "aktiv"
            s.cccdWriteRequested && !s.cccdWriteAccepted -> "write abgelehnt"
            s.cccdWriteRequested -> "write angefordert"
            s.cccdPresent -> "vorhanden"
            s.discoveryDone -> "fehlt"
            else -> "—"
        }
        diagRxCause?.text = s.missingCause
    }

    override fun onReadableCharacteristics(uuids: List<String>) {
        runOnUiThread {
            readPropertyFound = uuids.isNotEmpty()
            if (uuids.isNotEmpty()) {
                addLog(LogCategory.INFO, "Lesbare Characteristics: ${uuids.size}")
            }
        }
    }

    override fun onCharacteristicReadResult(outcome: ReadOutcome) {
        runOnUiThread {
            lastReadOutcome = outcome
            if (outcome.success) readSuccessCount++
            hardwareTest.recordRead(
                com.syrak.scooterlab.diagnostics.ReadRecord(
                    uuid = outcome.uuid,
                    status = outcome.status.name,
                    success = outcome.success,
                    byteCount = outcome.byteCount,
                    durationMs = outcome.durationMs,
                    atMs = SystemClock.elapsedRealtime(),
                    detail = outcome.message
                )
            )
            addLog(
                if (outcome.success) LogCategory.INFO else LogCategory.ERROR,
                "Read: ${outcome.summary(AppLanguage.DE)}"
            )
        }
    }

    override fun onFlashProgress(written: Int, total: Int) {
        runOnUiThread {
            val percent = if (total > 0) (written * 100 / total) else 0
            flashProgressBar?.progress = percent
            flashStatusText?.text = "Flash: $written / $total Bytes ($percent %)"
        }
    }

    override fun onFlashFinished(success: Boolean, message: String) {
        runOnUiThread {
            addLog(if (success) LogCategory.FLASH else LogCategory.ERROR, "Flash: $message")
            flashFlashedOk = success
            flashStatusText?.text = message
            AlertDialog.Builder(this)
                .setTitle(if (success) "Flash abgeschlossen" else "Flash fehlgeschlagen")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onRssi(rssi: Int) {
        runOnUiThread {
            lastRssi = rssi
            rssiSamples.addLast(LinkSample(rssi, SystemClock.elapsedRealtime()))
            while (rssiSamples.size > MAX_RSSI_SAMPLES) rssiSamples.removeFirst()
            hardwareTest.recordRssi(rssi, SystemClock.elapsedRealtime())
            sysRssiView?.text = "RSSI $rssi"
        }
    }
}