import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
}

application {
    mainClass = "app.benbow.server.MainKt"
}

// Generates OpenAPI metadata from routing code at compile time; served at /api/docs.
ktor {
    openApi {
        enabled = true
        codeInferenceEnabled = true
    }
}

dependencies {
    implementation(project(":shared:core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.routing.openapi)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(project(":shared:testing"))
    testImplementation(project(":shared:client"))
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---- Packaging (docs/architecture.md#packaging-and-deployment-windows) ----
// The bundled runtime is Temurin 25 LTS (it ships jmods for jlink); foojay provisions it on first use.
val packagingJdk = javaToolchains.compilerFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}
// From `jdeps --print-module-deps` over the server classpath, plus jdk.charsets for Windows console code pages.
val runtimeModules = "java.base,java.desktop,java.instrument,java.management,java.naming,java.sql,jdk.charsets,jdk.jfr,jdk.unsupported"
val windows = System.getProperty("os.name").startsWith("Windows")

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    description = "Builds the minimal Java runtime bundled with the server."
    // Locals only: lambdas must not capture script-level values (configuration cache).
    val output = layout.buildDirectory.dir("jlink/runtime")
    val jlink = packagingJdk.map { it.metadata.installationPath.asFile.resolve(if (windows) "bin/jlink.exe" else "bin/jlink").absolutePath }
    val modules = runtimeModules
    inputs.property("modules", modules)
    outputs.dir(output)
    doFirst {
        output.get().asFile.deleteRecursively()
        executable = jlink.get()
    }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--add-modules",
                modules,
                "--strip-debug",
                "--no-man-pages",
                "--no-header-files",
                "--compress=zip-6",
                "--output",
                output.get().asFile.absolutePath,
            )
        },
    )
}

// SHA-256 of WinSW.NET461.exe v2.12.0 as downloaded from the official GitHub release (655872 bytes).
val winswSha256 = "b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f"

tasks.register<Sync>("packageServer") {
    group = "distribution"
    description = "Self-contained server folder: bundled runtime, jars, WinSW service config and scripts."
    dependsOn(tasks.named("installDist"))
    val winswDir = layout.projectDirectory.dir("packaging/winsw")
    val expectedSha = winswSha256
    val packageDir = layout.buildDirectory.dir("package/benbow-server")
    val onWindows = windows
    doFirst {
        // Refuse to overwrite a service that runs straight from this folder (older installs did that);
        // install-service.cmd now installs into Program Files.
        if (onWindows) {
            val service = ProcessBuilder(
                "sc",
                "qc",
                "BenbowServer",
            ).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            check(!service.contains(packageDir.get().asFile.absolutePath, ignoreCase = true)) {
                "The BenbowServer service runs from ${packageDir.get().asFile}. Run its uninstall-service.cmd (as admin) first, " +
                    "then rebuild and use install-service.cmd, which installs into Program Files."
            }
        }
        winswDir.asFile.listFiles { f -> f.name.startsWith("WinSW") && f.name.endsWith(".exe") }.orEmpty().forEach { exe ->
            val actual = MessageDigest.getInstance("SHA-256").digest(exe.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == expectedSha) { "${exe.name} has SHA-256 $actual, expected $expectedSha (WinSW v2.12.0). Re-download it." }
        }
    }
    into(layout.buildDirectory.dir("package/benbow-server"))
    from(jlinkRuntime) { into("runtime") }
    from(layout.buildDirectory.dir("install/server/lib")) { into("lib") }
    from("packaging/windows")
    // WinSW is not committed; drop WinSW.NET461.exe into packaging/winsw (see packaging/windows/README.txt).
    from("packaging/winsw") {
        include("WinSW*.exe")
        rename { "benbow-server-service.exe" }
    }
}
