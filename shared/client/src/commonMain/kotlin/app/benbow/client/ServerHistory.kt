package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.LinkPreviewInfo
import app.benbow.core.api.RevisionInfo
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
