package app.benbow.server.offsite

import com.exceptionfactory.jagged.x25519.X25519KeyFactory
import com.exceptionfactory.jagged.x25519.X25519KeyPairGenerator
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * The key of the copy outside the house (ADR-043, as built 7.7 E1): an age X25519 identity made by the server. Its
 * secret (`AGE-SECRET-KEY-1…`) is the recovery code; its recipient (`age1…`) is all that encrypting needs. At home it
 * is a key file in age-keygen's own format, so the stock `age -d -i <file>` opens the copy with it; outside, the code
 * or `key.age`, this same file sealed with the owner's passphrase.
 */
class OffsiteKey private constructor(val secret: String) {
    val recipient: String = X25519KeyFactory().translateKey(SecretKeySpec(secret.toByteArray(Charsets.US_ASCII), "X25519")).toString()

    /** The key file: age-keygen's format. */
    fun text(created: Instant = Instant.now()): String =
        "# created: ${created.truncatedTo(ChronoUnit.SECONDS)}\n# public key: $recipient\n$secret\n"

    /** The code for writing down: its 58 characters after the prefix in groups of four ([parse] reads it either way). */
    fun grouped(): String = PREFIX + " " + secret.removePrefix(PREFIX).chunked(4).joinToString(" ")

    /** Writes the key file through a synced temporary file and one atomic move; an existing file is never replaced. */
    fun save(file: Path) {
        check(!file.exists()) { "$file exists: a copy has one key" }
        file.parent.createDirectories()
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        FileOutputStream(tmp.toFile()).use { out ->
            out.write(text().toByteArray(Charsets.US_ASCII))
            out.fd.sync()
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * `key.age`: the key file sealed with [passphrase] in age's scrypt format, which `age -d -i key.age` opens by asking
     * for it. scrypt at [workFactor] takes 128 × 8 × 2^[workFactor] bytes of memory (256 MiB at age's default 18).
     */
    fun seal(passphrase: String, workFactor: Int = WORK_FACTOR): ByteArray {
        needMemory(workFactor)
        return AgeFiles.encryptToPassphrase(text().toByteArray(Charsets.US_ASCII), passphraseBytes(passphrase), workFactor)
    }

    override fun equals(other: Any?) = other is OffsiteKey && other.secret == secret

    override fun hashCode() = secret.hashCode()

    override fun toString() = "OffsiteKey($recipient)"

    class WrongPassphrase : Exception("the passphrase doesn't open key.age")

    companion object {
        const val PREFIX = "AGE-SECRET-KEY-1"
        const val WORK_FACTOR = 18

        fun generate(): OffsiteKey = OffsiteKey(X25519KeyPairGenerator().generateKeyPair().private.toString())

        /** A code as typed or pasted: spaces, line breaks and case don't matter; null when it isn't a key. */
        fun parse(code: String): OffsiteKey? {
            val secret = code.filterNot(Char::isWhitespace).uppercase()
            if (!secret.startsWith(PREFIX) || secret.length != PREFIX.length + 58) return null
            return runCatching { OffsiteKey(secret) }.getOrNull()
        }

        /** The key file written by [save] (or by age-keygen); null when there is none. */
        fun load(file: Path): OffsiteKey? {
            if (!file.exists()) return null
            val line = file.readLines().map(String::trim).firstOrNull { it.startsWith(PREFIX) }
            return checkNotNull(line?.let(::parse)) { "$file holds no age key" }
        }

        /** Opens `key.age` with [passphrase]; [WrongPassphrase] when it doesn't. */
        fun unseal(sealed: ByteArray, passphrase: String): OffsiteKey {
            val header = sealed.copyOf(minOf(sealed.size, 200)).toString(Charsets.US_ASCII)
            Regex("""-> scrypt \S+ (\d+)""").find(header)?.groupValues?.get(1)?.toIntOrNull()?.let(::needMemory)
            val text = try {
                AgeFiles.decryptWithPassphrase(sealed, passphraseBytes(passphrase))
            } catch (e: java.security.GeneralSecurityException) {
                throw WrongPassphrase().initCause(e)
            }
            val line = text.toString(Charsets.US_ASCII).lines().map(String::trim).firstOrNull { it.startsWith(PREFIX) }
            return line?.let(::parse) ?: throw WrongPassphrase()
        }

        /** What is typed, in one Unicode form, so the same letters always make the same bytes. */
        private fun passphraseBytes(passphrase: String) = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

        /** scrypt's table must fit with room to spare; else a clear refusal instead of an OutOfMemoryError mid-request. */
        private fun needMemory(workFactor: Int) {
            val table = 1024L shl workFactor
            check(Runtime.getRuntime().maxMemory() >= table + 256L * 1024 * 1024) {
                "Not enough memory for scrypt at work factor $workFactor (${table shr 20} MiB): the server runs with " +
                    "${Runtime.getRuntime().maxMemory() shr 20} MiB"
            }
        }
    }
}
