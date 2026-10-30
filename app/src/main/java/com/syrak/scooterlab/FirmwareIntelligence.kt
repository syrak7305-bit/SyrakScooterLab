package com.syrak.scooterlab

import java.io.File
import java.security.MessageDigest

/* ============================================================================
 *  PHASE 15 — FIRMWARE-INTELLIGENCE (reine Logik, unit-testbar)
 *  ---------------------------------------------------------------------------
 *  GRUNDREGEL: Ehrlichkeit vor Optik.
 *
 *  Die sechs Zustände sind KUMULATIV und streng geordnet. Ein späterer Zustand
 *  darf NUR angezeigt werden, wenn alle vorherigen tatsächlich belegt sind:
 *
 *    1 Erkannt            Modell aus echten Erkennungsdaten bestimmt
 *    2 Vorschläge         modellbezogene Vorschläge angezeigt
 *    3 Übernommen         Datei heruntergeladen ODER in den App-Speicher kopiert
 *    4 Geprüft            Struktur gültig + SHA-256 berechnet
 *    5 Modell verifiziert Hash stimmt mit hinterlegtem Wert überein UND Modellbezug belegt
 *    6 Geflasht           echter Flash-Vorgang erfolgreich abgeschlossen
 *
 *  WICHTIG: "Übernommen" ist NICHT "geflasht". Ein kopierter Download-Link ist
 *  KEIN Flash. Es werden keine Fortschritte oder Erfolge vorgetäuscht.
 * ==========================================================================*/

/** Kumulative Firmware-Zustände. */
enum class FirmwareStage(val rank: Int) {
    DETECTED(1),
    PROPOSED(2),
    OBTAINED(3),
    CHECKED(4),
    MODEL_VERIFIED(5),
    FLASHED(6);

    fun label(language: AppLanguage): String = when (this) {
        DETECTED -> if (language == AppLanguage.DE) "Modell erkannt" else "Model detected"
        PROPOSED -> if (language == AppLanguage.DE) "Vorschläge geladen" else "Suggestions loaded"
        OBTAINED -> if (language == AppLanguage.DE) "Paket übernommen" else "Package obtained"
        CHECKED -> if (language == AppLanguage.DE) "Integrität geprüft" else "Integrity checked"
        MODEL_VERIFIED -> if (language == AppLanguage.DE) "Modell verifiziert" else "Model verified"
        FLASHED -> if (language == AppLanguage.DE) "Geflasht" else "Flashed"
    }

    companion object {
        fun ordered(): List<FirmwareStage> = entries.sortedBy { it.rank }
    }
}

/** Rohbelege, die von der UI/BLE-Schicht geliefert werden. */
data class FirmwareEvidence(
    val modelDetected: Boolean = false,
    val suggestionsShown: Boolean = false,
    val packageObtained: Boolean = false,
    val integrityChecked: Boolean = false,
    val modelVerified: Boolean = false,
    val flashed: Boolean = false
)

data class FirmwareStageEntry(
    val stage: FirmwareStage,
    val reached: Boolean,
    /** Kurze Begründung, warum der Zustand (nicht) erreicht ist. */
    val evidence: String
)

data class FirmwareStageReport(
    val stages: List<FirmwareStageEntry>,
    val highest: FirmwareStage?,
    val missing: List<FirmwareStage>
) {
    fun reached(stage: FirmwareStage): Boolean = stages.firstOrNull { it.stage == stage }?.reached == true
}

object FirmwareLifecycle {

