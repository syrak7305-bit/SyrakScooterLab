package com.syrak.scooterlab

import android.app.Activity
import android.content.DialogInterface
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.syrak.scooterlab.recognition.BrandFamily
import com.syrak.scooterlab.recognition.BuiltInModelFingerprints
import com.syrak.scooterlab.recognition.GattDiscoveryStatus
import com.syrak.scooterlab.recognition.HardwareGattSnapshot
import com.syrak.scooterlab.recognition.HardwareRecognitionSnapshot
import com.syrak.scooterlab.recognition.HardwareScanSnapshot
import com.syrak.scooterlab.recognition.HardwareTestJson
import com.syrak.scooterlab.recognition.HardwareTestRecord
import com.syrak.scooterlab.recognition.RecognitionConfidence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * PHASE 17.4.2 — LAUFZEITPRUEFUNG DER HARDWARE-TESTVERWALTUNG.
 *
 * Diese Tests starten die ECHTE [MainActivity] unter Robolectric auf der JVM:
 * echter Lifecycle, echte View-Hierarchie, echte Dialoge, echte
 * SharedPreferences. Sie ersetzen KEINEN Geraetetest — es gibt in dieser
 * Umgebung weder Geraet noch Emulator (siehe PHASE17.4.2_ERGEBNIS.md).
 *
 * Alle Datensaetze sind synthetische Testdaten. Es werden keine echten
 * Scooter-, Nutzer- oder Messdaten erfunden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase1742RuntimeUiTest {

    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        Rt.acceptStartGates(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            runCatching { controller.pause().stop().destroy() }
        }
    }

    private fun launch(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Rt.idle()
        activity = controller.get()
        return activity
    }

    private fun decor(): View = activity.window.decorView

    private fun openConfig() {
        assertTrue("CONFIG-Tab nicht klickbar", Rt.click(decor(), "CONFIG"))
    }

    private fun seedThree() {
        Rt.seed(
            RuntimeEnvironment.getApplication(),
            listOf(
                Rt.record("t1", ScooterManufacturer.NAVEE, "Navee GT3 Max", "2025-03-01", "navee-gt3max-unconfirmed"),
                Rt.record("t2", ScooterManufacturer.XIAOMI, "Mi Pro 2", "2025-01-15", "xiaomi-legacy-unconfirmed"),
                Rt.record("t3", ScooterManufacturer.NINEBOT, "Max G30", "2024-11-20", null)
            )
        )
    }

    private fun expandFilter() {
        assertTrue("Filterzeile nicht klickbar", Rt.click(decor(), "FILTER"))
        assertTrue("Filterbereich nicht aufgeklappt", Rt.hasText(decor(), "ANWENDEN"))
    }

    /** Container des Filterbereichs: Elternknoten des Labels "MARKE". */
    private fun filterContainer(): View =
        Rt.viewWithText(decor(), "MARKE")?.parent as? View
            ?: error("Filtercontainer nicht gefunden")

    private fun filterSpinners() = Rt.spinners(filterContainer())

    private fun filterEdits() = Rt.editTexts(filterContainer())

    private fun applyFilter() {
        assertTrue("ANWENDEN nicht klickbar", Rt.click(decor(), "ANWENDEN"))
    }

    private fun hitCount(): String? =
        Rt.texts(decor()).firstOrNull {
            it.contains("entsprechen dem Filter") || it.contains("neueste zuerst")
        }

    private fun rowDates(): List<String> =
        Rt.texts(decor()).filter { Regex("^\\d{4}-\\d{2}-\\d{2} · .*").matches(it) }

    private fun latestDialog(): AlertDialog {
        // AppCompat zeigt Dialoge ueber eine Handler-Nachricht an; erst nach
        // dem Idle ist der onShow-Listener (SPEICHERN-Handler) installiert.
        Rt.idle(1)
        return Rt.latestAlertDialog() ?: error("Kein Dialog angezeigt")
    }

    private fun importJsonFile(json: String) {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "import-${System.nanoTime()}.json")
        file.writeText(json)
        Rt.callPrivate(activity, "handleHardwareTestImport", Uri.fromFile(file))
        Rt.idle(2)
    }

    private fun dismissImportDialog() {
        val dialog = latestDialog()
        assertTrue(
            "Importdialog ohne Zusammenfassung: ${Rt.dialogTexts(dialog)}",
            Rt.dialogTexts(dialog).any { it.startsWith("Import:") }
        )
        Rt.clickDialogButton(dialog, DialogInterface.BUTTON_POSITIVE)
        Rt.idle(2)
    }

    /* ================================================================== */
    /*  A. START & UEBERSICHT                                             */
    /* ================================================================== */

    @Test
    fun activityStartsAndShowsAllTabs() {
        launch()
        val texts = Rt.texts(decor())
        listOf("INFO", "TOOLS", "DASHBOARD", "FLASH", "CONFIG").forEach {
            assertTrue("Tab $it fehlt: $texts", texts.contains(it))
        }
    }

    @Test
    fun configTabShowsEmptyHardwareSection() {
        launch()
        openConfig()
        assertTrue(Rt.hasText(decor(), "HARDWARE-TESTS & FINGERPRINT-QUALITAET"))
        assertTrue(Rt.hasText(decor(), "Noch keine Hardware-Tests erfasst."))
        assertTrue(Rt.hasText(decor(), "NEUEN TEST ANLEGEN"))
        assertFalse("Filter darf ohne Daten nicht erscheinen", Rt.hasText(decor(), "FILTER"))
    }

    /* ================================================================== */
    /*  B. FILTERBEREICH                                                  */
    /* ================================================================== */

    @Test
    fun filterPanelExpandsAndCollapses() {
        seedThree()
        launch()
        openConfig()
        assertTrue(Rt.hasText(decor(), "FILTER"))
        assertFalse(Rt.hasText(decor(), "ANWENDEN"))

        expandFilter()
        assertTrue(Rt.hasText(decor(), "FILTER AUSBLENDEN"))
        assertTrue(Rt.hasText(decor(), "MARKE"))
        assertTrue(Rt.hasText(decor(), "MODELL"))
        assertTrue(Rt.hasText(decor(), "FINGERPRINT"))
        assertTrue(Rt.hasText(decor(), "REIFEGRAD"))
        assertTrue(Rt.hasText(decor(), "ZEITRAUM"))
        assertEquals("Filter braucht genau 3 Auswahllisten", 3, filterSpinners().size)

        assertTrue(Rt.click(decor(), "FILTER AUSBLENDEN"))
        assertFalse("Filterbereich blieb offen", Rt.hasText(decor(), "ANWENDEN"))
        assertTrue(Rt.hasText(decor(), "FILTER"))
    }

    @Test
    fun manufacturerFilterNarrowsListAndShowsHitCount() {
        seedThree()
        launch()
        openConfig()
        expandFilter()

        filterSpinners()[0].setSelection(1) // [Alle, NAVEE, XIAOMI, NINEBOT, ...]
        applyFilter()

        assertEquals("1 von 3 Test(s) entsprechen dem Filter", hitCount())
        assertEquals(listOf("2025-03-01"), rowDates().map { it.substringBefore(" ·") })
    }

    @Test
    fun modelSubstringFilterIgnoresCase() {
        seedThree()
        launch()
        openConfig()
        expandFilter()

        filterEdits()[0].setText("PRO")
        applyFilter()

        assertEquals("1 von 3 Test(s) entsprechen dem Filter", hitCount())
        assertEquals(listOf("2025-01-15"), rowDates().map { it.substringBefore(" ·") })
    }

    @Test
    fun fingerprintFilterSelectsSingleFingerprint() {
        seedThree()
        launch()
        openConfig()
        expandFilter()

        val index = BuiltInModelFingerprints.all()
            .indexOfFirst { it.id == "navee-gt3max-unconfirmed" } + 1
        filterSpinners()[1].setSelection(index)
        applyFilter()

        assertEquals("1 von 3 Test(s) entsprechen dem Filter", hitCount())
        assertEquals(listOf("2025-03-01"), rowDates().map { it.substringBefore(" ·") })
    }

    /**
     * Ein vollstaendiger Datensatz, dessen GATT-Discovery aber unvollstaendig
     * ist: daraus leitet der Evaluator TESTED (nicht hardware-bestaetigt) ab.
     * Alle Merkmale sind synthetisch und nur fuer diesen Test erzeugt.
     */
    private fun completeButNotConfirmed(): HardwareTestRecord = HardwareTestRecord(
        id = "t4",
        manufacturer = ScooterManufacturer.NAVEE,
        modelName = "Navee GT3 Max",
        testDate = "2025-07-01",
        expectedFingerprintId = "navee-gt3max-unconfirmed",
        scan = HardwareScanSnapshot(
            deviceName = "NAVEE GT3",
            serviceUuids = listOf("0000ffe0-0000-1000-8000-00805f9b34fb")
        ),
        gatt = HardwareGattSnapshot(GattDiscoveryStatus.INCOMPLETE),
        observedRecognition = HardwareRecognitionSnapshot(
            confidence = RecognitionConfidence.PROBABLE,
            detectedBrand = ScooterManufacturer.NAVEE,
            detectedBrandFamily = BrandFamily.NAVEE,
            detectedModelFamily = "NAVEE",
            confirmedModel = null,
            probableModel = "GT3",
            matchedProfileId = "navee-gt3max-unconfirmed"
        )
    )

    @Test
    fun maturityFilterSeparatesDocumentedFromTested() {
        Rt.seed(
            RuntimeEnvironment.getApplication(),
            listOf(
                Rt.record("t1", ScooterManufacturer.NAVEE, "Navee GT3 Max", "2025-03-01", "navee-gt3max-unconfirmed"),
                Rt.record("t2", ScooterManufacturer.XIAOMI, "Mi Pro 2", "2025-01-15", "xiaomi-legacy-unconfirmed"),
                Rt.record("t3", ScooterManufacturer.NINEBOT, "Max G30", "2024-11-20", null),
                completeButNotConfirmed()
            )
        )
        launch()
        openConfig()
        expandFilter()

        // Reifegrade: [null, DOCUMENTED, TESTED, HARDWARE_CONFIRMED]
        filterSpinners()[2].setSelection(1) // DOCUMENTED
        applyFilter()
        assertEquals("3 von 4 Test(s) entsprechen dem Filter", hitCount())
        assertEquals(3, rowDates().size)

        // Der Filterbereich bleibt nach ANWENDEN offen.
        filterSpinners()[2].setSelection(2) // TESTED
        applyFilter()
        assertEquals("1 von 4 Test(s) entsprechen dem Filter", hitCount())
        assertEquals(listOf("2025-07-01"), rowDates().map { it.substringBefore(" ·") })
    }

    @Test
    fun dateRangeFilterKeepsOnlyRecordsInsideRange() {
        seedThree()
        launch()
        openConfig()
        expandFilter()

        val edits = filterEdits()
        edits[1].setText("2025-01-01")
        edits[2].setText("2025-12-31")
        applyFilter()

        assertEquals("2 von 3 Test(s) entsprechen dem Filter", hitCount())
        assertEquals(listOf("2025-03-01", "2025-01-15"), rowDates().map { it.substringBefore(" ·") })
    }

    @Test
    fun combinedFiltersAreAnded() {
        seedThree()
        launch()
        openConfig()
        expandFilter()

        filterSpinners()[0].setSelection(2) // XIAOMI
        filterEdits()[0].setText("pro")
        filterEdits()[1].setText("2025-01-01")
        applyFilter()

        assertEquals("1 von 3 Test(s) entsprechen dem Filter", hitCount())
        assertEquals(listOf("2025-01-15"), rowDates().map { it.substringBefore(" ·") })
    }

    @Test
    fun resetButtonRestoresAllRecords() {
        seedThree()
        launch()
        openConfig()
        expandFilter()

        filterSpinners()[0].setSelection(3) // NINEBOT
        applyFilter()
        assertEquals("1 von 3 Test(s) entsprechen dem Filter", hitCount())

        assertTrue(Rt.click(decor(), "ZURUECKSETZEN"))
        assertEquals("3 Test(s) — neueste zuerst", hitCount())
        assertEquals(3, rowDates().size)
    }

    @Test
    fun emptyFilterResultShowsHintAndResetButton() {
        seedThree()
        launch()
        openConfig()
        expandFilter()

        filterSpinners()[0].setSelection(4) // SEGWAY — kein Datensatz
        applyFilter()

        assertEquals("0 von 3 Test(s) entsprechen dem Filter", hitCount())
        assertTrue("Es duerfen keine Zeilen sichtbar bleiben", rowDates().isEmpty())
        assertTrue(Rt.hasTextContaining(decor(), "Kein Test entspricht den aktuellen Filtern"))
        assertTrue(Rt.hasText(decor(), "FILTER ZURUECKSETZEN"))

        assertTrue(Rt.click(decor(), "FILTER ZURUECKSETZEN"))
        assertEquals("3 Test(s) — neueste zuerst", hitCount())
    }

    @Test
    fun listIsSortedNewestFirst() {
        seedThree()
        launch()
        openConfig()

        val dates = rowDates().map { it.substringBefore(" ·") }
        assertEquals(listOf("2025-03-01", "2025-01-15", "2024-11-20"), dates)
    }

    @Test
    fun filteringDoesNotChangeStoredData() {
        seedThree()
        val before = Rt.storedJson(RuntimeEnvironment.getApplication())
        launch()
        openConfig()
        expandFilter()
        filterSpinners()[0].setSelection(2)
        applyFilter()

        assertEquals("Filter hat gespeicherte Daten veraendert", before, Rt.storedJson(RuntimeEnvironment.getApplication()))
        assertEquals(3, Rt.storedRecords(RuntimeEnvironment.getApplication()).size)
    }

    /* ================================================================== */
    /*  C. DATENVERWALTUNG                                                */
    /* ================================================================== */

    @Test
    fun createRecordThroughFormPersists() {
        launch()
        openConfig()
        assertTrue(Rt.click(decor(), "NEUEN TEST ANLEGEN"))

        val dialog = latestDialog()
        val form = dialog.window!!.decorView
        Rt.editTextByHint(form, "Modellname")!!.setText("Testmodell A")
        Rt.editTextByHint(form, "yyyy-MM-dd")!!.setText("2025-05-01")
        Rt.clickDialogButton(dialog, DialogInterface.BUTTON_POSITIVE)
        Rt.idle(2)

        val stored = Rt.storedRecords(RuntimeEnvironment.getApplication())
        assertEquals(1, stored.size)
        assertEquals("Testmodell A", stored[0].modelName)
        assertEquals("2025-05-01", stored[0].testDate)
        assertTrue(Rt.hasTextContaining(decor(), "Testmodell A"))
    }

    @Test
    fun formRejectsEmptyModelAndInvalidDate() {
        launch()
        openConfig()
        Rt.click(decor(), "NEUEN TEST ANLEGEN")

        val dialog = latestDialog()
        val form = dialog.window!!.decorView
        Rt.editTextByHint(form, "Modellname")!!.setText("")
        Rt.editTextByHint(form, "yyyy-MM-dd")!!.setText("2025-02-31")
        Rt.clickDialogButton(dialog, DialogInterface.BUTTON_POSITIVE)
        Rt.idle(1)

        assertTrue("Formular haette nicht speichern duerfen", Rt.storedRecords(RuntimeEnvironment.getApplication()).isEmpty())
    }

    @Test
    fun editRecordThroughDetailDialog() {
        seedThree()
        launch()
        openConfig()

        assertTrue(Rt.click(decor(), "Navee · Navee GT3 Max"))
        val detail = latestDialog()
        assertTrue(Rt.dialogTexts(detail).any { it.contains("Navee GT3 Max") })
        Rt.clickDialogButton(detail, DialogInterface.BUTTON_POSITIVE) // BEARBEITEN
        Rt.idle(2)

        val form = latestDialog()
        Rt.editTextByHint(form.window!!.decorView, "Modellname")!!.setText("GT3 Max (korrigiert)")
        Rt.clickDialogButton(form, DialogInterface.BUTTON_POSITIVE)
        Rt.idle(2)

        val stored = Rt.storedRecords(RuntimeEnvironment.getApplication())
        assertEquals(3, stored.size)
        val edited = stored.first { it.id == "t1" }
        assertEquals("GT3 Max (korrigiert)", edited.modelName)
        assertEquals("2025-03-01", edited.testDate)
    }

    @Test
    fun deleteRequiresConfirmationAndRemovesOnlyThatRecord() {
        seedThree()
        launch()
        openConfig()

        Rt.click(decor(), "Navee · Navee GT3 Max")
        val detail = latestDialog()
        Rt.clickDialogButton(detail, DialogInterface.BUTTON_NEUTRAL) // LOESCHEN
        Rt.idle(2)

        val confirm = latestDialog()
        assertTrue(
            "Bestaetigung fehlt: ${Rt.dialogTexts(confirm)}",
            Rt.dialogTexts(confirm).any { it.contains("wirklich loeschen") }
        )
        Rt.clickDialogButton(confirm, DialogInterface.BUTTON_POSITIVE)
        Rt.idle(2)

        val stored = Rt.storedRecords(RuntimeEnvironment.getApplication())
        assertEquals(2, stored.size)
        assertTrue(stored.none { it.id == "t1" })
        assertTrue(stored.any { it.id == "t2" })
        assertTrue(stored.any { it.id == "t3" })
    }

    @Test
    fun deleteCancelKeepsRecord() {
        seedThree()
        launch()
        openConfig()

        Rt.click(decor(), "Navee · Navee GT3 Max")
        Rt.clickDialogButton(latestDialog(), DialogInterface.BUTTON_NEUTRAL)
        Rt.idle(2)
        Rt.clickDialogButton(latestDialog(), DialogInterface.BUTTON_NEGATIVE) // ABBRECHEN
        Rt.idle(2)

        assertEquals(3, Rt.storedRecords(RuntimeEnvironment.getApplication()).size)
    }

    @Test
    fun createdRecordSurvivesActivityRestart() {
        launch()
        openConfig()
        Rt.click(decor(), "NEUEN TEST ANLEGEN")
        val dialog = latestDialog()
        val form = dialog.window!!.decorView
        Rt.editTextByHint(form, "Modellname")!!.setText("Neustart-Modell")
        Rt.editTextByHint(form, "yyyy-MM-dd")!!.setText("2025-06-02")
        Rt.clickDialogButton(dialog, DialogInterface.BUTTON_POSITIVE)
        Rt.idle(2)

        controller.pause().stop().destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Rt.idle()
        activity = controller.get()
        openConfig()

        assertTrue(Rt.hasTextContaining(decor(), "Neustart-Modell"))
        assertEquals(1, Rt.liveRecords(activity).size)
    }

    /* ================================================================== */
    /*  D. JSON EXPORT / IMPORT                                           */
    /* ================================================================== */

    @Test
    fun exportProducesMaskedJsonWithoutSerialNumbers() {
        Rt.seed(
            RuntimeEnvironment.getApplication(),
            listOf(
                Rt.record(
                    "x1", ScooterManufacturer.XIAOMI, "Mi 3", "2025-04-04",
                    addressMasked = "AA:BB:CC:DD:EE:FF",
                    notes = "synthetischer Testdatensatz"
                )
            )
        )
        launch()
        openConfig()
        runCatching { Rt.click(decor(), "JSON EXPORT") }
        Rt.idle(1)

        val payload = Rt.privateField(activity, "pendingExportContent") as? String
        assertNotNull("Exportinhalt wurde nicht erzeugt", payload)
        val json = payload!!
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"x1\""))
        assertFalse("Vollstaendige MAC im Export", json.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse("Seriennummernfeld im Export", json.lowercase().contains("serial"))
        assertFalse("Unmaskierte Adresse im Export", json.contains("DD:EE:FF"))
    }

    @Test
    fun importValidJsonAddsRecords() {
        launch()
        openConfig()
        val json = HardwareTestJson.encode(
            listOf(Rt.record("i1", ScooterManufacturer.SEGWAY, "Ninebot Max G2", "2025-02-02"))
        )
        importJsonFile(json)
        dismissImportDialog()

        val stored = Rt.storedRecords(RuntimeEnvironment.getApplication())
        assertEquals(1, stored.size)
        assertEquals("Ninebot Max G2", stored[0].modelName)
    }

    @Test
    fun importInvalidJsonKeepsExistingData() {
        seedThree()
        val before = Rt.storedJson(RuntimeEnvironment.getApplication())
        launch()
        openConfig()

        importJsonFile("{ das ist kein JSON")
        dismissImportDialog()

        assertEquals("Bestehende Daten wurden beschaedigt", before, Rt.storedJson(RuntimeEnvironment.getApplication()))
        assertEquals(3, Rt.storedRecords(RuntimeEnvironment.getApplication()).size)
    }

    @Test
    fun importDuplicateIdIsSkippedNotOverwritten() {
        seedThree()
        launch()
        openConfig()

        val json = HardwareTestJson.encode(
            listOf(Rt.record("t1", ScooterManufacturer.NAVEE, "Voellig anderes Modell", "2025-09-09"))
        )
        importJsonFile(json)
        dismissImportDialog()

        val stored = Rt.storedRecords(RuntimeEnvironment.getApplication())
        assertEquals(3, stored.size)
        assertEquals("Navee GT3 Max", stored.first { it.id == "t1" }.modelName)
    }

    @Test
    fun importUnknownManufacturerIsRejected() {
        seedThree()
        launch()
        openConfig()

        val json = """
            {"schemaVersion":1,"generatedAt":"2025-01-01T00:00:00Z","records":[
              {"id":"u1","manufacturer":"TESLA","modelName":"X","testDate":"2025-01-01",
               "tester":null,"addressMasked":null,"expectedFingerprintId":null,
               "scan":null,"gatt":null,"observedRecognition":null,"notes":null}
            ]}
        """.trimIndent()
        importJsonFile(json)
        dismissImportDialog()

        assertEquals(3, Rt.storedRecords(RuntimeEnvironment.getApplication()).size)
    }

    @Test
    fun importInvalidCalendarDateIsRejected() {
        seedThree()
        launch()
        openConfig()

        val json = """
            {"schemaVersion":1,"generatedAt":"2025-01-01T00:00:00Z","records":[
              {"id":"d1","manufacturer":"NAVEE","modelName":"X","testDate":"2025-02-31",
               "tester":null,"addressMasked":null,"expectedFingerprintId":null,
               "scan":null,"gatt":null,"observedRecognition":null,"notes":null}
            ]}
        """.trimIndent()
        importJsonFile(json)
        dismissImportDialog()

        assertEquals(3, Rt.storedRecords(RuntimeEnvironment.getApplication()).size)
    }

    /* ================================================================== */
    /*  E. BACKUP-WIEDERHERSTELLUNG                                       */
    /* ================================================================== */

    @Test
    fun corruptPrimaryFallsBackToBackupAndOffersRecovery() {
        val app = RuntimeEnvironment.getApplication()
        val good = HardwareTestJson.encode(
            listOf(Rt.record("b1", ScooterManufacturer.NAVEE, "Backup-Modell", "2025-01-01"))
        )
        Rt.prefs(app).edit()
            .putString(Rt.KEY_PRIMARY, "{ defekter Hauptstand")
            .putString(Rt.KEY_BACKUP, good)
            .commit()

        launch()
        openConfig()

        assertEquals(1, Rt.liveRecords(activity).size)
        assertTrue(Rt.hasTextContaining(decor(), "Backup-Modell"))
        assertTrue(
            "Wiederherstellungsbutton fehlt",
            Rt.hasText(decor(), "WIEDERHERGESTELLTEN STAND SICHERN")
        )
    }

    @Test
    fun savingRecoveredStateKeepsGoodBackup() {
        val app = RuntimeEnvironment.getApplication()
        val good = HardwareTestJson.encode(
            listOf(Rt.record("b1", ScooterManufacturer.NAVEE, "Backup-Modell", "2025-01-01"))
        )
        Rt.prefs(app).edit()
            .putString(Rt.KEY_PRIMARY, "{ defekter Hauptstand")
            .putString(Rt.KEY_BACKUP, good)
            .commit()

        launch()
        openConfig()
        assertTrue(Rt.click(decor(), "WIEDERHERGESTELLTEN STAND SICHERN"))
        Rt.idle(2)

        val primary = Rt.storedJson(app)
        assertNotNull(primary)
        assertTrue("Hauptstand wurde nicht repariert", HardwareTestJson.decode(primary!!).isSuccess)
        assertEquals("Gutes Backup wurde ueberschrieben", good, Rt.storedBackup(app))
        assertEquals(1, Rt.storedRecords(app).size)
    }

    /* ================================================================== */
    /*  F. UI-QUALITAET (kleine Displays, Querformat, grosse Schrift)     */
    /* ================================================================== */

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h480dp")
    fun smallDisplayRendersHardwareSectionWithoutCrash() {
        seedThree()
        launch()
        openConfig()
        expandFilter()
        assertTrue(Rt.hasText(decor(), "ANWENDEN"))
        assertTrue(Rt.hasText(decor(), "ZEITRAUM"))
        assertEquals(3, filterSpinners().size)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
    fun landscapeRendersHardwareSectionWithoutCrash() {
        seedThree()
        launch()
        openConfig()
        assertTrue(Rt.hasText(decor(), "HARDWARE-TESTS & FINGERPRINT-QUALITAET"))
        assertTrue(Rt.hasTextContaining(decor(), "Navee GT3 Max"))
    }

    @Test
    fun largeFontScaleRendersWithoutCrash() {
        seedThree()
        launch()
        val config = android.content.res.Configuration(activity.resources.configuration)
        config.fontScale = 1.35f
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        activity.recreate()
        Rt.idle()

        openConfig()
        expandFilter()
        assertTrue(Rt.hasText(decor(), "ANWENDEN"))
        assertTrue(Rt.hasTextContaining(decor(), "Navee GT3 Max"))
    }

    /* ================================================================== */
    /*  G. SICHERHEITSGRENZEN                                             */
    /* ================================================================== */

    @Test
    fun hardwareSectionExposesNoWriteOrFlashActions() {
        seedThree()
        launch()
        openConfig()

        val labels = Rt.buttonLabels(decor()).map { it.uppercase() }
        assertTrue(labels.contains("NEUEN TEST ANLEGEN"))
        assertTrue(labels.contains("JSON EXPORT"))
        assertTrue(labels.contains("JSON IMPORT"))
        val forbidden = Regex("(SCHREIB|WRITE|FLASH|SPEED|LIMIT|UNLOCK|FIRMWARE-UPDATE)")
        val offending = labels.filter { forbidden.containsMatchIn(it) }
        assertTrue("Unzulaessige Aktion in der Testverwaltung: $offending", offending.isEmpty())
    }

    @Test
    fun recordsNeverCarrySerialNumbers() {
        seedThree()
        launch()
        openConfig()
        val json = Rt.storedJson(RuntimeEnvironment.getApplication())!!
        assertFalse(json.lowercase().contains("serial"))
        assertTrue(Rt.liveRecords(activity).all { it.addressMasked == null || it.addressMasked!!.contains("*") || !it.addressMasked!!.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) })
    }
}
