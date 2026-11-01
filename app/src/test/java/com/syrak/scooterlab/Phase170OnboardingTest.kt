package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17.0 — Tests fuer Personalisiertes Onboarding & Scooter-Profile.
 *
 * Deckt die im Auftrag (Section 12) geforderten Faelle ab. Alle Tests laufen
 * gegen REINE Logik (kein Android-Context, kein Emulator) und damit in der
 * normalen JVM-Unit-Test-Suite.
 */
class Phase170OnboardingTest {

    private fun mapOfUser(u: UserProfile): Map<String, String> = ProfileCodec.encodeUser(u)
    private fun mapOfScooter(s: RiderScooterProfile): Map<String, String> =
        ProfileCodec.encodeScooter(s)

    // 1) Wizard wird beim ersten Start angezeigt.
    @Test
    fun `Wizard wird beim ersten Start angezeigt`() {
        val fresh = UserProfile()
        assertFalse(fresh.setupCompleted)
        assertTrue(OnboardingGate.shouldShowWizard(fresh))
    }

    // 2) Setup bleibt nach App-Neustart abgeschlossen (Persistenz-Roundtrip).
    @Test
    fun `Setup bleibt nach Neustart abgeschlossen`() {
        val done = UserProfile(displayName = "Alex", language = AppLanguage.DE,
            countryCode = "DE", setupCompleted = true)
        val restored = ProfileCodec.decodeUser(mapOfUser(done))
        assertTrue(restored.setupCompleted)
        assertFalse(OnboardingGate.shouldShowWizard(restored))
    }

    // 3) Unvollstaendiges Setup wird an der offenen Stelle fortgesetzt.
    @Test
    fun `Unvollstaendiges Setup wird fortgesetzt`() {
        val noName = UserProfile(displayName = "", setupCompleted = false)
        assertEquals(OnboardingGate.Step.USER,
            OnboardingGate.resumeStep(noName, RiderScooterProfile()))

        val namedNoBrand = UserProfile(displayName = "Alex", setupCompleted = false)
        assertEquals(OnboardingGate.Step.BRAND,
            OnboardingGate.resumeStep(namedNoBrand, RiderScooterProfile()))

        val brandNoModel = UserProfile(displayName = "Alex", setupCompleted = false)
        assertEquals(OnboardingGate.Step.MODEL,
            OnboardingGate.resumeStep(
                brandNoModel,
                RiderScooterProfile(brand = ScooterManufacturer.NINEBOT)
            ))

        val complete = UserProfile(displayName = "Alex", setupCompleted = false)
        assertEquals(OnboardingGate.Step.DONE,
            OnboardingGate.resumeStep(
                complete,
                RiderScooterProfile(
                    brand = ScooterManufacturer.NINEBOT,
                    model = ScooterModel.NINEBOT_F2_PRO
                )
            ))
    }

    // 4) Profilname wird korrekt gespeichert (Sanitizing + Roundtrip).
    @Test
    fun `Profilname wird korrekt gespeichert`() {
        assertEquals("Alex Meier", UserProfile.sanitizeName("  Alex   Meier  "))
        assertEquals("", UserProfile.sanitizeName(null))
        val u = UserProfile(displayName = "Alex Meier", setupCompleted = true)
        assertEquals("Alex Meier", ProfileCodec.decodeUser(mapOfUser(u)).displayName)
    }

    // 5) Sprache wird gespeichert und korrekt angewendet.
    @Test
    fun `Sprache wird gespeichert und angewendet`() {
        val de = UserProfile(language = AppLanguage.DE, setupCompleted = true)
        val en = UserProfile(language = AppLanguage.EN, setupCompleted = true)
        assertEquals(AppLanguage.DE, ProfileCodec.decodeUser(mapOfUser(de)).language)
        assertEquals(AppLanguage.EN, ProfileCodec.decodeUser(mapOfUser(en)).language)
        // LanguageResolver bleibt die bestehende Quelle fuer den Fallback.
        assertEquals(AppLanguage.DE, LanguageResolver.resolve("de-AT", "AT"))
        assertEquals(AppLanguage.EN, LanguageResolver.resolve("fr-FR", "FR"))
    }

