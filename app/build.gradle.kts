plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.syrak.scooterlab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.syrak.scooterlab"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.androidx.ui.tooling)
}

/* ============================================================================
 * PHASE 16.2 — CI-ABSICHERUNG
 * ----------------------------------------------------------------------------
 * BEFUND: Der bestehende GitHub-Actions-Workflow rief bisher NUR
 * `assembleDebug` auf. Ein erfolgreicher Build galt damit faelschlich als
 * vollstaendige Qualitaetssicherung, obwohl KEINE Tests liefen.
 *
 * Der Workflow selbst konnte in dieser Umgebung nicht geaendert werden
 * (der Bot-Token besitzt keine `workflows`-Berechtigung). Deshalb wird die
 * Testausfuehrung hier verbindlich in den Build-Graphen gehaengt:
 *
 *   assembleDebug  ->  haengt ab von  ->  testDebugUnitTest
 *
 * Der vorhandene Workflow ruft `assembleDebug` auf; damit laufen jetzt
 * zwangslaeufig alle Unit-Tests. Schlaegt ein Test fehl, schlaegt
 * `testDebugUnitTest` und damit auch `assembleDebug` und der CI-Job fehl.
 * Das ist bewusst KEIN stiller Zusatzschritt, sondern eine harte Abhaengigkeit.
 * ==========================================================================*/
tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("testDebugUnitTest")
}
