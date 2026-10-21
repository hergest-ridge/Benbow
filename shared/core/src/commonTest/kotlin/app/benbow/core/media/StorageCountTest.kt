package app.benbow.core.media

import app.benbow.core.api.StorageMonth
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.Hlc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Место» (ADR-041): each file once, as alive as its most alive holder, as what its strongest reference makes it. */
class StorageCountTest {
    @Test
    fun eachFileCountsOnceAsItsMostAliveHolderInThatHoldersMonth() {
        val report = StorageCount.of(
            listOf(
                MediaEntry(
                    "old",
                    trashed = true,
                    day = "2025-05-02",
                    attachments = mapOf("a" to link("a", sha(1), "video", preview = sha(2))),
                ),
                MediaEntry("new", trashed = false, day = "2026-10-21", attachments = mapOf("b" to link("b", sha(1), "video"))),
                MediaEntry(
                    "off",
                    trashed = false,
                    day = "2026-09-01",
                    attachments = mapOf("c" to link("c", sha(3), "voice", present = false)),
                ),
                MediaEntry("card", trashed = false, day = "2026-10-01", bodies = listOf("""{"image":"${sha(4)}"}""")),
            ),
            mapOf(sha(1) to 1_000L, sha(2) to 20L, sha(3) to 300L, sha(4) to 40L, sha(5) to 5L),
        )
        assertEquals(5, report.files)
        assertEquals(1_365, report.bytes)
        assertEquals(mapOf("video" to 1_000L, "preview" to 60L, "sound" to 300L, "file" to 5L), report.kinds)
        assertEquals(mapOf("live" to 1_040L, "trash" to 20L, "removed" to 300L, "unreferenced" to 5L), report.states)
        assertEquals(
            listOf(
                StorageMonth("2025-05", mapOf("preview" to 20L)),
                StorageMonth("2026-09", mapOf("sound" to 300L)),
                StorageMonth("2026-10", mapOf("video" to 1_000L, "preview" to 40L)),
            ),
            report.months,
        )
    }

    @Test
    fun purgedLinksHoldNothingButStillSayWhatTheFileWas() {
        val report = StorageCount.of(
            listOf(
                MediaEntry("e", trashed = true, day = "2026-10-21", attachments = mapOf("a" to link("a", sha(1), "image", purged = true))),
            ),
            mapOf(sha(1) to 10L),
        )
        assertEquals(mapOf("image" to 10L), report.kinds)
        assertEquals(mapOf("unreferenced" to 10L), report.states)
        assertEquals(emptyList(), report.months)
    }

    @Test
    fun kindsStatesAndMonthsAlwaysAddUpToWhatTheStoreHolds() {
        val random = Random(9)
        val pool = List(8) { sha(it + 1) }
        repeat(500) { round ->
            val entries = List(random.nextInt(1, 8)) { i ->
                MediaEntry(
                    "e$i",
                    trashed = random.nextInt(3) == 0,
                    parent = if (i > 0 && random.nextInt(4) == 0) "e${random.nextInt(i)}" else null,
                    day = "2026-0${random.nextInt(1, 10)}-15",
                    attachments = List(random.nextInt(0, 4)) { j ->
                        "a$j" to link(
                            "a$j",
                            pool.random(random),
                            listOf("image", "video", "voice", "audio", "drawing", "file").random(random),
                            preview = pool.random(random).takeIf { random.nextBoolean() },
                            present = random.nextInt(4) != 0,
                            purged = random.nextInt(6) == 0,
                        )
                    }.toMap(),
                    bodies = if (random.nextBoolean()) listOf("""{"image":"${pool.random(random)}"}""") else emptyList(),
                )
            }
            val files = pool.associateWith { random.nextLong(1, 1_000) }
            val report = StorageCount.of(entries, files)
            assertEquals(files.values.sum(), report.kinds.values.sum(), "round $round")
            assertEquals(files.values.sum(), report.states.values.sum(), "round $round")
            assertEquals(
                files.values.sum() - (report.states["unreferenced"] ?: 0),
                report.months.sumOf {
                    it.kinds.values.sum()
                },
                "round $round",
            )
        }
    }

    private fun sha(n: Int): String = n.toString(16).padStart(64, '0')

    private fun link(id: String, file: String, kind: String, preview: String? = null, present: Boolean = true, purged: Boolean = false) =
        AttachmentState(
            AttachmentMeta(id, file, 1, "application/octet-stream", kind, previewSha256 = preview),
            1.0,
            present = present,
            hlc = Hlc.ZERO,
            purged = purged,
        )
}
