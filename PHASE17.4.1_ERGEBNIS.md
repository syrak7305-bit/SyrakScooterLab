# Phase 17.4.1 — UI Completion & Device QA

## 1. Ausgangslage und Repository-Audit

| Punkt | Wert |
|---|---|
| Repository | `syrak7305-bit/SyrakScooterLab` |
| Basis-Branch | `main` |
| HEAD bei Bestandsaufnahme | `5ead39d` — `Merge pull request #31 from syrak7305-bit/feat/phase17.4-hardware-test-persistence` |
| PR #31 in `main` enthalten | ja (`5ead39d`, davor `420de17`, `e90d56c`) |
| Feature-Branch | `feat/phase17.4.1-ui-device-qa` |
| Arbeitsbaum vor Beginn | sauber |
| Ausgangs-Teststand | 39 Suites / 636 Tests / 0 Fehler / 0 Skipped |

Die Bestandsaufnahme hat bestaetigt, dass Phase 17.4 vollstaendig in `main`
liegt. Geprueft wurden `HardwareTestRecord`, `HardwareTestStore`,
`HardwareTestJson`, `HardwareTestFilter`, `HardwareTestImporter`,
`SharedPreferencesHardwareTestPersistence`, `ScooterRepository` und die
Hardware-Test-Sektion in `MainActivity`. Es gab keinen Abbruchgrund.

**Wesentlicher Befund der Bestandsaufnahme:** Die Klasse `HardwareTestFilter`
existierte bereits vollstaendig (Hersteller, Modell, Fingerprint, Reifegrad,
Datumsbereich, `isEmpty`, `matches`, `apply`), wurde in der UI aber **nirgends
verwendet**. Die Uebersicht zeigte immer alle Datensaetze. Es fehlte also
ausschliesslich die Bedienoberflaeche, nicht die Filterlogik. Entsprechend
wurde keine neue Filterlogik erfunden, sondern die vorhandene verdrahtet.

## 2. Was wurde geaendert?

### Aufgabe 2 — Interaktive Filter (neu in der UI)

Die Hardware-Testuebersicht besitzt jetzt einen einklappbaren Filterbereich
(`renderHardwareTestFilter()`), der genau die vorhandene `HardwareTestFilter`
verwendet:

- **Marke** — Spinner, inklusive Eintrag „Alle Marken“.
- **Modell** — Freitext, Teilstring, Gross-/Kleinschreibung egal, wird getrimmt.
- **Fingerprint** — Spinner ueber `BuiltInModelFingerprints.all()`.
- **Reifegrad** — Spinner ueber `FingerprintMaturity` (dokumentiert / getestet / hardware-bestaetigt).
- **Zeitraum** — zwei Datumsfelder `von` / `bis`, jeweils optional.

Alle Kriterien sind **kombinierbar** (UND-Verknuepfung, umgesetzt in
`HardwareTestFilter.matches`). Der Zustand liegt in `hardwareTestFilter` und
wird ueber `HardwareTestFilter.apply(...)` auf die Anzeige angewandt; die
Sortierung `sortedByDescending { it.testDate }` bleibt erhalten.

Weitere Anforderungen:

- **Zuruecksetzen** — Schaltflaeche im Filterbereich und zusaetzlich im
  Leerzustand; setzt auf `HardwareTestFilter()` zurueck.
- **Leere Ergebnisse** — verstaendlicher Hinweis: „Kein Test entspricht den
  aktuellen Filtern. Setze die Filter zurueck, um wieder alle Datensaetze zu
  sehen.“ plus direkte Zuruecksetzen-Schaltflaeche.
- **Trefferzaehlung** — „X von Y Test(s) entsprechen dem Filter“ bzw. bei
  inaktivem Filter „Y Test(s) — neueste zuerst“.
- **Keine Datenaenderung** — der Filter wirkt ausschliesslich auf die
  Anzeigeliste. `hardwareTests` wird nicht angefasst, nichts wird gespeichert.
- **Eingabepruefung** — ungueltige Datumseingaben werden nicht still
  verworfen, sondern per Toast gemeldet („Ungueltiges Startdatum. Erwartet:
  yyyy-MM-dd“); ein Startdatum nach dem Enddatum wird ebenfalls abgelehnt. In
  beiden Faellen bleibt der bisherige Filter unveraendert.

### Aufgabe 5 — Backup und Wiederherstellung (konkretisiert)

