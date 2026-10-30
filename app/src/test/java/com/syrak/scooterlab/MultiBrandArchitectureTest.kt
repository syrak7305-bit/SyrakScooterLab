package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* ============================================================================
 *  PHASE 10 — MULTI-BRAND ARCHITEKTUR (reine Logiktests)
 *  ----------------------------------------------------------------------------
 *  Diese Tests belegen:
 *    • Hersteller und Modell bleiben getrennt.
 *    • Generische Tokens ("G2", "MAX") ordnen KEIN Modell zu.
 *    • "Xiaomi Scooter 4" wird NICHT als M365/Pro 2 ausgegeben.
 *    • KuKirin wird NICHT als Ninebot erkannt.
 *    • Ein Adapter wird nur fuer Modelle benutzt, die er explizit fuehrt.
 *    • Support-Status wird NIE aus der Hersteller-Erkennung abgeleitet.
 *
 *  Sie beweisen NICHT, dass ein reales Geraet dieses Protokoll spricht.
 * ==========================================================================*/
class MultiBrandArchitectureTest {

    /* ------------------------- 1. Erkennung ---------------------------- */

    @Test
    fun `kukirin g2 max is not ninebot max g2`() {
        val r = ScooterModel.detectDetailed("KuKirin G2 Max", "")
        assertEquals(ScooterModel.UNKNOWN, r.model)
        assertEquals(ScooterManufacturer.KUKIRIN, r.manufacturer)
    }

    @Test
    fun `xiaomi scooter 4 is not falsely mapped to m365 or pro 2`() {
        val r = ScooterModel.detectDetailed("Xiaomi Scooter 4", "")
        assertEquals(ScooterModel.UNKNOWN, r.model)
        assertEquals(ScooterManufacturer.XIAOMI, r.manufacturer)
    }

