package app.benbow.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun blockEdges() {
        // 55, 56 and 64 bytes are where the padding spills into another block.
        assertEquals("9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318", Sha256.hex(ByteArray(55) { 'a'.code.toByte() }))
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", Sha256.hex(ByteArray(56) { 'a'.code.toByte() }))
        assertEquals("ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb", Sha256.hex(ByteArray(64) { 'a'.code.toByte() }))
        Random(1).nextBytes(100_000).let { assertEquals(64, Sha256.hex(it).length) }
    }
}
