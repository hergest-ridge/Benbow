package app.benbow.server

import app.benbow.core.api.PurgeRequest
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SyncRules
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Purges
import app.benbow.server.storage.SqliteSyncStore
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** «Без записи» (ADR-041, 7.18 M6): files no entry references, offered for deleting only after thirty days. */
class UnreferencedFilesTest {
    private val dir = Files.createTempDirectory("benbow-unreferenced")
    private val db = Database.open(dir, "test")
    private var clock = 1_792_000_000_000L
    private val blobs = BlobStore(db, dir.resolve("media")) { clock }
    private val purges = Purges(db, blobs, dir.resolve("db").resolve(PURGE_JOURNAL), now = { clock })
    private val day = 24L * 3_600_000

    private fun file(size: Int, seed: Int): String {
        val bytes = Random(seed).nextBytes(size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val upload = blobs.start(sha, size.toLong(), "image/jpeg", "f$seed.jpg", null, null) as BlobStore.Start.Upload
        blobs.append(upload.id, 0, bytes)
        return sha
    }

    @Test
    fun aFileHeldByNothingIsOfferedAfterThirtyDaysAndGoesLikeTheTrashs() {
        val orphan = file(5_000, 1)
        val held = file(3_000, 2)
        SyncRules(SqliteSyncStore(db, "phone")).push(
            listOf(
                CreateEntry("c", "e", "journal", "2026-10-21", 1),
                PutAttachment("a", "e", AttachmentMeta("a", held, 3_000, "image/jpeg", "image"), 1.0, hlc = Hlc(1, 0, "phone")),
            ),
        )
        assertEquals(emptyList(), purges.unreferenced(), "too new: its link may still come")
        assertEquals(Purges.Preview.Refused("too_new"), purges.preview(PurgeRequest(unreferenced = listOf(orphan)), null))

        clock += 31 * day
        assertEquals(listOf(orphan), purges.unreferenced().map { it.sha256 }, "the held one is never listed")
        assertIs<Purges.Preview.Refused>(purges.preview(PurgeRequest(unreferenced = listOf(held)), null))
        val preview = assertIs<Purges.Preview.Ready>(purges.preview(PurgeRequest(unreferenced = listOf(orphan)), null)).preview
        assertEquals(5_000, preview.bytes)
        assertIs<Purges.Commit.Done>(purges.commit(preview.planId))
        assertNull(blobs.blob(orphan))
        assertEquals(emptyList(), purges.unreferenced())
    }
}
