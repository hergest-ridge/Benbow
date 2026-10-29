package app.benbow.client

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Fonts from the server on Android and desktop (ADR-052): `<dir>/<sha256>.ttf`, written whole before it is named. */
class FolderFontShelf(private val dir: File) : FontShelf {
    override suspend fun read(sha256: String): LocalFile? = file(sha256).takeIf { it.isFile }

    override suspend fun keep(sha256: String, bytes: ByteArray): LocalFile {
        dir.mkdirs()
        val target = file(sha256)
        val tmp = File(dir, "$sha256.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    private fun file(sha256: String) = File(dir, "$sha256.ttf")
}
