package com.syrak.scooterlab

import com.syrak.scooterlab.core.protocol.NinebotAuth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Guards the FAIL-CLOSED behaviour of the Ninebot challenge/response auth.
 *
 * Vorher (Bug): der Default-Provider lieferte einen Null-Schlüssel und im
 * Fehlerfall wurde eine Null-Antwort (`ByteArray(16)`) zurückgegeben — eine
 * ungültige Auth konnte so als scheinbar gültig durchgehen. Diese Tests
 * stellen sicher, dass ohne verifizierten Schlüssel bzw. bei Fehlern LAUT
 * abgebrochen wird und dass mit einem gültigen Schlüssel deterministisch
 * AES-128-ECB gerechnet wird.
 */
class NinebotAuthTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `default provider fails closed instead of returning a zero response`() {
        val auth = NinebotAuth()
        val challenge = ByteArray(16)
        assertThrows(UnsupportedOperationException::class.java) {
            auth.computeResponse(challenge, "SERIAL-123")
        }
    }

    @Test
    fun `valid key produces the deterministic AES-128-ECB NIST vector`() {
        // NIST FIPS-197 AES-128 Testvektor
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = hex("00112233445566778899aabbccddeeff")
        val expected = hex("69c4e0d86a7b0430d8cdb78070b4c55a")

        val auth = NinebotAuth(NinebotAuth.KeyProvider { key })
        val actual = auth.computeResponse(plaintext, "SERIAL-123")
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `challenge of wrong length is rejected`() {
        val auth = NinebotAuth(NinebotAuth.KeyProvider { ByteArray(16) })
        assertThrows(IllegalArgumentException::class.java) {
            auth.computeResponse(ByteArray(15), "SERIAL")
        }
    }

    @Test
    fun `key of wrong length is rejected`() {
        val auth = NinebotAuth(NinebotAuth.KeyProvider { ByteArray(8) })
        assertThrows(IllegalArgumentException::class.java) {
            auth.computeResponse(ByteArray(16), "SERIAL")
        }
    }
}
