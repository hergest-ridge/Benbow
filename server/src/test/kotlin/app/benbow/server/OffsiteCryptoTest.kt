package app.benbow.server

import app.benbow.server.offsite.AgeFiles
import app.benbow.server.offsite.OffsiteKey
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.util.HexFormat
import java.util.zip.InflaterInputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The age layer of the copy outside the house (ADR-043, E1): the key, its code and passphrase, files and their checks. */
class OffsiteCryptoTest {
    private val dir = Files.createTempDirectory("benbow-offsite")
    private val key = OffsiteKey.generate()

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun aKeyIsAnAgeIdentityWhoseCodeReadsBackHoweverItIsTyped() {
        assertTrue(key.secret.startsWith("AGE-SECRET-KEY-1") && key.secret.length == 74, key.secret)
        assertTrue(key.recipient.startsWith("age1"), key.recipient)
        assertEquals(key, OffsiteKey.parse(key.grouped()))
        assertEquals(key, OffsiteKey.parse(" ${key.secret.lowercase()}\n"))
        assertEquals(key.recipient, OffsiteKey.parse(key.grouped())!!.recipient)
        assertNull(
            OffsiteKey.parse(
                key.secret.dropLast(1) + if (key.secret.last() ==
                    'Q'
                ) {
                    "P"
                } else {
                    "Q"
                },
            ),
            "a mistyped code fails its checksum",
        )
        assertNull(OffsiteKey.parse("hello"))
        assertNotEquals(key, OffsiteKey.generate())
        assertFalse(key.secret in key.toString(), "the secret isn't printed by accident")
    }

    @Test
    fun theKeyFileIsAgeKeygensOwnAndIsNeverReplaced() {
        val file = dir.resolve("keys").resolve("offsite-key.txt")
        key.save(file)
        val text = file.readText()
        assertTrue("# public key: ${key.recipient}\n${key.secret}\n" in text, text)
        assertEquals(key, OffsiteKey.load(file))
        assertFailsWith<IllegalStateException> { OffsiteKey.generate().save(file) }
        assertEquals(text, file.readText())
        assertEquals(listOf("offsite-key.txt"), file.parent.listDirectoryEntries().map { it.fileName.toString() })
        assertNull(OffsiteKey.load(dir.resolve("none.txt")))
    }

    @Test
    fun filesOfEverySizeComeBackAsTheyWere() {
        for (size in listOf(0, 1, 65_535, 65_536, 65_537, 300_000)) {
            val bytes = Random(size).nextBytes(size)
            val sealed = dir.resolve("f$size.age")
            assertEquals(sha(bytes), AgeFiles.encrypt(bytes, sealed, key))
            assertTrue(sealed.readBytes().copyOf(40).toString(Charsets.US_ASCII).startsWith("age-encryption.org/v1\n-> X25519 "))
            assertEquals(sha(bytes), AgeFiles.sha256(sealed, key))
            val back = dir.resolve("f$size.bin")
            assertEquals(sha(bytes), AgeFiles.decrypt(sealed, back, key))
            assertContentEquals(bytes, back.readBytes())
            assertContentEquals(bytes, AgeFiles.decrypt(sealed.readBytes(), key))
        }
        val source = dir.resolve("photo.jpg").also { it.writeBytes(Random(7).nextBytes(200_000)) }
        assertEquals(sha(source.readBytes()), AgeFiles.encrypt(source, dir.resolve("photo.age"), key))
        assertTrue(dir.listDirectoryEntries("*.part").isEmpty(), "no temporary file is left")
    }

