package app.benbow.server

import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.MediaFolder
import app.benbow.server.storage.MediaUnavailable
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The media folder's mark (ADR-042, data-safety "Server storage layout"): files are worked on only in this store's folder. */
class MediaFolderTest {
    private val dir = Files.createTempDirectory("benbow-media-folder")
    private val db = Database.open(dir, "test")
    private val media = dir.resolve("media")
    private val unplugged = dir.resolve("unplugged")

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun start(store: BlobStore, bytes: ByteArray) =
        store.start(sha256(bytes), bytes.size.toLong(), "image/jpeg", "IMG.jpg", 1_792_000_000_000, null)

    private fun upload(store: BlobStore, bytes: ByteArray): BlobStore.Blob {
        val upload = assertIs<BlobStore.Start.Upload>(start(store, bytes))
        return assertNotNull(assertIs<BlobStore.Chunk.Accepted>(store.append(upload.id, 0, bytes)).blob)
    }

    @Test
    fun aNewStoresFolderIsMadeAndMarked() {
        val store = BlobStore(db, media)
        assertTrue(store.ready())
        assertTrue("store=${db.storeId}" in media.resolve(MediaFolder.MARK).readText())
        val bytes = Random(1).nextBytes(1_000)
        assertContentEquals(bytes, store.file(upload(store, bytes)).readBytes())
        assertEquals(0, store.adoptUntracked(), "the mark is never taken for a file")
    }

    @Test
    fun anOldFolderWithFilesTheDatabaseKnowsIsMarkedOnce() {
        val bytes = Random(2).nextBytes(2_000)
        val blob = upload(BlobStore(db, media), bytes)
        media.resolve(MediaFolder.MARK).deleteExisting() // a folder from before marks
        val store = BlobStore(db, media)
        assertTrue(store.ready())
        assertTrue(media.resolve(MediaFolder.MARK).exists())
        assertContentEquals(bytes, store.file(blob).readBytes())
    }

    @Test
    fun someoneElsesPhotoFolderIsNeverUsedOrAdopted() {
        val photos = dir.resolve("Фото").createDirectories()
        val photo = Random(3).nextBytes(500)
        photos.resolve("IMG_0001.jpg").writeBytes(photo)
        val store = BlobStore(db, photos)
        assertEquals(MediaFolder.State.NotOurs, store.folder.check())
        assertEquals(0, store.adoptUntracked())
        assertEquals(emptyList(), store.all(), "nothing recorded, so nothing there can ever be purged")
        assertFailsWith<MediaUnavailable> { start(store, Random(4).nextBytes(100)) }
        assertEquals(listOf("IMG_0001.jpg"), photos.listDirectoryEntries().map { it.name }, "nothing written there")
        assertContentEquals(photo, photos.resolve("IMG_0001.jpg").readBytes())
    }

    @Test
    fun anUnpluggedDiskIsNeverMadeAgainAndFileWorkWaitsUntilItIsBack() {
        val store = BlobStore(db, media)
        val first = upload(store, Random(5).nextBytes(2_000))
        val bytes = Random(6).nextBytes(3_000)
        val upload = assertIs<BlobStore.Start.Upload>(start(store, bytes))
        assertIs<BlobStore.Chunk.Accepted>(store.append(upload.id, 0, bytes.copyOfRange(0, 1_000)))

        Files.move(media, unplugged)
        assertFalse(store.ready())
        assertFailsWith<MediaUnavailable> { store.append(upload.id, 1_000, bytes.copyOfRange(1_000, 3_000)) }
        assertFailsWith<MediaUnavailable> { start(store, Random(7).nextBytes(100)) }
        assertEquals(0, store.dropStaleUploads(0), "the half-sent file waits")
        assertEquals(emptyList(), store.scrub(-1, 100), "a missing folder is not a corrupt file")
        assertEquals(0, store.adoptUntracked())
        assertFalse(media.exists(), "nothing made the folder again")

        Files.move(unplugged, media)
        assertTrue(store.ready())
        val placed = assertNotNull(assertIs<BlobStore.Chunk.Accepted>(store.append(upload.id, 1_000, bytes.copyOfRange(1_000, 3_000))).blob)
        assertContentEquals(bytes, store.file(placed).readBytes())
        assertNotNull(store.blob(first.sha256))
    }

    @Test
    fun anEmptyFolderUnderTheSameLetterIsNotTakenForTheLibrary() {
        val store = BlobStore(db, media)
        upload(store, Random(8).nextBytes(1_000))
        Files.move(media, unplugged)
        media.createDirectories() // another disk got the letter and has a folder of that name
        assertEquals(MediaFolder.State.Missing, store.folder.check())
        assertFailsWith<MediaUnavailable> { start(store, Random(9).nextBytes(100)) }
        assertEquals(emptyList(), media.listDirectoryEntries(), "no mark, no uploads written there")
    }

    @Test
    fun aFolderOfAnotherStoreIsRefused() {
        val other = Database.open(Files.createTempDirectory("benbow-other"), "test")
        upload(BlobStore(other, media), Random(10).nextBytes(1_000))
        val store = BlobStore(db, media)
        assertEquals(MediaFolder.State.Foreign(other.storeId), store.folder.check())
        assertFailsWith<MediaUnavailable> { start(store, Random(11).nextBytes(100)) }
        assertEquals(0, store.adoptUntracked(), "another store's files never become this one's")
        assertEquals(emptyList(), store.all())
    }
}
