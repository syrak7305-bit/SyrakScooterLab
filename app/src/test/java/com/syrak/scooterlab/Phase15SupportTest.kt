package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/* ============================================================================
 *  PHASE 15 — Tests fuer die ehrliche Firmware-Logik.
 *  Reine JVM-Tests, keine Android-Abhaengigkeit, keine erfundenen Daten.
 * ==========================================================================*/
class Phase15SupportTest {

    private fun pkg(
        id: String = "p1",
        name: String = "Test Paket",
        version: String = "1.0",
        target: String = "DRV",
        sourceUrl: String = "Referenz: Test (kein offizieller Endpunkt)",
        maxKmh: Int = 20,
        notes: String = "Test",
        expectedSha256: String? = null,
        downloadUrl: String? = null
    ) = FirmwarePackage(id, name, version, target, sourceUrl, maxKmh, notes, expectedSha256, downloadUrl)

    // ---- FirmwareLifecycle -------------------------------------------------

    @Test
    fun `empty evidence reaches nothing`() {
        val report = FirmwareLifecycle.evaluate(FirmwareEvidence())
        assertNull(report.highest)
        assertEquals(FirmwareStage.ordered(), report.missing)
        assertTrue(report.stages.none { it.reached })
    }

    @Test
    fun `detected only reaches first stage`() {
        val report = FirmwareLifecycle.evaluate(FirmwareEvidence(modelDetected = true))
        assertEquals(FirmwareStage.DETECTED, report.highest)
        assertTrue(report.reached(FirmwareStage.DETECTED))
        assertFalse(report.reached(FirmwareStage.PROPOSED))
    }

    @Test
    fun `cumulative stages stop at first gap`() {
        val report = FirmwareLifecycle.evaluate(
            FirmwareEvidence(
                modelDetected = true,
                suggestionsShown = true,
                packageObtained = false,
                integrityChecked = true,
                modelVerified = true,
                flashed = true
            )
        )
        assertEquals(FirmwareStage.PROPOSED, report.highest)
        assertFalse(report.reached(FirmwareStage.OBTAINED))
        assertFalse(report.reached(FirmwareStage.FLASHED))
    }

    @Test
    fun `flashed without detected is downgraded to nothing`() {
        val report = FirmwareLifecycle.evaluate(FirmwareEvidence(flashed = true))
        assertNull(report.highest)
    }

    @Test
    fun `full evidence reaches flashed`() {
        val report = FirmwareLifecycle.evaluate(
            FirmwareEvidence(
                modelDetected = true,
                suggestionsShown = true,
                packageObtained = true,
                integrityChecked = true,
                modelVerified = true,
                flashed = true
            )
        )
        assertEquals(FirmwareStage.FLASHED, report.highest)
        assertTrue(report.missing.isEmpty())
    }

    @Test
    fun `stage labels exist in both languages`() {
        for (stage in FirmwareStage.ordered()) {
            assertTrue(stage.label(AppLanguage.DE).isNotBlank())
            assertTrue(stage.label(AppLanguage.EN).isNotBlank())
        }
    }

    // ---- FirmwareSuggestions ----------------------------------------------

    @Test
    fun `unknown model yields no suggestions`() {
        val list = FirmwareSuggestions.build(ScooterModel.UNKNOWN, listOf(pkg()))
        assertTrue(list.isEmpty())
    }

    @Test
    fun `known model maps packages one to one`() {
        val packages = listOf(pkg(id = "a"), pkg(id = "b"))
        val list = FirmwareSuggestions.build(ScooterModel.NAVEE_GT3_MAX, packages)
        assertEquals(2, list.size)
        assertEquals("a", list[0].packageId)
        assertEquals("Navee GT3 Max", list[0].targetModel)
    }

    @Test
    fun `package without hash is unverified`() {
        assertEquals(PackageVerification.UNVERIFIED, FirmwareSuggestions.verificationOf(pkg()))
        assertFalse(FirmwareSuggestions.verificationOf(pkg()).allowsHardFlash)
    }

    @Test
    fun `package with hash is only hash present`() {
        val p = pkg(expectedSha256 = "abc")
        assertEquals(PackageVerification.HASH_PRESENT, FirmwareSuggestions.verificationOf(p))
        assertFalse(FirmwareSuggestions.verificationOf(p).allowsHardFlash)
    }

