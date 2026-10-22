package app.benbow.server.offsite

import com.exceptionfactory.jagged.RecipientStanzaReader
import com.exceptionfactory.jagged.RecipientStanzaWriter
import com.exceptionfactory.jagged.framework.stream.StandardDecryptingChannelFactory
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory
import com.exceptionfactory.jagged.scrypt.ScryptRecipientStanzaReaderFactory
import com.exceptionfactory.jagged.scrypt.ScryptRecipientStanzaWriterFactory
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.deleteIfExists

/**
 * Files of the copy outside the house (ADR-043, as built 7.7 E1): age v1, binary, to the store's X25519 recipient. A
 * file is written under a temporary name, synced, decrypted back with the key and compared by SHA-256, and only then
 * moved to its name (data-safety "Off-site copy"): a cut or a bad write never leaves a file that looks done.
 */
object AgeFiles {
    private const val CHUNK = 65_536

    /** Encrypts [source] into [target]; the SHA-256 of [source] as it was read. */
    fun encrypt(source: Path, target: Path, key: OffsiteKey): String = Files.newByteChannel(source).use { encrypt(it, target, key) }

    /** Encrypts [bytes] into [target]; their SHA-256. */
    fun encrypt(bytes: ByteArray, target: Path, key: OffsiteKey): String = encrypt(Channels.newChannel(bytes.inputStream()), target, key)

    /** The SHA-256 of what [file] decrypts to; throws when it doesn't decrypt whole (another key, a cut, a changed byte). */
    fun sha256(file: Path, key: OffsiteKey): String = Files.newByteChannel(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        copy(decrypting(input, key), Channels.newChannel(ByteArrayOutputStream.nullOutputStream()), digest)
        HexFormat.of().formatHex(digest.digest())
    }

    /** Decrypts [source] into [target] through a synced temporary file and one atomic move; the SHA-256 of the result. */
    fun decrypt(source: Path, target: Path, key: OffsiteKey): String {
        val tmp = target.resolveSibling("${target.fileName}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newByteChannel(source).use { input ->
                FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE).use { out ->
                    copy(decrypting(input, key), out, digest)
                    out.force(true)
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return HexFormat.of().formatHex(digest.digest())
        } finally {
            tmp.deleteIfExists()
        }
    }

    /** A small file, decrypted whole in memory. */
    fun decrypt(bytes: ByteArray, key: OffsiteKey): ByteArray = readAll(decrypting(Channels.newChannel(bytes.inputStream()), key))

    private fun encrypt(input: ReadableByteChannel, target: Path, key: OffsiteKey): String {
        val tmp = target.resolveSibling("${target.fileName}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE).use { file ->
                // Closing the age channel writes the last chunk; the file stays open for the sync after it.
                encrypting(KeptOpen(file), X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(key.recipient)).use { out ->
                    copy(input, out, digest)
                }
                file.force(true)
            }
            val sha = HexFormat.of().formatHex(digest.digest())
            if (sha256(tmp, key) != sha) throw IOException("${tmp.fileName} didn't decrypt back to what was written")
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return sha
        } finally {
            tmp.deleteIfExists()
        }
    }

    /** age with an scrypt recipient: how `key.age` is sealed (the only file the passphrase opens). */
    internal fun encryptToPassphrase(bytes: ByteArray, passphrase: ByteArray, workFactor: Int): ByteArray {
        val out = ByteArrayOutputStream()
        encrypting(Channels.newChannel(out), ScryptRecipientStanzaWriterFactory.newRecipientStanzaWriter(passphrase, workFactor)).use {
            copy(Channels.newChannel(bytes.inputStream()), it)
        }
        return out.toByteArray()
    }

    internal fun decryptWithPassphrase(bytes: ByteArray, passphrase: ByteArray): ByteArray = readAll(
        decrypting(Channels.newChannel(bytes.inputStream()), ScryptRecipientStanzaReaderFactory.newRecipientStanzaReader(passphrase)),
    )

    private fun encrypting(out: WritableByteChannel, writer: RecipientStanzaWriter) =
        StandardEncryptingChannelFactory().newEncryptingChannel(out, listOf(writer))

    private fun decrypting(input: ReadableByteChannel, key: OffsiteKey) =
        decrypting(input, X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(key.secret))

    private fun decrypting(input: ReadableByteChannel, reader: RecipientStanzaReader) =
        StandardDecryptingChannelFactory().newDecryptingChannel(input, listOf(reader))

    private fun readAll(input: ReadableByteChannel): ByteArray = ByteArrayOutputStream().also {
        copy(input, Channels.newChannel(it))
    }.toByteArray()

    private fun copy(input: ReadableByteChannel, output: WritableByteChannel, digest: MessageDigest? = null) {
        val buffer = ByteBuffer.allocate(CHUNK)
        while (input.read(buffer) != -1) {
            buffer.flip()
            digest?.update(buffer.duplicate())
            while (buffer.hasRemaining()) output.write(buffer)
            buffer.clear()
        }
    }

    /** Passes writes through; closing it leaves the file open. */
    private class KeptOpen(private val file: FileChannel) : WritableByteChannel {
        override fun write(src: ByteBuffer) = file.write(src)

        override fun isOpen() = file.isOpen

        override fun close() = Unit
    }
}
