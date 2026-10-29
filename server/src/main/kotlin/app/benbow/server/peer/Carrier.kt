package app.benbow.server.peer

/** The two boxes every server has on a carrier (ADR-055): letters, small and many, and parts of files, large. */
enum class Box { LETTERS, PARTS }

/** A box on a carrier: whose (a card id) and which. A carrier makes its own names from it (a folder, a directory). */
data class Address(val owner: String, val box: Box) {
    /** Neutral names: only the start of the card id. */
    val short: String get() = owner.take(12)

    companion object {
        fun letters(card: PeerCard) = Address(card.id, Box.LETTERS)

        fun parts(card: PeerCard) = Address(card.id, Box.PARTS)
    }
}

/** Something waiting on a carrier: [handle] is the carrier's own (an IMAP UID, a file name), [name] the sender's. */
data class Dropped(val handle: String, val name: String, val size: Long)

/**
 * What a carrier can carry. [maxItem]: the largest item it takes; [dailyBudget]: how many bytes a day this server may
 * drop through it (null: no limit worth counting); [pushes]: it tells of an arrival at once, so waiting can be long;
 * [whole]: an item lands whole or not at all (a synced folder shows a file while it is still coming).
 */
data class CarrierTraits(val maxItem: Long, val dailyBudget: Long?, val pushes: Boolean, val whole: Boolean)

/**
 * How sealed things travel between servers (ADR-055): a dead drop with a box per server and kind. A carrier never sees
 * anything but sealed bytes and names that are random ids; everything else (numbers, acknowledgements, sending again,
 * cutting files into parts) is above it, so moving the link to another carrier is a matter of settings.
 */
interface Carrier : AutoCloseable {
    val traits: CarrierTraits

    /** Puts [data] under [name] into the box [to]; an item of the same name already there may stay beside it. */
    fun drop(to: Address, name: String, data: ByteArray)

    /** What waits in the box [at] (this server's own). */
    fun waiting(at: Address): List<Dropped>

    fun read(at: Address, item: Dropped): ByteArray

    /** Removes [items] from [at]; ones already gone are no matter. */
    fun remove(at: Address, items: Collection<Dropped>)

    /** The names waiting in someone else's box: a lost item is sent again only when it's no longer there. */
    fun names(at: Address): Set<String>

    /** Returns when something may have arrived in [at], [wake] was called, or [timeoutMs] passed. [close] ends it at once. */
    fun await(at: Address, timeoutMs: Long)

    /** Ends an [await] in progress (or the next one, if it comes first). */
    fun wake() {}
}

/** The carrier refused the login: retrying with the same password could get the mailbox locked, so nobody retries. */
class LoginRefused(message: String) : Exception(message)
