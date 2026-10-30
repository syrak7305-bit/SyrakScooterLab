package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 16.1 – Regressionstests fuer den im Audit gefundenen UUID-Rollen-
 * Widerspruch, den Read-Pfad, die Firmware-Identitaetssicherung und die
 * 7-stufige Compatibility-Ladder.
 *
 * Alle Tests laufen auf der JVM ohne echtes Geraet. Sie belegen Codeverhalten,
 * NICHT die Funktion an realer Hardware.
 */
class Phase161BleRoleAuditTest {

    /* ------------------------------------------------------------------ */
    /*  1. Registry <-> Adapter: Write/Notify-Rollen                       */
    /* ------------------------------------------------------------------ */

    @Test
    fun `ninebot adapter write and notify lists match the registry`() {
        val caps = NinebotAdapter().capabilities
        assertEquals(
            BleUuids.candidateUuids("Ninebot / Xiaomi", UuidRole.WRITE_CHARACTERISTIC),
            caps.gattWriteCharUuids
        )
        assertEquals(
            BleUuids.candidateUuids("Ninebot / Xiaomi", UuidRole.NOTIFY_CHARACTERISTIC),
            caps.gattNotifyCharUuids
        )
        assertEquals(
            BleUuids.candidateUuids("Ninebot / Xiaomi", UuidRole.GATT_SERVICE),
            caps.gattServiceUuids
        )
    }

    @Test
    fun `navee adapter write and notify lists match the registry`() {
        val caps = NaveeGt3MaxAdapter().capabilities
        assertEquals(
            BleUuids.candidateUuids("Navee / Brightway", UuidRole.WRITE_CHARACTERISTIC),
            caps.gattWriteCharUuids
        )
        assertEquals(
            BleUuids.candidateUuids("Navee / Brightway", UuidRole.NOTIFY_CHARACTERISTIC),
            caps.gattNotifyCharUuids
        )
        assertEquals(
            BleUuids.candidateUuids("Navee / Brightway", UuidRole.GATT_SERVICE),
            caps.gattServiceUuids
        )
    }

    @Test
    fun `every adapter write uuid is a registry write candidate`() {
        for (adapter in ProtocolAdapters.all) {
            for (uuid in adapter.capabilities.gattWriteCharUuids) {
                assertTrue(
                    "${adapter.id}: $uuid ist nicht als Write-Kandidat registriert",
                    UuidRole.WRITE_CHARACTERISTIC in BleUuids.rolesOf(uuid)
                )
            }
        }
    }

    @Test
    fun `every adapter notify uuid is a registry notify candidate`() {
        for (adapter in ProtocolAdapters.all) {
            for (uuid in adapter.capabilities.gattNotifyCharUuids) {
                assertTrue(
                    "${adapter.id}: $uuid ist nicht als Notify-Kandidat registriert",
                    UuidRole.NOTIFY_CHARACTERISTIC in BleUuids.rolesOf(uuid)
                )
            }
        }
    }

    @Test
    fun `write and notify overlap only where the registry marks it ambiguous`() {
        for (adapter in ProtocolAdapters.all) {
            val overlap = adapter.capabilities.gattWriteCharUuids
                .intersect(adapter.capabilities.gattNotifyCharUuids.toSet())
            for (uuid in overlap) {
                assertTrue(
                    "${adapter.id}: $uuid steht in beiden Listen, Registry nennt die Rolle aber eindeutig",
                    BleUuids.isRoleAmbiguous(uuid)
                )
            }
        }
    }

    @Test
    fun `ninebot fff1 and fff2 roles are honestly ambiguous and unconfirmed`() {
        for (uuid in listOf(BleUuids.NINEBOT_CHAR_WRITE, BleUuids.NINEBOT_CHAR_WRITE_ALT)) {
            assertTrue("$uuid sollte als mehrdeutig gelten", BleUuids.isRoleAmbiguous(uuid))
            val def = BleUuids.definitionOf(uuid)
            assertNotNull(def)
            assertFalse("$uuid darf nicht als hardwarebestaetigt gelten", def!!.confirmedOnHardware)
            assertEquals(UuidProvenance.PROJECT_CONSTANT, def.provenance)
        }
    }

    @Test
    fun `navee roles are ffe1 notify and ffe2 write`() {
        assertEquals(
            setOf(UuidRole.NOTIFY_CHARACTERISTIC),
            BleUuids.rolesOf(BleUuids.NAVEE_CHAR_MAIN)
        )
        assertEquals(
            setOf(UuidRole.WRITE_CHARACTERISTIC),
            BleUuids.rolesOf(BleUuids.NAVEE_CHAR_ALT)
        )
    }

    @Test
    fun `cccd is the documented descriptor used by the active engine`() {
        val cccd = BleUuids.definitionOf(BleUuids.CCCD)
        assertNotNull(cccd)
        assertEquals(UuidRole.DESCRIPTOR, cccd!!.role)
        assertEquals(UuidProvenance.DOCUMENTED, cccd.provenance)
        assertTrue(cccd.usedBy.any { it.contains("BleEngine") })
        // Bluetooth-SIG-Standardwert fuer 0x2902.
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", BleUuids.CCCD)
    }

