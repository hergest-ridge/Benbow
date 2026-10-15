import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.nio.ByteBuffer
import java.util.zip.Inflater

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
            // Color emoji served by our own server (OFL, docs/licenses/NotoColorEmoji-OFL.txt): without them Compose
            // fetches the glyphs from fonts.gstatic.com.
            implementation(npm("@fontsource/noto-color-emoji", "5.3.1"))
        }
    }
}

// Noto Color Emoji in Google's ten slices, as Google serves them to Chrome: COLRv1 glyphs only. Fontsource's files
// also carry an SVG table, which Skia picks and can't draw (the emoji come out blank), so it is left out here: each
// WOFF (zlib tables) becomes a plain TrueType file without it.
val emojiFonts = tasks.register("emojiFonts") {
    description = "Noto Color Emoji slices without their SVG table, for the web client."
    dependsOn(":kotlinWasmNpmInstall")
    val source = rootProject.layout.buildDirectory.dir("wasm/node_modules/@fontsource/noto-color-emoji/files")
    val output = layout.buildDirectory.dir("emoji-fonts")
    inputs.files(source.map { dir -> dir.asFileTree.matching { include("noto-color-emoji-?-400-normal.woff") } })
    outputs.dir(output)
    doLast {
        fun ttfWithoutSvg(woff: ByteArray): ByteArray {
            val input = ByteBuffer.wrap(woff)
            check(input.getInt(0) == 0x774F4646) { "not a WOFF file" }
            val flavor = input.getInt(4)
            val tables = (0 until input.getShort(12).toInt()).mapNotNull { i ->
                val at = 44 + 20 * i
                val tag = String(woff, at, 4, Charsets.US_ASCII)
                val offset = input.getInt(at + 4)
                val compressed = input.getInt(at + 8)
                val original = input.getInt(at + 12)
                val body = if (compressed < original) {
                    Inflater().run {
                        setInput(woff, offset, compressed)
                        ByteArray(original).also {
                            inflate(it)
                            end()
                        }
                    }
                } else {
                    woff.copyOfRange(offset, offset + original)
                }
                if (tag == "SVG ") null else tag to body
            }.sortedBy { it.first }
            val n = tables.size
            val log = 31 - Integer.numberOfLeadingZeros(n)
            val range = (1 shl log) * 16
            val size = 12 + 16 * n + tables.sumOf { (it.second.size + 3) / 4 * 4 }
            val out = ByteBuffer.allocate(size)
            out.putInt(flavor).putShort(n.toShort()).putShort(range.toShort()).putShort(log.toShort()).putShort((n * 16 - range).toShort())
            var data = 12 + 16 * n
            for ((tag, body) in tables) {
                val padded = body.copyOf((body.size + 3) / 4 * 4)
                val words = ByteBuffer.wrap(padded)
                var sum = 0L
                while (words.hasRemaining()) sum += words.getInt().toLong() and 0xFFFFFFFFL
                out.put(tag.toByteArray(Charsets.US_ASCII)).putInt(sum.toInt()).putInt(data).putInt(body.size)
                out.put(data, padded)
                data += padded.size
            }
            return out.array()
        }
        val dir = output.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        for (i in 0..9) {
            val woff = source.get().file("noto-color-emoji-$i-400-normal.woff").asFile.readBytes()
            dir.resolve("noto-color-emoji-$i.ttf").writeBytes(ttfWithoutSvg(woff))
        }
    }
}

// sql.js loads its WebAssembly file from beside the page's script.
tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(":kotlinWasmNpmInstall")
    from(rootProject.layout.buildDirectory.file("wasm/node_modules/sql.js/dist/sql-wasm-browser.wasm"))
    from(emojiFonts) { into("fonts") }
}