    // 6) Marke und Modell werden korrekt gespeichert.
    @Test
    fun `Marke und Modell werden korrekt gespeichert`() {
        val s = RiderScooterProfile(
            brand = ScooterManufacturer.NAVEE,
            model = ScooterModel.NAVEE_GT3_MAX,
            modelCode = "GT3M",
            serialNumber = "SN123",
            identificationConfidence = DetectionConfidence.PROBABLE
        )
        val restored = ProfileCodec.decodeScooter(mapOfScooter(s))
        assertEquals(ScooterManufacturer.NAVEE, restored.brand)
        assertEquals(ScooterModel.NAVEE_GT3_MAX, restored.model)
        assertEquals("GT3M", restored.modelCode)
        assertEquals("SN123", restored.serialNumber)
    }

    // 7) Modellwechsel aktualisiert die verfuegbaren Module.
    @Test
    fun `Modellwechsel aktualisiert verfuegbare Module`() {
        val known = RiderScooterProfile(
            brand = ScooterManufacturer.NINEBOT,
            model = ScooterModel.NINEBOT_F2_PRO
        )
        val unknown = RiderScooterProfile(
            brand = ScooterManufacturer.NINEBOT,
            model = ScooterModel.UNKNOWN,
            modelNotListed = true
        )
        assertTrue(ScooterFeatureRegistry.usableFeatures(known).contains(AppFeature.LIVE_TELEMETRY))
        assertFalse(ScooterFeatureRegistry.usableFeatures(unknown).contains(AppFeature.LIVE_TELEMETRY))
        assertTrue(ScooterFeatureRegistry.usableFeatures(unknown).contains(AppFeature.GATT_DIAGNOSIS))
    }

    // 8) Unbekannte Modelle bleiben sicher (keine Schreib-/Flash-Freigabe).
    @Test
    fun `Unbekannte Modelle bleiben sicher`() {
        val unknown = RiderScooterProfile(
            brand = ScooterManufacturer.UNKNOWN,
            model = ScooterModel.UNKNOWN,
            modelNotListed = true
        )
        val avail = ScooterFeatureRegistry.availability(unknown).associateBy { it.feature }
        assertEquals(FeatureStatus.UNAVAILABLE, avail[AppFeature.TUNING]!!.status)
        assertEquals(FeatureStatus.UNAVAILABLE, avail[AppFeature.FIRMWARE_FLASH]!!.status)
        assertEquals(FeatureStatus.UNAVAILABLE, avail[AppFeature.LIVE_TELEMETRY]!!.status)
        // Nur die generische BLE-Diagnose ist sicher nutzbar.
        assertTrue(ScooterFeatureRegistry.usableFeatures(unknown).contains(AppFeature.GATT_DIAGNOSIS))
        assertFalse(ScooterFeatureRegistry.usableFeatures(unknown).contains(AppFeature.TUNING))
    }

    // 9) Marken werden nicht verwechselt.
    @Test
    fun `Marken werden nicht verwechselt`() {
        // NAVEE-Modelle enthalten kein Ninebot-Modell.
        assertFalse(ScooterBrandModels.modelsForBrand(ScooterManufacturer.NAVEE)
            .contains(ScooterModel.NINEBOT_F2_PRO))
        // Ein Ninebot-Modell gehoert NICHT zu NAVEE/Xiaomi.
        assertFalse(ScooterBrandModels.modelBelongsToBrand(
            ScooterModel.NINEBOT_F2_PRO, ScooterManufacturer.NAVEE))
        assertFalse(ScooterBrandModels.modelBelongsToBrand(
            ScooterModel.NINEBOT_F2_PRO, ScooterManufacturer.XIAOMI))
        // Segway-Ninebot ist derselbe Herstellerverbund.
        assertTrue(ScooterBrandModels.modelBelongsToBrand(
            ScooterModel.NINEBOT_F2_PRO, ScooterManufacturer.SEGWAY))
        // Inkonsistente Kombination -> effektiv unbekannt.
        assertEquals(ScooterModel.UNKNOWN, RiderScooterProfile(
            brand = ScooterManufacturer.NAVEE,
            model = ScooterModel.NINEBOT_F2_PRO
        ).effectiveModel())
    }

