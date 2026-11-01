# Phase 17.0 — Personalized Onboarding & Scooter Profiles — Ergebnisbericht

**Branch:** `feat/phase17.0-onboarding-profiles`
**Basis:** `main` @ `c03f774` (Phase 16.3.2 gemerged)
**Datum:** siehe Commit

---

## 1. Vorbemerkung / Ist-Zustand (Section 2)

Vor jeder Änderung wurde der aktuelle `main`-Branch untersucht:

- **Startablauf (bestehend):** Splash → Haftungstext (`showDisclaimer`) → Länderhinweis (`showCountryDisclaimer`) → Willkommenstext (`showOnboarding`) → optional PIN (`showPinDialog`) → `enterMain`.
- **Speicherung:** `ScooterRepository` (SharedPreferences `SyrakPrefs`) mit Region, Protokoll, PIN (PBKDF2), Tuning-Profilen, letztem Gerät.
- **Sprache:** `AppLanguage` (DE/EN) + `LanguageResolver`; Rechtstexte bereits zweisprachig.
- **Erkennung:** `ScooterManufacturer`, `ScooterModel` (mit `.manufacturer`-Ableitung), `ProtocolAdapters` + `AdapterCapabilities` (exakte Modellauflösung, keine Fake-Kompatibilität).
- **Wichtige Namenskollision:** `ScooterProfile` existiert bereits als **Tuning**-Profil. Der neue Scooter-Identitätstyp heißt daher bewusst **`RiderScooterProfile`**.

Es wurde **keine bestehende Funktion entfernt** und keine Architektur neu gebaut.

---

## 2. Geänderte / neue Dateien

| Datei | Art | Inhalt |
|---|---|---|
| `app/src/main/java/com/syrak/scooterlab/OnboardingProfile.kt` | **neu** | Reine Logik: `UserProfile`, `RiderScooterProfile`, `ProfileCodec`, `ScooterBrandModels`, `OnboardingGate`, `ProfileSessionGuard`, `ScooterFeatureRegistry`, `ProtocolRegistryView` |
| `app/src/main/java/com/syrak/scooterlab/ScooterRepository.kt` | geändert | Profil-Persistenz (`userProfile`, `riderScooterProfile`, `saveUserProfile`, `saveRiderScooterProfile`) im **selben** SharedPreferences-Store |
| `app/src/main/java/com/syrak/scooterlab/DeviceIdentification.kt` | geändert | `INMOTION` **additiv** zu `ScooterManufacturer` + Prioritätsliste |
| `app/src/main/java/com/syrak/scooterlab/MainActivity.kt` | geändert | Setup-Wizard, Startablauf-Erweiterung, personalisiertes Dashboard, Settings „Mein Profil & Scooter“, Session-Reset, Sprach-/Land-Anwendung |
| `app/src/test/java/com/syrak/scooterlab/Phase170OnboardingTest.kt` | **neu** | 14 automatisierte Tests (Section 12) |

---

## 3. Architektur-Entscheidungen

1. **Kein zweites Speichersystem:** Profile liegen in denselben `SharedPreferences` wie alle anderen Einstellungen. `ProfileCodec` ist rein (Map ⇄ Datenklasse) und damit ohne Android testbar.
2. **Keine Duplikate:** Marke = bestehende `ScooterManufacturer`; Modell = bestehende `ScooterModel`; Land = bestehende `Region`; Sprache = bestehende `AppLanguage`. Die Feature-Ableitung nutzt **ausschließlich** `ProtocolAdapters`/`AdapterCapabilities`. `ProtocolRegistryView` ist nur eine Evidenz-Sicht, kein Parallel-Registry.
3. **Markentrennung:** `ScooterBrandModels.modelBelongsToBrand` verhindert, dass ein Ninebot-Modell unter NAVEE/Xiaomi als kompatibel gilt. SEGWAY und NINEBOT teilen bewusst denselben Herstellerverbund (Segway-Ninebot), bleiben aber getrennt gespeichert.
4. **Ehrliche Kompatibilität:** Eine Nutzer-Auswahl ist **kein** Hardware-Nachweis. `identificationConfidence` = PROBABLE/UNKNOWN. Unbekannte Modelle erhalten nur generische BLE-/GATT-Diagnose; Tuning/Flash bleiben `UNAVAILABLE`/`LOCKED_UNCONFIRMED`.
5. **Keine falsche Messwert-Zuordnung:** `ProfileSessionGuard` erzwingt bei Marken-/Modellwechsel einen Reset der flüchtigen Diagnose-Sitzung (Messwerte, Detektion, `hardwareVerified`). Es werden **keine** Firmware-/BLE-Einstellungen verändert.

---

## 4. Neuer Startablauf (Section 3)

