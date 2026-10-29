package com.syrak.scooterlab.core.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Challenge/response authentication for newer Ninebot BLE modules.
 *
 * Flow:
 *   1. App requests a session; the BLE module returns a 16-byte challenge (nonce).
 *   2. App computes response = AES-128-ECB(challenge, key) where `key` is derived
 *      from the controller serial number (or a firmware default).
 *   3. App returns the response; the module unlocks register access for the session.
 *
 * The key-derivation algorithm is model/firmware specific. [KeyProvider] is
 * pluggable so a per-model strategy can be registered without touching the
 * transport.
 *
 * FAIL-CLOSED (Sicherheits-Fix):
 *   Es existiert KEINE verifizierte per-Serial-Schlüsselableitung. Der Default-
 *   Provider liefert daher absichtlich keinen Schlüssel, sondern wirft. Zuvor
 *   wurde ein Null-Schlüssel verwendet und im Fehlerfall sogar eine Null-Antwort
 *   (`ByteArray(16)`) zurückgegeben — das konnte eine ungültige Auth-Antwort als
 *   scheinbar "erfolgreich" verschleiern. Jetzt schlägt jede nicht belegte bzw.
 *   fehlerhafte Auth laut fehl, statt still ein falsches Ergebnis zu liefern.
 */
class NinebotAuth(
    private val keyProvider: KeyProvider = KeyProvider.Default,
) {

    fun interface KeyProvider {
        fun keyFor(serial: String): ByteArray

        companion object {
            /**
             * Fail-closed default: es gibt keinen belegten Schlüssel. Ein Aufruf
             * wirft, damit niemals eine erfundene Auth-Antwort gesendet wird.
             */
            val Default = KeyProvider { _ ->
                throw UnsupportedOperationException(
                    "Ninebot-Auth-Schlüsselableitung ist nicht verifiziert. " +
                        "Einen KeyProvider mit belegter Ableitung registrieren, " +
                        "bevor Auth gegen echte Hardware genutzt wird.",
                )
            }
        }
    }

    /**
     * Berechnet die AES-128-ECB-Antwort auf [challenge].
     *
     * @throws IllegalArgumentException bei falscher Challenge-/Schlüssellänge.
     * @throws UnsupportedOperationException wenn kein verifizierter Schlüssel
     *         verfügbar ist (Default-Provider).
     * @throws java.security.GeneralSecurityException bei einem Krypto-Fehler —
     *         bewusst NICHT abgefangen, damit der Aufrufer fail-closed bleibt.
     */
    fun computeResponse(challenge: ByteArray, serial: String): ByteArray {
        require(challenge.size == 16) { "Challenge must be 16 bytes (got ${challenge.size})" }
        val key = keyProvider.keyFor(serial)
        require(key.size == 16) { "AES-128 key must be 16 bytes (got ${key.size})" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(challenge)
    }
}
