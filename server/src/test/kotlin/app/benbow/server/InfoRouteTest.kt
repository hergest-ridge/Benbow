package app.benbow.server

import app.benbow.core.api.Protocol
import app.benbow.core.api.ServerInfo
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InfoRouteTest {
    @Test
    fun infoReportsVersionProtocolAndStoreIdentity() {
        val dir = Files.createTempDirectory("benbow-test")
        Store.open(dir).use { store ->
            testApplication {
                application { benbowModule(ServerContext(store, "AA:BB", dir)) }
                val client = createClient { install(ContentNegotiation) { json() } }

                val response = client.get("/api/v1/info")

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(
                    ServerInfo(SERVER_VERSION, Protocol.MIN, Protocol.MAX, store.storeId, 1, Store.SCHEMA_VERSION),
                    response.body<ServerInfo>(),
                )
                assertEquals(HttpStatusCode.OK, client.get("/api/docs").status)
            }
        }
    }

    @Test
    fun storeIdentitySurvivesReopen() {
        val dir = Files.createTempDirectory("benbow-test")
        val first = Store.open(dir).use { it.storeId }
        assertEquals(first, Store.open(dir).use { it.storeId })
    }

    @Test
    fun refusesNewerSchema() {
        val dir = Files.createTempDirectory("benbow-test")
        Store.open(dir).close()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("db/benbow.sqlite")}").use {
            it.createStatement().execute("PRAGMA user_version = 99")
        }
        val error = assertFailsWith<IllegalStateException> { Store.open(dir) }
        assertTrue("newer" in error.message.orEmpty())
    }
}
