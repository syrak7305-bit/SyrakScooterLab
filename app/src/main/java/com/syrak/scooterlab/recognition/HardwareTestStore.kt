package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.ScooterManufacturer

/* ============================================================================
 *  PHASE 17.4 — PERSISTENZ, IMPORT UND FILTER FUER HARDWARE-TESTS
 *  ---------------------------------------------------------------------------
 *  Diese Datei ist bewusst Android-frei (reines Kotlin). Der konkrete
 *  Speicher (SharedPreferences) wird ueber [HardwareTestPersistence]
 *  eingebunden, sodass die Logik in Unit-Tests ohne Android-Laufzeit
 *  geprueft werden kann.
 *
 *  EHRLICHKEITSREGELN (bindend):
 *   • Es werden KEINE Messwerte erfunden. Persistiert wird exakt das, was in
 *     [HardwareTestRecord] steht.
 *   • Ein fehlgeschlagener Schreibvorgang darf niemals bestehende Daten
 *     loeschen — der alte Stand bleibt erhalten.
 *   • Import ueberschreibt NIEMALS stillschweigend vorhandene Datensaetze.
 * ==========================================================================*/

/**
 * Abstraktion des tatsaechlichen Speichers.
 *
 * [read] liefert den primaeren Rohtext (oder null, wenn nichts gespeichert ist).
 * [readBackup] liefert den letzten bekannt guten Stand (oder null).
 * [write] muss atomar sein: entweder der neue Stand ist vollstaendig
 * geschrieben oder der alte bleibt unveraendert. Rueckgabe `false` bedeutet
 * "nicht geschrieben".
 */
interface HardwareTestPersistence {
    fun read(): String?
    fun readBackup(): String?
    fun write(payload: String): Boolean
}

/** Ergebniszustand eines Ladevorgangs. */
enum class HardwareTestLoadStatus {
    /** Es wurde noch nie etwas gespeichert. */
    EMPTY,

    /** Datensaetze erfolgreich geladen (ggf. aus dem Backup). */
    LOADED,

    /** Gespeicherter Text ist strukturell defekt und nicht lesbar. */
    CORRUPT,

    /** Der Speicher selbst konnte nicht gelesen werden. */
    IO_ERROR
}

/**
 * Ergebnis von [HardwareTestStore.load].
 *
 * @param usedBackup true, wenn der Hauptstand defekt war und der Backup-Stand
 *   verwendet wurde. Der defekte Hauptstand wird dabei NICHT geloescht.
 */
data class HardwareTestLoadResult(
    val status: HardwareTestLoadStatus,
    val records: List<HardwareTestRecord> = emptyList(),
    val message: String = "",
    val usedBackup: Boolean = false,
    val warnings: List<String> = emptyList()
) {
    /** true, wenn mit den geladenen Daten gearbeitet werden kann. */
    val isUsable: Boolean
        get() = status == HardwareTestLoadStatus.EMPTY || status == HardwareTestLoadStatus.LOADED

    fun registry(): HardwareTestRegistry = HardwareTestRegistry(records)
}

/** Ergebnis von [HardwareTestStore.save]. */
data class HardwareTestSaveResult(
    val success: Boolean,
    val message: String
)

/**
 * Laedt und speichert [HardwareTestRecord]s ueber eine [HardwareTestPersistence].
 *
 * Der Store entscheidet NICHT ueber die Semantik der Datensaetze — er
 * transportiert sie nur verlustfrei.
 */
class HardwareTestStore(private val persistence: HardwareTestPersistence) {

