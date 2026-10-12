package app.benbow.server.api

import app.benbow.core.api.ApiError
import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.Protocol
import app.benbow.core.api.PushRequest
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ReconcileRequest
import app.benbow.core.api.ServerInfo
import app.benbow.core.sync.SyncRules
import app.benbow.server.SERVER_VERSION
import app.benbow.server.backup.BackupStatus
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.HighWater
import app.benbow.server.storage.Migrations
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.SqliteSyncStore
import io.ktor.http.ContentType
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
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
)

data class DevicePrincipal(val deviceId: String)

private const val MAX_OPS_PER_PUSH = 500
private const val MAX_PULL_LIMIT = 500
private const val MAX_RECONCILE = 2_000

// KDoc on routes feeds the generated OpenAPI summaries, so ktlint's "no KDoc in blocks" rule is off here.
@Suppress("ktlint:standard:kdoc")
fun Application.benbowModule(ctx: ServerContext) {
    install(ContentNegotiation) { json() }
    install(Authentication) {
        bearer("device") {
            authenticate { credential -> ctx.devices.authenticate(credential.token)?.let { DevicePrincipal(it.id) } }
        }
    }
    routing {
        adminRoutes(ctx)
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