    // 9b) Alle geforderten Marken sind waehlbar (inkl. INMOTION).
    @Test
    fun `Alle geforderten Marken sind waehlbar`() {
        val options = ScooterBrandModels.brandOptions()
        listOf(
            ScooterManufacturer.NINEBOT, ScooterManufacturer.SEGWAY,
            ScooterManufacturer.XIAOMI, ScooterManufacturer.NAVEE,
            ScooterManufacturer.SOFLOW, ScooterManufacturer.KUKIRIN,
            ScooterManufacturer.INMOTION, ScooterManufacturer.UNKNOWN
        ).forEach { assertTrue("Marke fehlt: $it", options.contains(it)) }
        assertEquals(ScooterManufacturer.INMOTION, ScooterManufacturer.classify("InMotion V12"))
    }

    // 10) Alte Diagnosewerte werden NICHT dem neuen Scooter zugeschrieben.
    @Test
    fun `Alte Diagnosewerte werden nicht neuem Scooter zugeschrieben`() {
        val old = RiderScooterProfile(
            brand = ScooterManufacturer.NINEBOT, model = ScooterModel.NINEBOT_F2_PRO)
        val same = RiderScooterProfile(
            brand = ScooterManufacturer.NINEBOT, model = ScooterModel.NINEBOT_F2_PRO)
        val newModel = RiderScooterProfile(
            brand = ScooterManufacturer.NINEBOT, model = ScooterModel.NINEBOT_MAX_G30)
        val newBrand = RiderScooterProfile(
            brand = ScooterManufacturer.NAVEE, model = ScooterModel.NAVEE_GT3_MAX)

        assertFalse(ProfileSessionGuard.mustResetSession(old, same))
        assertTrue(ProfileSessionGuard.mustResetSession(old, newModel))
        assertTrue(ProfileSessionGuard.mustResetSession(old, newBrand))
        // Kein vorheriges Profil -> nichts zurueckzusetzen.
        assertFalse(ProfileSessionGuard.mustResetSession(null, newModel))
    }

    // 11) Bestehende BLE-/Adapter-Funktionen bleiben unveraendert nutzbar.
    @Test
    fun `Bestehende Adapter-Funktionen bleiben unveraendert`() {
        assertTrue(ProtocolAdapters.all.size >= 2)
        val ninebot = ProtocolAdapters.resolveForModel(ScooterModel.NINEBOT_F2_PRO)
        assertTrue(ninebot.exact)
        val navee = ProtocolAdapters.resolveForModel(ScooterModel.NAVEE_GT3_MAX)
        assertTrue(navee.exact)
        // Unbekanntes Modell liefert weiterhin KEINEN Adapter (keine Fake-Kompatibilitaet).
        val unknown = ProtocolAdapters.resolveForModel(ScooterModel.UNKNOWN)
        assertNull(unknown.adapter)
        assertFalse(unknown.exact)
        // Die zentrale UUID-Registry bleibt unveraendert (0 hardware-bestaetigt).
        assertEquals(0, BleUuids.confirmedOnHardwareCount())
    }

    // Zusatz: Codec ist robust gegen kaputte/fehlende Werte.
    @Test
    fun `Codec ist robust gegen kaputte Werte`() {
        val restored = ProfileCodec.decodeScooter(
            mapOf(
                ProfileCodec.KEY_BRAND to "GIBTS_NICHT",
                ProfileCodec.KEY_MODEL to "AUCH_NICHT",
                ProfileCodec.KEY_CONFIDENCE to "???"
            )
        )
        assertEquals(ScooterManufacturer.UNKNOWN, restored.brand)
        assertEquals(ScooterModel.UNKNOWN, restored.model)
        assertEquals(DetectionConfidence.UNKNOWN, restored.identificationConfidence)
        // Leere Map -> Defaults, kein Crash.
        assertEquals(UserProfile(), ProfileCodec.decodeUser(emptyMap()))
    }

    // Zusatz: Modell-Not-Listed erlaubt Abschluss auch ohne bekanntes Modell.
    @Test
    fun `Nicht gelistetes Modell erlaubt Abschluss`() {
        val p = RiderScooterProfile(
            brand = ScooterManufacturer.KUKIRIN,
            model = ScooterModel.UNKNOWN,
            modelNotListed = true
        )
        assertTrue(p.isComplete)
        assertEquals(ScooterModel.UNKNOWN, p.effectiveModel())
        assertTrue(ScooterBrandModels.modelsForBrand(ScooterManufacturer.KUKIRIN).isEmpty())
    }
}
