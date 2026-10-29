package com.syrak.scooterlab

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/* ============================================================================
 *  PIN-HASHER
 *  ----------------------------------------------------------------------------
 *  Reine, Android-freie Krypto-Logik fuer die Wegfahrsperre (PIN).
 *
 *  WARUM DIESE KLASSE EXISTIERT (Phase 7):
 *  Die PBKDF2-Ableitung lag zuvor als private Methode direkt in
 *  [ScooterRepository]. Diese Klasse braucht einen Android-[Context] und war
 *  damit fuer schnelle JVM-Unit-Tests nicht erreichbar. Dadurch existierten
 *  bis Phase 7 KEINE automatisierten Tests fuer die PIN-Verifikation.
 *  Die Krypto ist hierher extrahiert (gleiches Muster wie [ProfileStore] und
 *  [ReconnectPolicy]) und damit ohne Android-Context testbar.
 *
 *  VERHALTEN UNVERAENDERT:
 *    • PBKDF2 mit HmacSHA256 (Fallback HmacSHA1), 120.000 Iterationen.
 *    • 16-Byte-Salt, 32-Byte-Schluessel.
 *    • Konstantzeitiger Vergleich des Hashes.
 *  Das gespeicherte Format (Base64-Salt + Base64-Hash + Iterationszahl) bleibt
 *  identisch. Die Klartext-PIN wird NIE zurueckgegeben, geloggt oder gespeichert.
 * ==========================================================================*/
object PinHasher {

    /** Laenge des zufaelligen Salts in Bytes. */
    const val SALT_BYTES = 16

    /** Standard-Iterationszahl fuer PBKDF2. */
    const val ITERATIONS = 120_000

    /** Laenge des abgeleiteten Schluessels in Bytes. */
    const val KEY_LENGTH_BYTES = 32

    /** Erzeugt ein frisches, kryptografisch zufaelliges Salt. */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Leitet aus [pin] und [salt] den PBKDF2-Schluessel ab.
     *
     * @throws IllegalArgumentException wenn [iterations] oder [keyLen] <= 0.
     */
    fun derive(
        pin: String,
        salt: ByteArray,
        iterations: Int = ITERATIONS,
        keyLen: Int = KEY_LENGTH_BYTES
    ): ByteArray {
        require(iterations > 0) { "iterations must be > 0" }
        require(keyLen > 0) { "keyLen must be > 0" }
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyLen * 8)
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: Exception) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        return factory.generateSecret(spec).encoded
    }

    /** Konstantzeitiger Byte-Vergleich (kein frueher Abbruch bei Differenz). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /**
     * Verifiziert [pin] gegen [expectedHash] (mit [salt] und [iterations]).
     *
     * Liefert false statt zu werfen bei: leerem [expectedHash], [iterations] <= 0
     * oder Fehlern in der Krypto-Engine. So kann ein korrupter Alt-Eintrag die
     * App nicht zum Absturz bringen.
     */
    fun verify(
        pin: String,
        salt: ByteArray,
        expectedHash: ByteArray,
        iterations: Int = ITERATIONS
    ): Boolean {
        if (expectedHash.isEmpty()) return false
        if (iterations <= 0) return false
        val actual = try {
            derive(pin, salt, iterations, expectedHash.size)
        } catch (_: Exception) {
            return false
        }
        return constantTimeEquals(expectedHash, actual)
    }
}
