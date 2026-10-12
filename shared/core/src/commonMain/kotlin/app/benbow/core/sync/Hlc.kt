package app.benbow.core.sync

import kotlinx.serialization.Serializable

/** Hybrid logical clock timestamp: orders metadata writes across devices despite clock drift (docs/sync.md). */
@Serializable
data class Hlc(val wall: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int = compareValuesBy(this, other, Hlc::wall, Hlc::counter, Hlc::node)

    companion object {
        val ZERO = Hlc(0, 0, "")
    }
}

class HlcClock(private val node: String, private val now: () -> Long) {
    private var last = Hlc(0, 0, node)

    /** Timestamp for a local event. */
    fun next(): Hlc {
        val wall = maxOf(now(), last.wall)
        last = if (wall == last.wall) last.copy(counter = last.counter + 1) else Hlc(wall, 0, node)
        return last
    }

    /** Merges a timestamp seen from another device, so later local events sort after it. */
    fun observe(remote: Hlc) {
        val wall = maxOf(now(), last.wall, remote.wall)
        val counter = when {
            wall == last.wall && wall == remote.wall -> maxOf(last.counter, remote.counter) + 1
            wall == last.wall -> last.counter + 1
            wall == remote.wall -> remote.counter + 1
            else -> 0
        }
        last = Hlc(wall, counter, node)
    }
}
