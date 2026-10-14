import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The release key lives outside the repo (tools/create-release-key.ps1). Android only accepts updates signed
// with the same key, so losing it means uninstalling, which wipes anything not yet synced.
val releaseSigning = providers.gradleProperty("benbow.signing")
    .orElse(providers.systemProperty("user.home").map { "$it/.benbow/release-signing.properties" })
    .map { file(it) }
    .get()
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "app.benbow.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.benbow"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // The owner's phones are arm64; ONNX Runtime's native library is big (ADR-025).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // The real app (app.benbow): only release builds, so test builds can never touch its data.
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Development builds install next to it as app.benbow.debug, with their own local DB.
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        // Non-debuggable build for performance checks; installs next to debug as app.benbow.bench.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"
            isDebuggable = false
            matchingFallbacks += "release"
        }
        // The same without R8: baseline profiles are collected here so their names match the code as written.
        create("profiling") {
            initWith(getByName("benchmark"))
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // The profiling build shares the benchmark build's seeding tool, manifest and name.
    sourceSets.getByName("profiling") {
        manifest.srcFile("src/benchmark/AndroidManifest.xml")
        kotlin.directories += "src/benchmark/kotlin"
        res.srcDir("src/benchmark/res")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
}

dependencies {
    implementation(project(":shared:app"))
    implementation(libs.sqldelight.android.driver)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.activity.compose)
    // Installs the baseline profile (src/main/baseline-prof.txt) on sideloaded installs too.
    implementation(libs.androidx.profileinstaller)
    // Speech recognition runs on ONNX Runtime in the sandboxed AsrService only (its telemetry provider is removed in the
    // manifest, and the sandbox has no network anyway).
    implementation(libs.onnxruntime.android)
}