    /**
     * Baut die Zustandsleiter. Ein Zustand gilt nur dann als erreicht, wenn er
     * selbst UND alle Vorgänger belegt sind (Kumulativität). Widersprüchliche
     * Eingaben (z. B. "flashed" ohne "detected") werden dadurch entschärft.
     */
    fun evaluate(evidence: FirmwareEvidence, language: AppLanguage = AppLanguage.DE): FirmwareStageReport {
        val flags = mapOf(
            FirmwareStage.DETECTED to evidence.modelDetected,
            FirmwareStage.PROPOSED to evidence.suggestionsShown,
            FirmwareStage.OBTAINED to evidence.packageObtained,
            FirmwareStage.CHECKED to evidence.integrityChecked,
            FirmwareStage.MODEL_VERIFIED to evidence.modelVerified,
            FirmwareStage.FLASHED to evidence.flashed
        )

        var stillReached = true
        val entries = FirmwareStage.ordered().map { stage ->
            val own = flags[stage] == true
            val reached = stillReached && own
            if (!reached) stillReached = false
            FirmwareStageEntry(
                stage = stage,
                reached = reached,
                evidence = when {
                    reached -> if (language == AppLanguage.DE) "belegt" else "verified"
                    own -> if (language == AppLanguage.DE) "Vorgänger fehlt" else "previous missing"
                    else -> if (language == AppLanguage.DE) "offen" else "pending"
                }
            )
        }
        val highest = entries.lastOrNull { it.reached }?.stage
        val missing = entries.filter { !it.reached }.map { it.stage }
        return FirmwareStageReport(entries, highest, missing)
    }
}

/* ---------------------------------------------------------------------------
 *  PAKET-VERIFIKATION
 * -------------------------------------------------------------------------*/

/**
 * Verifikationsstufe eines Katalog-Eintrags. Bewusst konservativ:
 *  - UNVERIFIED     kein erwarteter Hash hinterlegt
 *  - HASH_PRESENT   Hash hinterlegt, aber noch nicht gegen eine Datei geprüft
 *  - HASH_VERIFIED  Datei-Hash stimmt mit dem hinterlegten Wert überein
 *  - MODEL_VERIFIED Hash stimmt UND Modellbezug ist belegt
 */
enum class PackageVerification {
    UNVERIFIED,
    HASH_PRESENT,
    HASH_VERIFIED,
    MODEL_VERIFIED;

    fun label(language: AppLanguage): String = when (this) {
        UNVERIFIED -> if (language == AppLanguage.DE) "NICHT VERIFIZIERT" else "NOT VERIFIED"
        HASH_PRESENT -> if (language == AppLanguage.DE) "HASH HINTERLEGT" else "HASH STORED"
        HASH_VERIFIED -> if (language == AppLanguage.DE) "HASH VERIFIZIERT" else "HASH VERIFIED"
        MODEL_VERIFIED -> if (language == AppLanguage.DE) "MODELL VERIFIZIERT" else "MODEL VERIFIED"
    }

    /** Nur diese Stufe rechtfertigt einen Hard-Flash. */
    val allowsHardFlash: Boolean get() = this == MODEL_VERIFIED
}

/** Ein aufbereiteter, modellbezogener Firmware-Vorschlag. */
data class FirmwareSuggestion(
    val packageId: String,
    val name: String,
    val version: String,
    val targetModel: String,
    val controller: String,
    val sourceLabel: String,
    /** Echter Download-Link oder null. Es werden KEINE Links erfunden. */
    val downloadUrl: String?,
    val expectedSha256: String?,
    val verification: PackageVerification,
    val notes: String,
    /** Ziel-Limit laut Referenz — ausdrücklich unbestätigt. */
    val referenceMaxKmh: Int
) {
    val hasDownloadLink: Boolean get() = !downloadUrl.isNullOrBlank()
    val isVerified: Boolean get() = verification.allowsHardFlash
}

object FirmwareSuggestions {

    /**
     * Baut die Vorschlagsliste ausschließlich aus Paketen, die für das erkannte
     * Modell vorgesehen sind. Bei unbekanntem Modell wird NICHTS vorgeschlagen,
     * weil ohne Modellbezug keine sinnvolle Auswahl möglich ist.
     */
    fun build(model: ScooterModel, packages: List<FirmwarePackage>): List<FirmwareSuggestion> {
        if (!model.isKnown) return emptyList()
        return packages.map { pkg ->
            FirmwareSuggestion(
                packageId = pkg.id,
                name = pkg.name,
                version = pkg.version,
                targetModel = model.displayName,
                controller = pkg.target,
                sourceLabel = pkg.sourceUrl,
                downloadUrl = pkg.downloadUrl,
                expectedSha256 = pkg.expectedSha256,
                verification = verificationOf(pkg),
                notes = pkg.notes,
                referenceMaxKmh = pkg.recommendedMaxKmh
            )
        }
    }