    /**
     * Laedt alle Datensaetze.
     *
     * Reihenfolge:
     *  1. Nichts gespeichert → [HardwareTestLoadStatus.EMPTY]
     *  2. Hauptstand lesbar → [HardwareTestLoadStatus.LOADED]
     *  3. Hauptstand defekt, Backup lesbar → LOADED mit `usedBackup = true`
     *  4. Beides defekt → [HardwareTestLoadStatus.CORRUPT] (nichts wird geloescht)
     */
    fun load(): HardwareTestLoadResult {
        val primary = try {
            persistence.read()
        } catch (t: Throwable) {
            return HardwareTestLoadResult(
                status = HardwareTestLoadStatus.IO_ERROR,
                message = "Hardware-Tests konnten nicht gelesen werden: ${t.message ?: "unbekannter Fehler"}"
            )
        }

        if (primary.isNullOrBlank()) {
            return HardwareTestLoadResult(
                status = HardwareTestLoadStatus.EMPTY,
                message = "Noch keine Hardware-Tests gespeichert."
            )
        }

        val decoded = HardwareTestJson.decode(primary)
        if (decoded.isSuccess) {
            return HardwareTestLoadResult(
                status = HardwareTestLoadStatus.LOADED,
                records = decoded.records,
                message = "${decoded.records.size} Hardware-Test(s) geladen.",
                warnings = decoded.warnings + decoded.errors
            )
        }

        // Hauptstand defekt — Backup versuchen, ohne den Hauptstand zu loeschen.
        val backup = try {
            persistence.readBackup()
        } catch (_: Throwable) {
            null
        }

        if (!backup.isNullOrBlank()) {
            val fromBackup = HardwareTestJson.decode(backup)
            if (fromBackup.isSuccess) {
                return HardwareTestLoadResult(
                    status = HardwareTestLoadStatus.LOADED,
                    records = fromBackup.records,
                    message = "Hauptstand defekt, ${fromBackup.records.size} Test(s) aus Backup geladen.",
                    usedBackup = true,
                    warnings = decoded.errors + fromBackup.warnings
                )
            }
        }

        return HardwareTestLoadResult(
            status = HardwareTestLoadStatus.CORRUPT,
            message = "Gespeicherte Hardware-Tests sind defekt und konnten nicht gelesen werden. " +
                "Die Datei wurde nicht veraendert.",
            warnings = decoded.errors
        )
    }

    /**
     * Speichert [records] atomar.
     *
     * Bei einem Schreibfehler bleibt der alte Stand erhalten; es wird eine
     * verstaendliche Meldung zurueckgegeben.
     */
    fun save(records: List<HardwareTestRecord>): HardwareTestSaveResult {
        val payload = try {
            HardwareTestJson.encode(records)
        } catch (t: Throwable) {
            return HardwareTestSaveResult(
                success = false,
                message = "Hardware-Tests konnten nicht serialisiert werden: ${t.message ?: "unbekannter Fehler"}"
            )
        }

        val written = try {
            persistence.write(payload)
        } catch (t: Throwable) {
            return HardwareTestSaveResult(
                success = false,
                message = "Speichern fehlgeschlagen (${t.message ?: "unbekannter Fehler"}). " +
                    "Der bisherige Stand bleibt erhalten."
            )
        }

        return if (written) {
            HardwareTestSaveResult(true, "${records.size} Hardware-Test(s) gespeichert.")
        } else {
            HardwareTestSaveResult(
                success = false,
                message = "Speichern fehlgeschlagen. Der bisherige Stand bleibt erhalten."
            )
        }
    }
}

/**
 * Ergebnis eines JSON-Imports.
 *
 * @param importedIds IDs, die neu aufgenommen wurden.
 * @param skippedIds IDs, die bereits vorhanden waren (nicht ueberschrieben).
 * @param rejected Klartextgruende fuer abgelehnte Datensaetze.
 */
data class HardwareTestImportOutcome(
    val records: List<HardwareTestRecord>,
    val importedIds: List<String> = emptyList(),
    val skippedIds: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val importedCount: Int get() = importedIds.size
    val skippedCount: Int get() = skippedIds.size
    val rejectedCount: Int get() = rejected.size

    fun summary(): String = buildString {
        append("Import: ").append(importedCount).append(" neu")
        append(", ").append(skippedCount).append(" uebersprungen")
        append(", ").append(rejectedCount).append(" abgelehnt")
    }
}

/**
 * Fuehrt vorhandene und importierte Datensaetze zusammen, ohne jemals einen
 * vorhandenen Datensatz zu ueberschreiben.
 */
object HardwareTestImporter {

    /** Zusammenfuehrung bereits dekodierter Datensaetze. */
    fun merge(
        existing: List<HardwareTestRecord>,
        decoded: HardwareTestDecodeResult
    ): HardwareTestImportOutcome {
        val result = existing.toMutableList()
        val knownIds = existing.map { it.id }.toMutableSet()
        val knownContent = existing.map { it.contentKey() }.toMutableSet()

        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val rejected = decoded.errors.toMutableList()

        for (record in decoded.records) {
            when {
                record.id in knownIds -> {
                    skipped += record.id
                }
                record.contentKey() in knownContent -> {
                    // Gleicher Inhalt unter anderer ID: nicht doppelt aufnehmen.
                    skipped += record.id
                }
                else -> {
                    result += record
                    knownIds += record.id
                    knownContent += record.contentKey()
                    imported += record.id
                }
            }
        }

        return HardwareTestImportOutcome(
            records = result,
            importedIds = imported,
            skippedIds = skipped,
            rejected = rejected,
            warnings = decoded.warnings
        )
    }

