package app.benbow.client

import app.benbow.core.api.ApiError
import app.benbow.core.api.KnownEntry
import app.benbow.core.api.LinkPreviewInfo
import app.benbow.core.api.LinkPreviewRequest
import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.Protocol
import app.benbow.core.api.PushRequest
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ReconcileRequest
import app.benbow.core.api.ReconcileResponse
import app.benbow.core.api.RevisionInfo
import app.benbow.core.api.ServerInfo
import app.benbow.core.api.SpeechModelInfo
import app.benbow.core.api.StorageReport
import app.benbow.core.api.UploadStart
import app.benbow.core.api.UploadStatus
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.CalendarProtocol
import app.benbow.core.calendar.CalendarPullResponse
import app.benbow.core.calendar.CalendarPushRequest
import app.benbow.core.sync.PullResponse
import app.benbow.core.sync.RevisionRecord
import app.benbow.core.sync.SyncOp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
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

    /** The calendar's own channel (ADR-027). */
    suspend fun calendarPush(ops: List<CalendarOp>): PushResponse

    suspend fun calendarPull(cursor: Long, limit: Int): CalendarPullResponse

    /** Announces a file; `complete` when the server already has it. */
    suspend fun startUpload(file: UploadStart): UploadStatus

    /** Appends [data] at [offset]. A wrong offset is not an error: the answer carries the offset to continue from. */
    suspend fun uploadChunk(id: String, offset: Long, data: ByteArray): UploadStatus

    /** Streams a file to [write]; returns its MIME type. */
    suspend fun downloadBlob(sha256: String, write: suspend (ByteReadChannel) -> Unit): String

    /** Whether the server has the speech-recognition model (7.6). */
    suspend fun speechModel(): SpeechModelInfo

    /** Reads a page for a link card on the server (ADR-038; the web client can't reach other hosts). */
    suspend fun linkPreview(url: String): LinkPreviewInfo = throw UnsupportedOperationException("linkPreview")

    /** What the server's files weigh, each file once (ADR-041, «Место»). */
    suspend fun storage(): StorageReport = throw UnsupportedOperationException("storage")

    /**
     * A speech model file from byte [from] on (a Range request); [write] learns whether the server continued there
     * (`resumed`) or sent it from the start.
     */
    suspend fun downloadSpeechFile(name: String, from: Long, write: suspend (channel: ByteReadChannel, resumed: Boolean) -> Unit)
}

/** The finished upload didn't match its hash (corrupted on the way); start it again. */
class ChecksumMismatchException : Exception("The uploaded file didn't match its SHA-256")

/** A non-2xx answer from the server; [error] carries the API error code when the body had one. */
class ApiException(val status: Int, val error: String?, message: String) : Exception(message)

/** Clients ignore unknown fields so a newer server within the same protocol doesn't break them. */
val ClientJson = Json { ignoreUnknownKeys = true }

/** One upload chunk, sending included: 8 MB needs at least ~220 kbit/s. */
internal const val CHUNK_REQUEST_TIMEOUT_MS = 5 * 60_000L

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

    override suspend fun linkPreview(url: String): LinkPreviewInfo = client.post("$baseUrl/api/v1/links/preview") {
        auth()
        contentType(ContentType.Application.Json)
        setBody(LinkPreviewRequest(url))
    }.ok().body()

    override suspend fun storage(): StorageReport = client.get("$baseUrl/api/v1/storage") { auth() }.ok().body()

    override suspend fun revisions(entryId: String): List<RevisionInfo> =
        client.get("$baseUrl/api/v1/entries/$entryId/revisions") { auth() }.ok().body()

    override suspend fun revision(id: String): RevisionRecord = client.get("$baseUrl/api/v1/revisions/$id") { auth() }.ok().body()

    override suspend fun reconcile(entries: List<KnownEntry>): ReconcileResponse = client.post("$baseUrl/api/v1/sync/reconcile") {
        auth()
        contentType(ContentType.Application.Json)
        setBody(ReconcileRequest(entries))
    }.ok().body()

    override suspend fun calendarPush(ops: List<CalendarOp>): PushResponse = client.post("$baseUrl/api/v1/calendar/push") {
        auth()
        contentType(ContentType.Application.Json)
        setBody(CalendarPushRequest(CalendarProtocol.VERSION, ops))
    }.ok().body()

    override suspend fun calendarPull(cursor: Long, limit: Int): CalendarPullResponse = client.get("$baseUrl/api/v1/calendar/pull") {
        auth()
        parameter("cursor", cursor)
        parameter("limit", limit)
    }.ok().body()

    override suspend fun startUpload(file: UploadStart): UploadStatus = client.post("$baseUrl/api/v1/uploads") {
        auth()
        contentType(ContentType.Application.Json)
        setBody(file)
    }.ok().body()

    override suspend fun uploadChunk(id: String, offset: Long, data: ByteArray): UploadStatus {
        val response = client.patch("$baseUrl/api/v1/uploads/$id") {
            auth()
            // 8 MB over weak Wi-Fi takes longer than the usual 30 s; a dead connection is caught by the socket timeout.
            timeout { requestTimeoutMillis = CHUNK_REQUEST_TIMEOUT_MS }
            header("Upload-Offset", offset)
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }
        return when (response.status) {
            HttpStatusCode.Conflict -> response.body()
            HttpStatusCode.UnprocessableEntity -> throw ChecksumMismatchException()
            else -> response.ok().body()
        }
    }

    override suspend fun downloadBlob(sha256: String, write: suspend (ByteReadChannel) -> Unit): String =
        client.prepareGet("$baseUrl/api/v1/blobs/$sha256") { auth() }.execute { response ->
            response.ok()
            write(response.bodyAsChannel())
            response.contentType()?.withoutParameters()?.toString() ?: "application/octet-stream"
        }

    override suspend fun speechModel(): SpeechModelInfo = client.get("$baseUrl/api/v1/models/speech") { auth() }.ok().body()

    override suspend fun downloadSpeechFile(name: String, from: Long, write: suspend (channel: ByteReadChannel, resumed: Boolean) -> Unit) {
        client.prepareGet("$baseUrl/api/v1/models/speech/$name") {
            auth()
            if (from > 0) header(HttpHeaders.Range, "bytes=$from-")
        }.execute { response ->
            response.ok()
            write(response.bodyAsChannel(), response.status == HttpStatusCode.PartialContent)
        }
    }

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
