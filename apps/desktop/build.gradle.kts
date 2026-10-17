import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
}

dependencies {
    implementation(project(":shared:app"))
    implementation(libs.sqldelight.sqlite.driver)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    // The player (ADR-035): JavaFX Media, for this machine's OS (OpenJFX publishes one jar per platform).
    val os = System.getProperty("os.name").lowercase()
    val fx = if ("win" in os) {
        "win"
    } else if ("mac" in os) {
        "mac"
    } else {
        "linux"
    }
    for (module in listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.media)) implementation(variantOf(module) { classifier(fx) })
}

compose.desktop {
    application {
        mainClass = "app.benbow.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Benbow"
            modules("java.sql", "jdk.unsupported") // JavaFX needs jdk.unsupported
            packageVersion = "0.1.0"
        }
    }
}
