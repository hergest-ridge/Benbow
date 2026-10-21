package app.benbow.client

import app.benbow.core.api.AndroidApp
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The app's update from the paired server (ADR-042, as built 7.19 U2), in [dir]: downloaded in pieces that go on after a
 * cut or a restart, then checked against the size and SHA-256 the server's `info` gave. A file that doesn't match is
 * deleted, never handed on. Earlier versions' files go when a new one is fetched. Whether it is this app, signed by the
 * same key and newer, is the installer's check (Android's package manager).
 */
class AppDownload(private val dir: File, private val api: () -> BenbowApi) {
    /** The checked APK of [app], fetched now or before; [progress] hears bytes done of the whole. */
    suspend fun fetch(app: AndroidApp, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "benbow-${app.versionCode}.apk")
        dir.listFiles().orEmpty().filter { it.name != target.name && it.name != "${target.name}.part" }.forEach { it.delete() }
        if (target.length() == app.size && sha256(target) == app.sha256) return@withContext target
        target.delete()
        val part = File(dir, "${target.name}.part")
        if (part.length() > app.size) part.delete()
        var stalled = 0
        while (part.length() < app.size) {
            val had = part.length()
            api().downloadApp(had) { channel, resumed -> FileOutputStream(part, resumed).use { channel.copyTo(it) } }
            progress(part.length(), app.size)
            if (part.length() <= had && ++stalled >= MAX_STALLS) error("the download doesn't move")
        }
        if (part.length() != app.size || sha256(part) != app.sha256) {
            part.delete()
            error("the downloaded app doesn't match what the server said of it")
        }
        check(part.renameTo(target)) { "can't place the downloaded app" }
        target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_STALLS = 3
    }
}
