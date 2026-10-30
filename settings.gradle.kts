pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal() // WICHTIG für das Kotlin Compose Plugin!

        // Fallback: offizieller, von Google gehosteter Read-Through-Spiegel von
        // Maven Central. Wird NUR befragt, wenn mavenCentral() bzw. der
        // Plugin-Portal ein Artefakt nicht liefert (z. B. HTTP 403 durch
        // Cloudflare-/CDN-Blocking einzelner CI-Runner-IPs, siehe Build #157).
        // Es werden keine Versionen geändert und keine Abhängigkeiten ersetzt:
        // die Primaerquellen bleiben google() -> mavenCentral() -> gradlePluginPortal().
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // Siehe Kommentar oben in pluginManagement: reiner Ausfall-Fallback,
        // keine Aenderung an Versionen oder Abhaengigkeiten.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
    }
}

rootProject.name = "SyrakScooterLab"
include(":app")