Beim Audit fiel eine echte Luecke auf: Wenn der Hauptstand defekt war und aus
dem Backup geladen wurde, konnte der Nutzer den wiederhergestellten Stand nicht
sichern. Ein normales Speichern haette ausserdem den **defekten** Hauptstand in
das Backup geschrieben und damit das noch gute Backup zerstoert.

Behoben durch:

- `HardwareTestPersistence.writePrimaryOnly(payload)` — neue Methode mit
  Default-Implementierung (`= write(payload)`), damit bestehende
  Implementierungen und Tests unveraendert weiterlaufen.
- `SharedPreferencesHardwareTestPersistence.writePrimaryOnly(...)` —
  ueberschreibt per `commit()` **nur** `hardware_tests_json`, nicht
  `hardware_tests_json_backup`.
- `HardwareTestStore.saveRecovered(records)` — schreibt einen
  wiederhergestellten Stand ohne Backup-Verlust.
- `ScooterRepository.saveRecoveredHardwareTests(records)` — Durchreichung.
- UI: bei `usedBackup == true` erscheint die Schaltflaeche
  „WIEDERHERGESTELLTEN STAND SICHERN“.

Die bestehende Backup-Logik selbst (Backup vor Ueberschreiben im selben
`commit()`, Fallback bei defektem Hauptstand, nichts wird geloescht) wurde
**nicht** umgebaut, sondern nur ergaenzt.

### Aufgabe 6 — UI-Qualitaet

- Filterbereich nutzt ausschliesslich die vorhandenen Bausteine (`makeCard`,
  `makeText`, `makeSectionLabel`, `makePrimaryButton`, `makeOutlineButton`,
  `Palette`), also dieselbe dunkle Farbpalette und Typografie wie der Rest der
  App. Kein neues Designsystem, keine zweite Navigation.
- Der Filter ist standardmaessig **eingeklappt**, damit die Uebersicht nicht
  ueberladen wirkt; die Kopfzeile zeigt rechts den Zustand „ALLE“ bzw. „AKTIV“.
- Die beiden Datumsfelder stehen bewusst **untereinander** — nebeneinander
  haetten die Hinweise auf schmalen Displays abgeschnitten.
- Keine Animationen, keine kindlichen Elemente, Statusfarben aus der
  bestehenden Palette (Grau = neutral, Blau = aktiv, Gelb = Warnung,
  Rot = Fehler, Gruen = erfolgreich gesichert).

## 3. Welche Dateien wurden geaendert?

| Datei | Art | Aenderung |
|---|---|---|
| `app/src/main/java/com/syrak/scooterlab/MainActivity.kt` | geaendert | Filterzustand, `renderHardwareTestFilter()`, `filterLabel()`, `applyHardwareTestFilter()`, Trefferzaehlung, Leerzustand, Wiederherstellungs-Schaltflaeche |
| `app/src/main/java/com/syrak/scooterlab/recognition/HardwareTestStore.kt` | geaendert | `writePrimaryOnly` im Interface (Default), `saveRecovered()`, gemeinsame private `write(records, keepBackup)` |
| `app/src/main/java/com/syrak/scooterlab/recognition/SharedPreferencesHardwareTestPersistence.kt` | geaendert | `writePrimaryOnly()` — nur Hauptstand schreiben |
| `app/src/main/java/com/syrak/scooterlab/ScooterRepository.kt` | geaendert | `saveRecoveredHardwareTests()` |
| `app/src/test/java/com/syrak/scooterlab/Phase1741FilterAndQaTest.kt` | neu | 20 Unit-Tests |
| `PHASE17.4.1_ERGEBNIS.md` | neu | dieser Bericht |

Umfang: 4 geaenderte Produktivdateien, 1 neue Testdatei, 1 neuer Bericht;
+270 / −4 Zeilen in den Produktivdateien.

Es wurde **nichts** entfernt oder umgeschrieben, was nicht unmittelbar fuer die
Aufgaben noetig war. `HardwareTestJson`, `IsoDate`, `HardwareTestEvaluator`,
`FingerprintQualityAudit`, die Formulare, die Detailansicht und die
Loeschbestaetigung sind unveraendert.

## 4. Welche Fehler wurden gefunden?

1. **Filter ohne Bedienoberflaeche** (Hauptbefund). `HardwareTestFilter` war
   vollstaendig implementiert und getestet, aber in `MainActivity` nicht
   verdrahtet. Die Uebersicht konnte nicht gefiltert werden. → behoben.
2. **Backup-Verlust bei Wiederherstellung.** Nach einem Backup-Fallback haette
   ein normales Speichern den defekten Hauptstand ins Backup geschrieben und
   damit die letzte gute Kopie vernichtet. → behoben durch
   `writePrimaryOnly` / `saveRecovered`.