    @Test
    fun `verification after check requires hash and model`() {
        assertEquals(
            PackageVerification.MODEL_VERIFIED,
            FirmwareSuggestions.verificationAfterCheck("abc", hashVerified = true, modelCompatible = true)
        )
        assertEquals(
            PackageVerification.HASH_VERIFIED,
            FirmwareSuggestions.verificationAfterCheck("abc", hashVerified = true, modelCompatible = false)
        )
        assertEquals(
            PackageVerification.HASH_PRESENT,
            FirmwareSuggestions.verificationAfterCheck("abc", hashVerified = false, modelCompatible = true)
        )
        assertEquals(
            PackageVerification.UNVERIFIED,
            FirmwareSuggestions.verificationAfterCheck(null, hashVerified = false, modelCompatible = true)
        )
    }

    @Test
    fun `suggestion exposes download link only when present`() {
        val withLink = FirmwareSuggestions.build(
            ScooterModel.NAVEE_GT3_MAX,
            listOf(pkg(downloadUrl = "https://example.invalid/fw.bin"))
        ).first()
        assertTrue(withLink.hasDownloadLink)

        val without = FirmwareSuggestions.build(ScooterModel.NAVEE_GT3_MAX, listOf(pkg())).first()
        assertFalse(without.hasDownloadLink)
        assertNull(without.downloadUrl)
    }

    // ---- FirmwareStorage ---------------------------------------------------

    @Test
    fun `storage saves lists and hashes`() {
        val base = File(System.getProperty("java.io.tmpdir"), "syrak-test-${System.nanoTime()}")
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val file = FirmwareStorage.save(base, "navee/gt3 max", bytes)
            assertNotNull(file)
            assertTrue(file!!.exists())
            assertEquals("navee_gt3_max.bin", file.name)
            assertEquals(1, FirmwareStorage.list(base).size)
            assertEquals(FirmwareValidator.sha256Hex(bytes), FirmwareStorage.storedHash(file))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `storage rejects empty payload`() {
        val base = File(System.getProperty("java.io.tmpdir"), "syrak-test-${System.nanoTime()}")
        try {
            assertNull(FirmwareStorage.save(base, "x", ByteArray(0)))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `storage rejects oversized payload`() {
        val base = File(System.getProperty("java.io.tmpdir"), "syrak-test-${System.nanoTime()}")
        try {
            assertNull(FirmwareStorage.save(base, "x", ByteArray(512 * 1024 + 1)))
        } finally {
            base.deleteRecursively()
        }
    }

    // ---- FlashHonesty ------------------------------------------------------

    @Test
    fun `status line distinguishes obtained from flashed`() {
        val obtained = FlashHonesty.statusLine(
            FirmwareEvidence(
                modelDetected = true,
                suggestionsShown = true,
                packageObtained = true
            )
        )
        assertTrue(obtained.contains("KEIN Flash"))

        val flashed = FlashHonesty.statusLine(
            FirmwareEvidence(
                modelDetected = true,
                suggestionsShown = true,
                packageObtained = true,
                integrityChecked = true,
                modelVerified = true,
                flashed = true
            )
        )
        assertTrue(flashed.contains("abgeschlossen"))
    }

    @Test
    fun `status line without model is honest`() {
        val text = FlashHonesty.statusLine(FirmwareEvidence())
        assertTrue(text.contains("Kein Modell"))
    }

    @Test
    fun `hard flash requires verified hash and model`() {
        val good = FirmwareValidation(
            valid = true,
            message = "ok",
            sha256 = "abc",
            isZip = false,
            modelCompatible = true,
            extractedBinary = byteArrayOf(1),
            hashVerified = true
        )
        assertTrue(FlashHonesty.hardFlashAllowed(good, modelKnown = true, connected = true))
        assertFalse(FlashHonesty.hardFlashAllowed(good.copy(hashVerified = false), true, true))
        assertFalse(FlashHonesty.hardFlashAllowed(good.copy(modelCompatible = false), true, true))
        assertFalse(FlashHonesty.hardFlashAllowed(good, modelKnown = false, connected = true))
        assertFalse(FlashHonesty.hardFlashAllowed(good, modelKnown = true, connected = false))
        assertFalse(FlashHonesty.hardFlashAllowed(null, modelKnown = true, connected = true))
    }

    // ---- Katalogstand ------------------------------------------------------

    @Test
    fun `catalog date older than twelve months is outdated`() {
        assertTrue(CountryNoticeTexts.catalogOutdated("2024-10", 2026, 3))
        assertFalse(CountryNoticeTexts.catalogOutdated("2024-10", 2025, 6))
        assertTrue(CountryNoticeTexts.catalogOutdated("kaputt", 2025, 1))
    }

    @Test
    fun `outdated banner exists in both languages`() {
        assertTrue(CountryNoticeTexts.outdatedBanner(AppLanguage.DE, "2024-10").contains("veraltet"))
        assertTrue(CountryNoticeTexts.outdatedBanner(AppLanguage.EN, "2024-10").contains("outdated"))
    }
}
