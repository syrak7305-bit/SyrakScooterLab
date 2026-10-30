package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.ChargeState
import com.syrak.scooterlab.diagnostics.DataStatus
import com.syrak.scooterlab.diagnostics.DiagnosticEventLog
import com.syrak.scooterlab.diagnostics.DiagnosticSeverity
import com.syrak.scooterlab.diagnostics.DiagnosticsBuilder
import com.syrak.scooterlab.diagnostics.DiagnosticsInput
import com.syrak.scooterlab.diagnostics.Plausibility
import com.syrak.scooterlab.diagnostics.StaleGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9 — Tests der reinen Diagnose-Schicht.
 *
 * Kernaussage: Die Diagnose darf niemals Werte erfinden. Fehlende, ungueltige
 * oder veraltete Daten muessen als solche sichtbar werden.
 */
class DiagnosticsTest {

    /* ------------------------------------------------------------------ */
    /*  StaleGuard                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    fun `never received data is stale`() {
        assertTrue(StaleGuard.isStale(null, 1_000L))
        assertNull(StaleGuard.ageMillis(null, 1_000L))
    }

    @Test
    fun `zero timestamp counts as never received`() {
        assertNull(StaleGuard.ageMillis(0L, 5_000L))
        assertTrue(StaleGuard.isStale(0L, 5_000L))
    }

    @Test
    fun `age is computed from monotonic clock`() {
        assertEquals(1_500L, StaleGuard.ageMillis(10_000L, 11_500L))
    }

    @Test
    fun `backwards clock yields zero not negative`() {
        assertEquals(0L, StaleGuard.ageMillis(10_000L, 9_000L))
        assertFalse(StaleGuard.isStale(10_000L, 9_000L))
    }

    @Test
    fun `exactly at threshold is not stale`() {
        assertFalse(StaleGuard.isStale(1_000L, 1_000L + StaleGuard.DEFAULT_STALE_MS))
    }

    @Test
    fun `above threshold is stale`() {
        assertTrue(StaleGuard.isStale(1_000L, 1_000L + StaleGuard.DEFAULT_STALE_MS + 1))
    }

    /* ------------------------------------------------------------------ */
    /*  Plausibilitaet                                                    */
    /* ------------------------------------------------------------------ */

    @Test
    fun `battery percent bounds`() {
        assertEquals(DataStatus.NOT_AVAILABLE, Plausibility.batteryPercent(null))
        assertEquals(DataStatus.INVALID, Plausibility.batteryPercent(-1))
        assertEquals(DataStatus.INVALID, Plausibility.batteryPercent(101))
        assertEquals(DataStatus.OK, Plausibility.batteryPercent(0))
        assertEquals(DataStatus.OK, Plausibility.batteryPercent(100))
    }

    @Test
    fun `cell voltage bounds`() {
        assertEquals(DataStatus.INVALID, Plausibility.cellVoltage(1.0f))
        assertEquals(DataStatus.INVALID, Plausibility.cellVoltage(4.6f))
        assertEquals(DataStatus.OK, Plausibility.cellVoltage(3.7f))
        assertEquals(DataStatus.NOT_AVAILABLE, Plausibility.cellVoltage(null))
    }

    @Test
    fun `temperature bounds`() {
        assertEquals(DataStatus.INVALID, Plausibility.temperature(-41f))
        assertEquals(DataStatus.INVALID, Plausibility.temperature(126f))
        assertEquals(DataStatus.OK, Plausibility.temperature(25f))
    }

    @Test
    fun `pack voltage uses model cell count`() {
        // NAVEE GT3 Max: 13 Zellen -> 32.5 .. 55.9 V
        assertEquals(DataStatus.OK, Plausibility.packVoltage(48.0f, ScooterModel.NAVEE_GT3_MAX))
        assertEquals(DataStatus.INVALID, Plausibility.packVoltage(20.0f, ScooterModel.NAVEE_GT3_MAX))
        assertEquals(DataStatus.INVALID, Plausibility.packVoltage(80.0f, ScooterModel.NAVEE_GT3_MAX))
    }

    @Test
    fun `pack voltage for unknown model is not guessed`() {
        assertEquals(
            DataStatus.UNKNOWN_PROTOCOL,
            Plausibility.packVoltage(48.0f, ScooterModel.UNKNOWN)
        )
    }

    @Test
    fun `pack voltage missing is not available`() {
        assertEquals(
            DataStatus.NOT_AVAILABLE,
            Plausibility.packVoltage(null, ScooterModel.NAVEE_GT3_MAX)
        )
    }

