package com.syrak.scooterlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* ============================================================================
 *  PHASE 7 — Tests fuer die PIN-Krypto (Wegfahrsperre)
 *  ---------------------------------------------------------------------------
 *  Bis Phase 7 gab es KEINE automatisierten Tests fuer die PIN-Verifikation:
 *  die PBKDF2-Logik lag privat in [ScooterRepository] und brauchte einen
 *  Android-Context. Mit [PinHasher] ist die reine Logik jetzt testbar.
 *
 *  Abgedeckt werden die vom Auftrag geforderten Faelle:
 *    • korrekte PIN, falsche PIN, geaenderte PIN
 *    • ungueltige Eingabe (leer, Blank, sehr lang) und Grenzfaelle
 *    • Salt-Zufaelligkeit und deterministische Ableitung
 *    • konstantzeitiger Vergleich
 *    • KEIN Klartext-Leck (derive liefert nie die PIN selbst)
 *
 *  Diese Tests sind REINE Logiktests. Sie beweisen NICHT, dass die Wegfahrsperre
 *  auf einem echten NAVEE GT3 Max funktioniert — das braucht Hardware.
 * ==========================================================================*/
class PinHasherTest {

    // --- Korrekte / falsche PIN --------------------------------------------

    @Test
    fun `correct pin verifies`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.derive("1234", salt)
        assertTrue(PinHasher.verify("1234", salt, hash))
    }

    @Test
    fun `wrong pin does not verify`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.derive("1234", salt)
        assertFalse(PinHasher.verify("1235", salt, hash))
        assertFalse(PinHasher.verify("", salt, hash))
        assertFalse(PinHasher.verify("12345", salt, hash))
    }

    @Test
    fun `changed pin invalidates the old pin`() {
        // Erste PIN setzen.
        val salt1 = PinHasher.newSalt()
        val hash1 = PinHasher.derive("1111", salt1)
        assertTrue(PinHasher.verify("1111", salt1, hash1))

        // Neue PIN setzen (frisches Salt, neuer Hash).
        val salt2 = PinHasher.newSalt()
        val hash2 = PinHasher.derive("2222", salt2)
        assertTrue(PinHasher.verify("2222", salt2, hash2))

        // Die alte PIN darf gegen den neuen Datensatz NICHT mehr gelten.
        assertFalse(PinHasher.verify("1111", salt2, hash2))
        // Und das alte Salt/Hash-Paar bleibt konsistent fuer die alte PIN.
        assertTrue(PinHasher.verify("1111", salt1, hash1))
    }

    // --- Ungueltige Eingabe / Grenzfaelle ----------------------------------

    @Test
    fun `empty and blank pins are handled deterministically`() {
        val salt = PinHasher.newSalt()
        val emptyHash = PinHasher.derive("", salt)
        val blankHash = PinHasher.derive("   ", salt)
        // Leere PIN verifiziert gegen ihren eigenen Hash ...
        assertTrue(PinHasher.verify("", salt, emptyHash))
        // ... aber nicht gegen den Blank-Hash (und umgekehrt).
        assertFalse(PinHasher.verify("", salt, blankHash))
        assertFalse(PinHasher.verify("   ", salt, emptyHash))
    }

    @Test
    fun `very long pin is supported`() {
        val salt = PinHasher.newSalt()
        val longPin = "9".repeat(4096)
        val hash = PinHasher.derive(longPin, salt)
        assertTrue(PinHasher.verify(longPin, salt, hash))
        assertFalse(PinHasher.verify(longPin.dropLast(1), salt, hash))
    }

    @Test
    fun `empty expected hash never verifies`() {
        val salt = PinHasher.newSalt()
        assertFalse(PinHasher.verify("1234", salt, ByteArray(0)))
    }

    @Test
    fun `non positive iterations never verify`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.derive("1234", salt)
        assertFalse(PinHasher.verify("1234", salt, hash, iterations = 0))
        assertFalse(PinHasher.verify("1234", salt, hash, iterations = -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `derive rejects non positive iterations`() {
        PinHasher.derive("1234", PinHasher.newSalt(), iterations = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `derive rejects non positive key length`() {
        PinHasher.derive("1234", PinHasher.newSalt(), keyLen = 0)
    }

    // --- Salt / Determinismus / Schluessellaenge ---------------------------

    @Test
    fun `salt has expected length and is random`() {
        val a = PinHasher.newSalt()
        val b = PinHasher.newSalt()
        assertEquals(PinHasher.SALT_BYTES, a.size)
        assertEquals(PinHasher.SALT_BYTES, b.size)
        // Zwei frische Salts duerfen praktisch nie gleich sein.
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `derivation is deterministic for same salt`() {
        val salt = PinHasher.newSalt()
        val h1 = PinHasher.derive("4321", salt)
        val h2 = PinHasher.derive("4321", salt)
        assertArrayEquals(h1, h2)
    }

    @Test
    fun `different salt yields different hash`() {
        val h1 = PinHasher.derive("4321", PinHasher.newSalt())
        val h2 = PinHasher.derive("4321", PinHasher.newSalt())
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `key length is 32 bytes by default`() {
        val hash = PinHasher.derive("1234", PinHasher.newSalt())
        assertEquals(PinHasher.KEY_LENGTH_BYTES, hash.size)
        assertEquals(32, hash.size)
    }

    // --- Konstantzeitiger Vergleich ----------------------------------------

    @Test
    fun `constant time equals handles equal content`() {
        assertTrue(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `constant time equals detects different content`() {
        assertFalse(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(PinHasher.constantTimeEquals(byteArrayOf(0), byteArrayOf(1)))
    }

    @Test
    fun `constant time equals detects different length`() {
        assertFalse(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(PinHasher.constantTimeEquals(ByteArray(0), byteArrayOf(1)))
    }

    // --- Kein Klartext-Leck ------------------------------------------------

    @Test
    fun `derived hash never equals the plaintext pin`() {
        val pin = "0815"
        val hash = PinHasher.derive(pin, PinHasher.newSalt())
        assertFalse(hash.contentEquals(pin.toByteArray()))
        assertFalse(String(hash, Charsets.ISO_8859_1).contains(pin))
    }
}
