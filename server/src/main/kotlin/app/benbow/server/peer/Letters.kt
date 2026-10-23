package app.benbow.server.peer

import com.google.crypto.tink.ConfigurationV0
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException

/** What one server says to another (ADR-039). Readable only by the recipient once sealed into an [Envelope]. */
@Serializable
data class Letter(
    /** Random; a letter that arrives twice is taken once. */
    val id: String,
    /** 1, 2, 3… per sender and recipient; acknowledgements carry 0 and are never acknowledged themselves. */
    val seq: Long,
    val kind: String,
    val text: String? = null,
    /** For [ACK]: the numbers the sender of this letter has received and kept. */
    val acks: List<Long> = emptyList(),
    val sentAt: Long,
    /** The sender's Benbow version (servers before 0.14.1 leave it out; older ones skip it): «Друзья» shows it. */
    val server: String? = null,
) {
    companion object {
        const val NOTE = "note"
        const val ACK = "ack"

        /** [text] is a [SharedFile] as JSON: the body waits in the Drive folder. */
        const val FILE = "file"

        /** [text] is the id of a file whose body arrived and matched. */
        const val FILE_DONE = "file-done"
    }
}

/**
 * A sealed letter as it travels: who it's from and to (card ids), the HPKE ciphertext for the recipient, and the
 * sender's Ed25519 signature over all of that. A carrier sees these fields and nothing else.
 */
@Serializable
data class Envelope(val v: Int, val from: String, val to: String, val sealed: String, val sig: String)

/** The result of opening an envelope that arrived. */
sealed interface Opened {
    data class Ok(val from: PeerCard, val letter: Letter) : Opened

    /** From a server that isn't a contact (yet): left where it is until its card is added. */
    data class Unknown(val from: String) : Opened

    /**
     * In a format newer than this server knows (a friend updated first): left where it is until this one is updated,
     * never refused and removed, so nothing goes round and nothing is lost.
     */
    data class Newer(val from: String, val version: Int) : Opened

    /** Addressed to someone else, forged, damaged or of a format this server doesn't know. */
    data class Refused(val reason: String) : Opened
}

object Letters {
    /**
     * The envelope's and the letter's format. Optional fields and new kinds keep it (an older server keeps a letter of a
     * kind it doesn't know in its inbox, for the code that will); anything an older server would misread raises it.
     */
    const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    private val hpke: HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    fun seal(from: PeerIdentity, to: PeerCard, letter: Letter): String {
        val info = info(from.card.id, to.id)
        val sealed = encrypter(to).encrypt(json.encodeToString(letter).toByteArray(), info)
        val sig = from.sign(info + sealed)
        return json.encodeToString(Envelope(VERSION, from.card.id, to.id, b64(sealed), b64(sig)))
    }

    /** Checks the signature against the sender's card before anything is decrypted. */
    fun open(me: PeerIdentity, text: String, contact: (String) -> PeerCard?): Opened {
        val envelope = runCatching { json.decodeFromString<Envelope>(text) }.getOrNull()
            ?: return Opened.Refused("not an envelope")
        if (envelope.v > VERSION) return Opened.Newer(envelope.from, envelope.v)
        if (envelope.v != VERSION) return Opened.Refused("envelope version ${envelope.v}")
        if (envelope.to != me.card.id) return Opened.Refused("addressed to ${envelope.to}")
        val sender = contact(envelope.from) ?: return Opened.Unknown(envelope.from)
        val info = info(envelope.from, envelope.to)
        val sealed = runCatching { b64d(envelope.sealed) }.getOrNull() ?: return Opened.Refused("damaged")
        try {
            Ed25519Verify(sender.signKey).verify(b64d(envelope.sig), info + sealed)
        } catch (e: GeneralSecurityException) {
            return Opened.Refused("bad signature")
        } catch (e: IllegalArgumentException) {
            return Opened.Refused("damaged signature")
        }
        val plain = try {
            decrypter(me).decrypt(sealed, info)
        } catch (e: GeneralSecurityException) {
            return Opened.Refused("can't decrypt")
        }
        val letter = runCatching { json.decodeFromString<Letter>(plain.decodeToString()) }.getOrNull()
            ?: return Opened.Refused("not a letter")
        return Opened.Ok(sender, letter)
    }

    /** Binds the ciphertext to its sender and recipient (HPKE's info), and is signed with it. */
    private fun info(from: String, to: String) = "benbow-letter/$VERSION|$from|$to".toByteArray()

    private fun publicKey(card: PeerCard) = HpkePublicKey.create(hpke, Bytes.copyFrom(card.boxKey), null)

    private fun encrypter(to: PeerCard): HybridEncrypt = KeysetHandle.newBuilder()
        .addEntry(KeysetHandle.importKey(publicKey(to)).withRandomId().makePrimary())
        .build()
        .getPrimitive(ConfigurationV0.get(), HybridEncrypt::class.java)

    private fun decrypter(me: PeerIdentity): HybridDecrypt {
        val key = HpkePrivateKey.create(publicKey(me.card), SecretBytes.copyFrom(me.boxPrivateKey(), InsecureSecretKeyAccess.get()))
        return KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
            .getPrimitive(ConfigurationV0.get(), HybridDecrypt::class.java)
    }
}
