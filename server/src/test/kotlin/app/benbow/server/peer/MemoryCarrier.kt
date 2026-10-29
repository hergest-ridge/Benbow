package app.benbow.server.peer

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A carrier in memory that can lose, double and refuse what it carries (ADR-055). */
class MemoryCarrier(
    override val traits: CarrierTraits = CarrierTraits(maxItem = 1L shl 20, dailyBudget = null, pushes = true, whole = true),
) : Carrier {
    private class Item(val handle: Long, val name: String, val data: ByteArray)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val boxes = mutableMapOf<Address, MutableList<Item>>()
    private var next = 1L
    private var woken = false

    /** Fails the next `remove` (a connection cut after the items were kept). */
    var failNextRemove = false

    /** Every `drop` so far, by box: what a budget would have counted. */
    val dropped = mutableListOf<Pair<Address, Int>>()

    private fun box(at: Address) = boxes.getOrPut(at) { mutableListOf() }

    override fun drop(to: Address, name: String, data: ByteArray) = lock.withLock {
        require(data.size <= traits.maxItem) { "${data.size} bytes is more than this carrier takes" }
        box(to) += Item(next++, name, data.copyOf())
        dropped += to to data.size
        changed.signalAll()
    }

    override fun waiting(at: Address) = lock.withLock { box(at).map { Dropped(it.handle.toString(), it.name, it.data.size.toLong()) } }

    override fun read(at: Address, item: Dropped) = lock.withLock {
        box(at).first { it.handle.toString() == item.handle }.data.copyOf()
    }

    override fun remove(at: Address, items: Collection<Dropped>) = lock.withLock {
        if (failNextRemove) {
            failNextRemove = false
            error("connection reset")
        }
        val handles = items.map { it.handle }.toSet()
        box(at).removeAll { it.handle.toString() in handles }
        Unit
    }

    override fun names(at: Address) = lock.withLock { box(at).map { it.name }.toSet() }

    override fun await(at: Address, timeoutMs: Long) = lock.withLock {
        if (!woken) changed.await(timeoutMs, TimeUnit.MILLISECONDS)
        woken = false
    }

    override fun wake() = lock.withLock {
        woken = true
        changed.signalAll()
    }

    override fun close() {}

    fun lose(at: Address) = lock.withLock { box(at).clear() }

    fun double(at: Address) = lock.withLock { box(at).toList().forEach { box(at) += Item(next++, it.name, it.data) } }

    fun put(at: Address, text: String) = drop(at, "x", text.encodeToByteArray())

    fun count(at: Address) = lock.withLock { box(at).size }

    /** Changes one byte of the item [name] in [at], as a damaged copy would. */
    fun spoil(at: Address, name: String) = lock.withLock {
        box(at).first { it.name == name }.data.let { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
    }
}
