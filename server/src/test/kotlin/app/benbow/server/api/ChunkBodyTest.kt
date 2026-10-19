package app.benbow.server.api

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class ChunkBodyTest {
    private val bytes = ByteArray(100) { it.toByte() }

    @Test
    fun aWholeChunkIsReadIntoMemory() = runBlocking<Unit> {
        val body = ByteReadChannel(bytes).readChunk(declared = 100, limit = 100)
        assertContentEquals(bytes, assertIs<ChunkBody.Complete>(body).bytes)
        assertIs<ChunkBody.Complete>(ByteReadChannel(bytes).readChunk(declared = null, limit = 100), "no Content-Length")
    }

    @Test
    fun aCutOffChunkIsDropped() = runBlocking<Unit> {
        assertEquals(ChunkBody.Incomplete, ByteReadChannel(bytes).readChunk(declared = 345_178, limit = 1_000_000))
    }

    @Test
    fun aChunkThatStopsArrivingIsGivenUp() = runBlocking<Unit> {
        val open = ByteChannel().apply {
            writeFully(bytes)
            flush()
        }
        assertEquals(ChunkBody.Stalled, open.readChunk(declared = 345_178, limit = 1_000_000, idle = 100.milliseconds))
    }

    @Test
    fun aChunkOverTheLimitIsRefused() = runBlocking<Unit> {
        assertEquals(ChunkBody.TooLarge, ByteReadChannel(bytes).readChunk(declared = 100, limit = 99), "announced")
        assertEquals(ChunkBody.TooLarge, ByteReadChannel(bytes).readChunk(declared = null, limit = 99), "as it arrives")
    }
}