3. **Kein Weg, einen wiederhergestellten Stand zu sichern.** Der Nutzer sah nur
   eine Meldung, konnte aber nichts tun. → behoben durch die neue Schaltflaeche.
4. **Datumsfelder nebeneinander** haetten auf schmalen Displays die Hinweise
   abgeschnitten. → untereinander angeordnet.
5. **Eigener Fehler waehrend der Umsetzung (dokumentiert, weil nachvollziehbar):**
   Beim Einfuegen von `saveRecovered` wurde `HardwareTestStore.kt` versehentlich
   ab der Methode `save()` bis zum Dateiende abgeschnitten; dadurch fehlten
   kurzzeitig `HardwareTestImporter` und `HardwareTestFilter`, und der Build
   brach mit `Unresolved reference 'HardwareTestFilter'` ab. Die Datei wurde
   per `git checkout` vollstaendig wiederhergestellt und die Aenderung
   anschliessend mit exakten Textersetzungen erneut eingespielt. Der Fehler ist
   im Endstand nicht enthalten; der Build ist gruen.

## 5. Welche Tests liefen erfolgreich?

Ausgefuehrt mit `./gradlew testDebugUnitTest` und `./gradlew assembleDebug`.

| Kennzahl | Wert |
|---|---|
| Test-Suites | **40** (vorher 39) |
| Tests | **656** (vorher 636) |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |
| Build | **BUILD SUCCESSFUL** |
| APK | `app/build/outputs/apk/debug/app-debug.apk`, **19.337.034 Bytes** |
| Kotlin-Warnungen | 1, vorbestehend (`BleEngine.kt:932`, `writeDescriptor` deprecated) |

Neue Suite `Phase1741FilterAndQaTest` mit **20 Tests**:

*Filter (8)*
- `combinedFilterMatchesAllCriteriaAtOnce` — alle fuenf Kriterien gleichzeitig.
- `resetFilterReturnsEveryRecord` — leerer Filter liefert alles.
- `filterThatMatchesNothingReturnsEmptyListWithoutError` — leeres Ergebnis ist kein Fehler.
- `filteringNeverMutatesTheStoredRecords` — Filter veraendert den Bestand nicht.
- `dateRangeIsInclusiveOnBothEnds` — Datumsgrenzen inklusiv.
- `modelQueryIsCaseInsensitiveAndTrimmed` — Modellsuche tolerant.
- `maturityFilterSeparatesConfirmedFromDocumented` — Reifegrad trennt sauber.
- `sortingByDateDescendingIsPreservedAfterFiltering` — Sortierung bleibt.

*JSON (5)*
- `exportThenImportIntoEmptyRegistryRestoresAllRecords`
- `exportMasksEveryBluetoothAddress`
- `exportContainsNoSerialNumberField`
- `importRejectsUnknownManufacturerWithoutTouchingExistingRecords`
- `importRejectsInvalidCalendarDate`

*Backup (5)*
- `corruptPrimaryIsRecoveredFromBackupAndFlagged`
- `corruptPrimaryAndCorruptBackupReportCorruptWithoutDeleting`
- `failedWriteKeepsPreviousPayloadAndExplainsIt`
- `backupIsWrittenBeforeOverwritingPrimary`
- `savingARecoveredStateDoesNotOverwriteTheGoodBackup`

*Datum (2)*
- `leapYearRulesAreAppliedCorrectly` (2024-02-29 gueltig, 2026-02-29 ungueltig, 2100-02-29 ungueltig, 2000-02-29 gueltig)
- `monthAndDayBoundsAreEnforced` (2026-12-31 gueltig, 2026-00-10 / 2026-12-00 / 2026-04-31 ungueltig)

Bestehende Suiten unveraendert gruen, insbesondere
`Phase174HardwareTestPersistenceTest` (34 Tests) und
`Phase173HardwareValidationTest`. Es wurde **kein** Test geloescht, deaktiviert
oder uebersprungen.

## 6. Was wurde tatsaechlich auf einem Android-Geraet getestet?

**Nichts. Es stand kein Android-Geraet zur Verfuegung.** Das ist ehrlich der
wichtigste Punkt dieses Berichts.

Nachweis der Umgebung:

```
$ /opt/android-sdk/platform-tools/adb devices
List of devices attached
(leer)

$ ls /opt/android-sdk/emulator
kein emulator-paket

$ ls /opt/android-sdk/system-images
keine system-images

$ ls /dev/kvm
no kvm
```

