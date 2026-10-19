package app.benbow.server.api

import app.benbow.core.api.ApiError
import app.benbow.core.api.LinkPreviewRequest
import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.Protocol
import app.benbow.core.api.PushRequest
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ReconcileRequest
import app.benbow.core.api.ServerInfo
import app.benbow.core.api.SpeechModelInfo
import app.benbow.core.api.SpeechModelSpec
import app.benbow.core.api.UploadStart
import app.benbow.core.api.UploadStatus
import app.benbow.core.calendar.CalendarInfo
import app.benbow.core.calendar.CalendarProtocol
import app.benbow.core.calendar.CalendarPushRequest
import app.benbow.core.calendar.CalendarRules
import app.benbow.core.sync.SyncRules
import app.benbow.core.text.Links
import app.benbow.server.SERVER_VERSION
import app.benbow.server.SpeechModel
import app.benbow.server.backup.BackupStatus
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.calendar.SqliteCalendarStore
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.HighWater
import app.benbow.server.storage.Migrations
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.SqliteSyncStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.nio.file.Path

class ServerContext(
    val db: Database,
    val devices: Devices,
    val pairing: PairingCodes,
    val tlsFingerprint: String,
    val dataDir: Path?,
    val port: Int,
    val backupStatus: () -> BackupStatus? = { null },
    /** Takes a verified snapshot now (skipped when nothing changed since the last one). */
    val backupNow: () -> Unit = {},
    /** Admin actions and pairing codes are only for requests from the server PC itself. */
    val isLoopback: (ApplicationCall) -> Boolean = ::fromLoopback,
    /** Updated after every push so a database copied back by hand is noticed at the next start. */
    val highWater: HighWater? = null,
    /** Media files; null in tests that don't need them. */
    val blobs: BlobStore? = null,
    /** The speech-recognition model phones fetch from here (7.6); null in tests that don't need it. */
    val speech: SpeechModel? = null,
    /** The calendar store (ADR-027); null when it didn't open: the journal works without it. */
    val calendar: CalendarDatabase? = null,
    /** The calendar's own high-water mark, updated after every calendar push. */
    val calendarHighWater: HighWater? = null,
    /** The web client's files (ADR-029); null when this package has none. */
    val webRoot: Path? = null,
)

data class DevicePrincipal(val deviceId: String)

private const val MAX_OPS_PER_PUSH = 500
private const val MAX_PULL_LIMIT = 500
private const val MAX_RECONCILE = 2_000
private val NO_CALENDAR = ApiError("no_calendar", "The calendar store is off on this server; see its log")

