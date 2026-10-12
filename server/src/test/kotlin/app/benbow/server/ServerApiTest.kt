package app.benbow.server

import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.Protocol
import app.benbow.core.api.PushRequest
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ServerInfo
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.OpStatus
import app.benbow.core.sync.PullResponse
import app.benbow.core.sync.PutRevision
import app.benbow.core.sync.RevisionRecord
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.RevisionInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerApiTest {
    private val body = """{"v":1,"blocks":[
{"_key":"k1","_type":"block","style":"normal","children":[{"_type":"span","text":"Утро","marks":[]}],"markDefs":[]}
]}"""

    private fun withServer(loopback: Boolean = true, block: suspend ApplicationTestBuilder.(ServerContext, HttpClient) -> Unit) {
        val db = Database.inMemory()
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", null, 8765, isLoopback = { loopback })
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ContentNegotiation) { json() } }
            block(ctx, client)
        }
        db.close()
    }

    private suspend fun HttpClient.pair(ctx: ServerContext): String {
        val code = ctx.pairing.issue().first
        return post("/api/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest(code, "S24+", "android"))
        }.body<PairResponse>().token
    }

    @Test
    fun infoReportsStoreIdentity() = withServer { ctx, client ->
        val info = client.get("/api/v1/info").body<ServerInfo>()
        assertEquals(ctx.db.storeId, info.storeId)
        assertEquals(2, info.schemaVersion)
    }

    @Test
    fun syncNeedsAPairedDevice() = withServer { ctx, client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/sync/pull").status)
        val wrong = client.post("/api/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest("000000", "x", "android"))
        }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)

        val token = client.pair(ctx)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/sync/pull") { bearerAuth(token) }.status)

        val device = ctx.devices.list().single()
        ctx.devices.revoke(device.id)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/sync/pull") { bearerAuth(token) }.status)
    }

    @Test
    fun pairingCodeIsSingleUseAndBurnsAfterGuesses() {
        val codes = PairingCodes()
        val code = codes.issue().first
        assertTrue(codes.consume(code))
        assertFalse(codes.consume(code))
        val next = codes.issue().first
        repeat(5) { codes.consume("wrong") }
        assertFalse(codes.consume(next), "five wrong guesses burn the code")
    }

    @Test
    fun pushPullAndHistoryRoundTrip() = withServer { ctx, client ->
        val token = client.pair(ctx)
        val ops = listOf(
            CreateEntry("op1", "e1", "journal", "2026-10-13", 1),
            PutRevision("op2", "e1", "r1", emptyList(), body),
        )
        suspend fun push() = client.post("/api/v1/sync/push") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(Protocol.MAX, ops))
        }.body<PushResponse>()
        assertEquals(listOf(OpStatus.Applied, OpStatus.Applied), push().results.map { it.status })
        assertEquals(listOf(OpStatus.Duplicate, OpStatus.Duplicate), push().results.map { it.status }, "retries are no-ops")

        val pull = client.get("/api/v1/sync/pull?cursor=0") { bearerAuth(token) }.body<PullResponse>()
        assertEquals("r1", pull.changes.single().entry.head)
        assertEquals(body, pull.changes.single().headBody)

        val history = client.get("/api/v1/entries/e1/revisions") { bearerAuth(token) }.body<List<RevisionInfo>>()
        assertEquals(listOf("r1"), history.map { it.id })
        assertEquals(body, client.get("/api/v1/revisions/r1") { bearerAuth(token) }.body<RevisionRecord>().body)
    }

    @Test
    fun incompatibleProtocolIsRefused() = withServer { ctx, client ->
        val token = client.pair(ctx)
        val response = client.post("/api/v1/sync/push") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(Protocol.MAX + 1, emptyList()))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun adminIsOnlyForTheServerPc() {
        withServer(loopback = false) { ctx, client ->
            assertEquals(HttpStatusCode.Forbidden, client.post("/admin/pairing-code").status)
            ctx.pairing.issue()
            assertFalse(ctx.pairing.active()!!.first in client.get("/").bodyAsText(), "the code is never shown to the LAN")
        }
        withServer(loopback = true) { ctx, client ->
            val forged = client.post("/admin/pairing-code") { header("Origin", "https://evil.example") }
            assertEquals(HttpStatusCode.Forbidden, forged.status, "cross-site POSTs are refused")
            client.post("/admin/pairing-code") { header("Origin", "https://localhost:8765") }
            val code = ctx.pairing.active()!!.first
            assertTrue(code in client.get("/").bodyAsText())
        }
    }
}