    /* ------------------------------------------------------------------ */
    /*  2. Read-Pfad: Erfolg nur bei passendem Callback + Bytes           */
    /* ------------------------------------------------------------------ */

    @Test
    fun `read success requires gatt success and non empty bytes`() {
        assertEquals(ReadStatus.SUCCESS, ReadClassifier.fromCallback(0, 0, 8))
        assertEquals(ReadStatus.GATT_ERROR, ReadClassifier.fromCallback(133, 0, 8))
        assertEquals(ReadStatus.EMPTY_VALUE, ReadClassifier.fromCallback(0, 0, 0))
        assertFalse(ReadStatus.GATT_ERROR.isSuccess)
        assertFalse(ReadStatus.EMPTY_VALUE.isSuccess)
        assertFalse(ReadStatus.TIMEOUT.isSuccess)
        assertFalse(ReadStatus.DISCONNECTED.isSuccess)
        assertFalse(ReadStatus.ABORTED.isSuccess)
    }

    @Test
    fun `timeout is reported only after the limit and never as success`() {
        val t = ReadTracker()
        t.begin("fff1", 1, 1_000)
        assertNull(t.timeout(3_999, 3_000))
        val timedOut = t.timeout(4_000, 3_000)
        assertNotNull(timedOut)
        assertFalse(t.isPending())
        assertFalse(ReadStatus.TIMEOUT.isSuccess)
    }

    @Test
    fun `disconnect aborts the pending read`() {
        val t = ReadTracker()
        t.begin("fff1", 7, 0)
        val aborted = t.abort()
        assertNotNull(aborted)
        assertEquals("fff1", aborted!!.uuid)
        assertFalse(t.isPending())
        // Ein verspaeteter Callback darf danach nicht mehr matchen.
        assertFalse(t.matches("fff1", 7))
    }

    @Test
    fun `late callback from a previous request cannot complete a new one`() {
        val t = ReadTracker()
        t.begin("fff1", 1, 0)
        assertNotNull(t.complete())
        t.begin("fff2", 1, 10)
        // Alter Callback fuer fff1 (gleiche Generation) darf den neuen Read nicht abschliessen.
        assertFalse(t.matches("fff1", 1))
        assertTrue(t.matches("fff2", 1))
    }

    @Test
    fun `callback from an old connection generation is ignored`() {
        val t = ReadTracker()
        t.begin("fff1", 2, 0)
        assertFalse(t.matches("fff1", 1))
        assertTrue(t.matches("fff1", 2))
    }

    @Test
    fun `parallel reads are rejected while one is in flight`() {
        val t = ReadTracker()
        assertTrue(t.begin("fff1", 1, 0))
        assertFalse(t.begin("fff2", 1, 1))
        assertEquals("fff1", t.pendingUuid())
        t.complete()
        assertTrue(t.begin("fff2", 1, 2))
    }

    /* ------------------------------------------------------------------ */
    /*  3. Firmware-Import: Paket/Hash/Modell muessen passen              */
    /* ------------------------------------------------------------------ */

    @Test
    fun `firmware import blocks package hash and model deviations`() {
        // Alles korrekt -> erlaubt.
        assertEquals(
            FirmwareImportDecision.ALLOWED,
            FirmwareImportGuard.evaluate("pkg-a", "pkg-a", true, true, true)
        )
        // Datei gehoert zu fremdem Paket.
        assertEquals(
            FirmwareImportDecision.PACKAGE_MISMATCH,
            FirmwareImportGuard.evaluate("pkg-a", "pkg-b", true, true, true)
        )
        // Kein ausgewaehltes Paket.
        assertEquals(
            FirmwareImportDecision.PACKAGE_MISMATCH,
            FirmwareImportGuard.evaluate("pkg-a", null, true, true, true)
        )
        // Hash nicht verifiziert.
        assertEquals(
            FirmwareImportDecision.HASH_NOT_VERIFIED,
            FirmwareImportGuard.evaluate("pkg-a", "pkg-a", true, false, true)
        )
        // Modell nicht kompatibel.
        assertEquals(
            FirmwareImportDecision.MODEL_MISMATCH,
            FirmwareImportGuard.evaluate("pkg-a", "pkg-a", true, true, false)
        )
        // Datei nicht gueltig geprueft.
        assertEquals(
            FirmwareImportDecision.NO_VALIDATION,
            FirmwareImportGuard.evaluate("pkg-a", "pkg-a", false, true, true)
        )
    }

