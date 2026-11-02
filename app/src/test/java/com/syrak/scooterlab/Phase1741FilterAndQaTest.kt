package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.recognition.BrandFamily
import com.syrak.scooterlab.recognition.FingerprintMaturity
import com.syrak.scooterlab.recognition.GattCharacteristicFeature
import com.syrak.scooterlab.recognition.GattDiscoveryStatus
import com.syrak.scooterlab.recognition.GattServiceFeature
import com.syrak.scooterlab.recognition.HardwareGattSnapshot
import com.syrak.scooterlab.recognition.HardwareRecognitionSnapshot
import com.syrak.scooterlab.recognition.HardwareScanSnapshot
import com.syrak.scooterlab.recognition.HardwareTestFilter
import com.syrak.scooterlab.recognition.HardwareTestImporter
import com.syrak.scooterlab.recognition.HardwareTestJson
import com.syrak.scooterlab.recognition.HardwareTestLoadStatus
import com.syrak.scooterlab.recognition.HardwareTestPersistence
import com.syrak.scooterlab.recognition.HardwareTestRecord
import com.syrak.scooterlab.recognition.HardwareTestStore
import com.syrak.scooterlab.recognition.IsoDate
import com.syrak.scooterlab.recognition.RecognitionConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17.4.1 — Tests fuer die interaktive Filterlogik, den praktischen
 * JSON-Rundlauf und die Backup-/Wiederherstellungsfaelle.
 *
 * Reine Kotlin-Logik ohne Android-Laufzeit, ohne echte Geraete und ohne
 * erfundene Messwerte. Die Tests bilden exakt das ab, was die UI aufruft:
 * [HardwareTestFilter.apply] plus Sortierung nach Datum.
 */
class Phase1741FilterAndQaTest {

    private val naveeService = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val naveeNotify = "0000ffe1-0000-1000-8000-00805f9b34fb"
    private val naveeWrite = "0000ffe2-0000-1000-8000-00805f9b34fb"
    private val naveeFingerprintId = "navee-gt3max-unconfirmed"

    /* ----------------------------- Fixtures ----------------------------- */

    private fun scan() = HardwareScanSnapshot(
        deviceName = "NAVEE GT3 Max",
        serviceUuids = listOf(naveeService),
        manufacturerIds = emptyList(),
        rssi = -55
    )

