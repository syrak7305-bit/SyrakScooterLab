# CI-Vorschlag: Unit-Tests in GitHub Actions

## Warum diese Datei hier liegt und nicht in `.github/workflows/`

Der automatisierte Push von `.github/workflows/build.yml` wird vom Server
abgelehnt:

```
! [remote rejected] HEAD -> fix/phase4-ci-profile-persistence-ble-lifecycle
  (refusing to allow a GitHub App to create or update workflow
   `.github/workflows/build.yml` without `workflows` permission)
error: failed to push some refs to
  'https://github.com/syrak7305-bit/SyrakScooterLab.git'
```

Der verwendete GitHub-App-Token hat kein `workflows`-Recht. Es wurde **nicht**
versucht, diese Grenze zu umgehen. Die fertige Datei liegt deshalb als
`ci/build.yml.proposed` im Repository und kann von einem Maintainer mit
`workflows`-Recht übernommen werden.

**Wichtig:** Solange die Datei nicht unter `.github/workflows/build.yml` liegt
und dort erfolgreich gelaufen ist, laufen in der CI **keine** Unit-Tests.
Ein grüner CI-Lauf beweist aktuell nur, dass `assembleDebug` funktioniert.

## Übernehmen

```bash
cp ci/build.yml.proposed .github/workflows/build.yml
git add .github/workflows/build.yml
git commit -m "ci: run unit tests before the APK build and upload the JUnit report"
git push
```

Alternativ den Inhalt direkt im GitHub-Web-Editor in
`.github/workflows/build.yml` einfügen.

## Was sich gegenüber dem aktuellen Workflow ändert

Es werden **keine** bestehenden Trigger oder Schritte entfernt:

| Bereich | vorher | nachher |
|---|---|---|
| Trigger (`push` main, `pull_request` main, `workflow_dispatch`) | vorhanden | unverändert |
| Checkout inkl. `head.sha` für PRs | vorhanden | unverändert |
| JDK 17, `chmod +x gradlew`, Compose-Check, `--version` | vorhanden | unverändert |
| `testDebugUnitTest` | **fehlt** | neu, **vor** dem APK-Build |
| JUnit-Bericht als Artifact | fehlt | neu, `if: always()` (auch bei Fehlschlag) |
| `assembleDebug` | vorhanden | unverändert |
| APK-Upload | vorhanden | unverändert |

Ein fehlgeschlagener Test lässt den Job fehlschlagen, weil der
Gradle-Task `testDebugUnitTest` mit Exit-Code ≠ 0 endet. Der Bericht wird
durch `if: always()` trotzdem hochgeladen, sofern er erzeugt wurde
(`if-no-files-found: warn`).

## Lokal verifiziert (nicht in CI)

```
./gradlew --no-build-cache --no-configuration-cache testDebugUnitTest
  -> 110 Tests, 0 Failures, 0 Errors, 0 Skipped

./gradlew --no-build-cache --no-configuration-cache assembleDebug
  -> BUILD SUCCESSFUL, app-debug.apk
```

Diese Zahlen stammen aus einem lokalen Lauf auf dem Branch
`fix/phase5-ble-scan-and-connection-switch`, **nicht** aus GitHub Actions.

---

## Update nach Build #157 (HTTP 403) — Branch `fix/ci-maven-central-403-resilience`

Build #157 (`main`, Commit `6c0066f`) brach in der Konfigurationsphase ab. Die
Auswertung des Logs zeigt:

- **Alle** 403-Antworten kamen von `repo.maven.apache.org` (Maven Central).
- `dl.google.com` (Google Maven) lieferte im selben Lauf normal aus.
- Derselbe Commit war wenige Minuten vorher auf dem PR-Branch gruen
  (Runs `40974802688` / `40974823913`) und lud dieselben Artefakte von
  Maven Central.
- Ein lokaler Build aus leerem Cache mit `--refresh-dependencies` ist
  erfolgreich; Maven Central antwortet aktuell mit HTTP 200.

Damit ist die Ursache ein **fluechtiges CDN-/IP-Blocking von Maven Central
gegenueber dem jeweiligen Runner**, keine Fehlkonfiguration des Projekts.
Die Repository-Reihenfolge (`google()` -> `mavenCentral()` ->
`gradlePluginPortal()`) war korrekt.

`ci/build.yml.proposed` enthaelt deshalb zusaetzlich zu den Unit-Tests einen
**einmaligen Retry** des `assembleDebug`-Schritts. Der Retry aendert keine
Abhaengigkeiten und keine Versionen; er gibt einem nachweislich fluechtigen
Infrastrukturfehler eine zweite Chance und laesst den Job danach hart
fehlschlagen.

Der Push dieser Workflow-Aenderung wurde erneut vom Server abgelehnt:

```
! [remote rejected] ... (refusing to allow a GitHub App to create or update
  workflow `.github/workflows/build.yml` without `workflows` permission)
```

Es wird weiterhin **nicht** versucht, diese Grenze zu umgehen. Zum
Uebernehmen ist ein Token mit `workflows`-Recht noetig (oder der
GitHub-Web-Editor):

```bash
cp ci/build.yml.proposed .github/workflows/build.yml
git add .github/workflows/build.yml
git commit -m "ci: unit tests + one retry for transient Maven Central 403"
git push
```

Solange das nicht erfolgt, laufen in der CI **weiterhin keine Unit-Tests**.

---

## Fehlendes Download-Symbol beim APK-Artifact (Stand 2026-10-30)

In der mobilen GitHub-Ansicht fehlt neben dem Kopier-Symbol der Download-Button
fuer das Artifact `SyrakScooterLab-debug-apk`.

**Untersuchungsergebnis:** Build, Upload und API-Auslieferung sind korrekt.

- Artifacts-API: alle Artifacts `expired=false`, `expires_at=2027-01-28` (90-Tage-Default).
- Authentifizierter ZIP-Download (`/actions/artifacts/{id}/zip`): HTTP 200, valides ZIP,
  enthaelt `app-debug.apk`.
- Serverseitig gerenderte Run-Seite enthaelt die Artifact-Zeile, aber eine **leere
  Action-Zelle** (kein Download-Anker); `octicon-download` kommt 0x vor — auch auf
  aelteren Runs (keine Regression).
- `/actions/runs/{id}/artifacts_partial` -> HTTP 404.
- Legacy `/actions/runs/{id}/artifacts/{id}` -> 307 -> `/suites/{suiteId}/artifacts/{id}` -> HTTP 404.

**Fazit:** Das fehlende Symbol wird von GitHubs Frontend erzeugt und ist vom
Repository aus **nicht beeinflussbar**. Es wurde deshalb **keine** Aenderung an
`.github/workflows/build.yml` vorgenommen.

**Workaround (funktioniert unabhaengig vom UI):**

```bash
gh run download <RUN_ID> -n SyrakScooterLab-debug-apk
# oder
gh api repos/syrak7305-bit/SyrakScooterLab/actions/artifacts/<ARTIFACT_ID>/zip > apk.zip
```

Vollstaendige Analyse: `SYRAK_ARTIFACT_DOWNLOAD_ANALYSE.md` (im Repo-Root der
Doku-Ablage).
