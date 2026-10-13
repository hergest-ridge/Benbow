package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.ServerInfo
import io.ktor.client.HttpClient

/** Result of the first contact: what to show the user before pairing. */
data class Probe(val baseUrl: String, val info: ServerInfo, val fingerprint: String)

class PairingException(message: String) : Exception(message)

/**
 * Pairing in two steps (docs/sync.md#pairing-and-security): [probe] reads the server's certificate
 * fingerprint for the user to compare with the admin page on the server PC; [pair] then talks only
 * to that certificate and stores the connection.
 */
class Pairing(private val db: BenbowDb, private val httpClient: (ServerTrust) -> HttpClient = ::createHttpClient) {
    private val settings = Settings(db)

    suspend fun probe(host: String, port: Int): Probe {
        var fingerprint: String? = null
        val baseUrl = "https://${host.trim()}:$port"
        val info = httpClient(ServerTrust.FirstContact { fingerprint = it }).use { KtorBenbowApi(it, baseUrl) { null }.info() }
        return Probe(baseUrl, info, fingerprint ?: throw PairingException("The server presented no certificate"))
    }

    suspend fun pair(probe: Probe, code: String, deviceName: String, platform: String): Connection {
        val previous = settings.connection
        if (previous != null && previous.storeId != probe.info.storeId) {
            // Never mix two stores in one local cache (data-safety S11).
            throw PairingException("This device is paired with another store (${previous.storeId}); reset it first")
        }
        val response = httpClient(ServerTrust.Pinned(probe.fingerprint)).use {
            KtorBenbowApi(it, probe.baseUrl) { null }.pair(code, deviceName, platform)
        }
        val connection = Connection(probe.baseUrl, probe.fingerprint, response.token, response.storeId, response.epoch, response.deviceId)
        settings.connection = connection
        return connection
    }

    /** Unpairs and clears the local cache. Refused while anything local is unsynced (outbox, drafts). */
    fun forget() {
        db.transaction {
            val q = db.benbowQueries
            if (q.pendingCount().executeAsOne() + q.rejectedCount().executeAsOne() + q.draftCount().executeAsOne() > 0) {
                throw PairingException("Some changes haven't reached the server yet")
            }
            settings.connection = null
            q.deleteAllEntries()
            q.clearIndex()
        }
    }
}

/** API client for a stored connection (pinned to its certificate). */
fun apiFor(connection: Connection, httpClient: (ServerTrust) -> HttpClient = ::createHttpClient): BenbowApi =
    KtorBenbowApi(httpClient(ServerTrust.Pinned(connection.fingerprint)), connection.baseUrl) { connection.token }
