package app.benbow.server

import app.benbow.core.api.AndroidApp
import app.benbow.server.storage.BlobStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The Android app this package carries (ADR-042, as built 7.19 U1): `<package>/android/release.json` and the signed APK it
 * names, checked once at start (its size and SHA-256 against the release's). Phones update themselves from it; a package
 * without it, or with one that doesn't check, offers none.
 */
class AppRelease(val info: AndroidApp, val file: Path) {
    companion object {
        const val MANIFEST = "release.json"
        private val log = LoggerFactory.getLogger("benbow")

        fun load(dir: Path?): AppRelease? {
            val manifest = dir?.resolve(MANIFEST)?.takeIf { it.isRegularFile() } ?: return null
            return runCatching {
                val json = Json.parseToJsonElement(manifest.readText()).jsonObject
                fun text(key: String) = json.getValue(key).jsonPrimitive.content
                val info = AndroidApp(
                    versionCode = json.getValue("version_code").jsonPrimitive.int,
                    versionName = text("version_name"),
                    sha256 = text("sha256"),
                    size = json.getValue("size").jsonPrimitive.long,
                )
                val file = dir.resolve(text("file")).normalize()
                check(file.parent == dir.normalize() && file.isRegularFile()) { "no ${text("file")} beside $MANIFEST" }
                check(file.fileSize() == info.size && BlobStore.sha256(file) == info.sha256) { "${file.fileName} doesn't match $MANIFEST" }
                AppRelease(info, file)
            }.onFailure { log.error("The Android app in {} is not offered: {}", dir, it.message) }.getOrNull()
        }
    }
}
