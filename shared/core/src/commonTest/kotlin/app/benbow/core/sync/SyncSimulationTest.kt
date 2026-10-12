package app.benbow.core.sync

import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Deterministic simulation of docs/sync.md#test-plan: several offline-first clients edit the same
 * entries while requests and responses get lost. Invariants: no revision is ever lost, no op is
 * applied twice, all clients converge to the server state, every head is a valid document.
 */
class SyncSimulationTest {

    private val outcomes = HashMap<OpStatus, Int>()

    @Test
    fun randomHistoriesConvergeWithoutLosingData() = runTest {
        checkAll(Arb.long()) { seed -> simulate(seed) }
        // Guard against a vacuous simulation: the interesting paths must actually happen.
        for (status in listOf(OpStatus.Merged, OpStatus.Conflict, OpStatus.Duplicate)) {
            assertTrue((outcomes[status] ?: 0) > 0, "no $status outcome in any history: $outcomes")
        }
    }

    private fun simulate(seed: Long) {
        val rnd = Random(seed)
        val store = InMemoryStore()
        val server = SyncRules(store)
        val net = Network(rnd, failures = true)
        val clients = List(3) { SimClient("c$it", rnd, outcomes) }

        repeat(60) {
            val client = clients.random(rnd)
            when (rnd.nextInt(10)) {
                0 -> client.createEntry()
                in 1..5 -> client.editRandomEntry()
                6 -> client.setRandomField()
                else -> client.sync(server, net)
            }
        }
        // Drain: the network heals and everyone syncs until nothing is pending.
        val reliable = Network(rnd, failures = false)
        repeat(4) { clients.forEach { it.sync(server, reliable) } }

        clients.forEach { c -> assertTrue(c.outbox.isEmpty(), "outbox of ${c.name} drained") }
        assertTrue(clients.none { it.rejected.isNotEmpty() }, "no op rejected")
        val createdRevisions = clients.flatMap { it.createdRevisions }
        createdRevisions.forEach { assertNotNull(store.revision(it), "revision $it lost (seed $seed)") }
        store.applyCounts.forEach { (op, count) -> assertEquals(1, count, "op $op applied $count times") }
        for (entry in store.entries.values) {
            entry.conflictHeads.forEach { assertNotNull(store.revision(it)) }
            val head = entry.head ?: continue
            val body = store.revision(head)!!.body
            val doc = DocumentCodec.decode(body)
            assertEquals(doc.blocks.size, doc.blocks.map { it.key }.toSet().size, "unique keys")
            for (c in clients) {
                val local = c.entries[entry.id] ?: fail("${c.name} misses entry ${entry.id}")
                assertEquals(head, local.head, "${c.name} head of ${entry.id}")
                assertEquals(body, local.body)
                assertEquals(entry.fields.mapValues { it.value.value }, local.fields)
            }
        }
    }

    private class Network(private val rnd: Random, private val failures: Boolean) {
        fun lost(): Boolean = failures && rnd.nextInt(5) == 0
    }

    private class LocalEntry(var head: String?, var body: String, val fields: MutableMap<String, String?> = mutableMapOf())

    private class SimClient(val name: String, private val rnd: Random, private val outcomes: MutableMap<OpStatus, Int>) {
        private var time = 1_000L
        private val clock = HlcClock(name) { time }
        private var counter = 0
        val outbox = ArrayDeque<SyncOp>()
        val rejected = mutableListOf<OpResult>()
        val entries = mutableMapOf<String, LocalEntry>()
        val createdRevisions = mutableListOf<String>()
        private var cursor = 0L

        private fun id(prefix: String) = "$name-$prefix-${counter++}"

        fun createEntry() {
            val entryId = id("e")
            val body = DocumentCodec.encode(Document(listOf(TextBlock(id("k"), children = listOf(Span("start"))))))
            val revisionId = id("r")
            entries[entryId] = LocalEntry(revisionId, body)
            outbox += CreateEntry(id("op"), entryId, "journal", "2026-10-12", time)
            outbox += PutRevision(id("op"), entryId, revisionId, emptyList(), body)
            createdRevisions += revisionId
        }

