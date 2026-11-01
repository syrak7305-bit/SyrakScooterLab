package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.ScooterManufacturer
import com.syrak.scooterlab.ScanObservation
import com.syrak.scooterlab.diagnostics.GattProperty

/* ============================================================================
 *  PHASE 17.1 — MODELL-FINGERPRINT-DATENBANK
 *  ---------------------------------------------------------------------------
 *  Erweiterbare Struktur fuer Geraeteprofile. JEDES Merkmal traegt eine
 *  Evidenzquelle und einen Evidenzgrad.
 *
 *  EHRLICHKEITSREGEL (bindend):
 *   Es werden KEINE Geraeteprofile erfunden. Die eingebauten Profile leiten
 *   sich ausschliesslich aus bereits im Repository dokumentierten Werten ab
 *   (`BleUuids.kt`, `BLUETOOTH_PROTOCOL_AUDIT.md`, Phase 16). Keines dieser
 *   Profile ist auf echter Hardware bestaetigt — daher ist
 *   [HardwareTestStatus.CONFIRMED_ON_HARDWARE] nirgends gesetzt und
 *   [RecognitionConfidence.CONFIRMED] aktuell unerreichbar.
 * ==========================================================================*/

/** Ein Merkmal mit Herkunft und Evidenzgrad. */
data class FingerprintEvidence(
    val grade: EvidenceGrade,
    val source: String
)

/** Advertisement-Merkmale eines Profils. */
data class AdvertisementFingerprint(
    val namePatterns: List<String> = emptyList(),
    val serviceUuids: Map<String, FingerprintEvidence> = emptyMap(),
    val manufacturerIds: Map<Int, FingerprintEvidence> = emptyMap(),
    val serviceDataUuids: Map<String, FingerprintEvidence> = emptyMap()
)

/**
 * GATT-Merkmale eines Profils.
 *
 * [requiredProperties] beschreibt ausschliesslich technische Faehigkeiten
 * (z. B. "diese Characteristic ist notifiable"), NICHT Protokollrollen.
 */
data class GattFingerprint(
    val serviceUuids: Map<String, FingerprintEvidence> = emptyMap(),
    val characteristicUuids: Map<String, FingerprintEvidence> = emptyMap(),
    val requiredProperties: Map<String, Set<GattProperty>> = emptyMap()
)

/**
 * Ein einzelnes Geraeteprofil.
 *
 * @param modelName konkreter Modellname. Bei unbestätigten Profilen bewusst
 *   mit Hinweis "(unbestätigt)" versehen.
 * @param modelFamily uebergeordnete Familie, z. B. "Ninebot/Xiaomi".
 * @param source primaere Quelle des gesamten Profils.
 * @param hardwareTestStatus ehrlicher Teststatus.
 * @param confirmationDate nur gesetzt, wenn wirklich bestaetigt.
 */
data class ModelFingerprint(
    val id: String,
    val manufacturer: ScooterManufacturer,
    val brandFamily: BrandFamily,
    val modelName: String,
    val modelFamily: String,
    val advertisement: AdvertisementFingerprint = AdvertisementFingerprint(),
    val gatt: GattFingerprint = GattFingerprint(),
    val source: String,
    val hardwareTestStatus: HardwareTestStatus = HardwareTestStatus.NOT_TESTED,
    val confirmationDate: String? = null,
    val version: Int = 1
) {
    /** true nur bei echtem Hardware-Test UND vorhandenem Bestaetigungsdatum. */
    val isHardwareConfirmed: Boolean
        get() = hardwareTestStatus == HardwareTestStatus.CONFIRMED_ON_HARDWARE &&
            confirmationDate != null

    /** Alle Merkmale dieses Profils als flache Liste (fuer Export/Debug). */
    fun evidenceSummary(): List<String> = buildList {
        advertisement.serviceUuids.forEach { (uuid, ev) ->
            add("ADV_SERVICE=$uuid ${ev.grade.name} (${ev.source})")
        }
        advertisement.manufacturerIds.forEach { (id, ev) ->
            add("ADV_COMPANY=0x${id.toString(16)} ${ev.grade.name} (${ev.source})")
        }
        advertisement.serviceDataUuids.forEach { (uuid, ev) ->
            add("ADV_SERVICE_DATA=$uuid ${ev.grade.name} (${ev.source})")
        }
        gatt.serviceUuids.forEach { (uuid, ev) ->
            add("GATT_SERVICE=$uuid ${ev.grade.name} (${ev.source})")
        }
        gatt.characteristicUuids.forEach { (uuid, ev) ->
            add("GATT_CHAR=$uuid ${ev.grade.name} (${ev.source})")
        }
    }
}

/** Zugriff auf die Profil-Datenbank. */
interface ModelFingerprintDatabase {
    fun all(): List<ModelFingerprint>
    fun byBrand(manufacturer: ScooterManufacturer): List<ModelFingerprint>
    fun byId(id: String): ModelFingerprint?
}

