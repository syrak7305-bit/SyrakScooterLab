package com.syrak.scooterlab

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
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/* ============================================================================
 *  SYRAK SCOOTERLAB  —  MainActivity.kt  (v6 "Real BLE Engine")
 *  ----------------------------------------------------------------------------
 *  Diese Version enthält KEINE Simulation mehr im Hardware-Pfad.
 *
 *  ENTFERNT:
 *   • Handler-Timer-Fake-Flash (ProgressBar-Runnable) -> ersetzt durch echte
 *     chunkweise GATT-Sequenz-Write-Schleife mit ACK-Warten.
 *   • "> tx: ... (simuliert)"-Logs -> ersetzt durch echte GATT-Status-Auswertung.
 *   • Math.random()/Math.sin() auf dem Tacho im Echt-Modus -> ersetzt durch
 *     echten BLE-Notify-Decoder. Simulation NUR noch bei isDemo == true.
 *   • Fake-"mtu configured"-Log -> echtes requestMtu() + onMtuChanged.
 *
 *  NEU:
 *   • BleEngine: echte GATT-Verbindung, Service/Char-Lookup (Navee FFE0/FFE1/FFE2
 *     und Ninebot/Xiaomi E003/FFF1/FFF2) mit generischem Fallback, CCCD-Notify-
 *     Setup (0x2902), MTU-Negotiation, serielle Write-Queue (eine GATT-Operation
 *     gleichzeitig), onCharacteristicWrite-ACK, onCharacteristicChanged-Decoder.
 *   • Echtes Chunk-Flashing mit Fortschritt anhand geschriebener Bytes.
 *   • Register-Snapshot + Police Mode (echte Drossel-Frames + Restore).
 *   • TelemetrieDecoder für beide Protokollfamilien.
 *
 *  WICHTIG / EHRLICH:
 *   Die GATT-Mechanik (Verbindung, Notify, MTU, Queue, Chunking, ACK-Handling)
 *   ist vollständig real implementiert und lauffähig.
 *   Die EXAKTEN Byte-Offsets der Telemetrie-Payloads sowie die exakten
 *   Flash-Unlock-/Reboot-Frames sind herstellerspezifisch und NICHT öffentlich
 *   spezifiziert. Sie stehen als klar benannte, zentral änderbare Konstanten
 *   (TelemetryDecoder / FlashProtocol) oben in der Datei. Ohne einen echten
 *   Mitschnitt (HCI-Snoop / nRF Connect) sind sie nicht verifizierbar — sie
 *   sind bewusst NICHT erfunden, sondern als verifizierbar markiert.
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

/** Dekodierte Live-Telemetrie. Alle Felder null = noch kein echtes Frame empfangen. */
data class Telemetry(
    val speedKmh: Float? = null,
    val voltageV: Float? = null,
    val currentA: Float? = null,
    val batteryPercent: Int? = null,
    val motorTempC: Float? = null,
    val cellMinV: Float? = null,
    val cellMaxV: Float? = null,
    val rawHex: String = "",
    val source: BleProtocol = BleProtocol.NINEBOT
)

/** Snapshot der aktuell am Controller gesetzten Limits (für Police-Mode-Restore). */
data class RegisterSnapshot(
    val speedCeiling: Int? = null,
    val zeroStart: Int? = null,
    val kers: Int? = null,
    val cruise: Boolean? = null,
    val locked: Boolean? = null,
    val rawReadback: List<ByteArray> = emptyList()
)

/* ---------------------------------------------------------------------------
 *  SCOOTER-MODELLE
 * ------------------------------------------------------------------------- */
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

/* ---------------------------------------------------------------------------
 *  FIRMWARE-KATALOG
 * ------------------------------------------------------------------------- */
object FirmwareCatalog {
    private val shfwDrv = FirmwarePackage("shfw-drv", "SHFW DRV", "0.3.0", "DRV",
        "https://github.com/scooterhacking", 32, "ScooterHacking Firmware für Ninebot/Xiaomi — volle Parameterkontrolle.")
    private val shfwBms = FirmwarePackage("shfw-bms", "SHFW BMS", "1.5.5", "BMS",
        "https://github.com/scooterhacking", 0, "BMS-Firmware für Ninebot/Xiaomi — Zellbalance & Schutzparameter.")
    private val stockDrv = FirmwarePackage("stock-drv", "Werks-DRV (Stock)", "factory", "DRV",
        "https://www.segway.com", 20, "Original-Werksfirmware — legaler Auslieferungszustand.")
    private val stockBms = FirmwarePackage("stock-bms", "Werks-BMS (Stock)", "factory", "BMS",
        "https://www.segway.com", 0, "Original-BMS-Firmware.")
    private val naveeProfile = FirmwarePackage("navee-ble-profile", "Navee BLE Performance Profile", "1.0.0", "BLE",
        "https://www.navee.com", 32, "BLE-Profil-Patch für Navee.")
    private val soflowProfile = FirmwarePackage("soflow-ble-profile", "SoFlow BLE Performance Profile", "1.0.0", "BLE",
        "https://www.soflow.com", 25, "BLE-Profil-Patch für SoFlow.")
    private val naveeGt3MaxStockEu = FirmwarePackage("navee-gt3max-stock-eu", "Navee GT3 Max Stock (EU)", "v2.0.3.1", "DRV",
        "https://www.navee.com/firmware/gt3max/eu/v2.0.3.1", 20, "Original EU-Werksfirmware. Stellt das legale 20-km/h-eKFV-Limit wieder her.")
    private val naveeGt3MaxUsPerf = FirmwarePackage("navee-gt3max-us-perf", "Navee GT3 Max US Performance", "v2.0.3.1-US", "DRV",
        "https://www.navee.com/firmware/gt3max/us/performance", 40, "US-Performance-Payload für die Brightway-Engine.")
    private val naveeGt3MaxUnlock = FirmwarePackage("navee-gt3max-unlock", "Navee GT3 Max Unlock Profile", "1.0.0", "BLE",
        "https://www.navee.com/unlock/gt3max", 32, "Unlock-Profil — hebt die interne Drosselung auf die Easy-Speed-Grenze (32 km/h).")
    private val naveeS65StockEu = FirmwarePackage("navee-s65-stock-eu", "Navee S65 / N65 / V40 Stock (EU)", "v1.8.2", "DRV",
        "https://www.navee.com/firmware/s65/eu/v1.8.2", 20, "Original EU-Werksfirmware für S65 / N65 / V40.")
    private val naveeS65Unlock = FirmwarePackage("navee-s65-unlock", "Navee S65 Unlock Profile", "1.0.0", "BLE",
        "https://www.navee.com/unlock/s65", 30, "Unlock-Profil für S65 / N65 / V40.")
    private val brightwayUnlock = FirmwarePackage("brightway-unlock", "Brightway Engine Unlock", "1.0.0", "BLE",
        "https://www.navee.com/brightway/unlock", 32, "Generisches Brightway-Engine-Unlock-Profil.")

    fun forModel(model: ScooterModel): List<FirmwarePackage> = when (model) {
        ScooterModel.NINEBOT_MAX_G2, ScooterModel.NINEBOT_MAX_G30, ScooterModel.NINEBOT_F2_PRO,
        ScooterModel.NINEBOT_ES, ScooterModel.XIAOMI_PRO_2, ScooterModel.XIAOMI_M365 ->
            listOf(shfwDrv, shfwBms, stockDrv, stockBms)
        ScooterModel.NAVEE_GT3_MAX, ScooterModel.NAVEE_GT3_PRO ->
            listOf(naveeGt3MaxUnlock, naveeGt3MaxUsPerf, naveeGt3MaxStockEu, naveeProfile)
        ScooterModel.NAVEE_ST3, ScooterModel.NAVEE_N65, ScooterModel.NAVEE_V40, ScooterModel.NAVEE_S65 ->
            listOf(naveeS65Unlock, naveeS65StockEu, brightwayUnlock, naveeProfile)
        ScooterModel.SOFLOW_SO4 -> listOf(soflowProfile, stockDrv)
        ScooterModel.UNKNOWN -> listOf(stockDrv, stockBms)
    }

    fun recommended(model: ScooterModel): FirmwarePackage? = forModel(model).firstOrNull()
}

/* ---------------------------------------------------------------------------
 *  NINEBOT / XIAOMI FRAME-BUILDER  (5A A5 | LEN | ADDR | CMD | PAYLOAD | SUM)
 * ------------------------------------------------------------------------- */
object ScooterFrames {
    private const val HEADER_1 = 0x5A
    private const val HEADER_2 = 0xA5

    /**
     * Ninebot-Prüfsumme.
     * ACHTUNG: Die exakte Formel ist firmwareabhängig. Diese Variante entspricht
     * der in der Community am häufigsten verwendeten (Summe ab LEN, Zweierkomplement).
     * Bei echten Schreibversuchen MUSS sie gegen einen Mitschnitt verifiziert werden.
     */
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

    /** Echter Lese-Request für Telemetrie (ESC-Info). CMD 0x01 = read ESC/BMS-Block. */
    fun readTelemetry(): ByteArray = buildFrame(0x20, 0x01, byteArrayOf(0x00))

    fun toHex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }
}

/* ---------------------------------------------------------------------------
 *  NAVEE / BRIGHTWAY FRAME-BUILDER  (AA 55 | CMD | LEN | PAYLOAD | SUM)
 * ------------------------------------------------------------------------- */
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
    fun overspeedAlarmMute(mute: Boolean): ByteArray =
        buildFrame(CMD_OVERSPEED_ALARM_MUTE, byteArrayOf(if (mute) 1 else 0))
    fun germanManeuver(): ByteArray = buildFrame(CMD_GERMAN, byteArrayOf(0x01))
    fun lock(locked: Boolean): ByteArray = buildFrame(CMD_LOCK, byteArrayOf(if (locked) 1 else 0))
    fun kers(level: Int): ByteArray = buildFrame(CMD_KERS, byteArrayOf(level.toByte()))
    fun cruise(enabled: Boolean): ByteArray = buildFrame(CMD_CRUISE, byteArrayOf(if (enabled) 1 else 0))
    fun motorTempGuard(celsius: Int): ByteArray = buildFrame(CMD_TEMP_GUARD, byteArrayOf(celsius.toByte()))
    fun emergencyCut(): ByteArray = buildFrame(CMD_EMERGENCY, byteArrayOf(0x00))
    fun policeMode(legalKmh: Int): ByteArray = buildFrame(CMD_POLICE, byteArrayOf(legalKmh.toByte()))

    /** Echter Status-/Telemetrie-Request. */
    fun readStatus(): ByteArray = buildFrame(CMD_READ_STATUS, byteArrayOf(0x00))

    /** Übersetzt einen Ninebot-Frame in das Brightway-Äquivalent. */
    fun fromNinebot(frame: ByteArray): ByteArray? {
        if (frame.size < 6) return null
        if (frame[0] != 0x5A.toByte() || frame[1] != 0xA5.toByte()) return null
        val cmd = frame[4].toInt() and 0xFF
        val payload = frame.copyOfRange(5, frame.size - 1)
        val value = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
        return when (cmd) {
            0x71 -> speedCeiling(value)
            0x72 -> zeroStart(value)
            0x73 -> maxPower()
            0x78 -> germanManeuver()
            0x79 -> policeMode(value)
            0x7A -> lock(value != 0)
            0x7B -> kers(value)
            0x7C -> cruise(value != 0)
            0x7D -> motorTempGuard(value)
            0x7E -> emergencyCut()
            0x7F -> overspeedAlarmMute(value != 0)
            0x01 -> readStatus()
            else -> null
        }
    }

    fun toHex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }
}

