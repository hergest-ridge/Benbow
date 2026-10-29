package app.benbow.core.api

import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/friends/feed` («Лента друзей (проба)», ADR-056): the friends' link's test notes both ways, shown as posts
 * by the web client. A prototype to be rolled back once real posts exist; nothing here touches the journal.
 */
@Serializable
data class FriendsFeed(val friends: List<FriendInfo>, val posts: List<FriendPost>)

/** A friend's server as this one knows it: [server] is the Benbow version its letters last named. */
@Serializable
data class FriendInfo(val id: String, val name: String, val server: String? = null)

/**
 * A post, newest first in [FriendsFeed.posts]. [from] is a friend's id, or null for this server's owner; for one's own,
 * [to] tells for each friend how far it got: [WAITING], [SENT] (in the mailbox) or [DELIVERED].
 */
@Serializable
data class FriendPost(val id: String, val from: String?, val text: String, val at: Long, val to: Map<String, String> = emptyMap()) {
    companion object {
        const val WAITING = "waiting"
        const val SENT = "sent"
        const val DELIVERED = "delivered"
    }
}

/** `POST /api/v1/friends/posts`: a post to every friend. */
@Serializable
data class NewFriendPost(val text: String)
