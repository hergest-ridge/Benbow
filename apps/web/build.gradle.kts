import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The web client: the same app as on Android and desktop, compiled to WebAssembly (ADR-029).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "benbow.js" }
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared:app"))
        }
    }
}

// sql.js loads its WebAssembly file from beside the page's script.
tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(":kotlinWasmNpmInstall")
    from(rootProject.layout.buildDirectory.file("wasm/node_modules/sql.js/dist/sql-wasm-browser.wasm"))
}
