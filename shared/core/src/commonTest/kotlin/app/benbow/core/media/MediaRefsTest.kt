package app.benbow.core.media

import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.Hlc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Who holds each file (ADR-041): a purge frees exactly what nothing else holds, and only from the trash. */
class MediaRefsTest {
    @Test
    fun aSharedFileStaysWhileALiveEntryHoldsIt() {
        val refs = MediaRefs(
            listOf(
                MediaEntry("live", trashed = false, attachments = mapOf("a" to link("a", sha(1)))),
                MediaEntry("old", trashed = true, attachments = mapOf("b" to link("b", sha(1)), "c" to link("c", sha(2), size = 700))),
            ),
        )
        val plan = assertIs<PurgePlan.Ready>(refs.plan(PurgeSelection(entries = setOf("old"))))
        assertEquals(listOf("old"), plan.entries)
        assertEquals(listOf(AttachmentKey("old", "b"), AttachmentKey("old", "c")), plan.links)
        assertEquals(listOf(PurgeFile(sha(2), 700)), plan.files)
        assertEquals(700, plan.bytes)
        val kept = plan.staying.single()
        assertEquals(sha(1), kept.sha256)
        assertEquals(listOf(MediaRef(sha(1), "live", "a", MediaRole.File, MediaState.Live, 100)), kept.keptBy)
    }

    @Test
    fun onlyTheTrashCanBePurged() {
        val refs = MediaRefs(
            listOf(
                MediaEntry("live", trashed = false, attachments = mapOf("a" to link("a", sha(1)))),
                MediaEntry("old", trashed = true, attachments = mapOf("b" to link("b", sha(2)))),
            ),
        )
        assertEquals(
            PurgePlan.Refused(listOf("live", "gone"), emptyList(), emptyList()),
            refs.plan(PurgeSelection(entries = setOf("old", "live", "gone"))),
        )
        assertEquals(
            PurgePlan.Refused(emptyList(), listOf(AttachmentKey("live", "a"), AttachmentKey("old", "x")), emptyList()),
            refs.plan(
                PurgeSelection(attachments = setOf(AttachmentKey("live", "a"), AttachmentKey("old", "b"), AttachmentKey("old", "x"))),
            ),
        )
        assertEquals(
            PurgePlan.Refused(emptyList(), emptyList(), listOf(sha(2))),
            refs.plan(PurgeSelection(unreferenced = setOf(sha(2), sha(9)))),
        )
    }

    @Test
    fun aLinkTakenOffALiveEntryGoesWithItsPreview() {
        val refs = MediaRefs(
            listOf(
                MediaEntry(
                    "e",
                    trashed = false,
                    attachments = mapOf("off" to link("off", sha(1), preview = sha(3), present = false), "on" to link("on", sha(2))),
                ),
            ),
        )
        assertEquals(MediaState.Removed, refs.of(sha(3)).single().state)
        val plan = assertIs<PurgePlan.Ready>(refs.plan(PurgeSelection(attachments = setOf(AttachmentKey("e", "off")))))
        assertEquals(emptyList(), plan.entries)
        assertEquals(listOf(AttachmentKey("e", "off")), plan.links)
        assertEquals(listOf(sha(1), sha(3)), plan.files.map { it.sha256 })
        assertEquals(listOf(100L, null), plan.files.map { it.size })
    }

    @Test
    fun commentsShareTheirEntrysTrashAndGoWithIt() {
        val refs = MediaRefs(
            listOf(
                MediaEntry("post", trashed = true, attachments = mapOf("a" to link("a", sha(1)))),
                MediaEntry("comment", trashed = false, parent = "post", attachments = mapOf("b" to link("b", sha(4)))),
                MediaEntry("reply", trashed = false, parent = "comment", attachments = mapOf("c" to link("c", sha(5)))),
            ),
        )
        assertEquals(MediaState.Trash, refs.of(sha(5)).single().state)
        val plan = assertIs<PurgePlan.Ready>(refs.plan(PurgeSelection(entries = setOf("post"))))
        assertEquals(listOf("post", "comment", "reply"), plan.entries)
        assertEquals(setOf(sha(1), sha(4), sha(5)), plan.files.map { it.sha256 }.toSet())
    }

    @Test
    fun textHoldsEveryFileItNamesEvenUnread() {
        val refs = MediaRefs(
            listOf(
                MediaEntry("cards", trashed = false, bodies = listOf(card(sha(5)), card(sha(6)))),
                MediaEntry("odd", trashed = false, bodies = listOf("not a document ${sha(7)}")),
                MediaEntry("old", trashed = true, attachments = mapOf("a" to link("a", sha(5)))),
            ),
        )
        assertEquals(setOf(sha(5), sha(6), sha(7)), refs.held)
        val plan = assertIs<PurgePlan.Ready>(refs.plan(PurgeSelection(entries = setOf("old"))))
        assertEquals(emptyList(), plan.files)
        assertEquals(listOf(MediaRef(sha(5), "cards", null, MediaRole.Text, MediaState.Live)), plan.staying.single().keptBy)
    }

