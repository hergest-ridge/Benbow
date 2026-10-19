package app.benbow.server.peer

import com.google.crypto.tink.ConfigurationV0
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.util.SecretBytes
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * A file sent to a friend (ADR-039): its body goes through the shared Drive folder, sealed with a key of its own; the
 * key, the name and the plaintext's size and SHA-256 travel only inside a letter (the `text` of a [Letter.FILE]).
 */
@Serializable
data class SharedFile(val id: String, val name: String, val size: Long, val sha256: String, val key: String)

/** What sealing or opening a file measured of the plaintext. */
data class Measured(val size: Long, val sha256: String)

/**
 * Streaming AEAD for file bodies (Tink: AES-256-GCM-HKDF, 1 MB segments). The file id is the associated data, so a
 * body can't be passed off as another file. Any damaged or cut-short body fails as it is read.
 */
object FileSeal {
    private val parameters = AesGcmHkdfStreamingParameters.builder()
        .setKeySizeBytes(32)
        .setDerivedAesGcmKeySizeBytes(32)
        .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
        .setCiphertextSegmentSizeBytes(1 shl 20)
        .build()

    fun newKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)

    fun seal(source: Path, target: Path, key: ByteArray, id: String): Measured = source.inputStream().use { seal(it, target, key, id) }

    /** Seals straight from a stream (an upload), so no plain copy of the file is ever written. */
    fun seal(source: InputStream, target: Path, key: ByteArray, id: String): Measured {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        DigestInputStream(source, digest).use { input ->
            target.outputStream().use { file ->
                aead(key).newEncryptingStream(file, id.toByteArray()).use { size = input.copyTo(it) }
                (file as? java.io.FileOutputStream)?.fd?.sync()
            }
        }
        return Measured(size, digest.digest().hex())
    }

    fun open(source: Path, target: Path, key: ByteArray, id: String): Measured {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        source.inputStream().use { file ->
            aead(key).newDecryptingStream(file, id.toByteArray()).use { plain: InputStream ->
                DigestOutputStream(target.outputStream(), digest).use { out: OutputStream -> size = plain.copyTo(out) }
            }
        }
        return Measured(size, digest.digest().hex())
    }

    private fun aead(key: ByteArray): StreamingAead {
        val streamingKey = AesGcmHkdfStreamingKey.create(parameters, SecretBytes.copyFrom(key, InsecureSecretKeyAccess.get()))
        return KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(streamingKey).withRandomId().makePrimary())
            .build()
            .getPrimitive(ConfigurationV0.get(), StreamingAead::class.java)
    }
}

/**
 * The folder the Google Drive desktop app mirrors (ADR-039): sealed bodies wait in `Benbow/<recipient>/<id>.bin`, where
 * `<recipient>` is the start of its card id. The app carries them between the homes; Benbow only reads and writes files.
 */
class DriveFolder(val root: Path) {
    private fun dir(card: PeerCard): Path = root.resolve("Benbow").resolve(card.id.take(12))

    fun bodyFor(card: PeerCard, id: String): Path = dir(card).resolve("$id.bin")

    /** Moves a sealed body in at once where it can, so the app rarely sees half a file (half a file only waits anyway). */
    fun place(sealed: Path, to: PeerCard, id: String): Path {
        val target = bodyFor(to, id)
        dir(to).createDirectories()
        try {
            Files.move(sealed, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(sealed, target)
        }
        return target
    }

    fun find(me: PeerCard, id: String): Path? = bodyFor(me, id).takeIf { it.exists() }

    fun remove(to: PeerCard, id: String) {
        bodyFor(to, id).deleteIfExists()
    }
}

/**
 * Files between servers (ADR-039): sealing one into the Drive folder, and the sweep that takes in bodies that came
 * through (opened, measured against the letter, then kept in `<peer>/received/<sender>/`) and removes ours once the
 * friend has them. A body still syncing fails to open and simply waits for the next sweep.
 */
class FileCarrier(private val db: PeerDatabase, private val me: PeerIdentity, peerDir: Path, private val clock: () -> Long) {
    private val tmp = peerDir.resolve("tmp")
    val received: Path = peerDir.resolve("received")

    fun send(folder: DriveFolder, contact: Contact, name: String, source: InputStream): SharedFile {
        val id = UUID.randomUUID().toString()
        val key = FileSeal.newKey()
        val sealed = tmp.createDirectories().resolve("$id.sealing")
        try {
            val measured = FileSeal.seal(source, sealed, key, id)
            folder.place(sealed, contact.card, id)
            return SharedFile(id, name, measured.size, measured.sha256, b64(key)).also { db.addFileOut(contact.id, it, clock()) }
        } finally {
            sealed.deleteIfExists()
        }
    }

    /** Returns the files taken in now, as (contact id, file id). */
    fun sweep(folder: DriveFolder): List<Pair<String, String>> {
        val contacts = db.contacts().associateBy { it.id }
        val taken = mutableListOf<Pair<String, String>>()
        for (file in db.pendingIn()) {
            val sender = contacts[file.contact] ?: continue
            val body = folder.find(me.card, file.id) ?: continue
            val opened = tmp.createDirectories().resolve("${file.id}.opening")
            try {
                val measured = FileSeal.open(body, opened, b64d(file.key), file.id)
                if (measured.size != file.size || measured.sha256 != file.sha256) {
                    db.markInError(file.id, "the body doesn't match the letter (${measured.size} bytes)")
                    continue
                }
                val target = free(received.resolve(sender.card.id.take(12)).createDirectories(), safeName(file.name))
                Files.move(opened, target)
                db.markReceived(file.id, target, clock())
                runCatching { body.deleteIfExists() } // the sender removes it too once it hears
                taken += sender.id to file.id
            } catch (e: Exception) {
                db.markInError(file.id, e.message ?: e.javaClass.simpleName) // most often: Drive is still bringing it
            } finally {
                opened.deleteIfExists()
            }
        }
        for (out in db.doneNotRemoved()) {
            contacts[out.contact]?.let { runCatching { folder.remove(it.card, out.id) } }
            db.markRemoved(out.id, clock())
        }
        return taken
    }

    private fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trimStart('.').take(120).ifBlank { "file" }

    private fun free(dir: Path, name: String): Path {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var candidate = dir.resolve(name)
        var n = 2
        while (candidate.exists()) candidate = dir.resolve("${name.take(dot)} ($n)${name.drop(dot)}").also { n++ }
        return candidate
    }
}
