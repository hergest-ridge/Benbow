package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.Sha256
import app.benbow.core.api.FontFileInfo
import app.benbow.core.api.FontInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Font files on this device by SHA-256 (ADR-052): a folder on Android and desktop, IndexedDB in the browser. */
interface FontShelf {
    suspend fun read(sha256: String): LocalFile?

    suspend fun keep(sha256: String, bytes: ByteArray): LocalFile
}

/**
 * Fonts from the paired server (ADR-052, 7.20 L4-L5): its catalog, kept with the store's id so another server's is
 * never shown as this one's, and the files of the fonts notes use, fetched the first time one is drawn, checked by
 * SHA-256 and kept on this device, so a note keeps its font offline. Without a [shelf] (tests) no file is fetched.
 */
class ServerFonts(private val db: BenbowDb, private val apiFor: (Connection) -> BenbowApi, private val shelf: FontShelf?) {
    @Serializable
    private class Kept(val store: String, val fonts: List<FontInfo>)

    private val fonts = MutableStateFlow(kept())
    private val fetching = Mutex()

    /** The server's fonts as last read; empty before the first read and for a server without fonts. */
    val catalog: StateFlow<List<FontInfo>> = fonts.asStateFlow()

    /** A fresh catalog from the server; one from before fonts (404) has none. */
    suspend fun refresh() {
        val connection = Settings(db).connection ?: return
        val list = try {
            apiFor(connection).fonts().fonts
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            emptyList()
        }
        Settings(db).put(KEY, ClientJson.encodeToString(Kept.serializer(), Kept(connection.storeId, list)))
        fonts.value = list
    }

    /** [file] on this device: kept already, or fetched, checked and kept; null while it can't be had (offline, gone). */
    suspend fun file(file: FontFileInfo): LocalFile? {
        val shelf = shelf ?: return null
        shelf.read(file.sha256)?.let { return it }
        return fetching.withLock {
            shelf.read(file.sha256) ?: run {
                val connection = Settings(db).connection ?: return null
                val bytes = try {
                    apiFor(connection).downloadFont(file.sha256)
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    return null
                }
                if (Sha256.hex(bytes) != file.sha256) return null
                shelf.keep(file.sha256, bytes)
            }
        }
    }

    private fun kept(): List<FontInfo> {
        val store = Settings(db).connection?.storeId ?: return emptyList()
        val kept = Settings(db).get(KEY)?.let { runCatching { ClientJson.decodeFromString(Kept.serializer(), it) }.getOrNull() }
        return kept?.takeIf { it.store == store }?.fonts.orEmpty()
    }

    private companion object {
        const val KEY = "fonts.catalog"
    }
}
