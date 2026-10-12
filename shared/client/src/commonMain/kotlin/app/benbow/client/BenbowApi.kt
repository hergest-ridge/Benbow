package app.benbow.client

import app.benbow.core.api.ApiError
import app.benbow.core.api.KnownEntry
import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.Protocol
import app.benbow.core.api.PushRequest
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ReconcileRequest
import app.benbow.core.api.ReconcileResponse
import app.benbow.core.api.RevisionInfo
import app.benbow.core.api.ServerInfo
import app.benbow.core.sync.PullResponse
import app.benbow.core.sync.RevisionRecord
import app.benbow.core.sync.SyncOp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/** The server API as the client sees it (docs/sync.md#implemented-endpoints). */
interface BenbowApi {
    suspend fun info(): ServerInfo
    suspend fun pair(code: String, deviceName: String, platform: String): PairResponse
    suspend fun push(ops: List<SyncOp>): PushResponse
    suspend fun pull(cursor: Long, limit: Int): PullResponse
    suspend fun revisions(entryId: String): List<RevisionInfo>
    suspend fun revision(id: String): RevisionRecord
    suspend fun reconcile(entries: List<KnownEntry>): ReconcileResponse
}

/** A non-2xx answer from the server; [error] carries the API error code when the body had one. */
class ApiException(val status: Int, val error: String?, message: String) : Exception(message)

/** Clients ignore unknown fields so a newer server within the same protocol doesn't break them. */
val ClientJson = Json { ignoreUnknownKeys = true }

class KtorBenbowApi(private val client: HttpClient, private val baseUrl: String, private val token: () -> String?) :
    BenbowApi,
    AutoCloseable {
    override fun close() = client.close()

    override suspend fun info(): ServerInfo = client.get("$baseUrl/api/v1/info").ok().body()

    override suspend fun pair(code: String, deviceName: String, platform: String): PairResponse = client.post("$baseUrl/api/v1/pair") {
        contentType(ContentType.Application.Json)
        setBody(PairRequest(code, deviceName, platform))
    }.ok().body()

    override suspend fun push(ops: List<SyncOp>): PushResponse = client.post("$baseUrl/api/v1/sync/push") {
        auth()
        contentType(ContentType.Application.Json)
        setBody(PushRequest(Protocol.MAX, ops))
    }.ok().body()

    override suspend fun pull(cursor: Long, limit: Int): PullResponse = client.get("$baseUrl/api/v1/sync/pull") {
        auth()
        parameter("cursor", cursor)
        parameter("limit", limit)
    }.ok().body()

    override suspend fun revisions(entryId: String): List<RevisionInfo> =
        client.get("$baseUrl/api/v1/entries/$entryId/revisions") { auth() }.ok().body()

    override suspend fun revision(id: String): RevisionRecord = client.get("$baseUrl/api/v1/revisions/$id") { auth() }.ok().body()

    override suspend fun reconcile(entries: List<KnownEntry>): ReconcileResponse = client.post("$baseUrl/api/v1/sync/reconcile") {
        auth()
        contentType(ContentType.Application.Json)
        setBody(ReconcileRequest(entries))
    }.ok().body()

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        token()?.let { bearerAuth(it) }
    }

    private suspend fun HttpResponse.ok(): HttpResponse {
        if (status.isSuccess()) return this
        val text = bodyAsText()
        val error = runCatching { ClientJson.decodeFromString(ApiError.serializer(), text) }.getOrNull()
        throw ApiException(status.value, error?.error, error?.message ?: "HTTP ${status.value}")
    }
}
