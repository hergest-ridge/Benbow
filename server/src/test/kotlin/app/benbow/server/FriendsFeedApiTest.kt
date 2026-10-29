package app.benbow.server

import app.benbow.core.api.FriendPost
import app.benbow.core.api.FriendsFeed
import app.benbow.core.api.NewFriendPost
import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.peer.Letter
import app.benbow.server.peer.PeerDatabase
import app.benbow.server.peer.PeerIdentity
import app.benbow.server.peer.PeerLink
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
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Лента друзей (проба)» (ADR-056): the friends' test notes as posts for paired devices. */
class FriendsFeedApiTest {
    private fun withServer(friends: Boolean = true, block: suspend (PeerLink?, HttpClient, String) -> Unit) {
        val db = Database.inMemory()
        val peers = if (friends) {
            PeerLink(PeerDatabase.inMemory(), PeerIdentity.generate(), connect = {
                error("no network here")
            })
        } else {
            null
        }
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", null, 8765, isLoopback = { true }, peers = peers)
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ContentNegotiation) { json() } }
            val token = client.post("/api/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(ctx.pairing.issue().first, "Edge", "web"))
            }.body<PairResponse>().token
            block(peers, client, token)
        }
        peers?.close()
        db.close()
    }

    private suspend fun HttpClient.post(token: String, text: String) = post("/api/v1/friends/posts") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(NewFriendPost(text))
    }

    @Test
    fun aPostGoesToEveryFriendAndTheirsComeBackAsPosts() = withServer { peers, client, token ->
        val anna = PeerIdentity.generate().card
        val max = PeerIdentity.generate().card
        peers!!.db.addContact("Аня", anna, 0)
        peers.db.addContact("Макс", max, 0)
        assertEquals(HttpStatusCode.NoContent, client.post(token, "  Привет всем  ").status)
        peers.db.receive(anna.id, Letter("l-1", 1, Letter.NOTE, "И тебе привет", sentAt = System.currentTimeMillis() + 1_000), 0)

        val feed = client.get("/api/v1/friends/feed") { bearerAuth(token) }.body<FriendsFeed>()
        assertEquals(listOf("Аня", "Макс"), feed.friends.map { it.name })
        assertEquals(listOf("И тебе привет", "Привет всем"), feed.posts.map { it.text })
        assertEquals(anna.id, feed.posts[0].from)
        val mine = feed.posts[1]
        assertNull(mine.from)
        assertEquals(mapOf(anna.id to FriendPost.WAITING, max.id to FriendPost.WAITING), mine.to, "one post to both, not two")
    }

    @Test
    fun emptyPostsNoFriendsAndNoLinkAreRefused() {
        withServer { _, client, token ->
            assertEquals(HttpStatusCode.Conflict, client.post(token, "Некому").status)
            assertEquals(HttpStatusCode.BadRequest, client.post(token, "   ").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/friends/feed").status)
        }
        withServer(friends = false) { _, client, token ->
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/friends/feed") { bearerAuth(token) }.status)
        }
    }
}
