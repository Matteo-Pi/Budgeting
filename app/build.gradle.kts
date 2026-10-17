plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.matteopi.budgeting"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.matteopi.budgeting"
        minSdk = 26
        targetSdk = 36
        // Each CI build gets its run number, so Settings (and Android's app info) shows which build
        // is installed, and every newer build counts as an update. Local builds stay at 1.
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ciBuild ?: 1
        versionName = if (ciBuild != null) "0.1.0 (build $ciBuild)" else "0.1.0 (local)"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // With the orchestrator, every test starts from a freshly cleared app (empty database).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Screenshots go through Test Storage, which survives clearPackageData and is pulled
        // automatically into build/outputs/connected_android_test_additional_output.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    // One committed keystore so every build (CI, local, future sessions) has the same
    // signature. Android only installs an update over the top if signatures match;
    // a mismatch forces an uninstall, which would wipe the on-device database.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // v2 alone is enough for Android's own installer (minSdk 26), but some third-party
            // installers only read the old v1 (JAR) signature and report "no certificates".
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // PDFBox brings three Bouncy Castle jars, each with this multi-release manifest; keeping none of them
            // avoids the "2 files found with path" merge error.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        animationsDisabled = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("io.github.matteopi.budgeting:core:0.1.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    // Receipt OCR: the bundled Latin model, so it works offline and nothing leaves the phone (docs/PLAN.md §10.1).
    implementation(libs.mlkit.text.recognition)
    // Bundled barcode model (offline) for scanning food packs (docs/PLAN.md §11.5).
    implementation(libs.mlkit.barcode.scanning)
    // Santander PDF statements: PDFBox reads the text layer with exact positions, no OCR (docs/PLAN.md §5.16). Apache-2.0.
    implementation(libs.pdfbox.android)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests run on an emulator in CI (.github/workflows/build.yml, device-tests job).
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.storage)
    androidTestImplementation(libs.androidx.work.testing)
    // Migration tests (data/MigrationTest.kt). The Room Gradle plugin adds the schemas folder to the
    // androidTest assets itself, so no sourceSets entry is needed (a second one would duplicate it).
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.glance.appwidget.testing)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
    debugImplementation(libs.compose.ui.test.manifest)
}
