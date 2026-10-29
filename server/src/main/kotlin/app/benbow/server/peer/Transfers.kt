package app.benbow.server.peer

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Files between servers in parts through any carrier (ADR-055). A file is sealed whole as it is uploaded and kept in
 * `<peer>/outgoing/` until the friend has it; a pass on the carrier then cuts it into parts no larger than the carrier
 * takes and announces them by a `file-parts` letter, and drops parts within the carrier's daily budget, a [window]
 * ([IN_FLIGHT_BYTES]) at a time. The recipient checks each part against its SHA-256, writes it into place in
 * `<peer>/incoming/` and tells which it has by `file-got` letters; a part unconfirmed after [RESEND_AFTER_MS] and gone
 * from the friend's box is dropped again. With all parts there the file is opened, measured against the letter, kept
 * in `<peer>/received/<sender>/` and answered with `file-done`.
 */
class Transfers(
    private val db: PeerDatabase,
    private val me: PeerIdentity,
    peerDir: Path,
    private val clock: () -> Long,
    private val window: Long = IN_FLIGHT_BYTES,
) {
    /** What one pass did. [letters]: letters queued (the link sends them at once); [budgetUntil]: parts wait for it. */
    data class Pass(
        val partsIn: Int = 0,
        val partsOut: Int = 0,
        val filesIn: Int = 0,
        val letters: Int = 0,
        val budgetUntil: Long? = null,
    ) {
        val moved get() = partsIn + partsOut + filesIn + letters > 0
    }

    private val log = LoggerFactory.getLogger(Transfers::class.java)
    private val tmp = peerDir.resolve("tmp")
    private val outgoing = peerDir.resolve("outgoing")
    private val incoming = peerDir.resolve("incoming")
    private val received: Path = peerDir.resolve("received")

    /** Until when parts wait for the carrier's daily budget; shown in «Друзья». */
    @Volatile
    var budgetUntil: Long? = null
        private set

    init {
        // Half-sealed uploads and half-opened files of a run that stopped (the latter are plaintext), and sealed files
        // whose record never got written.
        runCatching { tmp.listDirectoryEntries().forEach { it.deleteIfExists() } }
        val known = db.outIds()
        runCatching {
            outgoing.listDirectoryEntries("*.sealed").filter { it.name.removeSuffix(".sealed") !in known }.forEach { it.deleteIfExists() }
        }
        val kept = db.announceKeptParts(clock())
        if (kept > 0) log.info("Files announced by letters kept before this version: {}", kept)
    }

    private fun sealed(id: String): Path = outgoing.resolve("$id.sealed")

    private fun assembling(id: String): Path = incoming.resolve("$id.sealed")

    /** Seals [source] for [contact] straight from the upload, so no plain copy is written; the next pass sends it [via]. */
    fun seal(contact: Contact, name: String, source: InputStream, via: String): String {
        val id = UUID.randomUUID().toString()
        val key = FileSeal.newKey()
        val sealing = tmp.createDirectories().resolve("$id.sealing")
        try {
            val measured = FileSeal.seal(source, sealing, key, id)
            val target = sealed(id).also { outgoing.createDirectories() }
            Files.move(sealing, target, StandardCopyOption.ATOMIC_MOVE)
            db.addFileOut(contact.id, SharedFile(id, name, measured.size, measured.sha256, b64(key)), via, target.fileSize(), clock())
            return id
        } finally {
            sealing.deleteIfExists()
        }
    }

    /** Something to do [via] a carrier: parts to send or to take in (a file that failed for good waits for nothing). */
    fun busy(via: String): Boolean = db.outInFlight(via).any { it.lastError == null } ||
        db.pendingIn(via).any { it.lastError == null } ||
        db.doneNotRemoved(via).isNotEmpty()

    /** One pass on [carrier] for the files that go [via] it; [account] is what its daily budget is counted against. */
    fun pass(carrier: Carrier, via: String, account: String): Pass {
        val contacts = db.contacts().associateBy { it.id }
        val (partsIn, told) = take(carrier, via, contacts)
        val (filesIn, done) = finish(via, contacts)
        val announced = cut(carrier, via, contacts)
        val partsOut = send(carrier, via, account, contacts)
        tidy(via)
        return Pass(partsIn, partsOut, filesIn, told + done + announced, budgetUntil)
    }

    private fun take(carrier: Carrier, via: String, contacts: Map<String, Contact>): Pair<Int, Int> {
        val pending = db.pendingIn(via).filter { it.parts != null && it.contact in contacts }.associateBy { it.id }
        if (pending.isEmpty()) return 0 to 0
        val box = Address.parts(me.card)
        val have = mutableMapOf<String, MutableSet<Int>>()
        val done = mutableListOf<Dropped>()
        var taken = 0
        for (item in carrier.waiting(box)) {
            val id = item.name.substringBeforeLast('.')
            val n = item.name.substringAfterLast('.').toIntOrNull() ?: continue
            val file = pending[id]
            if (file == null) {
                if (db.receivedIn(id)) done += item // a part sent twice of a file already kept
                continue // otherwise its announcement may still be on its way
            }
            val got = have.getOrPut(id) { db.partsIn(id).toMutableSet() }
            if (n in got) {
                done += item
                continue
            }
            val bytes = carrier.read(box, item)
            if (!fits(file, n, bytes)) {
                // A whole carrier brought it damaged: removed, so the sender drops it again. A synced folder may
                // still be bringing it, so there it waits.
                if (carrier.traits.whole) {
                    log.warn("Part {} of file {} doesn't match its letter ({} bytes); removed", n, id, bytes.size)
                    done += item
                }
                continue
            }
            write(file, n, bytes)
            db.partIn(id, n, clock())
            got += n
            done += item
            taken++
        }
        carrier.remove(box, done)
        val told = pending.values.count { have.containsKey(it.id) && db.tellParts(it, clock()) > 0 }
        return taken to told
    }

    private fun fits(file: FileIn, n: Int, bytes: ByteArray): Boolean {
        val parts = file.parts ?: return false
        if (n !in parts.indices) return false
        val length = if (n == parts.lastIndex) file.sealedSize!! - n * file.partSize!! else file.partSize!!
        return bytes.size.toLong() == length && sha256(bytes).hex() == parts[n]
    }

    /** Into its place in the file being put together, on the disk before it counts as got. */
    private fun write(file: FileIn, n: Int, bytes: ByteArray) {
        RandomAccessFile(assembling(file.id).also { incoming.createDirectories() }.toFile(), "rw").use {
            it.seek(n * file.partSize!!)
            it.write(bytes)
            it.fd.sync()
        }
    }

    private fun finish(via: String, contacts: Map<String, Contact>): Pair<Int, Int> {
        var kept = 0
        for (file in db.pendingIn(via)) {
            val parts = file.parts ?: continue
            val sender = contacts[file.contact] ?: continue
            if (file.got < parts.size || file.lastError != null) continue
            val opened = tmp.createDirectories().resolve("${file.id}.opening")
            try {
                val measured = FileSeal.open(assembling(file.id), opened, b64d(file.key), file.id)
                if (measured.size != file.size || measured.sha256 != file.sha256) {
                    db.markInError(file.id, "the file doesn't match its letter (${measured.size} bytes)")
                    continue
                }
                db.markReceived(file, keep(received, sender, file.name, opened), clock(), tell = true)
                assembling(file.id).deleteIfExists()
                kept++
            } catch (e: Exception) {
                log.warn("File {} from {} didn't open", file.id, sender.name, e)
                db.markInError(file.id, e.message ?: e.javaClass.simpleName)
            } finally {
                opened.deleteIfExists()
            }
        }
        return kept to kept
    }

    /** Files sealed and not yet announced: cut for [carrier] and announced. */
    private fun cut(carrier: Carrier, via: String, contacts: Map<String, Contact>): Int {
        var announced = 0
        for (file in db.outInFlight(via)) {
            if (file.parts != null || file.contact !in contacts) continue
            val path = sealed(file.id)
            val key = db.fileKey(file.id)
            if (!path.exists() || key == null) {
                db.markOutError(file.id, "the sealed file is gone")
                continue
            }
            val partSize = minOf(carrier.traits.maxItem, MAX_PART)
            val sealedSize = path.fileSize()
            val hashes = buildList {
                RandomAccessFile(path.toFile(), "r").use { f ->
                    var at = 0L
                    while (at < sealedSize) {
                        add(sha256(read(f, at, minOf(partSize, sealedSize - at))).hex())
                        at += partSize
                    }
                }
            }
            val letter = PartedFile(file.id, file.name, file.size, file.sha256, key, sealedSize, partSize, hashes, via)
            db.cutFileOut(file, partSize, hashes, fileJson.encodeToString(letter), clock())
            announced++
        }
        return announced
    }

    private fun send(carrier: Carrier, via: String, account: String, contacts: Map<String, Contact>): Int {
        budgetUntil = null
        val now = clock()
        val files = db.outInFlight(via).filter { it.parts != null && it.lastError == null && it.contact in contacts }
        val sent = files.associateWith { db.partsOut(it.id).associateBy(PartOut::n) }
        var inFlight = sent.entries.sumOf { (file, parts) -> parts.values.filter { it.gotAt == null }.sumOf { length(file, it.n) } }
        var dropped = 0
        for ((file, parts) in sent) {
            val friend = contacts.getValue(file.contact)
            val box = Address.parts(friend.card)
            val lost = parts.values.filter { it.gotAt == null && it.droppedAt < now - RESEND_AFTER_MS }
            val waiting = if (lost.isNotEmpty()) carrier.names(box) else emptySet()
            for (n in file.parts!!.indices) {
                val part = parts[n]
                val again = part != null && part.gotAt == null && part.droppedAt < now - RESEND_AFTER_MS && "${file.id}.$n" !in waiting
                if (part != null && !again) continue
                val size = length(file, n)
                if (!again && inFlight > 0 && inFlight + size > window) return dropped
                if (!withinBudget(carrier, account, size, now)) return dropped
                val bytes = RandomAccessFile(sealed(file.id).toFile(), "r").use { read(it, n * file.partSize!!, size) }
                carrier.drop(box, "${file.id}.$n", bytes)
                db.partDropped(file.id, n, clock())
                db.spend(account, size, clock())
                if (!again) inFlight += size
                dropped++
            }
        }
        return dropped
    }

    private fun withinBudget(carrier: Carrier, account: String, length: Long, now: Long): Boolean {
        val budget = carrier.traits.dailyBudget ?: return true
        if (db.spent(account, now - DAY_MS) + length <= budget) return true
        budgetUntil = (db.firstSpent(account, now - DAY_MS) ?: now) + DAY_MS
        return false
    }

    private fun length(file: FileOut, n: Int): Long =
        if (n == file.parts!!.lastIndex) file.sealedSize!! - n * file.partSize!! else file.partSize!!

    private fun read(f: RandomAccessFile, at: Long, length: Long): ByteArray = ByteArray(length.toInt()).also {
        f.seek(at)
        f.readFully(it)
    }

    /** The sealed copies of files the friend has, and their keys, are no longer needed. */
    private fun tidy(via: String) {
        for (file in db.doneNotRemoved(via)) {
            sealed(file.id).deleteIfExists()
            db.markRemoved(file.id, clock())
        }
    }

    companion object {
        /** Parts are kept to a size every provider takes in one message, whatever a carrier could take. */
        const val MAX_PART = 8L * 1024 * 1024

        /** Parts dropped and not yet confirmed, at most: an absent friend's box doesn't fill up with a whole file. */
        const val IN_FLIGHT_BYTES = 64L * 1024 * 1024

        /** A part unconfirmed this long and gone from the friend's box is dropped again. */
        const val RESEND_AFTER_MS = 30 * 60_000L
    }
}
