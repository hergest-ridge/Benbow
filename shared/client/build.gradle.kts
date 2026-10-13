// Offline-first client data layer for Android and desktop (docs/sync.md): local DB, outbox, sync, pinned TLS.
// The web client is thin (ADR-003) and doesn't use this module.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    jvm()
    android {
        namespace = "app.benbow.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
        }
        // Android and desktop share the OkHttp/TLS code; the default hierarchy has no JVM-only set for both.
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies { implementation(libs.ktor.client.okhttp) }
        }
        jvmMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies { implementation(libs.ktor.client.okhttp) }
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(project(":shared:testing"))
        }
    }
}

sqldelight {
    databases {
        create("BenbowDb") {
            packageName = "app.benbow.client.db"
            dialect(libs.sqldelight.dialect)
            module(libs.sqldelight.json.module)
        }
    }
}
