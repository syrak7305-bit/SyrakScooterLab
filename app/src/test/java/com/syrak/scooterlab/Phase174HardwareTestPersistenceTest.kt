package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.recognition.BrandFamily
import com.syrak.scooterlab.recognition.BuiltInModelFingerprints
import com.syrak.scooterlab.recognition.FingerprintMaturity
import com.syrak.scooterlab.recognition.FingerprintQualityAudit
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
import com.syrak.scooterlab.recognition.HardwareTestRegistry
import com.syrak.scooterlab.recognition.HardwareTestStatus
import com.syrak.scooterlab.recognition.HardwareTestStore
import com.syrak.scooterlab.recognition.IsoDate
import com.syrak.scooterlab.recognition.RecognitionConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17.4 — Tests fuer Persistenz, JSON-Import/-Export, Filter,
 * Fingerprint-Qualitaet und echte Kalenderdatums-Validierung.
 *
 * Reine Kotlin-Logik ohne Android-Laufzeit, ohne echte Geraete und ohne
 * erfundene Messwerte.
 */
class Phase174HardwareTestPersistenceTest {

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

    private fun observed(
        brand: ScooterManufacturer = ScooterManufacturer.NAVEE,
        confidence: RecognitionConfidence = RecognitionConfidence.PROBABLE
    ) = HardwareRecognitionSnapshot(
        confidence = confidence,
        detectedBrand = brand,
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
        addressMasked: String? = "AA:BB:CC:DD:EE:FF",
        tester: String? = "Tester A",
        notes: String? = "Notiz"
    ) = HardwareTestRecord(
        id = id,
        manufacturer = manufacturer,
        modelName = modelName,
        testDate = testDate,
        tester = tester,
        addressMasked = addressMasked,
        expectedFingerprintId = fingerprintId,
        scan = scan,
        gatt = gatt,
        observedRecognition = observed,
        notes = notes
    )

    /** In-Memory-Speicher, der sich wie SharedPreferences verhaelt. */
    private class FakePersistence(
        var primary: String? = null,
        var backup: String? = null,
        var failWrite: Boolean = false,
        var throwOnWrite: Boolean = false
    ) : HardwareTestPersistence {
        override fun read(): String? = primary
        override fun readBackup(): String? = backup
        override fun write(payload: String): Boolean {
            if (throwOnWrite) throw IllegalStateException("Platte voll")
            if (failWrite) return false
            backup = primary
            primary = payload
            return true
        }
    }

    /* ================= 1. Persistenz ================= */

    @Test
    fun saveThenLoadReturnsSameRecords() {
        val persistence = FakePersistence()
        val store = HardwareTestStore(persistence)
        val records = listOf(record())

        assertTrue(store.save(records).success)
        val loaded = store.load()

        assertEquals(HardwareTestLoadStatus.LOADED, loaded.status)
        assertEquals(1, loaded.records.size)
        assertEquals("test-1", loaded.records[0].id)
        assertEquals("NAVEE GT3 Max", loaded.records[0].modelName)
        assertEquals("2026-11-15", loaded.records[0].testDate)
    }

    @Test
    fun restartSimulationLoadsFromSamePersistence() {
        val persistence = FakePersistence()
        HardwareTestStore(persistence).save(listOf(record(id = "a"), record(id = "b")))

        // Neue Store-Instanz = simulierter App-Neustart.
        val afterRestart = HardwareTestStore(persistence).load()

        assertEquals(HardwareTestLoadStatus.LOADED, afterRestart.status)
        assertEquals(listOf("a", "b"), afterRestart.records.map { it.id })
    }

    @Test
    fun emptyPersistenceReportsEmptyWithoutError() {
        val loaded = HardwareTestStore(FakePersistence()).load()
        assertEquals(HardwareTestLoadStatus.EMPTY, loaded.status)
        assertTrue(loaded.records.isEmpty())
        assertTrue(loaded.isUsable)
    }

    @Test
    fun multipleRecordsSurviveRoundTrip() {
        val persistence = FakePersistence()
        val store = HardwareTestStore(persistence)
        val records = listOf(
            record(id = "1"),
            record(id = "2", modelName = "NAVEE GT3 Pro", testDate = "2026-01-02"),
            record(id = "3", manufacturer = ScooterManufacturer.XIAOMI, fingerprintId = null)
        )
        store.save(records)
        val loaded = store.load()
        assertEquals(3, loaded.records.size)
        assertEquals(records.map { it.id }, loaded.records.map { it.id })
    }

