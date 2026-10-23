import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
}

// The one version of Benbow (gradle.properties, ADR-042 U1) as SERVER_VERSION.
val benbowVersion = providers.gradleProperty("benbow.version")
val serverVersion = tasks.register("serverVersion") {
    val version = benbowVersion
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().asFile.resolve("app/benbow/server/Version.kt")
        file.parentFile.mkdirs()
        file.writeText(
            "package app.benbow.server\n\n/** From `benbow.version` in gradle.properties (ADR-042, U1). */\n" +
                "const val SERVER_VERSION = \"${version.get()}\"\n",
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(serverVersion) }

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
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.conditional.headers) // 304s for the web client's files
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.routing.openapi)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)
    implementation(libs.commons.compress) // unpacks the speech model archive (.tar.bz2)
    implementation(libs.tink) // sealed letters to friends' servers (ADR-039)
    implementation(libs.angus.mail) // the shared mailbox the letters travel through (IMAP)
    implementation(libs.zxing.core) // the QR code a phone scans to install the app (ADR-042, U4)
    implementation(libs.jagged.x25519) // age files for the copy outside the house (ADR-043)
    implementation(libs.jagged.scrypt) // key.age, the key sealed with the owner's passphrase

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.okhttp) // OkHttp itself: cutting a phone's connection mid-request (UploadCutOffTest)
    testImplementation(project(":shared:testing"))
    testImplementation(project(":shared:client"))
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.greenmail) // an IMAP server in the test process (PeerMailTest)
}

// ---- Packaging (docs/architecture.md#packaging-and-deployment-windows) ----
// The bundled runtime is Temurin 25 LTS (it ships jmods for jlink); foojay provisions it on first use.
val packagingJdk = javaToolchains.compilerFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}
// From `jdeps --print-module-deps` over the server classpath, plus jdk.charsets for Windows console code pages and
// java.net.http for the speech model download, java.security.sasl for IMAP logins (ADR-039). `packageServer` runs the
// packaged server's --self-check against it.
val runtimeModules =
    "java.base,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.security.sasl,java.sql," +
        "jdk.charsets,jdk.jfr,jdk.unsupported"
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

// The web client (ADR-029): its production build, with a gzip twin of every file for the server to send compressed.
val webApp = tasks.register<Sync>("webApp") {
    group = "distribution"
    description = "The web client for the server package (served at /app/), pre-compressed."
    dependsOn(":apps:web:wasmJsBrowserDistribution")
    val output = layout.buildDirectory.dir("web")
    from(rootProject.layout.projectDirectory.dir("apps/web/build/dist/wasmJs/productionExecutable"))
    into(output)
    doLast {
        output.get().asFile.walkTopDown().filter { it.isFile && it.extension != "gz" }.forEach { file ->
            val packed = object : GZIPOutputStream(file.resolveSibling(file.name + ".gz").outputStream()) {
                init {
                    def.setLevel(Deflater.BEST_COMPRESSION)
                }
            }
            packed.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }
}

// The Android app this server's phones update from (ADR-042, U1): the signed release APK and `release.json` saying
// what it is (from the build's own output-metadata.json, so it can't drift from the APK), for the package's `android`.
val androidRelease = tasks.register("androidRelease") {
    group = "distribution"
    description = "The signed Android app and its release.json for the server package."
    dependsOn(":apps:android:assembleRelease")
    val apks = rootProject.layout.projectDirectory.dir("apps/android/build/outputs/apk/release")
    val output = layout.buildDirectory.dir("android")
    inputs.files(apks.file("output-metadata.json"), apks.file("android-release.apk"))
    outputs.dir(output)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val metadata = JsonSlurper().parse(apks.file("output-metadata.json").asFile) as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val element = (metadata["elements"] as List<Map<String, Any>>).single()
        val apk = apks.asFile.resolve(element["outputFile"] as String)
        val name = "benbow-${element["versionName"]}.apk"
        val dir = output.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        apk.copyTo(dir.resolve(name))
        val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        val release = mapOf(
            "version_code" to element["versionCode"],
            "version_name" to element["versionName"],
            "package" to metadata["applicationId"],
            "file" to name,
            "sha256" to sha,
            "size" to apk.length(),
        )
        dir.resolve("release.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(release)))
    }
}

// The package says which version it is: setup.exe refuses to put an older one over it (ADR-042, I2).
val packageVersion = tasks.register("packageVersion") {
    val version = benbowVersion
    val output = layout.buildDirectory.dir("package-version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        output.get().asFile.apply { mkdirs() }.resolve("version.txt").writeText(version.get())
    }
}

// The admin page wears the app's icon: one source, the web client's favicon (itself the app_icon drawing).
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("apps/web/src/wasmJsMain/resources/favicon.svg")) {
        into("admin")
        rename { "icon.svg" }
    }
}

