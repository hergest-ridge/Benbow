// Macrobenchmarks and the baseline profile generator. They drive the bench builds of :apps:android
// (app.benbow.bench, own DB, seeded with made-up entries), never the owner's app.benbow.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "app.benbow.benchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":apps:android"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // connectedBenchmarkAndroidTest measures the R8 build; connectedProfilingAndroidTest collects the profile.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("profiling") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
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
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.uiautomator)
}
