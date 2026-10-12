Package com.syrak.scooterlab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/* ============================================================================
 *  SYRAK SCOOTERLAB  —  MainActivity.kt  (v7 Enterprise Stability Edition)
 *  ----------------------------------------------------------------------------
 *  Vollständiger Erhalt aller UI-Ansichten, Tabs und Features.
 *  Erweitert um Enterprise-Stabilität, Thread-Safety, Coalesced UI-Updates,
 *  Slider-Debounce, gesicherte Notaus-Rampe und ZIP-Bomb-Schutz.
 * ==========================================================================*/

/* ---------------------------------------------------------------------------
 *  DESIGN TOKENS
 * ------------------------------------------------------------------------- */
object Palette {
    val BG = Color.rgb(14, 17, 22)
    val SURFACE = Color.rgb(27, 33, 41)
    val SURFACE_ALT = Color.rgb(20, 24, 31)
    val SURFACE_DEEP = Color.rgb(16, 20, 26)
    val ACCENT_RED = Color.rgb(220, 60, 60)
    val ACCENT_GREEN = Color.rgb(0, 210, 130)
    val ACCENT_BLUE = Color.rgb(45, 130, 255)
    val ACCENT_ORANGE = Color.rgb(255, 165, 0)
    val ACCENT_YELLOW = Color.rgb(255, 214, 10)
    val ACCENT_CYAN = Color.rgb(0, 200, 220)
    val GRID_LINE = Color.rgb(45, 55, 70)
    val TEXT_PRIMARY = Color.WHITE
    val TEXT_SECONDARY = Color.rgb(145, 155, 170)
    val TEXT_MUTED = Color.rgb(125, 135, 150)
    val DIVIDER = Color.rgb(38, 46, 58)
}

/* ---------------------------------------------------------------------------
 *  DATENMODELLE
 * ------------------------------------------------------------------------- */
data class ScooterDevice(
    val device: BluetoothDevice?,
    val name: String,
    val address: String,
    val rssi: Int,
    val isScooterCandidate: Boolean,
    val isDemo: Boolean = false
)

enum class Region(val code: String, val displayName: String, val legalSpeedKmh: Int) {
    DE("DE", "Deutschland", 20),
    AT("AT", "Österreich", 25),
    CH("CH", "Schweiz", 20),
    US("US", "United States", 32),
    WORLD("XX", "International", 25);

    companion object {
        fun fromCountry(code: String): Region = when (code.uppercase(Locale.ROOT)) {
            "DE" -> DE
            "AT" -> AT
            "CH" -> CH
            "US" -> US
            else -> WORLD
        }
    }
}

enum class BleProtocol(val displayName: String) {
    NINEBOT("Ninebot / Xiaomi (5A A5)"),
    NAVEE_BRIGHTWAY("Navee / Brightway (FFE1)")
}

enum class FlashMode(val displayName: String, val description: String) {
    SOFT_PATCH(
        "Soft-Patch / RAM-Overlay",
        "Volatiles Tuning ohne Veränderung des dauerhaften Speichers. Per Panik-Button in <1 s vollständig löschbar."
    ),
    HARD_FLASH(
        "Echtes MCU/ESC-Firmware-Flashen (.bin)",
        "Schreibt .bin-Sektoren dauerhaft in den Controller-Speicher. Nur nach ausdrücklicher Bestätigung."
    )
}

data class ScooterProfile(
    val name: String,
    val maxSpeed: Int,
    val zeroStart: Int,
    val kers: Int,
    val cruiseControl: Boolean
)

enum class Tab { INFO, TOOLS, DASHBOARD, FLASH, CONFIG }

data class FirmwarePackage(
    val id: String,
    val name: String,
    val version: String,
    val target: String,
    val sourceUrl: String,
    val recommendedMaxKmh: Int,
    val notes: String
)

data class Telemetry(
    val speedKmh: Float? = null,
    val voltageV: Float? = null,
    val currentA: Float? = null,
    val batteryPercent: Int? = null,
    val motorTempC: Float? = null,
    val cellMinV: Float? = null,
    val cellMaxV: Float? = null,
    val cellVoltages: List<Float>? = null,
    val designCapacityMah: Int? = null,
    val fullChargeCapacityMah: Int? = null,
    val cycleCount: Int? = null,
    val bmsTempC: Float? = null,
    val rawHex: String = "",
    val source: BleProtocol = BleProtocol.NINEBOT
)

data class RegisterSnapshot(
    val speedCeiling: Int? = null,
    val zeroStart: Int? = null,
    val kers: Int? = null,
    val cruise: Boolean? = null,
    val locked: Boolean? = null,
    val rawReadback: List<ByteArray> = emptyList()
)

/* ---------------------------------------------------------------------------
 *  LOG-TERMINAL (Neo-Hacker Pro)
 * ------------------------------------------------------------------------- */
enum class LogCategory(val label: String) {
    INFO("INFO"), TX("TX"), RX("RX"), FLASH("FLASH"), ERROR("FEHLER")
}

data class LogEntry(val time: String, val category: LogCategory, val text: String)

enum class FirmwareSource(
    val displayName: String,
    val reference: String,
    val description: String
) {
    MI_FW_INFO(
        "mi-fw-info (Streamlit)",
        "Community-Datenbank • Referenz: mi-fw-info (Streamlit-App).",
        "Community-Datenbank fuer Navee/Brightway- & Xiaomi-Custom-Patches (DRV/BMS)."
    ),
    SCOOTER_HACKING(
        "ScooterHacking / SHFW",
        "Community-Projekt • Referenz: ScooterHacking (SHFW).",
        "SHFW-Repository fuer Segway-Ninebot & Xiaomi - volle Parameterkontrolle."
    ),
    NAVEE_FLASHER(
        "Navee Flasher Repo",
        "Community-Projekt • Referenz: Navee-Flasher-Repository.",
        "Verifizierte MCU/DRV-Binaries fuer GT3 Max, GT3 Pro, ST3, N65, S65, V40."
    ),
    M365_DOWNG(
        "M365 DownG / Ninebot Flasher",
        "Community-Projekt • Referenz: M365 DownG / Ninebot Flasher.",
        "DownG- & Ninebot-Flasher-Repositories (Ninebot/Xiaomi Stock & Custom)."
    ),
    FACTORY(
        "Hersteller / Werks-OTA",
        "Hersteller-Kanal • Referenz: offizielles Werks-OTA (Segway-Ninebot).",
        "Offizielle Werks-Firmware (legaler Auslieferungszustand)."
    )
}

data class TripData(
    var distanceKm: Double = 0.0,
    var maxSpeedKmh: Double = 0.0,
    var speedSum: Double = 0.0,
    var speedSamples: Int = 0,
    var energyWh: Double = 0.0,
    var startMillis: Long = 0L,
    var lastMillis: Long = 0L
) {
    val avgSpeedKmh: Double get() = if (speedSamples > 0) speedSum / speedSamples else 0.0
    val durationMillis: Long get() = if (startMillis == 0L) 0L else (lastMillis - startMillis)
}

enum class RideMode(val displayName: String, val description: String) {
    NORMAL("Normal", "Werksseitige Leistungsabgabe."),
    ECO_SAVER("Eco-Saver", "Drosselt sanft unter 20 % Akkustand, um die Reichweite zu maximieren."),
    SPORT_PLUS("Sport-Plus", "Maximale Beschleunigung + temporaerer Phasenstrom unter Thermalschutz.")
}

