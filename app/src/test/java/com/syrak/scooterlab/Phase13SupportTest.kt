package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 13 — Tests fuer die nicht umgehbaren Countdown-Gates, die
 * Scroll-Pflicht und die Splash-Fortschrittslogik.
 *
 * Diese Tests laufen vollstaendig ohne Android-Geraet (reine JVM-Logik).
 */
class Phase13SupportTest {

    /* ---------------- CountdownState ---------------- */

    @Test
    fun `state counts down and finishes exactly at required time`() {
        val start = 1_000L
        val mid = CountdownGate.state(10_000L, start, start + 4_000L)
        assertEquals(6_000L, mid.remainingMs)
        assertEquals(6, mid.remainingSeconds)
        assertFalse(mid.finished)
        assertEquals(0.4f, mid.progress, 0.0001f)

        val done = CountdownGate.state(10_000L, start, start + 10_000L)
        assertEquals(0L, done.remainingMs)
        assertEquals(0, done.remainingSeconds)
        assertTrue(done.finished)
        assertEquals(1f, done.progress, 0.0001f)
    }

    @Test
    fun `remaining seconds round up so the button never unlocks early`() {
        val state = CountdownGate.state(10_000L, 0L, 9_001L)
        assertEquals(999L, state.remainingMs)
        assertEquals(1, state.remainingSeconds)
        assertFalse(state.finished)
    }

    @Test
    fun `negative elapsed time is clamped and never unlocks the gate`() {
        val state = CountdownGate.state(10_000L, 5_000L, 4_000L)
        assertEquals(0L, state.elapsedMs)
        assertEquals(10_000L, state.remainingMs)
        assertFalse(state.finished)
    }

    @Test
    fun `elapsed beyond required time does not overflow`() {
        val state = CountdownGate.state(8_000L, 0L, 999_999L)
        assertTrue(state.finished)
        assertEquals(0L, state.remainingMs)
        assertEquals(1f, state.progress, 0.0001f)
    }

    @Test
    fun `zero required time is immediately finished`() {
        val state = CountdownGate.state(0L, 0L, 0L)
        assertTrue(state.finished)
        assertEquals(1f, state.progress, 0.0001f)
    }

    /* ---------------- CountdownGate.restore ---------------- */

    @Test
    fun `restore without saved value starts now`() {
        assertEquals(7_777L, CountdownGate.restore(null, 7_777L))
        assertEquals(7_777L, CountdownGate.restore(0L, 7_777L))
    }

    @Test
    fun `restore keeps a valid past start so real waiting time counts`() {
        assertEquals(1_000L, CountdownGate.restore(1_000L, 5_000L))
    }

    @Test
    fun `restore rejects a start in the future (reboot or clock jump)`() {
        assertEquals(5_000L, CountdownGate.restore(9_000L, 5_000L))
    }

    @Test
    fun `restored gate cannot be skipped by restarting within the wait time`() {
        val required = 10_000L
        val restoredStart = CountdownGate.restore(1_000L, 3_000L)
        val state = CountdownGate.state(required, restoredStart, 3_000L)
        assertFalse(state.finished)
        assertEquals(8_000L, state.remainingMs)
    }

    /* ---------------- ScrollGate ---------------- */

    @Test
    fun `short content needs no scrolling`() {
        assertTrue(ScrollGate.isAtBottom(0, 500, 800, 10))
    }

    @Test
    fun `long content requires reaching the bottom`() {
        assertFalse(ScrollGate.isAtBottom(0, 2_000, 800, 10))
        assertFalse(ScrollGate.isAtBottom(600, 2_000, 800, 10))
        assertTrue(ScrollGate.isAtBottom(1_200, 2_000, 800, 10))
        assertTrue(ScrollGate.isAtBottom(1_195, 2_000, 800, 10))
    }

    @Test
    fun `unknown layout sizes do not unlock the gate`() {
        assertFalse(ScrollGate.isAtBottom(0, 0, 0, 10))
        assertFalse(ScrollGate.isAtBottom(0, 1_000, 0, 10))
    }

    /* ---------------- SplashSequence ---------------- */

    @Test
    fun `splash progress is monotonic and ends at 100 percent`() {
        var last = -1f
        for (i in 0..SplashSequence.WORK_STAGES) {
            val p = SplashSequence.progress(i)
            assertTrue("progress must not decrease at stage $i", p >= last)
            last = p
        }
        assertEquals(1f, last, 0.0001f)
        assertEquals(100, SplashSequence.percent(SplashSequence.WORK_STAGES))
    }

    @Test
    fun `splash percent never reports 100 before the ready stage`() {
        for (i in 0 until SplashSequence.WORK_STAGES) {
            assertTrue(SplashSequence.percent(i, 1f) < 100)
        }
    }

    @Test
    fun `splash stage labels are the documented real steps`() {
        assertEquals("SYRAK SYSTEM INITIALIZING", SplashSequence.initializing().label)
        assertEquals("LOADING DIAGNOSTIC MODULES", SplashSequence.modules().label)
        assertEquals("CHECKING BLUETOOTH SERVICES", SplashSequence.bluetooth().label)
        assertEquals("PREPARING DASHBOARD", SplashSequence.dashboard().label)
        assertEquals("SYSTEM READY", SplashSequence.ready().label)
    }

    @Test
    fun `stage index is clamped to valid range`() {
        assertEquals(0f, SplashSequence.progress(-5), 0.0001f)
        assertEquals(1f, SplashSequence.progress(99), 0.0001f)
        assertEquals(100, SplashSequence.percent(99))
    }
}