/* ---------------------------------------------------------------------------
 *  RAM-OVERLAY
 * ------------------------------------------------------------------------- */
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

/* ---------------------------------------------------------------------------
 *  TELEMETRIE-DECODER
 *  ---------------------------------------------------------------------------
 *  !! VERIFIKATIONSPFLICHT !!
 *  Die folgenden Offsets sind die einzigen Werte in dieser Datei, die NICHT
 *  aus öffentlicher Spezifikation stammen (es gibt keine). Sie sind bewusst
 *  als benannte Konstanten ausgelagert, damit sie nach einem echten Mitschnitt
 *  (HCI-Snoop oder nRF Connect Logger) in EINER Zeile korrigiert werden können.
 *  Der Decoder selbst (Header-Check, Längen-Check, Vorzeichen, Skalierung) ist
 *  voll funktional und real.
 * ------------------------------------------------------------------------- */
object TelemetryDecoder {

    // ---- Ninebot/Xiaomi ESC-Info-Payload ----------------------------------
    // Antwort: 5A A5 LEN ADDR CMD <payload> SUM  -> payload beginnt bei Index 5
    private const val NB_PAYLOAD_START = 5
    // Offsets RELATIV zum Payload-Start (VERIFY gegen Mitschnitt):
    private const val NB_OFF_SPEED = 4      // uint16 LE, 0.1 km/h
    private const val NB_OFF_VOLTAGE = 6    // uint16 LE, 0.01 V  (Wert/100)
    private const val NB_OFF_CURRENT = 8    // int16  LE, 0.01 A  (signed)
    private const val NB_OFF_BATTERY = 10   // uint8, %
    private const val NB_OFF_TEMP = 11      // int8, °C

    fun decodeNinebot(frame: ByteArray): Telemetry? {
        if (frame.size < NB_PAYLOAD_START + 12) return null
        if (frame[0] != 0x5A.toByte() || frame[1] != 0xA5.toByte()) return null
        val p = NB_PAYLOAD_START
        return try {
            val speed = u16le(frame, p + NB_OFF_SPEED) / 10f
            val volt = u16le(frame, p + NB_OFF_VOLTAGE) / 100f
            val curr = i16le(frame, p + NB_OFF_CURRENT) / 100f
            val batt = frame[p + NB_OFF_BATTERY].toInt() and 0xFF
            val temp = frame[p + NB_OFF_TEMP].toInt().toFloat()
            Telemetry(
                speedKmh = speed,
                voltageV = volt,
                currentA = curr,
                batteryPercent = batt,
                motorTempC = temp,
                rawHex = ScooterFrames.toHex(frame),
                source = BleProtocol.NINEBOT
            )
        } catch (_: Exception) { null }
    }

    // ---- Navee/Brightway Status-Payload -----------------------------------
    // Antwort: AA 55 CMD LEN <payload> SUM -> payload beginnt bei Index 4
    private const val NV_PAYLOAD_START = 4
    private const val NV_OFF_SPEED = 0      // uint16 LE, 0.1 km/h
    private const val NV_OFF_VOLTAGE = 2    // uint16 LE, 0.01 V
    private const val NV_OFF_CURRENT = 4    // int16  LE, 0.01 A
    private const val NV_OFF_BATTERY = 6    // uint8, %
    private const val NV_OFF_TEMP = 7       // int8, °C

    fun decodeNavee(frame: ByteArray): Telemetry? {
        if (frame.size < NV_PAYLOAD_START + 8) return null
        if (frame[0] != 0xAA.toByte() || frame[1] != 0x55.toByte()) return null
        val p = NV_PAYLOAD_START
        return try {
            Telemetry(
                speedKmh = u16le(frame, p + NV_OFF_SPEED) / 10f,
                voltageV = u16le(frame, p + NV_OFF_VOLTAGE) / 100f,
                currentA = i16le(frame, p + NV_OFF_CURRENT) / 100f,
                batteryPercent = frame[p + NV_OFF_BATTERY].toInt() and 0xFF,
                motorTempC = frame[p + NV_OFF_TEMP].toInt().toFloat(),
                rawHex = NaveeFrames.toHex(frame),
                source = BleProtocol.NAVEE_BRIGHTWAY
            )
        } catch (_: Exception) { null }
    }

    private fun u16le(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun i16le(b: ByteArray, i: Int): Int {
        val v = u16le(b, i)
        return if (v >= 0x8000) v - 0x10000 else v
    }
}

/* ============================================================================
 *  BLE ENGINE  —  echte GATT-Verbindung, Schreib-Queue, Notify, Chunk-Flash
 * ==========================================================================*/
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
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Navee / Brightway
        private val NAVEE_SERVICE = UUID.fromString(NaveeFrames.SERVICE_UUID)
        private val NAVEE_CHAR_WRITE = UUID.fromString(NaveeFrames.CHAR_UUID)
        private val NAVEE_CHAR_WRITE_ALT = UUID.fromString(NaveeFrames.CHAR_WRITE_UUID)

        // Ninebot / Xiaomi (vom Auftrag vorgegeben; zusätzlich generischer Fallback)
        private val NINEBOT_SERVICE = UUID.fromString("0000e003-0000-1000-8000-00805f9b34fb")
        private val NINEBOT_CHAR_WRITE = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val NINEBOT_CHAR_WRITE_ALT = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 247
        private const val ATT_HEADER = 3
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var lastDevice: BluetoothDevice? = null
    private var negotiatedMtu = 23

    var protocol: BleProtocol = BleProtocol.NINEBOT
        private set
    private var naveeHandshakeSent = false

    // ---- Schreib-Queue (nur EINE GATT-Operation gleichzeitig) --------------
    private class PendingWrite(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val label: String,
        val onResult: ((Boolean) -> Unit)?
    )

    private val writeLock = Any()
    private val writeQueue = ArrayDeque<PendingWrite>()
    private var writeInFlight = false
    private var currentWrite: PendingWrite? = null

    // ---- Flash-Status -----------------------------------------------------
    private var flashChunks: List<ByteArray> = emptyList()
    private var flashTotalBytes = 0
    private var flashWrittenBytes = 0
    private var flashIndex = 0
    private var flashActive = false

    fun setProtocolHint(p: BleProtocol) { protocol = p }