1. Haftungstext (unverändert, EN-Standard)
2. Länderhinweis (unverändert)
3. Willkommenstext (unverändert)
4. **NEU: verpflichtender Setup-Wizard** — nur wenn `setupCompleted == false`
5. Personalisiertes Dashboard

- Bei vollständigem Profil wird der Wizard **nicht** erneut angezeigt.
- Unvollständiges Setup wird beim nächsten Start an der ersten offenen Stelle fortgesetzt (`OnboardingGate.resumeStep`: USER → BRAND → MODEL → DONE).
- Wizard-Schritte: **A** Anzeigename + Sprache + Land, **B** Marke (Karten), **C** Modell (markengefiltert, „Mein Modell ist nicht gelistet“, optionale Modell-/Seriennummer), **D** Zusammenfassung + Feature-Vorschau.

---

## 5. Personalisierung & Einstellungen (Section 8/9)

- **Dashboard:** Karte „MEIN SCOOTER“ zeigt Profil-Marke/-Modell und die Feature-Verfügbarkeit. Nicht verfügbare Funktionen werden **ausgewiesen** („Für dieses Modell nicht verfügbar“), nicht entfernt.
- **CONFIG:** neuer Bereich „MEIN PROFIL & SCOOTER“ mit Button „PROFIL & SCOOTER BEARBEITEN“ (Wizard im Bearbeiten-Modus, Rückkehr zu CONFIG).
- **Sprache:** Bei abgeschlossenem Profil bestimmt die gespeicherte Sprache appweit die lokalisierten Subsysteme (Rechts-/Ländertexte, Wizard, Profil-UI).

---

## 6. Testergebnisse (Section 12)

**Vollständige Suite:** 35 Suites, **514 Tests, 0 Failures, 0 Errors, 0 Skipped** (Baseline 500 + 14 neue).

Neue Tests (`Phase170OnboardingTest`, 14):
1. Wizard beim ersten Start angezeigt ✔
2. Setup bleibt nach Neustart abgeschlossen ✔
3. Unvollständiges Setup wird fortgesetzt ✔
4. Profilname korrekt gespeichert ✔
5. Sprache gespeichert/angewendet ✔
6. Marke + Modell korrekt gespeichert ✔
7. Modellwechsel aktualisiert verfügbare Module ✔
8. Unbekannte Modelle bleiben sicher ✔
9. Marken nicht verwechselt ✔ (+ alle geforderten Marken inkl. INMOTION)
10. Alte Diagnosewerte nicht neuem Scooter zugeschrieben ✔
11. Bestehende Adapter-/BLE-Funktionen unverändert ✔
+ Codec-Robustheit, „nicht gelistetes Modell erlaubt Abschluss“.

---

## 7. Build-Ergebnis (Section 13)

`./gradlew assembleDebug --offline` → **BUILD SUCCESSFUL**
APK: `app/build/outputs/apk/debug/app-debug.apk` (19.144.985 Bytes)

---

## 8. Ehrlicher Status (Regel 4/10)

| Bereich | Im Code | Automatisiert getestet | Auf Hardware bestätigt | Noch nicht verifiziert |
|---|---|---|---|---|
| Wizard-Ablauf / Resume / Persistenz | ja | ja | nein | Gerätetest offen |
| Marken-/Modell-Speicherung & -Trennung | ja | ja | nein | — |
| Feature-Ableitung aus Adaptern | ja | ja | nein | reale Decoder-Werte |
| Session-Reset bei Profilwechsel | ja | ja | nein | Gerätetest offen |
| Sprache appweit | teilweise | ja (Codec/Resolver) | nein | vollständige UI-Übersetzung offen |

---

## 9. Bekannte Einschränkungen

- **Sprache:** Persistiert und auf die lokalisierten Subsysteme angewendet. Die verbleibenden fest kodierten deutschen UI-Strings der großen `MainActivity` sind **noch nicht vollständig** übersetzt (kein Maschinen-Übersetzungs-Rewrite). Bewusst als offener Punkt dokumentiert.
- **Keine Hardware-Bestätigung:** Wizard/Profil ändern keine BLE-Kompatibilität. Unbekannte Modelle bleiben read-only-diagnostisch.
- **INMOTION** ist wählbar, hat aber noch **keine** geführten Modelle/Protokolle → bleibt „nicht gelistet“.
- **Basis:** PR zielt gegen `main` (`c03f774`). Phase 16.3.4 (PR #26) ist noch offen und **nicht** Voraussetzung für 17.0.

---

## 10. Sicherheitsgrenzen (Section 11) — eingehalten

Keine unbekannten BLE-Write-Befehle, keine Geschwindigkeits-Umgehung, kein Firmware-Flash, keine Decoder-Offset-Änderung, keine erfundenen UUIDs/Protokollrollen, keine vorgetäuschte Modell-Kompatibilität. Profilwahl allein autorisiert **keine** unbekannten Befehle. Bestehende Read-Only-Diagnose und Sicherheitsgates unverändert.
