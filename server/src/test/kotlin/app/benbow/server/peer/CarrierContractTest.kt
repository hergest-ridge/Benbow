package app.benbow.server.peer

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What every carrier promises the link (ADR-055), checked on each of them: a new carrier is ready when it passes this.
 * [open] gives a carrier on the same drop, as the other server would have.
 */
abstract class CarrierContract {
    abstract fun open(): Carrier

    private val anna = Address("a11aa11aa11aa11a", Box.LETTERS)
    private val annaParts = Address("a11aa11aa11aa11a", Box.PARTS)
    private val max = Address("bee5bee5bee5bee5", Box.LETTERS)

    @Test
    fun anItemIsDroppedListedReadAndRemoved() = open().use { here ->
        open().use { there ->
            there.drop(anna, "id-1", "{\"v\":1}".encodeToByteArray())
            there.drop(anna, "id-2", "второе".encodeToByteArray())
            val items = here.waiting(anna).sortedBy { it.name }
            assertEquals(listOf("id-1", "id-2"), items.map { it.name })
            assertEquals("{\"v\":1}", here.read(anna, items[0]).decodeToString().trim())
            here.remove(anna, listOf(items[0]))
            assertEquals(listOf("id-2"), here.waiting(anna).map { it.name })
            here.remove(anna, listOf(items[0])) // already gone: no matter
            here.remove(anna, emptyList())
            assertEquals(1, here.waiting(anna).size)
        }
    }

    @Test
    fun boxesAndOwnersStayApart() = open().use { carrier ->
        carrier.drop(anna, "letter", "l".encodeToByteArray())
        carrier.drop(annaParts, "part", "p".encodeToByteArray())
        carrier.drop(max, "other", "o".encodeToByteArray())
        assertEquals(listOf("letter"), carrier.waiting(anna).map { it.name })
        assertEquals(listOf("part"), carrier.waiting(annaParts).map { it.name })
        assertEquals(listOf("other"), carrier.waiting(max).map { it.name })
        assertEquals(setOf("other"), carrier.names(max))
        assertEquals(emptySet(), carrier.names(Address("0000000000000000", Box.PARTS)), "a box nobody used yet is empty")
    }

    @Test
    fun aLargeBinaryPartComesBackByteForByte() = open().use { here ->
        val bytes = Random(55).nextBytes(minOf(here.traits.maxItem, 3L * 1024 * 1024).toInt())
        bytes[0] = 0
        bytes[1] = -1
        open().use { there -> there.drop(annaParts, "file.0", bytes) }
        val item = here.waiting(annaParts).single()
        assertContentEquals(bytes, here.read(annaParts, item))
    }

    @Test
    fun anItemDroppedTwiceUnderOneNameReadsAsOneOfThem() = open().use { carrier ->
        carrier.drop(annaParts, "file.3", "first".encodeToByteArray())
        carrier.drop(annaParts, "file.3", "again".encodeToByteArray())
        val items = carrier.waiting(annaParts).filter { it.name == "file.3" }
        assertTrue(items.size in 1..2)
        items.forEach { assertTrue(carrier.read(annaParts, it).decodeToString() in setOf("first", "again")) }
    }

    @Test
    fun aWaitEndsByItsTimeAndByAWake() = open().use { carrier ->
        carrier.waiting(anna)
        val t0 = System.nanoTime()
        carrier.await(anna, timeoutMs = 300)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 8_000, "an empty wait ends")

        val waker = Thread {
            Thread.sleep(300)
            carrier.wake()
        }.apply { start() }
        val t1 = System.nanoTime()
        carrier.await(anna, timeoutMs = 60_000)
        waker.join()
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 10_000, "a wake ends the wait")
    }
}

class MemoryCarrierContractTest : CarrierContract() {
    private val shared = MemoryCarrier(CarrierTraits(maxItem = 4L shl 20, dailyBudget = null, pushes = true, whole = true))

    override fun open(): Carrier = object : Carrier by shared {
        override fun close() {}
    }
}

class FolderCarrierContractTest : CarrierContract() {
    private val dir = Files.createTempDirectory("benbow-folder-carrier")

    override fun open(): Carrier = FolderCarrier(dir.resolve("drive"), dir.resolve("staging"), pollMs = 200)
}

class ImapCarrierContractTest : CarrierContract() {
    private val mail = GreenMail(ServerSetupTest.IMAP.dynamicPort()).apply {
        start()
        setUser("box@benbow.test", "box", "secret")
    }

    override fun open(): Carrier = ImapCarrier(MailSettings("127.0.0.1", mail.imap.port, "box", "secret", secure = false))

    @AfterTest
    fun stop() = mail.stop()
}