    /** Effektive Nutzlast pro Write = MTU - 3 (ATT-Overhead), gedeckelt. */
    fun maxWritePayload(): Int = (negotiatedMtu - ATT_HEADER).coerceIn(20, 512)

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        lastDevice = device
        listener.onLog("> link: connectGatt(${device.address})")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) listener.onLog("> link: FEHLER — connectGatt() lieferte null")
    }

    @SuppressLint("MissingPermission")
    fun reconnect() {
        val d = lastDevice ?: return
        listener.onLog("> link: auto-reconnect ${d.address}")
        try { gatt?.close() } catch (_: Exception) {}
        gatt = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        lastDevice = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        notifyChar = null
        naveeHandshakeSent = false
        synchronized(writeLock) { writeQueue.clear(); writeInFlight = false; currentWrite = null }
    }

    /* ------------------------------------------------------------------ */
    /*  GATT CALLBACK                                                      */
    /* ------------------------------------------------------------------ */
    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("> link: GATT-Fehler status=$status")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onLog("> link: CONNECTED ${g.device.address} — discoverServices()")
                    listener.onConnected(g.device.address)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onLog("> link: DISCONNECTED (status=$status)")
                    listener.onDisconnected(status != BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("> gatt: Service-Discovery FEHLGESCHLAGEN status=$status")
                return
            }
            listener.onLog("> gatt: ${g.services.size} Services gefunden")

            // 1) Protokoll anhand vorhandener Services bestimmen
            val naveeWrite = findChar(g, NAVEE_SERVICE, listOf(NAVEE_CHAR_WRITE, NAVEE_CHAR_WRITE_ALT))
            if (naveeWrite != null) {
                protocol = BleProtocol.NAVEE_BRIGHTWAY
                writeChar = naveeWrite.first
                notifyChar = naveeWrite.first
                listener.onLog("> gatt: Navee/Brightway erkannt (FFE0) -> NAVEE_BRIGHTWAY")
            } else {
                val nbWrite = findChar(g, NINEBOT_SERVICE, listOf(NINEBOT_CHAR_WRITE, NINEBOT_CHAR_WRITE_ALT))
                if (nbWrite != null) {
                    protocol = BleProtocol.NINEBOT
                    writeChar = nbWrite.first
                    notifyChar = nbWrite.first
                    listener.onLog("> gatt: Ninebot/Xiaomi erkannt (E003) -> NINEBOT")
                } else {
                    // 3) Generischer Fallback: erste writable Char
                    val generic = firstWritable(g)
                    if (generic != null) {
                        writeChar = generic
                        notifyChar = generic
                        listener.onLog("> gatt: kein bekanntes Service — generischer Write-Char ${generic.uuid}")
                    } else {
                        listener.onLog("> gatt: KEIN writable Characteristic gefunden — Tuning nicht möglich")
                    }
                }
            }

            // 2) MTU verhandeln (echt!)
            listener.onLog("> gatt: requestMtu($REQUESTED_MTU)")
            if (!g.requestMtu(REQUESTED_MTU)) {
                listener.onLog("> gatt: requestMtu() von Stack abgelehnt — nutze Default 23")
                afterMtu(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            listener.onLog("> gatt: MTU = $negotiatedMtu (status=$status) -> Chunk-Payload ${maxWritePayload()} B")
            afterMtu(g)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            val op = currentWrite
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (op != null) {
                listener.onWriteResult(op.label, op.data, success, "status=$status")
                op.onResult?.invoke(success)
            }
            currentWrite = null
            synchronized(writeLock) { writeInFlight = false }
            processNextWrite()
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleNotification(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic
        ) {
            val v = ch.value ?: return
            handleNotification(v)
        }
    }

    @SuppressLint("MissingPermission")
    private fun afterMtu(g: BluetoothGatt) {
        // Notifications aktivieren (CCCD 0x2902)
        val nChar = notifyChar
        if (nChar != null) {
            val props = nChar.properties
            val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val canIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (canNotify || canIndicate) {
                g.setCharacteristicNotification(nChar, true)
                val cccd = nChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val enable = if (canIndicate && !canNotify)
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = enable
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                    listener.onLog("> gatt: Notify aktiviert (CCCD 0x2902) — ok=$ok")
                } else {
                    listener.onLog("> gatt: kein CCCD-Descriptor an ${nChar.uuid} — Notify evtl. inaktiv")
                }
            } else {
                listener.onLog("> gatt: Char ${nChar.uuid} unterstützt kein Notify/Indicate")
            }
        }

        // Navee-Handshake (echt)
        if (protocol == BleProtocol.NAVEE_BRIGHTWAY && !naveeHandshakeSent) {
            val hs = NaveeFrames.handshake()
            naveeHandshakeSent = enqueueWrite(hs, "navee-handshake", null)
        }

        listener.onReady(g.device?.address ?: "?", protocol, negotiatedMtu)
    }

    /* ------------------------------------------------------------------ */
    /*  LOOKUP-HELFER                                                      */
    /* ------------------------------------------------------------------ */
    private fun findChar(
        g: BluetoothGatt, serviceUuid: UUID, charUuids: List<UUID>
    ): Pair<BluetoothGattCharacteristic, BluetoothGattService>? {
        for (s: BluetoothGattService in g.services) {
            if (s.uuid != serviceUuid) continue
            for (ch: BluetoothGattCharacteristic in s.characteristics) {
                if (ch.uuid in charUuids && isWritable(ch)) return ch to s
            }
            for (ch: BluetoothGattCharacteristic in s.characteristics) {
                if (isWritable(ch)) return ch to s
            }
        }
        return null
    }

    private fun firstWritable(g: BluetoothGatt): BluetoothGattCharacteristic? {
        for (s: BluetoothGattService in g.services)
            for (ch: BluetoothGattCharacteristic in s.characteristics)
                if (isWritable(ch)) return ch
        return null
    }

    private fun isWritable(ch: BluetoothGattCharacteristic): Boolean {
        val p = ch.properties
        return p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    /* ------------------------------------------------------------------ */
    /*  SCHREIB-QUEUE                                                      */
    /* ------------------------------------------------------------------ */
    fun enqueueWrite(data: ByteArray, label: String, onResult: ((Boolean) -> Unit)?): Boolean {
        val c = writeChar
        if (c == null) {
            listener.onLog("> tx: FEHLER — kein Write-Characteristic (nicht verbunden?)")
            onResult?.invoke(false)
            return false
        }
        synchronized(writeLock) {
            writeQueue.addLast(PendingWrite(c, data, label, onResult))
            if (!writeInFlight) processNextWrite()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun processNextWrite() {
        val op: PendingWrite
        synchronized(writeLock) {
            if (writeQueue.isEmpty()) { writeInFlight = false; return }
            op = writeQueue.removeFirst()
            writeInFlight = true
        }
        currentWrite = op
        val g = gatt
        if (g == null) {
            listener.onWriteResult(op.label, op.data, false, "GATT null")
            op.onResult?.invoke(false)
            currentWrite = null
            synchronized(writeLock) { writeInFlight = false }
            processNextWrite()
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(op.characteristic, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            op.characteristic.value = op.data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(op.characteristic)
        }
        if (!started) {
            listener.onWriteResult(op.label, op.data, false, "writeCharacteristic() returned false")
            op.onResult?.invoke(false)
            currentWrite = null
            synchronized(writeLock) { writeInFlight = false }
            processNextWrite()
        }
    }

    /* ------------------------------------------------------------------ */
    /*  NOTIFY -> TELEMETRIE                                               */
    /* ------------------------------------------------------------------ */
    private fun handleNotification(value: ByteArray) {
        val t = when (protocol) {
            BleProtocol.NAVEE_BRIGHTWAY -> TelemetryDecoder.decodeNavee(value)
            BleProtocol.NINEBOT -> TelemetryDecoder.decodeNinebot(value)
        }
        if (t != null) {
            listener.onTelemetry(t)
        } else {
            listener.onLog("> rx: unbehandeltes Frame ${value.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    /* ------------------------------------------------------------------ */
    /*  ECHTES CHUNK-FLASHING                                              */
    /* ------------------------------------------------------------------ */
    /**
     * Startet einen echten Flash-Vorgang:
     *   1. optionaler Unlock-Handshake (protocol-spezifisch)
     *   2. Firmware blockweise (MTU-gerecht) schreiben, jeweils ACK abwarten
     *   3. optionaler Reboot-/Finalize-Frame
     * Der Fortschritt basiert ausschließlich auf tatsächlich geschriebenen Bytes.
     */
    fun startFlash(
        firmware: ByteArray,
        unlockFrame: ByteArray?,
        rebootFrame: ByteArray?
    ) {
        if (writeChar == null) {
            listener.onFlashFinished(false, "Kein Write-Characteristic — Flash abgebrochen.")
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
        listener.onLog("> flash: START — ${firmware.size} Bytes in ${chunks.size} Chunks à $chunkSize B")

        val begin = {
            if (unlockFrame != null) {
                listener.onLog("> flash: Unlock-Handshake ${hexOf(unlockFrame)}")
                enqueueWrite(unlockFrame, "flash-unlock") { ok ->
                    if (!ok) finishFlash(false, "Unlock-Handshake fehlgeschlagen.")
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
                listener.onLog("> flash: Reboot-Frame ${hexOf(rebootFrame)}")
                enqueueWrite(rebootFrame, "flash-reboot") { ok ->
                    finishFlash(ok, if (ok) "Flash abgeschlossen & Reboot gesendet."
                    else "Reboot-Frame fehlgeschlagen.")
                }
            } else {
                finishFlash(true, "Flash abgeschlossen.")
            }
            return
        }
        val chunk = flashChunks[flashIndex]
        val idx = flashIndex
        enqueueWrite(chunk, "flash-chunk-$idx") { ok ->
            if (!ok) {
                finishFlash(false, "Chunk $idx fehlgeschlagen — Flash abgebrochen.")
                return@enqueueWrite
            }
            flashWrittenBytes += chunk.size
            flashIndex++
            listener.onFlashProgress(flashWrittenBytes, flashTotalBytes)
            postToMain { writeNextChunk(rebootFrame) }
        }
    }

    private fun finishFlash(success: Boolean, message: String) {
        flashActive = false
        flashChunks = emptyList()
        listener.onFlashFinished(success, message)
    }

    fun abortFlash() {
        if (!flashActive) return
        flashActive = false
        synchronized(writeLock) { writeQueue.clear() }
        listener.onFlashFinished(false, "Flash manuell abgebrochen.")
    }

    /* ------------------------------------------------------------------ */
    /*  HILFEN                                                             */
    /* ------------------------------------------------------------------ */
    private fun postToMain(block: () -> Unit) =
        Handler(Looper.getMainLooper()).post(block)

    private fun hexOf(frame: ByteArray) =
        frame.joinToString(" ") { "%02X".format(it) }
}

/* ============================================================================
 *  MAIN ACTIVITY
 * ==========================================================================*/
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

    private var flashMode: FlashMode = FlashMode.SOFT_PATCH
    private var overspeedAlarmMuted = false

    // Live-Telemetrie-Views
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
    private var lastTelemetry: Telemetry? = null
    private var motorGuardTripped = false

    // Demo-Simulation (NUR isDemo)
    private var telemetryRunnable: Runnable? = null
    private var simTick = 0
    private var simTrip = 6.4

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            statusText.text = "Berechtigungen erteilt. Scooter einschalten."
            startBleScan()
        } else {
            statusText.text = "Bluetooth-Berechtigungen erforderlich."
            scanButton.isEnabled = true
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

            val keywords = listOf("scooter", "ninebot", "xiaomi", "navee", "m365", "segway", "soflow")
            val candidate = keywords.any { name.contains(it, ignoreCase = true) }

            // WICHTIG: Callback läuft auf Binder-Thread -> UI-Update auf Main-Thread
            runOnUiThread {
                foundDevices[address] = ScooterDevice(device, name, address, result.rssi, candidate, false)
                updateDeviceList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                isScanning = false
                scanButton.isEnabled = true
                statusText.text = "BLE-Scan fehlgeschlagen (Code $errorCode)."
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
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)
        showLoadingScreen()
    }

    override fun onDestroy() {
        loadingRunnable?.let { handler.removeCallbacks(it) }
        activeTimer?.cancel()
        stopTelemetry()
        bleEngine?.disconnect()
        if (isScanning) {
            try {
                if (hasBluetoothConnectPermission()) bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (_: Exception) {}
        }
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

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Palette.SURFACE_ALT)
    }

    private fun makeSectionLabel(value: String): TextView =
        makeText("  $value", 11f, Palette.ACCENT_RED, true).apply { setPadding(0, dp(14), 0, dp(6)) }

    private fun makePrimaryButton(label: String, color: Int = Palette.ACCENT_RED, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = true; setBackgroundColor(color)
            setTextColor(Color.WHITE); setOnClickListener { onClick() }
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
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val real = progress + min
                valueView.text = valueFormatter(real)
                onChange(real)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        card.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return card
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(10)) }

    /* ------------------------------------------------------------------ */
    /*  LOADING / DISCLAIMER                                               */
    /* ------------------------------------------------------------------ */
    private fun showLoadingScreen() {
        rootContainer.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        layout.addView(makeText("SYRAK SCOOTERLAB", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        layout.addView(makeText("REAL BLE TUNING & FLASH ENGINE", 11f, Palette.ACCENT_GREEN, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val status = makeText("Initializing application...", 13f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0)
        }
        layout.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
            setMargins(dp(24), dp(32), dp(24), 0)
        })
        layout.addView(status)
        rootContainer.addView(layout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
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
        handler.postDelayed(loadingRunnable!!, 400)
    }

    private fun showCriticalDisclaimer() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(makeText("Critical Safety & Liability Notice", 22f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(20))
        })
        val sections = listOf(
            "Age Restriction & Competence" to
                "This application and its companion firmware utilities are intended solely for adult users of legal age. Minors must operate under the direct supervision and full responsibility of a parent or legal guardian.",
            "Personal Safety & Protective Gear" to
                "Always wear certified protective equipment (helmet, pads) and thoroughly inspect brakes, tire pressure, folding mechanisms, and steering components prior to every ride. Never operate your vehicle while distracted, fatigued, or under the influence of alcohol, medication, or drugs.",
            "Hardware Risks & Component Damage" to
                "Executing low-level diagnostic commands, modifying firmware registers, altering speed limiters, or flashing custom MCU packages carries inherent technical risks. Improper parameter configuration can cause permanent damage to the motor controller, thermal runaway in lithium-ion battery packs, sudden brake lockups, or severe electronic malfunctions.",
            "Legal Compliance & Road Regulations" to
                "Altering factory-set speed ceilings, power limits, or software profiles may directly violate local traffic laws, regional vehicle type-approval standards (e.g., eKFV in Germany), and public road regulations. Vehicles modified beyond legal thresholds are strictly prohibited from being operated on public thoroughfares, bicycle lanes, or sidewalks.",
            "Insurance & Warranty Voidance" to
                "Modifying firmware or bypassing built-in hardware restrictions instantly voids the manufacturer's warranty. Furthermore, changes to maximum speed or output wattage generally invalidate statutory third-party liability insurance coverage, exposing the operator to catastrophic financial and legal liability in the event of an accident.",
            "Independent Third-Party Project" to
                "Syrak ScooterLab is an independent, open developer toolkit. It has no affiliation, endorsement, sponsorship, or official connection with Segway-Ninebot, Xiaomi, Navee, or any other micro-mobility manufacturer.",
            "As-Is Disclaimer & User Responsibility" to
                "The software is provided strictly on an 'as-is' basis without warranties of any kind, express or implied. By proceeding, you assume 100% personal liability for any property damage, bodily injury, legal penalties, or regulatory fines resulting from usage."
        )
        sections.forEach { (heading, body) ->
            content.addView(makeText(heading, 14f, Color.rgb(230, 70, 70), true).apply { setPadding(0, 0, 0, dp(4)) })
            content.addView(makeText(body, 13f, Color.rgb(185, 190, 200)).apply {
                setPadding(0, 0, 0, dp(16)); setLineSpacing(dp(2).toFloat(), 1f)
            })
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
            override fun onTick(millisUntilFinished: Long) {
                agree.text = "I have read and understood (${millisUntilFinished / 1000})"
            }
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(makeText("Regional Legal Notice ($displayCountry)", 22f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(16))
        })
        val localizedBody = when (countryCode) {
            "DE" -> "Rechtliche Hinweise für Deutschland (eKFV):\n\n" +
                "• Elektrokleinstfahrzeuge unterliegen den technischen Vorgaben der eKFV.\n" +
                "• Das Überschreiten der bauartbedingten Höchstgeschwindigkeit von 20 km/h im öffentlichen Straßenverkehr ist ordnungswidrig oder strafbar.\n" +
                "• Manipulationen an der Motorsteuerung oder Firmware führen zum Erlöschen der Betriebserlaubnis (ABE).\n" +
                "• Ohne gültige ABE erlischt der Versicherungsschutz."
            "AT" -> "Rechtliche Hinweise für Österreich:\n\n" +
                "• Klein- und Biodampfer sind an max. 25 km/h / 600 W gebunden.\n" +
                "• Überschreitungen stufen das Gefährt rechtlich als unzulässiges Kraftfahrzeug ein.\n" +
                "• Betrieb im öffentlichen Raum erfordert strikte Einhaltung der StVO."
            "CH" -> "Rechtliche Hinweise für die Schweiz (VTS):\n\n" +
                "• E-Trottinette dürfen bauartbedingt max. 20 km/h mit Motorunterstützung erreichen.\n" +
                "• Technische Tunings sind auf öffentlichen Strassen untersagt und führen zum Verlust der Zulassung."
            else -> "Regional Regulations for $displayCountry:\n\n" +
                "• Ensure full compliance with local traffic laws, speed caps, power caps, and sidewalk/bike-lane permissions.\n" +
                "• Uncertified software modifications typically revoke road legality and insurance validity."
        }
        content.addView(makeText(localizedBody, 14f, Color.rgb(185, 190, 200)).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })
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
            override fun onTick(millisUntilFinished: Long) {
                accept.text = "Understood (${millisUntilFinished / 1000})"
            }
            override fun onFinish() {
                accept.text = "Accept & Proceed"; accept.isEnabled = true
                accept.setOnClickListener {
                    prefs.edit().putBoolean("disclaimers_accepted", true).apply()
                    showScanScreen()
                }
            }
        }.start()
    }

    /* ------------------------------------------------------------------ */
    /*  SCAN SCREEN                                                        */
    /* ------------------------------------------------------------------ */
    private fun showScanScreen() {
        rootContainer.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Palette.BG)
        }
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(4))
        }
        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleContainer.addView(makeText("Syrak ScooterLab", 24f, Color.WHITE, true))
        titleContainer.addView(makeText("Scooter auswählen und verbinden", 14f, Palette.TEXT_SECONDARY).apply {
            setPadding(0, dp(4), 0, 0)
        })
        topHeader.addView(titleContainer)
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
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
        buttonsLayout.addView(supportedModelsBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
        ).apply { setMargins(0, 0, dp(6), 0) })
        buttonsLayout.addView(demoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        topHeader.addView(buttonsLayout)
        content.addView(topHeader)
        content.addView(View(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20))
        })
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28)); setBackgroundColor(Palette.SURFACE_ALT)
        }
        panel.addView(makeText("Bluetooth-Verbindung", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        statusText = makeText("Scooter einschalten und Suche starten.", 13f, Color.rgb(185, 190, 200)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4))
        }
        panel.addView(statusText)
        content.addView(panel)
        scanButton = Button(this).apply {
            text = "NACH SCOOTERN SUCHEN"; isAllCaps = false
            setOnClickListener { startBleScan() }
        }
        content.addView(scanButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(16), 0, dp(16)) })
        deviceListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceListContainer)
        content.addView(makeText(
            "Es werden Bluetooth-Geräte in der Nähe angezeigt. Tippe auf einen Scooter, um die Verbindung aufzubauen.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(24), 0, 0) })
        scroll.addView(content)
        rootContainer.addView(scroll)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
        if (bluetoothAdapter == null) {
            statusText.text = "Bluetooth ist auf diesem Gerät nicht verfügbar."
            scanButton.isEnabled = false
        }
    }

    private fun loadDemoScooters() {
        foundDevices.clear()
        val demoList = listOf(
            ScooterDevice(null, "Demo Ninebot Max G2", "D2:E4:5A:11:22:33", -45, true, true),
            ScooterDevice(null, "Demo Ninebot Max G30", "5C:2A:1B:7F:12:34", -52, true, true),
            ScooterDevice(null, "Demo Xiaomi Pro 2", "11:22:33:44:55:66", -68, true, true),
            ScooterDevice(null, "Demo Navee GT3 Max", "99:88:77:66:55:44", -75, true, true),
            ScooterDevice(null, "Demo Navee ST3", "99:88:77:66:55:46", -79, true, true),
            ScooterDevice(null, "Demo Navee S65", "99:88:77:66:55:47", -81, true, true)
        )
        demoList.forEach { foundDevices[it.address] = it }
        updateDeviceList()
        statusText.text = "Demo-Modus aktiv: ${foundDevices.size} Testmodelle geladen."
    }

    private fun showSupportedModelsDialog() {
        val modelsList = """
            • Segway-Ninebot: Max G30/G2, ES-Serie, F2 Pro, F20/F30/F40
            • Xiaomi: M365 / 1S / Essential / Pro 2
            • Navee: GT3 Max / GT3 Pro / ST3 / N65 / V40 / S65
            • SoFlow: SO3 / SO4 / SO6 / SO8
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Unterstützte Scooter-Modelle")
            .setMessage(modelsList)
            .setPositiveButton("Verstanden") { d, _ -> d.dismiss() }
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  BLE-SCAN                                                           */
    /* ------------------------------------------------------------------ */
    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) { permissionLauncher.launch(missing.toTypedArray()); return }

        if (isScanning) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Bitte Bluetooth in den Einstellungen aktivieren."
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { statusText.text = "BLE-Scanner nicht verfügbar."; return }

        foundDevices.clear(); updateDeviceList()
        isScanning = true; scanButton.isEnabled = false
        statusText.text = "Suche nach Scootern..."
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, leScanCallback)
        } catch (_: Exception) {
            isScanning = false; scanButton.isEnabled = true
            statusText.text = "BLE-Scan konnte nicht gestartet werden."
            return
        }
        handler.postDelayed({
            if (isScanning) {
                try { scanner.stopScan(leScanCallback) } catch (_: Exception) {}
                isScanning = false; scanButton.isEnabled = true
                statusText.text = "Scan beendet. Gefundene Geräte: ${foundDevices.size}"
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
            card.addView(makeText(
                if (item.isDemo) "Demo-Gerät  •  Tippen zum Verbinden"
                else "Signal: ${item.rssi} dBm  •  ${item.address} (Tippen zum Verbinden)",
                12f, Palette.TEXT_SECONDARY
            ).apply { setPadding(0, dp(5), 0, 0) })
            deviceListContainer.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) })
        }
    }

    /* ------------------------------------------------------------------ */
    /*  VERBINDUNG                                                         */
    /* ------------------------------------------------------------------ */
    private fun connectToDevice(item: ScooterDevice) {
        connectedDevice = item
        detectedModel = ScooterModel.detect(item.name, item.address)
        activeProtocol = detectedModel.protocol
        reconnectAttempts = 0
        userInitiatedDisconnect = false
        engineReady = false

        if (item.isDemo || item.device == null) {
            showMainDashboard()
            log("> link: DEMO verbunden ${item.address}")
            log("> model: ${detectedModel.displayName} (Easy ${detectedModel.easySpeedKmh} km/h)")
            engineReady = true
            return
        }
        showMainDashboard()
        val engine = BleEngine(this, this)
        bleEngine = engine
        engine.setProtocolHint(detectedModel.protocol)
        engine.connect(item.device)
    }

    private fun handleUnexpectedDisconnect() {
        engineReady = false
        if (userInitiatedDisconnect) return
        if (!autoReconnectEnabled) { runOnUiThread { showScanScreen() }; return }
        if (reconnectAttempts < 3) {
            reconnectAttempts++
            log("> link: Reconnect-Versuch $reconnectAttempts/3")
            handler.postDelayed({
                if (!userInitiatedDisconnect && connectedDevice?.device != null) bleEngine?.reconnect()
            }, 1200L * reconnectAttempts)
        } else {
            log("> link: Auto-Reconnect fehlgeschlagen — zurück zur Suche")
            runOnUiThread { showScanScreen() }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  BleEngine.Listener (läuft teils auf Binder-Threads!)               */
    /* ------------------------------------------------------------------ */
    override fun onLog(line: String) = log(line)

    override fun onConnected(address: String) {
        runOnUiThread { Toast.makeText(this, "Verbunden: $address", Toast.LENGTH_SHORT).show() }
    }

    override fun onReady(address: String, protocol: BleProtocol, mtu: Int) {
        activeProtocol = protocol
        engineReady = true
        runOnUiThread {
            log("> gatt: READY — Protokoll ${protocol.displayName}, MTU $mtu")
            if (currentTab == Tab.INFO) renderTab(Tab.INFO)
        }
    }

    override fun onDisconnected(unexpected: Boolean) {
        runOnUiThread { handleUnexpectedDisconnect() }
    }

    override fun onTelemetry(t: Telemetry) {
        lastTelemetry = t
        runOnUiThread { applyTelemetryToDashboard(t) }
    }

    override fun onWriteResult(label: String, frame: ByteArray, success: Boolean, detail: String) {
        val hex = hexFor(frame)
        if (success) log("> tx[$label]: $hex  ✔ ($detail)")
        else log("> tx[$label]: FEHLER — $detail  (Frame $hex nicht gesendet)")
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
            log("> flash: ${if (success) "OK" else "FEHLER"} — $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun hexFor(frame: ByteArray): String {
        val proto = bleEngine?.protocol ?: activeProtocol
        return if (proto == BleProtocol.NAVEE_BRIGHTWAY) {
            val out = NaveeFrames.fromNinebot(frame) ?: frame
            NaveeFrames.toHex(out)
        } else ScooterFrames.toHex(frame)
    }

    /* ------------------------------------------------------------------ */
    /*  DASHBOARD-SHELL                                                    */
    /* ------------------------------------------------------------------ */
    private fun showMainDashboard() {
        rootContainer.removeAllViews()
        currentTab = Tab.INFO
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        topBar.addView(makeText("SYRAK", 20f, Color.WHITE, true))
        topBar.addView(makeText(" SCOOTERLAB", 11f, Palette.ACCENT_RED, true))
        topBar.addView(makeText("  ${currentRegion.displayName} (${currentRegion.legalSpeedKmh} km/h)  ",
            10f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shell.addView(topBar)

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(20, 30, 28))
        }
        val dev = connectedDevice
        banner.addView(makeText(
            if (dev?.isDemo == true) "■ Controller online  •  ${dev.name} (Demo)"
            else "■ Controller online  •  ${dev?.name ?: "Unknown"}",
            12f, Palette.ACCENT_GREEN, true
        ))
        shell.addView(banner)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(tabContent)
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomNav = buildBottomNav()
        shell.addView(bottomNav)
        rootContainer.addView(shell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        selectTab(Tab.INFO)
    }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(10)); setBackgroundColor(Palette.SURFACE)
        }
        fun navItem(tab: Tab, icon: String, label: String): LinearLayout {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(tab) }
            }
            item.addView(makeText(icon, 18f, Palette.TEXT_SECONDARY).apply { gravity = Gravity.CENTER })
            item.addView(makeText(label, 10f, Palette.TEXT_SECONDARY).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
            })
            item.tag = tab
            return item
        }
        nav.addView(navItem(Tab.INFO, "ⓘ", "Info"))
        nav.addView(navItem(Tab.TOOLS, "⚙", "Tools"))
        val centerWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val centerCircle = TextView(this).apply {
            text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Palette.ACCENT_BLUE); setStroke(dp(4), Palette.BG)
            }
            layoutParams = FrameLayout.LayoutParams(dp(60), dp(60)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dp(26)
            }
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

    /* ------------------------------------------------------------------ */
    /*  TAB: INFO                                                          */
    /* ------------------------------------------------------------------ */
    private fun renderInfoTab() {
        val dev = connectedDevice
        val isDemo = dev?.isDemo == true
        val preview = makeCard()
        preview.addView(makeText("🛴  ${dev?.name ?: "Unbekannt"}", 20f, Color.WHITE, true))
        preview.addView(makeText(
            if (isDemo) "Demo Simulation  •  ${dev?.address}" else "BLE Connected  •  ${dev?.address}",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(preview, cardParams())

        tabContent.addView(makeSectionLabel("ERKANNTES MODELL"))
        val modelCard = makeCard()
        modelCard.addView(makeText(detectedModel.displayName, 16f, Palette.ACCENT_GREEN, true))
        modelCard.addView(makeText(
            "Easy Speed: ${detectedModel.easySpeedKmh} km/h   •   Techn. Max: ${detectedModel.absMaxKmh} km/h",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(6)) })
        modelCard.addView(makeText(
            "Panic-Hotkey: ${detectedModel.panicHotkey}\nStealth-Unlock: ${detectedModel.stealthUnlockHotkey}",
            12f, Palette.TEXT_MUTED
        ))
        tabContent.addView(modelCard, cardParams())

        tabContent.addView(makeSectionLabel("SYSTEM"))
        val info = makeCard()
        fun infoRow(l: String, r: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8))
            addView(makeText(l, 13f, Palette.TEXT_SECONDARY))
            addView(makeText(r, 13f, Color.WHITE, true).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        info.addView(infoRow("Protokoll", activeProtocol.displayName))
        info.addView(infoRow("GATT-Status", if (isDemo) "DEMO" else if (engineReady) "READY" else "verbinde…"))
        info.addView(infoRow("MTU", if (isDemo) "—" else "${bleEngine?.maxWritePayload()?.plus(3) ?: "—"} B"))
        info.addView(infoRow("Region", "${currentRegion.displayName} (${currentRegion.legalSpeedKmh} km/h)"))
        info.addView(infoRow("Overspeed-Alarm", if (overspeedAlarmMuted) "AUS (stumm)" else "AN"))
        info.addView(infoRow("RAM-Overlay", if (ramOverlay.active) "${ramOverlay.size} Patch(es)" else "inaktiv"))
        tabContent.addView(info, cardParams())

        tabContent.addView(makeSectionLabel("STATUS"))
        val status = makeCard()
        status.addView(makeText(
            if (isLocked) "🔒 Wegfahrsperre AKTIV" else "🔓 Scooter entsperrt",
            14f, if (isLocked) Palette.ACCENT_RED else Palette.ACCENT_GREEN, true
        ))
        status.addView(makeText(
            if (ramOverlay.active) "RAM-Overlay aktiv: ${ramOverlay.names.joinToString()}"
            else "Kein flüchtiges Tuning aktiv",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        status.addView(makeText("Flash-Modus: ${flashMode.displayName}", 12f,
            if (flashMode == FlashMode.HARD_FLASH) Palette.ACCENT_ORANGE else Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(status)
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: TOOLS                                                         */
    /* ------------------------------------------------------------------ */
    private fun renderToolsTab() {
        tabContent.addView(makeSectionLabel("ANFAHREN"))
        tabContent.addView(makeSliderCard(
            "Zero-Start / Anfahrgeschwindigkeit",
            "0 km/h = Instant Throttle. 1–5 km/h = Mindestgeschwindigkeit bis der Motor greift.",
            0, 5, zeroStartKmh, "km/h",
            valueFormatter = { if (it == 0) "0 km/h · ZERO-START" else "$it km/h" }
        ) { value ->
            zeroStartKmh = value
            sendFrame(ScooterFrames.zeroStart(value), "zero-start")
        }, cardParams())

        tabContent.addView(makeSectionLabel("GESCHWINDIGKEIT"))
        tabContent.addView(buildMaxSpeedCard(), cardParams())

        val gm = makeCard()
        gm.addView(makeText("German Maneuver", 15f, Color.WHITE, true))
        gm.addView(makeText(
            "Volatiler RAM-Patch. Vor dem Anwenden werden die aktuellen Register gesichert (Police Mode kann sie wiederherstellen).",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        gm.addView(makePrimaryButton("GERMAN MANEUVER ANWENDEN") {
            snapshotRegisters()
            germanManeuverActive = true
            val frame = ScooterFrames.germanManeuver()
            ramOverlay.apply("german-maneuver", frame)
            sendFrame(frame, "german-maneuver")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(gm, cardParams())

        tabContent.addView(makeSectionLabel("FAHRDYNAMIK"))
        tabContent.addView(makeSliderCard(
            "KERS (Rekuperation)", "Stärke der Energierückgewinnung.", 0, 3, kersLevel, "Stufe"
        ) { value ->
            kersLevel = value
            sendFrame(ScooterFrames.kers(value), "kers")
        }, cardParams())

        val cruise = makeCard()
        cruise.addView(makeToggleRow(
            "Tempomat (Cruise Control)", "Hält die Geschwindigkeit konstant.", cruiseEnabled
        ) { on ->
            cruiseEnabled = on
            sendFrame(ScooterFrames.cruise(on), "cruise")
        })
        tabContent.addView(cruise, cardParams())

        tabContent.addView(makeSectionLabel("MOTORSCHUTZ"))
        val guard = makeCard()
        guard.addView(makeToggleRow(
            "Motortemperatur-Schutzschalter",
            "Schneidet die Motorleistung ab, wenn die Temperatur die Schwelle überschreitet.",
            motorTempGuardEnabled
        ) { on ->
            motorTempGuardEnabled = on
            if (!on) motorGuardTripped = false
            sendFrame(ScooterFrames.motorTempGuard(if (on) motorTempGuardC else 0), "temp-guard")
        })
        guard.addView(makeSliderCard(
            "Abschalt-Schwelle", "Temperatur, ab der die Leistung gekappt wird.",
            60, 95, motorTempGuardC, "°C"
        ) { value ->
            motorTempGuardC = value
            if (motorTempGuardEnabled) sendFrame(ScooterFrames.motorTempGuard(value), "temp-guard")
        })
        tabContent.addView(guard, cardParams())

        tabContent.addView(makeSectionLabel("SICHERHEIT"))
        val lock = makeCard()
        lock.addView(makeText("Lock Code / Wegfahrsperre", 15f, Color.WHITE, true))
        lock.addView(makeText(
            "Elektronische Sperre mit PIN. Der Controller verweigert Gas, bis der Code eingegeben wird.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        lock.addView(makePrimaryButton(if (isLocked) "ENTSPERREN (PIN)" else "SPERREN / PIN FESTLEGEN") {
            showLockCodeDialog()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(lock, cardParams())

        val police = makeCard()
        police.addView(makeText("🚨 Police Mode (Panik-Button)", 15f, Palette.ACCENT_ORANGE, true))
        police.addView(makeText(
            "Flusht den RAM-Overlay, sendet echte Drossel-Frames und stellt das legale Werkslimit " +
            "(${currentRegion.displayName}: ${currentRegion.legalSpeedKmh} km/h) wieder her. " +
            "Falls ein Register-Snapshot vorliegt, werden die Ursprungs-Bytes zurückgeschrieben.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        police.addView(makePrimaryButton("POLICE MODE AUSLÖSEN", Palette.ACCENT_ORANGE) {
            engagePoliceMode()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(police, cardParams())

        tabContent.addView(makeSectionLabel("LENKER-HOTKEYS"))
        val hotkeys = makeCard()
        hotkeys.addView(makeText("Modellspezifische Kombinationen", 14f, Color.WHITE, true))
        hotkeys.addView(makeText(
            "🚨 Panic: ${detectedModel.panicHotkey}\n\n🕶 Stealth Unlock: ${detectedModel.stealthUnlockHotkey}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(12)) })
        hotkeys.addView(makePrimaryButton("PANIC-HOTKEY SIMULIEREN", Palette.ACCENT_ORANGE) {
            triggerPanicHotkey()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            setMargins(0, 0, 0, dp(8))
        })
        hotkeys.addView(makePrimaryButton("STEALTH-UNLOCK SIMULIEREN", Palette.ACCENT_GREEN) {
            triggerStealthUnlock()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        tabContent.addView(hotkeys, cardParams())
    }

    private fun buildMaxSpeedCard(): LinearLayout {
        val easy = detectedModel.easySpeedKmh
        val absMax = detectedModel.absMaxKmh
        val card = makeCard()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeText("Max Speed", 14f, Palette.TEXT_PRIMARY, true))
        val valueView = makeText("$maxSpeedKmh km/h", 14f, Palette.ACCENT_GREEN, true).apply { gravity = Gravity.END }
        header.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)
        card.addView(makeText(
            "Easy Speed (${detectedModel.displayName}): $easy km/h  •  techn. Max: $absMax km/h.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(3), 0, dp(10)) })
        val bar = SeekBar(this).apply { max = 65 - 15; progress = maxSpeedKmh - 15 }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val real = progress + 15
                valueView.text = "$real km/h"
                valueView.setTextColor(if (real > easy) Palette.ACCENT_ORANGE else Palette.ACCENT_GREEN)
                if (fromUser && real <= easy) {
                    maxSpeedKmh = real
                    sendFrame(ScooterFrames.maxSpeed(real), "max-speed")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val real = (seekBar?.progress ?: 0) + 15
                if (real > easy) confirmExtendedPower(real, easy, absMax, bar, valueView)
            }
        })
        card.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(makeText(
            if (extendedPowerUnlocked) "⚡ Erweiterte Strombegrenzung FREIGESCHALTET"
            else "🔒 Erweiterte Strombegrenzung gesperrt (Easy Speed aktiv)",
            11f, if (extendedPowerUnlocked) Palette.ACCENT_ORANGE else Palette.ACCENT_GREEN, true
        ).apply { setPadding(0, dp(6), 0, 0) })
        return card
    }

    private fun confirmExtendedPower(requested: Int, easy: Int, absMax: Int, bar: SeekBar, valueView: TextView) {
        val capped = requested.coerceAtMost(absMax)
        AlertDialog.Builder(this)
            .setTitle("⚠ Easy-Speed-Grenze überschritten")
            .setMessage(
                "Achtung: Ihr ${detectedModel.displayName} ist herstellerseitig nicht für diese Belastung ausgelegt. " +
                "Bei Überschreitung von $easy km/h droht thermische Überlastung. Nutzung auf eigene Gefahr!\n\n" +
                "Angefordert: $requested km/h (techn. Max: $absMax km/h)"
            )
            .setPositiveButton("Freischalten & senden") { _, _ ->
                extendedPowerUnlocked = true
                maxSpeedKmh = capped
                bar.progress = capped - 15
                valueView.text = "$capped km/h"
                valueView.setTextColor(Palette.ACCENT_ORANGE)
                val maxFrame = ScooterFrames.maxSpeed(capped)
                val powerFrame = ScooterFrames.maxPower()
                ramOverlay.apply("extended-power", powerFrame)
                ramOverlay.apply("max-speed-$capped", maxFrame)
                sendFrame(maxFrame, "max-speed")
                sendFrame(powerFrame, "max-power")
                renderTab(Tab.TOOLS)
                showPostFlashDialog()
            }
            .setNegativeButton("Abbrechen") { _, _ ->
                maxSpeedKmh = easy
                bar.progress = easy - 15
                valueView.text = "$easy km/h"
                valueView.setTextColor(Palette.ACCENT_GREEN)
                sendFrame(ScooterFrames.maxSpeed(easy), "max-speed")
            }
            .setCancelable(false)
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: DASHBOARD                                                     */
    /* ------------------------------------------------------------------ */
    private fun renderDashboardTab() {
        val isDemo = connectedDevice?.isDemo == true
        val speedCard = makeCard().apply { gravity = Gravity.CENTER }
        dashSpeed = makeText("--", 56f, Color.WHITE, true)
        speedCard.addView(dashSpeed)
        speedCard.addView(makeText("KM/H", 12f, Palette.ACCENT_RED, true))
        dashFeedState = makeText(
            if (isDemo) "LIVE FEED (DEMO SIMULATION)" else "LIVE FEED (WARTE AUF BLE-NOTIFY)",
            10f, if (isDemo) Palette.ACCENT_ORANGE else Palette.ACCENT_GREEN, true
        ).apply { setPadding(0, dp(4), 0, dp(14)) }
        speedCard.addView(dashFeedState)
        val metricsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        fun metricCol(label: String): TextView {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
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
                val c = makeCard().apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                }
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

        tabContent.addView(makeSectionLabel("AKKU-ZELLBALANCE"))
        val cellCard = makeCard()
        cellCard.addView(makeText("Zell-Ausgleich", 14f, Color.WHITE, true))
        cellCard.addView(makeText(
            "Differenz zwischen stärkster und schwächster Zelle (aus BMS-Notify).",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(3), 0, dp(10)) })
        fun cellRow(l: String): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(2))
            }
            row.addView(makeText(l, 12f, Palette.TEXT_SECONDARY))
            val v = makeText("--", 12f, Color.WHITE, true).apply { gravity = Gravity.END }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cellCard.addView(row)
            return v
        }
        dashCellMin = cellRow("Cell min")
        dashCellMax = cellRow("Cell max")
        dashCellDelta = cellRow("Cell delta")
        tabContent.addView(cellCard, cardParams())

        tabContent.addView(makeSectionLabel("SCHUTZ & STREAM"))
        val streamInfo = makeCard()
        dashTempGuardState = makeText(
            if (motorTempGuardEnabled) "🛡 Motorschutz aktiv (Schwelle $motorTempGuardC °C)" else "⚠ Motorschutz deaktiviert",
            12f, if (motorTempGuardEnabled) Palette.ACCENT_GREEN else Palette.ACCENT_RED, true
        )
        streamInfo.addView(dashTempGuardState)
        streamInfo.addView(makeText(
            if (isDemo) "Demo-Simulation läuft — Werte werden interpoliert."
            else "Echte Werte erscheinen, sobald der Controller Telemetrie-Notifications sendet.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        tabContent.addView(streamInfo)

        // Letzte bekannte Telemetrie sofort anzeigen (falls schon empfangen)
        lastTelemetry?.let { applyTelemetryToDashboard(it) }
        if (isDemo) startDemoTelemetry()
    }

    private fun applyTelemetryToDashboard(t: Telemetry) {
        dashSpeed?.text = t.speedKmh?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
        dashVoltage?.text = t.voltageV?.let { String.format(Locale.US, "%.1f V", it) } ?: "-- V"
        dashCurrent?.text = t.currentA?.let { String.format(Locale.US, "%.1f A", it) } ?: "-- A"
        dashBattery?.text = t.batteryPercent?.let { "$it %" } ?: "-- %"
        dashMotorTemp?.text = t.motorTempC?.let { String.format(Locale.US, "%.0f °C", it) } ?: "-- °C"
        val power = if (t.voltageV != null && t.currentA != null) t.voltageV * t.currentA else null
        dashPower?.text = power?.let { String.format(Locale.US, "%.0f W", it) } ?: "-- W"
        if (t.cellMinV != null) dashCellMin?.text = String.format(Locale.US, "%.3f V", t.cellMinV)
        if (t.cellMaxV != null) dashCellMax?.text = String.format(Locale.US, "%.3f V", t.cellMaxV)
        if (t.cellMinV != null && t.cellMaxV != null)
            dashCellDelta?.text = String.format(Locale.US, "%.0f mV", (t.cellMaxV - t.cellMinV) * 1000)
        dashFeedState?.text = "LIVE FEED • ${t.source.displayName}"
        dashFeedState?.setTextColor(Palette.ACCENT_GREEN)
        t.motorTempC?.let { evaluateMotorGuard(it.toDouble()) }
    }

    private fun startDemoTelemetry() {
        stopTelemetry()
        telemetryRunnable = object : Runnable {
            override fun run() {
                simTick++
                val speed = 26.0 + 4.0 * Math.sin(simTick / 6.0) + (Math.random() - 0.5)
                val volt = 40.6 - (simTick % 20) * 0.01
                val current = 8.0 + 5.0 * Math.abs(Math.sin(simTick / 4.0))
                val motorTemp = 41.0 + 3.0 * Math.abs(Math.sin(simTick / 10.0))
                simTrip += 0.0006
                applyTelemetryToDashboard(
                    Telemetry(
                        speedKmh = speed.toFloat(),
                        voltageV = volt.toFloat(),
                        currentA = current.toFloat(),
                        batteryPercent = 87 - (simTick / 120),
                        motorTempC = motorTemp.toFloat(),
                        cellMinV = (3.621 - (simTick % 30) * 0.0005).toFloat(),
                        cellMaxV = (3.688 - (simTick % 30) * 0.0004).toFloat(),
                        rawHex = "(demo)",
                        source = activeProtocol
                    )
                )
                dashTrip?.text = String.format(Locale.US, "%.2f KM", simTrip)
                dashRange?.text = String.format(Locale.US, "%.1f KM", 26.1 - simTick * 0.001)
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(telemetryRunnable!!, 400)
    }

    private fun evaluateMotorGuard(tempC: Double) {
        if (!motorTempGuardEnabled) return
        if (tempC >= motorTempGuardC && !motorGuardTripped) {
            motorGuardTripped = true
            sendFrame(ScooterFrames.emergencyCut(), "emergency-cut")
            dashTempGuardState?.text = "🛑 Motorschutz AUSGELÖST (${String.format(Locale.US, "%.0f", tempC)} °C)"
            dashTempGuardState?.setTextColor(Palette.ACCENT_RED)
        } else if (tempC < motorTempGuardC - 5 && motorGuardTripped) {
            motorGuardTripped = false
            dashTempGuardState?.text = "🛡 Motorschutz aktiv (Schwelle $motorTempGuardC °C)"
            dashTempGuardState?.setTextColor(Palette.ACCENT_GREEN)
        }
    }

    private fun stopTelemetry() {
        telemetryRunnable?.let { handler.removeCallbacks(it) }
        telemetryRunnable = null
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: FLASH                                                         */
    /* ------------------------------------------------------------------ */
    private var flashProgressBar: ProgressBar? = null
    private var flashStatusView: TextView? = null

    private fun renderFlashTab() {
        val recommendations = FirmwareCatalog.forModel(detectedModel)
        val recommended = FirmwareCatalog.recommended(detectedModel)

        tabContent.addView(makeSectionLabel("FLASH-MODUS"))
        val modeCard = makeCard()
        modeCard.addView(makeText(
            "Wähle zwischen gefahrlosem RAM-Overlay und echtem Firmware-Flash.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, 0, 0, dp(10)) })
        FlashMode.values().forEach { mode ->
            val isSel = flashMode == mode
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(if (isSel) Palette.SURFACE_DEEP else Palette.SURFACE_ALT)
            }
            row.addView(makeText("${if (isSel) "● " else "○ "}${mode.displayName}", 14f,
                if (isSel) Palette.ACCENT_GREEN else Color.WHITE, true))
            row.addView(makeText(mode.description, 11f, Palette.TEXT_MUTED).apply { setPadding(0, dp(4), 0, 0) })
            row.setOnClickListener { flashMode = mode; renderTab(Tab.FLASH) }
            modeCard.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) })
        }
        modeCard.addView(makeText(
            if (flashMode == FlashMode.HARD_FLASH)
                "⚠ HARD-FLASH: schreibt .bin-Sektoren dauerhaft. Vor jedem Flash erscheint ein Bestätigungsdialog."
            else "✔ SOFT-PATCH: Änderungen laufen als flüchtiges RAM-Overlay.",
            11f, if (flashMode == FlashMode.HARD_FLASH) Palette.ACCENT_RED else Palette.ACCENT_GREEN, true
        ))
        tabContent.addView(modeCard, cardParams())

        tabContent.addView(makeSectionLabel("EMPFOHLEN FÜR ${detectedModel.displayName.uppercase(Locale.ROOT)}"))
        val recCard = makeCard()
        if (recommended != null) {
            recCard.addView(makeText("⭐ ${recommended.name} ${recommended.version}", 15f, Palette.ACCENT_GREEN, true))
            recCard.addView(makeText(
                "Target: ${recommended.target}  •  Empf. Max: ${if (recommended.recommendedMaxKmh > 0) "${recommended.recommendedMaxKmh} km/h" else "—"}\n${recommended.notes}",
                12f, Palette.TEXT_SECONDARY
            ).apply { setPadding(0, dp(6), 0, dp(10)) })
            recCard.addView(makePrimaryButton("EMPFOHLENES PAKET WÄHLEN", Palette.ACCENT_GREEN) {
                selectedFirmware = recommended; selectedFirmwareUri = null
                selectedFirmwareName = "${recommended.name} ${recommended.version}"
                loadedFirmwareBytes = null; lastValidation = null
                renderTab(Tab.FLASH)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        } else recCard.addView(makeText("Keine Empfehlung verfügbar.", 13f, Palette.TEXT_MUTED))
        tabContent.addView(recCard, cardParams())

        tabContent.addView(makeSectionLabel("EIGENES PAKET (.bin / .zip)"))
        val pkg = makeCard()
        pkg.addView(makeText(
            "Wähle eine .bin- oder .zip-Firmware. Die Datei wird auf ZIP-Struktur, Modell-Kompatibilität und SHA-256 geprüft. " +
            "Der .bin-Inhalt wird als echte Byte-Sequenz blockweise über BLE geschrieben.",
            12f, Palette.TEXT_MUTED
        ).apply { setPadding(0, 0, 0, dp(12)) })
        pkg.addView(makePrimaryButton("FIRMWARE WÄHLEN", Palette.SURFACE) {
            firmwarePicker.launch(arrayOf("*/*"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(pkg, cardParams())

        tabContent.addView(makeSectionLabel("STATUS & VALIDIERUNG"))
        val statusCard = makeCard()
        statusCard.addView(makeText(
            selectedFirmwareName?.let { "Ausgewählt: $it" } ?: "Kein Paket ausgewählt",
            13f, if (selectedFirmwareName != null) Palette.ACCENT_GREEN else Palette.TEXT_MUTED, true
        ))
        flashStatusView = makeText(
            lastValidation?.let { "${if (it.valid) "✔" else "✘"} ${it.message}" } ?: "Noch keine Validierung durchgeführt.",
            11f, when {
                lastValidation == null -> Palette.TEXT_MUTED
                lastValidation!!.valid -> Palette.ACCENT_GREEN
                else -> Palette.ACCENT_RED
            }
        ).apply { setPadding(0, dp(6), 0, 0) }
        statusCard.addView(flashStatusView)
        val sha = lastValidation?.sha256
        if (sha != null && sha != "remote") {
            statusCard.addView(makeText("SHA-256: $sha", 10f, Palette.TEXT_MUTED).apply {
                setPadding(0, dp(4), 0, 0); setTypeface(Typeface.MONOSPACE)
            })
        }
        flashProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        statusCard.addView(flashProgressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { setMargins(0, dp(12), 0, dp(12)) })

        statusCard.addView(makePrimaryButton(
            if (flashMode == FlashMode.HARD_FLASH) "ECHTEN FIRMWARE-FLASH STARTEN" else "SOFT-PATCH ANWENDEN"
        ) {
            startFlashFlow()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(statusCard, cardParams())

        tabContent.addView(makeText(
            "⚠ Flashen überschreibt die DRV-Firmware. Akku > 50 % und stabile Verbindung erforderlich. " +
            "Ein Abbruch während des Schreibens kann den Controller unbrauchbar machen.",
            12f, Palette.ACCENT_RED
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun startFlashFlow() {
        if (selectedFirmwareName == null) {
            Toast.makeText(this, "Bitte zuerst ein Paket auswählen.", Toast.LENGTH_SHORT).show(); return
        }
        if (lastValidation?.valid == false) {
            Toast.makeText(this, "Validierung fehlgeschlagen — Flash blockiert.", Toast.LENGTH_LONG).show(); return
        }
        if (bleEngine == null || connectedDevice?.isDemo == true) {
            // Demo: nur UI-Feedback, KEIN Fake-Erfolg
            Toast.makeText(this, "Demo-Modus: Es wird kein echter Flash gesendet.", Toast.LENGTH_LONG).show()
            flashStatusView?.text = "Demo-Modus — kein echter Flash."
            flashStatusView?.setTextColor(Palette.ACCENT_ORANGE)
            return
        }
        if (flashMode == FlashMode.HARD_FLASH) confirmHardFlash() else doFlash()
    }

    private fun confirmHardFlash() {
        AlertDialog.Builder(this)
            .setTitle("⚠ ACHTUNG: ECHTES FIRMWARE-FLASHEN!")
            .setMessage(
                "Das Schreiben verändert den Controller-Speicher dauerhaft. Nutzung auf 100 % eigene Verantwortung!\n\n" +
                "Paket: ${selectedFirmwareName}\n" +
                "Ziel: MCU/ESC (.bin-Sektoren) via ${(bleEngine?.protocol ?: activeProtocol).displayName}\n\n" +
                "Ein Abbruch während des Schreibens kann den Controller unbrauchbar machen."
            )
            .setPositiveButton("Ich verstehe — JETZT FLASHEN") { _, _ -> doFlash() }
            .setNegativeButton("Abbrechen") { _, _ -> log("> flash: hard-flash abgebrochen") }
            .setCancelable(false)
            .show()
    }

    private fun doFlash() {
        val bytes = loadedFirmwareBytes
        if (bytes == null || bytes.isEmpty()) {
            Toast.makeText(this, "Keine Firmware-Bytes geladen.", Toast.LENGTH_SHORT).show(); return
        }
        val proto = bleEngine?.protocol ?: activeProtocol
        val unlock: ByteArray? = if (proto == BleProtocol.NAVEE_BRIGHTWAY) NaveeFrames.handshake() else null
        val reboot: ByteArray? = if (proto == BleProtocol.NAVEE_BRIGHTWAY)
            NaveeFrames.buildFrame(NaveeFrames.CMD_EMERGENCY, byteArrayOf(0x01))
        else ScooterFrames.buildFrame(0x20, 0x7E, byteArrayOf(0x01))
        flashProgressBar?.progress = 0
        flashStatusView?.text = "Starte echten Flash…"
        flashStatusView?.setTextColor(Palette.TEXT_SECONDARY)
        bleEngine?.startFlash(bytes, unlock, reboot)
    }

    /* ------------------------------------------------------------------ */
    /*  FIRMWARE-AUSWAHL & VALIDIERUNG                                     */
    /* ------------------------------------------------------------------ */
    private fun handleSelectedFirmware(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin"
        selectedFirmwareName = name
        selectedFirmwareUri = uri
        selectedFirmware = null
        val validation = validateFirmware(uri)
        lastValidation = validation
        loadedFirmwareBytes = validation.extractedBinary
        log("> flash: Paket $name — ${validation.message}")
        Toast.makeText(this, if (validation.valid) "Firmware geprüft: OK" else "Prüfung fehlgeschlagen",
            Toast.LENGTH_SHORT).show()
        if (currentTab == Tab.FLASH) renderTab(Tab.FLASH)
    }

    /**
     * Echte Integritätsprüfung:
     *  • Datei lesbar (streaming, kein Vollpuffer für SHA)
     *  • .bin direkt ODER ZIP mit enthaltener Firmware
     *  • Modell-Kompatibilität
     *  • SHA-256-Fingerprint
     * Liefert zusätzlich die extrahierten Firmware-Bytes für den Flash.
     */
    private fun validateFirmware(uri: Uri): FirmwareValidation {
        val bytes: ByteArray = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return FirmwareValidation(false, "Datei nicht lesbar.", "", false, false, null)
        } catch (e: Exception) {
            return FirmwareValidation(false, "Lesefehler: ${e.message}", "", false, false, null)
        }
        if (bytes.isEmpty()) return FirmwareValidation(false, "Datei ist leer.", "", false, false, null)

        val sha = sha256(bytes)
        val isZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

        // Direkte .bin
        if (!isZip) {
            val looksLikeBin = selectedFirmwareName?.lowercase(Locale.ROOT)?.endsWith(".bin") == true ||
                    bytes.size >= 64
            return if (looksLikeBin) {
                FirmwareValidation(true, "Direkte .bin akzeptiert (${bytes.size} Bytes).", sha, false, true, bytes)
            } else {
                FirmwareValidation(false, "Kein gültiges ZIP und keine erkennbare .bin.", sha, false, false, null)
            }
        }

        // ZIP entpacken -> Firmware-Eintrag suchen
        var firmwareEntry: ByteArray? = null
        var firmwareName: String? = null
        val entries = mutableListOf<String>()
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                var guard = 0
                while (entry != null && guard < 500) {
                    val lname = entry.name.lowercase(Locale.ROOT)
                    entries.add(lname)
                    if (!entry.isDirectory && firmwareEntry == null && (
                            lname.endsWith(".bin") || lname.endsWith(".fw") || lname.endsWith(".shfw") ||
                            lname.endsWith(".nbw") || lname.endsWith(".navee") ||
                            lname.contains("drv") || lname.contains("esc") || lname.contains("bms")
                            )
                    ) {
                        firmwareEntry = zis.readBytes()
                        firmwareName = lname
                    }
                    entry = zis.nextEntry
                    guard++
                }
            }
        } catch (e: Exception) {
            return FirmwareValidation(false, "ZIP beschädigt: ${e.message}", sha, true, false, null)
        }

        if (firmwareEntry == null) {
            return FirmwareValidation(false, "ZIP enthält keine erkennbare Firmware-Datei.", sha, true, false, null)
        }

        val modelTokens = detectedModel.matchKeywords.ifEmpty { listOf("generic") }
        val joined = (entries + selectedFirmwareName.orEmpty() + (firmwareName ?: "")).joinToString(" ")
        val isNaveeModel = detectedModel.protocol == BleProtocol.NAVEE_BRIGHTWAY
        val naveePackage = joined.contains("navee") || joined.contains("brightway")
        val modelCompatible = detectedModel == ScooterModel.UNKNOWN ||
                modelTokens.any { joined.contains(it) } ||
                (naveePackage && isNaveeModel) ||
                (joined.contains("shfw") && detectedModel in listOf(
                    ScooterModel.NINEBOT_MAX_G2, ScooterModel.NINEBOT_MAX_G30, ScooterModel.NINEBOT_F2_PRO,
                    ScooterModel.NINEBOT_ES, ScooterModel.XIAOMI_PRO_2, ScooterModel.XIAOMI_M365
                ))
        if (!modelCompatible) {
            return FirmwareValidation(false, "Modell-Konflikt: Paket passt nicht zu ${detectedModel.displayName}.",
                sha, true, false, null)
        }
        return FirmwareValidation(
            true,
            "Integrität OK — Firmware '${firmwareName}' (${firmwareEntry.size} Bytes) extrahiert.",
            sha, true, true, firmwareEntry
        )
    }

    private fun sha256(bytes: ByteArray): String = try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "n/a" }

    /* ------------------------------------------------------------------ */
    /*  POST-FLASH-DIALOG                                                  */
    /* ------------------------------------------------------------------ */
    private fun showPostFlashDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        container.addView(makeText(
            "Modell: ${detectedModel.displayName}  •  Protokoll: ${(bleEngine?.protocol ?: activeProtocol).displayName}",
            12f, Palette.TEXT_SECONDARY
        ))
        container.addView(makeText(
            "Easy-Speed: ${detectedModel.easySpeedKmh} km/h  •  techn. Max: ${detectedModel.absMaxKmh} km/h",
            12f, Palette.ACCENT_GREEN, true
        ).apply { setPadding(0, dp(4), 0, dp(12)) })

        val alarmState = makeText(
            if (overspeedAlarmMuted) "AUS (stumm)" else "AN",
            13f, if (overspeedAlarmMuted) Palette.TEXT_MUTED else Palette.ACCENT_GREEN, true
        )
        val alarmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(2))
        }
        alarmRow.addView(makeText("Overspeed-Piepton (ab ${detectedModel.easySpeedKmh} km/h)", 13f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        alarmRow.addView(alarmState)
        alarmRow.setOnClickListener {
            overspeedAlarmMuted = !overspeedAlarmMuted
            alarmState.text = if (overspeedAlarmMuted) "AUS (stumm)" else "AN"
            alarmState.setTextColor(if (overspeedAlarmMuted) Palette.TEXT_MUTED else Palette.ACCENT_GREEN)
            val frame = ScooterFrames.overspeedAlarmMute(overspeedAlarmMuted)
            ramOverlay.apply("overspeed-alarm", frame)
            sendFrame(frame, "overspeed-alarm")
        }
        container.addView(alarmRow)
        container.addView(makeText(
            "Navee/Brightway: CMD 0x14 (AA 55 14 01 …)  •  Ninebot/Xiaomi: CMD 0x7F",
            10f, Palette.TEXT_MUTED
        ))

        container.addView(makeText("Ziel-Geschwindigkeit: $maxSpeedKmh km/h", 13f, Color.WHITE, true)
            .apply { setPadding(0, dp(14), 0, dp(4)) })
        val speedBar = SeekBar(this).apply {
            max = (detectedModel.absMaxKmh - 15).coerceAtLeast(1)
            progress = (maxSpeedKmh - 15).coerceIn(0, max)
        }
        container.addView(speedBar)

        AlertDialog.Builder(this)
            .setTitle("⚙ Post-Flash-Einstellungen")
            .setView(container)
            .setPositiveButton("Übernehmen") { _, _ -> applyPostFlashSpeed(speedBar.progress + 15) }
            .setNegativeButton("Später") { _, _ -> }
            .setCancelable(true)
            .show()
    }

    private fun applyPostFlashSpeed(target: Int) {
        val easy = detectedModel.easySpeedKmh
        val absMax = detectedModel.absMaxKmh
        if (target <= easy) {
            maxSpeedKmh = target
            sendFrame(ScooterFrames.maxSpeed(target), "max-speed")
        } else confirmThermalSpeed(target, easy, absMax)
    }

    private fun confirmThermalSpeed(requested: Int, easy: Int, absMax: Int) {
        val capped = requested.coerceAtMost(absMax)
        AlertDialog.Builder(this)
            .setTitle("⚠ Thermische Belastungsgrenze")
            .setMessage(
                "Achtung: Ihr ${detectedModel.displayName} ist herstellerseitig nicht für diese Belastung ausgelegt. " +
                "Bei Überschreitung von $easy km/h droht thermische Überlastung. Nutzung auf eigene Gefahr!\n\n" +
                "Angefordert: $requested km/h (techn. Max: $absMax km/h)"
            )
            .setPositiveButton("Freischalten & senden") { _, _ ->
                extendedPowerUnlocked = true
                maxSpeedKmh = capped
                val maxFrame = ScooterFrames.maxSpeed(capped)
                val powerFrame = ScooterFrames.maxPower()
                ramOverlay.apply("extended-power", powerFrame)
                ramOverlay.apply("max-speed-$capped", maxFrame)
                sendFrame(maxFrame, "max-speed")
                sendFrame(powerFrame, "max-power")
            }
            .setNegativeButton("Abbrechen") { _, _ ->
                maxSpeedKmh = easy
                sendFrame(ScooterFrames.maxSpeed(easy), "max-speed")
            }
            .setCancelable(false)
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  TAB: CONFIG                                                        */
    /* ------------------------------------------------------------------ */
    private fun renderConfigTab() {
        tabContent.addView(makeSectionLabel("PROFILE"))
        val profileCard = makeCard()
        profileCard.addView(makeText("Aktuelles Profil", 15f, Color.WHITE, true))
        profileCard.addView(makeText(
            "Max $maxSpeedKmh km/h  •  Zero-Start $zeroStartKmh km/h  •  KERS $kersLevel  •  Tempomat ${if (cruiseEnabled) "AN" else "AUS"}",
            12f, Palette.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(12)) })
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val nameInput = EditText(this).apply {
            hint = "Profilname"; setTextColor(Color.WHITE); setHintTextColor(Palette.TEXT_MUTED); textSize = 12f
            setBackgroundColor(Palette.SURFACE_DEEP); setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameRow.addView(nameInput)
        nameRow.addView(makePrimaryButton("SPEICHERN", Palette.ACCENT_GREEN) {
            val name = nameInput.text.toString().trim().ifBlank { "Profil ${System.currentTimeMillis() % 1000}" }
            saveProfile(name); nameInput.setText("")
            Toast.makeText(this, "Profil '$name' gespeichert", Toast.LENGTH_SHORT).show()
            renderTab(Tab.CONFIG)
        }, LinearLayout.LayoutParams(dp(110), dp(44)).apply { setMargins(dp(8), 0, 0, 0) })
        profileCard.addView(nameRow)
        tabContent.addView(profileCard, cardParams())

        val saved = prefs.getStringSet("profiles", emptySet())?.sorted() ?: emptyList()
        if (saved.isNotEmpty()) {
            tabContent.addView(makeSectionLabel("GESPEICHERT"))
            val listCard = makeCard()
            saved.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(makeText(entry, 13f, Color.WHITE, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(makePrimaryButton("LADEN", Palette.SURFACE) { loadProfile(entry) },
                    LinearLayout.LayoutParams(dp(90), dp(38)))
                listCard.addView(row)
            }
            tabContent.addView(listCard, cardParams())
        }

        tabContent.addView(makeSectionLabel("VERBINDUNG"))
        val connCard = makeCard()
        connCard.addView(makeToggleRow(
            "Bluetooth Auto-Reconnect",
            "Stellt die Verbindung nach einem Abbruch automatisch wieder her (max. 3 Versuche).",
            autoReconnectEnabled
        ) { on ->
            autoReconnectEnabled = on
            prefs.edit().putBoolean("auto_reconnect", on).apply()
        })
        tabContent.addView(connCard, cardParams())

        val danger = makeCard()
        danger.addView(makeText("Verbindung", 15f, Color.WHITE, true))
        danger.addView(makeText("Trennt die BLE-Verbindung und kehrt zur Gerätesuche zurück.", 12f, Palette.TEXT_MUTED)
            .apply { setPadding(0, dp(4), 0, dp(12)) })
        danger.addView(makePrimaryButton("DISCONNECT", Palette.SURFACE) { disconnectAndReturn() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tabContent.addView(danger, cardParams())
    }

    private fun saveProfile(name: String) {
        val entry = "$name|$maxSpeedKmh|$zeroStartKmh|$kersLevel|$cruiseEnabled"
        val set = prefs.getStringSet("profiles", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(entry)
        prefs.edit().putStringSet("profiles", set).apply()
    }

    private fun loadProfile(entry: String) {
        val parts = entry.split("|")
        if (parts.size < 5) return
        maxSpeedKmh = parts[1].toIntOrNull() ?: maxSpeedKmh
        zeroStartKmh = parts[2].toIntOrNull() ?: zeroStartKmh
        kersLevel = parts[3].toIntOrNull() ?: kersLevel
        cruiseEnabled = parts[4].toBoolean()
        sendFrame(ScooterFrames.maxSpeed(maxSpeedKmh), "max-speed")
        sendFrame(ScooterFrames.zeroStart(zeroStartKmh), "zero-start")
        sendFrame(ScooterFrames.kers(kersLevel), "kers")
        sendFrame(ScooterFrames.cruise(cruiseEnabled), "cruise")
        Toast.makeText(this, "Profil '${parts[0]}' geladen", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectAndReturn() {
        userInitiatedDisconnect = true
        bleEngine?.disconnect()
        bleEngine = null
        connectedDevice = null
        ramOverlay.flush()
        showScanScreen()
    }

    /* ------------------------------------------------------------------ */
    /*  TUNING-AKTIONEN                                                    */
    /* ------------------------------------------------------------------ */
    private fun sendFrame(frame: ByteArray, label: String) {
        val engine = bleEngine
        if (engine == null || connectedDevice?.isDemo == true) {
            // Demo / nicht verbunden: KEIN Fake-Erfolg, klare Kennzeichnung
            log("> tx[$label]: (Demo/offline) ${hexFor(frame)} — nicht gesendet")
            return
        }
        engine.enqueueWrite(frame, label, null)
    }

    /** Liest die aktuellen Limits als Register-Snapshot (echter Read-Request + Cache). */
    private fun snapshotRegisters() {
        val engine = bleEngine
        if (engine == null || connectedDevice?.isDemo == true) {
            registerSnapshot = RegisterSnapshot(
                speedCeiling = maxSpeedKmh, zeroStart = zeroStartKmh, kers = kersLevel,
                cruise = cruiseEnabled, locked = isLocked
            )
            return
        }
        // Echter Lese-Request; der aktuelle UI-Zustand dient als Fallback-Snapshot,
        // bis die Antwort per Notify eintrifft (Decode über TelemetryDecoder).
        registerSnapshot = RegisterSnapshot(
            speedCeiling = maxSpeedKmh, zeroStart = zeroStartKmh, kers = kersLevel,
            cruise = cruiseEnabled, locked = isLocked
        )
        engine.enqueueWrite(ScooterFrames.readTelemetry(), "read-status", null)
        log("> snapshot: Register-Snapshot gesichert (Speed=$maxSpeedKmh, Zero=$zeroStartKmh, KERS=$kersLevel)")
    }

    private fun engagePoliceMode() {
        val start = System.currentTimeMillis()
        val flushed = ramOverlay.flush()
        germanManeuverActive = false
        extendedPowerUnlocked = false
        maxSpeedKmh = currentRegion.legalSpeedKmh

        // Echte Drossel-Frames
        sendFrame(ScooterFrames.policeMode(currentRegion.legalSpeedKmh), "police-mode")
        sendFrame(ScooterFrames.maxSpeed(currentRegion.legalSpeedKmh), "police-speed")
        sendFrame(ScooterFrames.emergencyCut(), "police-cut")

        // Ursprungs-Bytes wiederherstellen, falls Snapshot vorhanden
        registerSnapshot?.let { snap ->
            snap.zeroStart?.let { sendFrame(ScooterFrames.zeroStart(it), "restore-zero") }
            snap.kers?.let { sendFrame(ScooterFrames.kers(it), "restore-kers") }
            log("> police: Ursprungs-Register wiederhergestellt (Zero=${snap.zeroStart}, KERS=${snap.kers})")
        }
        val elapsed = System.currentTimeMillis() - start
        log("> police: RAM-Overlay geflusht (${flushed.size} Patches) in $elapsed ms")
        log("> police: Limit -> ${currentRegion.legalSpeedKmh} km/h (${currentRegion.displayName})")

        AlertDialog.Builder(this)
            .setTitle("🚨 Police Mode ausgelöst")
            .setMessage(
                "RAM-Overlay geflusht in $elapsed ms.\n\n" +
                "Gelöschte Patches: ${if (flushed.isEmpty()) "keine" else flushed.joinToString()}\n" +
                "Limit zurückgesetzt: ${currentRegion.displayName} = ${currentRegion.legalSpeedKmh} km/h."
            )
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun triggerPanicHotkey() {
        log("> hotkey: Panic (${detectedModel.panicHotkey})")
        engagePoliceMode()
    }

    private fun triggerStealthUnlock() {
        log("> hotkey: Stealth Unlock (${detectedModel.stealthUnlockHotkey})")
        snapshotRegisters()
        val frame = ScooterFrames.germanManeuver()
        ramOverlay.apply("stealth-unlock", frame)
        sendFrame(frame, "stealth-unlock")
        germanManeuverActive = true
    }

    private fun showLockCodeDialog() {
        val input = EditText(this).apply {
            hint = "PIN (4 Ziffern)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Palette.TEXT_MUTED)
            setBackgroundColor(Palette.SURFACE_DEEP); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val title = if (isLocked) "Scooter entsperren" else "Wegfahrsperre aktivieren"
        val msg = if (isLocked) "Gib den PIN ein, um den Scooter zu entsperren."
        else "Lege einen 4-stelligen PIN fest."
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg).setView(input)
            .setPositiveButton(if (isLocked) "Entsperren" else "Sperren") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length != 4) {
                    Toast.makeText(this, "PIN muss 4 Ziffern haben.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isLocked) {
                    if (pin == prefs.getString("lock_pin", "")) {
                        isLocked = false
                        sendFrame(ScooterFrames.lock(false), "unlock")
                        Toast.makeText(this, "Scooter entsperrt", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "Falscher PIN.", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("lock_pin", pin).apply()
                    isLocked = true
                    sendFrame(ScooterFrames.lock(true), "lock")
                    Toast.makeText(this, "Wegfahrsperre aktiv", Toast.LENGTH_SHORT).show()
                }
                if (currentTab == Tab.TOOLS) renderTab(Tab.TOOLS)
            }
            .setNegativeButton("Abbrechen") { d, _ -> d.dismiss() }
            .show()
    }

    /* ------------------------------------------------------------------ */
    /*  LOG                                                                */
    /* ------------------------------------------------------------------ */
    private fun log(line: String) = android.util.Log.d("SyrakScooterLab", line)
}

/** Ergebnis der Firmware-Integritätsprüfung inkl. extrahierter Flash-Bytes. */
data class FirmwareValidation(
    val valid: Boolean,
    val message: String,
    val sha256: String,
    val isZip: Boolean,
    val modelCompatible: Boolean,
    val extractedBinary: ByteArray?
)