    private fun gatt() = HardwareGattSnapshot(
        discoveryStatus = GattDiscoveryStatus.COMPLETE,
        services = listOf(
            GattServiceFeature(
                uuid = naveeService,
                characteristics = listOf(
                    GattCharacteristicFeature(naveeNotify, setOf(GattProperty.NOTIFY)),
                    GattCharacteristicFeature(
                        naveeWrite,
                        setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)
                    )
                )
            )
        )
    )

    private fun observed() = HardwareRecognitionSnapshot(
        confidence = RecognitionConfidence.PROBABLE,
        detectedBrand = ScooterManufacturer.NAVEE,
        detectedBrandFamily = BrandFamily.NAVEE,
        detectedModelFamily = "NAVEE",
        confirmedModel = null,
        probableModel = "NAVEE GT3 Max (unbestaetigt)",
        matchedProfileId = naveeFingerprintId
    )

    private fun record(
        id: String = "test-1",
        manufacturer: ScooterManufacturer = ScooterManufacturer.NAVEE,
        modelName: String = "NAVEE GT3 Max",
        testDate: String = "2026-11-15",
        fingerprintId: String? = naveeFingerprintId,
        scan: HardwareScanSnapshot? = scan(),
        gatt: HardwareGattSnapshot? = gatt(),
        observed: HardwareRecognitionSnapshot? = observed(),
        addressMasked: String? = "AA:BB:CC:DD:EE:FF"
    ) = HardwareTestRecord(
        id = id,
        manufacturer = manufacturer,
        modelName = modelName,
        testDate = testDate,
        tester = "Tester A",
        addressMasked = addressMasked,
        expectedFingerprintId = fingerprintId,
        scan = scan,
        gatt = gatt,
        observedRecognition = observed,
        notes = "Notiz"
    )

    private fun sampleSet() = listOf(
        record(id = "a", manufacturer = ScooterManufacturer.NAVEE, modelName = "GT3 Max", testDate = "2026-01-10"),
        record(id = "b", manufacturer = ScooterManufacturer.XIAOMI, modelName = "Mi Scooter Pro 2", testDate = "2026-03-05", fingerprintId = null),
        record(id = "c", manufacturer = ScooterManufacturer.NAVEE, modelName = "GT3 Max", testDate = "2026-07-22"),
        record(id = "d", manufacturer = ScooterManufacturer.NINEBOT, modelName = "Max G30", testDate = "2026-05-01", fingerprintId = null)
    )

    private class FakePersistence(
        var primary: String? = null,
        var backup: String? = null,
        var failWrite: Boolean = false,
        var primaryOnlyWrites: Int = 0
    ) : HardwareTestPersistence {
        override fun read(): String? = primary
        override fun readBackup(): String? = backup
        override fun write(payload: String): Boolean {
            if (failWrite) return false
            backup = primary
            primary = payload
            return true
        }

        override fun writePrimaryOnly(payload: String): Boolean {
            if (failWrite) return false
            primaryOnlyWrites++
            primary = payload
            return true
        }
    }

    /* ===================== 1. Interaktive Filter ===================== */

    @Test
    fun combinedFilterMatchesAllCriteriaAtOnce() {
        val filter = HardwareTestFilter(
            manufacturer = ScooterManufacturer.NAVEE,
            modelQuery = "gt3",
            fingerprintId = naveeFingerprintId,
            dateFrom = "2026-01-01",
            dateTo = "2026-12-31"
        )
        val result = HardwareTestFilter.apply(sampleSet(), filter)
        assertEquals(listOf("a", "c"), result.map { it.id })
    }

    @Test
    fun resetFilterReturnsEveryRecord() {
        val all = sampleSet()
        assertEquals(4, HardwareTestFilter.apply(all, HardwareTestFilter()).size)
        assertTrue(HardwareTestFilter().isEmpty)
    }

    @Test
    fun filterThatMatchesNothingReturnsEmptyListWithoutError() {
        val filter = HardwareTestFilter(manufacturer = ScooterManufacturer.SEGWAY)
        val result = HardwareTestFilter.apply(sampleSet(), filter)
        assertTrue(result.isEmpty())
        // Der Bestand bleibt unberuehrt.
        assertEquals(4, sampleSet().size)
    }

    @Test
    fun filteringNeverMutatesTheStoredRecords() {
        val all = sampleSet()
        val snapshot = all.map { it.id to it.testDate }
        HardwareTestFilter.apply(all, HardwareTestFilter(manufacturer = ScooterManufacturer.NAVEE))
        assertEquals(snapshot, all.map { it.id to it.testDate })
        assertEquals(4, all.size)
    }

    @Test
    fun dateRangeIsInclusiveOnBothEnds() {
        val filter = HardwareTestFilter(dateFrom = "2026-03-05", dateTo = "2026-05-01")
        val result = HardwareTestFilter.apply(sampleSet(), filter).map { it.id }.sorted()
        assertEquals(listOf("b", "d"), result)
    }

    @Test
    fun modelQueryIsCaseInsensitiveAndTrimmed() {
        val filter = HardwareTestFilter(modelQuery = "  mi scooter  ")
        val result = HardwareTestFilter.apply(sampleSet(), filter)
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun maturityFilterSeparatesConfirmedFromDocumented() {
        // Records ohne Fingerprint gelten als DOCUMENTED, vollstaendige als HARDWARE_CONFIRMED.
        val documented = HardwareTestFilter(maturity = FingerprintMaturity.DOCUMENTED)
        assertEquals(listOf("b", "d"), HardwareTestFilter.apply(sampleSet(), documented).map { it.id }.sorted())

        val confirmed = HardwareTestFilter(maturity = FingerprintMaturity.HARDWARE_CONFIRMED)
        assertEquals(listOf("a", "c"), HardwareTestFilter.apply(sampleSet(), confirmed).map { it.id }.sorted())
    }

    @Test
    fun sortingByDateDescendingIsPreservedAfterFiltering() {
        val filtered = HardwareTestFilter.apply(sampleSet(), HardwareTestFilter(manufacturer = ScooterManufacturer.NAVEE))
            .sortedByDescending { it.testDate }
        assertEquals(listOf("c", "a"), filtered.map { it.id })
    }

    /* ===================== 2. JSON-Rundlauf ===================== */

    @Test
    fun exportThenImportIntoEmptyRegistryRestoresAllRecords() {
        val exported = HardwareTestJson.encode(sampleSet())
        val decoded = HardwareTestJson.decode(exported)
        assertTrue(decoded.errors.toString(), decoded.isSuccess)
        assertEquals(4, decoded.records.size)

        val outcome = HardwareTestImporter.importJson(emptyList(), exported)
        assertEquals(4, outcome.importedIds.size)
        assertTrue(outcome.rejected.isEmpty())
        assertEquals(sampleSet().map { it.id }.sorted(), outcome.records.map { it.id }.sorted())
    }

    @Test
    fun exportMasksEveryBluetoothAddress() {
        val exported = HardwareTestJson.encode(listOf(record(addressMasked = "AA:BB:CC:DD:EE:FF")))
        assertFalse(exported.contains("CC:DD"))
        assertTrue(exported.contains("AA:BB:**:**:EE:FF"))
    }

    @Test
    fun exportContainsNoSerialNumberField() {
        val exported = HardwareTestJson.encode(sampleSet()).lowercase()
        assertFalse(exported.contains("serial"))
        assertFalse(exported.contains("imei"))
    }

    @Test
    fun importRejectsUnknownManufacturerWithoutTouchingExistingRecords() {
        val existing = sampleSet()
        val payload = """
            {
              "schemaVersion": 1,
              "records": [
                {
                  "id": "x1",
                  "manufacturer": "TESLA",
                  "modelName": "Unbekannt",
                  "testDate": "2026-01-01"
                }
              ]
            }
        """.trimIndent()
        val outcome = HardwareTestImporter.importJson(existing, payload)
        assertTrue(outcome.importedIds.isEmpty())
        assertTrue(outcome.rejected.isNotEmpty())
        assertEquals(existing, outcome.records)
    }

    @Test
    fun importRejectsInvalidCalendarDate() {
        val payload = """
            {
              "schemaVersion": 1,
              "records": [
                {
                  "id": "x2",
                  "manufacturer": "NAVEE",
                  "modelName": "GT3 Max",
                  "testDate": "2026-02-31"
                }
              ]
            }
        """.trimIndent()
        val outcome = HardwareTestImporter.importJson(emptyList(), payload)
        assertTrue(outcome.importedIds.isEmpty())
        assertTrue(outcome.rejected.isNotEmpty())
    }

    /* ===================== 3. Backup & Wiederherstellung ===================== */

    @Test
    fun corruptPrimaryIsRecoveredFromBackupAndFlagged() {
        val good = HardwareTestJson.encode(sampleSet())
        val persistence = FakePersistence(primary = "{ kaputt", backup = good)
        val result = HardwareTestStore(persistence).load()

        assertEquals(HardwareTestLoadStatus.LOADED, result.status)
        assertTrue(result.usedBackup)
        assertEquals(4, result.records.size)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun corruptPrimaryAndCorruptBackupReportCorruptWithoutDeleting() {
        val persistence = FakePersistence(primary = "{ kaputt", backup = "auch kaputt")
        val result = HardwareTestStore(persistence).load()

        assertEquals(HardwareTestLoadStatus.CORRUPT, result.status)
        assertFalse(result.isUsable)
        // Nichts wird geloescht.
        assertEquals("{ kaputt", persistence.primary)
        assertEquals("auch kaputt", persistence.backup)
        assertNotNull(result.message)
    }

    @Test
    fun failedWriteKeepsPreviousPayloadAndExplainsIt() {
        val persistence = FakePersistence()
        val store = HardwareTestStore(persistence)
        assertTrue(store.save(sampleSet()).success)

        val before = persistence.primary
        persistence.failWrite = true
        val failed = store.save(listOf(record(id = "neu")))
        assertFalse(failed.success)
        assertEquals(before, persistence.primary)
        assertTrue(failed.message.contains("erhalten", ignoreCase = true))
    }

    @Test
    fun backupIsWrittenBeforeOverwritingPrimary() {
        val persistence = FakePersistence()
        val store = HardwareTestStore(persistence)
        store.save(listOf(record(id = "erste")))
        val firstPayload = persistence.primary
        store.save(listOf(record(id = "zweite")))

        assertEquals(firstPayload, persistence.backup)
        assertTrue(persistence.primary!!.contains("zweite"))
    }

    @Test
    fun savingARecoveredStateDoesNotOverwriteTheGoodBackup() {
        val good = HardwareTestJson.encode(sampleSet())
        val persistence = FakePersistence(primary = "{ kaputt", backup = good)
        val store = HardwareTestStore(persistence)

        val loaded = store.load()
        assertTrue(loaded.usedBackup)

        val saved = store.saveRecovered(loaded.records)
        assertTrue(saved.success)
        assertEquals(1, persistence.primaryOnlyWrites)
        // Das gute Backup bleibt unveraendert erhalten.
        assertEquals(good, persistence.backup)
        // Der Hauptstand ist jetzt lesbar.
        assertTrue(HardwareTestJson.decode(persistence.primary!!).isSuccess)
    }

    /* ===================== 4. Datumsvalidierung (Grenzfaelle) ===================== */

    @Test
    fun leapYearRulesAreAppliedCorrectly() {
        assertTrue(IsoDate.isValid("2024-02-29"))
        assertFalse(IsoDate.isValid("2026-02-29"))
        assertFalse(IsoDate.isValid("2100-02-29"))
        assertTrue(IsoDate.isValid("2000-02-29"))
    }

    @Test
    fun monthAndDayBoundsAreEnforced() {
        assertTrue(IsoDate.isValid("2026-12-31"))
        assertFalse(IsoDate.isValid("2026-00-10"))
        assertFalse(IsoDate.isValid("2026-12-00"))
        assertFalse(IsoDate.isValid("2026-04-31"))
    }
}