    @Test
    fun `soh needs both capacities`() {
        assertNull(Plausibility.sohPercent(null, 15_000))
        assertNull(Plausibility.sohPercent(14_400, null))
        assertNull(Plausibility.sohPercent(14_400, 0))
        assertEquals(96f, Plausibility.sohPercent(14_400, 15_000)!!, 1e-3f)
    }

    @Test
    fun `cell delta is null when max below min`() {
        assertNull(Plausibility.cellDeltaMv(3.7f, 3.6f))
        assertEquals(60f, Plausibility.cellDeltaMv(3.62f, 3.68f)!!, 1e-3f)
    }

    /* ------------------------------------------------------------------ */
    /*  Builder — ehrliche "Nicht verfuegbar"-Semantik                    */
    /* ------------------------------------------------------------------ */

    private fun input(
        telemetry: Telemetry?,
        model: ScooterModel = ScooterModel.NAVEE_GT3_MAX,
        protocol: BleProtocol? = BleProtocol.NAVEE_BRIGHTWAY,
        connected: Boolean = true,
        lastAt: Long? = 10_000L,
        now: Long = 11_000L,
        commErrors: Int = 0,
        demo: Boolean = false
    ) = DiagnosticsInput(
        telemetry = telemetry,
        model = model,
        protocol = protocol,
        connected = connected,
        lastTelemetryAtMillis = lastAt,
        nowMillis = now,
        commErrorCount = commErrors,
        isDemo = demo
    )

    private fun goodTelemetry() = Telemetry(
        speedKmh = 20f,
        voltageV = 48.0f,
        currentA = 5f,
        batteryPercent = 80,
        motorTempC = 30f,
        cellMinV = 3.62f,
        cellMaxV = 3.68f,
        cellVoltages = List(13) { 3.65f },
        designCapacityMah = 15_000,
        fullChargeCapacityMah = 14_400,
        cycleCount = 42,
        source = BleProtocol.NAVEE_BRIGHTWAY
    )

    @Test
    fun `no telemetry yields not available everywhere`() {
        val r = DiagnosticsBuilder.build(input(null))
        assertEquals(DataStatus.NOT_AVAILABLE, r.overall)
        assertEquals(DataStatus.NOT_AVAILABLE, r.battery.percent.status)
        assertEquals(DataStatus.NOT_AVAILABLE, r.live.speedKmh.status)
        assertEquals(DataStatus.NOT_AVAILABLE, r.motor.rpm.status)
        assertEquals(DataStatus.NOT_AVAILABLE, r.electronics.firmwareVersion.status)
    }

    @Test
    fun `valid fresh telemetry is ok`() {
        val r = DiagnosticsBuilder.build(input(goodTelemetry()))
        assertEquals(DataStatus.OK, r.overall)
        assertEquals(DataStatus.OK, r.battery.percent.status)
        assertEquals(DataStatus.OK, r.battery.voltage.status)
        assertEquals(DataStatus.OK, r.live.speedKmh.status)
        assertEquals(80, r.battery.percent.value)
    }

    @Test
    fun `invalid battery percent is flagged and not ok overall`() {
        val r = DiagnosticsBuilder.build(input(goodTelemetry().copy(batteryPercent = 150)))
        assertEquals(DataStatus.INVALID, r.battery.percent.status)
        assertEquals(DataStatus.INVALID, r.overall)
        assertTrue(r.warnings.any { it.contains("Akkustand unplausibel") })
    }

    @Test
    fun `stale telemetry is marked stale`() {
        val r = DiagnosticsBuilder.build(input(goodTelemetry(), lastAt = 10_000L, now = 20_000L))
        assertEquals(DataStatus.STALE, r.overall)
        assertEquals(DataStatus.STALE, r.live.freshness)
        assertEquals(DataStatus.STALE, r.live.speedKmh.status)
        assertTrue(r.warnings.any { it.contains("veraltet") })
    }

    @Test
    fun `disconnected is not available even with last data`() {
        val r = DiagnosticsBuilder.build(input(goodTelemetry(), connected = false))
        assertEquals(DataStatus.NOT_AVAILABLE, r.overall)
        assertEquals(DataStatus.NOT_AVAILABLE, r.electronics.link.status)
    }

    @Test
    fun `protocol mismatch is reported`() {
        // Navee-Telemetrie, aber Ninebot als aktives Protokoll -> Warnung.
        val r = DiagnosticsBuilder.build(
            input(goodTelemetry(), protocol = BleProtocol.NINEBOT)
        )
        assertTrue(r.warnings.any { it.contains("passt nicht zum aktiven Protokoll") })
    }

