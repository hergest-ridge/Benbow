import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Test fixtures shared by core and server tests (sync simulation, in-memory store). Never shipped.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    jvm()
    android {
        namespace = "app.benbow.testing"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs() }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            api(libs.kotlin.test)
        }
    }
}
