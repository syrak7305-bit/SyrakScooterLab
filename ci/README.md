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
  -> 103 Tests, 0 Failures, 0 Errors, 0 Skipped

./gradlew --no-build-cache --no-configuration-cache assembleDebug
  -> BUILD SUCCESSFUL, app-debug.apk
```

Diese Zahlen stammen aus einem lokalen Lauf auf dem Branch
`fix/phase4-ci-profile-persistence-ble-lifecycle`, **nicht** aus GitHub Actions.
