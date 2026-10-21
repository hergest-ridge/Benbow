package app.benbow.core.sync

import app.benbow.testing.InMemorySyncStore
import app.benbow.testing.TombstoneSimulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deleting for good is final (ADR-041): a purged link or entry never comes back through sync, only the admin's undo. */
class PurgingTest {
    private val store = InMemorySyncStore()
    private val rules = SyncRules(store)
    private val meta = AttachmentMeta("a", "f".repeat(64), 10, "image/jpeg", "image")

    private fun put(opId: String, present: Boolean, wall: Long) =
        rules.push(listOf(PutAttachment(opId, "e", meta, 1.0, present = present, hlc = Hlc(wall, 0, "phone")))).single()

    private fun purge(whole: Boolean = false) {
        val entry = store.entry("e")!!
        store.putEntry(Purging.apply(entry, entry.attachments.keys, whole, Purging.clockFor(entry, 50)))
        store.appendChange("e")
    }

    private fun link() = store.entry("e")!!.attachments.getValue("a")

    @Test
    fun aPurgedLinkAbsorbsEveryLaterPutAndTheDeviceHearsSo() {
        rules.push(listOf(CreateEntry("c", "e", "journal", "2026-10-21", 1)))
        put("on", present = true, wall = 10)
        put("off", present = false, wall = 20)
        purge()
        assertEquals(link().copy(present = false, purged = true), link())
        val cursor = store.changesSince(0, Int.MAX_VALUE).last().first
        // An offline «Вернуть» with a clock far ahead: applied (nothing to retry), and nothing changes.
        assertEquals(OpStatus.Applied, put("back", present = true, wall = 9_999_999).status)
        assertTrue(link().purged && !link().present)
        assertTrue(store.changesSince(cursor, 10).isNotEmpty(), "logged, so the device pulls the purged state")
        assertEquals(OpStatus.Duplicate, put("back", present = true, wall = 9_999_999).status)
    }

    @Test
    fun noDeviceWritesThePurgedFieldAndAWholeEntryTakesNewLinksPurged() {
        rules.push(listOf(CreateEntry("c", "e", "journal", "2026-10-21", 1)))
        put("on", present = true, wall = 10)
        purge(whole = true)
        assertTrue(Purging.isPurged(store.entry("e")!!))
        rules.push(listOf(SetField("unpurge", "e", Purging.FIELD, null, Hlc(9_999_999, 0, "phone"))))
        assertTrue(Purging.isPurged(store.entry("e")!!), "only the server writes it")
        val late = meta.copy(id = "b", sha256 = "e".repeat(64))
        rules.push(listOf(PutAttachment("late", "e", late, 2.0, present = true, hlc = Hlc(9_999_999, 0, "phone"))))
        val b = store.entry("e")!!.attachments.getValue("b")
        assertTrue(b.purged && !b.present, "a link put on an entry gone for good holds nothing")
    }

    @Test
    fun theUndoPutsLinksBackAsTheyWereWithAFreshClock() {
        rules.push(listOf(CreateEntry("c", "e", "journal", "2026-10-21", 1)))
        put("on", present = true, wall = 10)
        put("off", present = false, wall = 20)
        val before = link()
        purge(whole = true)
        val entry = store.entry("e")!!
        val back = Purging.undo(entry, mapOf("a" to before), whole = true, Purging.clockFor(entry, 60))
        assertEquals(before.copy(hlc = back.attachments.getValue("a").hlc), back.attachments.getValue("a"))
        assertTrue(back.attachments.getValue("a").hlc > entry.attachments.getValue("a").hlc)
        assertEquals(false, Purging.isPurged(back))
    }

    @Test
    fun purgedLinksStayPurgedWhateverOfflineDevicesPushLater() {
        val simulation = TombstoneSimulation(::InMemorySyncStore)
        repeat(300) { simulation.run(seed = it.toLong()) }
        assertTrue(simulation.purges > 100, "purges ran: ${simulation.purges}")
    }
}
