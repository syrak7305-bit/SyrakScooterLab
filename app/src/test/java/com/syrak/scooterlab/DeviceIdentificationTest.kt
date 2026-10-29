package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* ============================================================================
 *  PHASE 8 — Tests fuer die Geraete-Identifikation
 *  ---------------------------------------------------------------------------
 *  Abgedeckt:
 *    • Hersteller-Erkennung (NAVEE, Xiaomi, Segway/Ninebot, KuKirin, SoFlow)
 *    • Trennung Hersteller / Modell / Sicherheit
 *    • Scan-Kandidatenfilter (inkl. "Unknown Device" und Nicht-Scooter)
 *    • Tuning-Sicherheitssperre
 *
 *  Das sind REINE Logiktests. Sie beweisen NICHT, dass ein Geraet mit diesem
 *  Namen tatsaechlich dieses Protokoll spricht — das braucht Hardware.
 * ==========================================================================*/
class DeviceIdentificationTest {

    /* ---------------------------- Hersteller ---------------------------- */

    @Test
    fun `navee name maps to navee manufacturer`() {
        assertEquals(ScooterManufacturer.NAVEE, ScooterManufacturer.classify("NAVEE GT3 Max"))
        assertEquals(ScooterManufacturer.NAVEE, ScooterManufacturer.classify("navee-1234"))
    }

    @Test
    fun `xiaomi names map to xiaomi`() {
        assertEquals(ScooterManufacturer.XIAOMI, ScooterManufacturer.classify("Mi Scooter Pro 2"))
        assertEquals(ScooterManufacturer.XIAOMI, ScooterManufacturer.classify("M365"))
        assertEquals(ScooterManufacturer.XIAOMI, ScooterManufacturer.classify("Xiaomi 1S"))
    }

    @Test
    fun `kukirin is recognised as manufacturer`() {
        assertEquals(ScooterManufacturer.KUKIRIN, ScooterManufacturer.classify("KuKirin G2 Max"))
        assertEquals(ScooterManufacturer.KUKIRIN, ScooterManufacturer.classify("KUKIRIN"))
    }

    @Test
    fun `soflow is recognised as manufacturer`() {
        assertEquals(ScooterManufacturer.SOFLOW, ScooterManufacturer.classify("SoFlow SO4 Pro"))
    }

    @Test
    fun `segway ninebot resolves to ninebot with documented precedence`() {
        // "Segway Ninebot Max G30" enthaelt beide Tokens -> Ninebot hat Vorrang.
        assertEquals(ScooterManufacturer.NINEBOT, ScooterManufacturer.classify("Segway Ninebot Max G30"))
        // "Segway" allein bleibt Segway.
        assertEquals(ScooterManufacturer.SEGWAY, ScooterManufacturer.classify("Segway ES2"))
    }

    @Test
    fun `unknown and blank names yield unknown manufacturer`() {
        assertEquals(ScooterManufacturer.UNKNOWN, ScooterManufacturer.classify(null))
        assertEquals(ScooterManufacturer.UNKNOWN, ScooterManufacturer.classify(""))
        assertEquals(ScooterManufacturer.UNKNOWN, ScooterManufacturer.classify("   "))
        assertEquals(ScooterManufacturer.UNKNOWN, ScooterManufacturer.classify("JBL Flip 5"))
        assertEquals(ScooterManufacturer.UNKNOWN, ScooterManufacturer.classify("Unknown Device"))
    }

    @Test
    fun `manufacturer isKnown flag is honest`() {
        assertTrue(ScooterManufacturer.NAVEE.isKnown)
        assertTrue(ScooterManufacturer.KUKIRIN.isKnown)
        assertFalse(ScooterManufacturer.UNKNOWN.isKnown)
        assertTrue(ScooterManufacturer.UNKNOWN.displayName.isNotBlank())
    }

    /* ------------------ Hersteller / Modell / Sicherheit ---------------- */

    @Test
    fun `known model exposes its manufacturer`() {
        assertEquals(ScooterManufacturer.NAVEE, ScooterModel.NAVEE_GT3_MAX.manufacturer)
        assertEquals(ScooterManufacturer.XIAOMI, ScooterModel.XIAOMI_M365.manufacturer)
        assertEquals(ScooterManufacturer.NINEBOT, ScooterModel.NINEBOT_MAX_G30.manufacturer)
        assertEquals(ScooterManufacturer.SOFLOW, ScooterModel.SOFLOW_SO4.manufacturer)
        assertEquals(ScooterManufacturer.UNKNOWN, ScooterModel.UNKNOWN.manufacturer)
    }

