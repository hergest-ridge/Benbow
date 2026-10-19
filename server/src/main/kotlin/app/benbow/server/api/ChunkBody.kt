package app.benbow.server.api

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** An upload chunk's body, read whole before the upload is touched. */
internal sealed interface ChunkBody {
    class Complete(val bytes: ByteArray) : ChunkBody

    data object TooLarge : ChunkBody

    /** Ended before its Content-Length: the connection was cut. Nothing is kept. */
    data object Incomplete : ChunkBody

    /** No byte for a while: a connection that died without a word (Wi-Fi gone, socket reset). Nothing is kept. */
    data object Stalled : ChunkBody
}

/** How long a chunk may go without a single byte before the request is given up. */
internal val CHUNK_IDLE = 30.seconds

/**
 * Reads a chunk's body into memory (≤ [limit]). The upload's lock is taken only afterwards, so a connection
 * that dies mid-chunk can't hold it: on 2026-10-19 a phone's socket was reset during a chunk, that request
 * never ended, and every retry of the upload waited behind it until the server restarted (UploadCutOffTest).
 */
internal suspend fun ByteReadChannel.readChunk(declared: Long?, limit: Long, idle: Duration = CHUNK_IDLE): ChunkBody {
    if (declared != null && declared > limit) return ChunkBody.TooLarge
    val out = ByteArrayOutputStream(declared?.toInt() ?: (1 shl 16))
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val n = withTimeoutOrNull(idle) { readAvailable(buffer, 0, buffer.size) } ?: return ChunkBody.Stalled
        if (n < 0) break
        if (out.size() + n > limit) return ChunkBody.TooLarge
        out.write(buffer, 0, n)
    }
    if (declared != null && out.size().toLong() != declared) return ChunkBody.Incomplete
    return ChunkBody.Complete(out.toByteArray())
}
