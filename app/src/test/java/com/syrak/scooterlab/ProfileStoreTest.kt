package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* ============================================================================
 *  PHASE 4 — Tests fuer den vollstaendigen Profil-Speicherpfad
 *  ---------------------------------------------------------------------------
 *  Diese Tests decken die Logik ab, die [ScooterRepository] fuer
 *  SharedPreferences verwendet: Speichern, Ueberschreiben, Loeschen,
 *  Round-Trip, Altformat, leere Namen, ungueltige Zahlen, mehrere '|' und
 *  beschaedigte Eintraege.
 *
 *  Bewusste Grenze: SharedPreferences selbst wird hier NICHT getestet (dafuer
 *  waere ein Android-Context/Robolectric noetig). Getestet wird die Logik, die
 *  das Repository unveraendert an SharedPreferences weiterreicht.
 * ==========================================================================*/
class ProfileStoreTest {

    private fun profile(name: String, speed: Int = 25, zero: Int = 0, kers: Int = 1, cruise: Boolean = false) =
        ScooterProfile(name, speed, zero, kers, cruise)

    /* ---------------------------- Round-Trip ---------------------------- */

    @Test
    fun `encode and decode are lossless`() {
        val p = profile("Tour", 27, 4, 2, true)
        val decoded = ProfileStore.decode(ProfileStore.encode(p))
        assertEquals(p, decoded)
    }

    @Test
    fun `save then reload after restart returns same values`() {
        // "Neustart" = neuer Aufruf auf einem frisch gelesenen Set.
        val stored = ProfileStore.upsert(emptySet(), profile("Stadt", 20, 0, 1, false))
        val reloaded = ProfileStore.decodeAll(stored)
        assertEquals(1, reloaded.size)
        assertEquals("Stadt", reloaded[0].name)
        assertEquals(20, reloaded[0].maxSpeed)
        assertEquals(0, reloaded[0].zeroStart)
        assertEquals(1, reloaded[0].kers)
        assertFalse(reloaded[0].cruiseControl)
    }

    /* --------------------------- Ueberschreiben -------------------------- */

    @Test
    fun `saving same name overwrites instead of duplicating`() {
        var stored = ProfileStore.upsert(emptySet(), profile("Tour", 25))
        stored = ProfileStore.upsert(stored, profile("Tour", 30, 5, 3, true))
        assertEquals(1, stored.size)
        val p = ProfileStore.decodeAll(stored).single()
        assertEquals(30, p.maxSpeed)
        assertEquals(5, p.zeroStart)
        assertEquals(3, p.kers)
        assertTrue(p.cruiseControl)
    }

    @Test
    fun `overwriting one profile keeps the other profiles intact`() {
        var stored = ProfileStore.upsert(emptySet(), profile("A", 20))
        stored = ProfileStore.upsert(stored, profile("B", 25))
        stored = ProfileStore.upsert(stored, profile("A", 35))
        val all = ProfileStore.decodeAll(stored)
        assertEquals(2, all.size)
        assertEquals(35, all.first { it.name == "A" }.maxSpeed)
        assertEquals(25, all.first { it.name == "B" }.maxSpeed)
    }

    /* ------------------------------ Loeschen ----------------------------- */

    @Test
    fun `delete removes only the requested profile`() {
        var stored = ProfileStore.upsert(emptySet(), profile("A"))
        stored = ProfileStore.upsert(stored, profile("B"))
        val after = ProfileStore.remove(stored, "A")
        assertEquals(1, after.size)
        assertEquals("B", ProfileStore.decodeAll(after).single().name)
    }

    @Test
    fun `delete of unknown name changes nothing`() {
        val stored = ProfileStore.upsert(emptySet(), profile("A"))
        assertEquals(stored, ProfileStore.remove(stored, "gibt-es-nicht"))
    }

    /* -------------------- Namen mit mehreren Trennern -------------------- */

    @Test
    fun `name with multiple pipes survives round trip`() {
        val p = profile("Wald|Tour|2026", 25, 1, 2, true)
        val decoded = ProfileStore.decode(ProfileStore.encode(p))
        assertNotNull(decoded)
        assertEquals("Wald|Tour|2026", decoded!!.name)
        assertEquals(25, decoded.maxSpeed)
        assertEquals(1, decoded.zeroStart)
        assertEquals(2, decoded.kers)
        assertTrue(decoded.cruiseControl)
    }