    @Test
    fun `hard flash stays locked unless validation model and link all hold`() {
        val good = FirmwareValidation(
            valid = true, message = "ok", sha256 = "abc", isZip = false,
            modelCompatible = true, extractedBinary = ByteArray(4), hashVerified = true
        )
        assertTrue(FlashHonesty.hardFlashAllowed(good, modelKnown = true, connected = true))
        assertFalse(FlashHonesty.hardFlashAllowed(good, modelKnown = false, connected = true))
        assertFalse(FlashHonesty.hardFlashAllowed(good, modelKnown = true, connected = false))
        assertFalse(FlashHonesty.hardFlashAllowed(good.copy(hashVerified = false), true, true))
        assertFalse(FlashHonesty.hardFlashAllowed(good.copy(modelCompatible = false), true, true))
        assertFalse(FlashHonesty.hardFlashAllowed(null, true, true))
    }

    /* ------------------------------------------------------------------ */
    /*  4. Unbekanntes Geraet bleibt read-only                            */
    /* ------------------------------------------------------------------ */

    @Test
    fun `unknown device stays read only even with manual confirmation`() {
        assertTrue(
            WriteGuard.isReadOnly(
                ScooterModel.UNKNOWN,
                hardwareVerified = true,
                adapter = NinebotAdapter()
            )
        )
        assertEquals(
            WriteDecision.BLOCKED_UNKNOWN_MODEL,
            WriteGuard.evaluate(ScooterModel.UNKNOWN, true, NinebotAdapter())
        )
    }

    /* ------------------------------------------------------------------ */
    /*  5. Simulation / GATT-Fund ist keine Hardware-Verifikation          */
    /* ------------------------------------------------------------------ */

    @Test
    fun `simulated detection cannot unlock hardware verification`() {
        // Nur "erkannt" + GATT gelesen, aber kein echter Read -> hoechstens Stufe 2.
        val report = CompatibilityLadder.evaluate(
            CompatibilityEvidence(detected = true, gattDiscovered = true)
        )
        assertEquals(CompatibilityStage.GATT_DISCOVERED, report.highest)
        assertFalse(report.stages.first { it.stage == CompatibilityStage.HARDWARE_VERIFIED }.reached)
    }

    @Test
    fun `read property found is not a successful read`() {
        val report = CompatibilityLadder.evaluate(
            CompatibilityEvidence(
                detected = true,
                gattDiscovered = true,
                readPropertyFound = true
            )
        )
        assertEquals(CompatibilityStage.READ_PROPERTY_FOUND, report.highest)
        assertFalse(report.stages.first { it.stage == CompatibilityStage.READ_ONLY_TESTED }.reached)
    }

    /* ------------------------------------------------------------------ */
    /*  6. Jede der 7 Stufen braucht ihren eigenen Beleg                  */
    /* ------------------------------------------------------------------ */

    @Test
    fun `each compatibility stage requires its own evidence`() {
        val steps = listOf(
            CompatibilityEvidence(detected = true) to CompatibilityStage.DETECTED,
            CompatibilityEvidence(detected = true, gattDiscovered = true) to
                CompatibilityStage.GATT_DISCOVERED,
            CompatibilityEvidence(
                detected = true, gattDiscovered = true, readPropertyFound = true
            ) to CompatibilityStage.READ_PROPERTY_FOUND,
            CompatibilityEvidence(
                detected = true, gattDiscovered = true, readPropertyFound = true,
                readOnlyTested = true
            ) to CompatibilityStage.READ_ONLY_TESTED,
            CompatibilityEvidence(
                detected = true, gattDiscovered = true, readPropertyFound = true,
                readOnlyTested = true, telemetryValidated = true
            ) to CompatibilityStage.TELEMETRY_VALIDATED,
            CompatibilityEvidence(
                detected = true, gattDiscovered = true, readPropertyFound = true,
                readOnlyTested = true, telemetryValidated = true,
                writeProtocolValidated = true
            ) to CompatibilityStage.WRITE_PROTOCOL_VALIDATED,
            CompatibilityEvidence(
                detected = true, gattDiscovered = true, readPropertyFound = true,
                readOnlyTested = true, telemetryValidated = true,
                writeProtocolValidated = true, hardwareVerified = true
            ) to CompatibilityStage.HARDWARE_VERIFIED
        )
        for ((evidence, expected) in steps) {
            assertEquals(
                "Stufe $expected wurde ohne eigenen Beleg falsch bewertet",
                expected,
                CompatibilityLadder.evaluate(evidence).highest
            )
        }
    }

    @Test
    fun `a skipped stage stops the ladder`() {
        // Telemetrie belegt, aber Read-Test fehlt -> Leiter stoppt bei READ_PROPERTY_FOUND.
        val report = CompatibilityLadder.evaluate(
            CompatibilityEvidence(
                detected = true, gattDiscovered = true, readPropertyFound = true,
                readOnlyTested = false, telemetryValidated = true
            )
        )
        assertEquals(CompatibilityStage.READ_PROPERTY_FOUND, report.highest)
        assertFalse(report.stages.first { it.stage == CompatibilityStage.TELEMETRY_VALIDATED }.reached)
    }
}
