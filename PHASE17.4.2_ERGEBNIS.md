# Phase 17.4.2 — Android Device QA & Runtime Verification

**Repository:** `syrak7305-bit/SyrakScooterLab`
**Paket:** `com.syrak.scooterlab`
**Ausgangspunkt:** `main` @ `3550e28` (Merge PR #32, Phase 17.4.1)
**Branch:** `feat/phase17.4.2-device-qa`
**Datum der Prüfung:** 2026-11-02

---

## 0. Kurzfassung (das Wichtigste zuerst)

Es stand **weder ein physisches Android-Gerät noch ein Emulator** zur Verfügung. Eine
Geräteprüfung hat daher **nicht stattgefunden** und wird in diesem Bericht auch nicht
behauptet. Was stattdessen gemacht wurde: Die App wurde auf einer **JVM-Laufzeitstufe mit
Robolectric 4.13** gegen die echte `MainActivity`, die echte View-Hierarchie, echte
`SharedPreferences` und echte `AlertDialog`s gefahren. Diese Stufe ist **kein Gerätetest**,
sie hat aber einen **echten UI-Fehler** gefunden, der von reinen Unit-Tests nie erfasst
worden wäre: `renderConfig()` hat den kompletten CONFIG-Bereich bei jedem Filterklick ein
zweites Mal unter den bestehenden Inhalt gehängt. Der Fehler ist behoben, die Regression
ist durch einen Laufzeittest abgesichert.

Ergebnis: **41 Test-Suites, 688 Tests, 0 Fehler, 0 Errors, 0 übersprungen**;
`assembleDebug` **BUILD SUCCESSFUL**, APK **18.904.330 Bytes (18,0 MiB)**.

---

## A. Was tatsächlich auf einem Gerät getestet wurde

**Nichts.** Es wurde kein Test auf einem physischen Android-Gerät und auf keinem Emulator
durchgeführt. Es gibt in dieser Umgebung kein Gerät, keinen Emulator und keine
System-Images; die Begründung steht vollständig in Abschnitt C.

Konkret bedeutet das: Es wurde **nicht** verifiziert, wie sich die App auf echter Hardware
verhält — kein echter BLE-Scan, kein echter GATT-Discovery-Lauf gegen einen Scooter, kein
echter SAF-Dateidialog, keine echte Bildschirmtastatur, kein echter TalkBack-Lauf, kein
echter Prozess-Kill mit anschließendem Kaltstart, keine echte Rotation auf einem
physischen Display.

---

## B. Was ausschließlich auf JVM-/Unit-Ebene getestet wurde

Zwei Ebenen, die beide **nicht** Gerätetests sind:

**Ebene 1 — reine Unit-Tests (bereits aus 17.4/17.4.1 vorhanden, unverändert):**
`HardwareTestStore`, `HardwareTestImporter`, `HardwareTestFilter`, `HardwareTestJson`
(Schema-Version 1, MAC-Maskierung, kein Serial), `IsoDate`, `HardwareTestEvaluator`,
`FingerprintQualityAudit`, `BuiltInModelFingerprints`, `HardwareTestRegistry` sowie die
Persistenz- und Recovery-Logik inklusive „guter Backup wird beim Sichern des
wiederhergestellten Stands nicht überschrieben".

**Ebene 2 — neu in dieser Phase: Robolectric-Laufzeitstufe (32 Tests).**
`app/src/test/java/com/syrak/scooterlab/Phase1742RuntimeUiTest.kt` startet die **echte**
`MainActivity` über `Robolectric.buildActivity(...).setup()`, durchläuft den echten
Lifecycle, die echten Start-Gates (`disclaimers_accepted`, `country_notice_accepted`,
`onboarding_seen`, `profile_setup_completed`), baut die echte View-Hierarchie auf und
bedient echte `AlertDialog`s. Abgedeckt:

*Start/Tabs:* App startet ohne Daten, CONFIG-Tab ist erreichbar, Hardware-Test-Bereich
zeigt den Leerzustand.

*Hardware-Test-Verwaltung:* Filterbereich auf-/zuklappen; Herstellerfilter (Spinner-Index
1 = NAVEE); Modell-Teilstring (`PRO`); Fingerprint-Filter; Reifegradfilter (DOCUMENTED vs.
TESTED anhand eines synthetischen, vollständigen, aber nicht hardware-bestätigten
Datensatzes); Zeitraumfilter; kombinierte Filter; „FILTER ZURÜCKSETZEN"; Trefferzählung
(„x von y Test(s) entsprechen dem Filter" bzw. „y Test(s) — neueste zuerst"); Leerzustand
bei null Treffern; Sortierung neueste zuerst; **Filter verändert nur die Anzeige, nicht den
gespeicherten Bestand** (Storage-JSON vor/nach dem Filtern byte-identisch).

*Datensatzverwaltung:* Anlegen über das Formular; Formular lehnt leeren Modellnamen und
das unmögliche Datum `2025-02-31` ab; Bearbeiten über die Detailansicht; Löschen mit
Bestätigungsdialog; Abbrechen des Löschens lässt den Datensatz stehen; **Neustart der
Activity** (neue Instanz, gleiche `SharedPreferences`) zeigt den Datensatz weiterhin —
Persistenz über den Lifecycle hinweg.

*Import/Export:* Export erzeugt maskiertes JSON (kein `AA:BB:CC:DD:EE:FF`, kein
„serial"-Feld); gültiger Re-Import; ungültiges JSON wird abgelehnt; doppelte ID wird
übersprungen (kein Überschreiben); unbekannter Hersteller wird abgelehnt; ungültiges
Kalenderdatum wird abgelehnt; nach einem fehlgeschlagenen Import bleibt der bestehende
Bestand unverändert.

*Backup/Recovery:* korrupter Primärschlüssel → Fallback auf Backup, Wiederherstellungsbutton
erscheint; „WIEDERHERGESTELLTEN STAND SICHERN" schreibt den Primärschlüssel neu und lässt
ein intaktes Backup unangetastet.

*UI-Qualität (nur Layout-Ebene):* `w320dp-h480dp` (kleines Display), `w891dp-h411dp-land`
(Querformat), `fontScale 1.35` mit `recreate()` — jeweils ohne Crash und ohne
abgeschnittene Pflichtaktionen.

*Sicherheitsgrenzen:* kein Button/Label passend zu
`(SCHREIB|WRITE|FLASH|SPEED|LIMIT|UNLOCK|FIRMWARE-UPDATE)`; keine Seriennummern in
Export/Anzeige.

Diese Ebene ersetzt **keinen** Gerätetest. Sie kann keine echte BLE-Hardware ansprechen,
keinen echten SAF-Dialog des System-Document-Providers öffnen, keine echte
Bildschirmtastatur einblenden und TalkBack nicht real bedienen.

---

## C. Was nicht testbar war — und warum (mit Belegen)

**C.1 Kein ADB-Gerät.** `adb devices` liefert ausschließlich die Kopfzeile
`List of devices attached` und danach keine Zeile. Es ist kein Gerät per USB oder über
TCP/IP verbunden.

**C.2 Kein Emulator installiert.** Das SDK unter `/opt/android-sdk` enthält nur
`build-tools/34.0.0`, `cmdline-tools`, `licenses`, `platform-tools` (37.0.1) und
`platforms/android-34`. Es gibt **kein** Verzeichnis `emulator/` und **kein**
`system-images/`. Der Befehl `emulator` existiert nicht.

**C.3 Keine Hardware-Virtualisierung.** `/dev/kvm` existiert nicht. `/proc/cpuinfo` enthält
**0** Vorkommen von `vmx` bzw. `svm`. Ein x86_64-Emulator könnte also selbst nach
Installation nicht beschleunigt starten; ein ARM-Image ohne KVM wäre auf dieser
2-Kern-Maschine praktisch nicht lauffähig.

**C.4 Der Download passt nicht auf die Platte.** Emulator-Paket ≈ 354,2 MB plus
System-Image ≈ 720,7 MB ergeben ≈ **1,08 GB**. Frei waren zum Prüfzeitpunkt ≈ **316 MB**
(und zeitweise deutlich weniger, siehe C.5). Die Installation ist damit nicht möglich,
unabhängig von C.3.

**C.5 Speicher und RAM sind die eigentliche Engstelle.** Die Platte hat 8,8 GB, davon sind
dauerhaft ≈ 8,2–8,4 GB belegt; während der Builds fiel der freie Platz mehrfach auf
**0 Bytes**. Der Arbeitsspeicher beträgt 3.939 MB **ohne Swap**. Ein kombinierter Lauf aus
Unit-Tests und APK-Build wurde vom OOM-Killer beendet
(`Out of memory: Killed process ... (java) total-vm:3972856kB`), weshalb Tests und
APK-Build in dieser Phase **sequenziell** gefahren wurden.

**C.6 Daraus folgende, blockierte QA-Schritte:** alle Punkte aus Aufgabe 3–7, soweit sie
echte Gerätehardware, echte Systemdialoge, echte Eingabegeräte oder echte Prozessneustarts
betreffen — also realer BLE-Scan/GATT-Discovery, realer SAF-Dialog inklusive Abbrechen,
echte Tastatur-/Fokus-Navigation, echter TalkBack, echte Rotation, echter App-Kill und
Kaltstart, echte Backup-Wiederherstellung auf Gerätespeicher. Diese Punkte sind **nicht
bestanden und nicht durchgefallen** — sie sind **nicht geprüft**.

---

## D. Gefundene Fehler (mit Dateipfad und konkretem Symptom)

**D.1 — CONFIG-Bereich wird bei jedem Filterklick doppelt angehängt**

*Datei:* `app/src/main/java/com/syrak/scooterlab/MainActivity.kt`
*Funktion:* `renderConfig()` (Beginn bei ca. Zeile 5549)

*Symptom:* Nach jedem Klick auf `ANWENDEN`, `ZURÜCKSETZEN` oder auf den
Wiederherstellungsbutton erschien der **komplette** CONFIG-Bereich ein zweites Mal
**unter** dem bereits sichtbaren Inhalt. Konkret beobachtbar: doppelte Karten für
„Mein Profil", doppelte Filterkarte, **zwei Trefferzahlen gleichzeitig** (die alte und die
neue), und der alte Filterzustand blieb sichtbar, obwohl der neue bereits gerendert war.

*Ursache:* `renderConfig()` hat neue Views an `tabContent` **angehängt**, ohne vorher zu
leeren. Über `showTab(...)` fiel das nicht auf, weil dort vorher geleert wird. Die
Filter-, Reset- und Recovery-Callbacks rufen `renderConfig()` jedoch **direkt** auf — dort
fehlte das Leeren.

*Reproduktion (JVM-Laufzeitstufe):* drei Datensätze seeden, Filterbereich öffnen,
Hersteller `NAVEE` wählen, `ANWENDEN` — erwartet „1 von 3 Test(s) entsprechen dem Filter",
tatsächlich war zusätzlich „3 dokumentierte(r) Test(s)" aus dem alten Rendering vorhanden
(`ComparisonFailure`). Der Test schlug vor dem Fix zuverlässig fehl und ist nach dem Fix
grün.

*Schweregrad:* mittel — kein Datenverlust, aber die Trefferzahl war irreführend und die
Oberfläche wuchs bei jedem Klick weiter, was auf kleinen Displays zum Scroll-Chaos führt.

Weitere Fehler wurden **nicht** gefunden. Insbesondere wurden **keine** Änderungen „auf
Verdacht" vorgenommen; die einzige Produktcode-Änderung dieser Phase ist der Fix aus E.1.

---

## E. Behobene Fehler

**E.1 — `renderConfig()` leert den Tab-Inhalt vor dem Neuaufbau**

*Datei:* `app/src/main/java/com/syrak/scooterlab/MainActivity.kt`, Funktion
`renderConfig()`, erste Anweisung:

```kotlin
private fun renderConfig() {
    // PHASE 17.4.2: renderConfig() wird auch direkt aus Filter- und
    // Wiederherstellungs-Callbacks aufgerufen (nicht nur ueber showTab).
    // Ohne dieses Leeren wurde der komplette CONFIG-Bereich bei jedem
    // Filterklick ein zweites Mal UNTER den bestehenden Inhalt gehaengt
    // (doppelte Karten, doppelte Trefferzahl, alter Filterzustand blieb
    // sichtbar). Befund aus der Laufzeitpruefung.
    tabContent.removeAllViews()
    ...
```

Wirkung: Der CONFIG-Bereich wird bei jedem Rendering vollständig neu aufgebaut; es gibt
genau eine Trefferzahl, einen Filterzustand und keine doppelten Karten. Das Verhalten von
`showTab(...)` bleibt unverändert (dort war das Leeren bereits vorhanden).

**E.2 — Testinfrastruktur (kein Produktcode)**

* `gradle/libs.versions.toml`: Robolectric auf **4.13** festgelegt (4.14.1 verlangt
  zwingend die API-35-Jar `android-all-instrumented:15-...`, die in dieser Umgebung nicht
  ladbar ist; 4.13 endet bei API 34 und passt zur vorhandenen Jar).
* `app/build.gradle.kts`: `testOptions { unitTests.isIncludeAndroidResources = true }`,
  `robolectric.enabledSdks = 34`, Testabhängigkeiten `robolectric` und
  `androidx.test:core`.
* Neu: `Phase1742TestSupport.kt` (Hilfsmodul `Rt`) und `Phase1742RuntimeUiTest.kt`
  (32 Tests).

Es wurden **keine** neuen Produktfeatures, **keine** neuen Designs und **keine**
Animationen eingeführt.

---

## F. Testergebnisse und Build-Status

**Tests** (`./gradlew testDebugUnitTest --offline --max-workers=1`):

| Kennzahl | Wert |
|---|---|
| Suites | 41 |
| Tests | 688 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| Ergebnis | **BUILD SUCCESSFUL** |

Davon neu in dieser Phase: **32** Laufzeittests in
`Phase1742RuntimeUiTest` (alle grün), plus die unveränderten 20 Tests aus
`Phase1741FilterAndQaTest` und die übrigen Bestandstests.

**APK-Build** (`./gradlew assembleDebug -x testDebugUnitTest --offline --max-workers=1`):

| Kennzahl | Wert |
|---|---|
| Ergebnis | **BUILD SUCCESSFUL** (37 Tasks, 18 s) |
| Artefakt | `app/build/outputs/apk/debug/app-debug.apk` |
| Größe | **18.904.330 Bytes (18,0 MiB)** |
| Paket | `com.syrak.scooterlab` |
| versionCode / versionName | 1 / 1.0 |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |

*Hinweis zur Build-Kommandozeile:* Der Test-Gate ist in `app/build.gradle.kts` als
`assembleDebug.dependsOn("testDebugUnitTest")` verdrahtet. In dieser Umgebung führt der
kombinierte Lauf wegen 3.939 MB RAM ohne Swap zum OOM-Kill des Gradle-Daemons. Tests und
APK-Build wurden deshalb **nacheinander im selben Workspace** ausgeführt; der APK-Lauf
wurde mit `-x testDebugUnitTest` gestartet, **nachdem** der vollständige Testlauf mit 688
grünen Tests abgeschlossen war. Es wurde kein Test „übersprungen", um einen Fehler zu
verstecken.

**Warnungen:** Eine Kotlin-Deprecation-Warnung beim Kompilieren:
`BleEngine.kt:932:53 'fun writeDescriptor(p0: BluetoothGattDescriptor!): Boolean' is
deprecated`. Sie bestand bereits vor dieser Phase und wurde nicht angefasst (kein
Fehlerbefund, keine Verhaltensänderung).

**Umgebungsbedingte Einschränkung:** Wegen des akuten Platzmangels musste der
Robolectric-Jar (`android-all-instrumented-14-...`, 144 MB) nach dem Testlauf aus
`/root/.m2` entfernt werden. Die Testergebnisse oben stammen aus dem Lauf **vor** dem
Entfernen; ein erneuter Testlauf in dieser Sandbox würde den Jar neu benötigen.

---

## G. Verbleibende Risiken

1. **Keine Gerätevalidierung.** Das größte Risiko: Die gesamte BLE-Kette
   (`BleEngine`, Scan, GATT-Discovery, Service-/Characteristic-Auswertung) ist in dieser
   Phase **nicht** gegen echte Hardware gelaufen. Die Laufzeitstufe prüft nur die
   UI-/Persistenzschicht. Ein Fehler, der ausschließlich auf realen Adaptern,
   Android-Versionen oder Hersteller-BLE-Stacks auftritt, wäre unentdeckt.
2. **SAF-Dialog nur simuliert.** Der Import/Export-Pfad wurde über eine `file://`-URI
   direkt in `handleHardwareTestImport(...)` gefahren. Das Verhalten des echten
   Storage-Access-Framework-Dialogs (Abbrechen, falscher Provider, entzogene
   Berechtigung, sehr große Dateien) ist ungeprüft; `readLimited(uri, limit)` ist nur
   logisch, nicht gegen einen echten Provider getestet.
3. **Kein echter Prozessneustart.** „Neustart" wurde als neue `Activity`-Instanz über
   denselben `SharedPreferences`-Store geprüft. Ein echter Kaltstart (Prozess-Kill,
   Wiederherstellung nach Absturz, Verhalten bei `commit()`-Fehlschlag durch vollen
   Speicher) ist ungeprüft.
4. **Backup-Recovery nur mit synthetischem Defekt.** Der „kontrollierte Fehler" war ein
   absichtlich korrupter Primärschlüssel im Test. Ein realer Teil-Schreibvorgang, ein
   Stromausfall mitten im `commit()` oder ein durch das System geleerter Cache wurden
   nicht nachgestellt.
5. **Barrierefreiheit und Eingabe.** TalkBack, Fokus-Reihenfolge, echte
   Bildschirmtastatur und echte Rotation sind nur auf Layout-Ebene (Qualifiers,
   `fontScale`) berührt. Ein echter TalkBack-Durchlauf und eine echte Tastaturbedienung
   stehen aus.
6. **Speicher-/OOM-Risiko in der CI.** Die Umgebung ist am Limit (8,8 GB Platte, 3,9 GB
   RAM, kein Swap). Builds können jederzeit durch Platzmangel oder OOM scheitern; das ist
   ein Infrastruktur-, kein App-Risiko, macht aber reproduzierbare Läufe fragil.
7. **Robolectric ≠ Android.** Die JVM-Stufe verwendet die Android-14-Jar; Verhalten von
   API 26–33 (minSdk 26) ist damit nicht abgedeckt.

**Empfehlung für die nächste Phase:** Sobald ein Gerät oder ein KVM-fähiger Host mit
ausreichend Speicher verfügbar ist, die Punkte aus C.6 in genau dieser Reihenfolge
nachholen — zuerst echter Start + Persistenz, dann SAF-Dialog inklusive Abbrechen, dann
BLE-Scan/GATT gegen einen echten Scooter, zuletzt TalkBack und Rotation.

---

## Sicherheitsgrenzen (unverändert)

Es wurden **keine** BLE-Schreibkommandos, **keine** Firmware-Manipulation, **keine**
Umgehung von Geschwindigkeitsbegrenzungen, **keine** automatische
Fingerprint-Bestätigung und **keine** erfundenen Hardware-Tests hinzugefügt. Ein
Laufzeittest prüft explizit, dass keine Bedienelemente mit
`SCHREIB/WRITE/FLASH/SPEED/LIMIT/UNLOCK/FIRMWARE-UPDATE` existieren, und dass Export und
Anzeige keine Seriennummern enthalten und MAC-Adressen maskiert sind. Es wurden keine
echten Scooter-Daten erfunden; alle Testdatensätze sind synthetisch und als solche
erkennbar.
