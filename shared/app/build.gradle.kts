import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Screens of the app (roadmap 2.4) on top of the offline-first client: Android, desktop and, the same code, the
// browser (ADR-029). Platform pieces live in androidMain / jvmMain / wasmJsMain, JVM-only ones in src/jvmShared.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    jvm()
    android {
        namespace = "app.benbow.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { enabled = false } } } // its tests run on desktop (jvmTest)

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:ui"))
            api(project(":shared:client"))
            implementation(libs.compose.components.resources)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.navigation3.ui)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(libs.materialkolor) // the player's colors from a cover (ADR-035)
        }
        // Capture (Photo Picker, camera, voice) and playback live in the platform source sets.
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
        }
        jvmMain {
            kotlin.srcDir("src/jvmShared/kotlin")
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.androidx.exifinterface)
        }
        // Desktop: DWM calls that paint the Windows title bar in the app's colors.
        jvmMain.dependencies {
            implementation(libs.jna)
        }
        // Headless UI smoke test of the whole app on desktop; it also writes screenshots to build/screens.
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

compose.resources {
    packageOfResClass = "app.benbow.app.resources"
}

// The app's UI tests run on desktop only; the browser build is a library with no test bundle (CMP-4906's check).
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }
