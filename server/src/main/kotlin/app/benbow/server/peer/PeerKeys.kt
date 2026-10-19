package app.benbow.server.peer

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * This server towards friends' servers (ADR-039): an Ed25519 key that signs its letters and an X25519 key that letters
 * to it are sealed to. Created once and kept in `<data>/peer/identity.json`; losing it means exchanging cards again.
 */
class PeerIdentity private constructor(private val signSeed: ByteArray, private val boxPrivate: ByteArray) {
    private val signPair = Ed25519Sign.KeyPair.newKeyPairFromSeed(signSeed)

    val card = PeerCard(signPair.publicKey, X25519.publicFromPrivate(boxPrivate))

    fun sign(data: ByteArray): ByteArray = Ed25519Sign(signPair.privateKey).sign(data)

    internal fun boxPrivateKey(): ByteArray = boxPrivate.copyOf()

    @Serializable
    private class Stored(val v: Int, val sign: String, val box: String)

    companion object {
        const val FILE = "identity.json"

        fun generate(): PeerIdentity = PeerIdentity(Ed25519Sign.KeyPair.newKeyPair().privateKey, X25519.generatePrivateKey())

        /** Reads `<dir>/identity.json`, or creates it (written to a temporary file, synced, then moved into place). */
        fun ensure(dir: Path): PeerIdentity {
            val file = dir.createDirectories().resolve(FILE)
            if (file.exists()) {
                val stored = Json.decodeFromString<Stored>(file.readText())
                check(stored.v == 1) { "$file: unknown version ${stored.v}" }
                return PeerIdentity(b64d(stored.sign), b64d(stored.box))
            }
            val created = generate()
            val tmp = dir.resolve("$FILE.tmp")
            FileOutputStream(tmp.toFile()).use { out ->
                out.write(Json.encodeToString(Stored(1, b64(created.signSeed), b64(created.boxPrivate))).toByteArray())
                out.fd.sync()
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
            return created
        }
    }
}

/**
 * What a friend keeps of a server: its two public keys. Travels as text (`benbow1.` + base64url of both keys and a
 * checksum, so a mistyped card is refused) and is compared in person by [fingerprint].
 */
class PeerCard(val signKey: ByteArray, val boxKey: ByteArray) {
    init {
        require(signKey.size == KEY_SIZE && boxKey.size == KEY_SIZE) { "a card holds two 32-byte keys" }
    }

    /** Names the server in envelopes and folder names. */
    val id: String = sha256(signKey).copyOf(16).hex()

    /** Five groups of four, read aloud or side by side when the cards are exchanged. */
    val fingerprint: String = sha256(signKey + boxKey).copyOf(10).hex().uppercase().chunked(4).joinToString(" ")

    fun encode(): String = PREFIX + b64url(signKey + boxKey + checksum(signKey + boxKey))

    override fun equals(other: Any?) = other is PeerCard && other.signKey.contentEquals(signKey) && other.boxKey.contentEquals(boxKey)

    override fun hashCode() = id.hashCode()

    companion object {
        const val PREFIX = "benbow1."
        private const val KEY_SIZE = 32
        private const val CHECKSUM_SIZE = 4

        /** Reads a card pasted or typed in; spaces and line breaks are ignored. Throws with a message for a person. */
        fun decode(text: String): PeerCard {
            val clean = text.filterNot(Char::isWhitespace)
            require(clean.startsWith(PREFIX)) { "это не карточка Benbow: она начинается с $PREFIX" }
            val bytes = runCatching { Base64.getUrlDecoder().decode(clean.removePrefix(PREFIX)) }.getOrNull()
            require(bytes != null && bytes.size == 2 * KEY_SIZE + CHECKSUM_SIZE) { "карточка обрезана или в ней лишние знаки" }
            val keys = bytes.copyOf(2 * KEY_SIZE)
            require(checksum(keys).contentEquals(bytes.copyOfRange(2 * KEY_SIZE, bytes.size))) { "в карточке опечатка" }
            return PeerCard(keys.copyOf(KEY_SIZE), keys.copyOfRange(KEY_SIZE, 2 * KEY_SIZE))
        }

        private fun checksum(keys: ByteArray) = sha256("benbow-card".toByteArray() + keys).copyOf(CHECKSUM_SIZE)
    }
}

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

internal fun b64d(text: String): ByteArray = Base64.getDecoder().decode(text)

private fun b64url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