// KDoc on routes feeds the generated OpenAPI summaries, so ktlint's "no KDoc in blocks" rule is off here.
@Suppress("ktlint:standard:kdoc")
fun Application.benbowModule(ctx: ServerContext) {
    install(ContentNegotiation) { json() }
    install(PartialContent) // Range requests: video seeking, resumed downloads
    install(AutoHeadResponse) // HEAD /blobs/{sha256}: does the server have it?
    install(Authentication) {
        bearer("device") {
            authenticate { credential -> ctx.devices.authenticate(credential.token)?.let { DevicePrincipal(it.id) } }
        }
    }
    val linkPreviews = LinkPreviews(ctx.blobs)
    routing {
        adminRoutes(ctx)
        webApp(ctx.webRoot)
        route("/api/v1") {
            /**
             * Server version, supported sync protocol range and store identity.
             * Clients must not sync when the store id differs from the paired one (docs/sync.md#store-identity).
             */
            get("/info") {
                call.respond(
                    ServerInfo(
                        SERVER_VERSION,
                        Protocol.MIN,
                        Protocol.MAX,
                        ctx.db.storeId,
                        ctx.db.epoch,
                        Migrations.SCHEMA_VERSION,
                        ctx.db.latestCursor(),
                        ctx.calendar?.let { CalendarInfo(CalendarProtocol.VERSION, it.epoch, it.latestCursor()) },
                    ),
                )
            }

            /**
             * Exchanges the one-time code shown on the server PC (https://localhost:8765) for a device token.
             */
            post("/pair") {
                val request = call.receive<PairRequest>()
                if (!ctx.pairing.consume(request.code)) {
                    call.respond(HttpStatusCode.Forbidden, ApiError("pairing_failed", "Wrong or expired pairing code"))
                    return@post
                }
                val (device, token) = ctx.devices.pair(request.deviceName, request.platform)
                call.respond(PairResponse(device.id, token, ctx.db.storeId, ctx.db.epoch))
            }

            authenticate("device") {
                /**
                 * Applies the device's pending ops in order, each in its own transaction; repeats are no-ops.
                 */
                post("/sync/push") {
                    val request = call.receive<PushRequest>()
                    if (request.protocol !in Protocol.MIN..Protocol.MAX) {
                        call.respond(HttpStatusCode.Conflict, ApiError("protocol", "Server speaks ${Protocol.MIN}..${Protocol.MAX}"))
                        return@post
                    }
                    if (request.ops.size > MAX_OPS_PER_PUSH) {
                        call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too_many_ops", "At most $MAX_OPS_PER_PUSH ops per push"))
                        return@post
                    }
                    val device = call.principal<DevicePrincipal>()!!.deviceId
                    val results = SyncRules(SqliteSyncStore(ctx.db, device)).push(request.ops)
                    ctx.highWater?.record(ctx.db)
                    call.respond(PushResponse(results))
                }

                /**
                 * After a server restore: which of the device's entries and head revisions this store lacks.
                 * The device re-pushes them (docs/sync.md#store-identity).
                 */
                post("/sync/reconcile") {
                    val request = call.receive<ReconcileRequest>()
                    if (request.entries.size > MAX_RECONCILE) {
                        call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            ApiError("too_many_entries", "At most $MAX_RECONCILE entries per request"),
                        )
                        return@post
                    }
                    call.respond(SyncRules(SqliteSyncStore(ctx.db)).reconcile(request.entries))
                }

                /**
                 * Entries changed after the cursor, oldest first, with their head bodies.
                 */
                get("/sync/pull") {
                    val cursor = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, MAX_PULL_LIMIT)
                    call.respond(SyncRules(SqliteSyncStore(ctx.db)).pull(cursor, limit))
                }

                /**
                 * Applies the device's calendar ops in order, each in its own transaction; repeats are no-ops.
                 * The calendar is a store of its own beside the journal (ADR-027).
                 */
                post("/calendar/push") {
                    val calendar = ctx.calendar ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, NO_CALENDAR)
                    val request = call.receive<CalendarPushRequest>()
                    if (request.protocol != CalendarProtocol.VERSION) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("protocol", "Server speaks calendar protocol ${CalendarProtocol.VERSION}"),
                        )
                        return@post
                    }
                    if (request.ops.size > MAX_OPS_PER_PUSH) {
                        call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too_many_ops", "At most $MAX_OPS_PER_PUSH ops per push"))
                        return@post
                    }
                    val device = call.principal<DevicePrincipal>()!!.deviceId
                    val results = CalendarRules(SqliteCalendarStore(calendar, device)).push(request.ops)
                    ctx.calendarHighWater?.record(calendar)
                    call.respond(PushResponse(results))
                }

                /**
                 * Calendars and events changed after the cursor, oldest change first.
                 */
                get("/calendar/pull") {
                    val calendar = ctx.calendar ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, NO_CALENDAR)
                    val cursor = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, MAX_PULL_LIMIT)
                    call.respond(CalendarRules(SqliteCalendarStore(calendar)).pull(cursor, limit))
                }

                /**
                 * A media file by SHA-256 (supports Range). HEAD answers whether the server has it.
                 */
                get("/blobs/{sha256}") {
                    val store =
                        ctx.blobs
                            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ApiError("no_media", "Media storage is off"))
                    val blob = store.blob(call.parameters["sha256"]!!)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such file"))
                    call.response.header(HttpHeaders.ETag, "\"${blob.sha256}\"")
                    call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
                    call.respond(LocalPathContent(store.file(blob), ContentType.parse(blob.mime)))
                }

                /**
                 * Announces a file by hash and size. `complete` when the server already has it; otherwise an
                 * upload id and the offset to continue from (an open upload of the same file is resumed).
                 */
                post("/uploads") {
                    val store =
                        ctx.blobs
                            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("no_media", "Media storage is off"))
                    val request = call.receive<UploadStart>()
                    val device = call.principal<DevicePrincipal>()!!.deviceId
                    try {
                        when (
                            val start = store.start(
                                request.sha256,
                                request.size,
                                request.mime,
                                request.name,
                                request.capturedAt,
                                device,
                            )
                        ) {
                            is BlobStore.Start.Exists -> call.respond(UploadStatus(null, start.blob.size, complete = true))
                            is BlobStore.Start.Upload -> call.respond(UploadStatus(start.id, start.offset, complete = false))
                        }
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("bad_upload", e.message.orEmpty()))
                    } catch (e: BlobStore.InsufficientSpace) {
                        call.respond(HttpStatusCode.InsufficientStorage, ApiError("no_space", e.message.orEmpty()))
                    }
                }

                /**
                 * Appends a chunk (at most 16 MB) at `Upload-Offset`; a wrong offset answers 409 with the one to
                 * resume from. The last chunk is verified (size, SHA-256) and placed; a mismatch answers 422.
                 * A chunk that doesn't arrive whole (cut off, or silent for [CHUNK_IDLE]) is dropped: 400 / 408.
                 */
                patch("/uploads/{id}") {
                    val store =
                        ctx.blobs
                            ?: return@patch call.respond(HttpStatusCode.ServiceUnavailable, ApiError("no_media", "Media storage is off"))
                    val id = call.parameters["id"]!!
                    val offset = call.request.header("Upload-Offset")?.toLongOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("bad_upload", "Upload-Offset header missing"))
                    val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                    val bytes = when (val body = call.receiveChannel().readChunk(declared, BlobStore.MAX_CHUNK)) {
                        is ChunkBody.Complete -> body.bytes

                        ChunkBody.TooLarge -> return@patch call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            ApiError("too_large", "Chunk over 16 MB"),
                        )

                        ChunkBody.Incomplete -> return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("incomplete", "The chunk was cut off; send it again"),
                        )

                        ChunkBody.Stalled -> return@patch call.respond(
                            HttpStatusCode.RequestTimeout,
                            ApiError("stalled", "The chunk stopped arriving; send it again"),
                        )
                    }
                    val result = withContext(Dispatchers.IO) { store.append(id, offset, bytes) }
                    when (result) {
                        is BlobStore.Chunk.Accepted -> call.respond(UploadStatus(id, result.offset, complete = result.blob != null))

                        is BlobStore.Chunk.WrongOffset -> call.respond(
                            HttpStatusCode.Conflict,
                            UploadStatus(id, result.expected, complete = false),
                        )

                        BlobStore.Chunk.TooLarge -> call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            ApiError("too_large", "Chunk over 16 MB or past the file size"),
                        )

                        BlobStore.Chunk.Corrupt -> call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ApiError("checksum", "The file doesn't match its hash; upload it again"),
                        )

                        BlobStore.Chunk.NotFound -> call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such upload"))
                    }
                }

                /**
                 * Reads a page for a link card (ADR-038): the web client's page reaches nothing but this server. Public
                 * addresses only; the page's picture is kept as a JPEG blob named in the answer.
                 */
                post("/links/preview") {
                    val request = call.receive<LinkPreviewRequest>()
                    val href = Links.parse(request.url)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("bad_url", "Not a web address"))
                    val device = call.principal<DevicePrincipal>()!!.deviceId
                    val info = linkPreviews.read(href, device)
                        ?: return@post call.respond(HttpStatusCode.BadGateway, ApiError("no_page", "The page couldn't be read"))
                    call.respond(info)
                }

                /**
                 * Whether this server has the speech-recognition model, and its pinned files. Phones check every file
                 * against their own copy of these hashes (docs/sync.md#speech-model).
                 */
                get("/models/speech") {
                    val ready = ctx.speech?.state == SpeechModel.State.Ready
                    call.respond(SpeechModelInfo(SpeechModelSpec.ID, ready, SpeechModelSpec.files))
                }

                /**
                 * One file of the speech model (supports Range, so a phone resumes an interrupted download).
                 */
                get("/models/speech/{name}") {
                    val path = ctx.speech?.file(call.parameters["name"]!!)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such model file here"))
                    call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
                    call.respond(LocalPathContent(path, ContentType.Application.OctetStream))
                }

                /**
                 * Revision history of an entry, newest first, without bodies.
                 */
                get("/entries/{id}/revisions") {
                    call.respond(SqliteSyncStore(ctx.db).revisionsOf(call.parameters["id"]!!))
                }

                /**
                 * One revision with its body (for history view and restore).
                 */
                get("/revisions/{id}") {
                    val revision = SqliteSyncStore(ctx.db).revision(call.parameters["id"]!!)
                    if (revision ==
                        null
                    ) {
                        call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such revision"))
                    } else {
                        call.respond(revision)
                    }
                }
            }
        }
        swaggerUI("/api/docs") {
            info = OpenApiInfo("Benbow API", SERVER_VERSION)
            source = OpenApiDocSource.Routing(contentType = ContentType.Application.Json)
        }
    }
}

private fun fromLoopback(call: ApplicationCall): Boolean =
    runCatching { InetAddress.getByName(call.request.local.remoteAddress).isLoopbackAddress }.getOrDefault(false)
