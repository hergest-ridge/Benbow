package app.benbow.client

import app.benbow.core.api.SpeechModelFile
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SpeechModelStoreTest {
    private val encoder = Random(1).nextBytes(10_000)
    private val tokens = "<blk> 0\n".toByteArray()
    private val files = listOf(
        SpeechModelFile("encoder.int8.onnx", encoder.size.toLong(), sha256(encoder)),
        SpeechModelFile("tokens.txt", tokens.size.toLong(), sha256(tokens)),
    )

    @Test
    fun waitsForTheServerThenResumesAcrossDropsAndChecksHashes() = runTest {
        val server = FakeServer()
        val api = FakeApi(server).apply { speechChunk = 3_000 } // every request drops after 3 KB
        val dir = Files.createTempDirectory("benbow-model").toFile()
        val store = SpeechModelStore(dir, { api }, files)
        assertEquals(SpeechModelStore.State.Missing, store.state.value)

        store.fetch()
        assertEquals(SpeechModelStore.State.NotOnServer, store.state.value, "the owner hasn't downloaded it on the server")

        server.speechFiles += mapOf("encoder.int8.onnx" to encoder, "tokens.txt" to tokens)
        store.fetch()
        assertEquals(SpeechModelStore.State.Ready, store.state.value)
        assertContentEquals(encoder, store.file("encoder.int8.onnx").readBytes())
        assertEquals(
            SpeechModelStore.State.Ready,
            SpeechModelStore(dir, {
                error("no network needed")
            }, files).state.value,
            "kept after a restart",
        )
    }

    @Test
    fun aFileThatDoesNotMatchThePinnedHashIsThrownAway() = runTest {
        val server = FakeServer().apply {
            speechFiles["encoder.int8.onnx"] = Random(9).nextBytes(10_000) // the right size, other bytes
            speechFiles["tokens.txt"] = tokens
        }
        val dir = Files.createTempDirectory("benbow-model").toFile()
        val store = SpeechModelStore(dir, { FakeApi(server) }, files)

        store.fetch()

        assertIs<SpeechModelStore.State.Failed>(store.state.value)
        assertFalse(store.file("encoder.int8.onnx").exists())
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") }, "the bad download is gone")
    }
}