    @Test
    fun `bare generic g2 and max tokens do not assign a model`() {
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("G2", ""))
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("MAX", ""))
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("G2 Max", ""))
    }

    @Test
    fun `explicit model tokens still detect`() {
        assertEquals(ScooterModel.NINEBOT_MAX_G2, ScooterModel.detect("Ninebot Max G2", ""))
        assertEquals(ScooterModel.NINEBOT_MAX_G30, ScooterModel.detect("Segway Ninebot Max G30", ""))
        assertEquals(ScooterModel.NAVEE_GT3_MAX, ScooterModel.detect("Navee GT3 Max", ""))
        assertEquals(ScooterModel.XIAOMI_PRO_2, ScooterModel.detect("Mi Scooter Pro 2", ""))
    }

    @Test
    fun `soflow is recognised as manufacturer and model`() {
        val r = ScooterModel.detectDetailed("SoFlow SO4", "")
        assertEquals(ScooterManufacturer.SOFLOW, r.manufacturer)
        assertEquals(ScooterModel.SOFLOW_SO4, r.model)
    }

    @Test
    fun `navee model without explicit token stays unknown`() {
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("NAVEE", ""))
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("Navee E-Scooter", ""))
    }

    /* --------------------- 2. Adapter-Aufloesung ----------------------- */

    @Test
    fun `navee gt3 max resolves to exact navee adapter`() {
        val res = ProtocolAdapters.resolveForModel(ScooterModel.NAVEE_GT3_MAX)
        assertTrue(res.exact)
        assertEquals("navee-gt3max", res.adapter?.id)
    }

    @Test
    fun `other navee models have no exact adapter`() {
        for (model in listOf(
            ScooterModel.NAVEE_GT3_PRO,
            ScooterModel.NAVEE_ST3,
            ScooterModel.NAVEE_N65,
            ScooterModel.NAVEE_V40,
            ScooterModel.NAVEE_S65
        )) {
            val res = ProtocolAdapters.resolveForModel(model)
            assertFalse("${model.displayName} darf keinen exakten Adapter haben", res.exact)
            assertNull(res.adapter)
        }
    }

    @Test
    fun `ninebot and xiaomi models resolve to ninebot adapter`() {
        for (model in listOf(
            ScooterModel.NINEBOT_MAX_G2,
            ScooterModel.NINEBOT_MAX_G30,
            ScooterModel.NINEBOT_F2_PRO,
            ScooterModel.NINEBOT_ES,
            ScooterModel.XIAOMI_PRO_2,
            ScooterModel.XIAOMI_M365
        )) {
            val res = ProtocolAdapters.resolveForModel(model)
            assertTrue(res.exact)
            assertEquals("ninebot-generic", res.adapter?.id)
        }
    }

    @Test
    fun `soflow has no exact adapter and no tuning release`() {
        val res = ProtocolAdapters.resolveForModel(ScooterModel.SOFLOW_SO4)
        assertFalse(res.exact)
        assertNull(res.adapter)
        assertFalse(ProtocolAdapters.supportsTuning(ScooterModel.SOFLOW_SO4))
    }

    @Test
    fun `unknown model never resolves to an adapter`() {
        val res = ProtocolAdapters.resolveForModel(ScooterModel.UNKNOWN)
        assertFalse(res.exact)
        assertNull(res.adapter)
        assertFalse(ProtocolAdapters.supportsTuning(ScooterModel.UNKNOWN))
    }

    @Test
    fun `adapter protocol matches every model it declares`() {
        for (adapter in ProtocolAdapters.all) {
            for (model in adapter.capabilities.supportedModels) {
                assertEquals(
                    "Adapter ${adapter.id} fuehrt ${model.displayName} mit falschem Protokoll",
                    adapter.protocol,
                    model.protocol
                )
            }
        }
    }

    @Test
    fun `adapter ids are unique and supported model sets are disjoint`() {
        val ids = ProtocolAdapters.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val navee = ProtocolAdapters.byId("navee-gt3max")!!.capabilities.supportedModels
        val ninebot = ProtocolAdapters.byId("ninebot-generic")!!.capabilities.supportedModels
        assertTrue(navee.intersect(ninebot).isEmpty())
    }

    @Test
    fun `protocol mismatch is detectable`() {
        val naveeCaps = ProtocolAdapters.capabilitiesFor(ScooterModel.NAVEE_GT3_MAX)!!
        assertEquals(BleProtocol.NAVEE_BRIGHTWAY, naveeCaps.protocol)
        assertFalse(naveeCaps.supportsModel(ScooterModel.NINEBOT_MAX_G2))

        val nbCaps = ProtocolAdapters.capabilitiesFor(ScooterModel.NINEBOT_MAX_G2)!!
        assertEquals(BleProtocol.NINEBOT, nbCaps.protocol)
        assertFalse(nbCaps.supportsModel(ScooterModel.NAVEE_GT3_MAX))
    }

    /* ---------------------- 3. Support-Matrix -------------------------- */

    @Test
    fun `unknown model has unknown status`() {
        val snap = SupportMatrix.staticSnapshot(ScooterModel.UNKNOWN)
        assertEquals(SupportStatus.UNKNOWN, snap.status)
        assertFalse(snap.tuningSupported)
    }

    @Test
    fun `manufacturer detection alone never yields a support status`() {
        // KuKirin ist als Marke bekannt, aber es gibt kein Modell/Protokoll.
        val r = ScooterModel.detectDetailed("KuKirin G2 Max", "")
        val snap = SupportMatrix.staticSnapshot(r.model)
        assertEquals(SupportStatus.UNKNOWN, snap.status)
        assertFalse(snap.tuningSupported)
        assertEquals(ScooterManufacturer.KUKIRIN, r.manufacturer)
    }

    @Test
    fun `model without exact adapter is only identified`() {
        val snap = SupportMatrix.staticSnapshot(ScooterModel.NAVEE_ST3)
        assertEquals(SupportStatus.IDENTIFIED, snap.status)
        assertFalse(snap.modelExplicitlySupported)
        assertFalse(snap.tuningSupported)
        assertTrue(snap.unavailable.contains("modellspezifischer Adapter"))
    }

    @Test
    fun `adapter without runtime evidence is only identified`() {
        val snap = SupportMatrix.staticSnapshot(ScooterModel.NAVEE_GT3_MAX)
        assertEquals(SupportStatus.IDENTIFIED, snap.status)
        assertTrue(snap.modelExplicitlySupported)
    }

    @Test
    fun `gatt discovery raises status to discovered`() {
        val snap = SupportMatrix.snapshot(
            ScooterModel.NAVEE_GT3_MAX,
            ProtocolAdapters.byId("navee-gt3max"),
            SupportRuntime(gattDiscovered = true)
        )
        assertEquals(SupportStatus.DISCOVERED, snap.status)
    }

    @Test
    fun `telemetry raises status to diagnostics supported`() {
        val snap = SupportMatrix.snapshot(
            ScooterModel.NAVEE_GT3_MAX,
            ProtocolAdapters.byId("navee-gt3max"),
            SupportRuntime(gattDiscovered = true, telemetryReceived = true)
        )
        assertEquals(SupportStatus.DIAGNOSTICS_SUPPORTED, snap.status)
        assertTrue(snap.readSupported)
        assertTrue(snap.diagnosticsSupported)
        assertFalse(snap.tuningSupported)
    }

    @Test
    fun `tuning requires hardware verification and open gate`() {
        val adapter = ProtocolAdapters.byId("navee-gt3max")
        val readOnly = SupportMatrix.snapshot(
            ScooterModel.NAVEE_GT3_MAX, adapter,
            SupportRuntime(gattDiscovered = true, telemetryReceived = true, hardwareVerified = true)
        )
        assertEquals(SupportStatus.DIAGNOSTICS_SUPPORTED, readOnly.status)

        val released = SupportMatrix.snapshot(
            ScooterModel.NAVEE_GT3_MAX, adapter,
            SupportRuntime(
                gattDiscovered = true,
                telemetryReceived = true,
                hardwareVerified = true,
                tuningGateOpen = true
            )
        )
        assertEquals(SupportStatus.TUNING_SUPPORTED, released.status)
        assertTrue(released.tuningSupported)
    }

    @Test
    fun `tuning support is derived from adapter capability not manufacturer`() {
        assertTrue(ProtocolAdapters.supportsTuning(ScooterModel.NAVEE_GT3_MAX))
        assertTrue(ProtocolAdapters.supportsTuning(ScooterModel.NINEBOT_MAX_G2))
        assertFalse(ProtocolAdapters.supportsTuning(ScooterModel.NAVEE_ST3))
        assertFalse(ProtocolAdapters.supportsTuning(ScooterModel.SOFLOW_SO4))
        assertFalse(ProtocolAdapters.supportsTuning(ScooterModel.UNKNOWN))
    }

    /* ------------------- 4. Capability-Deklaration --------------------- */

    @Test
    fun `every adapter declares services and read operations`() {
        for (adapter in ProtocolAdapters.all) {
            val c = adapter.capabilities
            assertTrue("${adapter.id}: Service fehlt", c.gattServiceUuids.isNotEmpty())
            assertTrue("${adapter.id}: Write-Char fehlt", c.gattWriteCharUuids.isNotEmpty())
            assertTrue("${adapter.id}: Notify-Char fehlt", c.gattNotifyCharUuids.isNotEmpty())
            assertTrue("${adapter.id}: Read-Ops fehlen", c.readOperations.isNotEmpty())
            assertTrue("${adapter.id}: Telemetrie-Decoding fehlt", c.telemetryDecoding)
            assertTrue("${adapter.id}: Diagnostics fehlt", c.diagnostics)
            assertTrue("${adapter.id}: Hardware-Pflicht fehlt", c.requiresHardwareVerificationForWrites)
        }
    }

    @Test
    fun `read only capability exposes no write operations`() {
        val caps = ProtocolAdapters.capabilitiesFor(ScooterModel.NAVEE_GT3_MAX)!!
        val readOnly = caps.copy(readOnlyByDefault = true)
        assertFalse(readOnly.supportsAnyWrite())
        assertFalse(readOnly.supportsWrite(WriteOperation.SPEED_CEILING))
        // Lesen bleibt erlaubt.
        assertTrue(readOnly.supportsRead(ReadOperation.STATUS_TELEMETRY))
    }

    @Test
    fun `supported models list is exactly the declared union`() {
        val expected = setOf(
            ScooterModel.NAVEE_GT3_MAX,
            ScooterModel.NINEBOT_MAX_G2,
            ScooterModel.NINEBOT_MAX_G30,
            ScooterModel.NINEBOT_F2_PRO,
            ScooterModel.NINEBOT_ES,
            ScooterModel.XIAOMI_PRO_2,
            ScooterModel.XIAOMI_M365
        )
        assertEquals(expected, ProtocolAdapters.supportedModels())
        assertNotNull(ProtocolAdapters.capabilitiesFor(ScooterModel.NINEBOT_MAX_G30))
    }
}