    @Test
    fun corruptPrimaryWithoutBackupIsReportedAndNotDeleted() {
        val persistence = FakePersistence(primary = "{kaputt")
        val loaded = HardwareTestStore(persistence).load()

        assertEquals(HardwareTestLoadStatus.CORRUPT, loaded.status)
        assertFalse(loaded.isUsable)
        assertTrue(loaded.records.isEmpty())
        // Der defekte Stand bleibt unveraendert erhalten.
        assertEquals("{kaputt", persistence.primary)
    }

    @Test
    fun corruptPrimaryFallsBackToValidBackup() {
        val valid = HardwareTestJson.encode(listOf(record(id = "backup-1")))
        val persistence = FakePersistence(primary = "nicht json", backup = valid)

        val loaded = HardwareTestStore(persistence).load()

        assertEquals(HardwareTestLoadStatus.LOADED, loaded.status)
        assertTrue(loaded.usedBackup)
        assertEquals("backup-1", loaded.records[0].id)
        // Der defekte Hauptstand wird NICHT geloescht.
        assertEquals("nicht json", persistence.primary)
    }

    @Test
    fun failedWriteKeepsPreviousState() {
        val persistence = FakePersistence()
        val store = HardwareTestStore(persistence)
        store.save(listOf(record(id = "old")))

        persistence.failWrite = true
        val result = store.save(listOf(record(id = "new")))

        assertFalse(result.success)
        assertTrue(result.message.contains("bisherige Stand"))
        val loaded = store.load()
        assertEquals("old", loaded.records[0].id)
    }

    @Test
    fun writeExceptionIsReportedAndKeepsPreviousState() {
        val persistence = FakePersistence()
        val store = HardwareTestStore(persistence)
        store.save(listOf(record(id = "old")))

        persistence.throwOnWrite = true
        val result = store.save(listOf(record(id = "new")))

        assertFalse(result.success)
        assertEquals("old", store.load().records[0].id)
    }

    /* ================= 2. JSON-Export / -Import ================= */

    @Test
    fun exportImportRoundTripPreservesFields() {
        val original = listOf(record(id = "rt-1"))
        val json = HardwareTestJson.encode(original)
        val decoded = HardwareTestJson.decode(json)

        assertTrue(decoded.isSuccess)
        assertEquals(1, decoded.records.size)
        val restored = decoded.records[0]
        assertEquals("rt-1", restored.id)
        assertEquals(ScooterManufacturer.NAVEE, restored.manufacturer)
        assertEquals("NAVEE GT3 Max", restored.modelName)
        assertEquals("2026-11-15", restored.testDate)
        assertEquals(naveeFingerprintId, restored.expectedFingerprintId)
        assertEquals(GattDiscoveryStatus.COMPLETE, restored.gatt?.discoveryStatus)
        assertEquals(2, restored.gatt?.characteristicCount)
        assertEquals(RecognitionConfidence.PROBABLE, restored.observedRecognition?.confidence)
    }

