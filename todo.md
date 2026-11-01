# PHASE 17.2 — LIVE GATT RECOGNITION & DEVICE SESSION INTEGRATION

## 1. Repository-Audit
- [x] main-Commit, git status, offene PRs, CI verifizieren
- [x] Phase-17.1-Implementierung lesen (Engine, Session, GattFeatures, Fingerprints)
- [x] BleEngine, MainActivity, HardwareTestReport, Tests lesen
- [x] Ist-Bericht erstellen (`PHASE17.2_IST_BERICHT.md`)

## 2. Live-GATT-Anbindung (§3)
- [x] discoveryStatus / discoveryError / discoveryGeneration in BleEngine
- [x] Status aus echten Callbacks (CONNECTED / onServicesDiscovered / Fehler)
- [x] Leerer Snapshot = INCOMPLETE, nicht COMPLETE
- [x] `liveGattSnapshot()` + `GattLiveSnapshot` mit Stale-/Adressprüfung
- [x] `gattSnapshot()` und bestehende Diagnose unverändert

## 3. Session-Management (§5)
- [x] Scan-Ergebnisse pro Adresse (`scanResults`)
- [x] Getrennter Slot für aktive Verbindung (`activeResult`/`activeAddress`)
- [x] Getrennte Historie (`lastActiveResult`/`lastActiveAddress`)
- [x] `bindActive()` mit Adressprüfung, `archiveActive()`, `displayResult()`
- [x] 17.1-Kompatibilität (`observe`, `current`, `hasResultFor`)

## 4. RecognitionEngine (§6)
- [x] GATT nur bei COMPLETE als positive Evidenz
- [x] `GATT_EVIDENCE_ADDED` / `GATT_NO_EVIDENCE`
- [x] `RecognitionConfidence.rank` korrigiert (höher = stärker)
- [x] Widersprüche begrenzen Konfidenz tatsächlich
- [x] Keine Prozentwerte, Nutzerprofil bleibt getrennt

## 5. UI (§7)
- [x] SCAN-ERKENNUNG
- [x] LIVE-HARDWARE-ERKENNUNG (inkl. Discovery-Status, Services, Fehler)
- [x] ERGEBNISVERGLEICH (vor/nach, neue Evidenz, fehlend, Widerspruch)
- [x] Hinweis bei fehlender GATT-Discovery, Historie-Kennzeichnung

## 6. Export (§8)
- [x] RecognitionExport additiv erweitert
- [x] HardwareTestReport JSON `comparison`
- [x] HardwareTestReport CSV `recognition_compare`
- [x] schemaVersion 1, MAC maskiert, keine Seriennummer

## 7. Tests (§9)
- [x] 27 neue Tests, alle 20 Szenarien abgedeckt
- [x] Phase171RecognitionTest unverändert grün
- [x] Kein Test entfernt oder übersprungen

## 8. Build & QA (§11)
- [x] `./gradlew testDebugUnitTest --offline` → 573 Tests, 0 Fehler, 0 skipped
- [x] `./gradlew assembleDebug --offline` → BUILD SUCCESSFUL, 19.251.428 Bytes
- [x] Warnungen dokumentiert (1 vorbestehende Deprecation)

## 9. Git & Doku (§12, §13)
- [x] Feature-Branch `feat/phase17.2-live-gatt-recognition`
- [x] Commit + Push
- [x] PR gegen `main`
- [x] `PHASE17.2_ERGEBNIS.md` (14-Punkte-Bericht)
