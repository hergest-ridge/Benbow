package app.benbow.server

import app.benbow.core.api.Protocol
import app.benbow.core.api.ServerInfo
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class InfoRouteTest {
    @Test
    fun infoReportsVersionAndProtocolRange() = testApplication {
        application { benbowModule() }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/v1/info")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ServerInfo(SERVER_VERSION, Protocol.MIN, Protocol.MAX), response.body<ServerInfo>())
    }
}