        fun editRandomEntry() {
            val (entryId, entry) = entries.entries.randomOrNull(rnd)?.toPair() ?: return
            val doc = DocumentCodec.decode(entry.body)
            val blocks = doc.blocks.toMutableList()
            when (rnd.nextInt(3)) {
                0 -> blocks.add(rnd.nextInt(blocks.size + 1), TextBlock(id("k"), children = listOf(Span("new $name"))))

                1 -> if (blocks.size > 1) blocks.removeAt(rnd.nextInt(blocks.size))

                else -> if (blocks.isNotEmpty()) {
                    val i = rnd.nextInt(blocks.size)
                    blocks[i] = TextBlock(blocks[i].key, children = listOf(Span("edit $name ${counter++}")))
                }
            }
            val body = DocumentCodec.encode(Document(blocks))
            if (body == entry.body) return
            val revisionId = id("r")
            outbox += PutRevision(id("op"), entryId, revisionId, listOfNotNull(entry.head), body)
            createdRevisions += revisionId
            entry.head = revisionId
            entry.body = body
        }

        fun setRandomField() {
            val (entryId, entry) = entries.entries.randomOrNull(rnd)?.toPair() ?: return
            time += rnd.nextLong(-50, 100) // drifting clock
            val value = "v${counter++}"
            entry.fields["mood"] = value
            outbox += SetField(id("op"), entryId, "mood", value, clock.next())
        }

        fun sync(server: SyncRules, net: Network) {
            if (outbox.isNotEmpty()) {
                if (net.lost()) return // request lost
                val results = server.push(outbox.toList())
                if (net.lost()) return // response lost: everything is retried, the server dedupes
                for (result in results) {
                    outcomes[result.status] = (outcomes[result.status] ?: 0) + 1
                    val op = outbox.removeFirst()
                    check(op.opId == result.opId)
                    if (result.status == OpStatus.Rejected) rejected += result
                }
            }
            while (true) {
                if (net.lost()) return
                val page = server.pull(cursor, limit = 3)
                for (snapshot in page.changes) {
                    val e = snapshot.entry
                    e.fields.values.forEach { clock.observe(it.hlc) }
                    // Pending local ops win until the server has seen them (docs/sync.md#pull).
                    if (outbox.any { it.entryId == e.id }) continue
                    val local = entries.getOrPut(e.id) { LocalEntry(null, "") }
                    local.head = e.head
                    local.body = snapshot.headBody.orEmpty()
                    local.fields.clear()
                    e.fields.forEach { (k, v) -> local.fields[k] = v.value }
                }
                cursor = page.nextCursor
                if (!page.hasMore) return
            }
        }
    }

    private class InMemoryStore : SyncStore {
        val entries = LinkedHashMap<String, EntryState>()
        private val revisions = HashMap<String, RevisionRecord>()
        private val applied = HashMap<String, OpResult>()
        val applyCounts = HashMap<String, Int>()
        private val changes = ArrayList<Pair<Long, String>>()
        private var revisionCounter = 0

        override fun <T> transaction(block: () -> T): T = block()
        override fun appliedResult(opId: String): OpResult? = applied[opId]
        override fun recordApplied(result: OpResult) {
            applied[result.opId] = result
            applyCounts[result.opId] = (applyCounts[result.opId] ?: 0) + 1
        }
        override fun entry(id: String): EntryState? = entries[id]
        override fun putEntry(entry: EntryState) {
            entries[entry.id] = entry
        }
        override fun revision(id: String): RevisionRecord? = revisions[id]
        override fun putRevision(revision: RevisionRecord) {
            check(revision.id !in revisions) { "revisions are append-only" }
            revisions[revision.id] = revision
        }
        override fun appendChange(entryId: String): Long {
            val seq = changes.size + 1L
            changes += seq to entryId
            return seq
        }
        override fun changesSince(cursor: Long, limit: Int) = changes.filter { it.first > cursor }.take(limit)
        override fun newRevisionId(): String = "server-merge-${revisionCounter++}"
    }
}
