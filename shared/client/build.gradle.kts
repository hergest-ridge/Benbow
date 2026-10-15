import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Offline-first client data layer (docs/sync.md): local DB, outbox, sync, pinned TLS. Android and desktop, and the
// browser (ADR-029): the same code, with the browser's fetch, SQLite built for wasm and media kept in memory.
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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { enabled = false } }
        nodejs() // the tests run in Node: the browser driver's logic with an in-memory shelf
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
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            // SQLite in the browser, with FTS4 (ADR-029); its .wasm file is copied next to the web app's script.
            implementation(npm("sql.js", libs.versions.sqljs.get()))
            // Time zones by name (an ICS file's TZID, the device's zone): kotlinx-datetime reads them from js-joda's database.
            // 2.23.0 is the last release for the @js-joda/core 3.x that kotlinx-datetime 0.8 uses.
            implementation(npm("@js-joda/timezone", "2.23.0"))
        }
        wasmJsTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
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
        // The calendar's own file (ADR-027): its schema and migrations never touch the journal's.
        create("CalendarDb") {
            packageName = "app.benbow.client.calendar.db"
            srcDirs("src/commonMain/calendar-sqldelight")
            dialect(libs.sqldelight.dialect)
        }
    }
}