    @Test
    fun `matching protocol produces no mismatch warning`() {
        val r = DiagnosticsBuilder.build(input(goodTelemetry()))
        assertFalse(r.warnings.any { it.contains("passt nicht") })
    }

    @Test
    fun `unknown model warns and does not guess voltage limits`() {
        val r = DiagnosticsBuilder.build(
            input(goodTelemetry(), model = ScooterModel.UNKNOWN)
        )
        assertEquals(DataStatus.UNKNOWN_PROTOCOL, r.battery.voltage.status)
        assertTrue(r.warnings.any { it.contains("Modell unbekannt") })
    }

    @Test
    fun `communication errors are surfaced`() {
        val r = DiagnosticsBuilder.build(input(goodTelemetry(), commErrors = 3))
        assertEquals(3, r.electronics.commErrorCount)
        assertTrue(r.warnings.any { it.contains("3 Kommunikationsfehler") })
    }

    @Test
    fun `demo data is explicitly labelled`() {
        val r = DiagnosticsBuilder.build(input(goodTelemetry(), demo = true))
        assertTrue(r.warnings.any { it.contains("DEMO") })
    }

    @Test
    fun `motor rpm and firmware are never invented`() {
        val r = DiagnosticsBuilder.build(input(goodTelemetry()))
        assertEquals(DataStatus.NOT_AVAILABLE, r.motor.rpm.status)
        assertNull(r.motor.rpm.value)
        assertEquals(DataStatus.NOT_AVAILABLE, r.electronics.firmwareVersion.status)
        assertNull(r.electronics.firmwareVersion.value)
        assertEquals(DataStatus.NOT_AVAILABLE, r.motor.controllerStatus.status)
    }

    @Test
    fun `charge state is idle only without measurable current`() {
        assertEquals(
            ChargeState.IDLE,
            DiagnosticsBuilder.build(input(goodTelemetry().copy(currentA = 0f)))
                .battery.chargeState
        )
        assertEquals(
            ChargeState.UNKNOWN,
            DiagnosticsBuilder.build(input(goodTelemetry().copy(currentA = 5f)))
                .battery.chargeState
        )
        assertEquals(
            ChargeState.UNKNOWN,
            DiagnosticsBuilder.build(input(goodTelemetry().copy(currentA = null)))
                .battery.chargeState
        )
    }

    @Test
    fun `large cell delta produces warning`() {
        val r = DiagnosticsBuilder.build(
            input(goodTelemetry().copy(cellMinV = 3.50f, cellMaxV = 3.75f))
        )
        assertTrue(r.warnings.any { it.contains("Zell-Delta") })
    }

    @Test
    fun `low soh produces warning`() {
        val r = DiagnosticsBuilder.build(
            input(goodTelemetry().copy(designCapacityMah = 15_000, fullChargeCapacityMah = 9_000))
        )
        assertTrue(r.warnings.any { it.contains("SoH") })
    }

    /* ------------------------------------------------------------------ */
    /*  DiagnosticEventLog                                                */
    /* ------------------------------------------------------------------ */

    @Test
    fun `event log keeps only capacity entries`() {
        val log = DiagnosticEventLog(capacity = 3)
        repeat(5) { i ->
            log.add(i.toLong(), DiagnosticSeverity.INFO, "E$i", "msg $i")
        }
        assertEquals(3, log.size())
        assertEquals(listOf("E2", "E3", "E4"), log.snapshot().map { it.code })
    }

    @Test
    fun `event log counts severities`() {
        val log = DiagnosticEventLog()
        log.add(1L, DiagnosticSeverity.INFO, "A", "a")
        log.add(2L, DiagnosticSeverity.WARNING, "B", "b")
        log.add(3L, DiagnosticSeverity.ERROR, "C", "c")
        log.add(4L, DiagnosticSeverity.ERROR, "D", "d")
        assertEquals(2, log.errorCount())
        assertEquals(1, log.warningCount())
        assertEquals("D", log.last(DiagnosticSeverity.ERROR)?.code)
        assertEquals("D", log.last()?.code)
    }

    @Test
    fun `event log clear empties everything`() {
        val log = DiagnosticEventLog()
        log.add(1L, DiagnosticSeverity.ERROR, "A", "a")
        log.clear()
        assertEquals(0, log.size())
        assertEquals(0, log.errorCount())
        assertNull(log.last())
    }

    @Test
    fun `event log rejects non positive capacity`() {
        try {
            DiagnosticEventLog(capacity = 0)
            throw AssertionError("erwartete IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // erwartet
        }
    }
}
