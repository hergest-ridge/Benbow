package app.benbow.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * The browser's fetch (ADR-029). The browser itself checks the server's certificate (the owner accepts the
 * self-signed one once), so [trust] has nothing to pin here; the page talks to the server that served it.
 */
actual fun createHttpClient(trust: ServerTrust): HttpClient = HttpClient(Js) {
    install(ContentNegotiation) { json(ClientJson) }
    install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    expectSuccess = false
}
