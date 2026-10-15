package app.benbow.server

import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ServerInfo
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.CalendarProtocol
import app.benbow.core.calendar.CalendarPullResponse
import app.benbow.core.calendar.CalendarPushRequest
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.OpStatus
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarApiTest {
    private fun withServer(calendar: Boolean = true, block: suspend ApplicationTestBuilder.(ServerContext, HttpClient) -> Unit) {
        val db = Database.inMemory()
        val store = if (calendar) CalendarDatabase.inMemory(db.storeId) else null
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", null, 8765, isLoopback = { true }, calendar = store)
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ContentNegotiation) { json() } }
            block(ctx, client)
        }
        store?.close()
        db.close()
    }

    private suspend fun HttpClient.pair(ctx: ServerContext): String = post("/api/v1/pair") {
        contentType(ContentType.Application.Json)
        setBody(PairRequest(ctx.pairing.issue().first, "S24+", "android"))
    }.body<PairResponse>().token

    private val op =
        CalendarOp("op-1", CalendarKind.EVENT, "e1", mapOf("title" to "Врач", "start" to "2026-10-14T09:30"), Hlc(1, 0, "phone"))

    @Test
    fun calendarOpsGoInAndComeBackOnTheirOwnChannel() = withServer { ctx, client ->
        val token = client.pair(ctx)
        assertEquals(1L, client.get("/api/v1/info").body<ServerInfo>().calendar?.epoch)
        val push = client.post("/api/v1/calendar/push") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CalendarPushRequest(CalendarProtocol.VERSION, listOf(op, op)))
        }.body<PushResponse>()
        assertEquals(listOf(OpStatus.Applied, OpStatus.Duplicate), push.results.map { it.status })
        val pull = client.get("/api/v1/calendar/pull?cursor=0") { bearerAuth(token) }.body<CalendarPullResponse>()
        assertEquals("Врач", pull.changes.single().fields.getValue("title").value)
        val info = client.get("/api/v1/info").body<ServerInfo>()
        assertEquals(pull.nextCursor, info.calendar?.cursor)
        assertEquals(0L, info.cursor, "the journal's change log saw nothing")
    }

    @Test
    fun anotherCalendarProtocolIsRefused() = withServer { ctx, client ->
        val token = client.pair(ctx)
        val response = client.post("/api/v1/calendar/push") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CalendarPushRequest(CalendarProtocol.VERSION + 1, listOf(op)))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun withoutACalendarStoreTheJournalStillAnswers() = withServer(calendar = false) { ctx, client ->
        val token = client.pair(ctx)
        assertEquals(null, client.get("/api/v1/info").body<ServerInfo>().calendar)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/calendar/pull") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/sync/pull") { bearerAuth(token) }.status)
    }

    @Test
    fun calendarNeedsAPairedDevice() = withServer { _, client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/calendar/pull").status)
    }
}