    fun verificationOf(pkg: FirmwarePackage): PackageVerification =
        if (pkg.expectedSha256.isNullOrBlank()) PackageVerification.UNVERIFIED
        else PackageVerification.HASH_PRESENT

    /**
     * Verifikation nach einer tatsächlichen Dateiprüfung. Der Zustand
     * MODEL_VERIFIED wird nur vergeben, wenn Hash UND Modellbezug belegt sind.
     */
    fun verificationAfterCheck(expectedSha256: String?, hashVerified: Boolean, modelCompatible: Boolean): PackageVerification =
        when {
            expectedSha256.isNullOrBlank() -> PackageVerification.UNVERIFIED
            !hashVerified -> PackageVerification.HASH_PRESENT
            modelCompatible -> PackageVerification.MODEL_VERIFIED
            else -> PackageVerification.HASH_VERIFIED
        }
}

/* ---------------------------------------------------------------------------
 *  PAKET-SPEICHER (App-interner Speicher)
 * -------------------------------------------------------------------------*/

/**
 * Kopiert übernommene Firmware in den app-eigenen Speicher. Bewusst reine
 * Datei-Logik ohne Android-Abhängigkeit, damit sie testbar bleibt.
 */
object FirmwareStorage {

    private const val MAX_STORED_BYTES = 512 * 1024

    fun directory(baseDir: File): File = File(baseDir, "firmware")

    fun fileNameFor(packageId: String): String {
        val safe = packageId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return if (safe.endsWith(".bin")) safe else "$safe.bin"
    }

    /**
     * Speichert [bytes] unter dem paketbezogenen Dateinamen. Gibt die Datei
     * zurück oder null, wenn die Größe unplausibel ist.
     */
    fun save(baseDir: File, packageId: String, bytes: ByteArray): File? {
        if (bytes.isEmpty() || bytes.size > MAX_STORED_BYTES) return null
        val dir = directory(baseDir)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileNameFor(packageId))
        return try {
            file.writeBytes(bytes)
            file
        } catch (_: Exception) {
            null
        }
    }

    fun list(baseDir: File): List<File> {
        val dir = directory(baseDir)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    fun storedHash(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        digest.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }
}

/* ---------------------------------------------------------------------------
 *  EHRLICHE STATUS-TEXTE
 * -------------------------------------------------------------------------*/

object FlashHonesty {

    /**
     * Klartext-Status für den FLASH-Bereich. Trennt ausdrücklich zwischen
     * "übernommen", "geprüft" und "geflasht".
     */
    fun statusLine(evidence: FirmwareEvidence, language: AppLanguage = AppLanguage.DE): String {
        val report = FirmwareLifecycle.evaluate(evidence, language)
        val highest = report.highest
        return when {
            highest == null -> if (language == AppLanguage.DE)
                "Kein Modell erkannt — es werden keine Firmware-Vorschläge angeboten." else
                "No model detected — no firmware suggestions offered."
            highest == FirmwareStage.DETECTED -> if (language == AppLanguage.DE)
                "Modell erkannt. Noch keine Vorschläge geladen." else
                "Model detected. No suggestions loaded yet."
            highest == FirmwareStage.PROPOSED -> if (language == AppLanguage.DE)
                "Vorschläge geladen. Es wurde noch keine Firmware übernommen." else
                "Suggestions loaded. No firmware obtained yet."
            highest == FirmwareStage.OBTAINED -> if (language == AppLanguage.DE)
                "Paket übernommen. Das ist KEIN Flash — Integrität noch nicht geprüft." else
                "Package obtained. This is NOT a flash — integrity not checked yet."
            highest == FirmwareStage.CHECKED -> if (language == AppLanguage.DE)
                "Datei geprüft, aber Modell/Hash nicht vollständig verifiziert. Hard-Flash gesperrt." else
                "File checked, but model/hash not fully verified. Hard flash locked."
            highest == FirmwareStage.MODEL_VERIFIED -> if (language == AppLanguage.DE)
                "Firmware für dieses Modell verifiziert. Flash noch nicht gestartet." else
                "Firmware verified for this model. Flash not started."
            else -> if (language == AppLanguage.DE)
                "Flash-Vorgang abgeschlossen und vom Controller bestätigt." else
                "Flash completed and confirmed by the controller."
        }
    }