// `./gradlew :server:webApp` once, then `:server:run` serves it at /app/ too.
tasks.named<JavaExec>("run") {
    systemProperty("benbow.web", layout.buildDirectory.dir("web").get().asFile.absolutePath)
    systemProperty("benbow.android", layout.buildDirectory.dir("android").get().asFile.absolutePath)
}

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
    from(webApp) { into("web") }
    from(androidRelease) { into("android") }
    from(packageVersion)
    from("packaging/windows")
    // WinSW is not committed; drop WinSW.NET461.exe into packaging/winsw (see packaging/windows/README.txt).
    from("packaging/winsw") {
        include("WinSW*.exe")
        rename { "benbow-server-service.exe" }
    }
    // The bundled runtime only has the modules listed above: start the packaged server once in self-check mode, so a
    // missing one fails here and not as a service that won't start on the owner's PC.
    doLast {
        val dir = packageDir.get().asFile
        val java = dir.resolve(if (onWindows) "runtime/bin/java.exe" else "runtime/bin/java").absolutePath
        // Like the service, it keeps Java's loopback sockets out of %TEMP% (some Windows profiles refuse them there).
        val sockets = dir.resolveSibling("self-check-tmp").apply { mkdirs() }.absolutePath
        val process = ProcessBuilder(
            java,
            "-Djdk.net.unixdomain.tmpdir=$sockets",
            "-cp",
            dir.resolve("lib").absolutePath + "/*",
            "app.benbow.server.MainKt",
            "--self-check",
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0 && "self-check ok" in output) { "The packaged server failed its self-check:\n$output" }
    }
}

// One setup.exe in Russian over the package (ADR-042, I2), by Inno Setup 6's ISCC: where its installer puts it, for
// this user or for all, or `-Pinnosetup.iscc=<path>`. Not part of `check`: the gate needs no Inno Setup.
tasks.register<Exec>("installer") {
    group = "distribution"
    description = "benbow-server-setup-<version>.exe from the server package (needs Inno Setup 6)."
    dependsOn("packageServer")
    val script = layout.projectDirectory.file("packaging/installer/benbow-server.iss")
    val icon = layout.projectDirectory.file("packaging/installer/benbow.ico")
    val source = layout.buildDirectory.dir("package/benbow-server")
    val output = layout.buildDirectory.dir("installer")
    val version = benbowVersion
    val given = providers.gradleProperty("innosetup.iscc")
    val places = listOf("LOCALAPPDATA" to "Programs\\", "ProgramFiles(x86)" to "", "ProgramFiles" to "").map { (variable, under) ->
        providers.environmentVariable(variable).map { "$it\\${under}Inno Setup 6\\ISCC.exe" }
    }
    inputs.files(script, icon)
    inputs.dir(source)
    inputs.property("version", version)
    outputs.dir(output)
    doFirst {
        val found = given.orNull ?: places.mapNotNull { it.orNull }.firstOrNull { File(it).exists() }
        executable = checkNotNull(found) { "Inno Setup 6 is missing: install it (jrsoftware.org) or pass -Pinnosetup.iscc=<ISCC.exe>." }
        output.get().asFile.deleteRecursively()
    }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "/Q",
                "/DAppVersion=${version.get()}",
                "/DSourceDir=${source.get().asFile.absolutePath}",
                "/DOutputDir=${output.get().asFile.absolutePath}",
                script.asFile.absolutePath,
            )
        },
    )
}

// A release for the owner and for friends (ADR-044): `release/<version>/` at the repository's root, never in git:
// setup.exe, the package as a folder (for the scripts), the memo, and SHA256SUMS.txt for what gets sent.
tasks.register<Sync>("release") {
    group = "distribution"
    description = "release/<benbow.version>/: setup.exe, the server package folder, the memo and checksums."
    dependsOn("installer")
    val target = rootProject.layout.projectDirectory.dir("release").dir(benbowVersion)
    from(layout.buildDirectory.dir("installer"))
    from(layout.buildDirectory.dir("package")) { include("benbow-server/**") }
    from(layout.projectDirectory.dir("packaging/windows")) { include("Первые шаги.txt") }
    into(target)
    doLast {
        val dir = target.get().asFile
        val sums = dir.walkTopDown()
            .filter { it.isFile && ((it.extension == "exe" && it.parentFile == dir) || it.extension == "apk") }
            .sortedBy { it.relativeTo(dir).path }
            .joinToString("") { file ->
                val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                "$sha  ${file.relativeTo(dir).invariantSeparatorsPath}\n"
            }
        dir.resolve("SHA256SUMS.txt").writeText(sums)
    }
}
