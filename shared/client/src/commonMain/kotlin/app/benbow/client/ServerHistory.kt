package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.LinkPreviewInfo
import app.benbow.core.api.PurgeDone
import app.benbow.core.api.PurgePreview
import app.benbow.core.api.PurgeRequest
import app.benbow.core.api.RevisionInfo
import app.benbow.core.api.StorageReport
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Revision history lives on the server only (the local DB keeps heads); it needs a connection. */
class ServerHistory(private val db: BenbowDb, private val apiFor: (Connection) -> BenbowApi) {
    /** Newest first. */
    suspend fun revisions(entryId: String): List<RevisionInfo> = api().revisions(entryId).sortedByDescending { it.createdAt }

    suspend fun body(revisionId: String): String = api().revision(revisionId).body

    private fun api() = apiFor(Settings(db).connection ?: throw PairingException("Not paired with a server"))
}

/** Pages read on the server for link cards (ADR-038): the web client's way, since its page reaches nothing else. */
class ServerPages(private val db: BenbowDb, private val apiFor: (Connection) -> BenbowApi) {
    suspend fun preview(url: String): LinkPreviewInfo =
        apiFor(Settings(db).connection ?: throw PairingException("Not paired with a server")).linkPreview(url)
}

/**
 * «Место» (ADR-041): the server's count of its files, and the last one this device got, so the screen has its numbers
 * while the server is away (kept with the store's id: another server's count is never shown as this one's).
 */
class ServerStorage(private val db: BenbowDb, private val apiFor: (Connection) -> BenbowApi) {
    @Serializable
    private class Kept(val store: String, val report: StorageReport)

    /** The last count this device got from the server it is paired with, or null. */
    fun last(): StorageReport? {
        val store = Settings(db).connection?.storeId ?: return null
        val kept = Settings(db).get(KEY)?.let { runCatching { ClientJson.decodeFromString(Kept.serializer(), it) }.getOrNull() }
        return kept?.takeIf { it.store == store }?.report
    }

    /** A fresh count from the server, kept for the next time. */
    suspend fun fetch(): StorageReport {
        val connection = Settings(db).connection ?: throw PairingException("Not paired with a server")
        val report = apiFor(connection).storage()
        Settings(db).put(KEY, ClientJson.encodeToString(Kept.serializer(), Kept(connection.storeId, report)))
        return report
    }

    private companion object {
        const val KEY = "storage.report"
    }
}

/** Deleting for good from the trash (ADR-041): only the server does it, and only online. */
class ServerPurges(private val db: BenbowDb, private val apiFor: (Connection) -> BenbowApi) {
    suspend fun preview(request: PurgeRequest): PurgePreview = api().purgePreview(request)

    suspend fun commit(planId: String): PurgeDone = api().purgeCommit(planId)

    private fun api() = apiFor(Settings(db).connection ?: throw PairingException("Not paired with a server"))
}

/** One HTTP client per connection: each client owns threads and a connection pool, so they are not created per call. */
@OptIn(ExperimentalAtomicApi::class)
class ApiCache(private val create: (Connection) -> BenbowApi = { apiFor(it) }) : (Connection) -> BenbowApi {
    private val current = AtomicReference<Pair<Connection, BenbowApi>?>(null)

    override fun invoke(connection: Connection): BenbowApi {
        current.load()?.let { (cached, api) -> if (cached == connection) return api }
        val api = create(connection)
        (current.exchange(connection to api)?.second as? AutoCloseable)?.close()
        return api
    }
}