    @Test
    fun exportMasksMacAndContainsNoSerialNumberField() {
        val json = HardwareTestJson.encode(listOf(record(addressMasked = "AA:BB:CC:DD:EE:FF")))

        assertTrue(json.contains("AA:BB:**:**:EE:FF"))
        assertFalse(json.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(json.contains("serial", ignoreCase = true))
    }

    @Test
    fun invalidJsonIsRejectedWithoutRecords() {
        val decoded = HardwareTestJson.decode("{ das ist kein json")
        assertFalse(decoded.isSuccess)
        assertTrue(decoded.records.isEmpty())
        assertTrue(decoded.errors.isNotEmpty())
    }

    @Test
    fun unknownSchemaVersionIsRejected() {
        val json = """{"schemaVersion": 99, "records": []}"""
        val decoded = HardwareTestJson.decode(json)
        assertFalse(decoded.isSuccess)
        assertTrue(decoded.errors.any { it.contains("Schema-Version") })
    }

    @Test
    fun missingSchemaVersionIsRejected() {
        val decoded = HardwareTestJson.decode("""{"records": []}""")
        assertFalse(decoded.isSuccess)
        assertTrue(decoded.errors.any { it.contains("schemaVersion") })
    }

    @Test
    fun duplicateIdsWithinFileAreRejected() {
        val json = HardwareTestJson.encode(listOf(record(id = "dup"), record(id = "dup")))
        val decoded = HardwareTestJson.decode(json)

        assertFalse(decoded.isSuccess)
        assertTrue(decoded.errors.any { it.contains("doppelt") || it.contains("Duplikat") || it.contains("dup") })
    }

    @Test
    fun missingRequiredFieldsAreRejected() {
        val noModel = """{"schemaVersion":1,"records":[
            {"id":"x","manufacturer":"NAVEE","modelName":"","testDate":"2026-11-15"}
        ]}"""
        assertFalse(HardwareTestJson.decode(noModel).isSuccess)

        val noDate = """{"schemaVersion":1,"records":[
            {"id":"x","manufacturer":"NAVEE","modelName":"Modell","testDate":""}
        ]}"""
        assertFalse(HardwareTestJson.decode(noDate).isSuccess)

        val unknownManufacturer = """{"schemaVersion":1,"records":[
            {"id":"x","manufacturer":"GIBTSNICHT","modelName":"Modell","testDate":"2026-11-15"}
        ]}"""
        assertFalse(HardwareTestJson.decode(unknownManufacturer).isSuccess)
    }

    @Test
    fun unknownFingerprintIdIsRejected() {
        val json = """{"schemaVersion":1,"records":[
            {"id":"x","manufacturer":"NAVEE","modelName":"Modell","testDate":"2026-11-15",
             "expectedFingerprintId":"erfunden-123"}
        ]}"""
        val decoded = HardwareTestJson.decode(json)
        assertFalse(decoded.isSuccess)
        assertTrue(decoded.errors.any { it.contains("Fingerprint") })
    }

    @Test
    fun importNeverOverwritesExistingRecord() {
        val existing = listOf(record(id = "same", modelName = "Original"))
        val incoming = HardwareTestJson.encode(listOf(record(id = "same", modelName = "Ueberschrieben")))

        val outcome = HardwareTestImporter.importJson(existing, incoming)

        assertEquals(0, outcome.importedCount)
        assertEquals(1, outcome.skippedCount)
        assertEquals("Original", outcome.records[0].modelName)
    }

    @Test
    fun importAddsNewRecordAndReportsCounts() {
        val existing = listOf(record(id = "a"))
        val incoming = HardwareTestJson.encode(listOf(record(id = "b", modelName = "Neu")))

        val outcome = HardwareTestImporter.importJson(existing, incoming)

        assertEquals(1, outcome.importedCount)
        assertEquals(0, outcome.skippedCount)
        assertEquals(2, outcome.records.size)
        assertTrue(outcome.importedIds.contains("b"))
    }

    @Test
    fun importSkipsDuplicateContentUnderDifferentId() {
        val existing = listOf(record(id = "a"))
        val incoming = HardwareTestJson.encode(listOf(record(id = "b")))

        val outcome = HardwareTestImporter.importJson(existing, incoming)

        assertEquals(0, outcome.importedCount)
        assertEquals(1, outcome.skippedCount)
        assertEquals(1, outcome.records.size)
    }

    @Test
    fun invalidImportDoesNotDamageExistingRecords() {
        val existing = listOf(record(id = "keep"))
        val outcome = HardwareTestImporter.importJson(existing, "{kaputt")

        assertEquals(existing, outcome.records)
        assertTrue(outcome.rejectedCount > 0)
        assertEquals(0, outcome.importedCount)
    }

    /* ================= 3. Filter ================= */

    @Test
    fun filterByManufacturerAndModelAndDate() {
        val records = listOf(
            record(id = "1", manufacturer = ScooterManufacturer.NAVEE, modelName = "GT3 Max", testDate = "2026-01-10"),
            record(id = "2", manufacturer = ScooterManufacturer.XIAOMI, modelName = "Mi 3", testDate = "2026-02-10", fingerprintId = null),
            record(id = "3", manufacturer = ScooterManufacturer.NAVEE, modelName = "GT3 Pro", testDate = "2026-03-10")
        )

        assertEquals(
            listOf("1", "3"),
            HardwareTestFilter.apply(records, HardwareTestFilter(manufacturer = ScooterManufacturer.NAVEE)).map { it.id }
        )
        assertEquals(
            listOf("1"),
            HardwareTestFilter.apply(records, HardwareTestFilter(modelQuery = "max")).map { it.id }
        )
        assertEquals(
            listOf("2", "3"),
            HardwareTestFilter.apply(records, HardwareTestFilter(dateFrom = "2026-02-01")).map { it.id }
        )
        assertEquals(
            listOf("1"),
            HardwareTestFilter.apply(records, HardwareTestFilter(dateTo = "2026-01-31")).map { it.id }
        )
    }

    @Test
    fun emptyFilterKeepsAllRecords() {
        val records = listOf(record(id = "1"), record(id = "2"))
        assertTrue(HardwareTestFilter().isEmpty)
        assertEquals(2, HardwareTestFilter.apply(records, HardwareTestFilter()).size)
    }

    /* ================= 4. Fingerprint-Qualitaet ================= */

    @Test
    fun auditWithoutTestsKeepsDocumentedMaturity() {
        val report = FingerprintQualityAudit.auditOne(
            BuiltInModelFingerprints.byId(naveeFingerprintId)!!,
            HardwareTestRegistry.empty()
        )
        assertEquals(0, report.testCount)
        assertEquals(FingerprintMaturity.DOCUMENTED, report.maturity)
        assertEquals(HardwareTestStatus.NOT_TESTED, report.declaredStatus)
    }

    @Test
    fun fullyConfirmingRecordProducesHardwareConfirmed() {
        val report = FingerprintQualityAudit.auditOne(
            BuiltInModelFingerprints.byId(naveeFingerprintId)!!,
            HardwareTestRegistry.of(record(id = "good"))
        )
        assertEquals(FingerprintMaturity.HARDWARE_CONFIRMED, report.maturity)
        assertTrue(report.confirmingTestIds.contains("good"))
        assertTrue(report.missingEvidence.isEmpty())
    }

    @Test
    fun contradictoryRecordIsReported() {
        val report = FingerprintQualityAudit.auditOne(
            BuiltInModelFingerprints.byId(naveeFingerprintId)!!,
            HardwareTestRegistry.of(record(id = "bad", observed = observed(brand = ScooterManufacturer.NINEBOT)))
        )
        assertTrue(report.hasContradictions)
        assertTrue(report.contradictoryTestIds.contains("bad"))
    }

    @Test
    fun multipleTestsPerFingerprintAreCounted() {
        val report = FingerprintQualityAudit.auditOne(
            BuiltInModelFingerprints.byId(naveeFingerprintId)!!,
            HardwareTestRegistry.of(record(id = "a"), record(id = "b"))
        )
        assertEquals(2, report.testCount)
    }

    @Test
    fun incompleteRecordListsMissingEvidence() {
        val report = FingerprintQualityAudit.auditOne(
            BuiltInModelFingerprints.byId(naveeFingerprintId)!!,
            HardwareTestRegistry.of(record(id = "partial", gatt = null))
        )
        assertTrue(report.missingEvidence.isNotEmpty())
    }

    @Test
    fun auditNeverAutoUpgradesTheFingerprint() {
        val fingerprint = BuiltInModelFingerprints.byId(naveeFingerprintId)!!
        val report = FingerprintQualityAudit.auditOne(
            fingerprint,
            HardwareTestRegistry.of(record(id = "good"))
        )
        assertEquals(FingerprintMaturity.HARDWARE_CONFIRMED, report.maturity)
        assertTrue(report.upgradePending)
        // Das Profil selbst bleibt unveraendert.
        assertEquals(HardwareTestStatus.NOT_TESTED, fingerprint.hardwareTestStatus)
        assertFalse(fingerprint.isHardwareConfirmed)
    }

    @Test
    fun allBuiltInFingerprintsRemainNotTested() {
        for (fingerprint in BuiltInModelFingerprints.all()) {
            assertEquals(HardwareTestStatus.NOT_TESTED, fingerprint.hardwareTestStatus)
        }
    }

    /* ================= 5. Kalenderdatums-Validierung ================= */

    @Test
    fun validCalendarDatesAreAccepted() {
        assertTrue(IsoDate.isValid("2026-02-28"))
        assertTrue(IsoDate.isValid("2024-02-29"))
        assertTrue(IsoDate.isValid("2026-12-31"))
        assertTrue(IsoDate.isValid("2026-01-01"))
    }

    @Test
    fun impossibleCalendarDatesAreRejected() {
        assertFalse(IsoDate.isValid("2026-02-31"))
        assertFalse(IsoDate.isValid("2026-13-01"))
        assertFalse(IsoDate.isValid("2026-04-31"))
        assertFalse(IsoDate.isValid("2026-00-10"))
        assertFalse(IsoDate.isValid("2026-01-00"))
        assertFalse(IsoDate.isValid("2026-02-29")) // 2026 ist kein Schaltjahr
    }

    @Test
    fun wrongDateFormatIsRejected() {
        assertFalse(IsoDate.isValid("15.11.2026"))
        assertFalse(IsoDate.isValid("2026/11/15"))
        assertFalse(IsoDate.isValid("2026-1-5"))
        assertFalse(IsoDate.isValid(""))
        assertFalse(IsoDate.isValid(null))
    }

    @Test
    fun recordHasValidTestDateReflectsCalendarValidation() {
        assertTrue(record(testDate = "2026-02-28").hasValidTestDate)
        assertFalse(record(testDate = "2026-02-31").hasValidTestDate)
        assertFalse(record(testDate = "2026-13-01").hasValidTestDate)
    }

    @Test
    fun incompleteRecordIsNotHardwareConfirmed() {
        val assessment = com.syrak.scooterlab.recognition.HardwareTestEvaluator.assess(
            record(id = "incomplete", gatt = null),
            BuiltInModelFingerprints.byId(naveeFingerprintId)!!
        )
        assertFalse(assessment.isConfirming)
        assertTrue(assessment.maturity != FingerprintMaturity.HARDWARE_CONFIRMED)
    }
}
