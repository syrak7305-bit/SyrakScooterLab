package com.syrak.scooterlab

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/* ============================================================================
 *  FIRMWARE-VALIDATOR (reine Logik, ohne Android-/Activity-Abhaengigkeit)
 *  ---------------------------------------------------------------------------
 *  Bewusst KONSERVATIV: Ein Hard-Flash ist nur dann freigegeben, wenn
 *    (a) die Datei strukturell plausibel ist (Groesse, optional ZIP -> .bin),
 *    (b) ein Modellbezug im Datei-/Katalognamen belegt ist UND
 *    (c) ein erwarteter SHA-256 vorhanden ist und uebereinstimmt.
 *
 *  Ist kein erwarteter Hash hinterlegt, bleibt das Ergebnis "struktur gueltig,
 *  Hash NICHT verifiziert" -> [FirmwareValidation.hashVerified] = false und
 *  damit der Hard-Flash gesperrt. Es werden KEINE Hashes/URLs erfunden.
 * ==========================================================================*/
object FirmwareValidator {

    /** Harte Obergrenze fuer Firmware-Dateien (512 KiB, wie in der Activity). */
    const val MAX_FIRMWARE_BYTES = 512 * 1024

    /** Erste zwei Bytes eines ZIP-Containers ("PK"). */
    private const val ZIP_MAGIC_0 = 0x50
    private const val ZIP_MAGIC_1 = 0x4B

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == ZIP_MAGIC_0.toByte() && bytes[1] == ZIP_MAGIC_1.toByte()

    /**
     * Prueft eine Firmware-Datei.
     *
     * @param bytes           Rohinhalt der Datei.
     * @param fileName        Anzeigename der Datei (fuer Modellbezug).
     * @param catalogName     Name des Katalogeintrags (optional, fuer Modellbezug).
     * @param model           Erkanntes Modell (UNKNOWN -> kein Modellbezug).
     * @param expectedSha256  Erwarteter Hash aus dem Katalog oder null.
     */
    fun validate(
        bytes: ByteArray,
        fileName: String,
        catalogName: String?,
        model: ScooterModel,
        expectedSha256: String?
    ): FirmwareValidation {
        val sha = sha256Hex(bytes)
        val zip = isZip(bytes)
        val binary = if (zip) extractFirstBinary(bytes) else bytes
        val sizeOk = binary != null && binary.isNotEmpty() && binary.size <= MAX_FIRMWARE_BYTES

        val modelTokens = if (model.isKnown) {
            model.matchKeywords.filter { it.length >= 4 }
        } else emptyList()
        val haystack = (fileName + " " + (catalogName ?: "")).lowercase(Locale.ROOT)
        val modelCompatible = model.isKnown && modelTokens.any { haystack.contains(it) }

        val hashVerified = expectedSha256 != null &&
                expectedSha256.equals(sha, ignoreCase = true)

        val message = when {
            !sizeOk -> "Datei leer oder groesser als ${MAX_FIRMWARE_BYTES / 1024} KiB."
            expectedSha256 != null && !hashVerified ->
                "SHA-256 stimmt nicht mit dem hinterlegten Wert ueberein."
            !modelCompatible ->
                "Modellkompatibilitaet nicht belegt (erkannt: ${model.displayName})."
            expectedSha256 == null ->
                "Gueltige Struktur, aber SHA-256 ist NICHT verifiziert. Hard-Flash bleibt gesperrt."
            else -> "Struktur, Hash und Modellbezug geprueft."
        }
        val valid = sizeOk && modelCompatible && (expectedSha256 == null || hashVerified)
        return FirmwareValidation(
            valid = valid,
            message = message,
            sha256 = sha,
            isZip = zip,
            modelCompatible = modelCompatible,
            extractedBinary = binary,
            hashVerified = hashVerified
        )
    }

    /** Erstes nicht-leeres `.bin`-Entry aus einem ZIP, gedeckelt auf [MAX_FIRMWARE_BYTES]. */
    fun extractFirstBinary(zipBytes: ByteArray): ByteArray? {
        return try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".bin")) {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_FIRMWARE_BYTES) return null
                            output.write(buffer, 0, read)
                        }
                        val data = output.toByteArray()
                        if (data.isNotEmpty()) return data
                    }
                    entry = zip.nextEntry
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