    /** Ein Hard-Flash ist nur bei vollständig verifizierter Datei zulässig. */
    fun hardFlashAllowed(
        validation: FirmwareValidation?,
        modelKnown: Boolean,
        connected: Boolean
    ): Boolean = validation != null &&
        validation.valid &&
        validation.hashVerified &&
        validation.modelCompatible &&
        modelKnown &&
        connected
}

/* ---------------------------------------------------------------------------
 *  PHASE 16 – FIRMWARE-IDENTITAETSSICHERUNG
 *  ---------------------------------------------------------------------------
 *  Problem (Audit §5): Eine lokal ausgewaehlte Datei konnte unter dem Namen
 *  eines FREMDEN Katalogpakets gespeichert werden, weil [FirmwareStorage.save]
 *  nur die packageId als Dateinamen nutzt. Damit waere die Zuordnung
 *  "Paketname -> Datei" nicht mehr nachvollziehbar.
 *
 *  Regel: Ein Paket darf nur uebernommen werden, wenn
 *    - die Datei gueltig geprueft ist,
 *    - das aktuell ausgewaehlte Katalogpaket GENAU dem Vorschlag entspricht,
 *    - der SHA-256 gegen einen hinterlegten Wert verifiziert ist und
 *    - der Modellbezug belegt ist.
 *  Reine Logik, ohne Android-Abhaengigkeit.
 * -------------------------------------------------------------------------*/
enum class FirmwareImportDecision {
    ALLOWED,
    NO_VALIDATION,
    PACKAGE_MISMATCH,
    HASH_NOT_VERIFIED,
    MODEL_MISMATCH
}

object FirmwareImportGuard {

    fun evaluate(
        suggestionPackageId: String,
        selectedPackageId: String?,
        validationValid: Boolean,
        hashVerified: Boolean,
        modelCompatible: Boolean
    ): FirmwareImportDecision {
        if (!validationValid) return FirmwareImportDecision.NO_VALIDATION
        if (selectedPackageId == null || selectedPackageId != suggestionPackageId) {
            return FirmwareImportDecision.PACKAGE_MISMATCH
        }
        if (!hashVerified) return FirmwareImportDecision.HASH_NOT_VERIFIED
        if (!modelCompatible) return FirmwareImportDecision.MODEL_MISMATCH
        return FirmwareImportDecision.ALLOWED
    }

    fun reason(decision: FirmwareImportDecision, language: AppLanguage = AppLanguage.DE): String =
        when (decision) {
            FirmwareImportDecision.ALLOWED ->
                if (language == AppLanguage.DE) "Datei, Hash und Paket-Zuordnung stimmen ueberein."
                else "File, hash and package assignment match."
            FirmwareImportDecision.NO_VALIDATION ->
                if (language == AppLanguage.DE) "Die Datei ist nicht gueltig geprueft."
                else "The file is not validly verified."
            FirmwareImportDecision.PACKAGE_MISMATCH ->
                if (language == AppLanguage.DE)
                    "Die gepruefte Datei gehoert zu einem anderen Paket. Uebernahme abgelehnt."
                else "The verified file belongs to a different package. Import refused."
            FirmwareImportDecision.HASH_NOT_VERIFIED ->
                if (language == AppLanguage.DE)
                    "Kein verifizierter SHA-256 fuer dieses Paket. Uebernahme abgelehnt."
                else "No verified SHA-256 for this package. Import refused."
            FirmwareImportDecision.MODEL_MISMATCH ->
                if (language == AppLanguage.DE)
                    "Modellbezug der Datei ist nicht belegt. Uebernahme abgelehnt."
                else "Model assignment of the file is not proven. Import refused."
        }
}
