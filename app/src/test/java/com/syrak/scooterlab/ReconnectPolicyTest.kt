package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* ============================================================================
 *  PHASE 4 — Tests fuer die Reconnect-Entscheidung
 *  ---------------------------------------------------------------------------
 *  Diese Tests sichern die Regeln ab, deren Verletzung im Feld am unangenehmsten
 *  waere: kein Auto-Reconnect nach bewusstem Trennen, keine Endlosschleife,
 *  kein Reconnect bei deaktivierter Einstellung.
 *
 *  Sie sind REINE Logiktests. Ob ein echter Scooter danach tatsaechlich wieder
 *  verbindet, ist damit NICHT bewiesen — das braucht Hardware.
 * ==========================================================================*/
class ReconnectPolicyTest {

    @Test
    fun `schedules while attempts remain`() {
        assertTrue(ReconnectPolicy.shouldSchedule(true, false, 0, 3))
        assertTrue(ReconnectPolicy.shouldSchedule(true, false, 2, 3))
    }

    @Test
    fun `stops after max attempts`() {
        assertFalse(ReconnectPolicy.shouldSchedule(true, false, 3, 3))
        assertFalse(ReconnectPolicy.shouldSchedule(true, false, 4, 3))
    }

    @Test
    fun `never schedules when auto reconnect is disabled`() {
        assertFalse(ReconnectPolicy.shouldSchedule(false, false, 0, 3))
    }

    @Test
    fun `never schedules after a user initiated disconnect`() {
        assertFalse(ReconnectPolicy.shouldSchedule(true, true, 0, 3))
    }

    @Test
    fun `invalid max attempts disables scheduling`() {
        assertFalse(ReconnectPolicy.shouldSchedule(true, false, 0, 0))
        assertFalse(ReconnectPolicy.shouldSchedule(true, false, 0, -1))
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        assertEquals(1_000L, ReconnectPolicy.delayMillis(0, 1_000L, 8_000L))
        assertEquals(2_000L, ReconnectPolicy.delayMillis(1, 1_000L, 8_000L))
        assertEquals(4_000L, ReconnectPolicy.delayMillis(2, 1_000L, 8_000L))
        assertEquals(8_000L, ReconnectPolicy.delayMillis(3, 1_000L, 8_000L))
        assertEquals(8_000L, ReconnectPolicy.delayMillis(4, 1_000L, 8_000L))
    }

    @Test
    fun `huge attempt numbers do not overflow into negative delays`() {
        val delay = ReconnectPolicy.delayMillis(Int.MAX_VALUE, 1_000L, 8_000L)
        assertEquals(8_000L, delay)
        assertTrue(delay >= 0)
    }

    @Test
    fun `zero base yields zero delay`() {
        assertEquals(0L, ReconnectPolicy.delayMillis(3, 0L, 8_000L))
    }

    /* ------------------------------------------------------------------ */
    /*  PHASE 7 — shouldFire (Pruefung zur FEUERZEIT des Runnables)        */
    /* ------------------------------------------------------------------ */
    /*  Belegte Luecke: shouldSchedule entscheidet nur beim PLANEN. Das     */
    /*  Runnable feuert bis zu 8 s spaeter. Wurde Auto-Reconnect in dieser  */
    /*  Zeit abgeschaltet, verband die App trotzdem. shouldFire schliesst   */
    /*  genau dieses Fenster.                                              */

    @Test
    fun `fires when auto reconnect on and not user initiated`() {
        assertTrue(ReconnectPolicy.shouldFire(autoReconnect = true, userInitiated = false))
    }

    @Test
    fun `does not fire when auto reconnect was turned off during backoff`() {
        assertFalse(ReconnectPolicy.shouldFire(autoReconnect = false, userInitiated = false))
    }

    @Test
    fun `does not fire after a user initiated disconnect`() {
        assertFalse(ReconnectPolicy.shouldFire(autoReconnect = true, userInitiated = true))
    }

    @Test
    fun `does not fire when both auto reconnect off and user initiated`() {
        assertFalse(ReconnectPolicy.shouldFire(autoReconnect = false, userInitiated = true))
    }
}
