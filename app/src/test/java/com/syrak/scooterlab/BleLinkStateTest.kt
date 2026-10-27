package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer den Link-Zustandsautomaten. Simuliert die Callback-Reihenfolge des
 * BLE-Stacks — ohne echtes Geraet und ohne Android-Runtime.
 */
class BleLinkStateTest {

    @Test
    fun `happy path idle to ready`() {
        var s = BleLinkState.IDLE
        s = s.transition(BleLinkEvent.CONNECT_REQUESTED)
        assertEquals(BleLinkState.CONNECTING, s)
        s = s.transition(BleLinkEvent.GATT_CONNECTED)
        assertEquals(BleLinkState.CONNECTED, s)
        s = s.transition(BleLinkEvent.READY)
        assertEquals(BleLinkState.READY, s)
        assertTrue(s.isUsable)
    }

    @Test
    fun `disconnect from any state returns to idle`() {
        for (state in BleLinkState.values()) {
            assertEquals(BleLinkState.IDLE, state.transition(BleLinkEvent.DISCONNECTED))
        }
    }

    @Test
    fun `connect timeout returns to idle`() {
        assertEquals(
            BleLinkState.IDLE,
            BleLinkState.CONNECTING.transition(BleLinkEvent.CONNECT_TIMEOUT)
        )
    }

    @Test
    fun `duplicate gatt connected callback is idempotent`() {
        assertEquals(
            BleLinkState.CONNECTED,
            BleLinkState.CONNECTED.transition(BleLinkEvent.GATT_CONNECTED)
        )
    }

    @Test
    fun `stale gatt connected after disconnect is ignored`() {
        // Nach DISCONNECTED ist der Zustand IDLE — ein verspaeteter
        // GATT_CONNECTED-Callback darf die UI nicht wieder "verbunden" zeigen.
        val s = BleLinkState.IDLE.transition(BleLinkEvent.GATT_CONNECTED)
        assertEquals(BleLinkState.IDLE, s)
        assertFalse(s.isUsable)
    }

    @Test
    fun `ready without connection is ignored`() {
        assertEquals(BleLinkState.IDLE, BleLinkState.IDLE.transition(BleLinkEvent.READY))
    }

    @Test
    fun `busy flag covers connecting and connected`() {
        assertTrue(BleLinkState.CONNECTING.isBusy)
        assertTrue(BleLinkState.CONNECTED.isBusy)
        assertFalse(BleLinkState.IDLE.isBusy)
        assertFalse(BleLinkState.READY.isBusy)
    }
}
