package com.syrak.scooterlab.core.protocol

import timber.log.Timber
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
 * transport. The default provider returns a documented placeholder key and MUST
 * be replaced with a verified derivation before use on real hardware.
 */
class NinebotAuth(
    private val keyProvider: KeyProvider = KeyProvider.Default,
) {

    fun interface KeyProvider {
        fun keyFor(serial: String): ByteArray

        companion object {
            /** Placeholder — replace with the verified per-serial derivation. */
            val Default = KeyProvider { _ ->
                byteArrayOf(
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                )
            }
        }
    }

    fun computeResponse(challenge: ByteArray, serial: String): ByteArray {
        require(challenge.size == 16) { "Challenge must be 16 bytes (got ${challenge.size})" }
        val key = keyProvider.keyFor(serial)
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(challenge)
        } catch (e: Exception) {
            Timber.e(e, "Auth response computation failed")
            ByteArray(16)
        }
    }
}
