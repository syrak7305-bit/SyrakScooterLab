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
    var lastMillis: Long = 0L,
    /**
     * Letzter Telemetrie-Tick auf der MONOTONEN Uhr
     * ([android.os.SystemClock.elapsedRealtime]). Nur fuer die
     * Strecken-/Energieintegration; Wanduhr-Spruenge duerfen sie nicht
     * verfaelschen. Nicht persistiert.
     */
    var lastElapsedMillis: Long = 0L
) {
    val avgSpeedKmh: Double get() = if (speedSamples > 0) speedSum / speedSamples else 0.0
    val durationMillis: Long get() = if (startMillis == 0L) 0L else (lastMillis - startMillis)

    fun reset() {
        distanceKm = 0.0; maxSpeedKmh = 0.0; speedSum = 0.0; speedSamples = 0
        energyWh = 0.0; startMillis = 0L; lastMillis = 0L; lastElapsedMillis = 0L
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
    NINEBOT_F2_PRO("Segway-Ninebot F2 Pro", listOf("f2 pro", "f40", "f30", "f20"), 30, 38, 36.0f, 10,
        "Bremshebel 2× + Power-Taste", "Power-Taste 3× kurz drücken"),
    NINEBOT_ES("Segway-Ninebot ES-Serie", listOf("es1", "es2", "es3", "es4", "ninebot es"), 25, 32, 36.0f, 10,
        "Bremshebel + Power-Taste 2 s", "Power-Taste 3× kurz drücken"),
    XIAOMI_PRO_2("Xiaomi Pro 2", listOf("pro 2", "pro2", "mi pro 2"), 30, 35, 36.0f, 10,
        "Bremshebel ziehen + Power-Taste 3 s halten", "Power-Taste 3× kurz drücken"),
    XIAOMI_M365("Xiaomi M365 / 1S", listOf("m365", "mi scooter", "mi 1s", "mi essential"), 28, 32, 36.0f, 10,
        "Bremshebel ziehen + Power-Taste 3 s halten", "Power-Taste 3× kurz drücken"),
    NAVEE_GT3_MAX("Navee GT3 Max", listOf("gt3 max", "navee gt3 max"), 20, 45, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_GT3_PRO("Navee GT3 Pro", listOf("gt3 pro", "navee gt3 pro"), 32, 42, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_ST3("Navee ST3", listOf("navee st3", "st3 pro", "st3"), 30, 40, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_N65("Navee N65", listOf("navee n65", "n65 pro", "n65"), 30, 40, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_V40("Navee V40", listOf("navee v40", "v40 pro", "v40"), 30, 40, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    NAVEE_S65("Navee S65", listOf("navee s65", "s65"), 30, 40, 48.0f, 13,
        "5× Bremshebel ziehen + 5× Gashebel drücken", "Blinker links + rechts gleichzeitig 3 s", BleProtocol.NAVEE_BRIGHTWAY),
    SOFLOW_SO4("SoFlow SO4 / SO6", listOf("soflow", "so4", "so6", "so8", "so3"), 25, 32, 36.0f, 10,
        "Bremshebel + Power-Taste 3 s", "Power-Taste 3× kurz drücken"),
    UNKNOWN("Unbekanntes Modell", emptyList(), 25, 35, 36.0f, 10,
        "Bremshebel + Power-Taste 3 s", "Power-Taste 3× kurz drücken");

    val isKnown: Boolean get() = this != UNKNOWN

    /**
     * Hersteller-/Marken-Ebene, getrennt vom Modell (Phase 8).
     *
     * Bewusst aus dem Enum ABGELEITET statt aus dem BLE-Namen geraten: ein
     * einmal korrekt erkanntes Modell kennt seinen Hersteller. Die
     * Namensheuristik fuer den Hersteller liegt in [ScooterManufacturer.classify]
     * und wird nur fuer UNBEKANNTE Modelle befragt.
     */
    val manufacturer: ScooterManufacturer
        get() = when (this) {
            NINEBOT_MAX_G2, NINEBOT_MAX_G30, NINEBOT_F2_PRO, NINEBOT_ES ->
                ScooterManufacturer.NINEBOT
            XIAOMI_PRO_2, XIAOMI_M365 ->
                ScooterManufacturer.XIAOMI
            NAVEE_GT3_MAX, NAVEE_GT3_PRO, NAVEE_ST3, NAVEE_N65, NAVEE_V40, NAVEE_S65 ->
                ScooterManufacturer.NAVEE
            SOFLOW_SO4 ->
                ScooterManufacturer.SOFLOW
            UNKNOWN ->
                ScooterManufacturer.UNKNOWN
        }

    companion object {
        /**
         * Erkennt das Modell aus dem BLE-Namen (Keyword-Scoring). [address] ist
         * Teil der Erkennungs-API (z. B. fuer spaetere MAC-/OUI-Zuordnung) und
         * wird derzeit bewusst NICHT ausgewertet \u2014 es werden keine Werte erfunden.
         */
        @Suppress("UNUSED_PARAMETER")
        fun detect(name: String, address: String): ScooterModel =
            detectDetailed(name, address).model

        /**
         * Wie [detect], liefert aber zusaetzlich eine ehrliche Vertrauensstufe.
         *
         * Regeln (bewusst konservativ):
         *  - leerer/fehlender Name                  -> UNKNOWN
         *  - kein Keyword-Treffer                   -> UNKNOWN
         *  - zwei Modelle mit identischem Top-Score -> UNKNOWN (mehrdeutig)
         *  - Treffer nur ueber ein kurzes/allgemeines Keyword -> PROBABLE
         *  - Treffer ueber ein spezifisches Keyword (>= 5 Zeichen UND Ziffer,
         *    z. B. "gt3 max", "max g30", "navee s65") -> CONFIRMED
         *
         * Damit wird ein Geraet mit "NAVEE" im Namen NICHT als GT3 Max
         * eingestuft - "navee" allein ist kein Modellmerkmal.
         */
        @Suppress("UNUSED_PARAMETER")
        fun detectDetailed(name: String, address: String): DetectionResult {
            val n = name.lowercase(Locale.ROOT).trim()
            if (n.isEmpty() || n == "unknown device") {
                return DetectionResult(
                    UNKNOWN, DetectionConfidence.UNKNOWN, "kein Geraetename",
                    ScooterManufacturer.UNKNOWN
                )
            }
            var best: ScooterModel? = null
            var bestScore = 0
            var bestKeyword = ""
            var ties = 0
            // PHASE 8 FIX (falsche Modellzuordnung): Ist im Namen ein BEKANNTER
            // Hersteller enthalten, darf nur ein Modell DIESES Herstellers
            // gewinnen. Sonst wurde z. B. "KuKirin G2 Max" ueber das generische
            // Token "g2" als Segway-Ninebot Max G2 eingestuft.
            val nameBrand = ScooterManufacturer.classify(name)
            for (m in values()) {
                if (m == UNKNOWN) continue
                if (nameBrand.isKnown && m.manufacturer != nameBrand) continue
                var score = 0
                var longest = ""
                for (kw in m.matchKeywords) {
                    if (n.contains(kw)) {
                        score += kw.length
                        if (kw.length > longest.length) longest = kw
                    }
                }
                if (score > bestScore) {
                    bestScore = score; best = m; bestKeyword = longest; ties = 1
                } else if (score > 0 && score == bestScore) {
                    ties++
                }
            }
            val model = best ?: return DetectionResult(
                UNKNOWN, DetectionConfidence.UNKNOWN, "kein Modellmerkmal im Namen",
                // Modell unbekannt, aber der Hersteller kann trotzdem belegt sein
                // (z. B. "KuKirin G2 Max" -> Marke KuKirin, Modell noch nicht gefuehrt).
                ScooterManufacturer.classify(name)
            )
            if (ties > 1) {
                return DetectionResult(
                    UNKNOWN, DetectionConfidence.UNKNOWN,
                    "mehrdeutiger Name (\"$name\") - kein Modell bestaetigt",
                    ScooterManufacturer.classify(name)
                )
            }
            val specific = bestKeyword.length >= 5 && bestKeyword.any { it.isDigit() }
            val confidence = if (specific) DetectionConfidence.CONFIRMED else DetectionConfidence.PROBABLE
            return DetectionResult(
                model, confidence, "Treffer \"$bestKeyword\"", model.manufacturer
            )
        }
    }
}

/** Vertrauensstufe der Modellerkennung. */
enum class DetectionConfidence(val label: String, val description: String) {
    CONFIRMED(
        "sicher erkannt",
        "Der BLE-Name enthaelt ein eindeutiges, modellspezifisches Merkmal."
    ),
    PROBABLE(
        "vermutet",
        "Nur ein allgemeines/uneindeutiges Merkmal gefunden - Modell nicht bestaetigt."
    ),
    UNKNOWN(
        "unbekannt",
        "Kein belastbares Modellmerkmal im BLE-Namen."
    )
}

/** Ergebnis der Modellerkennung inkl. Begruendung. */
data class DetectionResult(
    val model: ScooterModel,
    val confidence: DetectionConfidence,
    val evidence: String,
    /**
     * Hersteller-/Marken-Ebene, getrennt von Modell und Erkennungssicherheit
     * (Phase 8). Default haelt bestehende Aufrufer kompatibel.
     */
    val manufacturer: ScooterManufacturer = ScooterManufacturer.UNKNOWN
)

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
