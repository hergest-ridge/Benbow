package app.benbow.client

/** Fonts from the server in the browser (ADR-052): on the tab's IndexedDB shelf beside the media, `fonts/<sha256>`. */
class BrowserFontShelf(private val shelf: FileShelf) : FontShelf {
    override suspend fun read(sha256: String): LocalFile? = shelf.load(key(sha256))?.let { stored ->
        val bytes = stored.toByteArray()
        LocalFile(sha256, stored, bytes.size.toLong(), bytes)
    }

    override suspend fun keep(sha256: String, bytes: ByteArray): LocalFile {
        val stored = bytes.toUint8Array()
        shelf.store(key(sha256), stored)
        return LocalFile(sha256, stored, bytes.size.toLong(), bytes)
    }

    private fun key(sha256: String) = "fonts/$sha256"
}