    /** Import direkt aus JSON-Text. */
    fun importJson(
        existing: List<HardwareTestRecord>,
        json: String
    ): HardwareTestImportOutcome {
        val decoded = HardwareTestJson.decode(json)
        if (!decoded.isSuccess) {
            return HardwareTestImportOutcome(
                records = existing,
                rejected = decoded.errors.ifEmpty { listOf("Datei konnte nicht gelesen werden.") }
            )
        }
        return merge(existing, decoded)
    }
}

/**
 * Stabiler Inhalts-Schluessel eines Datensatzes.
 *
 * Bewusst OHNE `id`, `tester` und `notes`: diese Felder aendern den
 * fachlichen Inhalt eines Tests nicht. Zwei Datensaetze mit gleichem
 * Schluessel gelten als derselbe Test.
 */
fun HardwareTestRecord.contentKey(): String = buildString {
    append(manufacturer.name).append('|')
    append(modelName.trim().lowercase()).append('|')
    append(testDate).append('|')
    append(expectedFingerprintId ?: "").append('|')
    append(scan?.let { scanKey(it) } ?: "").append('|')
    append(gatt?.let { gattKey(it) } ?: "").append('|')
    append(observedRecognition?.let { obsKey(it) } ?: "")
}

private fun scanKey(scan: HardwareScanSnapshot): String = buildString {
    append(scan.deviceName ?: "").append(';')
    append(scan.serviceUuids.joinToString(",")).append(';')
    append(scan.manufacturerIds.joinToString(",")).append(';')
    append(scan.serviceDataUuids.joinToString(",")).append(';')
    append(scan.rssi?.toString() ?: "")
}

private fun gattKey(gatt: HardwareGattSnapshot): String = buildString {
    append(gatt.discoveryStatus.name).append(';')
    append(gatt.services.joinToString(",") { s ->
        s.uuid + "[" + s.characteristics.joinToString("/") { c ->
            c.uuid + ":" + c.properties.joinToString("+") { it.name }
        } + "]"
    })
}

private fun obsKey(obs: HardwareRecognitionSnapshot): String = buildString {
    append(obs.confidence.name).append(';')
    append(obs.detectedBrand.name).append(';')
    append(obs.detectedBrandFamily.name).append(';')
    append(obs.detectedModelFamily ?: "").append(';')
    append(obs.confirmedModel ?: "").append(';')
    append(obs.probableModel ?: "").append(';')
    append(obs.matchedProfileId ?: "")
}

/**
 * Filter fuer die Uebersichtsliste. Alle Felder sind optional; ein leeres
 * Filterobjekt laesst alles durch.
 */
data class HardwareTestFilter(
    val manufacturer: ScooterManufacturer? = null,
    val modelQuery: String = "",
    val fingerprintId: String? = null,
    val maturity: FingerprintMaturity? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null
) {
    val isEmpty: Boolean
        get() = manufacturer == null &&
            modelQuery.isBlank() &&
            fingerprintId == null &&
            maturity == null &&
            dateFrom == null &&
            dateTo == null

    fun matches(record: HardwareTestRecord): Boolean {
        if (manufacturer != null && record.manufacturer != manufacturer) return false
        if (modelQuery.isNotBlank() &&
            !record.modelName.contains(modelQuery.trim(), ignoreCase = true)
        ) return false
        if (fingerprintId != null && record.expectedFingerprintId != fingerprintId) return false
        if (dateFrom != null && IsoDate.compare(record.testDate, dateFrom) < 0) return false
        if (dateTo != null && IsoDate.compare(record.testDate, dateTo) > 0) return false
        if (maturity != null && maturityOf(record) != maturity) return false
        return true
    }

    /**
     * Reifegrad eines Datensatzes, abgeleitet aus dem erwarteten Fingerprint.
     * Unbekannte Fingerprints gelten als [FingerprintMaturity.DOCUMENTED].
     */
    fun maturityOf(record: HardwareTestRecord): FingerprintMaturity {
        val fingerprint = record.expectedFingerprintId
            ?.let { BuiltInModelFingerprints.byId(it) }
            ?: return FingerprintMaturity.DOCUMENTED
        return HardwareTestEvaluator.assess(record, fingerprint).maturity
    }

    companion object {
        fun apply(
            records: List<HardwareTestRecord>,
            filter: HardwareTestFilter
        ): List<HardwareTestRecord> =
            if (filter.isEmpty) records else records.filter { filter.matches(it) }
    }
}