    @Test
    fun purgedLinksHoldNothing() {
        val refs = MediaRefs(listOf(MediaEntry("e", trashed = true, attachments = mapOf("a" to link("a", sha(1), purged = true)))))
        assertEquals(MediaState.Purged, refs.of(sha(1)).single().state)
        assertEquals(emptySet(), refs.held)
        val plan = assertIs<PurgePlan.Ready>(refs.plan(PurgeSelection(entries = setOf("e"), unreferenced = setOf(sha(1))), sizeOf = { 42 }))
        assertEquals(emptyList(), plan.links)
        assertEquals(listOf(PurgeFile(sha(1), 42)), plan.files)
    }

    @Test
    fun onlyRunsOfExactlySixtyFourLowercaseHexAreNames() {
        val name = sha(10)
        assertEquals(setOf(name), MediaRefs.namedFiles(name))
        assertEquals(setOf(name), MediaRefs.namedFiles("\"image\":\"$name\"}"))
        assertEquals(emptySet(), MediaRefs.namedFiles(name.drop(1)))
        assertEquals(emptySet(), MediaRefs.namedFiles(name + "a"))
        assertEquals(emptySet(), MediaRefs.namedFiles(name.uppercase()))
        assertEquals(setOf(sha(1), sha(2)), MediaRefs.namedFiles("${sha(1)} ${sha(2)} ${sha(1)}"))
    }

    @Test
    fun randomPurgesFreeExactlyWhatNothingElseHolds() {
        val random = Random(41)
        val pool = List(6) { sha(it + 1) }
        val orphans = listOf(sha(100), sha(101))
        val sizes = (pool + orphans).associateWith { random.nextLong(1, 10_000) }
        repeat(2_000) { round ->
            val entries = List(random.nextInt(1, 10)) { i ->
                val links = List(random.nextInt(0, 4)) { j ->
                    "a$j" to link(
                        "a$j",
                        pool.random(random),
                        size = 0,
                        preview = pool.random(random).takeIf { random.nextBoolean() },
                        present = random.nextInt(4) != 0,
                        purged = random.nextInt(6) == 0,
                    )
                }
                MediaEntry(
                    "e$i",
                    trashed = random.nextInt(3) == 0,
                    parent = if (i > 0 && random.nextInt(4) == 0) "e${random.nextInt(i)}" else null,
                    attachments = links.toMap(),
                    bodies = List(random.nextInt(0, 3)) { if (random.nextBoolean()) card(pool.random(random)) else "{}" },
                )
            }
            val before = MediaRefs(entries)
            val selection = PurgeSelection(
                entries = entries.filter { before.inTrash(it) && random.nextBoolean() }.mapTo(HashSet()) { it.id },
                attachments = before.refs
                    .filter { it.attachmentId != null && it.state in setOf(MediaState.Trash, MediaState.Removed) && random.nextBoolean() }
                    .mapTo(HashSet()) { AttachmentKey(it.entryId, it.attachmentId!!) },
                unreferenced = orphans.filterTo(HashSet()) { random.nextBoolean() },
            )
            val plan = assertIs<PurgePlan.Ready>(before.plan(selection, sizes::get), "round $round")
            val after = MediaRefs(
                entries.filter { it.id !in plan.entries }.map { entry ->
                    entry.copy(
                        attachments = entry.attachments.mapValues { (id, link) ->
                            if (AttachmentKey(entry.id, id) in plan.links) link.copy(purged = true) else link
                        },
                    )
                },
            )
            val freed = plan.files.map { it.sha256 }.toSet()
            assertEquals(before.held - freed, after.held, "round $round")
            assertTrue(plan.staying.all { it.sha256 in after.held }, "round $round")
            assertEquals(plan.files.sumOf { sizes.getValue(it.sha256) }, plan.bytes, "round $round")
            if (entries.any { !before.inTrash(it) }) {
                val live = entries.first { !before.inTrash(it) }.id
                assertIs<PurgePlan.Refused>(before.plan(selection.copy(entries = selection.entries + live)), "round $round")
            }
        }
    }

    private fun sha(n: Int): String = n.toString(16).padStart(64, '0')

    private fun card(image: String) =
        """{"v":1,"blocks":[{"_key":"k1","_type":"linkCard","href":"https://example.org","image":"$image"}]}"""

    private fun link(
        id: String,
        file: String,
        size: Long = 100,
        preview: String? = null,
        present: Boolean = true,
        purged: Boolean = false,
    ) = AttachmentState(
        AttachmentMeta(id, file, size, "image/jpeg", "image", previewSha256 = preview),
        1.0,
        present = present,
        hlc = Hlc.ZERO,
        purged = purged,
    )
}