    @Test
    fun `detection result carries manufacturer for known model`() {
        val r = ScooterModel.detectDetailed("Navee GT3 Max", "")
        assertEquals(ScooterModel.NAVEE_GT3_MAX, r.model)
        assertEquals(ScooterManufacturer.NAVEE, r.manufacturer)
        assertEquals(DetectionConfidence.CONFIRMED, r.confidence)
    }

    @Test
    fun `unknown model can still have a known manufacturer`() {
        // KuKirin ist als Marke bekannt, aber es gibt (bewusst) kein
        // erfundenes KuKirin-Modell/Protokoll -> Modell UNKNOWN, Marke KuKirin.
        val r = ScooterModel.detectDetailed("KuKirin G2 Max", "")
        assertEquals(ScooterModel.UNKNOWN, r.model)
        assertEquals(ScooterManufacturer.KUKIRIN, r.manufacturer)
        assertEquals(DetectionConfidence.UNKNOWN, r.confidence)
    }

    @Test
    fun `kukirin g2 max is not misidentified as ninebot max g2`() {
        // PHASE 8 BUGFIX: "KuKirin G2 Max" enthaelt das generische Token "g2"
        // und wurde vor dem Fix als Segway-Ninebot Max G2 eingestuft.
        val r = ScooterModel.detectDetailed("KuKirin G2 Max", "")
        assertEquals(ScooterModel.UNKNOWN, r.model)
        assertEquals(ScooterManufacturer.KUKIRIN, r.manufacturer)
        assertNotEquals(ScooterModel.NINEBOT_MAX_G2, r.model)
    }

    @Test
    fun `bare navee name stays unknown model but navee manufacturer`() {
        val r = ScooterModel.detectDetailed("NAVEE", "")
        assertEquals(ScooterModel.UNKNOWN, r.model)
        assertEquals(ScooterManufacturer.NAVEE, r.manufacturer)
    }

    /* ----------------------- Scan-Kandidatenfilter ---------------------- */

    @Test
    fun `known brands are scooter candidates`() {
        assertTrue(ScooterCandidateFilter.looksLikeScooter("NAVEE GT3 Max"))
        assertTrue(ScooterCandidateFilter.looksLikeScooter("Ninebot Max G30"))
        assertTrue(ScooterCandidateFilter.looksLikeScooter("Mi Scooter Pro 2"))
        assertTrue(ScooterCandidateFilter.looksLikeScooter("KuKirin G2 Max"))
        assertTrue(ScooterCandidateFilter.looksLikeScooter("SoFlow SO4"))
    }

    @Test
    fun `generic scooter token is a candidate`() {
        assertTrue(ScooterCandidateFilter.looksLikeScooter("MyScooter-42"))
        assertTrue(ScooterCandidateFilter.looksLikeScooter("E-Scooter"))
    }

    @Test
    fun `unknown device and blank names are not candidates`() {
        assertFalse(ScooterCandidateFilter.looksLikeScooter(null))
        assertFalse(ScooterCandidateFilter.looksLikeScooter(""))
        assertFalse(ScooterCandidateFilter.looksLikeScooter("   "))
        assertFalse(ScooterCandidateFilter.looksLikeScooter("Unknown Device"))
        assertFalse(ScooterCandidateFilter.looksLikeScooter("unknown device"))
    }

    @Test
    fun `unrelated devices are not candidates`() {
        assertFalse(ScooterCandidateFilter.looksLikeScooter("JBL Flip 5"))
        assertFalse(ScooterCandidateFilter.looksLikeScooter("Galaxy Buds"))
        assertFalse(ScooterCandidateFilter.looksLikeScooter("MI Band 6"))
    }

    /* ------------------------- Tuning-Sicherheit ------------------------ */

    @Test
    fun `tuning is blocked by default`() {
        assertFalse(TuningSafetyGate.canSendTuning(hardwareVerified = false, modelKnown = true))
        assertFalse(TuningSafetyGate.canSendTuning(hardwareVerified = true, modelKnown = false))
        assertFalse(TuningSafetyGate.canSendTuning(hardwareVerified = false, modelKnown = false))
    }

    @Test
    fun `tuning allowed only when verified and model known`() {
        assertTrue(TuningSafetyGate.canSendTuning(hardwareVerified = true, modelKnown = true))
    }

    @Test
    fun `block reason explains the lock`() {
        val unknownModel = TuningSafetyGate.blockReason(hardwareVerified = true, modelKnown = false)
        assertNotNull(unknownModel)
        assertTrue(unknownModel!!.contains("Modell"))

        val unverified = TuningSafetyGate.blockReason(hardwareVerified = false, modelKnown = true)
        assertNotNull(unverified)
        assertTrue(unverified!!.contains("Hardware"))

        assertNull(TuningSafetyGate.blockReason(hardwareVerified = true, modelKnown = true))
    }
}