Es gibt also weder ein angeschlossenes Geraet noch ein Emulator-Paket, keine
System-Images und keine KVM-Beschleunigung. Ein instrumentierter Test
(`connectedAndroidTest`) ist damit nicht ausfuehrbar. Auch die SAF-Dialoge
(`CreateDocument` / `OpenDocument`) lassen sich ohne Android-Laufzeit nicht
real ausloesen.

**Was stattdessen verifiziert wurde:**

1. **Logik-Ebene, echt ausgefuehrt** — 656 JVM-Unit-Tests, davon 20 neue, die
   exakt die Aufrufe der UI nachbilden (`HardwareTestFilter.apply` plus
   Sortierung, `HardwareTestImporter.importJson`, `HardwareTestStore.load/save/
   saveRecovered`).
2. **Kompilierung** — `compileDebugKotlin` und `compileDebugUnitTestKotlin`
   fehlerfrei; damit sind alle UI-Aufrufe (Signaturen, Typen, Imports)
   nachweislich korrekt verdrahtet.
3. **APK-Inhalt** — die neuen Zeichenketten (`ZURUECKSETZEN`, `Alle Marken`)
   wurden im gebauten `classes11.dex` der APK nachgewiesen; der Code ist
   tatsaechlich im Artefakt.
4. **Code-Review der Ablaufe** — Erstellen, Bearbeiten, Loeschen (mit
   Bestaetigungsdialog), Detailansicht, Import, Export und Qualitaetsaudit
   wurden gelesen und auf korrekte Verdrahtung geprueft. Das ist eine
   Quelltextpruefung, **kein** Laufzeittest.

## 7. Was konnte mangels Hardware nicht getestet werden?

- Darstellung des Filterbereichs auf echten Smartphone-Displays (kleine
  Bildschirme, grosse Schrift, Querformat).
- Ausklappen/Einklappen, Scrollverhalten und Fokusverhalten der Tastatur.
- Die SAF-Dialoge fuer JSON-Import und -Export (Dateiauswahl, Speicherort,
  Abbruch).
- Rotation waehrend geoeffnetem Formular oder Dialog.
- Tatsaechliches Verhalten von `SharedPreferences.commit()` bei vollem
  Speicher auf einem realen Geraet (nur simuliert ueber `FakePersistence`).
- Wiederherstellung aus dem Backup auf einem Geraet mit realen Daten.
- Verhalten bei sehr grossen Registries (hunderte Datensaetze) in der
  LinearLayout-Liste.
- Bedienbarkeit mit TalkBack / Screenreader.

## 8. Welche Aufgaben bleiben offen?

1. **Geraete-QA nachholen** — die unter Punkt 7 genannten Punkte auf mindestens
   einem echten Geraet (Android 8–14) und einmal im Emulator pruefen.
2. **Optional: instrumentierte Tests** — fuer die UI-Ablaufe waeren
   Espresso-Tests sinnvoll; sie sind im Projekt bereits als
   `androidTestImplementation` vorhanden, aber ohne Geraet nicht ausfuehrbar.
3. **Optional: Listenperformanz** — bei sehr vielen Datensaetzen waere ein
   `RecyclerView` statt linearer `LinearLayout`-Kinder angebracht. Bewusst
   **nicht** umgesetzt, weil das ein Umbau der bestehenden UI-Struktur waere.
4. **Optional: Persistenz des Filterzustands** ueber Rotation/Neustart. Aktuell
   ist der Filter bewusst fluechtig (nur Anzeige, keine gespeicherten Daten).

## 9. Sicherheitsregeln — eingehalten

- Keine BLE-Schreibbefehle hinzugefuegt.
- Keine Firmware-Manipulation.
- Keine Umgehung von Geschwindigkeitsbegrenzungen.
- Keine erfundenen Hardware-Fingerprints; es wurden ausschliesslich
  `BuiltInModelFingerprints` verwendet.
- Keine automatische Hardware-Bestaetigung: `FingerprintQualityAudit` meldet
  nur, es wird nichts hochgestuft; die bestehenden Tests, die fuer alle
  Built-in-Fingerprints `NOT_TESTED` fordern, sind unveraendert.
- Keine erfundenen Testergebnisse: alle Zahlen in diesem Bericht stammen aus
  den tatsaechlichen Gradle- und JUnit-Ausgaben.
- Keine echten Nutzerdaten beschaedigt oder geloescht; defekte Staende werden
  weiterhin nie automatisch entfernt.
