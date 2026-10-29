package app.benbow.server.api

import app.benbow.core.api.ApiError
import app.benbow.core.api.FriendInfo
import app.benbow.core.api.FriendPost
import app.benbow.core.api.FriendsFeed
import app.benbow.core.api.NewFriendPost
import app.benbow.server.peer.PeerDatabase
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val NO_FRIENDS = ApiError("no_friends", "The link with friends' servers is off")

/**
 * «Лента друзей (проба)» (ADR-056): the friends' link's test notes as posts for the web client, sent to every friend
 * through the mailbox like the admin page's notes. A prototype, rolled back once posts are real.
 */
internal fun Route.friendsFeedRoutes(ctx: ServerContext) {
    get("/friends/feed") {
        val peers = ctx.peers ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, NO_FRIENDS)
        call.respond(withContext(Dispatchers.IO) { friendsFeed(peers.db) })
    }

    post("/friends/posts") {
        val peers = ctx.peers ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, NO_FRIENDS)
        val text = call.receive<NewFriendPost>().text.trim()
        if (text.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ApiError("empty", "The post is empty"))
        val friends = peers.db.contacts()
        if (friends.isEmpty()) return@post call.respond(HttpStatusCode.Conflict, ApiError("no_friends", "No friends yet"))
        withContext(Dispatchers.IO) { friends.forEach { peers.sendNote(it.id, text.take(MAX_POST)) } }
        call.respond(HttpStatusCode.NoContent)
    }
}

private const val MAX_POST = 2_000

/** One's own post went to every friend as a note of its own: the notes of one post are a few seconds apart at most. */
private const val SAME_POST_MS = 10_000L

/** The latest notes both ways as posts, newest first; one's own post to several friends is one post. */
internal fun friendsFeed(db: PeerDatabase, limit: Int = 100): FriendsFeed {
    val friends = db.contacts()
    val notes = friends.flatMap { friend -> db.notes(friend.id, limit).map { friend.id to it } }
    val theirs = notes.filter { !it.second.outgoing }.map { (friend, n) -> FriendPost("in-$friend-${n.seq}", friend, n.text, n.at) }
    val mine = mutableListOf<FriendPost>()
    for ((friend, n) in notes.filter { it.second.outgoing }.sortedBy { it.second.at }) {
        val state = when {
            n.ackedAt != null -> FriendPost.DELIVERED
            n.sentAt != null -> FriendPost.SENT
            else -> FriendPost.WAITING
        }
        val last = mine.lastOrNull()
        if (last != null && last.text == n.text && n.at - last.at < SAME_POST_MS && friend !in last.to) {
            mine[mine.lastIndex] = last.copy(to = last.to + (friend to state))
        } else {
            mine += FriendPost("out-$friend-${n.seq}", null, n.text, n.at, mapOf(friend to state))
        }
    }
    return FriendsFeed(
        friends.map { FriendInfo(it.id, it.name, db.seen(it.id)?.version) },
        (theirs + mine).sortedByDescending { it.at }.take(limit),
    )
}
