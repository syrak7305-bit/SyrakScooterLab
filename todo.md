# PHASE 12 — TODO (Stand: abgeschlossen)

## 0. Vorarbeiten
- [x] Repository-Stand geprueft (PR #16 war noch OFFEN, CI gruen, gemerged -> main 5f82f5c)
- [x] Branch `feat/phase12-professional-ui` erstellt
- [x] Bestandsaufnahme: echte UI ist klassisches Android-View-Layout in `MainActivity`, nicht Compose
- [x] Git-Historie nach verschwundenen Funktionen durchsucht (75 Commits, `git log -S`)

## 12.1 Wiederherstellung
- [x] Vollstaendiger Haftungsausschluss (7 Abschnitte) aus Commit `2d7638e` wiederhergestellt
- [x] Haftungsausschluss scrollbar, 6-s-Bestaetigungssperre, Persistenz (`disclaimersAccepted`)
- [x] Trip-Timer wiederhergestellt (Live-Anzeige "Fahrtzeit", `TripClock`)
- [x] Servo-Start: in der gesamten Historie NICHT vorhanden -> dokumentiert, nicht erfunden
- [x] Laender-Hinweise mit Quelle + Stand (`CountryLegalCatalog`)
- [x] Manuelle Landauswahl + neutraler Hinweis fuer unbekannte Laender
- [x] Keine erfundenen Gesetze/Geschwindigkeitsgrenzen

## 12.2 Redesign
- [x] Palette: Anthrazit + Cyan, Rot nur Gefahr, Gruen bestaetigt, Orange Warnung
- [x] Buttons 44 dp, einheitliche Radien, Abstaende 8/16/24
- [x] Monospace/tabellarische Messwerte
- [x] Fuenf Tabs mit aktivem Label

## 12.3 Bluetooth
- [x] Klassifikation bekannter/wahrscheinlicher Scooter, unbekannte getrennt
- [x] Hersteller + Modell + Erkennungsguete, Signalstaerke als Balken + Text
- [x] "Alle Geraete"-Umschalter, Herstellerfilter, Scan-Abbruch, Scan-Status
- [x] Unbekanntes Geraet wird nicht als Scooter behandelt ("Mi Band 7"-Fix)
- [x] Granulare Verbindungsstufen (`connectionStage()`)

## 12.4 BLE-Fehler
- [x] Poll-Fehlerflut behoben (`PollGate`, `hasWriteCharacteristic()`, stille Polls)
- [x] Polling stoppt bei fehlenden Voraussetzungen und bei Disconnect
- [x] GATT-Struktur mit Properties (R/W/N/I) in der UI
- [x] RSSI/MTU/Dauer/Abbrueche/Fehler angezeigt
- [x] Systemstatus-Kategorien (7 Zeilen mit OK/OFFEN)
- [x] UUID-Widerspruch `BleConstants` vs. `BleEngine` dokumentiert (nicht geraten)
- [x] JSON/CSV-Export erhalten

## 12.5 Cockpit
- [x] Halbkreis-Tacho (`CockpitGaugeView`)
- [x] Verlaufsgraphen (`SparklineView`), Min/Max
- [x] "Nicht verfuegbar" statt Platzhalter, DEMO-Kennzeichnung

## 12.6 Tools
- [x] Alle Slider/Schalter erhalten, Min/Max-Zeile, Einheit in einer Zeile
- [x] Fahrprofile als kompaktes Segmented Control
- [x] Erweiterte Einstellungen ein-/ausklappbar
- [x] Gefahr-Kennzeichnung
- [x] Polizei-Modus: ein Tap, Voraussetzungen geprueft, kein Erfolgston ohne Freigabe

## 12.7 Verbesserungen
- [x] A: Schnellzugriff auf zuletzt verwendeten Scooter (kein Auto-Connect)
- [x] B: Diagnose-Historie lokal + Vergleich zweier Berichte
- [x] C: Verbindungsqualitaet (Signal, MTU, Dauer, Abbrueche, Instabilitaetshinweis)
- [x] D: Systemstatus-Uebersicht
- [x] E: Onboarding beim ersten Start (ersetzt den Haftungsausschluss nicht)
- [x] F: Barrierefreiheit (Statuswort statt nur Farbe, grosse Touch-Ziele)

## 12.8 Sicherheit
- [x] `TuningSafetyGate`, `WriteGuard`, `AutoHandshakePolicy` unveraendert aktiv
- [x] Read-only-Default fuer unbekannte Geraete erhalten

## 12.9 Tests
- [x] Unit-Tests fuer alle neuen Logikbausteine (`Phase12SupportTest`)
- [x] Alle Unit-Tests: 268 Tests, 0 Fehler
- [x] `assembleDebug` erfolgreich
- [x] Regressionspruefung (bestehende Tests unveraendert gruen)

## Abschluss
- [x] `PHASE12_BERICHT.md` geschrieben
- [x] Commit + Push auf `feat/phase12-professional-ui`
- [x] PR erstellt
