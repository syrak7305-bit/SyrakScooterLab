package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regressionstests fuer die GATT-Characteristic-Auswahl.
 *
 * Belegter Defekt: die BleEngine setzte `notifyChar = writeChar`. Bei
 * Brightway/Navee ist FFE1 das Notify- und FFE2 das Write-Characteristic.
 * Wurde FFE2 als schreibbar gewaehlt, blieben Notifications aus -> keine
 * Telemetrie und kein echtes ACK. Diese Tests pinnen die korrekte Auswahl.
 *
 * Es werden ausschliesslich die im Projekt dokumentierten UUIDs verwendet
 * (NaveeFrames.CHAR_UUID = FFE1, NaveeFrames.CHAR_WRITE_UUID = FFE2).
 */
class GattCharacteristicSelectorTest {

    private val ffe1 = NaveeFrames.CHAR_UUID
    private val ffe2 = NaveeFrames.CHAR_WRITE_UUID

    private fun cand(uuid: String, writable: Boolean, notifiable: Boolean) =
        GattCharacteristicSelector.Candidate(uuid, writable, notifiable)

    @Test
    fun `notify char is chosen independently from write char`() {
        // Typische Brightway-Konfiguration: FFE1 = Notify-only, FFE2 = Write-only.
        val candidates = listOf(
            cand(ffe1, writable = false, notifiable = true),
            cand(ffe2, writable = true, notifiable = false),
        )
        val notify = GattCharacteristicSelector.pickNotify(candidates, listOf(ffe1, ffe2))
        val write = GattCharacteristicSelector.pickWrite(candidates, listOf(ffe1, ffe2))

        assertEquals("Notify muss FFE1 sein", ffe1, notify?.uuid)
        assertEquals("Write muss FFE2 sein", ffe2, write?.uuid)
        // Der eigentliche Defekt: Notify darf NICHT auf das Write-Char fallen.
        assertNotEquals(notify?.uuid, write?.uuid)
    }

    @Test
    fun `non notifiable preferred uuid is never selected`() {
        val candidates = listOf(
            cand(ffe1, writable = true, notifiable = false),
            cand(ffe2, writable = false, notifiable = true),
        )
        val notify = GattCharacteristicSelector.pickNotify(candidates, listOf(ffe1, ffe2))
        assertEquals(ffe2, notify?.uuid)
    }

    @Test
    fun `falls back to first notifiable when no preferred uuid matches`() {
        val other = "0000abcd-0000-1000-8000-00805f9b34fb"
        val candidates = listOf(
            cand(ffe2, writable = true, notifiable = false),
            cand(other, writable = false, notifiable = true),
        )
        val notify = GattCharacteristicSelector.pickNotify(candidates, listOf(ffe1))
        assertEquals(other, notify?.uuid)
    }

    @Test
    fun `no notifiable characteristic yields null`() {
        val candidates = listOf(cand(ffe2, writable = true, notifiable = false))
        assertNull(GattCharacteristicSelector.pickNotify(candidates, listOf(ffe1, ffe2)))
    }

    @Test
    fun `write selection prefers designated uuid`() {
        val candidates = listOf(
            cand(ffe1, writable = true, notifiable = true),
            cand(ffe2, writable = true, notifiable = false),
        )
        val write = GattCharacteristicSelector.pickWrite(candidates, listOf(ffe2, ffe1))
        assertEquals(ffe2, write?.uuid)
    }

    @Test
    fun `write selection ignores non writable preferred uuid`() {
        val candidates = listOf(
            cand(ffe1, writable = false, notifiable = true),
            cand(ffe2, writable = true, notifiable = false),
        )
        val write = GattCharacteristicSelector.pickWrite(candidates, listOf(ffe1, ffe2))
        assertEquals(ffe2, write?.uuid)
    }
}
