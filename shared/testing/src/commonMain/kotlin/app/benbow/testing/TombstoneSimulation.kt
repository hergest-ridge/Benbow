package app.benbow.testing

import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.HlcClock
import app.benbow.core.sync.OpStatus
import app.benbow.core.sync.Purging
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SyncRules
import app.benbow.core.sync.SyncStore
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Purged links stay purged (ADR-041, data-safety "Purge"), against any [SyncStore]: offline clients attach, take off and
 * bring back attachments while requests and answers get lost, and now and then the server purges links taken off
 * (or a whole entry). Invariants: a purged link never comes back on the server, whatever is pushed later; a link put on
 * a purged entry is purged from the start; once the network heals, every client holds exactly the server's links.
 */
class TombstoneSimulation(private val storeFactory: () -> SyncStore) {
    /** How often the interesting paths ran over all runs: a purge, and a late put the server absorbed. */
    var purges = 0
        private set
    var absorbed = 0
        private set

    fun run(seed: Long, steps: Int = 120, clientCount: Int = 3) {
        val rnd = Random(seed)
        val store = storeFactory()
        val server = SyncRules(store)
        val entries = listOf("e1", "e2")
        server.push(entries.map { CreateEntry("create-$it", it, "journal", "2026-10-21", 1) })
        val clients = List(clientCount) { Client("c$it", rnd) }
        val purged = HashSet<Pair<String, String>>()
        var time = 1_000L

        fun checkPurgedStay(when_: String) {
            for ((entry, link) in purged) {
                val state = store.entry(entry)!!.attachments.getValue(link)
                assertTrue(state.purged && !state.present, "$entry/$link came back $when_ (seed $seed)")
            }
        }

        repeat(steps) {
            time += 10
            when (rnd.nextInt(12)) {
                in 0..2 -> clients.random(rnd).attach(entries.random(rnd), time)

                in 3..5 -> clients.random(rnd).toggle(time)

                in 6..8 -> clients.random(rnd).sync(server, lose = true)

                9, 10 -> {
                    // The server purges a link taken off (as the trash's «Удалить навсегда» would).
                    val entry = store.entry(entries.random(rnd))!!
                    val off = entry.attachments.filterValues { !it.present && !it.purged }.keys.randomOrNull(rnd) ?: return@repeat
                    store.transaction {
                        store.putEntry(Purging.apply(entry, listOf(off), whole = false, Purging.clockFor(entry, time)))
                        store.appendChange(entry.id)
                    }
                    purged += entry.id to off
                    purges++
                }

                else -> {
                    // Rarely, a whole entry goes for good: every link of it, and any put on it later, is purged.
                    if (rnd.nextInt(4) != 0) return@repeat
                    val entry = store.entry(entries.random(rnd))!!
                    if (Purging.isPurged(entry)) return@repeat
                    store.transaction {
                        store.putEntry(Purging.apply(entry, entry.attachments.keys, whole = true, Purging.clockFor(entry, time)))
                        store.appendChange(entry.id)
                    }
                    entry.attachments.keys.forEach { purged += entry.id to it }
                    purges++
                }
            }
            checkPurgedStay("after a step")
            // Links on a purged entry are purged, including ones put after it went.
            for (id in entries) {
                val entry = store.entry(id)!!
                if (Purging.isPurged(entry)) {
                    entry.attachments.forEach { (link, state) ->
                        assertTrue(state.purged, "$id/$link is live on a purged entry (seed $seed)")
                        purged += id to link
                    }
                }
            }
        }
        repeat(4) { clients.forEach { it.sync(server, lose = false) } }
        checkPurgedStay("after the network healed")
        for (client in clients) {
            assertTrue(client.outbox.isEmpty(), "outbox of ${client.name} drained (seed $seed)")
            for (id in entries) {
                assertEquals(
                    store.entry(id)!!.attachments,
                    client.links[id].orEmpty(),
                    "${client.name} holds the server's links of $id (seed $seed)",
                )
            }
        }
        absorbed += clients.sumOf { it.absorbed }
    }

    private class Client(val name: String, private val rnd: Random) {
        val links = HashMap<String, MutableMap<String, AttachmentState>>()
        val outbox = ArrayList<PutAttachment>()
        var absorbed = 0
        private val clock = HlcClock(name) { 0 }
        private var counter = 0
        private var cursor = 0L

        fun attach(entry: String, time: Long) {
            val id = "$name-a${counter++}"
            val meta = AttachmentMeta(id, "sha-$id", 10, "image/jpeg", "image")
            put(entry, AttachmentState(meta, counter.toDouble(), hlc = clock.next()), time)
        }

        /** Takes a link off, or brings one back (an offline «Вернуть» may come after the server purged it). */
        fun toggle(time: Long) {
            val (entry, link) = links.flatMap { (e, m) -> m.keys.map { e to it } }.randomOrNull(rnd) ?: return
            val state = links.getValue(entry).getValue(link)
            put(entry, state.copy(present = !state.present, purged = false, hlc = clock.next()), time)
        }

        private fun put(entry: String, state: AttachmentState, time: Long) {
            links.getOrPut(entry) { LinkedHashMap() }[state.meta.id] = state
            outbox +=
                PutAttachment("$name-op${counter++}-$time", entry, state.meta, state.position, state.caption, state.present, state.hlc)
        }

        fun sync(server: SyncRules, lose: Boolean) {
            if (outbox.isNotEmpty()) {
                val batch = outbox.toList()
                val results = server.push(batch)
                if (lose && rnd.nextInt(4) == 0) return // the answer was lost: the same ops go again next time
                results.forEach { assertTrue(it.status == OpStatus.Applied || it.status == OpStatus.Duplicate, "$it") }
                outbox.removeAll(batch.toSet())
            }
            if (lose && rnd.nextInt(5) == 0) return
            while (true) {
                val page = server.pull(cursor, 50)
                for (snapshot in page.changes) {
                    // A link with a put still waiting keeps the device's state; everything else is the server's.
                    val waiting = outbox.filter { it.entryId == snapshot.entry.id }.map { it.attachment.id }.toSet()
                    val local = links.getOrPut(snapshot.entry.id) { LinkedHashMap() }
                    for ((id, state) in snapshot.entry.attachments) {
                        if (id in waiting) continue
                        if (state.purged && local[id]?.purged == false && local[id]?.present == true) absorbed++
                        local[id] = state
                    }
                }
                cursor = page.nextCursor
                if (!page.hasMore) break
            }
        }
    }
}