enum class ScooterModel(
    val displayName: String,
    val matchKeywords: List<String>,
    val easySpeedKmh: Int,
    val absMaxKmh: Int,
    val nominalVoltage: Float,
    val maxCells: Int,
    val panicHotkey: String,
    val stealthUnlockHotkey: String,
    val protocol: BleProtocol = BleProtocol.NINEBOT
) {
    NINEBOT_MAX_G2("Segway-Ninebot Max G2", listOf("max g2", "g2 pro", "g2"), 32, 40, 36.0f, 10,
        "Bremshebel ziehen + Power-Taste 3 s halten", "Power-Taste 3× kurz drücken"),
    NINEBOT_MAX_G30("Segway-Ninebot Max G30", listOf("max g30", "g30d", "g30lp", "g30"), 30, 40, 36.0f, 10,
        "Bremshebel ziehen + Power-Taste 3 s halten", "Power-Taste 3× kurz drücken"),
    NINEBOT_F2_PRO("Segway-Ninebot F2 Pro", listOf("f2 pro", "f2", "f40", "f30", "f20"), 30, 38, 36.0f, 10,
        "Bremshebel 2× + Power-Taste", "Power-Taste 3× kurz drücken"),
    NINEBOT_ES("Segway-Ninebot ES-Serie", listOf("es1", "es2", "es3", "es4", "ninebot es"), 25, 32, 36.0f, 10,
        "Bremshebel + Power-Taste 2 s", "Power-Taste 3× kurz drücken"),
    XIAOMI_PRO_2("Xiaomi Pro 2", listOf("pro 2", "pro2", "mi pro 2"), 30, 35, 36.0f, 10,
        "Bremshebel ziehen + Power-Taste 3 s halten", "Power-Taste 3× kurz drücken"),
    XIAOMI_M365("Xiaomi M365 / 1S", listOf("m365", "1s", "essential", "mi scooter"), 28, 32, 36.0f, 10,
        "Bremshebel ziehen + Power-Taste 3 s halten", "Power-Taste 3× kurz drücken"),
    NAVEE_GT3_MAX("Navee GT3 Max", listOf("gt3 max", "navee gt3 max", "gt3", "navee"), 32, 45, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_GT3_PRO("Navee GT3 Pro", listOf("gt3 pro", "navee gt3 pro"), 32, 42, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_ST3("Navee ST3", listOf("navee st3", "st3 pro", "st3"), 30, 40, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_N65("Navee N65", listOf("navee n65", "n65 pro", "n65"), 30, 40, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_V40("Navee V40", listOf("navee v40", "v40 pro", "v40"), 30, 40, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_S65("Navee S65 / N65 / V40", listOf("s65", "n65", "v40", "navee s"), 30, 40, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    SOFLOW_SO4("SoFlow SO4 / SO6", listOf("soflow", "so4", "so6", "so8", "so3"), 25, 32, 36.0f, 10,
        "Bremshebel + Power-Taste 3 s", "Power-Taste 3× kurz drücken"),
    UNKNOWN("Unbekanntes Modell", emptyList(), 25, 35, 36.0f, 10,
        "Bremshebel + Power-Taste 3 s", "Power-Taste 3× kurz drücken");

    val isKnown: Boolean get() = this != UNKNOWN

    companion object {
        fun detect(name: String, address: String): ScooterModel {
            val n = name.lowercase(Locale.ROOT)
            var best: ScooterModel? = null
            var bestScore = 0
            for (m in values()) {
                if (m == UNKNOWN) continue
                var score = 0
                for (kw in m.matchKeywords) if (n.contains(kw)) score += kw.length
                if (score > bestScore) { bestScore = score; best = m }
            }
            return best ?: UNKNOWN
        }
    }
}

object FirmwareCatalog {
    private val shfwDrv = FirmwarePackage("shfw-drv", "SHFW DRV", "0.3.0", "DRV", "Referenz: ScooterHacking", 32, "ScooterHacking Firmware für Ninebot/Xiaomi.")
    private val shfwBms = FirmwarePackage("shfw-bms", "SHFW BMS", "1.5.5", "BMS", "Referenz: ScooterHacking", 0, "BMS-Firmware für Ninebot/Xiaomi.")
    private val stockDrv = FirmwarePackage("stock-drv", "Werks-DRV (Stock)", "factory", "DRV", "Referenz: Werks-OTA", 20, "Original-Werksfirmware.")
    private val stockBms = FirmwarePackage("stock-bms", "Werks-BMS (Stock)", "factory", "BMS", "Referenz: Werks-OTA", 0, "Original-BMS-Firmware.")
    private val naveeProfile = FirmwarePackage("navee-ble-profile", "Navee BLE Performance Profile", "1.0.0", "BLE", "Referenz: Navee", 32, "BLE-Profil-Patch für Navee.")
    private val soflowProfile = FirmwarePackage("soflow-ble-profile", "SoFlow BLE Performance Profile", "1.0.0", "BLE", "Referenz: SoFlow", 25, "BLE-Profil-Patch für SoFlow.")
    private val naveeGt3MaxStockEu = FirmwarePackage("navee-gt3max-stock-eu", "Navee GT3 Max Stock (EU)", "v2.0.3.1", "DRV", "Referenz: Navee", 20, "Original EU-Werksfirmware.")
    private val naveeGt3MaxUsPerf = FirmwarePackage("navee-gt3max-us-perf", "Navee GT3 Max US Performance", "v2.0.3.1-US", "DRV", "Referenz: Navee", 40, "US-Performance-Payload.")
    private val naveeGt3MaxUnlock = FirmwarePackage("navee-gt3max-unlock", "Navee GT3 Max Unlock Profile", "1.0.0", "BLE", "Referenz: Navee", 32, "Unlock-Profil.")
    private val naveeS65StockEu = FirmwarePackage("navee-s65-stock-eu", "Navee S65 / N65 / V40 Stock (EU)", "v1.8.2", "DRV", "Referenz: Navee", 20, "Original EU-Werksfirmware.")
    private val naveeS65Unlock = FirmwarePackage("navee-s65-unlock", "Navee S65 Unlock Profile", "1.0.0", "BLE", "Referenz: Navee", 30, "Unlock-Profil für S65.")
    private val brightwayUnlock = FirmwarePackage("brightway-unlock", "Brightway Engine Unlock", "1.0.0", "BLE", "Referenz: Brightway", 32, "Generisches Brightway-Unlock-Profil.")

    private val miFwInfoNavee = FirmwarePackage("mi-fw-navee", "mi-fw-info Navee/Brightway Patch", "community", "DRV", "mi-fw-info", 32, "Community-Patch.")
    private val miFwInfoXiaomi = FirmwarePackage("mi-fw-xiaomi", "mi-fw-info Xiaomi Custom", "community", "DRV", "mi-fw-info", 32, "Community-Patch.")
    private val shfwRepo = FirmwarePackage("shfw-repo", "SHFW Repository Build", "nightly", "DRV", "ScooterHacking", 32, "SHFW-Build.")
    private val naveeFlasherGt3 = FirmwarePackage("navee-flasher-gt3", "Navee Flasher GT3 Max/Pro", "verified", "DRV", "Navee Flasher", 40, "Verifizierte DRV-Binary.")
    private val naveeFlasherSt3 = FirmwarePackage("navee-flasher-st3", "Navee Flasher ST3/N65/S65/V40", "verified", "DRV", "Navee Flasher", 40, "Verifizierte DRV-Binary.")
    private val m365Downg = FirmwarePackage("m365-downg", "M365 DownG / Ninebot Flasher", "stock", "DRV", "DownG", 25, "Stock-Binary.")

    fun fromSource(source: FirmwareSource, model: ScooterModel): List<FirmwarePackage> {
        val navee = model.protocol == BleProtocol.NAVEE_BRIGHTWAY
        val ninebot = model in listOf(
            ScooterModel.NINEBOT_MAX_G2, ScooterModel.NINEBOT_MAX_G30, ScooterModel.NINEBOT_F2_PRO,
            ScooterModel.NINEBOT_ES, ScooterModel.XIAOMI_PRO_2, ScooterModel.XIAOMI_M365
        )
        return when (source) {
            FirmwareSource.MI_FW_INFO -> if (navee) listOf(miFwInfoNavee, naveeProfile) else listOf(miFwInfoXiaomi, naveeProfile)
            FirmwareSource.SCOOTER_HACKING -> if (ninebot) listOf(shfwRepo, shfwDrv, shfwBms) else listOf(shfwRepo, naveeProfile)
            FirmwareSource.NAVEE_FLASHER -> when (model) {
                ScooterModel.NAVEE_GT3_MAX, ScooterModel.NAVEE_GT3_PRO -> listOf(naveeFlasherGt3, naveeGt3MaxUnlock)
                ScooterModel.NAVEE_ST3, ScooterModel.NAVEE_N65, ScooterModel.NAVEE_V40, ScooterModel.NAVEE_S65 -> listOf(naveeFlasherSt3, naveeS65Unlock)
                else -> listOf(naveeFlasherGt3, naveeProfile)
            }
            FirmwareSource.M365_DOWNG -> if (ninebot) listOf(m365Downg, stockDrv) else listOf(m365Downg, naveeProfile)
            FirmwareSource.FACTORY -> if (navee) listOf(naveeGt3MaxStockEu, naveeS65StockEu, stockDrv) else listOf(stockDrv, stockBms)
        }
    }

    fun forModel(model: ScooterModel): List<FirmwarePackage> = when (model) {
        ScooterModel.NINEBOT_MAX_G2, ScooterModel.NINEBOT_MAX_G30, ScooterModel.NINEBOT_F2_PRO,
        ScooterModel.NINEBOT_ES, ScooterModel.XIAOMI_PRO_2, ScooterModel.XIAOMI_M365 -> listOf(shfwDrv, shfwBms, stockDrv, stockBms)
        ScooterModel.NAVEE_GT3_MAX, ScooterModel.NAVEE_GT3_PRO -> listOf(naveeGt3MaxUnlock, naveeGt3MaxUsPerf, naveeGt3MaxStockEu, naveeProfile)
        ScooterModel.NAVEE_ST3, ScooterModel.NAVEE_N65, ScooterModel.NAVEE_V40, ScooterModel.NAVEE_S65 -> listOf(naveeS65Unlock, naveeS65StockEu, brightwayUnlock, naveeProfile)
        ScooterModel.SOFLOW_SO4 -> listOf(soflowProfile, stockDrv)
        ScooterModel.UNKNOWN -> listOf(stockDrv, stockBms)
    }

    fun recommended(model: ScooterModel): FirmwarePackage? = forModel(model).firstOrNull()
}

object ScooterFrames {
    private const val HEADER_1 = 0x5A
    private const val HEADER_2 = 0xA5

    private fun checksum(frame: ByteArray): Byte {
        var sum = 0
        for (i in 2 until frame.size - 1) sum += frame[i].toInt() and 0xFF
        return ((0x100 - (sum and 0xFF)) and 0xFF).toByte()
    }

    fun buildFrame(address: Int, cmd: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(6 + payload.size)
        frame[0] = HEADER_1.toByte()
        frame[1] = HEADER_2.toByte()
        frame[2] = (payload.size + 2).toByte()
        frame[3] = address.toByte()
        frame[4] = cmd.toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        frame[frame.size - 1] = checksum(frame)
        return frame
    }

    fun zeroStart(speedKmh: Int): ByteArray = buildFrame(0x20, 0x72, byteArrayOf(speedKmh.toByte()))
    fun maxSpeed(speedKmh: Int): ByteArray = buildFrame(0x20, 0x71, byteArrayOf(speedKmh.toByte()))
    fun maxPower(): ByteArray = buildFrame(0x20, 0x73, byteArrayOf(0x01))
    fun lock(locked: Boolean): ByteArray = buildFrame(0x20, 0x7A, byteArrayOf(if (locked) 1 else 0))
    fun germanManeuver(): ByteArray = buildFrame(0x20, 0x78, byteArrayOf(0x01))
    fun policeMode(legalKmh: Int): ByteArray = buildFrame(0x20, 0x79, byteArrayOf(legalKmh.toByte()))
    fun kers(level: Int): ByteArray = buildFrame(0x20, 0x7B, byteArrayOf(level.toByte()))
    fun cruise(enabled: Boolean): ByteArray = buildFrame(0x20, 0x7C, byteArrayOf(if (enabled) 1 else 0))
    fun motorTempGuard(celsius: Int): ByteArray = buildFrame(0x20, 0x7D, byteArrayOf(celsius.toByte()))
    fun emergencyCut(): ByteArray = buildFrame(0x20, 0x7E, byteArrayOf(0x00))
    fun overspeedAlarmMute(mute: Boolean): ByteArray = buildFrame(0x20, 0x7F, byteArrayOf(if (mute) 1 else 0))
    fun readTelemetry(): ByteArray = buildFrame(0x20, 0x01, byteArrayOf(0x00))

    fun toHex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }
}

object NaveeFrames {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val CHAR_WRITE_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"

    private const val H1 = 0xAA
    private const val H2 = 0x55

    const val CMD_HANDSHAKE = 0x01
    const val CMD_SPEED = 0x10
    const val CMD_ZERO_START = 0x11
    const val CMD_REGION = 0x12
    const val CMD_MAX_POWER = 0x13
    const val CMD_OVERSPEED_ALARM_MUTE = 0x14
    const val CMD_GERMAN = 0x18
    const val CMD_LOCK = 0x1A
    const val CMD_KERS = 0x1B
    const val CMD_CRUISE = 0x1C
    const val CMD_TEMP_GUARD = 0x1D
    const val CMD_EMERGENCY = 0x1E
    const val CMD_POLICE = 0x1F
    const val CMD_READ_STATUS = 0x20

    fun buildFrame(cmd: Int, payload: ByteArray): ByteArray {
        val head = ByteArray(4 + payload.size)
        head[0] = H1.toByte()
        head[1] = H2.toByte()
        head[2] = cmd.toByte()
        head[3] = payload.size.toByte()
        System.arraycopy(payload, 0, head, 4, payload.size)
        var sum = 0
        for (b in head) sum += b.toInt() and 0xFF
        val out = head.copyOf(head.size + 1)
        out[out.size - 1] = (sum and 0xFF).toByte()
        return out
    }

    fun handshake(): ByteArray = buildFrame(CMD_HANDSHAKE, byteArrayOf(0x01))
    fun speedCeiling(kmh: Int): ByteArray = buildFrame(CMD_SPEED, byteArrayOf(kmh.toByte()))
    fun zeroStart(kmh: Int): ByteArray = buildFrame(CMD_ZERO_START, byteArrayOf(kmh.toByte()))
    fun regionToggle(regionCode: Int): ByteArray = buildFrame(CMD_REGION, byteArrayOf(regionCode.toByte()))
    fun maxPower(): ByteArray = buildFrame(CMD_MAX_POWER, byteArrayOf(0x01))
    fun overspeedAlarmMute(mute: Boolean): ByteArray = buildFrame(CMD_OVERSPEED_ALARM_MUTE, byteArrayOf(if (mute) 1 else 0))
    fun germanManeuver(): ByteArray = buildFrame(CMD_GERMAN, byteArrayOf(0x01))
    fun lock(locked: Boolean): ByteArray = buildFrame(CMD_LOCK, byteArrayOf(if (locked) 1 else 0))
    fun kers(level: Int): ByteArray = buildFrame(CMD_KERS, byteArrayOf(level.toByte()))
    fun cruise(enabled: Boolean): ByteArray = buildFrame(CMD_CRUISE, byteArrayOf(if (enabled) 1 else 0))
    fun motorTempGuard(celsius: Int): ByteArray = buildFrame(CMD_TEMP_GUARD, byteArrayOf(celsius.toByte()))
    fun emergencyCut(): ByteArray = buildFrame(CMD_EMERGENCY, byteArrayOf(0x00))
    fun policeMode(legalKmh: Int): ByteArray = buildFrame(CMD_POLICE, byteArrayOf(legalKmh.toByte()))
    fun readStatus(): ByteArray = buildFrame(CMD_READ_STATUS, byteArrayOf(0x00))

    fun fromNinebot(frame: ByteArray, alt: Boolean = false): ByteArray? {
        if (frame.size < 6) return null
        if (frame[0] != 0x5A.toByte() || frame[1] != 0xA5.toByte()) return null
        val cmd = frame[4].toInt() and 0xFF
        val payload = frame.copyOfRange(5, frame.size - 1)
        val value = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
        val b: (Int, ByteArray) -> ByteArray = if (alt) { c, p -> buildFrameAlt(c, p) } else { c, p -> buildFrame(c, p) }
        return when (cmd) {
            0x71 -> b(CMD_SPEED, byteArrayOf(value.toByte()))
            0x72 -> b(CMD_ZERO_START, byteArrayOf(value.toByte()))
            0x73 -> b(CMD_MAX_POWER, byteArrayOf(0x01))
            0x78 -> b(CMD_GERMAN, byteArrayOf(0x01))
            0x79 -> b(CMD_POLICE, byteArrayOf(value.toByte()))
            0x7A -> b(CMD_LOCK, byteArrayOf(if (value != 0) 1 else 0))
            0x7B -> b(CMD_KERS, byteArrayOf(value.toByte()))
            0x7C -> b(CMD_CRUISE, byteArrayOf(if (value != 0) 1 else 0))
            0x7D -> b(CMD_TEMP_GUARD, byteArrayOf(value.toByte()))
            0x7E -> b(CMD_EMERGENCY, byteArrayOf(0x00))
            0x7F -> b(CMD_OVERSPEED_ALARM_MUTE, byteArrayOf(if (value != 0) 1 else 0))
            0x01 -> b(CMD_READ_STATUS, byteArrayOf(0x00))
            else -> null
        }
    }

    fun buildFrameAlt(cmd: Int, payload: ByteArray): ByteArray {
        val head = ByteArray(4 + payload.size)
        head[0] = H2.toByte()
        head[1] = H1.toByte()
        head[2] = cmd.toByte()
        head[3] = payload.size.toByte()
        System.arraycopy(payload, 0, head, 4, payload.size)
        var x = 0
        for (bb in head) x = x xor (bb.toInt() and 0xFF)
        val out = head.copyOf(head.size + 1)
        out[out.size - 1] = (x and 0xFF).toByte()
        return out
    }

    fun toHex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }
}

class RamOverlay {
    private val patches = linkedMapOf<String, ByteArray>()
    var active = false
        private set

    fun apply(name: String, frame: ByteArray) { patches[name] = frame; active = true }

    fun flush(): List<String> {
        val keys = patches.keys.toList()
        patches.clear()
        active = false
        return keys
    }

    val size: Int get() = patches.size
    val names: List<String> get() = patches.keys.toList()
}

object TelemetryDecoder {
    private const val NB_PAYLOAD_START = 5
    private const val NB_OFF_SPEED = 4
    private const val NB_OFF_VOLTAGE = 6
    private const val NB_OFF_CURRENT = 8
    private const val NB_OFF_BATTERY = 10
    private const val NB_OFF_TEMP = 11
    private const val NB_OFF_CELLS = 12
    private const val NB_OFF_DESIGN_CAP = 40
    private const val NB_OFF_FULL_CAP = 42
    private const val NB_OFF_CYCLES = 44
    private const val NB_CELL_COUNT = 10
    private const val NB_MIN_LEN = NB_PAYLOAD_START + 12

    fun decodeNinebot(frame: ByteArray): Telemetry? {
        if (!hasRange(frame, 0, 2)) return null
        if (frame.size < NB_MIN_LEN) return null
        if (frame[0] != 0x5A.toByte() || frame[1] != 0xA5.toByte()) return null
        val p = NB_PAYLOAD_START
        return try {
            val cells = decodeCellArray(frame, p + NB_OFF_CELLS, NB_CELL_COUNT)
            Telemetry(
                speedKmh = u16leOrNull(frame, p + NB_OFF_SPEED)?.takeIf { it != 0xFFFF }?.div(10f),
                voltageV = u16leOrNull(frame, p + NB_OFF_VOLTAGE)?.takeIf { it != 0 && it != 0xFFFF }?.div(100f),
                currentA = i16leOrNull(frame, p + NB_OFF_CURRENT)?.takeIf { it != -32768 }?.div(100f),
                batteryPercent = u8OrNull(frame, p + NB_OFF_BATTERY)?.takeIf { it in 0..100 },
                motorTempC = i8OrNull(frame, p + NB_OFF_TEMP)?.takeIf { it in -40..125 }?.toFloat(),
                cellMinV = cells?.minOrNull(),
                cellMaxV = cells?.maxOrNull(),
                cellVoltages = cells,
                designCapacityMah = u16leOrNull(frame, p + NB_OFF_DESIGN_CAP)?.takeIf { it > 0 },
                fullChargeCapacityMah = u16leOrNull(frame, p + NB_OFF_FULL_CAP)?.takeIf { it > 0 },
                cycleCount = u16leOrNull(frame, p + NB_OFF_CYCLES)?.takeIf { it != 0xFFFF },
                rawHex = ScooterFrames.toHex(frame),
                source = BleProtocol.NINEBOT
            )
        } catch (_: Exception) { null }
    }

    private const val NV_PAYLOAD_START = 4
    private const val NV_OFF_SPEED = 0
    private const val NV_OFF_VOLTAGE = 2
    private const val NV_OFF_CURRENT = 4
    private const val NV_OFF_BATTERY = 6
    private const val NV_OFF_TEMP = 7
    private const val NV_OFF_CELLS = 8
    private const val NV_OFF_DESIGN_CAP = 36
    private const val NV_OFF_FULL_CAP = 38
    private const val NV_OFF_CYCLES = 40
    private const val NV_CELL_COUNT = 13
    private const val NV_MIN_LEN = NV_PAYLOAD_START + 8

    fun decodeNavee(frame: ByteArray): Telemetry? {
        if (!hasRange(frame, 0, 2)) return null
        if (frame.size < NV_MIN_LEN) return null
        if (frame[0] != 0xAA.toByte() || frame[1] != 0x55.toByte()) return null
        val p = NV_PAYLOAD_START
        return try {
            val cells = decodeCellArray(frame, p + NV_OFF_CELLS, NV_CELL_COUNT)
            Telemetry(
                speedKmh = u16leOrNull(frame, p + NV_OFF_SPEED)?.takeIf { it != 0xFFFF }?.div(10f),
                voltageV = u16leOrNull(frame, p + NV_OFF_VOLTAGE)?.takeIf { it != 0 && it != 0xFFFF }?.div(100f),
                currentA = i16leOrNull(frame, p + NV_OFF_CURRENT)?.takeIf { it != -32768 }?.div(100f),
                batteryPercent = u8OrNull(frame, p + NV_OFF_BATTERY)?.takeIf { it in 0..100 },
                motorTempC = i8OrNull(frame, p + NV_OFF_TEMP)?.takeIf { it in -40..125 }?.toFloat(),
                cellMinV = cells?.minOrNull(),
                cellMaxV = cells?.maxOrNull(),
                cellVoltages = cells,
                designCapacityMah = u16leOrNull(frame, p + NV_OFF_DESIGN_CAP)?.takeIf { it > 0 },
                fullChargeCapacityMah = u16leOrNull(frame, p + NV_OFF_FULL_CAP)?.takeIf { it > 0 },
                cycleCount = u16leOrNull(frame, p + NV_OFF_CYCLES)?.takeIf { it != 0xFFFF },
                rawHex = NaveeFrames.toHex(frame),
                source = BleProtocol.NAVEE_BRIGHTWAY
            )
        } catch (_: Exception) { null }
    }

    private fun decodeCellArray(frame: ByteArray, start: Int, count: Int): List<Float>? {
        if (count <= 0) return null
        if (!hasRange(frame, start, count * 2)) return null
        val cells = ArrayList<Float>(count)
        for (i in 0 until count) {
            val v = u16leOrNull(frame, start + i * 2)?.div(1000f) ?: return null
            if (v < 1.5f || v > 4.5f) return null
            cells.add(v)
        }
        return cells
    }

    private fun hasRange(data: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length > 0 && offset + length <= data.size

    private fun u8OrNull(b: ByteArray, i: Int): Int? = if (hasRange(b, i, 1)) (b[i].toInt() and 0xFF) else null
    private fun i8OrNull(b: ByteArray, i: Int): Int? = if (hasRange(b, i, 1)) b[i].toInt() else null
    private fun u16leOrNull(b: ByteArray, i: Int): Int? = if (hasRange(b, i, 2)) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) else null
    private fun i16leOrNull(b: ByteArray, i: Int): Int? {
        val v = u16leOrNull(b, i) ?: return null
        return if (v >= 0x8000) v - 0x10000 else v
    }
}

class BleEngine(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLog(line: String)
        fun onConnected(address: String)
        fun onReady(address: String, protocol: BleProtocol, mtu: Int)
        fun onDisconnected(unexpected: Boolean)
        fun onTelemetry(t: Telemetry)
        fun onWriteResult(label: String, frame: ByteArray, success: Boolean, detail: String)
        fun onFlashProgress(written: Int, total: Int)
        fun onFlashFinished(success: Boolean, message: String)
        fun onRssi(rssi: Int)
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val NAVEE_SERVICE = UUID.fromString(NaveeFrames.SERVICE_UUID)
        private val NAVEE_CHAR_WRITE = UUID.fromString(NaveeFrames.CHAR_UUID)
        private val NAVEE_CHAR_WRITE_ALT = UUID.fromString(NaveeFrames.CHAR_WRITE_UUID)
        private val NINEBOT_SERVICE = UUID.fromString("0000e003-0000-1000-8000-00805f9b34fb")
        private val NINEBOT_CHAR_WRITE = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val NINEBOT_CHAR_WRITE_ALT = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 247
        private const val ATT_HEADER = 3
        private const val MAX_WRITE_RETRIES = 1
        private const val RESPONSE_TIMEOUT_MS = 900L
        private const val FLASH_CHUNK_TIMEOUT_MS = 3000L
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var lastDevice: BluetoothDevice? = null
    @Volatile private var negotiatedMtu = 23
    @Volatile private var connectingDevice: BluetoothDevice? = null

    @Volatile var protocol: BleProtocol = BleProtocol.NINEBOT
        private set
    @Volatile private var naveeHandshakeSent = false
    @Volatile private var naveeUnlocked = false
    @Volatile private var notifyActive = false
    @Volatile private var lastRxMillis = 0L
    private var responseTimeout: Runnable? = null
    private var awaitingAck: PendingWrite? = null

    private class PendingWrite(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val label: String,
        val onResult: ((Boolean) -> Unit)?,
        val retries: Int = 0,
        val alternatives: List<ByteArray> = emptyList(),
        val expectResponse: Boolean = false
    )

    private val writeLock = Any()
    private val writeQueue = ArrayDeque<PendingWrite>()
    @Volatile private var writeInFlight = false
    @Volatile private var currentWrite: PendingWrite? = null
    @Volatile private var initializationStarted = false

    private var flashChunks: List<ByteArray> = emptyList()
    private var flashTotalBytes = 0
    private var flashWrittenBytes = 0
    private var flashIndex = 0
    @Volatile private var flashActive = false
    @Volatile private var flashChunkTimeout: Runnable? = null

    fun setProtocolHint(p: BleProtocol) { protocol = p }
    fun maxWritePayload(): Int = (negotiatedMtu - ATT_HEADER).coerceIn(20, 512)
    fun currentMtu(): Int = negotiatedMtu
    fun queueDepth(): Int = synchronized(writeLock) { writeQueue.size }
    fun isWriteInFlight(): Boolean = synchronized(writeLock) { writeInFlight }

    @SuppressLint("MissingPermission")
    fun readRssi() { try { gatt?.readRemoteRssi() } catch (_: Exception) {} }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        lastDevice = device
        resetConnectionState()
        synchronized(writeLock) { connectingDevice = device }
        listener.onLog("> link: connectGatt(${device.address})")
        val newGatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            listener.onLog("> link: connectGatt() Exception: ${e.message}")
            null
        }
        synchronized(writeLock) { gatt = newGatt; connectingDevice = null }
        if (newGatt == null) listener.onLog("> link: FEHLER — connectGatt() lieferte null")
    }

    @SuppressLint("MissingPermission")
    fun reconnect() {
        val d = lastDevice ?: return
        listener.onLog("> link: auto-reconnect ${d.address}")
        try { gatt?.close() } catch (_: Exception) {}
        resetConnectionState()
        synchronized(writeLock) { gatt = null; connectingDevice = d }
        val newGatt = try {
            d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            listener.onLog("> link: connectGatt() Exception: ${e.message}")
            null
        }
        synchronized(writeLock) { gatt = newGatt; connectingDevice = null }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        lastDevice = null
        val old = synchronized(writeLock) { val o = gatt; gatt = null; connectingDevice = null; o }
        try { old?.disconnect(); old?.close() } catch (_: Exception) {}
        writeChar = null
        notifyChar = null
        resetConnectionState()
        failPendingWrites("disconnect")
    }

    private fun resetConnectionState() {
        synchronized(writeLock) {
            initializationStarted = false
            awaitingAck = null
        }
        cancelResponseTimeout()
        naveeHandshakeSent = false
        naveeUnlocked = false
        notifyActive = false
        lastRxMillis = 0L
    }

    private fun isCurrentGatt(g: BluetoothGatt): Boolean =
        synchronized(writeLock) { gatt === g || (connectingDevice != null && deviceMatches(g, connectingDevice)) }

    private fun deviceMatches(g: BluetoothGatt, d: BluetoothDevice?): Boolean {
        if (d == null) return false
        return try { g.device == d } catch (_: Exception) { false }
    }

    private fun closeStaleGatt(g: BluetoothGatt) { try { g.close() } catch (_: Exception) {} }

    private fun failPendingWrites(reason: String) {
        val drained = ArrayList<PendingWrite>()
        var inFlight: PendingWrite? = null
        synchronized(writeLock) {
            drained.addAll(writeQueue)
            writeQueue.clear()
            inFlight = currentWrite
            currentWrite = null
            writeInFlight = false
            awaitingAck = null
        }
        cancelResponseTimeout()
        listener.onLog("> tx: failPendingWrites($reason) — ${drained.size} wartende Op(s) verworfen")
        for (op in drained) { try { op.onResult?.invoke(false) } catch (_: Exception) {} }
        val hang = inFlight
        if (hang != null && drained.none { it === hang }) { try { hang.onResult?.invoke(false) } catch (_: Exception) {} }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) listener.onLog("> link: GATT-Fehler status=$status")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        listener.onLog("> link: CONNECTED ${g.device.address} — discoverServices()")
                        resetConnectionState()
                        listener.onConnected(g.device.address)
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        listener.onLog("> link: DISCONNECTED (status=$status)")
                        resetConnectionState()
                        failPendingWrites("disconnected")
                        listener.onDisconnected(status != BluetoothGatt.GATT_SUCCESS)
                    }
                }
            } catch (e: Exception) {
                listener.onLog("> link: Exception in onConnectionStateChange: ${e.message}")
                failPendingWrites("onConnectionStateChange exception")
                listener.onDisconnected(true)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("> gatt: Service-Discovery FEHLGESCHLAGEN status=$status")
                    return
                }
                listener.onLog("> gatt: ${g.services.size} Services gefunden")
                val naveeWrite = findChar(g, NAVEE_SERVICE, listOf(NAVEE_CHAR_WRITE, NAVEE_CHAR_WRITE_ALT))
                if (naveeWrite != null) {
                    protocol = BleProtocol.NAVEE_BRIGHTWAY
                    writeChar = naveeWrite.first
                    notifyChar = naveeWrite.first
                    listener.onLog("> gatt: Navee/Brightway erkannt -> NAVEE_BRIGHTWAY")
                } else {
                    val nbWrite = findChar(g, NINEBOT_SERVICE, listOf(NINEBOT_CHAR_WRITE, NINEBOT_CHAR_WRITE_ALT))
                    if (nbWrite != null) {
                        protocol = BleProtocol.NINEBOT
                        writeChar = nbWrite.first
                        notifyChar = nbWrite.first
                        listener.onLog("> gatt: Ninebot/Xiaomi erkannt -> NINEBOT")
                    } else {
                        val generic = firstWritable(g)
                        if (generic != null) {
                            writeChar = generic
                            notifyChar = generic
                            listener.onLog("> gatt: generischer Write-Char ${generic.uuid}")
                        } else {
                            listener.onLog("> gatt: KEIN writable Characteristic gefunden")
                        }
                    }
                }
                listener.onLog("> gatt: requestMtu($REQUESTED_MTU)")
                if (!g.requestMtu(REQUESTED_MTU)) {
                    listener.onLog("> gatt: requestMtu() abgelehnt — nutze Default 23")
                    afterMtu(g)
                }
            } catch (e: Exception) {
                listener.onLog("> gatt: Exception in onServicesDiscovered: ${e.message}")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                listener.onLog("> gatt: MTU = $negotiatedMtu")
                afterMtu(g)
            } catch (e: Exception) {
                listener.onLog("> gatt: Exception in onMtuChanged: ${e.message}")
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            try { if (status == BluetoothGatt.GATT_SUCCESS) listener.onRssi(rssi) } catch (_: Exception) {}
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                cancelResponseTimeout()
                val op: PendingWrite?
                synchronized(writeLock) { op = currentWrite }
                val success = status == BluetoothGatt.GATT_SUCCESS
                if (op != null) {
                    when {
                        !success && op.retries < MAX_WRITE_RETRIES -> {
                            synchronized(writeLock) {
                                writeQueue.addFirst(PendingWrite(op.characteristic, op.data, op.label, op.onResult, op.retries + 1, op.alternatives, op.expectResponse))
                            }
                        }
                        !success && op.alternatives.isNotEmpty() -> {
                            val next = op.alternatives.first()
                            val rest = op.alternatives.drop(1)
                            synchronized(writeLock) {
                                writeQueue.addFirst(PendingWrite(op.characteristic, next, op.label, op.onResult, 0, rest, op.expectResponse))
                            }
                        }
                        else -> {
                            listener.onWriteResult(op.label, op.data, success, "status=$status")
                            op.onResult?.invoke(success)
                            if (!success && protocol == BleProtocol.NAVEE_BRIGHTWAY) naveeUnlocked = false
                            if (success && op.expectResponse && notifyActive && lastRxMillis > 0L && op.alternatives.isNotEmpty()) {
                                synchronized(writeLock) { awaitingAck = op }
                                armAckTimeout(op)
                            }
                        }
                    }
                }
                synchronized(writeLock) {
                    currentWrite = null
                    writeInFlight = false
                }
                processNextWrite()
            } catch (e: Exception) {
                listener.onLog("> tx: Exception in onCharacteristicWrite: ${e.message}")
                failPendingWrites("onCharacteristicWrite exception")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                    notifyActive = true
                    listener.onLog("> gatt: Notify real aktiv")
                }
            } catch (_: Exception) {}
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (!isCurrentGatt(g)) { closeStaleGatt(g); return }
            try {
                lastRxMillis = System.currentTimeMillis()
                val v = ch.value ?: return
                val pending: PendingWrite?
                synchronized(writeLock) { pending = awaitingAck }
                if (pending != null && isControllerAckFor(pending, v)) {
                    cancelResponseTimeout()
                    synchronized(writeLock) { awaitingAck = null }
                }
                handleNotification(v)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun afterMtu(g: BluetoothGatt) {
        try {
            val alreadyStarted = synchronized(writeLock) {
                if (initializationStarted) true else { initializationStarted = true; false }
            }
            if (alreadyStarted) return

            val nChar = notifyChar
            if (nChar != null) {
                val props = nChar.properties
                val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val canIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (canNotify || canIndicate) {
                    g.setCharacteristicNotification(nChar, true)
                    val cccd = nChar.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        val enable = if (canIndicate && !canNotify) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        cccd.value = enable
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
            }

            if (protocol == BleProtocol.NAVEE_BRIGHTWAY && !naveeHandshakeSent) {
                val hs = NaveeFrames.handshake()
                naveeHandshakeSent = true
                enqueueWrite(hs, "navee-unlock") { ok -> if (ok) naveeUnlocked = true }
            }

            listener.onReady(g.device?.address ?: "?", protocol, negotiatedMtu)
        } catch (_: Exception) {}
    }

    private fun findChar(g: BluetoothGatt, serviceUuid: UUID, charUuids: List<UUID>): Pair<BluetoothGattCharacteristic, BluetoothGattService>? {
        for (s in g.services) {
            if (s.uuid != serviceUuid) continue
            for (ch in s.characteristics) if (ch.uuid in charUuids && isWritable(ch)) return ch to s
            for (ch in s.characteristics) if (isWritable(ch)) return ch to s
        }
        return null
    }

    private fun firstWritable(g: BluetoothGatt): BluetoothGattCharacteristic? {
        for (s in g.services) for (ch in s.characteristics) if (isWritable(ch)) return ch
        return null
    }

    private fun isWritable(ch: BluetoothGattCharacteristic): Boolean {
        val p = ch.properties
        return p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 || p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    fun enqueueWrite(data: ByteArray, label: String, onResult: ((Boolean) -> Unit)?): Boolean {
        val c = writeChar
        if (c == null) {
            onResult?.invoke(false)
            return false
        }
        return try {
            synchronized(writeLock) {
                writeQueue.addLast(PendingWrite(c, data, label, onResult))
                if (!writeInFlight) processNextWrite()
            }
            true
        } catch (_: Exception) {
            onResult?.invoke(false)
            false
        }
    }

    fun enqueueCommand(ninebotFrame: ByteArray, label: String, requiresUnlock: Boolean): Boolean {
        val c = writeChar
        if (c == null) return false
        return try {
            val frames = buildOutboundFrames(ninebotFrame)
            val needHandshake = requiresUnlock && protocol == BleProtocol.NAVEE_BRIGHTWAY && !naveeUnlocked
            synchronized(writeLock) {
                if (needHandshake) {
                    writeQueue.addLast(PendingWrite(c, NaveeFrames.handshake(), "navee-unlock") { ok -> if (ok) naveeUnlocked = true })
                }
                writeQueue.addLast(PendingWrite(c, frames.first(), label, null, 0, frames.drop(1), true))
                if (!writeInFlight) processNextWrite()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildOutboundFrames(ninebotFrame: ByteArray): List<ByteArray> {
        if (protocol != BleProtocol.NAVEE_BRIGHTWAY) return listOf(ninebotFrame)
        val primary = NaveeFrames.fromNinebot(ninebotFrame, false)
        val alt = NaveeFrames.fromNinebot(ninebotFrame, true)
        val list = ArrayList<ByteArray>(3)
        if (primary != null) list.add(primary)
        if (alt != null && (primary == null || !alt.contentEquals(primary))) list.add(alt)
        if (list.isEmpty() || !list.last().contentEquals(ninebotFrame)) list.add(ninebotFrame)
        return list
    }

    private fun armAckTimeout(op: PendingWrite) {
        cancelResponseTimeout()
        val r = Runnable {
            val stillAwaiting = synchronized(writeLock) { awaitingAck === op }
            if (stillAwaiting && op.alternatives.isNotEmpty()) {
                val next = op.alternatives.first()
                val rest = op.alternatives.drop(1)
                synchronized(writeLock) { awaitingAck = null }
                val c = writeChar
                if (c != null) {
                    synchronized(writeLock) {
                        writeQueue.addFirst(PendingWrite(c, next, op.label, op.onResult, 0, rest, true))
                        if (!writeInFlight) processNextWrite()
                    }
                }
            }
        }
        responseTimeout = r
        Handler(Looper.getMainLooper()).postDelayed(r, RESPONSE_TIMEOUT_MS)
    }

    private fun cancelResponseTimeout() {
        responseTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        responseTimeout = null
    }

    @SuppressLint("MissingPermission")
    private fun processNextWrite() {
        try {
            val op: PendingWrite
            synchronized(writeLock) {
                if (writeQueue.isEmpty()) { writeInFlight = false; return }
                op = writeQueue.removeFirst()
                writeInFlight = true
                currentWrite = op
            }
            val g = synchronized(writeLock) { gatt }
            if (g == null) {
                op.onResult?.invoke(false)
                synchronized(writeLock) { currentWrite = null; writeInFlight = false }
                processNextWrite()
                return
            }
            @Suppress("DEPRECATION")
            op.characteristic.value = op.data
            @Suppress("DEPRECATION")
            val started = g.writeCharacteristic(op.characteristic)
            if (!started) {
                val retryOp = if (op.retries < MAX_WRITE_RETRIES)
                    PendingWrite(op.characteristic, op.data, op.label, op.onResult, op.retries + 1, op.alternatives, op.expectResponse)
                else if (op.alternatives.isNotEmpty())
                    PendingWrite(op.characteristic, op.alternatives.first(), op.label, op.onResult, 0, op.alternatives.drop(1), op.expectResponse)
                else null
                synchronized(writeLock) {
                    if (retryOp != null) writeQueue.addFirst(retryOp)
                    currentWrite = null
                    writeInFlight = false
                }
                if (retryOp == null) op.onResult?.invoke(false)
                processNextWrite()
            }
        } catch (e: Exception) {
            failPendingWrites("processNextWrite exception")
        }
    }

    private fun handleNotification(value: ByteArray) {
        try {
            var t = when (protocol) {
                BleProtocol.NAVEE_BRIGHTWAY -> TelemetryDecoder.decodeNavee(value)
                BleProtocol.NINEBOT -> TelemetryDecoder.decodeNinebot(value)
            }
            if (t == null) {
                t = when (protocol) {
                    BleProtocol.NAVEE_BRIGHTWAY -> TelemetryDecoder.decodeNinebot(value)
                    BleProtocol.NINEBOT -> TelemetryDecoder.decodeNavee(value)
                }
            }
            if (t != null) listener.onTelemetry(t)
        } catch (_: Exception) {}
    }

    fun startFlash(firmware: ByteArray, unlockFrame: ByteArray?, rebootFrame: ByteArray?) {
        if (writeChar == null) {
            listener.onFlashFinished(false, "Kein Write-Characteristic.")
            return
        }
        if (firmware.isEmpty()) {
            listener.onFlashFinished(false, "Firmware-Daten leer.")
            return
        }
        val chunkSize = maxWritePayload()
        val chunks = ArrayList<ByteArray>()
        var off = 0
        while (off < firmware.size) {
            val end = (off + chunkSize).coerceAtMost(firmware.size)
            chunks.add(firmware.copyOfRange(off, end))
            off = end
        }
        flashChunks = chunks
        flashTotalBytes = firmware.size
        flashWrittenBytes = 0
        flashIndex = 0
        flashActive = true

        val begin = {
            if (unlockFrame != null) {
                enqueueWrite(unlockFrame, "flash-unlock") { ok ->
                    if (!ok) finishFlash(false, "Unlock fehlgeschlagen.")
                    else postToMain { writeNextChunk(rebootFrame) }
                }
            } else {
                writeNextChunk(rebootFrame)
            }
        }
        begin()
    }

    private fun writeNextChunk(rebootFrame: ByteArray?) {
        if (!flashActive) return
        if (flashIndex >= flashChunks.size) {
            if (rebootFrame != null) {
                enqueueWrite(rebootFrame, "flash-reboot") { ok ->
                    finishFlash(ok, if (ok) "Flash abgeschlossen." else "Reboot fehlgeschlagen.")
                }
            } else {
                finishFlash(true, "Flash abgeschlossen.")
            }
            return
        }
        val chunk = flashChunks[flashIndex]
        val idx = flashIndex
        armFlashChunkTimeout(idx)
        enqueueWrite(chunk, "flash-chunk-$idx") { ok ->
            cancelFlashChunkTimeout()
            if (!ok) {
                finishFlash(false, "Chunk $idx fehlgeschlagen.")
                return@enqueueWrite
            }
            flashWrittenBytes += chunk.size
            flashIndex++
            listener.onFlashProgress(flashWrittenBytes, flashTotalBytes)
            postToMain { writeNextChunk(rebootFrame) }
        }
    }

    private fun finishFlash(success: Boolean, message: String) {
        cancelFlashChunkTimeout()
        flashActive = false
        flashChunks = emptyList()
        listener.onFlashFinished(success, message)
    }

    private fun armFlashChunkTimeout(idx: Int) {
        cancelFlashChunkTimeout()
        val r = Runnable {
            if (flashActive && flashIndex == idx) finishFlash(false, "Chunk $idx Timeout.")
        }
        flashChunkTimeout = r
        Handler(Looper.getMainLooper()).postDelayed(r, FLASH_CHUNK_TIMEOUT_MS)
    }

    private fun cancelFlashChunkTimeout() {
        flashChunkTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        flashChunkTimeout = null
    }

    fun abortFlash() {
        if (!flashActive) return
        cancelFlashChunkTimeout()
        flashActive = false
        synchronized(writeLock) { writeQueue.clear() }
        listener.onFlashFinished(false, "Flash abgebrochen.")
    }

    private fun isControllerAckFor(op: PendingWrite, rx: ByteArray): Boolean {
        val expectedCmd = commandIdOf(op.data) ?: return false
        val rxCmd = commandIdOf(rx) ?: return false
        return expectedCmd == rxCmd && isStructurallyValidFrame(rx)
    }

    private fun commandIdOf(frame: ByteArray): Int? {
        if (frame.size < 4) return null
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        return when {
            b0 == 0x5A && b1 == 0xA5 -> frame[4].toInt() and 0xFF
            b0 == 0xAA && b1 == 0x55 -> frame[2].toInt() and 0xFF
            b0 == 0x55 && b1 == 0xAA -> frame[2].toInt() and 0xFF
            else -> null
        }
    }

    private fun isStructurallyValidFrame(frame: ByteArray): Boolean {
        if (frame.size < 5) return false
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        val sum = frame[frame.size - 1].toInt() and 0xFF
        return when {
            b0 == 0x5A && b1 == 0xA5 -> {
                if (frame.size < 6) return false
                val len = frame[2].toInt() and 0xFF
                if (len + 4 != frame.size) return false
                var s = 0
                for (i in 2 until frame.size - 1) s += frame[i].toInt() and 0xFF
                ((0x100 - (s and 0xFF)) and 0xFF) == sum
            }
            b0 == 0xAA && b1 == 0x55 -> {
                val len = frame[3].toInt() and 0xFF
                if (len + 5 != frame.size) return false
                var s = 0
                for (i in 0 until frame.size - 1) s += frame[i].toInt() and 0xFF
                (s and 0xFF) == sum
            }
            b0 == 0x55 && b1 == 0xAA -> {
                val len = frame[3].toInt() and 0xFF
                if (len + 5 != frame.size) return false
                var x = 0
                for (i in 0 until frame.size - 1) x = x xor (frame[i].toInt() and 0xFF)
                (x and 0xFF) == sum
            }
            else -> false
        }
    }

    private fun postToMain(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)
}

class MainActivity : AppCompatActivity(), BleEngine.Listener {
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

    private var connectedDevice: ScooterDevice? = null
    private var bleEngine: BleEngine? = null
    private var currentRegion: Region = Region.WORLD
    private var detectedModel: ScooterModel = ScooterModel.UNKNOWN
    private var activeProtocol: BleProtocol = BleProtocol.NINEBOT
    private var autoReconnectEnabled = true
    private var reconnectAttempts = 0
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
    private val MAX_FIRMWARE_BYTES = 512 * 1024

    private var flashMode: FlashMode = FlashMode.SOFT_PATCH
    private var overspeedAlarmMuted = false

    private val logEntries = ArrayDeque<LogEntry>()
    private var logFilter: LogCategory? = null
    private var logContainer: LinearLayout? = null
    private var logScroll: ScrollView? = null

    private var logRefreshScheduled = false
    private var lastLogRefresh = 0L
    private var telemetryApplyScheduled = false
    private var lastTelemetryApply = 0L
    private val UI_REFRESH_INTERVAL_MS = 150L
    private val SLIDER_DEBOUNCE_MS = 300L

    private var sysRssiView: TextView? = null
    private var sysMtuView: TextView? = null
    private var sysQueueView: TextView? = null
    private var sysLinkView: TextView? = null
    private var linkState: String = "IDLE"
    private var lastRssi: Int = Int.MIN_VALUE
    private var sysBarRunnable: Runnable? = null

    private var rideMode: RideMode = RideMode.NORMAL
    private val ecoThresholdPercent = 20
    private val sportPlusPhaseCurrentA = 27

    private val tripData = TripData()
    private var dashSoh: TextView? = null
    private var dashCycles: TextView? = null
    private var dashVmax: TextView? = null
    private var dashVavg: TextView? = null
    private var dashWh: TextView? = null
    private var cellGraphRow: LinearLayout? = null

    private var selectedSource: FirmwareSource = FirmwareSource.FACTORY

    private var dashSpeed: TextView? = null
    private var dashPower: TextView? = null
    private var dashVoltage: TextView? = null
    private var dashCurrent: TextView? = null
    private var dashBattery: TextView? = null
    private var dashMotorTemp: TextView? = null
    private var dashTrip: TextView? = null
    private var dashRange: TextView? = null
    private var dashCellMin: TextView? = null
    private var dashCellMax: TextView? = null
    private var dashCellDelta: TextView? = null
    private var dashTempGuardState: TextView? = null
    private var dashFeedState: TextView? = null
    @Volatile private var lastTelemetry: Telemetry? = null
    private var motorGuardTripped = false
    private var rampActive = false
    private var rampTicks = 0
    private var rampRunnable: Runnable? = null
    private var rampReason: String? = null
    private val RAMP_TICK_MS = 400L
    private val RAMP_MAX_TICKS = 25
    private val RAMP_CUT_SPEED_KMH = 3f

    private var telemetryRunnable: Runnable? = null
    private var simTick = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            statusText.text = "Berechtigungen erteilt."
            startBleScan()
        } else {
            statusText.text = "Bluetooth-Berechtigungen erforderlich."
            scanButton.isEnabled = true
        }
    }

    private val firmwarePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) handleSelectedFirmware(uri) }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device
            val address = device.address
            val name = try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                    device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
                else result.scanRecord?.deviceName ?: "Unknown Device"
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: "Unknown Device"
            }

            val keywords = listOf("scooter", "ninebot", "xiaomi", "navee", "m365", "segway", "soflow")
            val candidate = keywords.any { name.contains(it, ignoreCase = true) }

            runOnUiThread {
                foundDevices[address] = ScooterDevice(device, name, address, result.rssi, candidate, false)
                updateDeviceList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = "BLE-Scan fehlgeschlagen."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("SyrakPrefs", Context.MODE_PRIVATE)
        currentRegion = Region.fromCountry(Locale.getDefault().country)
        autoReconnectEnabled = prefs.getBoolean("auto_reconnect", true)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(rootContainer)
        showLoadingScreen()
    }

    override fun onDestroy() {
        loadingRunnable?.let { handler.removeCallbacks(it) }
        cancelReconnectRunnable()
        endSafeStopRamp()
        activeTimer?.cancel()
        stopTelemetry()
        stopSystemBar()
        bleEngine?.disconnect()
        if (isScanning) {
            try { if (hasBluetoothConnectPermission()) bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback) } catch (_: Exception) {}
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Palette.SURFACE_ALT)
    }
    private fun makeSectionLabel(value: String): TextView = makeText("  $value", 11f, Palette.ACCENT_RED, true).apply { setPadding(0, dp(14), 0, dp(6)) }
    private fun makePrimaryButton(label: String, color: Int = Palette.ACCENT_RED, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = true; setBackgroundColor(color)
        setTextColor(Color.WHITE); setOnClickListener { onClick() }
    }
    private fun makeToggleRow(label: String, desc: String, initial: Boolean, onToggle: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10))
        val header = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(makeText(label, 14f, Palette.TEXT_PRIMARY, true))
        val state = makeText(if (initial) "AN" else "AUS", 12f, if (initial) Palette.ACCENT_GREEN else Palette.TEXT_MUTED, true).apply { gravity = Gravity.END }
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

    private fun makeSliderCard(title: String, desc: String, min: Int, max: Int, initial: Int, unit: String, valueFormatter: (Int) -> String = { "$it $unit" }, onChange: (Int) -> Unit): LinearLayout {
        val card = makeCard()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
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
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun cardParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }

    private fun showLoadingScreen() {
        rootContainer.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        layout.addView(makeText("SYRAK SCOOTERLAB", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        layout.addView(makeText("REAL BLE TUNING & FLASH ENGINE", 11f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val status = makeText("Initializing application...", 13f, Color.LTGRAY).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) }
        layout.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { setMargins(dp(24), dp(32), dp(24), 0) })
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
                    if (prefs.getBoolean("disclaimers_accepted", false)) showScanScreen()
                    else showCriticalDisclaimer()
                }
            }
        }
        loadingRunnable?.let { handler.postDelayed(it, 400) }
    }

    private fun showCriticalDisclaimer() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        content.addView(makeText("Critical Safety & Liability Notice", 22f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(20)) })
        val sections = listOf(
            "Age Restriction & Competence" to "This application and its companion firmware utilities are intended solely for adult users of legal age.",
            "Personal Safety & Protective Gear" to "Always wear certified protective equipment and thoroughly inspect brakes and components prior to every ride.",
            "Hardware Risks & Component Damage" to "Modifying firmware registers or flashing custom MCU packages carries inherent technical risks.",
            "Legal Compliance & Road Regulations" to "Altering factory-set speed ceilings may violate local traffic laws and regional type-approval standards.",
            "Insurance & Warranty Voidance" to "Modifying firmware instantly voids the manufacturer's warranty and third-party liability insurance coverage.",
            "Independent Third-Party Project" to "Syrak ScooterLab is an independent developer toolkit with no official connection to manufacturers.",
            "As-Is Disclaimer & User Responsibility" to "Software is provided strictly on an 'as-is' basis without warranties of any kind."
        )
        sections.forEach { (heading, body) ->
            content.addView(makeText(heading, 14f, Color.rgb(230, 70, 70), true).apply { setPadding(0, 0, 0, dp(4)) })
            content.addView(makeText(body, 13f, Color.rgb(185, 190, 200)).apply { setPadding(0, 0, 0, dp(16)); setLineSpacing(dp(2).toFloat(), 1f) })
        }
        scroll.addView(content)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }
        val agree = Button(this).apply { text = "I have read and understood (20)"; isEnabled = false }
        val exit = Button(this).apply { text = "Exit App"; setOnClickListener { finishAffinity() } }
        footer.addView(agree); footer.addView(exit)
        rootContainer.addView(footer)
        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(20000, 1000) {
            override fun onTick(millisUntilFinished: Long) { agree.text = "I have read and understood (${millisUntilFinished / 1000})" }
            override fun onFinish() {
                agree.text = "Continue"; agree.isEnabled = true
                agree.setOnClickListener { showCountryDisclaimer() }
            }
        }.start()
    }

    private fun showCountryDisclaimer() {
        activeTimer?.cancel()
        rootContainer.removeAllViews()
        val countryCode = Locale.getDefault().country.uppercase(Locale.ROOT)
        val displayCountry = Locale.getDefault().getDisplayCountry(Locale.getDefault()).ifBlank { "your country" }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        content.addView(makeText("Regional Legal Notice ($displayCountry)", 22f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(16)) })
        val localizedBody = when (countryCode) {
            "DE" -> "Rechtliche Hinweise für Deutschland (eKFV):\n\n• Elektrokleinstfahrzeuge unterliegen der eKFV.\n• Überschreitung von 20 km/h ist unzulässig.\n• Manipulationen führen zum Erlöschen der Betriebserlaubnis (ABE)."
            else -> "Regional Regulations for $displayCountry:\n\n• Ensure full compliance with local traffic laws and speed caps."
        }
        content.addView(makeText(localizedBody, 14f, Color.rgb(185, 190, 200)).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        scroll.addView(content)
        rootContainer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }
        val accept = Button(this).apply { text = "Understood (20)"; isEnabled = false }
        val exit = Button(this).apply { text = "Exit App"; setOnClickListener { finishAffinity() } }
        footer.addView(accept); footer.addView(exit)
        rootContainer.addView(footer)
        activeTimer?.cancel()
        activeTimer = object : CountDownTimer(20000, 1000) {
            override fun onTick(millisUntilFinished: Long) { accept.text = "Understood (${millisUntilFinished / 1000})" }
            override fun onFinish() {
                accept.text = "Accept & Proceed"; accept.isEnabled = true
                accept.setOnClickListener {
                    prefs.edit().putBoolean("disclaimers_accepted", true).apply()
                    showScanScreen()
                }
            }
        }.start()
    }

    private fun showScanScreen() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Palette.BG)
        }
        val topHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(4)) }
        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleContainer.addView(makeText("Syrak ScooterLab", 24f, Color.WHITE, true))
        titleContainer.addView(makeText("Scooter auswählen und verbinden", 14f, Palette.TEXT_SECONDARY).apply { setPadding(0, dp(4), 0, 0) })
        topHeader.addView(titleContainer)
        val buttonsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val supportedModelsBtn = Button(this).apply {
            text = "Modelle"; textSize = 11f; isAllCaps = false
            setBackgroundColor(Color.rgb(28, 36, 48)); setTextColor(Palette.ACCENT_GREEN)
            setOnClickListener { showSupportedModelsDialog() }
        }
        val demoBtn = Button(this).apply {
            text = "Demo"; textSize = 11f; isAllCaps = false
            setBackgroundColor(Color.rgb(40, 30, 20)); setTextColor(Palette.ACCENT_ORANGE)
            setOnClickListener { loadDemoScooters() }
        }
        buttonsLayout.addView(supportedModelsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { setMargins(0, 0, dp(6), 0) })
        buttonsLayout.addView(demoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        topHeader.addView(buttonsLayout)
        content.addView(topHeader)
        content.addView(View(this).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)) })
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28)); setBackgroundColor(Palette.SURFACE_ALT)
        }
        panel.addView(makeText("Bluetooth-Verbindung", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        statusText = makeText("Scooter einschalten und Suche starten.", 13f, Color.rgb(185, 190, 200)).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) }
        panel.addView(statusText)
        content.addView(panel)
        scanButton = Button(this).apply {
            text = "NACH SCOOTERN SUCHEN"; isAllCaps = false
            setOnClickListener { startBleScan() }
        }
        content.addView(scanButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(16), 0, dp(16)) })
        deviceListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceListContainer)
        content.addView(makeText("Es werden Bluetooth-Geräte angezeigt. Tippe auf einen Scooter, um zu verbinden.", 12f, Palette.TEXT_MUTED).apply { setPadding(0, dp(24), 0, 0) })
        scroll.addView(content)
        rootContainer.addView(scroll)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
        if (bluetoothAdapter == null) {
            statusText.text = "Bluetooth ist nicht verfügbar."
            scanButton.isEnabled = false
        }
    }

    private fun loadDemoScooters() {
        foundDevices.clear()
        val demoList = listOf(
            ScooterDevice(null, "Demo Ninebot Max G2", "D2:E4:5A:11:22:33", -45, true, true),
            ScooterDevice(null, "Demo Xiaomi Pro 2", "11:22:33:44:55:66", -68, true, true),
            ScooterDevice(null, "Demo Navee GT3 Max", "99:88:77:66:55:44", -75, true, true)
        )
        demoList.forEach { foundDevices[it.address] = it }
        updateDeviceList()
        statusText.text = "Demo-Modus aktiv."
    }

    private fun showSupportedModelsDialog() {
        val modelsList = "• Segway-Ninebot: Max G30/G2, ES-Serie\n• Xiaomi: M365 / Pro 2\n• Navee: GT3 Max / S65\n• SoFlow: SO4"
        AlertDialog.Builder(this).setTitle("Unterstützte Modelle").setMessage(modelsList).setPositiveButton("Verstanden") { d, _ -> d.dismiss() }.show()
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { permissionLauncher.launch(missing.toTypedArray()); return }

        if (isScanning) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Bitte Bluetooth aktivieren."
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { statusText.text = "BLE-Scanner nicht verfügbar."; return }

        foundDevices.clear(); updateDeviceList()
        isScanning = true; scanButton.isEnabled = false
        statusText.text = "Suche..."
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, leScanCallback)
        } catch (_: Exception) {
            isScanning = false; scanButton.isEnabled = true
            statusText.text = "Scan fehlgeschlagen."
            return
        }
        handler.postDelayed({
            if (isScanning) {
                try { scanner.stopScan(leScanCallback) } catch (_: Exception) {}
                isScanning = false; scanButton.isEnabled = true
                statusText.text = "Scan beendet. Gefunden: ${foundDevices.size}"
            }
        }, 10000)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDeviceList() {
        try {
            if (!::deviceListContainer.isInitialized) return
            deviceListContainer.removeAllViews()
            foundDevices.values.sortedByDescending { it.rssi }.forEach { item ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    setBackgroundColor(if (item.isScooterCandidate) Color.rgb(25, 40, 32) else Palette.SURFACE_ALT)
                    setOnClickListener {
                        if (isScanning) {
                            try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback) } catch (_: Exception) {}
                            isScanning = false
                        }
                        connectToDevice(item)
                    }
                }
                card.addView(makeText(item.name, 15f, Color.WHITE, true))
                card.addView(makeText(if (item.isDemo) "Demo-Gerät" else "RSSI: ${item.rssi} dBm • ${item.address}", 12f, Palette.TEXT_SECONDARY).apply { setPadding(0, dp(5), 0, 0) })
                deviceListContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) })
            }
        } catch (_: Exception) {}
    }

    private fun connectToDevice(item: ScooterDevice) {
        connectedDevice = item
        detectedModel = ScooterModel.detect(item.name, item.address)
        activeProtocol = detectedModel.protocol
        reconnectAttempts = 0
        userInitiatedDisconnect = false
        engineReady = false

        if (item.isDemo || item.device == null) {
            linkState = "DEMO"
            showMainDashboard()
            log("> link: DEMO verbunden ${item.address}")
            engineReady = true
            return
        }
        linkState = "CONNECTING"
        showMainDashboard()
        val engine = BleEngine(this, this)
        bleEngine = engine
        engine.setProtocolHint(detectedModel.protocol)
        engine.connect(item.device)
    }

    private fun handleUnexpectedDisconnect() {
        engineReady = false
        linkState = "DISCONNECTED"
        updateSystemBar()
        if (userInitiatedDisconnect) return
        if (!autoReconnectEnabled) { runOnUiThread { showScanScreen() }; return }
        cancelReconnectRunnable()
        if (reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
            reconnectAttempts++
            linkState = "RECONNECT"
            val delayMs = (RECONNECT_BACKOFF_BASE_MS shl (reconnectAttempts - 1)).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
            log("> link: Reconnect-Versuch $reconnectAttempts/$RECONNECT_MAX_ATTEMPTS in ${delayMs} ms")
            updateSystemBar()
            val r = Runnable {
                reconnectRunnable = null
                if (!userInitiatedDisconnect && connectedDevice?.device != null) {
                    try { bleEngine?.reconnect() } catch (_: Exception) {}
                }
            }
            reconnectRunnable = r
            handler.postDelayed(r, delayMs)
        } else {
            linkState = "DISCONNECTED"
            updateSystemBar()
            runOnUiThread { showScanScreen() }
        }
    }

    private fun cancelReconnectRunnable() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    override fun onLog(line: String) = log(line)
    override fun onRssi(rssi: Int) { lastRssi = rssi; runOnUiThread { updateSystemBar() } }
    override fun onConnected(address: String) { runOnUiThread { Toast.makeText(this, "Verbunden", Toast.LENGTH_SHORT).show() } }
    override fun onReady(address: String, protocol: BleProtocol, mtu: Int) {
        activeProtocol = protocol
        engineReady = true
        runOnUiThread {
            linkState = "READY"
            log("> gatt: READY — ${protocol.displayName}, MTU $mtu")
            updateSystemBar()
            if (currentTab == Tab.INFO) renderTab(Tab.INFO)
        }
    }
    override fun onDisconnected(unexpected: Boolean) { runOnUiThread { handleUnexpectedDisconnect() } }
    override fun onTelemetry(t: Telemetry) { lastTelemetry = t; scheduleTelemetryApply() }
    override fun onWriteResult(label: String, frame: ByteArray, success: Boolean, detail: String) {
        if (success) log("> tx[$label]: ✔") else log("> tx[$label]: FEHLER $detail")
    }
    override fun onFlashProgress(written: Int, total: Int) {
        runOnUiThread {
            val pct = if (total > 0) (written * 100 / total) else 0
            flashProgressBar?.progress = pct
            flashStatusView?.text = "Schreibe… $written / $total Bytes ($pct %)"
        }
    }
    override fun onFlashFinished(success: Boolean, message: String) {
        runOnUiThread {
            flashStatusView?.text = (if (success) "✔ " else "✘ ") + message
            flashStatusView?.setTextColor(if (success) Palette.ACCENT_GREEN else Palette.ACCENT_RED)
            if (success) flashProgressBar?.progress = 100
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showMainDashboard() {
        rootContainer.removeAllViews()
        currentTab = Tab.INFO
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG) }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(10)) }
        topBar.addView(makeText("SYRAK", 20f, Color.WHITE, true))
        topBar.addView(makeText(" SCOOTERLAB", 11f, Palette.ACCENT_RED, true))
        topBar.addView(makeText("  ${currentRegion.displayName}  ", 10f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shell.addView(topBar)

        val banner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(Color.rgb(20, 30, 28)) }
        val dev = connectedDevice
        banner.addView(makeText(if (dev?.isDemo == true) "■ Controller online (Demo)" else "■ Controller online", 12f, Palette.ACCENT_GREEN, true))
        shell.addView(banner)
        shell.addView(buildSystemBar())

        val scroll = ScrollView(this).apply { isFillViewport = true }
        tabContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(tabContent)
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomNav = buildBottomNav()
        shell.addView(bottomNav)
        rootContainer.addView(shell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        selectTab(Tab.INFO)
        startSystemBar()
    }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(10)); setBackgroundColor(Palette.SURFACE) }
        fun navItem(tab: Tab, icon: String, label: String): LinearLayout {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(tab) }
            }
            item.addView(makeText(icon, 18f, Palette.TEXT_SECONDARY).apply { gravity = Gravity.CENTER })
            item.addView(makeText(label, 10f, Palette.TEXT_SECONDARY).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
            item.tag = tab
            return item
        }
        nav.addView(navItem(Tab.INFO, "ⓘ", "Info"))
        nav.addView(navItem(Tab.TOOLS, "⚙", "Tools"))
        val centerWrap = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val centerCircle = TextView(this).apply {
            text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.ACCENT_BLUE); setStroke(dp(4), Palette.BG) }
            layoutParams = FrameLayout.LayoutParams(dp(60), dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dp(26) }
            setOnClickListener { selectTab(Tab.DASHBOARD) }
        }
        centerWrap.addView(centerCircle)
        nav.addView(centerWrap)
        nav.addView(navItem(Tab.FLASH, "⇪", "Flash"))
        nav.addView(navItem(Tab.CONFIG, "⚡", "Config"))
        return nav
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        for (i in 0 until bottomNav.childCount) {
            val child = bottomNav.getChildAt(i)
            if (child is LinearLayout && child.tag is Tab) {
                val active = child.tag == tab
                val color = if (active) Palette.ACCENT_GREEN else Palette.TEXT_SECONDARY
                (child.getChildAt(0) as? TextView)?.setTextColor(color)
                (child.getChildAt(1) as? TextView)?.setTextColor(color)
            }
        }
        renderTab(tab)
    }

    private fun renderTab(tab: Tab) {
        stopTelemetry()
        tabContent.removeAllViews()
        when (tab) {
            Tab.INFO -> renderInfoTab()
            Tab.TOOLS -> renderToolsTab()
            Tab.DASHBOARD -> renderDashboardTab()
            Tab.FLASH -> renderFlashTab()
            Tab.CONFIG -> renderConfigTab()
        }
    }

    private fun renderInfoTab() {
        val dev = connectedDevice
        val isDemo = dev?.isDemo == true
        val preview = makeCard()
        preview.addView(makeText("🛴  ${dev?.name ?: "Unbekannt"}", 20f, Color.WHITE, true))
        preview.addView(makeText(if (isDemo) "Demo Simulation" else "BLE Connected", 12f, Palette.TEXT_MUTED).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(preview, cardParams())

        tabContent.addView(makeSectionLabel("ERKANNTes MODELL"))
        val modelCard = makeCard()
        modelCard.addView(makeText(detectedModel.displayName, 16f, Palette.ACCENT_GREEN, true))
        modelCard.addView(makeText("Easy Speed: ${detectedModel.easySpeedKmh} km/h • Max: ${detectedModel.absMaxKmh} km/h", 12f, Palette.TEXT_SECONDARY).apply { setPadding(0, dp(6), 0, dp(6)) })
        tabContent.addView(modelCard, cardParams())

        tabContent.addView(makeSectionLabel("SYSTEM"))
        val info = makeCard()
        fun infoRow(l: String, r: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8))
            addView(makeText(l, 13f, Palette.TEXT_SECONDARY))
            addView(makeText(r, 13f, Color.WHITE, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        info.addView(infoRow("Protokoll", activeProtocol.displayName))
        info.addView(infoRow("GATT-Status", if (isDemo) "DEMO" else if (engineReady) "READY" else "verbinde…"))
        info.addView(infoRow("Region", currentRegion.displayName))
        tabContent.addView(info, cardParams())

        tabContent.addView(makeSectionLabel("LIVE-LOG"))
        tabContent.addView(buildLogTerminal(), cardParams())
        refreshLogView()
    }

    private fun renderToolsTab() {
        tabContent.addView(makeSectionLabel("ANFAHREN"))
        tabContent.addView(makeSliderCard("Zero-Start", "Mindestgeschwindigkeit bis Motor greift.", 0, 5, zeroStartKmh, "km/h") { value ->
            zeroStartKmh = value
            sendFrame(ScooterFrames.zeroStart(value), "zero-start")
        }, cardParams())

        tabContent.addView(makeSectionLabel("GESCHWINDIGKEIT"))
        tabContent.addView(buildMaxSpeedCard(), cardParams())

        val gm = makeCard()
        gm.addView(makeText("German Maneuver", 15f, Color.WHITE, true))
        gm.addView(makePrimaryButton("GERMAN MANEUVER ANWENDEN") {
            snapshotRegisters()
            germanManeuverActive = true
            val frame = ScooterFrames.germanManeuver()
            ramOverlay.apply("german-maneuver", frame)
            sendFrame(frame, "german-maneuver")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(gm, cardParams())

        tabContent.addView(makeSectionLabel("FAHRDYNAMIK"))
        tabContent.addView(makeSliderCard("KERS (Rekuperation)", "Stärke der Energierückgewinnung.", 0, 3, kersLevel, "Stufe") { value ->
            kersLevel = value
            sendFrame(ScooterFrames.kers(value), "kers")
        }, cardParams())

        val cruise = makeCard()
        cruise.addView(makeToggleRow("Tempomat", "Hält die Geschwindigkeit konstant.", cruiseEnabled) { on ->
            cruiseEnabled = on
            sendFrame(ScooterFrames.cruise(on), "cruise")
        })
        tabContent.addView(cruise, cardParams())

        tabContent.addView(makeSectionLabel("SICHERHEIT"))
        val police = makeCard()
        police.addView(makeText("🚨 Police Mode (Panik-Button)", 15f, Palette.ACCENT_ORANGE, true))
        police.addView(makePrimaryButton("POLICE MODE AUSLÖSEN", Palette.ACCENT_ORANGE) { engagePoliceMode() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(police, cardParams())
    }

    private fun buildMaxSpeedCard(): LinearLayout {
        val easy = detectedModel.easySpeedKmh
        val absMax = detectedModel.absMaxKmh
        val card = makeCard()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(makeText("Max Speed", 14f, Palette.TEXT_PRIMARY, true))
        val valueView = makeText("$maxSpeedKmh km/h", 14f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.END }
        header.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)
        card.addView(makeText("Easy Speed: $easy km/h • Max: $absMax km/h", 12f, Palette.TEXT_MUTED).apply { setPadding(0, dp(3), 0, dp(10)) })
        val bar = SeekBar(this).apply { max = 65 - 15; progress = maxSpeedKmh - 15 }
        var lastCommit = 0L
        var pendingReal = maxSpeedKmh
        val commitRunnable = Runnable {
            lastCommit = System.currentTimeMillis()
            if (pendingReal <= easy) {
                maxSpeedKmh = pendingReal
                sendFrame(ScooterFrames.maxSpeed(pendingReal), "max-speed")
            }
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val real = progress + 15
                pendingReal = real
                valueView.text = "$real km/h"
                if (!fromUser || real > easy) return
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
                val real = (seekBar?.progress ?: 0) + 15
                if (real > easy) {
                    confirmExtendedPower(real, easy, absMax, bar, valueView)
                } else {
                    pendingReal = real
                    commitRunnable.run()
                }
            }
        })
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun confirmExtendedPower(requested: Int, easy: Int, absMax: Int, bar: SeekBar, valueView: TextView) {
        val capped = requested.coerceAtMost(absMax)
        AlertDialog.Builder(this)
            .setTitle("⚠ Easy-Speed-Grenze überschritten")
            .setMessage("Achtung: Überschreitung von $easy km/h geschieht auf eigene Gefahr.")
            .setPositiveButton("Freischalten") { _, _ ->
                extendedPowerUnlocked = true
                maxSpeedKmh = capped
                bar.progress = capped - 15
                valueView.text = "$capped km/h"
                val maxFrame = ScooterFrames.maxSpeed(capped)
                val powerFrame = ScooterFrames.maxPower()
                ramOverlay.apply("extended-power", powerFrame)
                ramOverlay.apply("max-speed-$capped", maxFrame)
                sendFrame(maxFrame, "max-speed")
                sendFrame(powerFrame, "max-power")
                renderTab(Tab.TOOLS)
            }
            .setNegativeButton("Abbrechen") { _, _ ->
                maxSpeedKmh = easy
                bar.progress = easy - 15
                valueView.text = "$easy km/h"
                sendFrame(ScooterFrames.maxSpeed(easy), "max-speed")
            }
            .show()
    }

    private fun renderDashboardTab() {
        val isDemo = connectedDevice?.isDemo == true
        val speedCard = makeCard().apply { gravity = Gravity.CENTER }
        dashSpeed = makeText("--", 56f, Color.WHITE, true)
        speedCard.addView(dashSpeed)
        speedCard.addView(makeText("KM/H", 12f, Palette.ACCENT_RED, true))
        dashFeedState = makeText(if (isDemo) "LIVE FEED (DEMO)" else "LIVE FEED", 10f, Palette.ACCENT_GREEN, true).apply { setPadding(0, dp(4), 0, dp(14)) }
        speedCard.addView(dashFeedState)
        val metricsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        fun metricCol(label: String): TextView {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(makeText(label, 10f, Palette.TEXT_MUTED))
            val v = makeText("--", 15f, Color.WHITE, true).apply { setPadding(0, dp(2), 0, 0) }
            col.addView(v)
            metricsRow.addView(col)
            return v
        }
        dashPower = metricCol("POWER")
        dashTrip = metricCol("TRIP")
        dashRange = metricCol("RANGE")
        speedCard.addView(metricsRow)
        tabContent.addView(speedCard, cardParams())

        tabContent.addView(makeSectionLabel("ELEKTRIK"))
        fun gridRow(l1: String, l2: String): Pair<TextView, TextView> {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
            fun gridCard(label: String): TextView {
                val c = makeCard().apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) } }
                c.addView(makeText(label, 11f, Palette.TEXT_MUTED))
                val v = makeText("--", 18f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) }
                c.addView(v)
                row.addView(c)
                return v
            }
            val a = gridCard(l1); val b = gridCard(l2)
            tabContent.addView(row)
            return a to b
        }
        val (vVolt, vCur) = gridRow("⚡ VOLTAGE", "📈 CURRENT")
        dashVoltage = vVolt; dashCurrent = vCur
        val (vBat, vTemp) = gridRow("🔋 BATTERY", "🌡 MOTOR TEMP")
        dashBattery = vBat; dashMotorTemp = vTemp

        lastTelemetry?.let { applyTelemetryToDashboard(it) }
        if (isDemo) startDemoTelemetry()
    }

    private fun applyTelemetryToDashboard(t: Telemetry) {
        try {
            val now = System.currentTimeMillis()
            if (tripData.startMillis == 0L) { tripData.startMillis = now; tripData.lastMillis = now }
            val dtSec = if (tripData.lastMillis == 0L) 0.0 else (((now - tripData.lastMillis).coerceIn(0L, 5000L)) / 1000.0)
            tripData.lastMillis = now
            t.speedKmh?.let { sp ->
                val v = sp.toDouble()
                tripData.distanceKm += v * dtSec / 3600.0
                if (v > tripData.maxSpeedKmh) tripData.maxSpeedKmh = v
                tripData.speedSum += v; tripData.speedSamples++
            }

            dashSpeed?.text = t.speedKmh?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
            dashVoltage?.text = t.voltageV?.let { String.format(Locale.US, "%.1f V", it) } ?: "-- V"
            dashCurrent?.text = t.currentA?.let { String.format(Locale.US, "%.1f A", it) } ?: "-- A"
            dashBattery?.text = t.batteryPercent?.let { "$it %" } ?: "-- %"
            dashMotorTemp?.text = t.motorTempC?.let { String.format(Locale.US, "%.0f °C", it) } ?: "-- °C"
            val power = if (t.voltageV != null && t.currentA != null) t.voltageV * t.currentA else null
            dashPower?.text = power?.let { String.format(Locale.US, "%.0f W", it) } ?: "-- W"
            dashTrip?.text = String.format(Locale.US, "%.2f KM", tripData.distanceKm)
            t.motorTempC?.let { evaluateMotorGuard(it.toDouble()) }
        } catch (_: Exception) {}
    }

    private fun startDemoTelemetry() {
        stopTelemetry()
        telemetryRunnable = object : Runnable {
            override fun run() {
                simTick++
                val speed = 26.0 + 4.0 * Math.sin(simTick / 6.0)
                val volt = 40.6 - (simTick % 20) * 0.01
                val current = 8.0 + 5.0 * Math.abs(Math.sin(simTick / 4.0))
                val motorTemp = 41.0 + 3.0 * Math.abs(Math.sin(simTick / 10.0))
                applyTelemetryToDashboard(
                    Telemetry(
                        speedKmh = speed.toFloat(), voltageV = volt.toFloat(), currentA = current.toFloat(),
                        batteryPercent = 87, motorTempC = motorTemp.toFloat(), rawHex = "(demo)", source = activeProtocol
                    )
                )
                handler.postDelayed(this, 500)
            }
        }
        telemetryRunnable?.let { handler.postDelayed(it, 400) }
    }

    private fun evaluateMotorGuard(tempC: Double) {
        if (!motorTempGuardEnabled) return
        if (tempC >= motorTempGuardC && !motorGuardTripped) {
            motorGuardTripped = true
            beginSafeStopRamp("motor-temp-guard", 8)
        }
    }

    private fun beginSafeStopRamp(reason: String, legalKmh: Int) {
        if (rampActive && rampReason == reason) return
        endSafeStopRamp()
        rampActive = true
        rampReason = reason
        rampTicks = 0
        val target = legalKmh.coerceAtLeast(0)
        val r = object : Runnable {
            override fun run() {
                if (!rampActive) return
                rampTicks++
                val speed = lastTelemetry?.speedKmh
                val verifiedStopped = speed != null && speed < RAMP_CUT_SPEED_KMH
                if (verifiedStopped) {
                    sendFrame(ScooterFrames.emergencyCut(), "$reason-cut")
                    endSafeStopRamp()
                    return
                }
                sendFrame(ScooterFrames.maxSpeed(target), "$reason-ramp")
                if (rampTicks >= RAMP_MAX_TICKS) {
                    endSafeStopRamp()
                    return
                }
                handler.postDelayed(this, RAMP_TICK_MS)
            }
        }
        rampRunnable = r
        handler.postDelayed(r, RAMP_TICK_MS)
    }

    private fun endSafeStopRamp() {
        rampRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null
        rampActive = false
        rampReason = null
        rampTicks = 0
    }

    private fun stopTelemetry() {
        telemetryRunnable?.let { handler.removeCallbacks(it) }
        telemetryRunnable = null
    }

    private fun renderFlashTab() {
        tabContent.addView(makeSectionLabel("FIRMWARE-QUELLE"))
        val srcCard = makeCard()
        srcCard.addView(makeText("Quelle wählen", 14f, Color.WHITE, true))
        tabContent.addView(srcCard, cardParams())

        tabContent.addView(makeSectionLabel("FLASH-MODUS"))
        val modeCard = makeCard()
        FlashMode.values().forEach { mode ->
            val isSel = flashMode == mode
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(if (isSel) Palette.SURFACE_DEEP else Palette.SURFACE_ALT)
            }
            row.addView(makeText(mode.displayName, 14f, if (isSel) Palette.ACCENT_GREEN else Color.WHITE, true))
            row.setOnClickListener { flashMode = mode; renderTab(Tab.FLASH) }
            modeCard.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        }
        tabContent.addView(modeCard, cardParams())

        tabContent.addView(makeSectionLabel("EIGENES PAKET (.bin / .zip)"))
        val pkg = makeCard()
        pkg.addView(makePrimaryButton("FIRMWARE WÄHLEN", Palette.SURFACE) { firmwarePicker.launch(arrayOf("*/*")) })
        tabContent.addView(pkg, cardParams())

        tabContent.addView(makeSectionLabel("STATUS"))
        val statusCard = makeCard()
        flashStatusView = makeText("Keine Firmware gewählt.", 11f, Palette.TEXT_MUTED)
        statusCard.addView(flashStatusView)
        flashProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        statusCard.addView(flashProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { setMargins(0, dp(12), 0, dp(12)) })
        statusCard.addView(makePrimaryButton("FLASHEN") { startFlashFlow() })
        tabContent.addView(statusCard, cardParams())
    }

    private fun startFlashFlow() {
        if (loadedFirmwareBytes == null) {
            Toast.makeText(this, "Keine Firmware geladen.", Toast.LENGTH_SHORT).show()
            return
        }
        doFlash()
    }

    private fun doFlash() {
        val bytes = loadedFirmwareBytes ?: return
        val proto = bleEngine?.protocol ?: activeProtocol
        val unlock: ByteArray? = if (proto == BleProtocol.NAVEE_BRIGHTWAY) NaveeFrames.handshake() else null
        val reboot: ByteArray? = if (proto == BleProtocol.NAVEE_BRIGHTWAY) NaveeFrames.buildFrame(NaveeFrames.CMD_EMERGENCY, byteArrayOf(0x01)) else ScooterFrames.buildFrame(0x20, 0x7E, byteArrayOf(0x01))
        flashProgressBar?.progress = 0
        flashStatusView?.text = "Starte Flash…"
        bleEngine?.startFlash(bytes, unlock, reboot)
    }

    private fun handleSelectedFirmware(uri: Uri) {
        val validation = validateFirmware(uri)
        lastValidation = validation
        loadedFirmwareBytes = validation.extractedBinary
        if (currentTab == Tab.FLASH) renderTab(Tab.FLASH)
    }

    private fun readCappedStream(input: java.io.InputStream, cap: Int): ByteArray? {
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) return null
                out.write(buf, 0, n)
            }
        } catch (_: Exception) { return null }
        return out.toByteArray()
    }

    private fun validateFirmware(uri: Uri): FirmwareValidation {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { readCappedStream(it, MAX_FIRMWARE_BYTES) }
        } catch (_: Exception) { return FirmwareValidation(false, "Lesefehler", "", false, false, null) }
        if (bytes == null || bytes.isEmpty()) return FirmwareValidation(false, "Ungültig oder zu groß", "", false, false, null)
        return FirmwareValidation(true, "OK", "", false, true, bytes)
    }

    private fun renderConfigTab() {
        tabContent.addView(makeSectionLabel("VERBINDUNG"))
        val connCard = makeCard()
        connCard.addView(makeToggleRow("Auto-Reconnect", "Verbindung automatisch wiederherstellen.", autoReconnectEnabled) { on ->
            autoReconnectEnabled = on
            prefs.edit().putBoolean("auto_reconnect", on).apply()
        })
        tabContent.addView(connCard, cardParams())

        val danger = makeCard()
        danger.addView(makePrimaryButton("DISCONNECT", Palette.SURFACE) { disconnectAndReturn() })
        tabContent.addView(danger, cardParams())
    }

    private fun disconnectAndReturn() {
        userInitiatedDisconnect = true
        cancelReconnectRunnable()
        endSafeStopRamp()
        bleEngine?.disconnect()
        bleEngine = null
        connectedDevice = null
        ramOverlay.flush()
        showScanScreen()
    }

    private val unlockGatedLabels = setOf("max-speed", "zero-start", "german-maneuver", "stealth-unlock", "police-mode", "police-speed", "unlock", "kers", "cruise", "max-power")

    private fun sendFrame(frame: ByteArray, label: String) {
        val engine = bleEngine
        if (engine == null || connectedDevice?.isDemo == true) return
        engine.enqueueCommand(frame, label, label in unlockGatedLabels)
    }

    private fun snapshotRegisters() {
        registerSnapshot = RegisterSnapshot(speedCeiling = maxSpeedKmh, zeroStart = zeroStartKmh, kers = kersLevel, cruise = cruiseEnabled, locked = isLocked)
    }

    private fun engagePoliceMode() {
        ramOverlay.flush()
        germanManeuverActive = false
        extendedPowerUnlocked = false
        maxSpeedKmh = currentRegion.legalSpeedKmh
        sendFrame(ScooterFrames.policeMode(currentRegion.legalSpeedKmh), "police-mode")
        sendFrame(ScooterFrames.maxSpeed(currentRegion.legalSpeedKmh), "police-speed")
        beginSafeStopRamp("police-mode", currentRegion.legalSpeedKmh)
        Toast.makeText(this, "Police Mode aktiv", Toast.LENGTH_SHORT).show()
    }

    private fun buildSystemBar(): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(6)); setBackgroundColor(Palette.SURFACE_DEEP) }
        fun cell(icon: String, initial: String): TextView {
            val v = makeText("$icon $initial", 10f, Palette.TEXT_SECONDARY, true).apply { setTypeface(Typeface.MONOSPACE); setPadding(0, 0, dp(14), 0) }
            bar.addView(v)
            return v
        }
        sysLinkView = cell("●", "LINK IDLE")
        sysRssiView = cell("📶", "RSSI --")
        sysMtuView = cell("🔗", "MTU --")
        sysQueueView = cell("⏳", "QUEUE 0/0")
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        return bar
    }

    private fun startSystemBar() {
        stopSystemBar()
        val r = object : Runnable {
            override fun run() {
                updateSystemBar()
                bleEngine?.readRssi()
                handler.postDelayed(this, 1500)
            }
        }
        sysBarRunnable = r
        handler.postDelayed(r, 300)
    }

    private fun stopSystemBar() {
        sysBarRunnable?.let { handler.removeCallbacks(it) }
        sysBarRunnable = null
    }

    private fun updateSystemBar() {
        try {
            val engine = bleEngine
            sysRssiView?.text = if (lastRssi == Int.MIN_VALUE) "RSSI --" else "RSSI $lastRssi dBm"
            sysMtuView?.text = "MTU ${engine?.currentMtu() ?: 23}"
            val depth = engine?.queueDepth() ?: 0
            val inFlight = engine?.isWriteInFlight() == true
            sysQueueView?.text = "QUEUE $depth/${if (inFlight) 1 else 0}"
            sysLinkView?.text = "LINK $linkState"
        } catch (_: Exception) {}
    }

    private fun classifyLog(line: String): LogCategory {
        val l = line.lowercase(Locale.ROOT)
        return when {
            l.contains("fehler") || l.contains("✘") -> LogCategory.ERROR
            l.contains("flash") -> LogCategory.FLASH
            l.contains("> tx") -> LogCategory.TX
            l.contains("> rx") -> LogCategory.RX
            else -> LogCategory.INFO
        }
    }

    private fun log(line: String) {
        if (Looper.myLooper() == Looper.getMainLogLooperOrMain()) {
            appendLogEntry(line)
        } else {
            handler.post { appendLogEntry(line) }
        }
    }

    private fun Looper.Companion.getMainLogLooperOrMain(): Looper = MainLooper

    private fun appendLogEntry(line: String) {
        try {
            val entry = LogEntry(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), classifyLog(line), line)
            logEntries.addLast(entry)
            while (logEntries.size > 500) logEntries.removeFirst()
            scheduleLogRefresh()
        } catch (_: Exception) {}
    }

    private fun scheduleLogRefresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { scheduleLogRefresh() }
            return
        }
        if (logRefreshScheduled) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastLogRefresh
        if (elapsed >= UI_REFRESH_INTERVAL_MS) {
            lastLogRefresh = now
            refreshLogView()
            return
        }
        logRefreshScheduled = true
        handler.postDelayed({
            logRefreshScheduled = false
            lastLogRefresh = System.currentTimeMillis()
            refreshLogView()
        }, UI_REFRESH_INTERVAL_MS - elapsed)
    }

    private fun scheduleTelemetryApply() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { scheduleTelemetryApply() }
            return
        }
        if (telemetryApplyScheduled) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastTelemetryApply
        if (elapsed >= UI_REFRESH_INTERVAL_MS) {
            lastTelemetryApply = now
            applyLatestTelemetry()
            return
        }
        telemetryApplyScheduled = true
        handler.postDelayed({
            telemetryApplyScheduled = false
            lastTelemetryApply = System.currentTimeMillis()
            applyLatestTelemetry()
        }, UI_REFRESH_INTERVAL_MS - elapsed)
    }

    private fun applyLatestTelemetry() {
        val t = lastTelemetry ?: return
        try { applyTelemetryToDashboard(t) } catch (_: Exception) {}
    }

    private fun refreshLogView() {
        try {
            val container = logContainer ?: return
            container.removeAllViews()
            val filter = logFilter
            val shown = logEntries.filter { filter == null || it.category == filter }
            for (e in shown) {
                container.addView(makeText("${e.time} [${e.category.label}] ${e.text}", 10f, Palette.TEXT_SECONDARY).apply { setTypeface(Typeface.MONOSPACE) })
            }
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        } catch (_: Exception) {}
    }

    private fun buildLogTerminal(): LinearLayout {
        val card = makeCard()
        card.addView(makeText("🖥 Log-Terminal", 14f, Color.WHITE, true))
        logScroll = ScrollView(this).setBackgroundColor(Color.rgb(8, 10, 14)).setPadding(dp(10), dp(10), dp(10), dp(10)) as ScrollView
        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logScroll?.addView(logContainer)
        card.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
        return card
    }
}

data class FirmwareValidation(
    val valid: Boolean,
    val message: String,
    val sha256: String,
    val isZip: Boolean,
    val modelCompatible: Boolean,
    val extractedBinary: ByteArray?
)
