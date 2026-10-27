package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests fuer die Firmware-Pruefung. Wichtig: Es wird NIE geflasht und es werden
 * keine echten Firmware-Dateien benutzt — nur synthetische Byte-Arrays.
 */
class FirmwareValidatorTest {

    private fun zipWith(name: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `sha256 matches known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FirmwareValidator.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun `empty file is rejected`() {
        val v = FirmwareValidator.validate(
            ByteArray(0), "navee gt3 max.bin", null, ScooterModel.NAVEE_GT3_MAX, null
        )
        assertFalse(v.valid)
        assertTrue(v.message.contains("leer") || v.message.contains("groesser"))
    }

    @Test
    fun `unknown model is never model compatible`() {
        val v = FirmwareValidator.validate(
            ByteArray(64) { 1 }, "navee gt3 max.bin", null, ScooterModel.UNKNOWN, null
        )
        assertFalse(v.modelCompatible)
        assertFalse(v.valid)
    }

    @Test
    fun `missing hash means not verified and flash stays locked`() {
        val v = FirmwareValidator.validate(
            ByteArray(64) { 2 }, "navee gt3 max v1.bin", null, ScooterModel.NAVEE_GT3_MAX, null
        )
        assertTrue(v.modelCompatible)
        assertFalse(v.hashVerified)
        assertTrue(v.valid) // strukturell gueltig ...
        assertTrue(v.message.contains("NICHT verifiziert"))
    }

    @Test
    fun `wrong hash is rejected`() {
        val v = FirmwareValidator.validate(
            ByteArray(64) { 3 }, "navee gt3 max v1.bin", null,
            ScooterModel.NAVEE_GT3_MAX, "deadbeef"
        )
        assertFalse(v.hashVerified)
        assertFalse(v.valid)
    }

    @Test
    fun `correct hash is verified`() {
        val data = ByteArray(64) { 4 }
        val sha = FirmwareValidator.sha256Hex(data)
        val v = FirmwareValidator.validate(
            data, "navee gt3 max v1.bin", null, ScooterModel.NAVEE_GT3_MAX, sha.uppercase()
        )
        assertTrue(v.hashVerified)
        assertTrue(v.valid)
        assertFalse(v.isZip)
    }

    @Test
    fun `zip container is detected and bin extracted`() {
        val payload = ByteArray(128) { 5 }
        val zip = zipWith("firmware.bin", payload)
        val v = FirmwareValidator.validate(
            zip, "navee gt3 max v2.zip", null, ScooterModel.NAVEE_GT3_MAX, null
        )
        assertTrue(v.isZip)
        assertNotNull(v.extractedBinary)
        assertEquals(payload.size, v.extractedBinary!!.size)
    }

    @Test
    fun `zip without bin entry yields no binary`() {
        val zip = zipWith("readme.txt", "hello".toByteArray())
        val v = FirmwareValidator.validate(
            zip, "navee gt3 max v2.zip", null, ScooterModel.NAVEE_GT3_MAX, null
        )
        assertTrue(v.isZip)
        assertNull(v.extractedBinary)
        assertFalse(v.valid)
    }

    @Test
    fun `oversized payload is rejected`() {
        val big = ByteArray(FirmwareValidator.MAX_FIRMWARE_BYTES + 1) { 6 }
        val v = FirmwareValidator.validate(
            big, "navee gt3 max huge.bin", null, ScooterModel.NAVEE_GT3_MAX, null
        )
        assertFalse(v.valid)
    }

    @Test
    fun `model mismatch is rejected even with valid structure`() {
        val v = FirmwareValidator.validate(
            ByteArray(64) { 7 }, "ninebot max g30.bin", null, ScooterModel.NAVEE_GT3_MAX, null
        )
        assertFalse(v.modelCompatible)
        assertFalse(v.valid)
    }
}
