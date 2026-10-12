package app.benbow.server

import app.benbow.core.api.Protocol
import app.benbow.core.api.ServerInfo
import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.nio.file.Path

class ServerContext(val store: Store, val tlsFingerprint: String, val dataDir: Path)

// KDoc on routes feeds the generated OpenAPI summaries, so ktlint's "no KDoc in blocks" rule is off here.
@Suppress("ktlint:standard:kdoc")
fun Application.benbowModule(context: ServerContext) {
    install(ContentNegotiation) { json() }
    routing {
        get("/") { call.respondText(statusPage(context), ContentType.Text.Html) }
        route("/api/v1") {
            /**
             * Server version, supported sync protocol range and store identity.
             * Clients must not sync when the store id differs from the paired one (docs/sync.md#store-identity).
             */
            get("/info") {
                val store = context.store
                call.respond(ServerInfo(SERVER_VERSION, Protocol.MIN, Protocol.MAX, store.storeId, store.epoch, Store.SCHEMA_VERSION))
            }
        }
        swaggerUI("/api/docs") {
            info = OpenApiInfo("Benbow API", SERVER_VERSION)
            source = OpenApiDocSource.Routing(contentType = ContentType.Application.Json)
        }
    }
}

/** Placeholder for the admin page (Phase 2): enough to confirm the service is up. */
private fun statusPage(context: ServerContext): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
    return """
        <!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Benbow</title>
        <style>body{font-family:system-ui,sans-serif;max-width:40rem;margin:3rem auto;padding:0 1rem;background:#fbf6ee;color:#1f1b16}
        code{background:#f0e0cf;padding:.1rem .3rem;border-radius:.3rem;word-break:break-all}</style></head><body>
        <h1>⚓ Адмирал Бенбоу</h1>
        <p>Сервер работает. Версия <code>$SERVER_VERSION</code>.</p>
        <p>Данные: <code>${esc(context.dataDir.toString())}</code><br>
        Хранилище: <code>${context.store.storeId}</code>, эпоха <code>${context.store.epoch}</code></p>
        <p>Отпечаток сертификата (SHA-256):<br><code>${context.tlsFingerprint}</code></p>
        <p><a href="/api/docs">Документация API</a></p>
        </body></html>
    """.trimIndent()
}
