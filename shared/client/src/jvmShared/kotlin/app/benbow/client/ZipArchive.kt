package app.benbow.client

import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The apps' archive: a ZIP stream; text is deflated, media stored as it is. */
class ZipArchiveWriter(private val zip: ZipOutputStream) : ArchiveWriter {
    override suspend fun text(path: String, text: String) {
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.encodeToByteArray())
        zip.closeEntry()
    }

    override suspend fun file(path: String, file: File) {
        zip.setLevel(Deflater.NO_COMPRESSION) // photos and videos are compressed already
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}

/** Writes the archive as a ZIP into [out] and closes it. */
suspend fun ArchiveExport.write(out: OutputStream, withMedia: Boolean, today: String, onProgress: (ExportProgress) -> Unit = {}): ExportReport =
    ZipOutputStream(out.buffered()).use { zip -> write(ZipArchiveWriter(zip), withMedia, today, onProgress) }
