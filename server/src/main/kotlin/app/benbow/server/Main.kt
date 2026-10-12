package app.benbow.server

import app.benbow.core.api.Protocol
import app.benbow.core.api.ServerInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

const val SERVER_VERSION = "0.1.0"
const val DEFAULT_PORT = 8765

fun main() {
    embeddedServer(Netty, port = DEFAULT_PORT, module = Application::benbowModule).start(wait = true)
}

fun Application.benbowModule() {
    install(ContentNegotiation) { json() }
    routing {
        route("/api/v1") {
            get("/info") {
                call.respond(ServerInfo(SERVER_VERSION, Protocol.MIN, Protocol.MAX))
            }
        }
    }
}