    @Test
    fun aChangedByteAnotherKeyOrACutIsRefusedAndLeavesNothing() {
        val bytes = Random(1).nextBytes(150_000)
        val sealed = dir.resolve("x.age").also { AgeFiles.encrypt(bytes, it, key) }
        val good = sealed.readBytes()
        val changed = dir.resolve("changed.age").also {
            it.writeBytes(good.copyOf().apply { this[size - 10] = (this[size - 10] + 1).toByte() })
        }
        val cut = dir.resolve("cut.age").also { it.writeBytes(good.copyOf(good.size - 100)) }
        for (file in listOf(changed, cut)) {
            assertFailsWith<Exception> { AgeFiles.sha256(file, key) }
            val target = dir.resolve("${file.fileName}.out")
            assertFailsWith<Exception> { AgeFiles.decrypt(file, target, key) }
            assertFalse(target.exists())
        }
        assertFailsWith<Exception> { AgeFiles.sha256(sealed, OffsiteKey.generate()) }
        assertTrue(dir.listDirectoryEntries("*.part").isEmpty())
    }

    @Test
    fun theKeySealedWithAPassphraseOpensOnlyWithIt() {
        val passphrase = "Йожик в тумане 1975"
        val sealed = key.seal(passphrase, workFactor = 10)
        assertTrue("\n-> scrypt " in sealed.copyOf(80).toString(Charsets.US_ASCII))
        assertEquals(key, OffsiteKey.unseal(sealed, passphrase))
        assertEquals(
            key,
            OffsiteKey.unseal(sealed, Normalizer.normalize(passphrase, Normalizer.Form.NFD)),
            "the same letters typed elsewhere",
        )
        assertFailsWith<OffsiteKey.WrongPassphrase> { OffsiteKey.unseal(sealed, "йожик в тумане 1975") }
        assertFailsWith<OffsiteKey.WrongPassphrase> { OffsiteKey.unseal(sealed, "") }
        assertNotEquals(sealed.toList(), key.seal(passphrase, workFactor = 10).toList(), "a new salt every time")
    }

    // C2SP/CCTV age vectors (age/testdata, copied as the license allows), produced by the reference implementation.

    private class Vector(val header: Map<String, String>, val body: ByteArray)

    private fun vector(name: String): Vector {
        val bytes = javaClass.getResourceAsStream("/age-vectors/$name")!!.readBytes()
        val end = (0 until bytes.size - 1).first { bytes[it] == '\n'.code.toByte() && bytes[it + 1] == '\n'.code.toByte() }
        val header = bytes.copyOf(end).toString(Charsets.US_ASCII).lines().associate { it.substringBefore(": ") to it.substringAfter(": ") }
        val body = bytes.copyOfRange(end + 2, bytes.size)
        return Vector(header, if (header["compressed"] == "zlib") InflaterInputStream(body.inputStream()).readBytes() else body)
    }

    @Test
    fun theReferenceImplementationsFilesOpenAndItsBrokenOnesDont() {
        val good = listOf("x25519", "x25519_multiple_recipients", "stream_two_chunks", "stream_last_chunk_full", "stream_empty_payload")
        for (name in good) {
            val v = vector(name)
            assertEquals("success", v.header["expect"])
            assertEquals(v.header["payload"], sha(AgeFiles.decrypt(v.body, OffsiteKey.parse(v.header["identity"]!!)!!)), name)
        }
        val bad =
            listOf("x25519_bad_tag", "x25519_no_match", "stream_bad_tag_second_chunk", "stream_trailing_garbage_short", "stream_no_final")
        for (name in bad) {
            val v = vector(name)
            assertNotEquals("success", v.header["expect"])
            assertFailsWith<Exception>(name) { AgeFiles.decrypt(v.body, OffsiteKey.parse(v.header["identity"]!!)!!) }
        }
        val scrypt = vector("scrypt")
        assertEquals(
            scrypt.header["payload"],
            sha(AgeFiles.decryptWithPassphrase(scrypt.body, scrypt.header["passphrase"]!!.toByteArray())),
        )
        assertFailsWith<OffsiteKey.WrongPassphrase> { OffsiteKey.unseal(scrypt.body, "not the password") }
        // Work factor 23 would want 8 GiB: refused before scrypt starts, not an OutOfMemoryError.
        assertFailsWith<IllegalStateException> { OffsiteKey.unseal(vector("scrypt_work_factor_23").body, "password") }
    }
}
