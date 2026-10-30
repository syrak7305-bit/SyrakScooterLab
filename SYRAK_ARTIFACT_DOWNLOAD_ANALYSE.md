# SYRAK SCOOTERLAB — Analyse: fehlendes Download-Symbol beim APK-Artifact

**Datum der Untersuchung:** 2026-10-30
**Repository:** `syrak7305-bit/SyrakScooterLab` (public, default branch `main`)
**Untersuchtes Artifact:** `SyrakScooterLab-debug-apk`
**Letzter erfolgreicher Build (main):** Run `40986890826`, SHA `ac111fad85e4333d2c5faafd8f891af9730b8d94`, Status `success`

---

## 1. Kurzfazit (vorweg)

Der Build, der Upload und die API-Auslieferung des Artifacts sind **vollständig korrekt**.
Das fehlende Download-Symbol in der mobilen GitHub-Ansicht wird **nicht vom Repository, nicht vom Workflow und nicht von `actions/upload-artifact`** verursacht, sondern von der **serverseitigen HTML-Ausgabe der GitHub-Weboberfläche selbst**.

Es gibt **keine Änderung an der Workflow-Datei**, weil es keinen technisch begründbaren Repository-seitigen Fix gibt. Ein Blindfix hätte den funktionierenden Build-/Upload-Pfad nur riskiert.

**Das Problem liegt außerhalb unseres Repositories und ist von uns nicht beeinflussbar.**

---

## 2. Geprüfte Punkte (Auftrag Punkt 1–8)

### 2.1 Workflow-Datei
Es existiert genau **eine** aktive Workflow-Datei: `.github/workflows/build.yml` (1532 Bytes).
`ci/build.yml.proposed` ist ein **nicht aktiver** Vorschlag (kein `.yml` unter `.github/workflows/`).

Relevanter Upload-Schritt:

```yaml
- name: Upload APK Artifact
  uses: actions/upload-artifact@v4
  if: success()
  with:
    name: SyrakScooterLab-debug-apk
    path: app/build/outputs/apk/debug/app-debug.apk
    if-no-files-found: error
```

Bewertung: Name, Pfad, Fehlerverhalten (`if-no-files-found: error`) und Bedingung (`if: success()`) sind Standard und korrekt.

### 2.2 `actions/upload-artifact` und Konfiguration
- Version: `@v4` (aktuell, nicht deprecated).
- **Keine** gesetzten Sonderoptionen: kein `retention-days`, kein `compression-level`, kein `overwrite`, kein `include-hidden-files`.
- Es werden also **ausschließlich GitHub-Defaults** verwendet. Es gibt hier nichts, was das Web-UI-Rendering beeinflussen könnte.

### 2.3 Artifact-Name, Pfad, Upload-Bedingung
- Name: `SyrakScooterLab-debug-apk` — konstant, keine Sonderzeichen, keine Matrix-Suffixe.
- Pfad: exakt eine Datei (`app-debug.apk`), kein Glob, kein Verzeichnis.
- Bedingung: `if: success()` — gültige Statusfunktion.

→ Alles unauffällig.

### 2.4 Retention und Limitierungen
Artifacts-API (`GET /repos/.../actions/artifacts`):

| Artifact-ID | expired | Größe (Bytes) | erstellt | Branch |
|---|---|---|---|---|
| 12947585315 | false | 17.663.026 | 2026-10-30T01:28:37Z | feat/phase10-multibrand-architecture |
| 12947532819 | false | 17.663.034 | 2026-10-30T01:40:42Z | main |
| 12946596270 | false | 17.650.323 | 2026-10-30T01:14:54Z | main |
| 12945253487 | false | 17.650.324 | 2026-10-30T00:58:45Z | feat/phase9-professional-diagnostics |
| 12943727295 | false | 17.624.649 | 2026-10-30T00:16:51Z | fix/ci-maven-central-403-resilience |

Detail für `12947532819`: `expires_at = 2027-01-28T01:36:57Z` → **Default-Retention von 90 Tagen**, nicht abgelaufen.

→ Retention ist **nicht** die Ursache.

### 2.5 Können Upload-Art oder Workflow-Konfiguration das fehlende Symbol erklären?
**Nein.** Das Symbol wird nicht vom Workflow gesteuert, sondern von GitHubs Frontend beim Rendern der Run-Seite. Belegt in 2.7.

### 2.6 Unterschied PR-Build vs. `main`-Build
Beide Branches erzeugen **identische** Artifacts (gleicher Name, gleiche Größe ~17,6 MB, gleicher Pfad). Es gibt **keinen** Unterschied in der Upload-Konfiguration. Das fehlende Symbol tritt bei **beiden** auf.