/**
 * Eingebaute Profile — ausschliesslich aus dokumentierten Projektwerten.
 *
 * Bewusst KEINE erfundenen Modelle, KEINE erfundenen UUIDs, KEINE
 * Hardware-Bestaetigung.
 */
object BuiltInModelFingerprints : ModelFingerprintDatabase {

    private const val AUDIT_SOURCE =
        "BleUuids.kt / BLUETOOTH_PROTOCOL_AUDIT.md (Phase 16, PROJECT_CONSTANT)"

    private fun u(short: String): String =
        ScanObservation.normalizeUuid(short) ?: short

    private val entries: List<ModelFingerprint> = listOf(
        ModelFingerprint(
            id = "ninebot-generic-unconfirmed",
            manufacturer = ScooterManufacturer.NINEBOT,
            brandFamily = BrandFamily.SEGWAY_NINEBOT,
            modelName = "Ninebot (Modell unbestätigt)",
            modelFamily = "Ninebot/Xiaomi",
            advertisement = AdvertisementFingerprint(
                namePatterns = listOf("ninebot", "segway"),
                serviceUuids = mapOf(
                    u("e003") to FingerprintEvidence(EvidenceGrade.PROBABLE, AUDIT_SOURCE)
                )
            ),
            gatt = GattFingerprint(
                serviceUuids = mapOf(
                    u("e003") to FingerprintEvidence(EvidenceGrade.PROBABLE, AUDIT_SOURCE)
                ),
                characteristicUuids = mapOf(
                    u("fff1") to FingerprintEvidence(EvidenceGrade.PROBABLE, AUDIT_SOURCE),
                    u("fff2") to FingerprintEvidence(EvidenceGrade.PROBABLE, AUDIT_SOURCE)
                )
            ),
            source = AUDIT_SOURCE,
            hardwareTestStatus = HardwareTestStatus.NOT_TESTED,
            confirmationDate = null,
            version = 1
        ),
        ModelFingerprint(
            id = "navee-gt3max-unconfirmed",
            manufacturer = ScooterManufacturer.NAVEE,
            brandFamily = BrandFamily.NAVEE,
            modelName = "NAVEE GT3 Max (unbestätigt)",
            modelFamily = "NAVEE",
            advertisement = AdvertisementFingerprint(
                namePatterns = listOf("navee", "gt3"),
                serviceUuids = mapOf(
                    u("ffe0") to FingerprintEvidence(EvidenceGrade.PROBABLE, AUDIT_SOURCE)
                )
            ),
            gatt = GattFingerprint(
                serviceUuids = mapOf(
                    u("ffe0") to FingerprintEvidence(EvidenceGrade.PROBABLE, AUDIT_SOURCE)
                ),
                characteristicUuids = mapOf(
                    u("ffe1") to FingerprintEvidence(EvidenceGrade.PROBABLE, AUDIT_SOURCE),
                    u("ffe2") to FingerprintEvidence(EvidenceGrade.PROBABLE, AUDIT_SOURCE)
                ),
                requiredProperties = mapOf(
                    u("ffe1") to setOf(GattProperty.NOTIFY),
                    u("ffe2") to setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)
                )
            ),
            source = AUDIT_SOURCE,
            hardwareTestStatus = HardwareTestStatus.NOT_TESTED,
            confirmationDate = null,
            version = 1
        ),
        ModelFingerprint(
            id = "xiaomi-legacy-unconfirmed",
            manufacturer = ScooterManufacturer.XIAOMI,
            brandFamily = BrandFamily.XIAOMI,
            modelName = "Xiaomi (Legacy-Hinweis, unbestätigt)",
            modelFamily = "Xiaomi",
            advertisement = AdvertisementFingerprint(
                namePatterns = listOf("xiaomi", "m365", "mi scooter"),
                serviceUuids = mapOf(
                    u("fe95") to FingerprintEvidence(EvidenceGrade.UNKNOWN, AUDIT_SOURCE)
                )
            ),
            gatt = GattFingerprint(
                serviceUuids = mapOf(
                    u("fe95") to FingerprintEvidence(EvidenceGrade.UNKNOWN, AUDIT_SOURCE)
                )
            ),
            source = "$AUDIT_SOURCE — LEGACY_UNCONFIRMED",
            hardwareTestStatus = HardwareTestStatus.NOT_TESTED,
            confirmationDate = null,
            version = 1
        )
    )

    override fun all(): List<ModelFingerprint> = entries

    override fun byBrand(manufacturer: ScooterManufacturer): List<ModelFingerprint> =
        entries.filter { it.manufacturer == manufacturer }

    override fun byId(id: String): ModelFingerprint? = entries.firstOrNull { it.id == id }
}