    @Test
    fun `profile with pipe in name is overwritten by exact name`() {
        var stored = ProfileStore.upsert(emptySet(), profile("A|B", 20))
        stored = ProfileStore.upsert(stored, profile("A|B", 40))
        assertEquals(1, stored.size)
        assertEquals(40, ProfileStore.decodeAll(stored).single().maxSpeed)
    }

    /* --------------------------- Altformat ------------------------------- */

    @Test
    fun `legacy entry without pipe in name still loads`() {
        val decoded = ProfileStore.decode("Alt|22|0|1|true")
        assertNotNull(decoded)
        assertEquals("Alt", decoded!!.name)
        assertEquals(22, decoded.maxSpeed)
        assertTrue(decoded.cruiseControl)
    }

    /* -------------------------- Ungueltige Daten ------------------------- */

    @Test
    fun `empty name is not a valid name for a NEW profile but decode stays lossless`() {
        // Parser bleibt verlustfrei (bestehende Invariante aus
        // ProfileSerializationTest), damit alte Eintraege nicht verloren gehen.
        val decoded = ProfileStore.decode("|25|0|1|false")
        assertNotNull(decoded)
        assertEquals("", decoded!!.name)
        assertEquals(25, decoded.maxSpeed)

        // Neu anlegen ist mit leerem Namen nicht erlaubt (UI-Regel).
        assertFalse(ProfileStore.isValidName(""))
        assertFalse(ProfileStore.isValidName("   "))
        assertTrue(ProfileStore.isValidName("Tour"))
    }

    @Test
    fun `too few fields are rejected`() {
        assertNull(ProfileStore.decode(""))
        assertNull(ProfileStore.decode("Name|25"))
        assertNull(ProfileStore.decode("Name|25|0|1"))
    }

    @Test
    fun `invalid max speed is rejected`() {
        assertNull(ProfileStore.decode("Name|abc|0|1|false"))
        assertNull(ProfileStore.decode("Name||0|1|false"))
    }

    @Test
    fun `invalid optional numbers fall back to defaults`() {
        val decoded = ProfileStore.decode("Name|25|x|y|false")
        assertNotNull(decoded)
        assertEquals(0, decoded!!.zeroStart)
        assertEquals(1, decoded.kers)
    }

    @Test
    fun `corrupt entries are counted but never deleted and never block valid ones`() {
        val stored = setOf(
            "Gut|25|0|1|false",
            "kaputt",
            "ZuWenig|25|0",
            "NochGut|30|1|2|true"
        )
        assertEquals(2, ProfileStore.invalidCount(stored))
        val valid = ProfileStore.decodeAll(stored)
        assertEquals(2, valid.size)
        assertEquals(listOf("Gut", "NochGut"), valid.map { it.name })

        // Ein Speichervorgang darf die kaputten Eintraege nicht entfernen.
        val afterSave = ProfileStore.upsert(stored, profile("Neu"))
        assertTrue(afterSave.contains("kaputt"))
        assertEquals(2, ProfileStore.invalidCount(afterSave))
        assertEquals(3, ProfileStore.decodeAll(afterSave).size)
    }

    @Test
    fun `deleting a valid profile leaves corrupt entries untouched`() {
        val stored = setOf("Gut|25|0|1|false", "kaputt")
        val after = ProfileStore.remove(stored, "Gut")
        assertTrue(after.contains("kaputt"))
        assertEquals(0, ProfileStore.decodeAll(after).size)
    }

    /* ------------------------------ Sortierung --------------------------- */

    @Test
    fun `profiles are sorted by name case insensitive`() {
        var stored = ProfileStore.upsert(emptySet(), profile("zeta"))
        stored = ProfileStore.upsert(stored, profile("Alpha"))
        stored = ProfileStore.upsert(stored, profile("beta"))
        assertEquals(listOf("Alpha", "beta", "zeta"), ProfileStore.decodeAll(stored).map { it.name })
    }
}