### 2.7 Liefert die GitHub-API das Artifact korrekt?
**Ja.**
- Authentifizierter ZIP-Download von `.../actions/artifacts/12947532819/zip`:
  - HTTP 200, **17.663.034 Bytes**
  - valides ZIP
  - enthält genau einen Eintrag: `app-debug.apk` (18.444.098 Bytes unkomprimiert)
- Anonymer Zugriff auf denselben Endpoint: **HTTP 401** — erwartetes Verhalten (die Artifact-API verlangt auch bei öffentlichen Repos eine Authentifizierung).

→ Die API ist **fehlerfrei**. Das Artifact ist real vorhanden und herunterladbar.

### 2.8 Ist das fehlende Symbol ein reines GitHub-Web-/Mobile-UI-Problem?
**Ja — mit konkreten Belegen:**

1. Die **serverseitig gerenderte** Run-Seite (`GET /actions/runs/40986890826`, HTTP 200, 170.255 Bytes) enthält die Artifact-Zeile:
   ```html
   <tr role="row" data-artifact-id="12947532819">
   ```
   mit Name, Größe (`16.8 MB`), Digest (`sha256:41aa38c2…`) und einem Clipboard-Copy-Button für den Digest.
   Die **abschließende Action-Zelle ist jedoch leer**:
   ```html
   <td class="compact tmp-pr-3 text-right color-fg-muted"><div class="d-flex flex-justify-end"></div></td>
   ```
   → Es wird **kein** Download-Anker (`<a>`) ausgegeben.

2. `grep -c 'octicon-download'` auf der aktuellen Run-Seite: **0**.
   Ebenso **0** auf drei älteren Runs (`40984298465`, `40979653163`, `40973241626`).
   → Das Symbol fehlt **konsistent** über alle Runs, auch über ältere. Es ist **keine Regression** durch eine unserer Änderungen.

3. Der in der Seite referenzierte Partial-Endpoint `/actions/runs/40986890826/artifacts_partial` liefert **HTTP 404**.

4. Der Legacy-Direktlink `github.com/{owner}/{repo}/actions/runs/{id}/artifacts/{id}` leitet **307** weiter auf `github.com/{owner}/{repo}/suites/{suiteId}/artifacts/{id}` — dieser liefert **HTTP 404**.

→ GitHub rendert die Artifact-Zeile ohne Download-Control und die zugehörigen Nachlade-/Direkt-Routen antworten mit 404. Das ist ein **Upstream-Fehler der GitHub-Weboberfläche**, kein Repository-Problem.

---

## 3. Was NICHT verändert wurde (und warum)

- **Keine** Änderung an `.github/workflows/build.yml`.
- **Keine** Änderung an Artifact-Name, Pfad, Retention oder Upload-Optionen.
- **Kein** Wechsel der `upload-artifact`-Version.

Begründung: Der Build- und Upload-Pfad funktioniert nachweislich (API liefert ein valides ZIP). Jede Änderung an dieser Stelle würde das Risiko erhöhen, ohne das UI-Problem zu lösen — das UI-Problem entsteht in GitHubs Frontend, nicht in unserem YAML.

---

## 4. Funktionsfähiger Workaround (bis GitHub das UI korrigiert)

Das Artifact ist **jederzeit** per API herunterladbar:

```bash
# Artifact-ID des letzten main-Runs ermitteln
gh api repos/syrak7305-bit/SyrakScooterLab/actions/artifacts \
  --jq '.artifacts[] | select(.expired==false) | "\(.id) \(.name) \(.created_at)"'

# ZIP herunterladen (authentifiziert)
gh api repos/syrak7305-bit/SyrakScooterLab/actions/artifacts/<ARTIFACT_ID>/zip > apk-artifact.zip
unzip -o apk-artifact.zip   # enthält app-debug.apk
```

Alternativ über die GitHub CLI direkt:

```bash
gh run download 40986890826 -n SyrakScooterLab-debug-apk
```

Beide Wege funktionieren unabhängig vom fehlenden Web-UI-Symbol.

---

## 5. Empfehlung

1. **Nichts am Workflow ändern.** Der Upload ist korrekt.
2. Für die tägliche Nutzung `gh run download` bzw. den API-ZIP-Endpoint verwenden (siehe Abschnitt 4).
3. Das fehlende Symbol als **bekanntes Upstream-UI-Verhalten** dokumentieren (dieses Dokument + Hinweis in `ci/README.md`).
4. Sollte GitHub das UI später korrigieren, ist keinerlei Repository-Änderung nötig — das Artifact ist bereits korrekt hinterlegt.

---

## 6. Abgrenzung

Diese Analyse behebt **kein** GitHub-UI-Problem und behauptet das auch nicht. Sie belegt mit reproduzierbaren Kommandos, dass die Ursache außerhalb des Repositories liegt, und dokumentiert den funktionierenden API-Downloadweg als Alternative.
