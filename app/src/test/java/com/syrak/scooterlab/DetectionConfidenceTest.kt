package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer die Vertrauensstufen der Modellerkennung.
 *
 * Ziel: Ein Geraet wird nur dann als "sicher erkannt" gemeldet, wenn der
 * BLE-Name ein eindeutiges, modellspezifisches Merkmal enthaelt. Alles andere
 * ist "vermutet" oder "unbekannt".
 */
class DetectionConfidenceTest {

    @Test
    fun `explicit GT3 Max is confirmed`() {
        val r = ScooterModel.detectDetailed("Navee GT3 Max", "")
        assertEquals(ScooterModel.NAVEE_GT3_MAX, r.model)
        assertEquals(DetectionConfidence.CONFIRMED, r.confidence)
        assertTrue(r.evidence.contains("gt3 max"))
    }

    @Test
    fun `bare NAVEE is unknown, not probable`() {
        val r = ScooterModel.detectDetailed("NAVEE", "")
        assertEquals(ScooterModel.UNKNOWN, r.model)
        assertEquals(DetectionConfidence.UNKNOWN, r.confidence)
    }

    @Test
    fun `empty and placeholder names are unknown`() {
        assertEquals(DetectionConfidence.UNKNOWN, ScooterModel.detectDetailed("", "").confidence)
        assertEquals(DetectionConfidence.UNKNOWN, ScooterModel.detectDetailed("   ", "").confidence)
        assertEquals(
            DetectionConfidence.UNKNOWN,
            ScooterModel.detectDetailed("Unknown Device", "").confidence
        )
    }

    @Test
    fun `short ambiguous token is only probable`() {
        // "s65" ist ein gueltiges Modellmerkmal, aber ohne Herstellerbezug nur vermutet.
        val r = ScooterModel.detectDetailed("S65", "")
        assertEquals(ScooterModel.NAVEE_S65, r.model)
        assertEquals(DetectionConfidence.PROBABLE, r.confidence)
    }

    @Test
    fun `manufacturer plus model token is confirmed`() {
        val r = ScooterModel.detectDetailed("Navee S65", "")
        assertEquals(ScooterModel.NAVEE_S65, r.model)
        assertEquals(DetectionConfidence.CONFIRMED, r.confidence)
    }

    @Test
    fun `GT3 Pro is never reported as GT3 Max`() {
        val r = ScooterModel.detectDetailed("Navee GT3 Pro", "")
        assertEquals(ScooterModel.NAVEE_GT3_PRO, r.model)
        assertEquals(DetectionConfidence.CONFIRMED, r.confidence)
    }

    @Test
    fun `detect delegates to detectDetailed`() {
        assertEquals(
            ScooterModel.detectDetailed("Ninebot Max G30", "").model,
            ScooterModel.detect("Ninebot Max G30", "")
        )
    }

    @Test
    fun `confidence labels are user readable`() {
        assertTrue(DetectionConfidence.CONFIRMED.label.isNotBlank())
        assertTrue(DetectionConfidence.PROBABLE.label.isNotBlank())
        assertTrue(DetectionConfidence.UNKNOWN.label.isNotBlank())
        assertFalse(DetectionConfidence.UNKNOWN.description.isBlank())
    }
}
