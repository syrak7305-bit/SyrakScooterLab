package com.syrak.scooterlab

import android.bluetooth.BluetoothDevice
import java.util.Locale

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
    val notes: String,
    /**
     * Optionaler erwarteter SHA-256 (hex, lowercase). Ist er gesetzt, wird die
     * geladene Firmware HART dagegen geprüft (echte Integritätsprüfung).
     * Ist er null, wird nur der Fingerprint angezeigt — dann KEINE Verifikation.
     */
    val expectedSha256: String? = null
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
    /** Alle Zellgruppen-Spannungen (V) aus dem BMS-Block, sofern gesendet. */
    val cellVoltages: List<Float>? = null,
    /** BMS-Design-Kapazität (mAh) + aktuelle Vollkapazität (mAh) -> SoH. */
    val designCapacityMah: Int? = null,
    val fullChargeCapacityMah: Int? = null,
    val cycleCount: Int? = null,
    val bmsTempC: Float? = null,
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
 *  LOG-TERMINAL (Neo-Hacker Pro)
 * ------------------------------------------------------------------------- */
enum class LogCategory(val label: String) {
    INFO("INFO"), TX("TX"), RX("RX"), FLASH("FLASH"), ERROR("FEHLER")
}

data class LogEntry(val time: String, val category: LogCategory, val text: String)

/* ---------------------------------------------------------------------------
 *  FIRMWARE-QUELLEN (Community-Plattformen)
 * ------------------------------------------------------------------------- */
enum class FirmwareSource(
    val displayName: String,
    // KEINE FAKES: bewusst KEINE erfundenen Download-URLs. Stattdessen ehrliche
    // Referenz-Texte, damit die UI identisch bleibt, aber nichts vorgetaeuscht wird.
    val reference: String,
    val description: String
) {
    MI_FW_INFO(
        "mi-fw-info (Streamlit)",
        "Community-Datenbank • Referenz: mi-fw-info (Streamlit-App). Kein offizieller Download-Endpunkt hinterlegt.",
        "Community-Datenbank fuer Navee/Brightway- & Xiaomi-Custom-Patches (DRV/BMS)."
    ),
    SCOOTER_HACKING(
        "ScooterHacking / SHFW",
        "Community-Projekt • Referenz: ScooterHacking (SHFW). Kein offizieller Download-Endpunkt hinterlegt.",
        "SHFW-Repository fuer Segway-Ninebot & Xiaomi - volle Parameterkontrolle."
    ),
    NAVEE_FLASHER(
        "Navee Flasher Repo",
        "Community-Projekt • Referenz: Navee-Flasher-Repository. Kein offizieller Download-Endpunkt hinterlegt.",
        "Verifizierte MCU/DRV-Binaries fuer GT3 Max, GT3 Pro, ST3, N65, S65, V40."
    ),
    M365_DOWNG(
        "M365 DownG / Ninebot Flasher",
        "Community-Projekt • Referenz: M365 DownG / Ninebot Flasher. Kein offizieller Download-Endpunkt hinterlegt.",
        "DownG- & Ninebot-Flasher-Repositories (Ninebot/Xiaomi Stock & Custom)."
    ),
    FACTORY(
        "Hersteller / Werks-OTA",
        "Hersteller-Kanal • Referenz: offizielles Werks-OTA (Segway-Ninebot). Kein direkter Download-Endpunkt hinterlegt.",
        "Offizielle Werks-Firmware (legaler Auslieferungszustand)."
    )
}

/** Fahrdaten der aktuellen Fahrt (Trip-Computer). */
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

    fun reset() {
        distanceKm = 0.0; maxSpeedKmh = 0.0; speedSum = 0.0; speedSamples = 0
        energyWh = 0.0; startMillis = 0L; lastMillis = 0L
    }
}

/** Motor-Performance-Profil (Eco-Saver / Sport-Plus). */
enum class RideMode(val displayName: String, val description: String) {
    NORMAL("Normal", "Werksseitige Leistungsabgabe."),
    ECO_SAVER("Eco-Saver", "Drosselt sanft unter 20 % Akkustand, um die Reichweite zu maximieren."),
    SPORT_PLUS("Sport-Plus", "Maximale Beschleunigung + temporaerer Phasenstrom unter Thermalschutz.")
}

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
    NAVEE_GT3_MAX("Navee GT3 Max", listOf("gt3 max", "navee gt3 max", "gt3", "navee"), 20, 45, 48.0f, 13,
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
        /**
         * Erkennt das Modell aus dem BLE-Namen (Keyword-Scoring). [address] ist
         * Teil der Erkennungs-API (z. B. fuer spaetere MAC-/OUI-Zuordnung) und
         * wird derzeit bewusst NICHT ausgewertet \u2014 es werden keine Werte erfunden.
         */
        @Suppress("UNUSED_PARAMETER")
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

/** Ergebnis der Firmware-Integritätsprüfung inkl. extrahierter Flash-Bytes. */
data class FirmwareValidation(
    val valid: Boolean,
    val message: String,
    val sha256: String,
    val isZip: Boolean,
    val modelCompatible: Boolean,
    val extractedBinary: ByteArray?,
    /** true, wenn ein erwarteter Hash vorlag UND übereinstimmte. */
    val hashVerified: Boolean = false
)
